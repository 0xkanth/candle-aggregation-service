package com.fintech.candles.ingestion;

import com.fintech.candles.aggregation.CandleAggregator;
import com.fintech.candles.config.CandleProperties;
import com.fintech.candles.domain.BidAskEvent;
import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * High-performance event publisher using LMAX Disruptor.
 * 
 * Provides lock-free, low-latency event publishing with:
 * - Ring buffer for pre-allocated event objects
 * - Wait-free publishing (no contention)
 * - Efficient batching
 * - Back-pressure handling
 * 
 * Capable of handling millions of events per second.
 */
@Component
public class DisruptorEventPublisher {
    
    private static final Logger log = LoggerFactory.getLogger(DisruptorEventPublisher.class);
    
    private final CandleAggregator aggregator;
    private final CandleProperties properties;
    private final MeterRegistry meterRegistry;
    
    // Prometheus metrics
    private final AtomicLong ringBufferEventsDropped = new AtomicLong(0);
    
    private Disruptor<BidAskEventWrapper> disruptor;
    private RingBuffer<BidAskEventWrapper> ringBuffer;
    
    public DisruptorEventPublisher(CandleAggregator aggregator, CandleProperties properties, MeterRegistry meterRegistry) {
        this.aggregator = aggregator;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        
        // Register Prometheus gauge for ring buffer drops
        meterRegistry.gauge("disruptor.ringbuffer.events.dropped", ringBufferEventsDropped);
    }
    
    @PostConstruct
    public void start() {
        int bufferSize = properties.getAggregation().getDisruptor().getBufferSize();
        
        // Factory for pre-allocating events in the ring buffer
        EventFactory<BidAskEventWrapper> eventFactory = BidAskEventWrapper::new;
        
        // Thread factory for event handler threads
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setName("disruptor-event-handler-" + counter.incrementAndGet());
                thread.setDaemon(false);
                return thread;
            }
        };
        
        // Create Disruptor with specified wait strategy
        WaitStrategy waitStrategy = createWaitStrategy();
        
        disruptor = new Disruptor<>(
            eventFactory,
            bufferSize,
            threadFactory,
            ProducerType.MULTI,  // Multiple producers (thread-safe publishing)
            waitStrategy
        );
        
        // Connect event handler(s) - can be multiple for parallel processing
        int numConsumers = properties.getAggregation().getDisruptor().getNumConsumers();
        if (numConsumers <= 1) {
            // Single consumer (original behavior)
            disruptor.handleEventsWith(this::handleEvent);
            log.info("Disruptor configured with single consumer thread");
        } else {
            // Multiple parallel consumers - create separate EventHandler instances
            // Each runs on its own thread and processes events in parallel
            @SuppressWarnings("unchecked")
            EventHandler<BidAskEventWrapper>[] handlers = new EventHandler[numConsumers];
            for (int i = 0; i < numConsumers; i++) {
                final int workerId = i;
                handlers[i] = (event, sequence, endOfBatch) -> {
                    if (event.event != null) {
                        aggregator.processEvent(event.event);
                        if (log.isTraceEnabled()) {
                            log.trace("Worker {} processed event at sequence {}", workerId, sequence);
                        }
                    }
                };
            }
            disruptor.handleEventsWith(handlers);
            log.info("Disruptor configured with {} parallel consumer threads", numConsumers);
        }
        
        // Exception handler for production resilience
        disruptor.setDefaultExceptionHandler(new ExceptionHandler<BidAskEventWrapper>() {
            @Override
            public void handleEventException(Throwable ex, long sequence, BidAskEventWrapper event) {
                log.error("Exception processing event at sequence {}: {}", sequence, event.event, ex);
            }
            
            @Override
            public void handleOnStartException(Throwable ex) {
                log.error("Exception during Disruptor startup", ex);
            }
            
            @Override
            public void handleOnShutdownException(Throwable ex) {
                log.error("Exception during Disruptor shutdown", ex);
            }
        });
        
        // Start the disruptor
        ringBuffer = disruptor.start();
        
        log.info("Disruptor started: bufferSize={}, waitStrategy={}", 
                bufferSize, waitStrategy.getClass().getSimpleName());
    }
    
    /**
     * Publishes a bid/ask event to the ring buffer for processing.
     * Non-blocking if buffer has capacity, blocks if buffer is full (back-pressure).
     * 
     * @param event The event to publish
     */
    public void publish(BidAskEvent event) {
        long sequence = ringBuffer.next();  // Claim next slot
        try {
            BidAskEventWrapper wrapper = ringBuffer.get(sequence);
            wrapper.event = event;
        } finally {
            ringBuffer.publish(sequence);  // Make available for consumption
        }
    }
    
    /**
     * Attempts to publish without blocking.
     * Returns false if buffer is full.
     * 
     * @param event The event to publish
     * @return true if published, false if buffer full
     */
    public boolean tryPublish(BidAskEvent event) {
        try {
            long sequence = ringBuffer.tryNext();
            try {
                BidAskEventWrapper wrapper = ringBuffer.get(sequence);
                wrapper.event = event;
                return true;
            } finally {
                ringBuffer.publish(sequence);
            }
        } catch (InsufficientCapacityException e) {
            // Track dropped events due to ring buffer full
            ringBufferEventsDropped.incrementAndGet();
            return false;  // Buffer full
        }
    }
    
    /**
     * Event handler called by Disruptor when events are ready.
     * Runs on dedicated event handler thread(s).
     */
    private void handleEvent(BidAskEventWrapper wrapper, long sequence, boolean endOfBatch) {
        if (wrapper.event != null) {
            aggregator.processEvent(wrapper.event);
            
            // Log batching efficiency occasionally
            if (endOfBatch && log.isTraceEnabled()) {
                log.trace("Processed event at sequence {}, end of batch", sequence);
            }
        }
    }
    
    @PreDestroy
    public void shutdown() {
        if (disruptor != null) {
            log.info("Shutting down Disruptor...");
            disruptor.shutdown();
            log.info("Disruptor shutdown complete");
        }
    }
    
    /**
     * Creates the appropriate wait strategy based on configuration.
     */
    private WaitStrategy createWaitStrategy() {
        String strategy = properties.getAggregation().getDisruptor().getWaitStrategy();
        
        return switch (strategy.toUpperCase()) {
            case "BLOCKING" -> new BlockingWaitStrategy();
            case "SLEEPING" -> new SleepingWaitStrategy();
            case "YIELDING" -> new YieldingWaitStrategy();
            case "BUSY_SPIN" -> new BusySpinWaitStrategy();
            default -> {
                log.warn("Unknown wait strategy: {}, using YIELDING", strategy);
                yield new YieldingWaitStrategy();
            }
        };
    }
    
    /**
     * Wrapper class for events in the ring buffer.
     * Pre-allocated to avoid GC pressure.
     */
    private static class BidAskEventWrapper {
        BidAskEvent event;
    }
    
    // Metrics
    public long getRemainingCapacity() {
        return ringBuffer.remainingCapacity();
    }
    
    public long getBufferSize() {
        return ringBuffer.getBufferSize();
    }
    
    /** Returns total events dropped due to ring buffer full. */
    public long getRingBufferEventsDropped() {
        return ringBufferEventsDropped.get();
    }
}

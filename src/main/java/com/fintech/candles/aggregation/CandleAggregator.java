package com.fintech.candles.aggregation;

import com.fintech.candles.domain.BidAskEvent;
import com.fintech.candles.domain.Candle;
import com.fintech.candles.domain.Interval;
import com.fintech.candles.storage.CandleRepository;
import com.fintech.candles.util.TimeWindowManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lock-free OHLC candle aggregator for multi-interval event processing.
 * Uses CAS-based updates and atomic counters for high throughput.
 */
@Component
public class CandleAggregator {
    
    private static final Logger log = LoggerFactory.getLogger(CandleAggregator.class);
    
    private final CandleRepository repository;
    private final TimeWindowManager timeWindowManager;
    private final MeterRegistry meterRegistry;
    
    // Active candles keyed by "SYMBOL-INTERVAL" (e.g., "BTC-USD-M1")
    // AtomicReference provides lock-free CAS updates
    private final Map<String, AtomicReference<MutableCandle>> activeCandles;
    
    // Prometheus metrics (thread-safe atomic counters)
    private final AtomicLong eventsProcessed = new AtomicLong(0);
    private final AtomicLong candlesCompleted = new AtomicLong(0);
    private final AtomicLong lateEventsDropped = new AtomicLong(0);
    
    public CandleAggregator(
            CandleRepository repository,
            TimeWindowManager timeWindowManager,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.timeWindowManager = timeWindowManager;
        this.meterRegistry = meterRegistry;
        this.activeCandles = new ConcurrentHashMap<>();
        
        meterRegistry.gauge("candle.aggregator.events.processed", eventsProcessed);
        meterRegistry.gauge("candle.aggregator.candles.completed", candlesCompleted);
        meterRegistry.gauge("candle.aggregator.late.events.dropped", lateEventsDropped);
    }
    
    /**
     * Main entry: processes a BidAskEvent and updates all interval candles.
     */
    public void processEvent(BidAskEvent event) {
        // Defensive check: skip invalid events (corrupted data, crossed markets, etc.)
        if (!event.isValid()) {
            log.warn("Invalid event received, skipping: {}", event);
            return;
        }
        
        // Start latency timer (uses System.nanoTime() internally)
        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            // Calculate mid-price: standard reference price for candles
            // Using mid-price (not last trade) because we're aggregating quotes, not trades
            double price = event.midPrice();
            
            // Process for all intervals in single pass (more efficient than separate processors)
            // Each interval may be in different window state (new vs update vs late)
            for (Interval interval : Interval.values()) {
                processForInterval(event.symbol(), interval, price, event.timestamp());
            }
            
            // Increment success counter (atomic operation, thread-safe)
            eventsProcessed.incrementAndGet();
            
        } finally {
            // Stop timer and record latency (even if exception thrown)
            sample.stop(meterRegistry.timer("candle.aggregator.event.processing.time"));
        }
    }
    
    /**
     * Processes an event for a symbol-interval. Handles window rotation and late events.
     */
    private void processForInterval(String symbol, Interval interval, double price, long eventTimestamp) {
        // Create composite key: "BTC-USD-M1", "ETH-USD-H1", etc.
        String key = createKey(symbol, interval);
        
        // Align timestamp to window boundary using integer division
        // Example: 10:37:23.456 → 10:37:00.000 for M1 interval
        long windowStart = timeWindowManager.getWindowStart(eventTimestamp, interval);
        
        // Atomic compute operation: thread-safe read-modify-write
        // Lambda executes under map's internal lock (very brief - just pointer swap)
        activeCandles.compute(key, (k, candleRef) -> {
            // CASE 1: First event for this symbol-interval (candleRef is null)
            // Action: Create new candle and wrap in AtomicReference
            if (candleRef == null) {
                MutableCandle newCandle = new MutableCandle(symbol, interval, windowStart, price);
                log.debug("Started new candle: symbol={}, interval={}, window={}", 
                         symbol, interval, windowStart);
                return new AtomicReference<>(newCandle);
            }
            
            // Get current candle (thread-safe read)
            MutableCandle currentCandle = candleRef.get();
            
            // CASE 2: Event belongs to CURRENT window (most common case - hot path)
            // Action: Update OHLC using CAS for thread safety
            if (currentCandle.windowStart == windowStart) {
                // Lock-free update using CAS (compare-and-swap)
                // If another thread modified candle between get() and updateAndGet(),
                // the lambda retries automatically until success
                candleRef.updateAndGet(candle -> {
                    candle.update(price);  // Mutates high, low, close, volume
                    return candle;
                });
                
            // CASE 3: Event belongs to NEW window (time advanced forward)
            // Action: Persist completed candle, start fresh candle
            } else if (timeWindowManager.isNewWindow(eventTimestamp, currentCandle.windowStart, interval)) {
                // Persist the completed candle to Chronicle Map
                persistCandle(currentCandle);
                
                // Create new candle for the new window
                MutableCandle newCandle = new MutableCandle(symbol, interval, windowStart, price);
                candleRef.set(newCandle);  // Atomic swap
                
                log.debug("Rotated candle: symbol={}, interval={}, old_window={}, new_window={}", 
                         symbol, interval, currentCandle.windowStart, windowStart);
                
            // CASE 4: Event is LATE (belongs to past window)
            // Action: Check tolerance and handle or drop
            } else if (timeWindowManager.isLateEvent(eventTimestamp, currentCandle.windowStart, interval)) {
                // Check if event is within configurable tolerance (default 5 seconds)
                if (timeWindowManager.shouldProcessLateEvent(eventTimestamp, currentCandle.windowStart)) {
                    // Within tolerance: reopen historical candle and update
                    // This is expensive (requires Chronicle Map read-modify-write)
                    handleLateEvent(symbol, interval, price, windowStart);
                } else {
                    // Beyond tolerance: drop event and count it
                    // We don't want to keep historical candles mutable forever
                    lateEventsDropped.incrementAndGet();
                    log.trace("Dropped late event: symbol={}, interval={}, event_window={}, current_window={}, lag={}ms",
                             symbol, interval, windowStart, currentCandle.windowStart,
                             currentCandle.windowStart - eventTimestamp);
                }
            }
            
            // Return existing reference (map entry unchanged)
            return candleRef;
        });
    }
    
    /**
     * Updates persisted candles for late events (within tolerance).
     */
    private void handleLateEvent(String symbol, Interval interval, double price, long windowStart) {
        // Try to find existing candle in Chronicle Map
        repository.findByExactTime(symbol, interval, windowStart).ifPresentOrElse(
            existingCandle -> {
                // Candle exists: create updated version with new price incorporated
                Candle updated = updateCandle(existingCandle, price);
                repository.save(symbol, interval, updated);
                log.debug("Updated late event candle: symbol={}, interval={}, window={}", 
                         symbol, interval, windowStart);
            },
            () -> {
                // Candle doesn't exist yet (very late, before first event for that window)
                // Create new candle with this single price
                Candle newCandle = Candle.of(windowStart, price);
                repository.save(symbol, interval, newCandle);
                log.debug("Created candle for very late event: symbol={}, interval={}, window={}",
                         symbol, interval, windowStart);
            }
        );
    }
    
    /**
     * Returns a new Candle with updated OHLC values.
     */
    private Candle updateCandle(Candle existing, double newPrice) {
        return new Candle(
            existing.time(),              // Time never changes (window start)
            existing.open(),              // Open never changes (first price)
            Math.max(existing.high(), newPrice),  // High = max price seen
            Math.min(existing.low(), newPrice),   // Low = min price seen
            newPrice,                     // Close = latest price
            existing.volume() + 1         // Volume = event count
        );
    }
    
    /**
     * Persists a completed candle to storage.
     */
    private void persistCandle(MutableCandle mutableCandle) {
        // Convert mutable → immutable (creates new Candle record)
        Candle candle = mutableCandle.toImmutableCandle();
        
        // Save to Chronicle Map (off-heap write, memory-mapped file)
        repository.save(mutableCandle.symbol, mutableCandle.interval, candle);
        
        // Increment metrics counter
        candlesCompleted.incrementAndGet();
        
        // Trace logging (only if TRACE level enabled - zero cost if disabled)
        if (log.isTraceEnabled()) {
            log.trace("Persisted candle: symbol={}, interval={}, candle={}", 
                     mutableCandle.symbol, mutableCandle.interval, candle);
        }
    }
    
    /**
     * Persists all active candles (for shutdown or checkpoint).
     */
    public void flushAllCandles() {
        log.info("Flushing {} active candles to storage", activeCandles.size());
        
        // Thread-safe iteration (snapshot of current candles)
        activeCandles.values().forEach(candleRef -> {
            MutableCandle candle = candleRef.get();
            if (candle != null) {
                persistCandle(candle);
            }
        });
        
        log.info("Flush completed - {} candles persisted", candlesCompleted.get());
    }
    
    /**
     * Returns composite key for symbol-interval.
     */
    private String createKey(String symbol, Interval interval) {
        return symbol + "-" + interval.name();
    }
    
    // Metrics accessors
    /** Returns total events processed. */
    public long getEventsProcessed() {
        return eventsProcessed.get();
    }
    
    /** Returns total candles persisted. */
    public long getCandlesCompleted() {
        return candlesCompleted.get();
    }
    
    /** Returns total late events dropped. */
    public long getLateEventsDropped() {
        return lateEventsDropped.get();
    }
    
    /**
     * Internal mutable candle for aggregation. Not thread-safe; use via AtomicReference.
     */
    private static class MutableCandle {
        /** Trading pair */
        final String symbol;
        
        /** Time interval */
        final Interval interval;
        
        /** Window start timestamp */
        final long windowStart;
        
        /** Opening price */
        final double open;
        
        /** Highest price */
        double high;
        
        /** Lowest price */
        double low;
        
        /** Closing price */
        double close;
        
        /** Event counter */
        long volume;
        
        /**
         * Creates a new mutable candle with initial price.
         */
        MutableCandle(String symbol, Interval interval, long windowStart, double initialPrice) {
            this.symbol = symbol;
            this.interval = interval;
            this.windowStart = windowStart;
            this.open = initialPrice;        // Open never changes after this
            this.high = initialPrice;        // Will be updated if higher prices arrive
            this.low = initialPrice;         // Will be updated if lower prices arrive
            this.close = initialPrice;       // Will be updated on every subsequent event
            this.volume = 1;                 // First event = volume 1
        }
        
        /**
         * Updates candle with new price.
         */
        void update(double price) {
            this.high = Math.max(this.high, price);  // Track maximum
            this.low = Math.min(this.low, price);    // Track minimum
            this.close = price;                      // Latest price always
            this.volume++;                           // Increment counter
        }
        
        /**
         * Converts to immutable Candle record.
         */
        Candle toImmutableCandle() {
            return new Candle(windowStart, open, high, low, close, volume);
        }
    }
}

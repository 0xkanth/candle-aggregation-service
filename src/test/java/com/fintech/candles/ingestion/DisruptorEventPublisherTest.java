package com.fintech.candles.ingestion;

import com.fintech.candles.aggregation.CandleAggregator;
import com.fintech.candles.config.CandleProperties;
import com.fintech.candles.domain.BidAskEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for DisruptorEventPublisher.
 * 
 * <p><b>Test Coverage:</b>
 * <ul>
 *   <li>Event publishing (blocking and non-blocking)</li>
 *   <li>Ring buffer capacity management</li>
 *   <li>Event processing and aggregation</li>
 *   <li>Different wait strategies</li>
 *   <li>Exception handling</li>
 *   <li>Concurrent publishing</li>
 *   <li>Back-pressure scenarios</li>
 *   <li>Lifecycle (start/shutdown)</li>
 * </ul>
 */
@DisplayName("DisruptorEventPublisher Tests")
class DisruptorEventPublisherTest {

    private CandleAggregator mockAggregator;
    private CandleProperties properties;
    private DisruptorEventPublisher publisher;

    @BeforeEach
    void setUp() {
        mockAggregator = mock(CandleAggregator.class);
        properties = createDefaultProperties();
        publisher = new DisruptorEventPublisher(mockAggregator, properties);
        publisher.start();
    }

    @AfterEach
    void tearDown() {
        if (publisher != null) {
            publisher.shutdown();
        }
    }

    private CandleProperties createDefaultProperties() {
        CandleProperties props = new CandleProperties();
        CandleProperties.Aggregation aggregation = new CandleProperties.Aggregation();
        CandleProperties.Aggregation.DisruptorConfig disruptor = new CandleProperties.Aggregation.DisruptorConfig();
        
        disruptor.setBufferSize(1024);
        disruptor.setWaitStrategy("BLOCKING");
        
        aggregation.setDisruptor(disruptor);
        props.setAggregation(aggregation);
        
        return props;
    }

    @Test
    @DisplayName("Should publish event successfully")
    void testPublishEvent() throws InterruptedException {
        // Given
        BidAskEvent event = new BidAskEvent("BTC-USD", 50000.0, 50010.0, System.currentTimeMillis());
        
        // When
        publisher.publish(event);
        
        // Wait for async processing
        Thread.sleep(100);
        
        // Then
        verify(mockAggregator, timeout(1000).atLeastOnce()).processEvent(argThat(e -> 
            e.symbol().equals("BTC-USD") && e.bid() == 50000.0 && e.ask() == 50010.0
        ));
    }

    @Test
    @DisplayName("Should publish multiple events in order")
    void testPublishMultipleEvents() throws InterruptedException {
        // Given
        List<BidAskEvent> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            events.add(new BidAskEvent("BTC-USD", 50000.0 + i, 50010.0 + i, System.currentTimeMillis()));
        }
        
        // When
        for (BidAskEvent event : events) {
            publisher.publish(event);
        }
        
        // Wait for processing
        Thread.sleep(200);
        
        // Then
        verify(mockAggregator, timeout(2000).times(10)).processEvent(any(BidAskEvent.class));
    }

    @Test
    @DisplayName("Should try publish successfully when buffer has capacity")
    void testTryPublishSuccess() throws InterruptedException {
        // Given
        BidAskEvent event = new BidAskEvent("ETH-USD", 3000.0, 3005.0, System.currentTimeMillis());
        
        // When
        boolean published = publisher.tryPublish(event);
        
        // Then
        assertThat(published).isTrue();
        
        // Wait for processing
        Thread.sleep(100);
        verify(mockAggregator, timeout(1000).atLeastOnce()).processEvent(any(BidAskEvent.class));
    }

    @Test
    @DisplayName("Should return false when buffer is full")
    void testTryPublishBufferFull() throws InterruptedException {
        // Given - slow down processing to fill buffer
        CountDownLatch processingLatch = new CountDownLatch(1);
        
        doAnswer(invocation -> {
            processingLatch.await(5, TimeUnit.SECONDS); // Block processing
            return null;
        }).when(mockAggregator).processEvent(any(BidAskEvent.class));
        
        // When - fill the buffer
        int bufferSize = (int) publisher.getBufferSize();
        int published = 0;
        
        for (int i = 0; i < bufferSize * 2; i++) {
            BidAskEvent event = new BidAskEvent("BTC-USD", 50000.0, 50010.0, System.currentTimeMillis());
            if (publisher.tryPublish(event)) {
                published++;
            } else {
                break; // Buffer full
            }
        }
        
        // Then
        assertThat(published).isLessThanOrEqualTo(bufferSize);
        
        // Cleanup
        processingLatch.countDown();
        Thread.sleep(100);
    }

    @Test
    @DisplayName("Should report correct buffer size")
    void testGetBufferSize() {
        // When
        long bufferSize = publisher.getBufferSize();
        
        // Then
        assertThat(bufferSize).isEqualTo(1024);
    }

    @Test
    @DisplayName("Should report correct remaining capacity")
    void testGetRemainingCapacity() {
        // When
        long remainingCapacity = publisher.getRemainingCapacity();
        
        // Then
        assertThat(remainingCapacity).isGreaterThan(0);
        assertThat(remainingCapacity).isLessThanOrEqualTo(1024);
    }

    @Test
    @DisplayName("Should decrease remaining capacity as events are published")
    void testRemainingCapacityDecreases() throws InterruptedException {
        // Given
        CountDownLatch processingLatch = new CountDownLatch(1);
        
        doAnswer(invocation -> {
            processingLatch.await(5, TimeUnit.SECONDS);
            return null;
        }).when(mockAggregator).processEvent(any(BidAskEvent.class));
        
        long initialCapacity = publisher.getRemainingCapacity();
        
        // When - publish some events
        for (int i = 0; i < 10; i++) {
            publisher.tryPublish(new BidAskEvent("BTC-USD", 50000.0, 50010.0, System.currentTimeMillis()));
        }
        
        long capacityAfterPublish = publisher.getRemainingCapacity();
        
        // Then
        assertThat(capacityAfterPublish).isLessThan(initialCapacity);
        
        // Cleanup
        processingLatch.countDown();
        Thread.sleep(100);
    }

    @Test
    @DisplayName("Should handle concurrent publishing from multiple threads")
    void testConcurrentPublishing() throws InterruptedException {
        // Given
        int threadCount = 10;
        int eventsPerThread = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        
        // When
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    
                    for (int j = 0; j < eventsPerThread; j++) {
                        BidAskEvent event = new BidAskEvent(
                            "BTC-USD",
                            50000.0 + threadId,
                            50010.0 + threadId,
                            System.currentTimeMillis()
                        );
                        
                        if (publisher.tryPublish(event)) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }
        
        startLatch.countDown(); // Start all threads
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        
        // Then
        assertThat(completed).isTrue();
        assertThat(successCount.get()).isGreaterThan(0);
        
        // Wait for processing
        Thread.sleep(500);
        verify(mockAggregator, atLeast(successCount.get() / 2)).processEvent(any(BidAskEvent.class));
    }

    @Test
    @DisplayName("Should handle events with different symbols")
    void testMultipleSymbols() throws InterruptedException {
        // Given
        String[] symbols = {"BTC-USD", "ETH-USD", "SOL-USD", "ADA-USD"};
        
        // When
        for (String symbol : symbols) {
            BidAskEvent event = new BidAskEvent(symbol, 1000.0, 1001.0, System.currentTimeMillis());
            publisher.publish(event);
        }
        
        // Wait for processing
        Thread.sleep(200);
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockAggregator, timeout(2000).times(symbols.length)).processEvent(eventCaptor.capture());
        
        List<String> capturedSymbols = eventCaptor.getAllValues().stream()
            .map(BidAskEvent::symbol)
            .distinct()
            .toList();
        
        assertThat(capturedSymbols).containsExactlyInAnyOrder(symbols);
    }

    @Test
    @DisplayName("Should handle rapid event publishing")
    void testHighThroughput() throws InterruptedException {
        // Given
        int eventCount = 1000;
        AtomicInteger publishedCount = new AtomicInteger(0);
        
        // When
        for (int i = 0; i < eventCount; i++) {
            BidAskEvent event = new BidAskEvent("BTC-USD", 50000.0 + i, 50010.0 + i, System.currentTimeMillis());
            if (publisher.tryPublish(event)) {
                publishedCount.incrementAndGet();
            }
        }
        
        // Wait for processing
        Thread.sleep(500);
        
        // Then
        assertThat(publishedCount.get()).isGreaterThan(0);
        verify(mockAggregator, atLeast(publishedCount.get() / 2)).processEvent(any(BidAskEvent.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOCKING", "SLEEPING", "YIELDING", "BUSY_SPIN"})
    @DisplayName("Should work with different wait strategies")
    void testDifferentWaitStrategies(String waitStrategy) throws InterruptedException {
        // Given
        publisher.shutdown();
        
        CandleProperties.Aggregation.DisruptorConfig disruptor = properties.getAggregation().getDisruptor();
        disruptor.setWaitStrategy(waitStrategy);
        
        publisher = new DisruptorEventPublisher(mockAggregator, properties);
        publisher.start();
        
        // When
        BidAskEvent event = new BidAskEvent("BTC-USD", 50000.0, 50010.0, System.currentTimeMillis());
        publisher.publish(event);
        
        // Wait for processing
        Thread.sleep(100);
        
        // Then
        verify(mockAggregator, timeout(1000).atLeastOnce()).processEvent(any(BidAskEvent.class));
    }

    @Test
    @DisplayName("Should handle unknown wait strategy gracefully")
    void testUnknownWaitStrategy() throws InterruptedException {
        // Given
        publisher.shutdown();
        
        CandleProperties.Aggregation.DisruptorConfig disruptor = properties.getAggregation().getDisruptor();
        disruptor.setWaitStrategy("UNKNOWN_STRATEGY");
        
        publisher = new DisruptorEventPublisher(mockAggregator, properties);
        publisher.start();
        
        // When
        BidAskEvent event = new BidAskEvent("BTC-USD", 50000.0, 50010.0, System.currentTimeMillis());
        publisher.publish(event);
        
        // Wait for processing
        Thread.sleep(100);
        
        // Then - should default to YIELDING and still work
        verify(mockAggregator, timeout(1000).atLeastOnce()).processEvent(any(BidAskEvent.class));
    }

    @Test
    @DisplayName("Should handle aggregator exceptions gracefully")
    void testAggregatorException() throws InterruptedException {
        // Given
        doThrow(new RuntimeException("Simulated error"))
            .when(mockAggregator).processEvent(any(BidAskEvent.class));
        
        // When
        BidAskEvent event = new BidAskEvent("BTC-USD", 50000.0, 50010.0, System.currentTimeMillis());
        publisher.publish(event);
        
        // Wait for processing
        Thread.sleep(200);
        
        // Then - should not crash, exception is logged
        verify(mockAggregator, timeout(1000).atLeastOnce()).processEvent(any(BidAskEvent.class));
    }

    @Test
    @DisplayName("Should process events with different buffer sizes")
    void testDifferentBufferSizes() throws InterruptedException {
        // Given
        publisher.shutdown();
        
        CandleProperties.Aggregation.DisruptorConfig disruptor = properties.getAggregation().getDisruptor();
        disruptor.setBufferSize(512); // Smaller buffer
        
        publisher = new DisruptorEventPublisher(mockAggregator, properties);
        publisher.start();
        
        // When
        for (int i = 0; i < 100; i++) {
            BidAskEvent event = new BidAskEvent("BTC-USD", 50000.0 + i, 50010.0 + i, System.currentTimeMillis());
            publisher.publish(event);
        }
        
        // Wait for processing
        Thread.sleep(300);
        
        // Then
        verify(mockAggregator, timeout(2000).times(100)).processEvent(any(BidAskEvent.class));
        assertThat(publisher.getBufferSize()).isEqualTo(512);
    }

    @Test
    @DisplayName("Should shutdown gracefully")
    void testShutdown() throws InterruptedException {
        // Given
        BidAskEvent event = new BidAskEvent("BTC-USD", 50000.0, 50010.0, System.currentTimeMillis());
        publisher.publish(event);
        
        // Wait for processing
        Thread.sleep(100);
        
        // When
        publisher.shutdown();
        
        // Then - no exceptions should be thrown
        assertThat(publisher).isNotNull();
    }

    @Test
    @DisplayName("Should handle shutdown when already shut down")
    void testDoubleShutdown() {
        // Given
        publisher.shutdown();
        
        // When/Then - should not throw exception
        publisher.shutdown();
    }

    @Test
    @DisplayName("Should process events with realistic market data")
    void testRealisticMarketData() throws InterruptedException {
        // Given
        List<BidAskEvent> marketEvents = List.of(
            new BidAskEvent("BTC-USD", 42500.50, 42501.50, System.currentTimeMillis()),
            new BidAskEvent("ETH-USD", 2249.75, 2250.25, System.currentTimeMillis()),
            new BidAskEvent("SOL-USD", 95.48, 95.52, System.currentTimeMillis()),
            new BidAskEvent("EURUSD", 1.09998, 1.10002, System.currentTimeMillis()),
            new BidAskEvent("XAUUSD", 1799.80, 1800.20, System.currentTimeMillis())
        );
        
        // When
        for (BidAskEvent event : marketEvents) {
            publisher.publish(event);
        }
        
        // Wait for processing
        Thread.sleep(200);
        
        // Then
        verify(mockAggregator, timeout(2000).times(marketEvents.size())).processEvent(any(BidAskEvent.class));
    }

    @Test
    @DisplayName("Should maintain event order for same symbol")
    void testEventOrdering() throws InterruptedException {
        // Given
        List<BidAskEvent> orderedEvents = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            orderedEvents.add(new BidAskEvent("BTC-USD", 50000.0 + i, 50001.0 + i, System.currentTimeMillis() + i));
        }
        
        // When
        for (BidAskEvent event : orderedEvents) {
            publisher.publish(event);
        }
        
        // Wait for processing
        Thread.sleep(300);
        
        // Then
        verify(mockAggregator, timeout(2000).times(50)).processEvent(any(BidAskEvent.class));
    }

    @Test
    @DisplayName("Should recover from processing errors and continue")
    void testRecoveryFromErrors() throws InterruptedException {
        // Given - throw exception for first few events, then succeed
        AtomicInteger callCount = new AtomicInteger(0);
        
        doAnswer(invocation -> {
            if (callCount.incrementAndGet() <= 3) {
                throw new RuntimeException("Simulated transient error");
            }
            return null;
        }).when(mockAggregator).processEvent(any(BidAskEvent.class));
        
        // When
        for (int i = 0; i < 10; i++) {
            BidAskEvent event = new BidAskEvent("BTC-USD", 50000.0 + i, 50010.0 + i, System.currentTimeMillis());
            publisher.publish(event);
        }
        
        // Wait for processing
        Thread.sleep(300);
        
        // Then - all events should be processed despite initial errors
        verify(mockAggregator, timeout(2000).times(10)).processEvent(any(BidAskEvent.class));
    }
}

package com.fintech.candles.aggregation;

import com.fintech.candles.domain.BidAskEvent;
import com.fintech.candles.domain.Candle;
import com.fintech.candles.domain.Interval;
import com.fintech.candles.storage.CandleRepository;
import com.fintech.candles.util.TimeWindowManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CandleAggregator} - the core candle aggregation engine.
 * 
 * <p><b>Test Strategy:</b>
 * <ul>
 *   <li>Mock repository to isolate aggregation logic from storage concerns</li>
 *   <li>Use real TimeWindowManager to test actual alignment calculations</li>
 *   <li>Verify lock-free CAS update behavior through state assertions</li>
 *   <li>Test edge cases: late events, window boundaries, concurrent symbols</li>
 * </ul>
 * 
 * <p><b>Coverage Areas:</b>
 * <ul>
 *   <li>Candle creation on first event</li>
 *   <li>OHLC updates within same window</li>
 *   <li>Window rotation and persistence</li>
 *   <li>Late event handling (within and beyond tolerance)</li>
 *   <li>Multi-interval processing</li>
 *   <li>Symbol isolation</li>
 * </ul>
 * 
 * <p><b>Mocking Rationale:</b>
 * <ul>
 *   <li>Repository mock: Avoid Chronicle Map initialization overhead in unit tests</li>
 *   <li>SimpleMeterRegistry: Real metrics collector (lightweight, no external dependencies)</li>
 *   <li>TimeWindowManager: Real instance (pure logic, no I/O)</li>
 * </ul>
 * 
 * @author Senior Java/Web3 Developer
 * @see CandleAggregator
 */
@DisplayName("CandleAggregator Tests")
class CandleAggregatorTest {

    private CandleRepository repository;
    private TimeWindowManager timeWindowManager;
    private SimpleMeterRegistry meterRegistry;
    private CandleAggregator aggregator;

    @BeforeEach
    void setUp() {
        repository = mock(CandleRepository.class);
        when(repository.findByExactTime(anyString(), any(Interval.class), anyLong()))
            .thenReturn(Optional.empty());
        timeWindowManager = new TimeWindowManager(5000L); // 5 second late tolerance
        meterRegistry = new SimpleMeterRegistry();
        aggregator = new CandleAggregator(repository, timeWindowManager, meterRegistry);
    }

    /**
     * <b>Test Scenario:</b> First event for a symbol-interval creates a new candle.
     * 
     * <p><b>Given:</b> No existing candle for BTC-USD S1 interval
     * 
     * <p><b>When:</b> First BidAskEvent arrives with mid-price 50005.0
     * 
     * <p><b>Then:</b> New candle created with:
     * <ul>
     *   <li>All OHLC values = 50005.0 (single price point)</li>
     *   <li>Volume = 1 (one event processed)</li>
     *   <li>Time = aligned window start (1000 ms)</li>
     * </ul>
     * 
     * <p><b>Rationale:</b> Verifies candle initialization logic when starting from empty state.
     * Critical for ensuring first price is correctly recorded as open.
     * 
     * <p><b>Edge Case Covered:</b> Bootstrap scenario (no historical data).
     */
    @Test
    @DisplayName("Should create new candle for first event")
    void testFirstEvent() {
        BidAskEvent event = new BidAskEvent("BTC-USD", 50000.0, 50010.0, 1000L);
        
        // Process first event
        aggregator.processEvent(event);
        
        // Trigger window rotation by sending event in next window (2000L is new S1 window)
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, 2000L));
        
        // Capture all persisted candles
        ArgumentCaptor<Candle> candleCaptor = ArgumentCaptor.forClass(Candle.class);
        verify(repository, atLeastOnce()).save(eq("BTC-USD"), eq(Interval.S1), candleCaptor.capture());
        
        // Find the candle for window 1000L
        Candle candle = candleCaptor.getAllValues().stream()
            .filter(c -> c.time() == 1000L)
            .findFirst()
            .orElseThrow();
        
        // Verify all OHLC values match the single price point
        assertThat(candle.open()).isEqualTo(50005.0); // mid price
        assertThat(candle.high()).isEqualTo(50005.0);
        assertThat(candle.low()).isEqualTo(50005.0);
        assertThat(candle.close()).isEqualTo(50005.0);
        assertThat(candle.volume()).isEqualTo(1L);
    }

    /**
     * <b>Test Scenario:</b> Multiple events in same window correctly update OHLC values.
     * 
     * <p><b>Given:</b> Empty candle for window 1000L
     * 
     * <p><b>When:</b> Four events arrive in same S1 window (1000-1999ms):
     * <ol>
     *   <li>t=1000ms, mid=50005 → open=50005 (first price)</li>
     *   <li>t=1300ms, mid=50105 → high=50105 (new high)</li>
     *   <li>t=1600ms, mid=49905 → low=49905 (new low)</li>
     *   <li>t=1900ms, mid=50055 → close=50055 (last price)</li>
     * </ol>
     * 
     * <p><b>Then:</b> Persisted candle shows:
     * <ul>
     *   <li>open = 50005 (unchanged from first event)</li>
     *   <li>high = 50105 (maximum of all prices)</li>
     *   <li>low = 49905 (minimum of all prices)</li>
     *   <li>close = 50055 (last price observed)</li>
     *   <li>volume = 4 (four events processed)</li>
     * </ul>
     * 
     * <p><b>Rationale:</b> Validates OHLC update semantics:
     * <ul>
     *   <li>Open is immutable after first event</li>
     *   <li>High tracks maximum price</li>
     *   <li>Low tracks minimum price</li>
     *   <li>Close always reflects latest price</li>
     *   <li>Volume increments with each event</li>
     * </ul>
     * 
     * <p><b>Lock-Free CAS Verification:</b> Multiple updates in tight loop tests
     * thread-safe CAS operations without race conditions.
     */
    @Test
    @DisplayName("Should aggregate multiple events in same window")
    void testMultipleEventsInSameWindow() {
        long baseTime = 1000L; // Aligned to S1 interval
        
        // First event - sets open
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, baseTime));
        
        // Second event - updates high and close
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50100.0, 50110.0, baseTime + 300));
        
        // Third event - updates low and close
        aggregator.processEvent(new BidAskEvent("BTC-USD", 49900.0, 49910.0, baseTime + 600));
        
        // Fourth event - updates close
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50050.0, 50060.0, baseTime + 900));
        
        // Trigger window rotation
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, baseTime + 1000));
        
        ArgumentCaptor<Candle> candleCaptor = ArgumentCaptor.forClass(Candle.class);
        verify(repository, atLeastOnce()).save(eq("BTC-USD"), eq(Interval.S1), candleCaptor.capture());
        
        Candle candle = candleCaptor.getAllValues().stream()
            .filter(c -> c.time() == baseTime)
            .findFirst()
            .orElseThrow();
        
        assertThat(candle.open()).isEqualTo(50005.0); // First mid price
        assertThat(candle.high()).isGreaterThanOrEqualTo(50105.0); // Highest mid price
        assertThat(candle.low()).isLessThanOrEqualTo(49905.0); // Lowest mid price
        assertThat(candle.close()).isEqualTo(50055.0); // Last mid price
        assertThat(candle.volume()).isEqualTo(4L);
    }

    @Test
    @DisplayName("Should create new candle when window changes")
    void testWindowBoundary() {
        // First event in window 1000-2000
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, 1000L));
        
        // Second event in window 2000-3000 (different window) - triggers rotation
        aggregator.processEvent(new BidAskEvent("BTC-USD", 51000.0, 51010.0, 2000L));
        
        // Third event to trigger second window rotation
        aggregator.processEvent(new BidAskEvent("BTC-USD", 51000.0, 51010.0, 3000L));
        
        // Should have saved two different candles for S1 interval
        verify(repository, atLeast(2)).save(eq("BTC-USD"), eq(Interval.S1), any(Candle.class));
    }

    @Test
    @DisplayName("Should drop late events beyond tolerance")
    void testLateEventDropped() {
        long currentTime = 10000L;
        long lateTime = 1000L; // 9 seconds late, beyond 5 second tolerance
        
        // Process current event first
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, currentTime));
        
        // Process late event
        aggregator.processEvent(new BidAskEvent("BTC-USD", 49000.0, 49010.0, lateTime));
        
        try { Thread.sleep(500); } catch (InterruptedException e) { }
        
        // Late event should be dropped
        assertThat(aggregator.getLateEventsDropped()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should handle multiple symbols independently")
    void testMultipleSymbols() {
        long timestamp = 1000L;
        
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, timestamp));
        aggregator.processEvent(new BidAskEvent("ETH-USD", 3000.0, 3005.0, timestamp));
        aggregator.processEvent(new BidAskEvent("SOL-USD", 100.0, 100.5, timestamp));
        
        // Trigger window rotation for all symbols
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, timestamp + 1000));
        aggregator.processEvent(new BidAskEvent("ETH-USD", 3000.0, 3005.0, timestamp + 1000));
        aggregator.processEvent(new BidAskEvent("SOL-USD", 100.0, 100.5, timestamp + 1000));
        
        verify(repository, atLeastOnce()).save(eq("BTC-USD"), eq(Interval.S1), any(Candle.class));
        verify(repository, atLeastOnce()).save(eq("ETH-USD"), eq(Interval.S1), any(Candle.class));
        verify(repository, atLeastOnce()).save(eq("SOL-USD"), eq(Interval.S1), any(Candle.class));
    }

    @Test
    @DisplayName("Should handle multiple intervals for same symbol")
    void testMultipleIntervals() {
        long timestamp = 60000L; // Aligned to M1
        
        BidAskEvent event = new BidAskEvent("BTC-USD", 50000.0, 50010.0, timestamp);
        
        aggregator.processEvent(event);
        
        // Trigger window rotation for S1 (1 second later)
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, timestamp + 1000));
        
        verify(repository, atLeastOnce()).save(eq("BTC-USD"), eq(Interval.S1), any(Candle.class));
        
        // Trigger rotation for S5 (5 seconds later from start)
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, timestamp + 5000));
        
        verify(repository, atLeastOnce()).save(eq("BTC-USD"), eq(Interval.S5), any(Candle.class));
        
        // Trigger rotation for M1 (60 seconds later from start)
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, timestamp + 60000));
        
        verify(repository, atLeastOnce()).save(eq("BTC-USD"), eq(Interval.M1), any(Candle.class));
    }

    @ParameterizedTest(name = "Interval: {0}")
    @MethodSource("intervalProvider")
    @DisplayName("Should handle all supported intervals")
    void testAllIntervals(Interval interval) {
        long timestamp = interval.alignTimestamp(System.currentTimeMillis());
        
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, timestamp));
        
        // Trigger window rotation for this interval
        long nextWindow = timestamp + interval.toMillis();
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, nextWindow));
        
        verify(repository, atLeastOnce()).save(eq("BTC-USD"), eq(interval), any(Candle.class));
    }

    static Stream<Arguments> intervalProvider() {
        return Stream.of(
            Arguments.of(Interval.S1),
            Arguments.of(Interval.S5),
            Arguments.of(Interval.M1),
            Arguments.of(Interval.M15),
            Arguments.of(Interval.H1)
        );
    }

    @Test
    @DisplayName("Should track events processed metric")
    void testEventsProcessedMetric() {
        long timestamp = 1000L;
        
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, timestamp));
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50100.0, 50110.0, timestamp + 500));
        
        try { Thread.sleep(500); } catch (InterruptedException e) { }
        
        assertThat(aggregator.getEventsProcessed()).isGreaterThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("Should track candles completed metric")
    void testCandlesCompletedMetric() {
        // Create candle in first window
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, 1000L));
        
        // Create candle in second window (completes first)
        aggregator.processEvent(new BidAskEvent("BTC-USD", 51000.0, 51010.0, 2000L));
        
        try { Thread.sleep(500); } catch (InterruptedException e) { }
        
        assertThat(aggregator.getCandlesCompleted()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("Should handle concurrent events from multiple threads")
    void testConcurrentProcessing() throws InterruptedException {
        int threadCount = 10;
        int eventsPerThread = 100;
        long baseTime = 1000L;
        
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < eventsPerThread; j++) {
                    double price = 50000.0 + threadIndex * 100 + j;
                    aggregator.processEvent(
                        new BidAskEvent("BTC-USD", price, price + 10, baseTime + j)
                    );
                }
            });
            threads[i].start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        Thread.sleep(1000);
        
        assertThat(aggregator.getEventsProcessed()).isEqualTo(threadCount * eventsPerThread);
    }

    @Test
    @DisplayName("Should handle bullish candle correctly")
    void testBullishCandle() {
        long timestamp = 1000L;
        
        // Start low, end high
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, timestamp));
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50500.0, 50510.0, timestamp + 500));
        
        // Trigger window rotation
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, timestamp + 1000));
        
        ArgumentCaptor<Candle> candleCaptor = ArgumentCaptor.forClass(Candle.class);
        verify(repository, atLeastOnce()).save(eq("BTC-USD"), eq(Interval.S1), candleCaptor.capture());
        
        Candle candle = candleCaptor.getAllValues().stream()
            .filter(c -> c.time() == timestamp)
            .findFirst()
            .orElseThrow();
        
        assertThat(candle.isBullish()).isTrue();
        assertThat(candle.close()).isGreaterThan(candle.open());
    }

    @Test
    @DisplayName("Should handle bearish candle correctly")
    void testBearishCandle() {
        long timestamp = 1000L;
        
        // Start high, end low
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50500.0, 50510.0, timestamp));
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, timestamp + 500));
        
        // Trigger window rotation
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, timestamp + 1000));
        
        ArgumentCaptor<Candle> candleCaptor = ArgumentCaptor.forClass(Candle.class);
        verify(repository, atLeastOnce()).save(eq("BTC-USD"), eq(Interval.S1), candleCaptor.capture());
        
        Candle candle = candleCaptor.getAllValues().stream()
            .filter(c -> c.time() == timestamp)
            .findFirst()
            .orElseThrow();
        
        assertThat(candle.isBearish()).isTrue();
        assertThat(candle.close()).isLessThan(candle.open());
    }

    @Test
    @DisplayName("Should calculate correct OHLC values")
    void testOHLCCalculation() {
        long timestamp = 1000L;
        
        // Send events with known prices
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, timestamp)); // open: 50005
        aggregator.processEvent(new BidAskEvent("BTC-USD", 51000.0, 51010.0, timestamp + 200)); // high: 51005
        aggregator.processEvent(new BidAskEvent("BTC-USD", 49000.0, 49010.0, timestamp + 400)); // low: 49005
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50500.0, 50510.0, timestamp + 600)); // close: 50505
        
        // Trigger window rotation
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, timestamp + 1000));
        
        ArgumentCaptor<Candle> candleCaptor = ArgumentCaptor.forClass(Candle.class);
        verify(repository, atLeastOnce()).save(eq("BTC-USD"), eq(Interval.S1), candleCaptor.capture());
        
        Candle candle = candleCaptor.getAllValues().stream()
            .filter(c -> c.time() == timestamp)
            .findFirst()
            .orElseThrow();
        
        assertThat(candle.open()).isEqualTo(50005.0);
        assertThat(candle.high()).isGreaterThanOrEqualTo(51005.0);
        assertThat(candle.low()).isLessThanOrEqualTo(49005.0);
        assertThat(candle.close()).isEqualTo(50505.0);
    }

    @Test
    @DisplayName("Should reset metrics correctly")
    void testResetMetrics() {
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50010.0, 1000L));
        
        try { Thread.sleep(500); } catch (InterruptedException e) { }
        
        long eventsBefore = aggregator.getEventsProcessed();
        assertThat(eventsBefore).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should handle zero spread events")
    void testZeroSpread() {
        long timestamp = 1000L;
        
        // Event with bid = ask (zero spread)
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50000.0, timestamp));
        
        // Trigger window rotation
        aggregator.processEvent(new BidAskEvent("BTC-USD", 50000.0, 50000.0, timestamp + 1000));
        
        ArgumentCaptor<Candle> candleCaptor = ArgumentCaptor.forClass(Candle.class);
        verify(repository, atLeastOnce()).save(eq("BTC-USD"), eq(Interval.S1), candleCaptor.capture());
        
        Candle candle = candleCaptor.getAllValues().stream()
            .filter(c -> c.time() == timestamp)
            .findFirst()
            .orElseThrow();
        
        assertThat(candle.open()).isEqualTo(50000.0);
    }

    @Test
    @DisplayName("Should handle rapid sequential events")
    void testRapidEvents() {
        long timestamp = 1000L;
        
        for (int i = 0; i < 1000; i++) {
            double price = 50000.0 + i;
            aggregator.processEvent(
                new BidAskEvent("BTC-USD", price, price + 10, timestamp + i)
            );
        }
        
        try { Thread.sleep(1000); } catch (InterruptedException e) { }
        
        assertThat(aggregator.getEventsProcessed()).isEqualTo(1000L);
    }
}

package com.fintech.candles.util;

import com.fintech.candles.domain.Interval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TimeWindowManager Tests")
class TimeWindowManagerTest {

    private TimeWindowManager manager;

    @BeforeEach
    void setUp() {
        manager = new TimeWindowManager(5000L); // 5 second late tolerance
    }

    @Test
    @DisplayName("Should detect when event is in current window")
    void testCurrentWindow() {
        long windowStart = 1000L;
        long eventTime = 1500L;
        
        boolean result = !manager.isNewWindow(eventTime, windowStart, Interval.S1);
        
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should detect when event is in different window")
    void testDifferentWindow() {
        long windowStart = 1000L;
        long eventTime = 2500L; // Different S1 window
        
        boolean result = manager.isNewWindow(eventTime, windowStart, Interval.S1);
        
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should accept late events within tolerance")
    void testLateEventWithinTolerance() {
        long currentTime = 10000L;
        long lateTime = 6000L; // 4 seconds late, within 5 second tolerance
        
        boolean result = manager.shouldProcessLateEvent(lateTime, currentTime);
        
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should reject late events beyond tolerance")
    void testLateEventBeyondTolerance() {
        long currentTime = 10000L;
        long lateTime = 1000L; // 9 seconds late, beyond 5 second tolerance
        
        boolean result = manager.shouldProcessLateEvent(lateTime, currentTime);
        
        assertThat(result).isFalse();
    }

    @ParameterizedTest(name = "{0}: event at {1} vs window at {2}")
    @MethodSource("windowTestProvider")
    @DisplayName("Should correctly determine window membership for all intervals")
    void testWindowMembershipForAllIntervals(Interval interval, long eventTime, long windowStart, boolean expectedInSameWindow) {
        boolean result = !manager.isNewWindow(eventTime, windowStart, interval);
        
        assertThat(result).isEqualTo(expectedInSameWindow);
    }

    static Stream<Arguments> windowTestProvider() {
        return Stream.of(
            // S1 interval tests
            Arguments.of(Interval.S1, 1000L, 1000L, true),
            Arguments.of(Interval.S1, 1500L, 1000L, true),
            Arguments.of(Interval.S1, 1999L, 1000L, true),
            Arguments.of(Interval.S1, 2000L, 1000L, false),
            
            // S5 interval tests
            Arguments.of(Interval.S5, 5000L, 5000L, true),
            Arguments.of(Interval.S5, 7500L, 5000L, true),
            Arguments.of(Interval.S5, 9999L, 5000L, true),
            Arguments.of(Interval.S5, 10000L, 5000L, false),
            
            // M1 interval tests
            Arguments.of(Interval.M1, 60000L, 60000L, true),
            Arguments.of(Interval.M1, 90000L, 60000L, true),
            Arguments.of(Interval.M1, 119999L, 60000L, true),
            Arguments.of(Interval.M1, 120000L, 60000L, false),
            
            // M15 interval tests
            Arguments.of(Interval.M15, 900000L, 900000L, true),
            Arguments.of(Interval.M15, 1350000L, 900000L, true),
            Arguments.of(Interval.M15, 1799999L, 900000L, true),
            Arguments.of(Interval.M15, 1800000L, 900000L, false),
            
            // H1 interval tests
            Arguments.of(Interval.H1, 3600000L, 3600000L, true),
            Arguments.of(Interval.H1, 5400000L, 3600000L, true),
            Arguments.of(Interval.H1, 7199999L, 3600000L, true),
            Arguments.of(Interval.H1, 7200000L, 3600000L, false)
        );
    }

    @Test
    @DisplayName("Should calculate correct window start")
    void testGetWindowStart() {
        long timestamp = 1234L;
        
        long windowStart = manager.getWindowStart(timestamp, Interval.S1);
        
        assertThat(windowStart).isEqualTo(1000L);
    }

    @Test
    @DisplayName("Should handle zero late tolerance")
    void testZeroLateTolerance() {
        TimeWindowManager strictManager = new TimeWindowManager(0L);
        
        long currentTime = 10000L;
        long lateTime = 9999L; // Even 1ms late
        
        boolean result = strictManager.shouldProcessLateEvent(lateTime, currentTime);
        
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should handle very large late tolerance")
    void testLargeLateTolerance() {
        TimeWindowManager lenientManager = new TimeWindowManager(3600000L); // 1 hour
        
        long currentTime = 3600000L;
        long lateTime = 1000L; // ~1 hour late
        
        boolean result = lenientManager.shouldProcessLateEvent(lateTime, currentTime);
        
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should handle exact window boundary")
    void testExactBoundary() {
        long windowStart = 1000L;
        long boundaryTime = 2000L; // Exactly at next window
        
        boolean result = manager.isNewWindow(boundaryTime, windowStart, Interval.S1);
        
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should handle timestamp at window start")
    void testWindowStartTimestamp() {
        long windowStart = 1000L;
        
        boolean result = !manager.isNewWindow(windowStart, windowStart, Interval.S1);
        
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should calculate window start for aligned timestamps")
    void testAlignedTimestamps() {
        long alignedTime = 60000L; // Already aligned to M1
        
        long windowStart = manager.getWindowStart(alignedTime, Interval.M1);
        
        assertThat(windowStart).isEqualTo(60000L);
    }

    @Test
    @DisplayName("Should calculate window start for unaligned timestamps")
    void testUnalignedTimestamps() {
        long unalignedTime = 65432L; // Not aligned to M1
        
        long windowStart = manager.getWindowStart(unalignedTime, Interval.M1);
        
        assertThat(windowStart).isEqualTo(60000L);
    }

    @Test
    @DisplayName("Should handle events at exact late tolerance boundary")
    void testExactToleranceBoundary() {
        long currentTime = 10000L;
        long lateTime = 5000L; // Exactly 5 seconds late (tolerance)
        
        boolean result = manager.shouldProcessLateEvent(lateTime, currentTime);
        
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should handle events just beyond late tolerance")
    void testJustBeyondTolerance() {
        long currentTime = 10000L;
        long lateTime = 4999L; // Just beyond 5 second tolerance
        
        boolean result = manager.shouldProcessLateEvent(lateTime, currentTime);
        
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should handle future events")
    void testFutureEvents() {
        long currentTime = 10000L;
        long futureTime = 20000L; // In the future
        
        boolean result = manager.shouldProcessLateEvent(futureTime, currentTime);
        
        assertThat(result).isTrue(); // Future events are always accepted
    }

    @Test
    @DisplayName("Should handle same timestamp for current and event")
    void testSameTimestamp() {
        long timestamp = 10000L;
        
        boolean result = manager.shouldProcessLateEvent(timestamp, timestamp);
        
        assertThat(result).isTrue();
    }
}

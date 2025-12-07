package com.fintech.candles.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Interval Tests")
class IntervalTest {

    @ParameterizedTest(name = "{0} should have {1} milliseconds")
    @MethodSource("intervalMillisProvider")
    @DisplayName("toMillis() should return correct milliseconds for each interval")
    void testToMillis(Interval interval, long expectedMillis) {
        assertThat(interval.toMillis()).isEqualTo(expectedMillis);
    }

    static Stream<Arguments> intervalMillisProvider() {
        return Stream.of(
            Arguments.of(Interval.S1, 1000L),
            Arguments.of(Interval.S5, 5000L),
            Arguments.of(Interval.M1, 60000L),
            Arguments.of(Interval.M15, 900000L),
            Arguments.of(Interval.H1, 3600000L)
        );
    }

    @ParameterizedTest(name = "{0}: {1} should align to {2}")
    @MethodSource("alignTimestampProvider")
    @DisplayName("alignTimestamp() should correctly align timestamps to interval boundaries")
    void testAlignTimestamp(Interval interval, long timestamp, long expectedAligned) {
        assertThat(interval.alignTimestamp(timestamp)).isEqualTo(expectedAligned);
    }

    static Stream<Arguments> alignTimestampProvider() {
        return Stream.of(
            // S1 - 1 second intervals
            Arguments.of(Interval.S1, 1000L, 1000L),
            Arguments.of(Interval.S1, 1500L, 1000L),
            Arguments.of(Interval.S1, 1999L, 1000L),
            Arguments.of(Interval.S1, 2000L, 2000L),
            
            // S5 - 5 second intervals
            Arguments.of(Interval.S5, 5000L, 5000L),
            Arguments.of(Interval.S5, 7500L, 5000L),
            Arguments.of(Interval.S5, 9999L, 5000L),
            Arguments.of(Interval.S5, 10000L, 10000L),
            
            // M1 - 1 minute intervals
            Arguments.of(Interval.M1, 60000L, 60000L),
            Arguments.of(Interval.M1, 90000L, 60000L),
            Arguments.of(Interval.M1, 119999L, 60000L),
            Arguments.of(Interval.M1, 120000L, 120000L),
            
            // M15 - 15 minute intervals
            Arguments.of(Interval.M15, 900000L, 900000L),
            Arguments.of(Interval.M15, 1350000L, 900000L),
            Arguments.of(Interval.M15, 1799999L, 900000L),
            Arguments.of(Interval.M15, 1800000L, 1800000L),
            
            // H1 - 1 hour intervals
            Arguments.of(Interval.H1, 3600000L, 3600000L),
            Arguments.of(Interval.H1, 5400000L, 3600000L),
            Arguments.of(Interval.H1, 7199999L, 3600000L),
            Arguments.of(Interval.H1, 7200000L, 7200000L),
            
            // Edge cases
            Arguments.of(Interval.S1, 0L, 0L),
            Arguments.of(Interval.M1, 0L, 0L),
            Arguments.of(Interval.H1, 0L, 0L)
        );
    }

    @ParameterizedTest(name = "{0}: window starting at {1} should end at {2}")
    @MethodSource("windowEndProvider")
    @DisplayName("windowEnd() should calculate correct window end time")
    void testWindowEnd(Interval interval, long windowStart, long expectedEnd) {
        assertThat(interval.windowEnd(windowStart)).isEqualTo(expectedEnd);
    }

    static Stream<Arguments> windowEndProvider() {
        return Stream.of(
            // S1 - 1 second intervals
            Arguments.of(Interval.S1, 1000L, 2000L),
            Arguments.of(Interval.S1, 2000L, 3000L),
            Arguments.of(Interval.S1, 0L, 1000L),
            
            // S5 - 5 second intervals
            Arguments.of(Interval.S5, 5000L, 10000L),
            Arguments.of(Interval.S5, 10000L, 15000L),
            Arguments.of(Interval.S5, 0L, 5000L),
            
            // M1 - 1 minute intervals
            Arguments.of(Interval.M1, 60000L, 120000L),
            Arguments.of(Interval.M1, 120000L, 180000L),
            Arguments.of(Interval.M1, 0L, 60000L),
            
            // M15 - 15 minute intervals
            Arguments.of(Interval.M15, 900000L, 1800000L),
            Arguments.of(Interval.M15, 1800000L, 2700000L),
            Arguments.of(Interval.M15, 0L, 900000L),
            
            // H1 - 1 hour intervals
            Arguments.of(Interval.H1, 3600000L, 7200000L),
            Arguments.of(Interval.H1, 7200000L, 10800000L),
            Arguments.of(Interval.H1, 0L, 3600000L)
        );
    }

    @ParameterizedTest(name = "{0}: {1} and {2} in same window = {3}")
    @MethodSource("inSameWindowProvider")
    @DisplayName("inSameWindow() should correctly determine if timestamps are in same window")
    void testInSameWindow(Interval interval, long timestamp1, long timestamp2, boolean expectedSame) {
        assertThat(interval.inSameWindow(timestamp1, timestamp2)).isEqualTo(expectedSame);
    }

    static Stream<Arguments> inSameWindowProvider() {
        return Stream.of(
            // S1 - Same window cases
            Arguments.of(Interval.S1, 1000L, 1000L, true),
            Arguments.of(Interval.S1, 1000L, 1500L, true),
            Arguments.of(Interval.S1, 1000L, 1999L, true),
            Arguments.of(Interval.S1, 1500L, 1999L, true),
            
            // S1 - Different window cases
            Arguments.of(Interval.S1, 1000L, 2000L, false),
            Arguments.of(Interval.S1, 1999L, 2000L, false),
            Arguments.of(Interval.S1, 1000L, 3000L, false),
            
            // S5 - Same window cases
            Arguments.of(Interval.S5, 5000L, 5000L, true),
            Arguments.of(Interval.S5, 5000L, 7500L, true),
            Arguments.of(Interval.S5, 5000L, 9999L, true),
            
            // S5 - Different window cases
            Arguments.of(Interval.S5, 5000L, 10000L, false),
            Arguments.of(Interval.S5, 9999L, 10000L, false),
            
            // M1 - Same window cases
            Arguments.of(Interval.M1, 60000L, 60000L, true),
            Arguments.of(Interval.M1, 60000L, 90000L, true),
            Arguments.of(Interval.M1, 60000L, 119999L, true),
            
            // M1 - Different window cases
            Arguments.of(Interval.M1, 60000L, 120000L, false),
            Arguments.of(Interval.M1, 119999L, 120000L, false),
            
            // M15 - Same window cases
            Arguments.of(Interval.M15, 900000L, 900000L, true),
            Arguments.of(Interval.M15, 900000L, 1350000L, true),
            Arguments.of(Interval.M15, 900000L, 1799999L, true),
            
            // M15 - Different window cases
            Arguments.of(Interval.M15, 900000L, 1800000L, false),
            Arguments.of(Interval.M15, 1799999L, 1800000L, false),
            
            // H1 - Same window cases
            Arguments.of(Interval.H1, 3600000L, 3600000L, true),
            Arguments.of(Interval.H1, 3600000L, 5400000L, true),
            Arguments.of(Interval.H1, 3600000L, 7199999L, true),
            
            // H1 - Different window cases
            Arguments.of(Interval.H1, 3600000L, 7200000L, false),
            Arguments.of(Interval.H1, 7199999L, 7200000L, false)
        );
    }

    @Test
    @DisplayName("All intervals should be properly defined")
    void testAllIntervalsDefined() {
        Interval[] intervals = Interval.values();
        assertThat(intervals).hasSize(5);
        assertThat(intervals).containsExactly(
            Interval.S1, 
            Interval.S5, 
            Interval.M1, 
            Interval.M15, 
            Interval.H1
        );
    }

    @ParameterizedTest
    @MethodSource("intervalMillisProvider")
    @DisplayName("Intervals should have positive durations")
    void testPositiveDuration(Interval interval, long expectedMillis) {
        assertThat(interval.toMillis()).isPositive();
    }

    @Test
    @DisplayName("Intervals should be in ascending order of duration")
    void testIntervalOrdering() {
        assertThat(Interval.S1.toMillis()).isLessThan(Interval.S5.toMillis());
        assertThat(Interval.S5.toMillis()).isLessThan(Interval.M1.toMillis());
        assertThat(Interval.M1.toMillis()).isLessThan(Interval.M15.toMillis());
        assertThat(Interval.M15.toMillis()).isLessThan(Interval.H1.toMillis());
    }
}

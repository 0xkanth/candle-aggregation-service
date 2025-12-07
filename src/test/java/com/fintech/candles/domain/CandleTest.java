package com.fintech.candles.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Candle Tests")
class CandleTest {

    @Test
    @DisplayName("Should create valid Candle with all OHLC values")
    void testCreateCandle() {
        Candle candle = new Candle(1000L, 100.0, 105.0, 99.0, 103.0, 50L);
        
        assertThat(candle.time()).isEqualTo(1000L);
        assertThat(candle.open()).isEqualTo(100.0);
        assertThat(candle.high()).isEqualTo(105.0);
        assertThat(candle.low()).isEqualTo(99.0);
        assertThat(candle.close()).isEqualTo(103.0);
        assertThat(candle.volume()).isEqualTo(50L);
    }

    @Test
    @DisplayName("of() should create candle with single price")
    void testOfFactoryMethod() {
        Candle candle = Candle.of(1000L, 100.0);
        
        assertThat(candle.time()).isEqualTo(1000L);
        assertThat(candle.open()).isEqualTo(100.0);
        assertThat(candle.high()).isEqualTo(100.0);
        assertThat(candle.low()).isEqualTo(100.0);
        assertThat(candle.close()).isEqualTo(100.0);
        assertThat(candle.volume()).isEqualTo(1L);
    }

    @ParameterizedTest(name = "high={0} < low={1} should throw exception")
    @CsvSource({
        "99.0, 100.0",
        "50.0, 51.0",
        "0.0, 0.1"
    })
    @DisplayName("Constructor should reject high < low")
    void testHighLessThanLow(double high, double low) {
        assertThatThrownBy(() -> new Candle(1000L, 100.0, high, low, 100.0, 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("High price")
            .hasMessageContaining("cannot be less than low price");
    }

    @ParameterizedTest(name = "high={0}, open={1}, close={2} should throw exception")
    @MethodSource("invalidHighProvider")
    @DisplayName("Constructor should reject high < open or high < close")
    void testHighLessThanOpenOrClose(double high, double open, double close) {
        assertThatThrownBy(() -> new Candle(1000L, open, high, 90.0, close, 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("High price")
            .hasMessageContaining("must be >=");
    }

    static Stream<Arguments> invalidHighProvider() {
        return Stream.of(
            // high < open
            Arguments.of(99.0, 100.0, 95.0),
            Arguments.of(95.0, 100.0, 92.0),
            // high < close
            Arguments.of(99.0, 95.0, 100.0),
            Arguments.of(95.0, 92.0, 100.0)
        );
    }

    @ParameterizedTest(name = "low={0}, open={1}, close={2} should throw exception")
    @MethodSource("invalidLowProvider")
    @DisplayName("Constructor should reject low > open or low > close")
    void testLowGreaterThanOpenOrClose(double low, double open, double close) {
        assertThatThrownBy(() -> new Candle(1000L, open, 110.0, low, close, 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Low price")
            .hasMessageContaining("must be <=");
    }

    static Stream<Arguments> invalidLowProvider() {
        return Stream.of(
            // low > open
            Arguments.of(101.0, 100.0, 105.0),
            Arguments.of(105.0, 100.0, 108.0),
            // low > close
            Arguments.of(101.0, 95.0, 100.0),
            Arguments.of(105.0, 98.0, 100.0)
        );
    }

    @ParameterizedTest(name = "open={0}, high={1}, low={2}, close={3} -> range={4}")
    @CsvSource({
        "100.0, 105.0, 99.0, 103.0, 6.0",
        "100.0, 100.0, 100.0, 100.0, 0.0",
        "50.0, 60.0, 40.0, 55.0, 20.0",
        "1.0, 1.5, 0.5, 1.2, 1.0"
    })
    @DisplayName("range() should calculate correct high-low difference")
    void testRange(double open, double high, double low, double close, double expectedRange) {
        Candle candle = new Candle(1000L, open, high, low, close, 1L);
        assertThat(candle.range()).isEqualTo(expectedRange);
    }

    @ParameterizedTest(name = "open={0}, close={1} -> body={2}")
    @CsvSource({
        "100.0, 103.0, 3.0",
        "100.0, 97.0, -3.0",
        "100.0, 100.0, 0.0",
        "50.0, 60.0, 10.0",
        "50.0, 40.0, -10.0"
    })
    @DisplayName("body() should calculate correct close-open difference")
    void testBody(double open, double close, double expectedBody) {
        double high = Math.max(open, close);
        double low = Math.min(open, close);
        Candle candle = new Candle(1000L, open, high, low, close, 1L);
        assertThat(candle.body()).isEqualTo(expectedBody);
    }

    @ParameterizedTest(name = "open={0}, close={1} -> bullish={2}")
    @CsvSource({
        "100.0, 103.0, true",
        "100.0, 100.1, true",
        "100.0, 97.0, false",
        "100.0, 100.0, false",
        "50.0, 60.0, true",
        "60.0, 50.0, false"
    })
    @DisplayName("isBullish() should return true when close > open")
    void testIsBullish(double open, double close, boolean expectedBullish) {
        Candle candle = new Candle(1000L, open, Math.max(open, close), Math.min(open, close), close, 1L);
        assertThat(candle.isBullish()).isEqualTo(expectedBullish);
    }

    @ParameterizedTest(name = "open={0}, close={1} -> bearish={2}")
    @CsvSource({
        "100.0, 97.0, true",
        "100.0, 99.9, true",
        "100.0, 103.0, false",
        "100.0, 100.0, false",
        "60.0, 50.0, true",
        "50.0, 60.0, false"
    })
    @DisplayName("isBearish() should return true when close < open")
    void testIsBearish(double open, double close, boolean expectedBearish) {
        Candle candle = new Candle(1000L, open, Math.max(open, close), Math.min(open, close), close, 1L);
        assertThat(candle.isBearish()).isEqualTo(expectedBearish);
    }

    @ParameterizedTest(name = "open={0}, close={1} -> doji={2}")
    @MethodSource("dojiProvider")
    @DisplayName("isDoji() should detect candles with close ≈ open")
    void testIsDoji(double open, double close, boolean expectedDoji) {
        Candle candle = new Candle(1000L, open, Math.max(open, close), Math.min(open, close), close, 1L);
        assertThat(candle.isDoji()).isEqualTo(expectedDoji);
    }

    static Stream<Arguments> dojiProvider() {
        return Stream.of(
            // Perfect doji
            Arguments.of(100.0, 100.0, true),
            
            // Very small difference (< 0.0001 * open)
            Arguments.of(100.0, 100.0001, true),
            Arguments.of(100.0, 99.9999, true),
            Arguments.of(10000.0, 10000.0001, true),
            Arguments.of(10000.0, 9999.9999, true),
            
            // Larger difference (>= 0.0001 * open)
            Arguments.of(100.0, 100.01, false),
            Arguments.of(100.0, 99.99, false),
            Arguments.of(100.0, 101.0, false),
            Arguments.of(100.0, 99.0, false),
            Arguments.of(10000.0, 10001.0, false),
            Arguments.of(10000.0, 9999.0, false)
        );
    }

    @Test
    @DisplayName("Candle with close > open should be bullish but not bearish")
    void testBullishCandle() {
        Candle candle = new Candle(1000L, 100.0, 105.0, 99.0, 103.0, 50L);
        
        assertThat(candle.isBullish()).isTrue();
        assertThat(candle.isBearish()).isFalse();
        assertThat(candle.isDoji()).isFalse();
    }

    @Test
    @DisplayName("Candle with close < open should be bearish but not bullish")
    void testBearishCandle() {
        Candle candle = new Candle(1000L, 100.0, 105.0, 95.0, 97.0, 50L);
        
        assertThat(candle.isBullish()).isFalse();
        assertThat(candle.isBearish()).isTrue();
        assertThat(candle.isDoji()).isFalse();
    }

    @Test
    @DisplayName("Candle with close = open should be doji")
    void testDojiCandle() {
        Candle candle = new Candle(1000L, 100.0, 105.0, 95.0, 100.0, 50L);
        
        assertThat(candle.isBullish()).isFalse();
        assertThat(candle.isBearish()).isFalse();
        assertThat(candle.isDoji()).isTrue();
    }

    @Test
    @DisplayName("Valid candle with low=open and high=close should be accepted")
    void testValidCandleLowEqualsOpenHighEqualsClose() {
        Candle candle = new Candle(1000L, 100.0, 110.0, 100.0, 110.0, 10L);
        
        assertThat(candle.open()).isEqualTo(100.0);
        assertThat(candle.high()).isEqualTo(110.0);
        assertThat(candle.low()).isEqualTo(100.0);
        assertThat(candle.close()).isEqualTo(110.0);
    }

    @Test
    @DisplayName("Valid candle with all OHLC equal should be accepted")
    void testValidCandleAllEqual() {
        Candle candle = new Candle(1000L, 100.0, 100.0, 100.0, 100.0, 1L);
        
        assertThat(candle.range()).isEqualTo(0.0);
        assertThat(candle.body()).isEqualTo(0.0);
        assertThat(candle.isDoji()).isTrue();
    }

    @Test
    @DisplayName("Candle should be serializable")
    void testSerializable() {
        Candle candle = new Candle(1000L, 100.0, 105.0, 99.0, 103.0, 50L);
        assertThat(candle).isInstanceOf(java.io.Serializable.class);
    }

    @Test
    @DisplayName("Two candles with same values should be equal")
    void testEquality() {
        Candle candle1 = new Candle(1000L, 100.0, 105.0, 99.0, 103.0, 50L);
        Candle candle2 = new Candle(1000L, 100.0, 105.0, 99.0, 103.0, 50L);
        
        assertThat(candle1).isEqualTo(candle2);
        assertThat(candle1.hashCode()).isEqualTo(candle2.hashCode());
    }

    @Test
    @DisplayName("Two candles with different values should not be equal")
    void testInequality() {
        Candle candle1 = new Candle(1000L, 100.0, 105.0, 99.0, 103.0, 50L);
        Candle candle2 = new Candle(2000L, 100.0, 105.0, 99.0, 103.0, 50L);
        Candle candle3 = new Candle(1000L, 101.0, 105.0, 99.0, 103.0, 50L);
        
        assertThat(candle1).isNotEqualTo(candle2);
        assertThat(candle1).isNotEqualTo(candle3);
    }

    @Test
    @DisplayName("Candle created with of() should be valid doji")
    void testFactoryMethodCreatesDoji() {
        Candle candle = Candle.of(1000L, 100.0);
        
        assertThat(candle.isDoji()).isTrue();
        assertThat(candle.range()).isEqualTo(0.0);
        assertThat(candle.body()).isEqualTo(0.0);
    }
}

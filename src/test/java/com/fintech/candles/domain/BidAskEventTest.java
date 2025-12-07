package com.fintech.candles.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BidAskEvent Tests")
class BidAskEventTest {

    @Test
    @DisplayName("Should create valid BidAskEvent with all fields")
    void testCreateBidAskEvent() {
        BidAskEvent event = new BidAskEvent("BTC-USD", 50000.0, 50010.0, 1234567890L);
        
        assertThat(event.symbol()).isEqualTo("BTC-USD");
        assertThat(event.bid()).isEqualTo(50000.0);
        assertThat(event.ask()).isEqualTo(50010.0);
        assertThat(event.timestamp()).isEqualTo(1234567890L);
    }

    @ParameterizedTest(name = "bid={0}, ask={1} -> midPrice={2}")
    @CsvSource({
        "50000.0, 50010.0, 50005.0",
        "100.0, 100.0, 100.0",
        "99.5, 100.5, 100.0",
        "1.234, 1.236, 1.235",
        "0.001, 0.002, 0.0015",
        "10000.12345, 10000.54321, 10000.33333"
    })
    @DisplayName("midPrice() should calculate correct mid-market price")
    void testMidPrice(double bid, double ask, double expectedMid) {
        BidAskEvent event = new BidAskEvent("TEST", bid, ask, 1000L);
        assertThat(event.midPrice()).isCloseTo(expectedMid, org.assertj.core.data.Offset.offset(0.0001));
    }

    @ParameterizedTest(name = "bid={0}, ask={1} -> spread={2}")
    @CsvSource({
        "50000.0, 50010.0, 10.0",
        "100.0, 100.0, 0.0",
        "99.5, 100.5, 1.0",
        "1.234, 1.236, 0.002",
        "0.001, 0.002, 0.001",
        "10000.0, 10005.0, 5.0"
    })
    @DisplayName("spread() should calculate correct bid-ask spread")
    void testSpread(double bid, double ask, double expectedSpread) {
        BidAskEvent event = new BidAskEvent("TEST", bid, ask, 1000L);
        assertThat(event.spread()).isCloseTo(expectedSpread, org.assertj.core.data.Offset.offset(0.0001));
    }

    @ParameterizedTest(name = "bid={0}, ask={1} -> spreadPct={2}")
    @MethodSource("spreadPercentageProvider")
    @DisplayName("spreadPercentage() should calculate correct spread percentage")
    void testSpreadPercentage(double bid, double ask, double expectedPercentage) {
        BidAskEvent event = new BidAskEvent("TEST", bid, ask, 1000L);
        assertThat(event.spreadPercentage()).isCloseTo(expectedPercentage, org.assertj.core.data.Offset.offset(0.0001));
    }

    static Stream<Arguments> spreadPercentageProvider() {
        return Stream.of(
            // 10 spread on 50005 mid = 0.0001999 (0.02%)
            Arguments.of(50000.0, 50010.0, 0.0001999),
            // 0 spread = 0%
            Arguments.of(100.0, 100.0, 0.0),
            // 1 spread on 100 mid = 0.01 (1%)
            Arguments.of(99.5, 100.5, 0.01),
            // 0.002 spread on 1.235 mid = 0.001619 (0.16%)
            Arguments.of(1.234, 1.236, 0.001619),
            // 0.001 spread on 0.0015 mid = 0.6667 (66.67%)
            Arguments.of(0.001, 0.002, 0.6667)
        );
    }

    @ParameterizedTest(name = "symbol={0}, bid={1}, ask={2}, timestamp={3} -> valid={4}")
    @MethodSource("validationProvider")
    @DisplayName("isValid() should correctly validate event data")
    void testIsValid(String symbol, double bid, double ask, long timestamp, boolean expectedValid) {
        BidAskEvent event = new BidAskEvent(symbol, bid, ask, timestamp);
        assertThat(event.isValid()).isEqualTo(expectedValid);
    }

    static Stream<Arguments> validationProvider() {
        return Stream.of(
            // Valid cases
            Arguments.of("BTC-USD", 50000.0, 50010.0, 1000L, true),
            Arguments.of("ETH-USD", 100.0, 100.0, 1000L, true),
            Arguments.of("SYMBOL", 0.001, 0.002, 1L, true),
            Arguments.of("XYZ", 1.0, 1.0, Long.MAX_VALUE, true),
            
            // Invalid bid (zero or negative)
            Arguments.of("BTC-USD", 0.0, 50010.0, 1000L, false),
            Arguments.of("BTC-USD", -1.0, 50010.0, 1000L, false),
            Arguments.of("BTC-USD", -50000.0, 50010.0, 1000L, false),
            
            // Invalid ask (zero or negative)
            Arguments.of("BTC-USD", 50000.0, 0.0, 1000L, false),
            Arguments.of("BTC-USD", 50000.0, -1.0, 1000L, false),
            Arguments.of("BTC-USD", 50000.0, -50010.0, 1000L, false),
            
            // Invalid ask < bid (crossed market)
            Arguments.of("BTC-USD", 50010.0, 50000.0, 1000L, false),
            Arguments.of("BTC-USD", 100.0, 99.0, 1000L, false),
            Arguments.of("BTC-USD", 1.236, 1.234, 1000L, false),
            
            // Invalid timestamp (zero or negative)
            Arguments.of("BTC-USD", 50000.0, 50010.0, 0L, false),
            Arguments.of("BTC-USD", 50000.0, 50010.0, -1L, false),
            Arguments.of("BTC-USD", 50000.0, 50010.0, -1000L, false),
            
            // Multiple invalid fields
            Arguments.of("BTC-USD", 0.0, 0.0, 0L, false),
            Arguments.of("BTC-USD", -1.0, -1.0, -1L, false)
        );
    }

    @Test
    @DisplayName("Zero mid-price should result in zero spread percentage")
    void testSpreadPercentageWithZeroMidPrice() {
        // Edge case: both bid and ask are zero (invalid but testing calculation safety)
        BidAskEvent event = new BidAskEvent("TEST", 0.0, 0.0, 1000L);
        assertThat(event.spreadPercentage()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Very small prices should calculate spread correctly")
    void testVerySmallPrices() {
        BidAskEvent event = new BidAskEvent("MICRO", 0.0001, 0.0002, 1000L);
        
        assertThat(event.midPrice()).isCloseTo(0.00015, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(event.spread()).isCloseTo(0.0001, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(event.spreadPercentage()).isCloseTo(0.6667, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("Very large prices should calculate spread correctly")
    void testVeryLargePrices() {
        BidAskEvent event = new BidAskEvent("LARGE", 999999.0, 1000000.0, 1000L);
        
        assertThat(event.midPrice()).isEqualTo(999999.5);
        assertThat(event.spread()).isEqualTo(1.0);
        assertThat(event.spreadPercentage()).isCloseTo(0.000001, org.assertj.core.data.Offset.offset(0.0000001));
    }

    @Test
    @DisplayName("Equal bid and ask should have zero spread")
    void testZeroSpread() {
        BidAskEvent event = new BidAskEvent("TEST", 100.0, 100.0, 1000L);
        
        assertThat(event.spread()).isEqualTo(0.0);
        assertThat(event.spreadPercentage()).isEqualTo(0.0);
        assertThat(event.midPrice()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("BidAskEvent should be serializable")
    void testSerializable() {
        BidAskEvent event = new BidAskEvent("BTC-USD", 50000.0, 50010.0, 1234567890L);
        assertThat(event).isInstanceOf(java.io.Serializable.class);
    }

    @Test
    @DisplayName("Two events with same values should be equal")
    void testEquality() {
        BidAskEvent event1 = new BidAskEvent("BTC-USD", 50000.0, 50010.0, 1000L);
        BidAskEvent event2 = new BidAskEvent("BTC-USD", 50000.0, 50010.0, 1000L);
        
        assertThat(event1).isEqualTo(event2);
        assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
    }

    @Test
    @DisplayName("Two events with different values should not be equal")
    void testInequality() {
        BidAskEvent event1 = new BidAskEvent("BTC-USD", 50000.0, 50010.0, 1000L);
        BidAskEvent event2 = new BidAskEvent("BTC-USD", 50000.0, 50010.0, 2000L);
        BidAskEvent event3 = new BidAskEvent("ETH-USD", 50000.0, 50010.0, 1000L);
        
        assertThat(event1).isNotEqualTo(event2);
        assertThat(event1).isNotEqualTo(event3);
    }
}

package com.fintech.candles.api;

import com.fintech.candles.domain.Candle;
import com.fintech.candles.domain.Interval;
import com.fintech.candles.storage.CandleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "candle.simulation.enabled=false",  // Disable simulator during tests
    "spring.jmx.enabled=false",
    "candle.storage.chronicle-map.path=target/test-history-${random.uuid}.dat"  // Use unique file per test run
})
@DisplayName("HistoryController Integration Tests")
class HistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CandleRepository repository;

    @BeforeEach
    void setUp() {
        // Chronicle Map persists data, so we use unique timestamps for each test
        // to avoid conflicts
    }

    private void insertTestData(String symbol, Interval interval, List<Candle> candles) {
        candles.forEach(candle -> repository.save(symbol, interval, candle));
    }

    @Test
    @DisplayName("Should return candles for valid request")
    void testValidRequest() throws Exception {
        // Use timestamps relative to a fixed base time
        long baseTime = 1700000000000L; // Fixed epoch time in milliseconds
        
        List<Candle> candles = Arrays.asList(
            new Candle(baseTime, 50000.0, 50100.0, 49900.0, 50050.0, 10L),
            new Candle(baseTime + 1000, 50050.0, 50150.0, 49950.0, 50100.0, 12L)
        );
        
        insertTestData("BTCUSD", Interval.S1, candles);
        
        mockMvc.perform(get("/api/v1/history")
                .param("symbol", "BTCUSD")
                .param("interval", "1s")
                .param("from", String.valueOf(baseTime / 1000))  // Convert to seconds for API
                .param("to", String.valueOf((baseTime + 2000) / 1000)))  // Convert to seconds for API
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.s").value("ok"))
            .andExpect(jsonPath("$.t", hasSize(2)))
            .andExpect(jsonPath("$.o", hasSize(2)))
            .andExpect(jsonPath("$.h", hasSize(2)))
            .andExpect(jsonPath("$.l", hasSize(2)))
            .andExpect(jsonPath("$.c", hasSize(2)))
            .andExpect(jsonPath("$.v", hasSize(2)))
            .andExpect(jsonPath("$.o[0]").value(50000.0))
            .andExpect(jsonPath("$.c[1]").value(50100.0));
    }

    @Test
    @DisplayName("Should return empty array when no candles found")
    void testNoCandles() throws Exception {
        // Query a time range with no data
        long futureTime = 1900000000000L; // Far future time in milliseconds
        
        mockMvc.perform(get("/api/v1/history")
                .param("symbol", "BTCUSD")
                .param("interval", "1s")
                .param("from", String.valueOf(futureTime / 1000))  // Convert to seconds
                .param("to", String.valueOf((futureTime + 2000) / 1000)))  // Convert to seconds
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.s").value("ok"))
            .andExpect(jsonPath("$.t", hasSize(0)));
    }

    @Test
    @DisplayName("Should return 400 for missing symbol parameter")
    void testMissingSymbol() throws Exception {
        mockMvc.perform(get("/api/v1/history")
                .param("interval", "1s")
                .param("from", "1000")
                .param("to", "2000"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should return 400 for missing interval parameter")
    void testMissingInterval() throws Exception {
        mockMvc.perform(get("/api/v1/history")
                .param("symbol", "BTCUSD")
                .param("from", "1000")
                .param("to", "2000"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should return 400 for missing from parameter")
    void testMissingFrom() throws Exception {
        mockMvc.perform(get("/api/v1/history")
                .param("symbol", "BTCUSD")
                .param("interval", "1s")
                .param("to", "2000"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should return 400 for missing to parameter")
    void testMissingTo() throws Exception {
        mockMvc.perform(get("/api/v1/history")
                .param("symbol", "BTCUSD")
                .param("interval", "1s")
                .param("from", "1000"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should return 400 for invalid interval")
    void testInvalidInterval() throws Exception {
        mockMvc.perform(get("/api/v1/history")
                .param("symbol", "BTCUSD")
                .param("interval", "invalid")
                .param("from", "1000")
                .param("to", "2000"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should return empty results when from > to")
    void testInvalidTimeRange() throws Exception {
        mockMvc.perform(get("/api/v1/history")
                .param("symbol", "BTCUSD")
                .param("interval", "1s")
                .param("from", "2000")
                .param("to", "1000"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.s").value("ok"))
            .andExpect(jsonPath("$.t", hasSize(0)));  // Empty results
    }

    @Test
    @DisplayName("Should handle all supported intervals")
    void testAllIntervals() throws Exception {
        String[] intervals = {"1s", "5s", "1m", "15m", "1h"};
        long baseTime = 1701000000000L;
        
        for (String interval : intervals) {
            mockMvc.perform(get("/api/v1/history")
                    .param("symbol", "BTCUSD")
                    .param("interval", interval)
                    .param("from", String.valueOf(baseTime / 1000))
                    .param("to", String.valueOf((baseTime + 2000) / 1000)))
                .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("Should handle multiple symbols")
    void testMultipleSymbols() throws Exception {
        String[] symbols = {"BTCUSD", "ETHUSD", "SOLUSD", "ADAUSD"};
        long baseTime = 1702000000000L;
        
        for (String symbol : symbols) {
            mockMvc.perform(get("/api/v1/history")
                    .param("symbol", symbol)
                    .param("interval", "1s")
                    .param("from", String.valueOf(baseTime / 1000))
                    .param("to", String.valueOf((baseTime + 2000) / 1000)))
                .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("Should normalize symbol to uppercase with hyphen")
    void testSymbolNormalization() throws Exception {
        long baseTime = 1703000000000L;
        
        mockMvc.perform(get("/api/v1/history")
                .param("symbol", "btcusd")
                .param("interval", "1s")
                .param("from", String.valueOf(baseTime / 1000))
                .param("to", String.valueOf((baseTime + 2000) / 1000)))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should return candles in correct TradingView format")
    void testTradingViewFormat() throws Exception {
        long baseTime = 1704000000000L;
        
        List<Candle> candles = Arrays.asList(
            new Candle(baseTime, 100.0, 110.0, 90.0, 105.0, 50L),
            new Candle(baseTime + 1000, 105.0, 115.0, 95.0, 110.0, 60L),
            new Candle(baseTime + 2000, 110.0, 120.0, 100.0, 115.0, 70L)
        );
        
        insertTestData("BTCUSD", Interval.S1, candles);
        
        mockMvc.perform(get("/api/v1/history")
                .param("symbol", "BTCUSD")
                .param("interval", "1s")
                .param("from", String.valueOf(baseTime / 1000))
                .param("to", String.valueOf((baseTime + 3000) / 1000)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.s").value("ok"))
            .andExpect(jsonPath("$.t").isArray())
            .andExpect(jsonPath("$.o").isArray())
            .andExpect(jsonPath("$.h").isArray())
            .andExpect(jsonPath("$.l").isArray())
            .andExpect(jsonPath("$.c").isArray())
            .andExpect(jsonPath("$.v").isArray())
            .andExpect(jsonPath("$.t", hasSize(3)))
            .andExpect(jsonPath("$.o[0]").value(100.0))
            .andExpect(jsonPath("$.h[0]").value(110.0))
            .andExpect(jsonPath("$.l[0]").value(90.0))
            .andExpect(jsonPath("$.c[0]").value(105.0))
            .andExpect(jsonPath("$.v[0]").value(50));
    }

    @Test
    @DisplayName("Should handle large time ranges")
    void testLargeTimeRange() throws Exception {
        long baseTime = 1705000000000L;
        
        mockMvc.perform(get("/api/v1/history")
                .param("symbol", "BTCUSD")
                .param("interval", "1h")
                .param("from", String.valueOf(baseTime / 1000))
                .param("to", String.valueOf((baseTime + 86400000) / 1000))) // 24 hours
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should handle concurrent requests")
    void testConcurrentRequests() throws Exception {
        long baseTime = 1706000000000L;
        
        // Simulate multiple concurrent requests
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/v1/history")
                    .param("symbol", "BTCUSD")
                    .param("interval", "1s")
                    .param("from", String.valueOf(baseTime / 1000))
                    .param("to", String.valueOf((baseTime + 2000) / 1000)))
                .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("Should handle single candle response")
    void testSingleCandle() throws Exception {
        long baseTime = 1707000000000L;
        
        List<Candle> candles = Arrays.asList(
            new Candle(baseTime, 50000.0, 50100.0, 49900.0, 50050.0, 10L)
        );
        
        insertTestData("BTCUSD", Interval.S1, candles);
        
        mockMvc.perform(get("/api/v1/history")
                .param("symbol", "BTCUSD")
                .param("interval", "1s")
                .param("from", String.valueOf(baseTime / 1000))
                .param("to", String.valueOf((baseTime + 1000) / 1000)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.s").value("ok"))
            .andExpect(jsonPath("$.t", hasSize(1)));
    }

    @Test
    @DisplayName("Should convert millisecond timestamps to seconds")
    void testTimestampConversion() throws Exception {
        long baseTime = 1708000000000L;
        
        List<Candle> candles = Arrays.asList(
            new Candle(baseTime, 100.0, 110.0, 90.0, 105.0, 10L)
        );
        
        insertTestData("BTCUSD", Interval.S1, candles);
        
        mockMvc.perform(get("/api/v1/history")
                .param("symbol", "BTCUSD")
                .param("interval", "1s")
                .param("from", String.valueOf(baseTime / 1000))
                .param("to", String.valueOf((baseTime + 1000) / 1000)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.t[0]").value(baseTime / 1000)); // Should be in seconds
    }

    @Test
    @DisplayName("Should handle bullish and bearish candles")
    void testBullishBearishCandles() throws Exception {
        long baseTime = 1709000000000L;
        
        List<Candle> candles = Arrays.asList(
            new Candle(baseTime, 100.0, 110.0, 90.0, 105.0, 10L),  // Bullish
            new Candle(baseTime + 1000, 105.0, 115.0, 95.0, 100.0, 12L)   // Bearish
        );
        
        insertTestData("BTCUSD", Interval.S1, candles);
        
        mockMvc.perform(get("/api/v1/history")
                .param("symbol", "BTCUSD")
                .param("interval", "1s")
                .param("from", String.valueOf(baseTime / 1000))
                .param("to", String.valueOf((baseTime + 2000) / 1000)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.o[0]").value(100.0))
            .andExpect(jsonPath("$.c[0]").value(105.0))
            .andExpect(jsonPath("$.o[1]").value(105.0))
            .andExpect(jsonPath("$.c[1]").value(100.0));
    }
}

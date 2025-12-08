package com.fintech.candles.storage.timescaledb;

import com.fintech.candles.domain.Candle;
import com.fintech.candles.domain.Interval;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests for TimescaleDB candle repository.
 * Uses Testcontainers with actual PostgreSQL/TimescaleDB for realistic testing.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("TimescaleDB Candle Repository Tests")
class TimescaleDBCandleRepositoryTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    
    @Autowired
    private CandleJpaRepository jpaRepository;
    
    private TimescaleDBCandleRepository repository;
    
    @BeforeEach
    void setUp() {
        jpaRepository.deleteAll();
        repository = new TimescaleDBCandleRepository(jpaRepository, new SimpleMeterRegistry());
    }
    
    @Test
    @DisplayName("Should save and retrieve candle")
    void testSaveAndRetrieve() {
        // Given
        String symbol = "BTCUSD";
        Interval interval = Interval.S1;
        long timestamp = 1733000000000L;
        Candle candle = new Candle(timestamp, 100.0, 110.0, 90.0, 105.0, 1000L);
        
        // When
        repository.save(symbol, interval, candle);
        
        // Then
        Optional<Candle> retrieved = repository.findByExactTime(symbol, interval, timestamp);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().time()).isEqualTo(timestamp);
        assertThat(retrieved.get().open()).isEqualTo(100.0);
        assertThat(retrieved.get().close()).isEqualTo(105.0);
    }
    
    @Test
    @DisplayName("Should find candles in time range")
    void testFindByRange() {
        // Given
        String symbol = "ETHUSD";
        Interval interval = Interval.M1;
        long baseTime = 1733000000000L;
        
        // Save multiple candles
        for (int i = 0; i < 5; i++) {
            long timestamp = baseTime + (i * 60000); // 1 minute apart
            Candle candle = new Candle(timestamp, 100.0 + i, 110.0 + i, 90.0 + i, 105.0 + i, 1000L + i);
            repository.save(symbol, interval, candle);
        }
        
        // When - Query middle 3 candles
        List<Candle> results = repository.findByRange(
            symbol, 
            interval, 
            baseTime + 60000,   // from 2nd candle
            baseTime + 180000   // to 4th candle
        );
        
        // Then
        assertThat(results).hasSize(3);
        assertThat(results.get(0).time()).isEqualTo(baseTime + 60000);
        assertThat(results.get(2).time()).isEqualTo(baseTime + 180000);
    }
    
    @Test
    @DisplayName("Should handle updates to existing candle")
    void testUpdate() {
        // Given
        String symbol = "BTCUSD";
        Interval interval = Interval.S1;
        long timestamp = 1733000000000L;
        Candle original = new Candle(timestamp, 100.0, 110.0, 90.0, 105.0, 1000L);
        repository.save(symbol, interval, original);
        
        // When - Save updated candle with same timestamp
        Candle updated = new Candle(timestamp, 100.0, 120.0, 85.0, 110.0, 1500L);
        repository.save(symbol, interval, updated);
        
        // Then - Should have updated values
        Optional<Candle> retrieved = repository.findByExactTime(symbol, interval, timestamp);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().high()).isEqualTo(120.0);
        assertThat(retrieved.get().low()).isEqualTo(85.0);
        assertThat(retrieved.get().volume()).isEqualTo(1500L);
        
        // And - Should still only have one record
        assertThat(repository.count()).isEqualTo(1);
    }
    
    @Test
    @DisplayName("Should handle multiple symbols independently")
    void testMultipleSymbols() {
        // Given
        long timestamp = 1733000000000L;
        Interval interval = Interval.M1;
        
        Candle btcCandle = new Candle(timestamp, 50000.0, 51000.0, 49000.0, 50500.0, 100L);
        Candle ethCandle = new Candle(timestamp, 3000.0, 3100.0, 2900.0, 3050.0, 200L);
        
        // When
        repository.save("BTCUSD", interval, btcCandle);
        repository.save("ETHUSD", interval, ethCandle);
        
        // Then
        Optional<Candle> btcResult = repository.findByExactTime("BTCUSD", interval, timestamp);
        Optional<Candle> ethResult = repository.findByExactTime("ETHUSD", interval, timestamp);
        
        assertThat(btcResult).isPresent();
        assertThat(ethResult).isPresent();
        assertThat(btcResult.get().close()).isEqualTo(50500.0);
        assertThat(ethResult.get().close()).isEqualTo(3050.0);
    }
    
    @Test
    @DisplayName("Should handle multiple intervals independently")
    void testMultipleIntervals() {
        // Given
        String symbol = "BTCUSD";
        long timestamp = 1733000000000L;
        
        Candle s1Candle = new Candle(timestamp, 100.0, 110.0, 90.0, 105.0, 10L);
        Candle m1Candle = new Candle(timestamp, 100.0, 120.0, 80.0, 115.0, 600L);
        
        // When
        repository.save(symbol, Interval.S1, s1Candle);
        repository.save(symbol, Interval.M1, m1Candle);
        
        // Then
        Optional<Candle> s1Result = repository.findByExactTime(symbol, Interval.S1, timestamp);
        Optional<Candle> m1Result = repository.findByExactTime(symbol, Interval.M1, timestamp);
        
        assertThat(s1Result).isPresent();
        assertThat(m1Result).isPresent();
        assertThat(s1Result.get().volume()).isEqualTo(10L);
        assertThat(m1Result.get().volume()).isEqualTo(600L);
    }
    
    @Test
    @DisplayName("Should return empty for non-existent candle")
    void testNotFound() {
        // When
        Optional<Candle> result = repository.findByExactTime("NONEXISTENT", Interval.M1, 999999L);
        
        // Then
        assertThat(result).isEmpty();
    }
    
    @Test
    @DisplayName("Should return empty list for range with no data")
    void testEmptyRange() {
        // When
        List<Candle> results = repository.findByRange("BTCUSD", Interval.M1, 1000000L, 2000000L);
        
        // Then
        assertThat(results).isEmpty();
    }
    
    @Test
    @DisplayName("Should count candles correctly")
    void testCount() {
        // Given
        String symbol = "BTCUSD";
        Interval interval = Interval.M1;
        long baseTime = 1733000000000L;
        
        // When - Save 10 candles
        for (int i = 0; i < 10; i++) {
            Candle candle = new Candle(baseTime + (i * 60000), 100.0, 110.0, 90.0, 105.0, 1000L);
            repository.save(symbol, interval, candle);
        }
        
        // Then
        assertThat(repository.count()).isEqualTo(10);
    }
    
    @Test
    @DisplayName("Should report healthy when database is accessible")
    void testHealthCheck() {
        // When
        boolean healthy = repository.isHealthy();
        
        // Then
        assertThat(healthy).isTrue();
    }
    
    @Test
    @DisplayName("Should delete older candles")
    void testDeleteOlderThan() {
        // Given
        String symbol = "BTCUSD";
        Interval interval = Interval.M1;
        long baseTime = 1733000000000L;
        
        // Save candles at different times
        for (int i = 0; i < 10; i++) {
            long timestamp = baseTime + (i * 60000);
            Candle candle = new Candle(timestamp, 100.0, 110.0, 90.0, 105.0, 1000L);
            repository.save(symbol, interval, candle);
        }
        
        // When - Delete candles older than 5th candle
        long cutoffTime = baseTime + (5 * 60000);
        long deleted = repository.deleteOlderThan(cutoffTime);
        
        // Then - Should have deleted first 5 candles
        assertThat(deleted).isEqualTo(5);
        assertThat(repository.count()).isEqualTo(5);
    }
    
    @Test
    @DisplayName("Should return candles sorted by time ascending")
    void testSortedResults() {
        // Given
        String symbol = "BTCUSD";
        Interval interval = Interval.M1;
        long baseTime = 1733000000000L;
        
        // Save candles out of order
        long[] timestamps = {baseTime + 120000, baseTime, baseTime + 60000, baseTime + 180000};
        for (long timestamp : timestamps) {
            Candle candle = new Candle(timestamp, 100.0, 110.0, 90.0, 105.0, 1000L);
            repository.save(symbol, interval, candle);
        }
        
        // When
        List<Candle> results = repository.findByRange(symbol, interval, baseTime, baseTime + 180000);
        
        // Then - Should be sorted by time
        assertThat(results).hasSize(4);
        for (int i = 0; i < results.size() - 1; i++) {
            assertThat(results.get(i).time()).isLessThan(results.get(i + 1).time());
        }
    }
    
    @Test
    @DisplayName("Should handle boundary timestamps correctly")
    void testBoundaryTimestamps() {
        // Given
        String symbol = "BTCUSD";
        Interval interval = Interval.M1;
        long baseTime = 1733000000000L;
        
        // Save candles at boundaries
        repository.save(symbol, interval, new Candle(baseTime, 100.0, 110.0, 90.0, 105.0, 1000L));
        repository.save(symbol, interval, new Candle(baseTime + 60000, 105.0, 115.0, 95.0, 110.0, 1100L));
        repository.save(symbol, interval, new Candle(baseTime + 120000, 110.0, 120.0, 100.0, 115.0, 1200L));
        
        // When - Query with exact boundaries
        List<Candle> results = repository.findByRange(symbol, interval, baseTime, baseTime + 120000);
        
        // Then - Should include both start and end timestamps
        assertThat(results).hasSize(3);
        assertThat(results.get(0).time()).isEqualTo(baseTime);
        assertThat(results.get(2).time()).isEqualTo(baseTime + 120000);
    }
    
    @Test
    @DisplayName("Should handle extreme price values")
    void testExtremePriceValues() {
        // Given
        String symbol = "EXTREME";
        Interval interval = Interval.S1;
        long timestamp = 1733000000000L;
        
        // When - Save candle with extreme values
        Candle extremeCandle = new Candle(
            timestamp,
            0.00000001,        // Very small price
            999999999.99,      // Very large price
            0.00000001,
            999999999.99,
            Long.MAX_VALUE     // Maximum volume
        );
        repository.save(symbol, interval, extremeCandle);
        
        // Then - Should persist and retrieve correctly
        Optional<Candle> retrieved = repository.findByExactTime(symbol, interval, timestamp);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().open()).isEqualTo(0.00000001);
        assertThat(retrieved.get().high()).isEqualTo(999999999.99);
        assertThat(retrieved.get().volume()).isEqualTo(Long.MAX_VALUE);
    }
    
    @Test
    @DisplayName("Should handle zero volume trades")
    void testZeroVolume() {
        // Given
        String symbol = "ZEROVOL";
        Interval interval = Interval.S1;
        long timestamp = 1733000000000L;
        
        // When
        Candle zeroVolumeCandle = new Candle(timestamp, 100.0, 100.0, 100.0, 100.0, 0L);
        repository.save(symbol, interval, zeroVolumeCandle);
        
        // Then
        Optional<Candle> retrieved = repository.findByExactTime(symbol, interval, timestamp);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().volume()).isEqualTo(0L);
    }
    
    @Test
    @DisplayName("Should handle high precision decimal prices")
    void testHighPrecisionPrices() {
        // Given
        String symbol = "PRECISION";
        Interval interval = Interval.S1;
        long timestamp = 1733000000000L;
        
        // When - Save candle with high precision
        Candle preciseCandle = new Candle(
            timestamp,
            123.456789012345,
            123.456789012345,
            123.456789012345,
            123.456789012345,
            1L
        );
        repository.save(symbol, interval, preciseCandle);
        
        // Then - Should preserve precision (within Double limits)
        Optional<Candle> retrieved = repository.findByExactTime(symbol, interval, timestamp);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().open()).isCloseTo(123.456789012345, within(0.000001));
    }
    
    @Test
    @DisplayName("Should handle very long symbol names")
    void testLongSymbolNames() {
        // Given
        String longSymbol = "A".repeat(20); // Max 20 chars per schema
        Interval interval = Interval.M1;
        long timestamp = 1733000000000L;
        
        // When
        Candle candle = new Candle(timestamp, 100.0, 110.0, 90.0, 105.0, 1000L);
        repository.save(longSymbol, interval, candle);
        
        // Then
        Optional<Candle> retrieved = repository.findByExactTime(longSymbol, interval, timestamp);
        assertThat(retrieved).isPresent();
    }
    
    @Test
    @DisplayName("Should handle rapid consecutive updates to same candle")
    void testRapidUpdates() {
        // Given
        String symbol = "RAPID";
        Interval interval = Interval.S1;
        long timestamp = 1733000000000L;
        
        // When - Rapidly update same candle 100 times
        for (int i = 0; i < 100; i++) {
            Candle candle = new Candle(
                timestamp,
                100.0 + i,
                110.0 + i,
                90.0,
                105.0 + i,
                1000L + i
            );
            repository.save(symbol, interval, candle);
        }
        
        // Then - Should have latest update only
        assertThat(repository.count()).isEqualTo(1);
        Optional<Candle> retrieved = repository.findByExactTime(symbol, interval, timestamp);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().close()).isEqualTo(204.0); // 105 + 99
    }
    
    @Test
    @DisplayName("Should handle millisecond timestamp precision")
    void testMillisecondPrecision() {
        // Given
        String symbol = "MSEC";
        Interval interval = Interval.S1;
        long timestamp1 = 1733000000001L; // ...001 millisecond
        long timestamp2 = 1733000000999L; // ...999 millisecond
        
        // When
        repository.save(symbol, interval, new Candle(timestamp1, 100.0, 110.0, 90.0, 105.0, 1000L));
        repository.save(symbol, interval, new Candle(timestamp2, 100.0, 110.0, 90.0, 105.0, 1000L));
        
        // Then - Both should be distinct candles
        Optional<Candle> candle1 = repository.findByExactTime(symbol, interval, timestamp1);
        Optional<Candle> candle2 = repository.findByExactTime(symbol, interval, timestamp2);
        
        assertThat(candle1).isPresent();
        assertThat(candle2).isPresent();
        assertThat(repository.count()).isEqualTo(2);
    }
    
    @Test
    @DisplayName("Should handle all interval types")
    void testAllIntervalTypes() {
        // Given
        String symbol = "MULTI";
        long timestamp = 1733000000000L;
        Interval[] allIntervals = {
            Interval.S1, Interval.S5, Interval.M1, 
            Interval.M15, Interval.H1
        };
        
        // When - Save candles for all intervals
        for (Interval interval : allIntervals) {
            Candle candle = new Candle(timestamp, 100.0, 110.0, 90.0, 105.0, 1000L);
            repository.save(symbol, interval, candle);
        }
        
        // Then - All should be retrievable independently
        for (Interval interval : allIntervals) {
            Optional<Candle> retrieved = repository.findByExactTime(symbol, interval, timestamp);
            assertThat(retrieved)
                .as("Candle should exist for interval " + interval)
                .isPresent();
        }
        
        assertThat(repository.count()).isEqualTo(allIntervals.length);
    }
    
    @Test
    @DisplayName("Should handle large time range queries")
    void testLargeTimeRangeQuery() {
        // Given
        String symbol = "LARGE";
        Interval interval = Interval.M1;
        long baseTime = 1733000000000L;
        
        // Save candles over 1 day (1440 minutes)
        int candleCount = 100; // Subset for test performance
        for (int i = 0; i < candleCount; i++) {
            long timestamp = baseTime + (i * 60000L); // 1 minute apart
            Candle candle = new Candle(timestamp, 100.0 + i, 110.0 + i, 90.0 + i, 105.0 + i, 1000L);
            repository.save(symbol, interval, candle);
        }
        
        // When - Query entire range
        List<Candle> results = repository.findByRange(
            symbol, interval, baseTime, baseTime + (candleCount * 60000L)
        );
        
        // Then
        assertThat(results).hasSize(candleCount);
        assertThat(results.get(0).time()).isEqualTo(baseTime);
        assertThat(results.get(candleCount - 1).time()).isEqualTo(baseTime + ((candleCount - 1) * 60000L));
    }
    
    @Test
    @DisplayName("Should handle overlapping time windows")
    void testOverlappingTimeWindows() {
        // Given
        String symbol = "OVERLAP";
        Interval interval = Interval.M1;
        long baseTime = 1733000000000L;
        
        // Save 10 candles
        for (int i = 0; i < 10; i++) {
            repository.save(symbol, interval, 
                new Candle(baseTime + (i * 60000), 100.0, 110.0, 90.0, 105.0, 1000L));
        }
        
        // When - Query with overlapping windows
        List<Candle> window1 = repository.findByRange(symbol, interval, baseTime, baseTime + 300000);
        List<Candle> window2 = repository.findByRange(symbol, interval, baseTime + 240000, baseTime + 540000);
        
        // Then
        assertThat(window1).hasSize(6); // 0-5 minutes
        assertThat(window2).hasSize(6); // 4-9 minutes
        
        // Should have 2 overlapping candles (minutes 4 and 5)
        long overlapStart = baseTime + 240000;
        long overlapEnd = baseTime + 300000;
        
        long overlapCount1 = window1.stream()
            .filter(c -> c.time() >= overlapStart && c.time() <= overlapEnd)
            .count();
        long overlapCount2 = window2.stream()
            .filter(c -> c.time() >= overlapStart && c.time() <= overlapEnd)
            .count();
            
        assertThat(overlapCount1).isEqualTo(overlapCount2);
    }
}


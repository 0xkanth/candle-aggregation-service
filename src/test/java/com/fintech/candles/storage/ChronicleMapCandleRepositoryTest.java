package com.fintech.candles.storage;

import com.fintech.candles.config.CandleProperties;
import com.fintech.candles.domain.Candle;
import com.fintech.candles.domain.Interval;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChronicleMapCandleRepository Tests")
class ChronicleMapCandleRepositoryTest {

    private CandleProperties properties;
    private ChronicleMapCandleRepository repository;
    private File testDataFile;

    @BeforeEach
    void setUp() {
        properties = new CandleProperties();
        CandleProperties.Storage storage = new CandleProperties.Storage();
        CandleProperties.Storage.ChronicleMapConfig chronicleConfig = new CandleProperties.Storage.ChronicleMapConfig();
        
        testDataFile = new File("target/test-candles-" + System.currentTimeMillis() + ".dat");
        chronicleConfig.setPath(testDataFile.getPath());
        chronicleConfig.setEntries(10000L);
        chronicleConfig.setAverageKeySize(30);
        
        storage.setChronicleMap(chronicleConfig);
        properties.setStorage(storage);
        
        repository = new ChronicleMapCandleRepository(properties);
        repository.initialize();
    }

    @AfterEach
    void tearDown() {
        // Explicitly close the Chronicle Map
        if (repository != null) {
            repository.shutdown();
        }
        
        // Clean up test file
        if (testDataFile != null && testDataFile.exists()) {
            testDataFile.delete();
        }
    }

    @Test
    @DisplayName("Should save and retrieve candle")
    void testSaveAndRetrieve() {
        Candle candle = new Candle(1000L, 50000.0, 50100.0, 49900.0, 50050.0, 10L);
        
        repository.save("BTC-USD", Interval.S1, candle);
        
        Optional<Candle> retrieved = repository.findByExactTime("BTC-USD", Interval.S1, 1000L);
        
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get()).isEqualTo(candle);
    }

    @Test
    @DisplayName("Should return empty when candle not found")
    void testNotFound() {
        Optional<Candle> result = repository.findByExactTime("BTC-USD", Interval.S1, 1000L);
        
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should find candles in time range")
    void testFindByRange() {
        // Save candles at different times
        repository.save("BTC-USD", Interval.S1, new Candle(1000L, 100.0, 110.0, 90.0, 105.0, 10L));
        repository.save("BTC-USD", Interval.S1, new Candle(2000L, 105.0, 115.0, 95.0, 110.0, 12L));
        repository.save("BTC-USD", Interval.S1, new Candle(3000L, 110.0, 120.0, 100.0, 115.0, 15L));
        repository.save("BTC-USD", Interval.S1, new Candle(4000L, 115.0, 125.0, 105.0, 120.0, 18L));
        
        List<Candle> candles = repository.findByRange("BTC-USD", Interval.S1, 1500L, 3500L);
        
        assertThat(candles).hasSize(2);
        assertThat(candles).extracting(Candle::time).containsExactlyInAnyOrder(2000L, 3000L);
    }

    @Test
    @DisplayName("Should return empty list when no candles in range")
    void testEmptyRange() {
        repository.save("BTC-USD", Interval.S1, new Candle(1000L, 100.0, 110.0, 90.0, 105.0, 10L));
        
        List<Candle> candles = repository.findByRange("BTC-USD", Interval.S1, 5000L, 6000L);
        
        assertThat(candles).isEmpty();
    }

    @Test
    @DisplayName("Should handle multiple symbols independently")
    void testMultipleSymbols() {
        Candle btcCandle = new Candle(1000L, 50000.0, 50100.0, 49900.0, 50050.0, 10L);
        Candle ethCandle = new Candle(1000L, 3000.0, 3050.0, 2950.0, 3025.0, 8L);
        
        repository.save("BTC-USD", Interval.S1, btcCandle);
        repository.save("ETH-USD", Interval.S1, ethCandle);
        
        Optional<Candle> btcRetrieved = repository.findByExactTime("BTC-USD", Interval.S1, 1000L);
        Optional<Candle> ethRetrieved = repository.findByExactTime("ETH-USD", Interval.S1, 1000L);
        
        assertThat(btcRetrieved).isPresent();
        assertThat(ethRetrieved).isPresent();
        assertThat(btcRetrieved.get()).isEqualTo(btcCandle);
        assertThat(ethRetrieved.get()).isEqualTo(ethCandle);
    }

    @Test
    @DisplayName("Should handle multiple intervals independently")
    void testMultipleIntervals() {
        Candle s1Candle = new Candle(1000L, 50000.0, 50100.0, 49900.0, 50050.0, 10L);
        Candle m1Candle = new Candle(60000L, 50000.0, 51000.0, 49000.0, 50500.0, 100L);
        
        repository.save("BTC-USD", Interval.S1, s1Candle);
        repository.save("BTC-USD", Interval.M1, m1Candle);
        
        Optional<Candle> s1Retrieved = repository.findByExactTime("BTC-USD", Interval.S1, 1000L);
        Optional<Candle> m1Retrieved = repository.findByExactTime("BTC-USD", Interval.M1, 60000L);
        
        assertThat(s1Retrieved).isPresent();
        assertThat(m1Retrieved).isPresent();
        assertThat(s1Retrieved.get()).isEqualTo(s1Candle);
        assertThat(m1Retrieved.get()).isEqualTo(m1Candle);
    }

    @ParameterizedTest(name = "Interval: {0}")
    @MethodSource("intervalProvider")
    @DisplayName("Should handle all supported intervals")
    void testAllIntervals(Interval interval) {
        long timestamp = interval.alignTimestamp(1000000L);
        Candle candle = new Candle(timestamp, 100.0, 110.0, 90.0, 105.0, 10L);
        
        repository.save("BTC-USD", interval, candle);
        Optional<Candle> retrieved = repository.findByExactTime("BTC-USD", interval, timestamp);
        
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get()).isEqualTo(candle);
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
    @DisplayName("Should update existing candle")
    void testUpdate() {
        Candle original = new Candle(1000L, 50000.0, 50100.0, 49900.0, 50050.0, 10L);
        Candle updated = new Candle(1000L, 50000.0, 50200.0, 49800.0, 50150.0, 15L);
        
        repository.save("BTC-USD", Interval.S1, original);
        repository.save("BTC-USD", Interval.S1, updated);
        
        Optional<Candle> retrieved = repository.findByExactTime("BTC-USD", Interval.S1, 1000L);
        
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get()).isEqualTo(updated);
    }

    @Test
    @DisplayName("Should handle large number of candles")
    void testManyCandles() {
        int candleCount = 1000;
        
        for (int i = 0; i < candleCount; i++) {
            long time = 1000L + (i * 1000L);
            Candle candle = new Candle(time, 100.0 + i, 110.0 + i, 90.0 + i, 105.0 + i, 10L);
            repository.save("BTC-USD", Interval.S1, candle);
        }
        
        List<Candle> retrieved = repository.findByRange("BTC-USD", Interval.S1, 0L, 1001000L);
        
        assertThat(retrieved).hasSizeGreaterThanOrEqualTo(candleCount);
    }

    @Test
    @DisplayName("Should return candles sorted by time")
    void testSortedResults() {
        repository.save("BTC-USD", Interval.S1, new Candle(3000L, 110.0, 120.0, 100.0, 115.0, 15L));
        repository.save("BTC-USD", Interval.S1, new Candle(1000L, 100.0, 110.0, 90.0, 105.0, 10L));
        repository.save("BTC-USD", Interval.S1, new Candle(2000L, 105.0, 115.0, 95.0, 110.0, 12L));
        
        List<Candle> candles = repository.findByRange("BTC-USD", Interval.S1, 0L, 4000L);
        
        assertThat(candles).hasSize(3);
        assertThat(candles).extracting(Candle::time).containsExactly(1000L, 2000L, 3000L);
    }

    @Test
    @DisplayName("Should handle concurrent writes")
    void testConcurrentWrites() throws InterruptedException {
        int threadCount = 10;
        int candlesPerThread = 100;
        
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < candlesPerThread; j++) {
                    long time = (threadIndex * candlesPerThread + j) * 1000L;
                    Candle candle = new Candle(time, 100.0, 110.0, 90.0, 105.0, 10L);
                    repository.save("BTC-USD", Interval.S1, candle);
                }
            });
            threads[i].start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        List<Candle> retrieved = repository.findByRange("BTC-USD", Interval.S1, 0L, Long.MAX_VALUE);
        assertThat(retrieved).hasSizeGreaterThanOrEqualTo(threadCount * candlesPerThread);
    }

    @Test
    @DisplayName("Should handle concurrent reads")
    void testConcurrentReads() throws InterruptedException {
        // Pre-populate data
        for (int i = 0; i < 100; i++) {
            long time = i * 1000L;
            repository.save("BTC-USD", Interval.S1, new Candle(time, 100.0, 110.0, 90.0, 105.0, 10L));
        }
        
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    long time = j * 1000L;
                    Optional<Candle> candle = repository.findByExactTime("BTC-USD", Interval.S1, time);
                    assertThat(candle).isPresent();
                }
            });
            threads[i].start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
    }

    @Test
    @DisplayName("Should persist data to disk")
    void testPersistence() {
        Candle candle = new Candle(1000L, 50000.0, 50100.0, 49900.0, 50050.0, 10L);
        repository.save("BTC-USD", Interval.S1, candle);
        
        // Close and reopen
        repository.shutdown();
        repository = new ChronicleMapCandleRepository(properties);
        repository.initialize();
        
        Optional<Candle> retrieved = repository.findByExactTime("BTC-USD", Interval.S1, 1000L);
        
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get()).isEqualTo(candle);
    }

    @Test
    @DisplayName("Should handle boundary timestamps")
    void testBoundaryTimestamps() {
        repository.save("BTC-USD", Interval.S1, new Candle(1000L, 100.0, 110.0, 90.0, 105.0, 10L));
        repository.save("BTC-USD", Interval.S1, new Candle(2000L, 105.0, 115.0, 95.0, 110.0, 12L));
        repository.save("BTC-USD", Interval.S1, new Candle(3000L, 110.0, 120.0, 100.0, 115.0, 15L));
        
        // Test inclusive boundaries
        List<Candle> candles = repository.findByRange("BTC-USD", Interval.S1, 1000L, 3000L);
        
        assertThat(candles).hasSize(3);
    }

    @Test
    @DisplayName("Should handle very large time ranges")
    void testLargeTimeRange() {
        repository.save("BTC-USD", Interval.S1, new Candle(1000L, 100.0, 110.0, 90.0, 105.0, 10L));
        repository.save("BTC-USD", Interval.S1, new Candle(1000000000L, 200.0, 210.0, 190.0, 205.0, 20L));
        
        List<Candle> candles = repository.findByRange("BTC-USD", Interval.S1, 0L, 2000000000L);
        
        assertThat(candles).hasSize(2);
    }

    @Test
    @DisplayName("Should return latest candle")
    void testLatestCandle() {
        repository.save("BTC-USD", Interval.S1, new Candle(1000L, 100.0, 110.0, 90.0, 105.0, 10L));
        repository.save("BTC-USD", Interval.S1, new Candle(2000L, 105.0, 115.0, 95.0, 110.0, 12L));
        repository.save("BTC-USD", Interval.S1, new Candle(3000L, 110.0, 120.0, 100.0, 115.0, 15L));
        
        List<Candle> candles = repository.findByRange("BTC-USD", Interval.S1, 0L, Long.MAX_VALUE);
        
        assertThat(candles).isNotEmpty();
        Candle latest = candles.get(candles.size() - 1);
        assertThat(latest.time()).isEqualTo(3000L);
    }

    @Test
    @DisplayName("Should return empty when no candles exist for symbol")
    void testLatestCandleEmpty() {
        List<Candle> candles = repository.findByRange("NONEXISTENT", Interval.S1, 0L, Long.MAX_VALUE);
        
        assertThat(candles).isEmpty();
    }
}

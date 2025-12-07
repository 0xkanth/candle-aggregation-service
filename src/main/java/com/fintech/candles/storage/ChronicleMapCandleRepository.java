package com.fintech.candles.storage;

import com.fintech.candles.config.CandleProperties;
import com.fintech.candles.domain.Candle;
import com.fintech.candles.domain.Interval;
import com.fintech.candles.domain.TimeSeriesKey;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import net.openhft.chronicle.map.ChronicleMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chronicle Map implementation of CandleRepository.
 * 
 * Uses off-heap memory-mapped storage for:
 * - Sub-microsecond read/write latency
 * - Zero GC pressure (data stored off-heap)
 * - Automatic persistence to disk
 * - Instant recovery on restart
 * 
 * Thread-safe for concurrent access.
 */
@Repository
public class ChronicleMapCandleRepository implements CandleRepository {
    
    private static final Logger log = LoggerFactory.getLogger(ChronicleMapCandleRepository.class);
    
    private final CandleProperties properties;
    private ChronicleMap<String, Candle> candleMap;
    private final AtomicLong writeCounter = new AtomicLong(0);
    private final AtomicLong readCounter = new AtomicLong(0);
    
    public ChronicleMapCandleRepository(CandleProperties properties) {
        this.properties = properties;
    }
    
    @PostConstruct
    public void initialize() {
        try {
            File dataFile = new File(properties.getStorage().getChronicleMap().getPath());
            File parentDir = dataFile.getParentFile();
            
            // Ensure parent directory exists
            if (parentDir != null && !parentDir.exists()) {
                boolean created = parentDir.mkdirs();
                if (created) {
                    log.info("Created Chronicle Map data directory: {}", parentDir.getAbsolutePath());
                }
            }
            
            // Create or load Chronicle Map
            candleMap = ChronicleMap
                .of(String.class, Candle.class)
                .name("candle-storage")
                .entries(properties.getStorage().getChronicleMap().getEntries())
                .averageKeySize(properties.getStorage().getChronicleMap().getAverageKeySize())
                .averageValueSize(200) // Candle object serialized size
                .createOrRecoverPersistedTo(dataFile);
            
            log.info("Chronicle Map initialized: path={}, existing_entries={}", 
                    dataFile.getAbsolutePath(), candleMap.size());
            
        } catch (IOException e) {
            log.error("Failed to initialize Chronicle Map", e);
            throw new RuntimeException("Chronicle Map initialization failed", e);
        }
    }
    
    @Override
    public void save(String symbol, Interval interval, Candle candle) {
        String key = createKey(symbol, interval, candle.time());
        candleMap.put(key, candle);
        writeCounter.incrementAndGet();
        
        if (log.isTraceEnabled()) {
            log.trace("Saved candle: symbol={}, interval={}, time={}", 
                     symbol, interval, candle.time());
        }
    }
    
    @Override
    public List<Candle> findByRange(String symbol, Interval interval, long fromTime, long toTime) {
        readCounter.incrementAndGet();
        
        // Chronicle Map doesn't have native range queries, so we scan with a prefix filter
        // In practice, this is still very fast due to off-heap access
        String prefix = createKeyPrefix(symbol, interval);
        
        List<Candle> results = new ArrayList<>();
        
        try {
            for (var entry : candleMap.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    Candle candle = entry.getValue();
                    if (candle.time() >= fromTime && candle.time() <= toTime) {
                        results.add(candle);
                    }
                }
            }
            
            // Sort by time
            results.sort(Comparator.comparingLong(Candle::time));
            
            if (log.isDebugEnabled()) {
                log.debug("Range query: symbol={}, interval={}, from={}, to={}, results={}", 
                         symbol, interval, fromTime, toTime, results.size());
            }
            
        } catch (Exception e) {
            log.error("Error during range query: symbol={}, interval={}", symbol, interval, e);
            throw new RuntimeException("Range query failed", e);
        }
        
        return results;
    }
    
    @Override
    public Optional<Candle> findByExactTime(String symbol, Interval interval, long time) {
        readCounter.incrementAndGet();
        String key = createKey(symbol, interval, time);
        return Optional.ofNullable(candleMap.get(key));
    }
    
    @Override
    public long deleteOlderThan(long cutoffTime) {
        // This is an expensive operation - intended for periodic cleanup
        long deletedCount = candleMap.entrySet().removeIf(entry -> 
            entry.getValue().time() < cutoffTime
        ) ? 1 : 0;
        
        if (deletedCount > 0) {
            log.info("Deleted {} candles older than {}", deletedCount, cutoffTime);
        }
        
        return deletedCount;
    }
    
    @Override
    public long count() {
        return candleMap.size();
    }
    
    @Override
    public boolean isHealthy() {
        try {
            // Quick health check - can we read/write?
            String testKey = "HEALTH-CHECK-" + System.currentTimeMillis();
            Candle testCandle = Candle.of(System.currentTimeMillis(), 100.0);
            candleMap.put(testKey, testCandle);
            Candle retrieved = candleMap.get(testKey);
            candleMap.remove(testKey);
            
            return retrieved != null;
        } catch (Exception e) {
            log.error("Health check failed", e);
            return false;
        }
    }
    
    @PreDestroy
    public void shutdown() {
        if (candleMap != null) {
            log.info("Closing Chronicle Map: total_writes={}, total_reads={}", 
                    writeCounter.get(), readCounter.get());
            candleMap.close();
        }
    }
    
    /**
     * Creates a unique key for Chronicle Map storage.
     * Format: SYMBOL-INTERVAL-TIMESTAMP
     */
    private String createKey(String symbol, Interval interval, long time) {
        return symbol + "-" + interval.name() + "-" + time;
    }
    
    /**
     * Creates a key prefix for range scans.
     * Format: SYMBOL-INTERVAL-
     */
    private String createKeyPrefix(String symbol, Interval interval) {
        return symbol + "-" + interval.name() + "-";
    }
    
    // Metrics accessors for monitoring
    public long getWriteCount() {
        return writeCounter.get();
    }
    
    public long getReadCount() {
        return readCounter.get();
    }
}

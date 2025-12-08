package com.fintech.candles.storage.timescaledb;

import com.fintech.candles.domain.Candle;
import com.fintech.candles.domain.Interval;
import com.fintech.candles.storage.CandleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * TimescaleDB implementation of CandleRepository.
 * 
 * Provides persistent, scalable storage for historical candle data using
 * TimescaleDB's time-series optimizations:
 * - Hypertable partitioning by timestamp
 * - Automatic compression for old data
 * - Continuous aggregation views
 * - Retention policies
 * 
 * Trade-offs vs Chronicle Map:
 * - Latency: ~1-5ms (vs <1μs for Chronicle Map)
 * - Capacity: Unlimited (vs RAM-limited)
 * - Durability: ACID guarantees (vs eventual consistency)
 * - Query power: SQL analytics (vs key-value only)
 */
@Repository
public class TimescaleDBCandleRepository implements CandleRepository {
    
    private static final Logger log = LoggerFactory.getLogger(TimescaleDBCandleRepository.class);
    
    private final CandleJpaRepository jpaRepository;
    private final MeterRegistry meterRegistry;
    
    private final AtomicLong writeCounter = new AtomicLong(0);
    private final AtomicLong readCounter = new AtomicLong(0);
    private final AtomicLong writeErrorCounter = new AtomicLong(0);
    private final Timer writeTimer;
    private final Timer readTimer;
    
    public TimescaleDBCandleRepository(
            CandleJpaRepository jpaRepository,
            MeterRegistry meterRegistry) {
        this.jpaRepository = jpaRepository;
        this.meterRegistry = meterRegistry;
        
        // Register metrics
        meterRegistry.gauge("timescaledb.candles.writes.total", writeCounter);
        meterRegistry.gauge("timescaledb.candles.reads.total", readCounter);
        meterRegistry.gauge("timescaledb.candles.write.errors", writeErrorCounter);
        
        this.writeTimer = meterRegistry.timer("timescaledb.candles.write.latency");
        this.readTimer = meterRegistry.timer("timescaledb.candles.read.latency");
        
        log.info("TimescaleDB candle repository initialized");
    }
    
    @Override
    @Transactional
    public void save(String symbol, Interval interval, Candle candle) {
        writeTimer.record(() -> {
            try {
                CandleEntity entity = toEntity(symbol, interval, candle);
                jpaRepository.save(entity);
                writeCounter.incrementAndGet();
                
                if (log.isTraceEnabled()) {
                    log.trace("Persisted candle to TimescaleDB: {}", entity.getId());
                }
            } catch (Exception e) {
                writeErrorCounter.incrementAndGet();
                log.error("Failed to save candle to TimescaleDB: symbol={}, interval={}, time={}", 
                         symbol, interval, candle.time(), e);
                throw new RuntimeException("TimescaleDB write failed", e);
            }
        });
    }
    
    @Override
    @Transactional(readOnly = true)
    public List<Candle> findByRange(String symbol, Interval interval, long fromTime, long toTime) {
        return readTimer.record(() -> {
            try {
                List<CandleEntity> entities = jpaRepository.findByRange(
                    symbol, 
                    interval.name(), 
                    fromTime, 
                    toTime
                );
                
                readCounter.addAndGet(entities.size());
                
                if (log.isDebugEnabled()) {
                    log.debug("TimescaleDB range query: symbol={}, interval={}, from={}, to={}, results={}", 
                             symbol, interval.name(), fromTime, toTime, entities.size());
                }
                
                return entities.stream()
                    .map(this::fromEntity)
                    .collect(Collectors.toList());
                    
            } catch (Exception e) {
                log.error("Failed to query TimescaleDB: symbol={}, interval={}, from={}, to={}", 
                         symbol, interval, fromTime, toTime, e);
                return List.of();
            }
        });
    }
    
    @Override
    @Transactional(readOnly = true)
    public Optional<Candle> findByExactTime(String symbol, Interval interval, long time) {
        return readTimer.record(() -> {
            try {
                Optional<CandleEntity> entity = jpaRepository.findBySymbolAndIntervalTypeAndTimestamp(
                    symbol, 
                    interval.name(), 
                    time
                );
                
                if (entity.isPresent()) {
                    readCounter.incrementAndGet();
                }
                
                return entity.map(this::fromEntity);
                
            } catch (Exception e) {
                log.error("Failed to find candle in TimescaleDB: symbol={}, interval={}, time={}", 
                         symbol, interval, time, e);
                return Optional.empty();
            }
        });
    }
    
    @Override
    @Transactional
    public long deleteOlderThan(long timestamp) {
        try {
            long count = jpaRepository.count();
            jpaRepository.deleteOlderThan(timestamp);
            long remaining = jpaRepository.count();
            long deleted = count - remaining;
            log.info("Deleted {} candles older than timestamp={} from TimescaleDB", deleted, timestamp);
            return deleted;
        } catch (Exception e) {
            log.error("Failed to delete old candles from TimescaleDB: timestamp={}", timestamp, e);
            return 0;
        }
    }
    
    @Override
    public long count() {
        return jpaRepository.count();
    }
    
    @Override
    public boolean isHealthy() {
        try {
            jpaRepository.count();
            return true;
        } catch (Exception e) {
            log.error("TimescaleDB health check failed", e);
            return false;
        }
    }
    
    /**
     * Get latest timestamp for backfill detection.
     */
    public Optional<Long> getLatestTimestamp(String symbol, Interval interval) {
        return jpaRepository.findLatestTimestamp(symbol, interval.name());
    }
    
    /**
     * Get total candle count for monitoring.
     */
    public long count(String symbol, Interval interval) {
        return jpaRepository.countBySymbolAndIntervalType(symbol, interval.name());
    }
    
    /**
     * Convert domain Candle to JPA entity.
     */
    private CandleEntity toEntity(String symbol, Interval interval, Candle candle) {
        String id = CandleEntity.generateId(symbol, interval.name(), candle.time());
        
        return CandleEntity.builder()
            .id(id)
            .symbol(symbol)
            .intervalType(interval.name())
            .timestamp(candle.time())
            .open(candle.open())
            .high(candle.high())
            .low(candle.low())
            .close(candle.close())
            .volume(candle.volume())
            .tradeCount(candle.volume())  // Using volume as trade count for now
            .build();
    }
    
    /**
     * Convert JPA entity to domain Candle.
     */
    private Candle fromEntity(CandleEntity entity) {
        return new Candle(
            entity.getTimestamp(),
            entity.getOpen(),
            entity.getHigh(),
            entity.getLow(),
            entity.getClose(),
            entity.getVolume()
        );
    }
}

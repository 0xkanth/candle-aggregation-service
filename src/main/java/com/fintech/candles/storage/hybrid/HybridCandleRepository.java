package com.fintech.candles.storage.hybrid;

import com.fintech.candles.domain.Candle;
import com.fintech.candles.domain.Interval;
import com.fintech.candles.storage.CandleRepository;
import com.fintech.candles.storage.timescaledb.TimescaleDBCandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Primary candle repository implementation using TimescaleDB directly.
 * 
 * This implementation stores all candle data directly in TimescaleDB,
 * leveraging its time-series optimizations for efficient storage and retrieval.
 * 
 * Benefits:
 * - Persistent, durable storage with ACID guarantees
 * - Unlimited storage capacity
 * - Efficient time-series queries with hypertable partitioning
 * - SQL analytics capabilities
 * - Automatic compression and retention policies
 */
@Repository
@Primary
public class HybridCandleRepository implements CandleRepository {
    
    private static final Logger log = LoggerFactory.getLogger(HybridCandleRepository.class);
    
    private final TimescaleDBCandleRepository timescaleDBRepository;
    
    public HybridCandleRepository(TimescaleDBCandleRepository timescaleDBRepository) {
        this.timescaleDBRepository = timescaleDBRepository;
        log.info("Candle repository initialized with TimescaleDB storage");
    }
    
    @Override
    public void save(String symbol, Interval interval, Candle candle) {
        timescaleDBRepository.save(symbol, interval, candle);
    }
    
    @Override
    public List<Candle> findByRange(String symbol, Interval interval, long fromTime, long toTime) {
        return timescaleDBRepository.findByRange(symbol, interval, fromTime, toTime);
    }
    
    @Override
    public Optional<Candle> findByExactTime(String symbol, Interval interval, long time) {
        return timescaleDBRepository.findByExactTime(symbol, interval, time);
    }
    
    @Override
    public long deleteOlderThan(long timestamp) {
        return timescaleDBRepository.deleteOlderThan(timestamp);
    }
    
    @Override
    public long count() {
        return timescaleDBRepository.count();
    }
    
    @Override
    public boolean isHealthy() {
        return timescaleDBRepository.isHealthy();
    }
}

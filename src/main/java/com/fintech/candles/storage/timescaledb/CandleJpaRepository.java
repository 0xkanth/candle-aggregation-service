package com.fintech.candles.storage.timescaledb;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for TimescaleDB candle operations.
 * Leverages TimescaleDB's time-series optimizations for fast queries.
 */
@Repository
public interface CandleJpaRepository extends JpaRepository<CandleEntity, String> {
    
    /**
     * Find candles within a time range, ordered by timestamp ascending.
     * TimescaleDB optimizes this with hypertable partitioning.
     */
    @Query("SELECT c FROM CandleEntity c " +
           "WHERE c.symbol = :symbol " +
           "AND c.intervalType = :intervalType " +
           "AND c.timestamp >= :fromTime " +
           "AND c.timestamp <= :toTime " +
           "ORDER BY c.timestamp ASC")
    List<CandleEntity> findByRange(
        @Param("symbol") String symbol,
        @Param("intervalType") String intervalType,
        @Param("fromTime") long fromTime,
        @Param("toTime") long toTime
    );
    
    /**
     * Find a specific candle by exact timestamp.
     */
    Optional<CandleEntity> findBySymbolAndIntervalTypeAndTimestamp(
        String symbol, 
        String intervalType, 
        long timestamp
    );
    
    /**
     * Delete candles older than specified timestamp.
     * Used for data retention policies.
     */
    @Modifying
    @Query("DELETE FROM CandleEntity c WHERE c.timestamp < :timestamp")
    void deleteOlderThan(@Param("timestamp") long timestamp);
    
    /**
     * Count candles for a symbol-interval pair.
     */
    long countBySymbolAndIntervalType(String symbol, String intervalType);
    
    /**
     * Get latest candle timestamp for a symbol-interval.
     * Useful for backfill detection.
     */
    @Query("SELECT MAX(c.timestamp) FROM CandleEntity c " +
           "WHERE c.symbol = :symbol AND c.intervalType = :intervalType")
    Optional<Long> findLatestTimestamp(
        @Param("symbol") String symbol,
        @Param("intervalType") String intervalType
    );
}

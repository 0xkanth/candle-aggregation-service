package com.fintech.candles.storage;

import com.fintech.candles.domain.Candle;
import com.fintech.candles.domain.Interval;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for candle persistence operations.
 * Abstracts the underlying storage mechanism to allow different implementations
 * (Chronicle Map, TimescaleDB, in-memory, etc.) without changing business logic.
 */
public interface CandleRepository {
    
    /**
     * Persists a candle to storage.
     * Implementation should be idempotent - saving the same candle twice should be safe.
     * 
     * @param symbol The trading pair symbol
     * @param interval The candle interval
     * @param candle The candle data to save
     */
    void save(String symbol, Interval interval, Candle candle);
    
    /**
     * Retrieves candles for a symbol-interval within a time range.
     * Results should be ordered by time ascending.
     * 
     * @param symbol The trading pair symbol
     * @param interval The candle interval
     * @param fromTime Start of time range (inclusive)
     * @param toTime End of time range (inclusive)
     * @return List of candles in the range, empty if none found
     */
    List<Candle> findByRange(String symbol, Interval interval, long fromTime, long toTime);
    
    /**
     * Finds a specific candle by exact timestamp.
     * 
     * @param symbol The trading pair symbol
     * @param interval The candle interval
     * @param time The exact candle timestamp
     * @return Optional containing the candle if found
     */
    Optional<Candle> findByExactTime(String symbol, Interval interval, long time);
    
    /**
     * Deletes candles older than the specified timestamp.
     * Used for data cleanup and archival.
     * 
     * @param cutoffTime Timestamp threshold (exclusive)
     * @return Number of candles deleted
     */
    long deleteOlderThan(long cutoffTime);
    
    /**
     * Returns the total number of candles currently stored.
     * Useful for monitoring and capacity planning.
     * 
     * @return Count of candles
     */
    long count();
    
    /**
     * Checks if the repository is healthy and ready to serve requests.
     * 
     * @return true if operational, false otherwise
     */
    boolean isHealthy();
}

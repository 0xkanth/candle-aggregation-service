package com.fintech.candles.domain;

/**
 * Enumeration of supported time intervals for candle aggregation.
 * 
 * <p>Each interval defines a fixed time window for grouping market data events into candles.
 * The system processes all intervals simultaneously for each event using epoch alignment.
 * 
 * <p><b>Design Rationale:</b>
 * <ul>
 *   <li>Enum ensures type safety - impossible to create invalid intervals</li>
 *   <li>Fixed set of intervals optimizes memory layout (no dynamic allocations)</li>
 *   <li>Millisecond precision aligns with Unix epoch timestamps</li>
 *   <li>Standard intervals (1s, 5s, 1m, 15m, 1h) match industry conventions</li>
 * </ul>
 * 
 * <p><b>Interval Selection Strategy:</b>
 * <ul>
 *   <li><b>S1, S5:</b> High-frequency trading, scalping strategies, real-time monitoring</li>
 *   <li><b>M1:</b> Day trading, momentum strategies, tick data aggregation</li>
 *   <li><b>M15:</b> Swing trading, intraday trend analysis</li>
 *   <li><b>H1:</b> Position trading, longer-term trend identification</li>
 * </ul>
 * 
 * <p><b>Epoch Alignment:</b> All time windows are aligned to Unix epoch (1970-01-01 00:00:00 UTC).
 * This ensures consistency across symbols and services. Example for M1 interval:
 * <pre>
 * Event at 10:37:23.456 → Window 10:37:00.000 to 10:37:59.999
 * Event at 10:38:01.123 → Window 10:38:00.000 to 10:38:59.999
 * </pre>
 * 
 * <p><b>Thread Safety:</b> Enums are inherently thread-safe (singleton per constant).
 * 
 * @author Senior Java/Web3 Developer
 * @since 1.0.0
 */
public enum Interval {
    
    /** 1-second candles - for tick data aggregation and high-frequency trading */
    S1(1_000L),
    
    /** 5-second candles - for scalping and microstructure analysis */
    S5(5_000L),
    
    /** 1-minute candles - most common interval for day trading */
    M1(60_000L),
    
    /** 15-minute candles - popular for swing trading and pattern recognition */
    M15(900_000L),
    
    /** 1-hour candles - for longer-term trend analysis and position trading */
    H1(3_600_000L);
    
    private final long milliseconds;
    
    /**
     * Private constructor (enum pattern).
     * 
     * @param milliseconds The interval duration in milliseconds
     */
    Interval(long milliseconds) {
        this.milliseconds = milliseconds;
    }
    
    /**
     * Returns the interval duration in milliseconds.
     * 
     * <p><b>Use Case:</b> Time window calculations, timeout configurations.
     * 
     * <p><b>Examples:</b>
     * <ul>
     *   <li>S1.toMillis() → 1000</li>
     *   <li>M1.toMillis() → 60000</li>
     *   <li>H1.toMillis() → 3600000</li>
     * </ul>
     * 
     * @return Duration in milliseconds (always positive)
     */
    public long toMillis() {
        return milliseconds;
    }
    
    /**
     * Aligns a timestamp to the start of its interval window using integer division.
     * 
     * <p><b>Algorithm:</b> {@code (timestamp / intervalMs) * intervalMs}
     * 
     * <p>Integer division floors the result, effectively "rounding down" to the nearest
     * interval boundary. This is NOT mathematical rounding - it's always floor().
     * 
     * <p><b>Why Integer Division (Not Modulo or Rounding):</b>
     * <ul>
     *   <li>Guarantees windows never overlap (critical for correct aggregation)</li>
     *   <li>Ensures same timestamp always maps to same window (deterministic)</li>
     *   <li>Aligns to Unix epoch (1970-01-01 00:00:00.000 UTC)</li>
     *   <li>Fast CPU operation (single division + multiplication)</li>
     * </ul>
     * 
     * <p><b>Example: M1 interval (60,000 ms)</b>
     * <pre>
     * Timestamp: 10:37:23.456 = 1,733,529,443,456 ms
     * Step 1: 1,733,529,443,456 / 60,000 = 28,892,157.3909... → 28,892,157 (integer division floors)
     * Step 2: 28,892,157 × 60,000 = 1,733,529,420,000
     * Result: 10:37:00.000 (window start)
     * </pre>
     * 
     * <p><b>Example: S5 interval (5,000 ms)</b>
     * <pre>
     * Same timestamp: 10:37:23.456
     * Step 1: 1,733,529,443,456 / 5,000 = 346,705,888.6912 → 346,705,888
     * Step 2: 346,705,888 × 5,000 = 1,733,529,440,000
     * Result: 10:37:20.000 (window start)
     * </pre>
     * 
     * <p><b>Edge Cases:</b>
     * <ul>
     *   <li>Timestamps already aligned return unchanged: alignTimestamp(10:00:00.000) → 10:00:00.000</li>
     *   <li>Negative timestamps supported (theoretical, not used in practice)</li>
     * </ul>
     * 
     * @param timestamp Unix epoch milliseconds (can be any value, including millisecond fractions)
     * @return Aligned timestamp representing the window start (always <= input timestamp)
     */
    public long alignTimestamp(long timestamp) {
        return (timestamp / milliseconds) * milliseconds;
    }
    
    /**
     * Calculates the exclusive end boundary of an interval window.
     * 
     * <p>Candle windows are half-open intervals: [start, end). A timestamp
     * exactly equal to the end belongs to the NEXT window.
     * 
     * <p><b>Formula:</b> {@code windowStart + intervalDurationMs}
     * 
     * <p><b>Example: M1 interval</b>
     * <pre>
     * Window start: 10:00:00.000 (1,733,529,600,000)
     * Window end:   10:00:59.999 (1,733,529,659,999)
     * 
     * Event at 10:00:00.000 → INCLUDED (belongs to this window)
     * Event at 10:00:59.999 → INCLUDED (belongs to this window)
     * Event at 10:01:00.000 → EXCLUDED (belongs to next window)
     * </pre>
     * 
     * @param windowStart The aligned start timestamp of a window
     * @return The exclusive end timestamp (first timestamp NOT in this window)
     */
    public long windowEnd(long windowStart) {
        return windowStart + milliseconds;
    }
    
    /**
     * Checks if two timestamps belong to the same interval window.
     * 
     * <p>Two timestamps are in the same window if they align to the same start time.
     * This is used for detecting window boundaries during aggregation.
     * 
     * <p><b>Algorithm:</b> {@code alignTimestamp(t1) == alignTimestamp(t2)}
     * 
     * <p><b>Example: M1 interval</b>
     * <pre>
     * inSameWindow(10:00:05, 10:00:50) → true  (both → 10:00:00)
     * inSameWindow(10:00:59, 10:01:01) → false (10:00:00 vs 10:01:00)
     * </pre>
     * 
     * <p><b>Use Case:</b> Detecting when a new candle should be created vs updating existing.
     * 
     * @param timestamp1 First timestamp to compare
     * @param timestamp2 Second timestamp to compare
     * @return true if both timestamps belong to the same window
     */
    public boolean inSameWindow(long timestamp1, long timestamp2) {
        return alignTimestamp(timestamp1) == alignTimestamp(timestamp2);
    }
}

package com.fintech.candles.domain;

/**
 * Time intervals for candle aggregation with epoch-aligned window calculations.
 * All intervals process simultaneously using integer division for alignment.
 */
public enum Interval {
    
    S1(1_000L),
    S5(5_000L),
    M1(60_000L),
    M15(900_000L),
    H1(3_600_000L);
    
    private final long milliseconds;
    
    Interval(long milliseconds) {
        this.milliseconds = milliseconds;
    }
    
    /** Returns interval duration in milliseconds. */
    public long toMillis() {
        return milliseconds;
    }
    
    /**
     * Aligns timestamp to window start: (timestamp / intervalMs) * intervalMs.
     * Integer division floors to nearest boundary.
     */
    public long alignTimestamp(long timestamp) {
        return (timestamp / milliseconds) * milliseconds;
    }
    
    /** Returns exclusive window end: windowStart + intervalMs. */
    public long windowEnd(long windowStart) {
        return windowStart + milliseconds;
    }
    
    /** Returns true if both timestamps align to same window start. */
    public boolean inSameWindow(long timestamp1, long timestamp2) {
        return alignTimestamp(timestamp1) == alignTimestamp(timestamp2);
    }
}

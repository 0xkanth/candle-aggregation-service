package com.fintech.candles.domain;

import java.util.Objects;

/**
 * Composite key for uniquely identifying a candle in storage.
 * Implements natural ordering by symbol, then interval, then time.
 * 
 * This enables efficient range queries and sorted iteration.
 */
public record TimeSeriesKey(
    String symbol,
    Interval interval,
    long time
) implements Comparable<TimeSeriesKey> {
    
    public TimeSeriesKey {
        Objects.requireNonNull(symbol, "Symbol cannot be null");
        Objects.requireNonNull(interval, "Interval cannot be null");
    }
    
    /**
     * Compares keys for natural ordering.
     * Order: symbol (asc) -> interval (asc) -> time (asc)
     * 
     * This ordering allows efficient queries like:
     * - All candles for a symbol
     * - All candles for a symbol-interval pair
     * - Time-range queries within a symbol-interval
     */
    @Override
    public int compareTo(TimeSeriesKey other) {
        int symbolCompare = this.symbol.compareTo(other.symbol);
        if (symbolCompare != 0) {
            return symbolCompare;
        }
        
        int intervalCompare = this.interval.compareTo(other.interval);
        if (intervalCompare != 0) {
            return intervalCompare;
        }
        
        return Long.compare(this.time, other.time);
    }
    
    /**
     * Creates a string representation suitable for use as a map key.
     * Format: "SYMBOL-INTERVAL-TIMESTAMP"
     * 
     * @return String key
     */
    public String toStringKey() {
        return symbol + "-" + interval.name() + "-" + time;
    }
    
    /**
     * Parses a string key back into a TimeSeriesKey object.
     * 
     * @param stringKey The string key to parse
     * @return Parsed TimeSeriesKey
     * @throws IllegalArgumentException if format is invalid
     */
    public static TimeSeriesKey fromStringKey(String stringKey) {
        String[] parts = stringKey.split("-");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid key format: " + stringKey);
        }
        
        return new TimeSeriesKey(
            parts[0],
            Interval.valueOf(parts[1]),
            Long.parseLong(parts[2])
        );
    }
}

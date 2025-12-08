package com.fintech.candles.domain;

import java.io.Serializable;

/**
 * Immutable OHLC candlestick aggregating price movements over a time window.
 * Uses record semantics for immutability and compact constructor for validation.
 * Serializable for off-heap Chronicle Map persistence.
 * 
 * @param time Window start timestamp (epoch-aligned millis)
 * @param open First price in window
 * @param high Maximum price (must be >= open, close, low)
 * @param low Minimum price (must be <= open, close, high)
 * @param close Last price in window
 * @param volume Tick count in window
 */
public record Candle(
    long time,
    double open,
    double high,
    double low,
    double close,
    long volume
) implements Serializable {
    
    /**
     * Creates a single-price candle (first tick in window).
     * 
     * @param time Window start timestamp
     * @param price Initial OHLC value
     * @return Candle with all OHLC = price, volume = 1
     */
    public static Candle of(long time, double price) {
        return new Candle(time, price, price, price, price, 1);
    }
    
    /**
     * Validates OHLC invariants: high >= {open,close,low}, low <= {open,close,high}.
     */
    public Candle {
        // Validate high is truly the highest
        if (high < low) {
            throw new IllegalArgumentException(
                "High price (" + high + ") cannot be less than low price (" + low + ")"
            );
        }
        if (high < open || high < close) {
            throw new IllegalArgumentException(
                "High price (" + high + ") must be >= open (" + open + ") and close (" + close + ")"
            );
        }
        
        // Validate low is truly the lowest
        if (low > open || low > close) {
            throw new IllegalArgumentException(
                "Low price (" + low + ") must be <= open (" + open + ") and close (" + close + ")"
            );
        }
    }
    
    /** Returns price range (high - low). */
    public double range() {
        return high - low;
    }
    
    /** Returns body size (close - open). Positive = bullish, negative = bearish. */
    public double body() {
        return close - open;
    }
    
    /** Returns true if close > open. */
    public boolean isBullish() {
        return close > open;
    }
    
    /** Returns true if close < open. */
    public boolean isBearish() {
        return close < open;
    }
    
    /** Returns true if |close - open| < 0.01% of open (doji pattern). */
    public boolean isDoji() {
        return Math.abs(close - open) < 0.0001 * open;
    }
}

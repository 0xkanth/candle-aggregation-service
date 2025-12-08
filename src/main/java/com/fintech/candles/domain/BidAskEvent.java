package com.fintech.candles.domain;

import java.io.Serializable;

/**
 * Immutable market data tick with best bid/ask prices.
 * Timestamp represents event creation time for late event detection.
 * 
 * @param symbol Trading pair (e.g., "BTC-USD")
 * @param bid Highest buyer price
 * @param ask Lowest seller price
 * @param timestamp Event creation time (Unix epoch millis)
 */
public record BidAskEvent(
    String symbol,
    double bid,
    double ask,
    long timestamp
) implements Serializable {
    
    /** Returns mid-market price: (bid + ask) / 2. */
    public double midPrice() {
        return (bid + ask) / 2.0;
    }
    
    /** Returns bid-ask spread: ask - bid. */
    public double spread() {
        return ask - bid;
    }
    
    /** Returns spread as percentage of mid price (0.0 if mid is zero). */
    public double spreadPercentage() {
        double mid = midPrice();
        return mid > 0 ? spread() / mid : 0.0;
    }
    
    /** Validates bid > 0, ask > 0, ask >= bid, timestamp > 0. */
    public boolean isValid() {
        return bid > 0 && ask > 0 && ask >= bid && timestamp > 0;
    }
}

package com.fintech.candles.domain;

import java.io.Serializable;

/**
 * Immutable market data tick representing a bid/ask quote from an exchange or market maker.
 * 
 * <p>This is the fundamental input event for the candle aggregation pipeline. Each event
 * represents a snapshot of the order book's best bid and ask prices at a specific moment.
 * 
 * <p><b>Design Rationale:</b>
 * <ul>
 *   <li>Record type for immutability - critical for concurrent processing without defensive copies</li>
 *   <li>Serializable for LMAX Disruptor ring buffer event storage</li>
 *   <li>Timestamp is the <b>event creation time</b>, not arrival time - essential for late event detection</li>
 * </ul>
 * 
 * <p><b>Thread Safety:</b> Fully immutable, safe for concurrent access across multiple threads.
 * 
 * <p><b>Example Usage:</b>
 * <pre>{@code
 * BidAskEvent event = new BidAskEvent(
 *     "BTC-USD",
 *     50000.00,  // bid
 *     50010.00,  // ask
 *     System.currentTimeMillis()
 * );
 * double midPrice = event.midPrice();  // 50005.00
 * }</pre>
 * 
 * @param symbol Trading pair identifier (e.g., "BTC-USD", "ETH-USD"). Must not be null or empty.
 * @param bid Highest price a buyer is willing to pay. Must be positive and <= ask.
 * @param ask Lowest price a seller is willing to accept. Must be positive and >= bid.
 * @param timestamp Unix epoch milliseconds when the quote was generated (NOT when received).
 *                  Used for time window alignment and late event detection.
 * 
 * @author Senior Java/Web3 Developer
 * @since 1.0.0
 */
public record BidAskEvent(
    String symbol,
    double bid,
    double ask,
    long timestamp
) implements Serializable {
    
    /**
     * Calculates the mid-market price (midpoint between bid and ask).
     * 
     * <p>The mid price is the standard reference price used for candle OHLC values.
     * It represents the theoretical "fair" price where buyers and sellers meet.
     * 
     * <p><b>Formula:</b> {@code (bid + ask) / 2}
     * 
     * <p><b>Example:</b> bid=50000, ask=50010 → midPrice=50005
     * 
     * @return The mid-market price (arithmetic mean of bid and ask)
     */
    public double midPrice() {
        return (bid + ask) / 2.0;
    }
    
    /**
     * Calculates the bid-ask spread in absolute price units.
     * 
     * <p>The spread represents the cost of immediacy - the price paid by takers
     * to execute immediately rather than waiting. Wider spreads indicate lower liquidity.
     * 
     * <p><b>Formula:</b> {@code ask - bid}
     * 
     * <p><b>Example:</b> bid=50000, ask=50010 → spread=10
     * 
     * @return Absolute spread in the quote currency (always >= 0)
     */
    public double spread() {
        return ask - bid;
    }
    
    /**
     * Calculates the bid-ask spread as a percentage of mid price.
     * 
     * <p>Useful for comparing liquidity across different price levels and instruments.
     * A lower percentage indicates better liquidity.
     * 
     * <p><b>Formula:</b> {@code (ask - bid) / midPrice}
     * 
     * <p><b>Example:</b> bid=50000, ask=50010 → spreadPercentage ≈ 0.0002 (0.02%)
     * 
     * <p><b>Edge Case:</b> Returns 0.0 if midPrice is 0 to avoid division by zero.
     * 
     * @return Spread as a decimal (e.g., 0.0002 = 0.02% = 2 basis points)
     */
    public double spreadPercentage() {
        double mid = midPrice();
        return mid > 0 ? spread() / mid : 0.0;
    }
    
    /**
     * Validates the event data for business logic correctness.
     * 
     * <p><b>Validation Rules:</b>
     * <ul>
     *   <li>Bid must be positive (> 0)</li>
     *   <li>Ask must be positive (> 0)</li>
     *   <li>Ask must be >= bid (no crossed market)</li>
     *   <li>Timestamp must be positive (valid Unix epoch)</li>
     * </ul>
     * 
     * <p><b>Use Case:</b> Called before processing events to filter out corrupted market data.
     * 
     * @return true if event passes all validation rules, false otherwise
     */
    public boolean isValid() {
        return bid > 0 && ask > 0 && ask >= bid && timestamp > 0;
    }
}

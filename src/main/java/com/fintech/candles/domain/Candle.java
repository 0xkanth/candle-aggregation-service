package com.fintech.candles.domain;

import java.io.Serializable;

/**
 * Immutable OHLC (Open-High-Low-Close) candlestick representing aggregated price action over a time window.
 * 
 * <p>Candlesticks are the standard visualization format for time-series price data in trading platforms.
 * Each candle summarizes all price movements within a specific time interval (e.g., 1 minute, 1 hour).
 * 
 * <p><b>Design Rationale:</b>
 * <ul>
 *   <li>Record type for immutability - candles are historical facts that never change once complete</li>
 *   <li>Serializable for Chronicle Map off-heap storage</li>
 *   <li>Compact constructor validates OHLC invariants (high >= all, low <= all)</li>
 *   <li>Helper methods support technical analysis (isBullish, isDoji, etc.)</li>
 * </ul>
 * 
 * <p><b>OHLC Semantics:</b>
 * <ul>
 *   <li><b>Open:</b> First price in the window - establishes the baseline</li>
 *   <li><b>High:</b> Maximum price observed - indicates buying pressure</li>
 *   <li><b>Low:</b> Minimum price observed - indicates selling pressure</li>
 *   <li><b>Close:</b> Last price in the window - most recent consensus</li>
 *   <li><b>Volume:</b> Number of price updates (tick count) - indicates activity level</li>
 * </ul>
 * 
 * <p><b>Invariants (enforced by compact constructor):</b>
 * <ul>
 *   <li>{@code high >= open && high >= close && high >= low}</li>
 *   <li>{@code low <= open && low <= close && low <= high}</li>
 * </ul>
 * 
 * <p><b>Thread Safety:</b> Fully immutable, safe for concurrent access.
 * 
 * <p><b>Example Usage:</b>
 * <pre>{@code
 * Candle candle = new Candle(
 *     1733529600000L,  // time: 2024-12-06 10:00:00 UTC
 *     50000.0,         // open
 *     50100.0,         // high
 *     49900.0,         // low
 *     50050.0,         // close
 *     125              // volume
 * );
 * 
 * boolean bullish = candle.isBullish();  // true (close > open)
 * double range = candle.range();          // 200.0 (high - low)
 * }</pre>
 * 
 * @param time Unix epoch milliseconds representing the START of the candle's time window.
 *             This is always epoch-aligned (e.g., 10:00:00.000, not 10:00:37.123).
 * @param open First mid-price observed in this window. Establishes the opening level.
 * @param high Maximum mid-price reached during this window. Must be >= all other OHLC values.
 * @param low Minimum mid-price reached during this window. Must be <= all other OHLC values.
 * @param close Last mid-price observed in this window. Represents the closing level.
 * @param volume Number of BidAskEvents processed in this window. Indicates liquidity/activity.
 * 
 * @throws IllegalArgumentException if OHLC invariants are violated
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
     * Factory method to create a candle from a single price point.
     * 
     * <p>Used when the first event for a time window arrives. All OHLC values
     * are initialized to the same price, and volume is set to 1.
     * 
     * <p><b>Use Case:</b> Starting a new candle when time window changes.
     * 
     * <p><b>Example:</b>
     * <pre>{@code
     * Candle candle = Candle.of(1733529600000L, 50000.0);
     * // Results in: open=50000, high=50000, low=50000, close=50000, volume=1
     * }</pre>
     * 
     * @param time The candle's window start timestamp (epoch-aligned)
     * @param price The initial price for this candle
     * @return A new candle with all OHLC set to the same price and volume=1
     */
    public static Candle of(long time, double price) {
        return new Candle(time, price, price, price, price, 1);
    }
    
    /**
     * Compact constructor that validates OHLC invariants.
     * 
     * <p>This is automatically called by the record constructor to ensure
     * data integrity. Throws if the candlestick would violate OHLC semantics.
     * 
     * <p><b>Validated Invariants:</b>
     * <ul>
     *   <li>high must be the highest value (high >= low, high >= open, high >= close)</li>
     *   <li>low must be the lowest value (low <= high, low <= open, low <= close)</li>
     * </ul>
     * 
     * <p><b>Why This Matters:</b> Prevents corrupted candles from entering the system,
     * which could break charting libraries or technical analysis algorithms.
     * 
     * @throws IllegalArgumentException if any OHLC invariant is violated
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
    
    /**
     * Calculates the price range (volatility indicator).
     * 
     * <p>Range represents the total price movement during the time window.
     * Larger ranges indicate higher volatility or significant price discovery.
     * 
     * <p><b>Formula:</b> {@code high - low}
     * 
     * <p><b>Use Case:</b> Identifying volatile periods, calculating ATR (Average True Range).
     * 
     * @return The absolute price range (always >= 0)
     */
    public double range() {
        return high - low;
    }
    
    /**
     * Calculates the candle body size (directional pressure indicator).
     * 
     * <p>The body represents the net price change during the window:
     * <ul>
     *   <li><b>Positive:</b> Bullish candle (buyers won, price up)</li>
     *   <li><b>Negative:</b> Bearish candle (sellers won, price down)</li>
     *   <li><b>Near Zero:</b> Doji candle (indecision, equilibrium)</li>
     * </ul>
     * 
     * <p><b>Formula:</b> {@code close - open}
     * 
     * <p><b>Use Case:</b> Measuring buying/selling pressure, identifying trend strength.
     * 
     * @return The candle body size (positive for bullish, negative for bearish)
     */
    public double body() {
        return close - open;
    }
    
    /**
     * Checks if this candle is bullish (closing price higher than opening).
     * 
     * <p>Bullish candles indicate buying pressure dominated the time window.
     * Typically rendered as green/white in charting applications.
     * 
     * <p><b>Condition:</b> {@code close > open}
     * 
     * @return true if close is strictly greater than open
     */
    public boolean isBullish() {
        return close > open;
    }
    
    /**
     * Checks if this candle is bearish (closing price lower than opening).
     * 
     * <p>Bearish candles indicate selling pressure dominated the time window.
     * Typically rendered as red/black in charting applications.
     * 
     * <p><b>Condition:</b> {@code close < open}
     * 
     * @return true if close is strictly less than open
     */
    public boolean isBearish() {
        return close < open;
    }
    
    /**
     * Checks if this candle is a doji (open ≈ close, indicating market indecision).
     * 
     * <p>Doji candles suggest equilibrium between buyers and sellers. Often appear
     * at trend reversals or during consolidation periods.
     * 
     * <p><b>Condition:</b> {@code |close - open| < 0.01% of open price}
     * 
     * <p><b>Why 0.01%:</b> Accounts for floating-point precision while being strict
     * enough to exclude small-bodied candles that aren't true dojis.
     * 
     * <p><b>Example:</b> open=50000, close=50004.9 → body is 0.0098% → doji
     * 
     * @return true if the candle is a doji (near-zero body)
     */
    public boolean isDoji() {
        return Math.abs(close - open) < 0.0001 * open;
    }
}

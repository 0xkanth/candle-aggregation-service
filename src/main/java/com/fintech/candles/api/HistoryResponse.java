package com.fintech.candles.api;

import com.fintech.candles.domain.Candle;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Response format compatible with TradingView Lightweight Charts.
 * 
 * Uses columnar format where each OHLC component is in its own array.
 * This format is:
 * - More compact than array-of-objects
 * - Easier to compress
 * - Directly consumable by charting libraries
 * 
 * Example response:
 * {
 *   "s": "ok",
 *   "t": [1620000000, 1620000060],
 *   "o": [29500.5, 29501.0],
 *   "h": [29510.0, 29505.0],
 *   "l": [29490.0, 29500.0],
 *   "c": [29505.0, 29502.0],
 *   "v": [10, 8]
 * }
 */
public record HistoryResponse(
    @JsonProperty("s") String status,
    @JsonProperty("t") List<Long> time,
    @JsonProperty("o") List<Double> open,
    @JsonProperty("h") List<Double> high,
    @JsonProperty("l") List<Double> low,
    @JsonProperty("c") List<Double> close,
    @JsonProperty("v") List<Long> volume
) {
    
    /**
     * Creates a successful response from a list of candles.
     * 
     * @param candles List of OHLC candles
     * @return TradingView-formatted response
     */
    public static HistoryResponse fromCandles(List<Candle> candles) {
        int size = candles.size();
        
        List<Long> time = new ArrayList<>(size);
        List<Double> open = new ArrayList<>(size);
        List<Double> high = new ArrayList<>(size);
        List<Double> low = new ArrayList<>(size);
        List<Double> close = new ArrayList<>(size);
        List<Long> volume = new ArrayList<>(size);
        
        for (Candle candle : candles) {
            // Convert milliseconds to seconds for TradingView
            time.add(candle.time() / 1000);
            open.add(candle.open());
            high.add(candle.high());
            low.add(candle.low());
            close.add(candle.close());
            volume.add(candle.volume());
        }
        
        return new HistoryResponse("ok", time, open, high, low, close, volume);
    }
    
    /**
     * Creates an empty successful response (no data in range).
     */
    public static HistoryResponse empty() {
        return new HistoryResponse(
            "ok",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }
    
    /**
     * Creates an error response.
     * 
     * @param errorMessage The error description
     * @return Error response
     */
    public static HistoryResponse error(String errorMessage) {
        return new HistoryResponse(
            "error",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }
}

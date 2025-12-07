package com.fintech.candles.api;

import com.fintech.candles.domain.Candle;
import com.fintech.candles.domain.Interval;
import com.fintech.candles.storage.CandleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for querying historical candle data.
 * Returns data in TradingView Lightweight Charts compatible format.
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class HistoryController {
    
    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);
    
    private final CandleRepository repository;
    private final MeterRegistry meterRegistry;
    
    public HistoryController(CandleRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }
    
    /**
     * GET /api/v1/history
     * 
     * Retrieves historical OHLC candle data for charting.
     * 
     * @param symbol Trading pair (e.g., "BTC-USD")
     * @param interval Candle interval (e.g., "1s", "1m", "1h")
     * @param from Start timestamp in Unix seconds
     * @param to End timestamp in Unix seconds
     * @return TradingView-compatible OHLC data
     */
    @GetMapping("/history")
    public ResponseEntity<HistoryResponse> getHistory(
            @RequestParam @NotBlank String symbol,
            @RequestParam @NotBlank String interval,
            @RequestParam @Positive long from,
            @RequestParam @Positive long to) {
        
        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            // Validate and parse interval
            Interval intervalEnum = parseInterval(interval);
            
            // Convert Unix seconds to milliseconds
            long fromMs = from * 1000;
            long toMs = to * 1000;
            
            // Query repository
            List<Candle> candles = repository.findByRange(symbol, intervalEnum, fromMs, toMs);
            
            log.debug("History query: symbol={}, interval={}, from={}, to={}, results={}", 
                     symbol, interval, from, to, candles.size());
            
            // Convert to TradingView format
            HistoryResponse response = HistoryResponse.fromCandles(candles);
            
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            log.warn("Invalid history request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
            
        } finally {
            sample.stop(meterRegistry.timer("api.history.request.time",
                "symbol", symbol,
                "interval", interval));
        }
    }
    
    /**
     * GET /api/v1/symbols
     * 
     * Returns list of available trading symbols.
     * In a real system, this would query active symbols from the repository.
     */
    @GetMapping("/symbols")
    public ResponseEntity<List<String>> getSymbols() {
        // For now, return configured symbols
        // In production, this would scan the repository
        List<String> symbols = List.of("BTC-USD", "ETH-USD", "SOL-USD");
        return ResponseEntity.ok(symbols);
    }
    
    /**
     * Parses interval string to Interval enum.
     * Supports formats like: "1s", "5s", "1m", "15m", "1h"
     */
    private Interval parseInterval(String intervalStr) {
        return switch (intervalStr.toLowerCase()) {
            case "1s", "s1" -> Interval.S1;
            case "5s", "s5" -> Interval.S5;
            case "1m", "m1" -> Interval.M1;
            case "15m", "m15" -> Interval.M15;
            case "1h", "h1" -> Interval.H1;
            default -> throw new IllegalArgumentException("Unsupported interval: " + intervalStr);
        };
    }
}

package com.fintech.candles.api;

import com.fintech.candles.domain.Candle;
import com.fintech.candles.domain.Interval;
import com.fintech.candles.service.CandleService;
import com.fintech.candles.storage.CandleRepository;
import com.fintech.candles.aggregation.CandleAggregator;
import com.fintech.candles.ingestion.DisruptorEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * REST API for querying historical candle data.
 * Returns data in TradingView Lightweight Charts compatible format.
 */
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Candle Data", description = "Historical OHLC candle data API")
public class HistoryController {
    
    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);
    private static final Set<String> VALID_INTERVALS = Set.of("1s", "s1", "5s", "s5", "1m", "m1", "15m", "m15", "1h", "h1");
    private static final Set<String> SUPPORTED_SYMBOLS = Set.of("BTCUSD", "ETHUSD", "SOLUSD", "EURUSD", "GBPUSD", "XAUUSD");
    private static final long MAX_TIME_RANGE_SECONDS = 86400 * 7; // 7 days
    private static final long MIN_TIMESTAMP = 0;
    private static final long MAX_TIMESTAMP = 9999999999L; // Year 2286
    
    private final CandleService candleService;
    private final CandleRepository repository;
    private final MeterRegistry meterRegistry;
    private final CandleAggregator candleAggregator;
    private final DisruptorEventPublisher disruptorEventPublisher;
    
    public HistoryController(
            CandleService candleService,
            CandleRepository repository,
            MeterRegistry meterRegistry,
            CandleAggregator candleAggregator,
            DisruptorEventPublisher disruptorEventPublisher) {
        this.candleService = candleService;
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.candleAggregator = candleAggregator;
        this.disruptorEventPublisher = disruptorEventPublisher;
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
    @Operation(
        summary = "Get historical OHLC candle data",
        description = """
            Retrieves historical candlestick data for a given symbol and time range.
            Returns data in TradingView Lightweight Charts compatible format.
            
            **Supported Intervals:** 1s, 5s, 1m, 15m, 1h
            
            **Example Request:**
            ```
            GET /api/v1/history?symbol=BTCUSD&interval=1m&from=1733529420&to=1733533020
            ```
            """,
        tags = {"Candle Data"}
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved candle data",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = HistoryResponse.class),
                examples = @ExampleObject(
                    name = "Sample Response",
                    value = """
                        {
                          "s": "ok",
                          "t": [1733529420, 1733529480, 1733529540],
                          "o": [50000.0, 50100.0, 50050.0],
                          "h": [50150.0, 50200.0, 50150.0],
                          "l": [49950.0, 50000.0, 49980.0],
                          "c": [50100.0, 50050.0, 50120.0],
                          "v": [1250, 980, 1100]
                        }
                        """
                )
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request parameters (e.g., unsupported interval)",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class),
                examples = @ExampleObject(
                    name = "Validation Error",
                    value = """
                        {
                          "status": 400,
                          "error": "VALIDATION_ERROR",
                          "message": "Request validation failed",
                          "path": "/api/v1/history",
                          "timestamp": "2025-12-09T10:30:00Z",
                          "validationErrors": [
                            {
                              "field": "interval",
                              "rejectedValue": "99s",
                              "message": "Unsupported interval. Allowed: 1s, 5s, 1m, 15m, 1h"
                            }
                          ]
                        }
                        """
                )
            )
        ),
        @ApiResponse(
            responseCode = "500",
            description = "Internal server error",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class)
            )
        )
    })
    @GetMapping("/history")
    public ResponseEntity<HistoryResponse> getHistory(
            @Parameter(description = "Trading symbol (e.g., BTCUSD, ETHUSD)", example = "BTCUSD", required = true)
            @RequestParam 
            @NotBlank(message = "Symbol is required and cannot be blank") 
            @Pattern(regexp = "^[A-Z0-9]{6,10}$", message = "Symbol must be 6-10 uppercase alphanumeric characters")
            String symbol,
            
            @Parameter(description = "Candle interval: 1s, 5s, 1m, 15m, 1h", example = "1m", required = true)
            @RequestParam 
            @NotBlank(message = "Interval is required and cannot be blank")
            String interval,
            
            @Parameter(description = "Start time (Unix timestamp in seconds)", example = "1733529420", required = true)
            @RequestParam 
            @NotNull(message = "From timestamp is required")
            @Positive(message = "From timestamp must be positive")
            Long from,
            
            @Parameter(description = "End time (Unix timestamp in seconds)", example = "1733533020", required = true)
            @RequestParam 
            @NotNull(message = "To timestamp is required")
            @Positive(message = "To timestamp must be positive")
            Long to) {
        
        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            // Normalize symbol to uppercase
            symbol = symbol.trim().toUpperCase();
            interval = interval.trim().toLowerCase();
            
            // Validate symbol is supported
            if (!SUPPORTED_SYMBOLS.contains(symbol)) {
                throw new IllegalArgumentException(
                    String.format("Unsupported symbol '%s'. Allowed: %s", 
                        symbol, String.join(", ", SUPPORTED_SYMBOLS))
                );
            }
            
            // Validate interval format
            if (!VALID_INTERVALS.contains(interval)) {
                throw new IllegalArgumentException(
                    String.format("Unsupported interval '%s'. Allowed: 1s, 5s, 1m, 15m, 1h", interval)
                );
            }
            
            // Validate timestamp range
            if (from < MIN_TIMESTAMP || from > MAX_TIMESTAMP) {
                throw new IllegalArgumentException(
                    String.format("Invalid 'from' timestamp: %d. Must be between %d and %d", 
                        from, MIN_TIMESTAMP, MAX_TIMESTAMP)
                );
            }
            
            if (to < MIN_TIMESTAMP || to > MAX_TIMESTAMP) {
                throw new IllegalArgumentException(
                    String.format("Invalid 'to' timestamp: %d. Must be between %d and %d", 
                        to, MIN_TIMESTAMP, MAX_TIMESTAMP)
                );
            }
            
            // Validate from < to
            if (from >= to) {
                throw new IllegalArgumentException(
                    String.format("Invalid time range: 'from' (%d) must be less than 'to' (%d)", from, to)
                );
            }
            
            // Validate time range not too large
            long rangeSeconds = to - from;
            if (rangeSeconds > MAX_TIME_RANGE_SECONDS) {
                throw new IllegalArgumentException(
                    String.format("Time range too large: %d seconds. Maximum allowed: %d seconds (7 days)", 
                        rangeSeconds, MAX_TIME_RANGE_SECONDS)
                );
            }
            
            // Validate and parse interval
            Interval intervalEnum = parseInterval(interval);
            
            // Convert Unix seconds to milliseconds
            long fromMs = from * 1000;
            long toMs = to * 1000;
            
            // Query via service layer (with circuit breaker protection)
            List<Candle> candles = candleService.findCandlesByRange(symbol, intervalEnum, fromMs, toMs);
            
            log.debug("History query: symbol={}, interval={}, from={}, to={}, results={}", 
                     symbol, interval, from, to, candles.size());
            
            // Convert to TradingView format
            HistoryResponse response = HistoryResponse.fromCandles(candles);
            
            return ResponseEntity.ok(response);
            
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
     */
    @Operation(
        summary = "Get available trading symbols",
        description = "Returns a list of all supported trading symbols in the system.",
        tags = {"Candle Data"}
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved symbols list",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    name = "Sample Response",
                    value = """
                        ["BTCUSD", "ETHUSD", "SOLUSD", "EURUSD", "GBPUSD", "XAUUSD"]
                        """
                )
            )
        )
    })
    @GetMapping("/symbols")
    public ResponseEntity<List<String>> getSymbols() {
        return ResponseEntity.ok(List.copyOf(SUPPORTED_SYMBOLS));
    }
    
    /**
     * GET /api/v1/metrics/dropped-events
     * 
     * Returns dropped event metrics for monitoring.
     * Exposes both late events (outside tolerance window) and ring buffer drops (back-pressure).
     */
    @Operation(
        summary = "Get dropped events metrics",
        description = "Returns metrics about dropped events for system monitoring and alerting.",
        tags = {"Monitoring"}
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Successfully retrieved dropped events metrics",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = DroppedEventsMetrics.class),
                examples = @ExampleObject(
                    name = "Sample Response",
                    value = """
                        {
                          "lateEventsDropped": 42,
                          "ringBufferEventsDropped": 0
                        }
                        """
                )
            )
        )
    })
    @GetMapping("/metrics/dropped-events")
    public ResponseEntity<DroppedEventsMetrics> getDroppedEventsMetrics() {
        DroppedEventsMetrics metrics = new DroppedEventsMetrics(
            candleAggregator.getLateEventsDropped(),
            disruptorEventPublisher.getRingBufferEventsDropped()
        );
        return ResponseEntity.ok(metrics);
    }
    
    /**
     * DTO for dropped events metrics response.
     */
    @Schema(description = "Metrics about dropped events in the system")
    public record DroppedEventsMetrics(
        @Schema(description = "Number of late events dropped (outside time tolerance)", example = "42")
        long lateEventsDropped,
        
        @Schema(description = "Number of events dropped due to ring buffer back-pressure", example = "0")
        long ringBufferEventsDropped
    ) {}
    
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

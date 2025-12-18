package com.fintech.candles.api;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Production-grade metrics endpoint exposing accurate percentiles.
 * 
 * DESIGN DECISIONS:
 * - Exposes HdrHistogram-calculated percentiles via REST API
 * - Prometheus /actuator/prometheus already exports these automatically
 * - This endpoint provides human-readable JSON for ops/debugging
 * - Used by performance monitoring scripts and dashboards
 */
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    private final MeterRegistry meterRegistry;

    public MetricsController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Get comprehensive latency metrics with accurate percentiles.
     * 
     * EXAMPLE RESPONSE:
     * {
     *   "event_processing": {
     *     "count": 1500000,
     *     "mean_us": 1.47,
     *     "max_us": 103.5,
     *     "p50_us": 1.38,
     *     "p95_us": 2.41,
     *     "p99_us": 4.67,
     *     "p999_us": 47.2
     *   },
     *   "database_write": { ... },
     *   "database_read": { ... }
     * }
     */
    @GetMapping("/latency")
    public Map<String, Object> getLatencyMetrics() {
        Map<String, Object> response = new HashMap<>();
        
        // Event processing latency (most critical metric)
        addTimerMetrics(response, "event_processing", 
                       "candle.aggregator.event.processing.time");
        
        // Database write latency
        addTimerMetrics(response, "database_write", 
                       "timescaledb.candles.write.latency");
        
        // Database read latency
        addTimerMetrics(response, "database_read", 
                       "timescaledb.candles.read.latency");
        
        // HTTP request latency
        addTimerMetrics(response, "http_requests", 
                       "http.server.requests");
        
        return response;
    }

    /**
     * Get throughput metrics (events/sec, candles/sec).
     */
    @GetMapping("/throughput")
    public Map<String, Object> getThroughputMetrics() {
        Map<String, Object> response = new HashMap<>();
        
        // Events processed
        Gauge eventsGauge = meterRegistry.find("candle.aggregator.events.processed")
                                        .gauge();
        if (eventsGauge != null) {
            response.put("events_processed_total", (long) eventsGauge.value());
        }
        
        // Candles completed
        Gauge candlesGauge = meterRegistry.find("candle.aggregator.candles.completed")
                                         .gauge();
        if (candlesGauge != null) {
            response.put("candles_completed_total", (long) candlesGauge.value());
        }
        
        // Late events dropped
        Gauge lateEventsGauge = meterRegistry.find("candle.aggregator.late.events.dropped")
                                            .gauge();
        if (lateEventsGauge != null) {
            response.put("late_events_dropped", (long) lateEventsGauge.value());
        }
        
        // Calculate events/second from Timer count
        Timer processingTimer = meterRegistry.find("candle.aggregator.event.processing.time")
                                            .timer();
        if (processingTimer != null) {
            long count = processingTimer.count();
            double totalTimeSeconds = processingTimer.totalTime(TimeUnit.SECONDS);
            if (totalTimeSeconds > 0) {
                response.put("events_per_second", (long) (count / totalTimeSeconds));
            }
        }
        
        return response;
    }

    /**
     * Get complete performance summary (latency + throughput + errors).
     */
    @GetMapping("/summary")
    public Map<String, Object> getPerformanceSummary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("latency", getLatencyMetrics());
        summary.put("throughput", getThroughputMetrics());
        summary.put("errors", getErrorMetrics());
        summary.put("timestamp", System.currentTimeMillis());
        return summary;
    }

    /**
     * Get error/drop metrics.
     */
    @GetMapping("/errors")
    public Map<String, Object> getErrorMetrics() {
        Map<String, Object> response = new HashMap<>();
        
        Gauge writeErrors = meterRegistry.find("timescaledb.candles.write.errors")
                                        .gauge();
        if (writeErrors != null) {
            response.put("database_write_errors", (long) writeErrors.value());
        }
        
        Gauge lateEvents = meterRegistry.find("candle.aggregator.late.events.dropped")
                                       .gauge();
        if (lateEvents != null) {
            response.put("late_events_dropped", (long) lateEvents.value());
        }
        
        return response;
    }

    /**
     * Helper: Extract percentile data from Timer and add to response.
     */
    private void addTimerMetrics(Map<String, Object> response, String key, String timerName) {
        Timer timer = meterRegistry.find(timerName).timer();
        if (timer == null) {
            return;
        }
        
        Map<String, Object> metrics = new HashMap<>();
        
        // Basic stats
        metrics.put("count", timer.count());
        metrics.put("mean_us", timer.mean(TimeUnit.MICROSECONDS));
        metrics.put("max_us", timer.max(TimeUnit.MICROSECONDS));
        metrics.put("total_time_seconds", timer.totalTime(TimeUnit.SECONDS));
        
        // Accurate percentiles from HdrHistogram
        HistogramSnapshot snapshot = timer.takeSnapshot();
        
        // Extract percentile values
        for (ValueAtPercentile percentile : snapshot.percentileValues()) {
            double p = percentile.percentile();
            double valueUs = percentile.value(TimeUnit.MICROSECONDS);
            
            // Convert percentile to readable key: 0.5 -> "p50", 0.99 -> "p99", 0.999 -> "p999"
            String percentileKey = formatPercentileKey(p);
            metrics.put(percentileKey + "_us", valueUs);
        }
        
        response.put(key, metrics);
    }

    /**
     * Format percentile key: 0.5 -> "p50", 0.95 -> "p95", 0.99 -> "p99", 0.999 -> "p999"
     */
    private String formatPercentileKey(double percentile) {
        if (percentile == 0.5) return "p50";
        if (percentile == 0.95) return "p95";
        if (percentile == 0.99) return "p99";
        if (percentile == 0.999) return "p999";
        
        // Fallback for other percentiles
        int pValue = (int) (percentile * 100);
        return "p" + pValue;
    }
}

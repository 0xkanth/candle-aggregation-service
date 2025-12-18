package com.fintech.candles.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Production-grade metrics configuration for accurate percentile measurement.
 * 
 * APPROACH:
 * - Uses HdrHistogram for accurate percentile calculation (already in classpath)
 * - Configures p50, p95, p99, p99.9 percentiles for all Timers
 * - Sets SLA boundaries for Prometheus histogram buckets
 * - Enables percentile histograms for Grafana visualization
 * 
 * SENIOR/PRINCIPAL ENGINEER DECISIONS:
 * 1. HdrHistogram over simple estimation (industry standard, low overhead)
 * 2. Pre-configured SLAs based on system requirements (<50μs p99)
 * 3. Separate config for local dev vs AWS CloudWatch
 * 4. Memory-conscious: 60s max age, 3 rotations (HdrHistogram memory = ~20KB per timer)
 */
@Configuration
public class MetricsConfiguration {

    /**
     * Local development & Prometheus configuration.
     * Uses client-side percentile calculation with HdrHistogram.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> {
            // Add common tags for all metrics (useful for multi-instance deployments)
            registry.config().commonTags(
                "application", "candle-aggregation-service",
                "environment", getEnvironment()
            );
            
            // Configure accurate percentile measurement for ALL timers
            registry.config().meterFilter(
                new io.micrometer.core.instrument.config.MeterFilter() {
                    @Override
                    public io.micrometer.core.instrument.distribution.DistributionStatisticConfig configure(
                            io.micrometer.core.instrument.Meter.Id id,
                            io.micrometer.core.instrument.distribution.DistributionStatisticConfig config) {
                        
                        // Only apply to Timer metrics (not Gauges, Counters, etc.)
                        if (id.getType() == io.micrometer.core.instrument.Meter.Type.TIMER) {
                            return DistributionStatisticConfig.builder()
                                // CLIENT-SIDE PERCENTILES (calculated in-process using HdrHistogram)
                                // Exported to Prometheus as separate metrics: metric_name{quantile="0.95"}
                                .percentiles(0.5, 0.95, 0.99, 0.999)
                                
                                // PERCENTILE PRECISION: Controls HdrHistogram accuracy
                                // 2 significant digits = 1% accuracy (e.g., 47μs ± 0.47μs)
                                // Higher = more accurate but more memory
                                .percentilePrecision(2)
                                
                                // HISTOGRAM BUCKETS (for Prometheus histogram_quantile)
                                // Server-side percentile calculation in Grafana/Prometheus
                                // These SLAs based on system requirement: <50μs p99
                                .serviceLevelObjectives(
                                    0.000001,    // 1 μs
                                    0.000002,    // 2 μs
                                    0.000005,    // 5 μs
                                    0.00001,     // 10 μs
                                    0.000025,    // 25 μs
                                    0.00005,     // 50 μs (SLA boundary)
                                    0.0001,      // 100 μs
                                    0.00025,     // 250 μs
                                    0.0005,      // 500 μs
                                    0.001,       // 1 ms
                                    0.005,       // 5 ms
                                    0.01         // 10 ms
                                )
                                
                                // ENABLE HISTOGRAM EXPORT to Prometheus
                                // Required for Grafana's histogram_quantile() function
                                .percentilesHistogram(true)
                                
                                // TIME-BASED ROTATION: Prevent memory leaks in long-running services
                                // Max age = 60 seconds (data older than this is discarded)
                                .expiry(Duration.ofSeconds(60))
                                
                                // BUFFER COUNT: Number of histogram rotations to keep
                                // 3 buffers = smooth percentile calculation during rotation
                                .bufferLength(3)
                                .build();
                        }
                        return config;
                    }
                }
            );
        };
    }

    /**
     * AWS CloudWatch-specific configuration.
     * 
     * KEY DIFFERENCE FROM PROMETHEUS:
     * - CloudWatch doesn't support histograms, only statistical sets
     * - Client-side percentiles are REQUIRED (server-side not available)
     * - Uses CloudWatch Embedded Metric Format (EMF) for efficient export
     * 
     * COST OPTIMIZATION:
     * - Fewer percentiles (p50, p99 only) to reduce CloudWatch API calls
     * - Longer aggregation period (5 minutes) to batch metrics
     * - Disabled histogram buckets (CloudWatch doesn't use them)
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> cloudWatchMetricsConfig() {
        return registry -> {
            String cloudProvider = System.getenv("CLOUD_PROVIDER");
            
            if ("AWS".equalsIgnoreCase(cloudProvider)) {
                registry.config().meterFilter(
                    new io.micrometer.core.instrument.config.MeterFilter() {
                        @Override
                        public DistributionStatisticConfig configure(
                                io.micrometer.core.instrument.Meter.Id id,
                                DistributionStatisticConfig config) {
                            
                            if (id.getType() == io.micrometer.core.instrument.Meter.Type.TIMER) {
                                return DistributionStatisticConfig.builder()
                                    // Only p50 and p99 for cost efficiency
                                    .percentiles(0.5, 0.99)
                                    .percentilePrecision(2)
                                    
                                    // NO histogram buckets (CloudWatch doesn't support)
                                    .percentilesHistogram(false)
                                    
                                    // Longer expiry for batching
                                    .expiry(Duration.ofMinutes(5))
                                    .bufferLength(2)
                                    .build();
                            }
                            return config;
                        }
                    }
                );
            }
        };
    }

    /**
     * Detect environment from system properties.
     */
    private String getEnvironment() {
        String env = System.getenv("SPRING_PROFILES_ACTIVE");
        return env != null ? env : "local";
    }
}

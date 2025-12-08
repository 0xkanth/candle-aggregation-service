package com.fintech.candles.api;

import com.fintech.candles.storage.hybrid.HybridCandleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for monitoring storage metrics.
 */
@RestController
@RequestMapping("/api/v1/storage")
@ConditionalOnBean(HybridCandleRepository.class)
public class StorageMetricsController {
    
    private final HybridCandleRepository hybridRepository;
    private final MeterRegistry meterRegistry;
    
    public StorageMetricsController(
            HybridCandleRepository hybridRepository,
            MeterRegistry meterRegistry) {
        this.hybridRepository = hybridRepository;
        this.meterRegistry = meterRegistry;
    }
    
    /**
     * Get storage metrics.
     * 
     * Example response:
     * {
     *   "totalCandles": 125000,
     *   "healthy": true,
     *   "storageType": "TimescaleDB"
     * }
     */
    @GetMapping("/metrics")
    public ResponseEntity<StorageMetricsResponse> getStorageMetrics() {
        long totalCandles = hybridRepository.count();
        boolean healthy = hybridRepository.isHealthy();
        
        return ResponseEntity.ok(new StorageMetricsResponse(
            totalCandles,
            healthy,
            "TimescaleDB"
        ));
    }
    
    /**
     * Response DTO for storage metrics.
     */
    public record StorageMetricsResponse(
        long totalCandles,
        boolean healthy,
        String storageType
    ) {}
}

package com.fintech.candles.service;

import com.fintech.candles.domain.Candle;
import com.fintech.candles.domain.Interval;
import com.fintech.candles.storage.CandleRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service layer for candle data operations.
 * 
 * Responsibilities:
 * - Business logic validation
 * - Circuit breaker integration
 * - Defensive error handling
 * - Metrics and logging
 * 
 * This layer ensures the API never crashes due to repository failures.
 */
@Service
public class CandleService {
    
    private static final Logger log = LoggerFactory.getLogger(CandleService.class);
    
    private final CandleRepository repository;
    private final CircuitBreaker circuitBreaker;
    private final MeterRegistry meterRegistry;
    
    private final AtomicLong validationErrors = new AtomicLong(0);
    private final AtomicLong serviceErrors = new AtomicLong(0);
    
    public CandleService(
            CandleRepository repository,
            CircuitBreakerRegistry circuitBreakerRegistry,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("database");
        this.meterRegistry = meterRegistry;
        
        // Register metrics
        meterRegistry.gauge("candle.service.validation.errors", validationErrors);
        meterRegistry.gauge("candle.service.errors", serviceErrors);
        
        // Monitor circuit breaker state
        circuitBreaker.getEventPublisher()
            .onStateTransition(event -> 
                log.warn("Circuit breaker state changed: {} -> {}", 
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState())
            );
        
        circuitBreaker.getEventPublisher()
            .onError(event -> 
                log.error("Circuit breaker recorded error: {}", event.getThrowable().getMessage())
            );
    }
    
    /**
     * Find candles by time range with comprehensive error handling.
     * 
     * @throws ServiceException if operation fails critically
     * @throws ValidationException if input validation fails
     */
    public List<Candle> findCandlesByRange(
            String symbol, 
            Interval interval, 
            long fromTimeMs, 
            long toTimeMs) {
        
        // Service-level validation (redundant but defensive)
        validateInputs(symbol, interval, fromTimeMs, toTimeMs);
        
        try {
            // Execute with circuit breaker protection
            return circuitBreaker.executeSupplier(() -> {
                try {
                    List<Candle> candles = repository.findByRange(symbol, interval, fromTimeMs, toTimeMs);
                    
                    // Defensive: never return null
                    if (candles == null) {
                        log.warn("Repository returned null for symbol={}, interval={}", symbol, interval);
                        return Collections.emptyList();
                    }
                    
                    // Log large result sets (potential performance issue)
                    if (candles.size() > 10000) {
                        log.warn("Large result set: {} candles for symbol={}, interval={}, range={}ms",
                            candles.size(), symbol, interval, toTimeMs - fromTimeMs);
                    }
                    
                    return candles;
                    
                } catch (Exception e) {
                    serviceErrors.incrementAndGet();
                    log.error("Repository error during findByRange: symbol={}, interval={}, from={}, to={}", 
                        symbol, interval, fromTimeMs, toTimeMs, e);
                    throw new ServiceException("Failed to retrieve candles from repository", e);
                }
            });
            
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            // Circuit breaker is OPEN - fail fast
            log.error("Circuit breaker OPEN - rejecting request for symbol={}, interval={}", symbol, interval);
            throw new ServiceException("Database circuit breaker is open. System is recovering from errors.", e);
            
        } catch (Exception e) {
            serviceErrors.incrementAndGet();
            log.error("Unexpected error in findCandlesByRange: symbol={}, interval={}", symbol, interval, e);
            throw new ServiceException("Unexpected error retrieving candles", e);
        }
    }
    
    /**
     * Save candle with error handling.
     */
    public void saveCandle(String symbol, Interval interval, Candle candle) {
        // Validate inputs
        Objects.requireNonNull(symbol, "Symbol cannot be null");
        Objects.requireNonNull(interval, "Interval cannot be null");
        Objects.requireNonNull(candle, "Candle cannot be null");
        
        if (symbol.isBlank()) {
            validationErrors.incrementAndGet();
            throw new ValidationException("Symbol cannot be blank");
        }
        
        // Validate OHLC values
        if (candle.high() < candle.low() || candle.open() < 0 || candle.close() < 0) {
            validationErrors.incrementAndGet();
            throw new ValidationException("Invalid candle OHLC values");
        }
        
        try {
            circuitBreaker.executeRunnable(() -> {
                try {
                    repository.save(symbol, interval, candle);
                } catch (Exception e) {
                    serviceErrors.incrementAndGet();
                    log.error("Repository error during save: symbol={}, interval={}, time={}", 
                        symbol, interval, candle.time(), e);
                    throw new ServiceException("Failed to save candle to repository", e);
                }
            });
            
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            log.warn("Circuit breaker OPEN - candle save rejected: symbol={}, time={}", symbol, candle.time());
            // Don't throw - log and continue (candle is already in memory cache)
            
        } catch (Exception e) {
            serviceErrors.incrementAndGet();
            log.error("Unexpected error in saveCandle: symbol={}, interval={}, time={}", 
                symbol, interval, candle.time(), e);
            // Don't throw - candle is already in aggregator's memory
        }
    }
    
    /**
     * Validate service-level inputs.
     */
    private void validateInputs(String symbol, Interval interval, long fromTimeMs, long toTimeMs) {
        if (symbol == null || symbol.isBlank()) {
            validationErrors.incrementAndGet();
            throw new ValidationException("Symbol cannot be null or blank");
        }
        
        if (interval == null) {
            validationErrors.incrementAndGet();
            throw new ValidationException("Interval cannot be null");
        }
        
        if (fromTimeMs < 0) {
            validationErrors.incrementAndGet();
            throw new ValidationException("From time cannot be negative");
        }
        
        if (toTimeMs < 0) {
            validationErrors.incrementAndGet();
            throw new ValidationException("To time cannot be negative");
        }
        
        if (fromTimeMs >= toTimeMs) {
            validationErrors.incrementAndGet();
            throw new ValidationException("From time must be less than to time");
        }
        
        // Prevent extremely large queries
        long rangeMs = toTimeMs - fromTimeMs;
        long maxRangeMs = 7L * 24 * 60 * 60 * 1000; // 7 days
        if (rangeMs > maxRangeMs) {
            validationErrors.incrementAndGet();
            throw new ValidationException("Time range exceeds maximum allowed (7 days)");
        }
    }
    
    /**
     * Get circuit breaker state for monitoring.
     */
    public String getCircuitBreakerState() {
        return circuitBreaker.getState().name();
    }
    
    /**
     * Business logic validation exception.
     */
    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }
    }
    
    /**
     * Service layer exception (wraps repository/infrastructure errors).
     */
    public static class ServiceException extends RuntimeException {
        public ServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

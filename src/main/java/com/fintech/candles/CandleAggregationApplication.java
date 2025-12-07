package com.fintech.candles;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Candle Aggregation Service
 * 
 * High-performance OHLC candlestick aggregation for real-time market data streams.
 * 
 * Key Features:
 * - LMAX Disruptor for lock-free event processing (1M+ events/sec)
 * - Chronicle Map for off-heap storage (sub-microsecond latency)
 * - Virtual Threads (Java 21) for high API concurrency
 * - Multi-interval aggregation (1s, 5s, 1m, 15m, 1h)
 * - TradingView-compatible API responses
 * - Production-ready observability (Prometheus metrics)
 * 
 * @since 1.0.0
 */
@SpringBootApplication
@EnableScheduling
public class CandleAggregationApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(CandleAggregationApplication.class, args);
    }
}

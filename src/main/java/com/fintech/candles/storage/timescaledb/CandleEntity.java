package com.fintech.candles.storage.timescaledb;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JPA entity for TimescaleDB candle persistence.
 * 
 * Mapped to hypertable partitioned by timestamp for optimal time-series queries.
 * Indexes optimized for common query patterns (symbol+interval+time range).
 */
@Entity
@Table(
    name = "candles",
    indexes = {
        @Index(name = "idx_candles_symbol_interval_time", columnList = "symbol, interval_type, timestamp DESC"),
        @Index(name = "idx_candles_time", columnList = "timestamp DESC")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_candle", columnNames = {"symbol", "interval_type", "timestamp"})
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandleEntity {
    
    /**
     * Composite primary key: symbol_interval_timestamp
     * Example: "BTCUSD_S1_1703000000000"
     */
    @Id
    @Column(length = 100)
    private String id;
    
    /**
     * Trading pair symbol (e.g., BTCUSD, EURUSD)
     */
    @Column(nullable = false, length = 20)
    private String symbol;
    
    /**
     * Interval type (e.g., S1, M1, H1, D1)
     */
    @Column(name = "interval_type", nullable = false, length = 10)
    private String intervalType;
    
    /**
     * Candle open timestamp (Unix epoch milliseconds)
     * Hypertable partition key for TimescaleDB
     */
    @Column(nullable = false)
    private Long timestamp;
    
    /**
     * Opening price
     */
    @Column(nullable = false)
    private Double open;
    
    /**
     * Highest price during interval
     */
    @Column(nullable = false)
    private Double high;
    
    /**
     * Lowest price during interval
     */
    @Column(nullable = false)
    private Double low;
    
    /**
     * Closing price
     */
    @Column(nullable = false)
    private Double close;
    
    /**
     * Trading volume
     */
    @Column(nullable = false)
    private Long volume;
    
    /**
     * Number of trades aggregated
     */
    @Column(name = "trade_count", nullable = false)
    private Long tradeCount;
    
    /**
     * Record creation timestamp (for auditing)
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Long createdAt;
    
    /**
     * Last update timestamp
     */
    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;
    
    /**
     * Generates composite ID from symbol, interval, and timestamp.
     */
    public static String generateId(String symbol, String intervalType, long timestamp) {
        return String.format("%s_%s_%d", symbol, intervalType, timestamp);
    }
    
    @PrePersist
    protected void onCreate() {
        long now = System.currentTimeMillis();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = System.currentTimeMillis();
    }
}

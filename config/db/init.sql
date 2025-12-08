-- TimescaleDB Initialization Script for Candle Aggregation Service
-- This script runs automatically on first container startup

-- Enable TimescaleDB extension
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Create candles table
-- Note: For TimescaleDB hypertables, the partitioning column (timestamp) 
-- MUST be part of any unique constraints or primary keys
CREATE TABLE IF NOT EXISTS candles (
    id VARCHAR(100) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    interval_type VARCHAR(10) NOT NULL,
    timestamp BIGINT NOT NULL,
    open DOUBLE PRECISION NOT NULL,
    high DOUBLE PRECISION NOT NULL,
    low DOUBLE PRECISION NOT NULL,
    close DOUBLE PRECISION NOT NULL,
    volume BIGINT NOT NULL,
    trade_count BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    
    -- Primary key MUST include timestamp for hypertable partitioning
    PRIMARY KEY (id, timestamp),
    
    -- Ensure uniqueness per symbol-interval-timestamp
    CONSTRAINT uk_candle UNIQUE (symbol, interval_type, timestamp)
);

-- Convert to TimescaleDB hypertable (partitioned by timestamp)
-- Chunk interval: 1 day (86400000 ms)
SELECT create_hypertable(
    'candles', 
    'timestamp',
    chunk_time_interval => 86400000,
    if_not_exists => TRUE
);

-- Set integer_now function for BIGINT timestamp (epoch milliseconds)
-- This is required for compression policies on BIGINT timestamps
SELECT set_integer_now_func('candles', 'current_epoch_ms');

-- Helper function to get current time in epoch milliseconds
CREATE OR REPLACE FUNCTION current_epoch_ms() RETURNS BIGINT
LANGUAGE SQL STABLE AS $$
  SELECT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT
$$;

-- Create indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_candles_symbol_interval_time 
    ON candles (symbol, interval_type, timestamp DESC);

CREATE INDEX IF NOT EXISTS idx_candles_time 
    ON candles (timestamp DESC);

CREATE INDEX IF NOT EXISTS idx_candles_symbol_time 
    ON candles (symbol, timestamp DESC);

-- Enable compression for old data (after 7 days)
-- This can save 90%+ storage space
ALTER TABLE candles SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'symbol, interval_type',
    timescaledb.compress_orderby = 'timestamp DESC'
);

-- Automatic compression policy: compress chunks older than 7 days
-- compress_after expects milliseconds for BIGINT timestamp columns
SELECT add_compression_policy('candles', compress_after => 604800000);

-- Retention policy: automatically drop data older than 90 days
-- Uncomment if you want automatic cleanup
-- SELECT add_retention_policy('candles', INTERVAL '90 days');

-- Create continuous aggregate for hourly OHLC from 1-second candles
-- This pre-computes hourly candles for fast queries
CREATE MATERIALIZED VIEW IF NOT EXISTS candles_hourly
WITH (timescaledb.continuous) AS
SELECT 
    symbol,
    'H1' as interval_type,
    time_bucket(3600000, timestamp) AS timestamp,
    first(open, timestamp) AS open,
    max(high) AS high,
    min(low) AS low,
    last(close, timestamp) AS close,
    sum(volume) AS volume,
    sum(trade_count) AS trade_count,
    count(*) AS candle_count
FROM candles
WHERE interval_type = 'S1'
GROUP BY symbol, time_bucket(3600000, timestamp);

-- Refresh policy for continuous aggregate (every 5 minutes)
SELECT add_continuous_aggregate_policy('candles_hourly',
    start_offset => INTERVAL '1 day',
    end_offset => INTERVAL '1 minute',
    schedule_interval => INTERVAL '5 minutes');

-- Create index on materialized view
CREATE INDEX IF NOT EXISTS idx_candles_hourly_symbol_time 
    ON candles_hourly (symbol, timestamp DESC);

-- Grant permissions (if using non-superuser)
GRANT ALL PRIVILEGES ON TABLE candles TO candle_user;
GRANT ALL PRIVILEGES ON TABLE candles_hourly TO candle_user;

-- Create helper function to get table stats
CREATE OR REPLACE FUNCTION get_candle_stats()
RETURNS TABLE (
    total_candles BIGINT,
    compressed_chunks INTEGER,
    uncompressed_chunks INTEGER,
    total_size TEXT,
    compressed_size TEXT
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        (SELECT count(*) FROM candles) as total_candles,
        (SELECT count(*) FROM timescaledb_information.chunks 
         WHERE hypertable_name = 'candles' AND is_compressed = true) as compressed_chunks,
        (SELECT count(*) FROM timescaledb_information.chunks 
         WHERE hypertable_name = 'candles' AND is_compressed = false) as uncompressed_chunks,
        pg_size_pretty(pg_total_relation_size('candles')) as total_size,
        pg_size_pretty(pg_total_relation_size('candles') - pg_relation_size('candles')) as compressed_size;
END;
$$ LANGUAGE plpgsql;

-- Sample usage:
-- SELECT * FROM get_candle_stats();

-- Performance optimization: increase shared buffers for better caching
-- Note: Requires PostgreSQL configuration in postgresql.conf
COMMENT ON TABLE candles IS 'OHLC candlestick data partitioned by timestamp using TimescaleDB hypertable';

-- Log successful initialization
DO $$
BEGIN
    RAISE NOTICE 'TimescaleDB candle schema initialized successfully';
    RAISE NOTICE 'Hypertable: candles (chunk_interval=1 day)';
    RAISE NOTICE 'Compression: enabled (after 7 days)';
    RAISE NOTICE 'Continuous aggregate: candles_hourly';
END $$;

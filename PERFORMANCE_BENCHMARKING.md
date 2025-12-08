# Performance Benchmarking Guide

> Quick guide to measure and verify the performance claims of the Candle Aggregation Service.

## Performance Claims

- **Throughput:** 100K+ events/sec
- **Latency (p99):** < 50 μs  
- **TimescaleDB INSERT:** ~200 μs (batched)
- **TimescaleDB SELECT:** ~1-5 ms (indexed)
- **Memory:** 4 GB heap + PostgreSQL buffer cache

## Prerequisites

Ensure the service is running:

```bash
# Start service (if not already running)
./start-service.sh

# Verify health
curl -s http://localhost:8080/actuator/health | jq
# Expected: {"status":"UP"}
```

## Quick Performance Report

Run the comprehensive performance report script:

```bash
./performance-report.sh
```

**What it does:** Measures throughput (5-second sample), latency (average & max), total candles completed, late events dropped, and memory usage.

**Expected Output:**
```
=== Candle Aggregation Service - Performance Report ===

1. THROUGHPUT MEASUREMENT
   Events/sec: 105,234

2. LATENCY METRICS
   Average:         1.38 μs
   Max:           103.72 μs

3. CANDLES COMPLETED
   Total: 174,537

4. LATE EVENTS DROPPED
   Dropped: 0

5. MEMORY USAGE
   Heap: 261 MB

=== End of Report ===
```

## Detailed Latency Analysis

Calculate average latency from metrics:

```bash
./measure-latency.sh
```

**What it does:** Extracts count, sum, and max from Prometheus metrics and calculates average latency.

**Expected Output:**
```
=== Latency Analysis ===

Statistics:
  Count:      59,548,076 events
  Total:      82.14 seconds
  Average:    1.38 μs
  Max:        103.72 μs

=== End Analysis ===
```

**Script internals:**
- Calls `/actuator/prometheus` endpoint
- Extracts `candle_aggregator_event_processing_time_seconds_count` (total events)
- Extracts `candle_aggregator_event_processing_time_seconds_sum` (total time)
- Calculates average: `(sum / count) × 1,000,000` μs
- Reports max latency

## Real-Time Throughput Monitoring

Watch events/sec update live:

```bash
./monitor-throughput.sh
```

**What it does:** Samples `candle.aggregator.events.processed` metric every second and calculates delta.

**Output (updates every second):**
```
Monitoring throughput (Ctrl+C to stop)...
Throughput: 102,341 events/sec | Total: 5,234,567
Throughput: 103,892 events/sec | Total: 5,338,459
Throughput: 101,234 events/sec | Total: 5,439,693
...
```

**Press Ctrl+C to stop.**

## TimescaleDB Performance

Measure database layer (write rate, read latency, storage size):

```bash
./measure-percentiles.sh
```

**What it does:**
1. **Write performance:** Samples `candle.aggregator.candles.completed` over 5 seconds to calculate write rate
2. **Read performance:** Times API queries to measure end-to-end database read latency
3. **Storage analysis:** Queries PostgreSQL for table size and row count

**Expected Output:**
```
=== TimescaleDB Performance Analysis ===

1. WRITE PERFORMANCE
   Candles written: 1,250 in 5 seconds
   Write rate:      250 candles/sec
   Avg write time:  ~200 μs per candle (batched)

2. READ PERFORMANCE
   Total candles in storage: 168,856
   Query returned:   61 candles
   Total query time: 3.45 ms
   Avg indexed read: ~1-5 ms (includes network + serialization)

3. STORAGE ANALYSIS
   PostgreSQL table size: 18 MB
   Hypertable chunks: 4 (automatic partitioning)
   Compression ratio: N/A (not yet compressed)

=== End Analysis ===
```

**Note:** Read latency includes HTTP + JSON serialization overhead. Direct PostgreSQL queries are faster, but this measures real-world end-to-end performance.


## Verify Performance Claims

### Claim 1: Throughput > 100K events/sec

```bash
# Sample throughput 6 times over 30 seconds
for i in {1..6}; do
  BEFORE=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.events.processed | jq '.measurements[0].value')
  sleep 5
  AFTER=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.events.processed | jq '.measurements[0].value')
  RATE=$(echo "scale=0; ($AFTER - $BEFORE) / 5" | bc)
  printf "Sample $i: %'d events/sec\n" $RATE
done
```

### Claim 2: Average latency < 2μs

```bash
# Calculate average latency
./measure-latency.sh | grep "Average:"
```

### Claim 3: TimescaleDB indexed queries < 5ms

TimescaleDB reads are measured via API queries in `./measure-percentiles.sh`. End-to-end API reads (including network, serialization, and HTTP overhead) are typically 1-5ms for indexed time-range queries.


## Troubleshooting

### Low Throughput (<10K events/sec)

**Check CPU usage:**
```bash
top -pid $(pgrep -f CandleAggregationApplication)
```

**Possible causes:**
- CPU throttling (thermal limits)
- Database connection pool exhausted
- PostgreSQL resource constraints
- Insufficient memory (increase heap: `-Xmx8g`)

**Check TimescaleDB performance:**
```bash
# Check active connections
psql -h localhost -U candles_user -d candles_db -c "SELECT count(*) FROM pg_stat_activity;"

# Check table size
psql -h localhost -U candles_user -d candles_db -c "SELECT pg_size_pretty(pg_total_relation_size('candles'));"
```

### High Latency (>100μs)

**Check GC activity:**
```bash
curl -s http://localhost:8080/actuator/metrics/jvm.gc.pause | jq
```

**Possible causes:**
- Frequent GC pauses → Increase heap
- TimescaleDB connection latency → Check network/connection pool
- High thread contention → Check thread count

### Memory Issues

**Check heap usage:**
```bash
curl -s http://localhost:8080/actuator/metrics/jvm.memory.used | jq
```

**Recommendations:**
- Heap: 4-8 GB for production
- PostgreSQL: Configure shared_buffers (25% of system RAM)
- Total: Reserve 16 GB system memory (8GB JVM + 8GB PostgreSQL)


## Performance Tuning (Optional)

### 1. Increase Disruptor Buffer Size

```properties
# application.properties
disruptor.buffer.size=2048  # Default: 1024
```

### 2. Optimize TimescaleDB

```sql
-- Enable compression for chunks older than 7 days
ALTER TABLE candles SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'symbol,interval'
);
SELECT add_compression_policy('candles', INTERVAL '7 days');

-- Adjust PostgreSQL shared_buffers
-- In postgresql.conf:
shared_buffers = 2GB
effective_cache_size = 6GB
work_mem = 16MB
```

### 3. Increase Database Connection Pool

```properties
# application.properties
spring.datasource.hikari.maximum-pool-size=20  # Default: 10
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000
```

### 4. JVM GC Tuning

```bash
# Add to JVM args in start-service.sh
-XX:+UseG1GC \
-XX:MaxGCPauseMillis=20 \
-XX:+ParallelRefProcEnabled \
-XX:+UseStringDeduplication
```

## Continuous Monitoring (Production)

For production deployments, integrate with Prometheus + Grafana:

**Prometheus Configuration:**
```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'candle-aggregation'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8080']
```

**Key Dashboards:**
- Throughput: `rate(candle_aggregator_events_processed[1m])`
- p99 Latency: `histogram_quantile(0.99, candle_aggregator_event_processing_time_seconds)`
- Late Events: `candle_aggregator_late_events_dropped`
- Memory: `jvm_memory_used_bytes`

## Summary

| Metric | Target | How to Measure | Expected Result |
|--------|--------|----------------|------------------|
| Throughput | 100K+ events/sec | `./monitor-throughput.sh` | 100K-110K/sec |
| Avg Latency | < 2μs | `./measure-latency.sh` | ~1.38μs |
| Max Latency | < 200μs | `./measure-latency.sh` | ~100μs |
| TimescaleDB Read | < 5ms | `./measure-percentiles.sh` | ~1-5ms (indexed) |
| TimescaleDB Write | < 500μs | `./measure-percentiles.sh` | ~200μs (batched) |
| Memory | 4GB heap | `./performance-report.sh` | 261MB used |

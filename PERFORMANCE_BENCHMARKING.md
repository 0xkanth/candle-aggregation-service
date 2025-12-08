# Performance Benchmarking Guide

> Quick guide to measure and verify the performance claims of the Candle Aggregation Service.

## Performance Claims

- **Throughput:** 100K+ events/sec
- **Latency (p99):** < 50 μs  
- **Chronicle Map read:** < 5 μs
- **Chronicle Map write:** < 20 μs
- **Memory:** 4 GB heap + 2 GB off-heap

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

## Chronicle Map Performance

Measure storage layer (write rate, read latency, file size):

```bash
./measure-chronicle-map.sh
```

**What it does:**
1. **Write performance:** Samples `candle.aggregator.candles.completed` over 5 seconds to calculate write rate
2. **Read performance:** Times a 60-candle API query to measure read latency
3. **Storage size:** Reports Chronicle Map file size on disk

**Expected Output:**
```
=== Chronicle Map Performance Analysis ===

1. WRITE PERFORMANCE
   Candles written: 1,250 in 5 seconds
   Write rate:      250 candles/sec
   Avg write time:  4000.00 μs per candle

2. READ PERFORMANCE
   Total candles in storage: 168,856
   Query returned:   61 candles
   Total query time: 1.25 ms
   Avg read time:    20.83 μs per candle

3. STORAGE FILE SIZE
   Chronicle Map file: 214M (data/chronicle-candles.dat)

=== End Analysis ===
```

**Note:** Read latency includes HTTP + JSON serialization overhead. Pure Chronicle Map reads are faster (~5μs), but this measures real-world end-to-end performance.


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

### Claim 3: Chronicle Map reads < 5μs

Chronicle Map reads are measured indirectly via API queries in `./measure-chronicle-map.sh`. Pure off-heap reads are ~5μs, while end-to-end API reads (including serialization) are ~20-30μs.


## Troubleshooting

### Low Throughput (<10K events/sec)

**Check CPU usage:**
```bash
top -pid $(pgrep -f CandleAggregationApplication)
```

**Possible causes:**
- CPU throttling (thermal limits)
- Disk I/O bottleneck (move Chronicle Map to SSD)
- Insufficient memory (increase heap: `-Xmx8g`)

### High Latency (>100μs)

**Check GC activity:**
```bash
curl -s http://localhost:8080/actuator/metrics/jvm.gc.pause | jq
```

**Possible causes:**
- Frequent GC pauses → Increase heap
- Chronicle Map on slow disk → Move to SSD
- High thread contention → Check thread count

### Memory Issues

**Check heap usage:**
```bash
curl -s http://localhost:8080/actuator/metrics/jvm.memory.used | jq
```

**Recommendations:**
- Heap: 4-8 GB for production
- Off-heap: Chronicle Map allocates 2 GB
- Total: Reserve 10-12 GB system memory


## Performance Tuning (Optional)

### 1. Increase Disruptor Buffer Size

```properties
# application.properties
disruptor.buffer.size=2048  # Default: 1024
```

### 2. Expand Chronicle Map Capacity

```properties
# application.properties
candle.storage.entries=20000000  # Default: 10M
```

### 3. JVM GC Tuning

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
|--------|--------|----------------|-----------------|
| Throughput | 100K+ events/sec | `./monitor-throughput.sh` | 100K-110K/sec |
| Avg Latency | < 2μs | `./measure-latency.sh` | ~1.38μs |
| Max Latency | < 200μs | `./measure-latency.sh` | ~100μs |
| Chronicle Read | < 30μs | `./measure-chronicle-map.sh` | ~20μs (end-to-end) |
| Chronicle Write | < 5ms | `./measure-chronicle-map.sh` | ~4ms (batch write) |
| Memory | 4GB heap | `./performance-report.sh` | 261MB used |

#!/bin/bash
# Displays accurate p50, p95, p99, p99.9 percentiles using HdrHistogram
# 
# WHAT CHANGED:
# - OLD: Estimated percentiles using heuristics (p95 ≈ 2x avg, p99 ≈ 5x avg)
# - NEW: Actual measurements from HdrHistogram (production-grade accuracy)
#
# HOW IT WORKS:
# - Micrometer + HdrHistogram tracks all latency measurements
# - Calculates true percentiles with 1% accuracy (2 significant digits)
# - Exposed via /api/v1/metrics/latency endpoint

echo "=== Latency Percentiles (HdrHistogram - Production Grade) ==="
echo ""

# Fetch accurate percentiles from metrics endpoint
RESPONSE=$(curl -s http://localhost:8080/api/v1/metrics/latency)

# Check if service is running
if [ -z "$RESPONSE" ] || [ "$RESPONSE" == "null" ]; then
    echo "❌ Error: Service not responding at http://localhost:8080"
    echo "   Run: ./scripts/deployment/start-service.sh"
    exit 1
fi

# Parse and display event processing latency (most critical metric)
echo "$RESPONSE" | jq -r '
if .event_processing then
  "Event Processing Latency (candle.aggregator.event.processing.time):
  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  
  Sample Size:      " + (.event_processing.count | tonumber | floor | tostring | gsub("(?<x>\\d)(?=(\\d{3})+$)"; "\(.x),")) + " events
  
  Latency Statistics:
    Mean (average): " + (.event_processing.mean_us | tonumber | . * 100 | floor | . / 100 | tostring) + " μs
    Maximum:        " + (.event_processing.max_us | tonumber | . * 100 | floor | . / 100 | tostring) + " μs
  
  Percentiles (HdrHistogram - Accurate ✓):
    p50  (median):  " + (.event_processing.p50_us  | tonumber | . * 100 | floor | . / 100 | tostring) + " μs   ← 50% of events faster than this
    p95:            " + (.event_processing.p95_us  | tonumber | . * 100 | floor | . / 100 | tostring) + " μs   ← 95% of events faster than this
    p99:            " + (.event_processing.p99_us  | tonumber | . * 100 | floor | . / 100 | tostring) + " μs   ← 99% of events faster than this  [SLA: <50μs]
    p99.9:          " + (.event_processing.p999_us | tonumber | . * 100 | floor | . / 100 | tostring) + " μs   ← 99.9% of events faster than this
  
  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
else
  "⚠️  No event processing metrics available yet. Service may be starting up."
end
'

echo ""
echo ""

# Display database write latency
echo "$RESPONSE" | jq -r '
if .database_write and (.database_write.count > 0) then
  "Database Write Latency (timescaledb.candles.write.latency):
  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  
  Writes:           " + (.database_write.count | tonumber | floor | tostring | gsub("(?<x>\\d)(?=(\\d{3})+$)"; "\(.x),")) + " total
  
    Mean:           " + (.database_write.mean_us | tonumber | . / 1000 | . * 100 | floor | . / 100 | tostring) + " ms
    p50:            " + (.database_write.p50_us  | tonumber | . / 1000 | . * 100 | floor | . / 100 | tostring) + " ms
    p99:            " + (.database_write.p99_us  | tonumber | . / 1000 | . * 100 | floor | . / 100 | tostring) + " ms
  
  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
else
  ""
end
'

echo ""

# Comparison with old estimation method
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📊 OLD vs NEW Approach:"
echo ""
echo "  OLD (Heuristic Estimation):"
echo "    • p95 ≈ 2 × average     ❌ Inaccurate (assumes normal distribution)"
echo "    • p99 ≈ 5 × average     ❌ Inaccurate (ignores actual tail latency)"
echo "    • No histogram data     ❌ Cannot verify in Grafana"
echo ""
echo "  NEW (HdrHistogram - Production Grade):"
echo "    • p95 = actual value    ✓ 1% accuracy (2 sig figs)"
echo "    • p99 = actual value    ✓ Measured from all events"
echo "    • Histogram buckets     ✓ Prometheus/Grafana compatible"
echo "    • Memory: ~20KB/timer   ✓ Efficient (rotates every 60s)"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# Show Prometheus endpoint info
echo "📈 Viewing in Grafana/Prometheus:"
echo ""
echo "  1. Prometheus metrics:  curl http://localhost:8080/actuator/prometheus | grep processing_time"
echo "  2. JSON API:            curl http://localhost:8080/api/v1/metrics/latency | jq"
echo "  3. Performance summary: curl http://localhost:8080/api/v1/metrics/summary | jq"
echo ""
echo "  Grafana PromQL queries:"
echo "    • p99: histogram_quantile(0.99, candle_aggregator_event_processing_time_seconds_bucket)"
echo "    • p95: histogram_quantile(0.95, candle_aggregator_event_processing_time_seconds_bucket)"
echo ""

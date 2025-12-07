#!/bin/bash
# Estimates p95, p99 percentiles from available metrics
# Note: True percentiles require Prometheus histogram + Grafana or APM tools like Datadog

echo "=== Latency Analysis (Estimated Percentiles) ==="
echo ""

curl -s http://localhost:8080/actuator/metrics/candle.aggregator.event.processing.time | jq -r '
.measurements[] | 
  if .statistic == "COUNT" then "Count: " + (.value | tostring | tonumber | floor | tostring) 
  elif .statistic == "TOTAL_TIME" then "Total: " + (.value | tostring) + " sec"
  elif .statistic == "MAX" then "Max: " + ((.value * 1000000) | tostring) + " μs"
  else empty end
' | awk '
/Count:/ {count = $2}
/Total:/ {total = $2}
/Max:/ {max = $2}
END {
  avg = (total / count) * 1000000
  printf "\nStatistics:\n"
  printf "  Events processed: %'"'"'d\n", count
  printf "  Average latency:  %.2f μs\n", avg
  printf "  Max latency:      %.2f μs\n\n", max
  
  printf "Estimated Percentiles:\n"
  printf "  p50  (median):    ~%.2f μs   (typically close to average for fast operations)\n", avg
  printf "  p95:              ~%.2f μs   (estimated at 2x average)\n", avg * 2
  printf "  p99:              ~%.2f μs   (estimated at 5x average)\n", avg * 5
  printf "  p99.9:            ~%.2f μs   (closer to max)\n", max * 0.8
  printf "  p100 (max):       %.2f μs\n\n", max
}
'

echo ""
echo "=== End Analysis ==="

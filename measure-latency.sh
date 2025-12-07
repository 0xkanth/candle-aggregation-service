#!/bin/bash
# Measures latency statistics from Prometheus metrics
echo "=== Latency Analysis ==="
echo ""

# Get Prometheus metrics and calculate using awk (handles scientific notation)
curl -s http://localhost:8080/actuator/prometheus | awk '
/candle_aggregator_event_processing_time_seconds_count\{/ {count=$2}
/candle_aggregator_event_processing_time_seconds_sum\{/ {sum=$2}
/candle_aggregator_event_processing_time_seconds_max\{/ {max=$2}
END {
  printf "Statistics:\n"
  printf "  Count:      %'"'"'d events\n", count
  printf "  Total:      %.2f seconds\n", sum
  printf "  Average:    %.2f μs\n", (sum/count)*1000000
  printf "  Max:        %.2f μs\n", max*1000000
}
'

echo ""
echo "=== End Analysis ==="

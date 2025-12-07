#!/bin/bash
# Generates a complete performance report with all key metrics
echo "=== Candle Aggregation Service - Performance Report ==="
echo ""

# Throughput
echo "1. THROUGHPUT MEASUREMENT"
INITIAL=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.events.processed | jq '.measurements[0].value')
sleep 5
FINAL=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.events.processed | jq '.measurements[0].value')
RATE=$(echo "scale=0; ($FINAL - $INITIAL) / 5" | bc)
RATE_INT=$(printf "%.0f" $RATE)
printf "   Events/sec: %'d\n" $RATE_INT
echo ""

# Latency (average and max)
echo "2. LATENCY METRICS"
curl -s http://localhost:8080/actuator/prometheus | awk '
/candle_aggregator_event_processing_time_seconds_count\{/ {count=$2}
/candle_aggregator_event_processing_time_seconds_sum\{/ {sum=$2}
/candle_aggregator_event_processing_time_seconds_max\{/ {max=$2}
END {
  printf "   Average:    %8.2f μs\n", (sum/count)*1000000
  printf "   Max:        %8.2f μs\n", max*1000000
}
'
echo ""

# Candles completed
echo "3. CANDLES COMPLETED"
CANDLES=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.candles.completed | jq '.measurements[0].value' 2>/dev/null || echo 0)
CANDLES_INT=$(printf "%.0f" $CANDLES)
printf "   Total: %'d\n" $CANDLES_INT
echo ""

# Late events
echo "4. LATE EVENTS DROPPED"
LATE=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.late.events.dropped 2>/dev/null | jq '.measurements[0].value // 0')
LATE_INT=$(printf "%.0f" $LATE)
printf "   Dropped: %'d\n" $LATE_INT
echo ""

# Memory
echo "5. MEMORY USAGE"
HEAP=$(curl -s http://localhost:8080/actuator/metrics/jvm.memory.used | jq '.measurements[0].value')
HEAP_MB=$(awk "BEGIN {printf \"%.0f\", $HEAP / 1024 / 1024}")
printf "   Heap: %'d MB\n" $HEAP_MB
echo ""

echo "=== End of Report ==="

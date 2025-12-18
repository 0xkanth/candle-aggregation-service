#!/bin/bash
# Generates a complete performance report with all key metrics
echo "=== Candle Aggregation Service - Performance Report ==="
echo ""

# Throughput
echo "1. THROUGHPUT MEASUREMENT"
INITIAL=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.events.processed | jq -r '.measurements[0].value // 0')
sleep 5
FINAL=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.events.processed | jq -r '.measurements[0].value // 0')

# Handle null/empty values
if [ -z "$INITIAL" ] || [ "$INITIAL" = "null" ]; then INITIAL=0; fi
if [ -z "$FINAL" ] || [ "$FINAL" = "null" ]; then FINAL=0; fi

RATE=$(echo "scale=2; ($FINAL - $INITIAL) / 5" | bc 2>/dev/null || echo "0")
RATE_INT=$(printf "%.0f" "$RATE" 2>/dev/null || echo "0")
printf "   Events/sec: %'d\n" "$RATE_INT"
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
CANDLES=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.candles.completed 2>/dev/null | jq -r '.measurements[0].value // 0')
if [ -z "$CANDLES" ] || [ "$CANDLES" = "null" ]; then CANDLES=0; fi
CANDLES_INT=$(printf "%.0f" "$CANDLES" 2>/dev/null || echo "0")
printf "   Total: %'d\n" "$CANDLES_INT"
echo ""

# Late events
echo "4. LATE EVENTS DROPPED"
LATE=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.late.events.dropped 2>/dev/null | jq -r '.measurements[0].value // 0')
if [ -z "$LATE" ] || [ "$LATE" = "null" ]; then LATE=0; fi
LATE_INT=$(printf "%.0f" "$LATE" 2>/dev/null || echo "0")
printf "   Dropped: %'d\n" "$LATE_INT"
echo ""

# Memory
echo "5. MEMORY USAGE"
HEAP=$(curl -s http://localhost:8080/actuator/metrics/jvm.memory.used 2>/dev/null | jq -r '.measurements[0].value // 0')
if [ -z "$HEAP" ] || [ "$HEAP" = "null" ] || [ "$HEAP" = "0" ]; then HEAP=0; fi
HEAP_MB=$(echo "scale=0; $HEAP / 1024 / 1024" | bc 2>/dev/null || echo "0")
printf "   Heap: %'d MB\n" "$HEAP_MB"
echo ""

echo "=== End of Report ==="

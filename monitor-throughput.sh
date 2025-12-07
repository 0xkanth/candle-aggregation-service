#!/bin/bash
# Monitors throughput in events/sec by sampling every second
echo "Monitoring throughput (Ctrl+C to stop)..."
PREV=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.events.processed | jq '.measurements[0].value')
sleep 1

while true; do
  CURR=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.events.processed | jq '.measurements[0].value')
  RATE=$(echo "$CURR - $PREV" | bc)
  RATE_INT=$(printf "%.0f" $RATE)
  CURR_INT=$(printf "%.0f" $CURR)
  printf "Throughput: %'d events/sec | Total: %'d\n" $RATE_INT $CURR_INT
  PREV=$CURR
  sleep 1
done

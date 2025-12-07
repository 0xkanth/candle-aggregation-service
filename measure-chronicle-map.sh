#!/bin/bash
echo "=== Chronicle Map Performance Analysis ==="
echo ""

# Get candles completed
echo "1. WRITE PERFORMANCE"
INITIAL=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.candles.completed | jq '.measurements[0].value' 2>/dev/null || echo 0)
sleep 5
FINAL=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.candles.completed | jq '.measurements[0].value' 2>/dev/null || echo 0)
WRITES=$(echo "$FINAL - $INITIAL" | bc)
WRITE_RATE=$(echo "scale=0; $WRITES / 5" | bc)

# Convert to integer for printf
WRITES_INT=$(printf "%.0f" $WRITES)
WRITE_RATE_INT=$(printf "%.0f" $WRITE_RATE)

echo "   Candles written: $WRITES_INT in 5 seconds"
echo "   Write rate:      $WRITE_RATE_INT candles/sec"

# Calculate average write latency (approximate)
if [ $(echo "$WRITE_RATE > 0" | bc) -eq 1 ]; then
  AVG_WRITE_US=$(echo "scale=2; 1000000 / $WRITE_RATE" | bc)
  echo "   Avg write time:  $AVG_WRITE_US μs per candle"
fi

echo ""

# Get total candles for read test
TOTAL_CANDLES=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.candles.completed | jq '.measurements[0].value' 2>/dev/null || echo 0)
TOTAL_CANDLES_INT=$(printf "%.0f" $TOTAL_CANDLES)
echo "2. READ PERFORMANCE"
echo "   Total candles in storage: $TOTAL_CANDLES_INT"

# Measure API query time (includes Chronicle Map reads)
NOW=$(date +%s)
START_NS=$(date +%s%N)
RESPONSE=$(curl -s "http://localhost:8080/api/v1/history?symbol=BTCUSD&interval=1s&from=$((NOW-60))&to=$NOW" 2>/dev/null)
END_NS=$(date +%s%N)

ELAPSED_MS=$(echo "scale=2; ($END_NS - $START_NS) / 1000000" | bc)
CANDLE_COUNT=$(echo "$RESPONSE" | jq '.t | length' 2>/dev/null || echo 0)
CANDLE_COUNT_INT=$(printf "%.0f" $CANDLE_COUNT)

echo "   Query returned:   $CANDLE_COUNT_INT candles"
echo "   Total query time: $ELAPSED_MS ms"

if [ $(echo "$CANDLE_COUNT > 0" | bc) -eq 1 ]; then
  AVG_READ_US=$(echo "scale=2; ($ELAPSED_MS * 1000) / $CANDLE_COUNT" | bc)
  echo "   Avg read time:    $AVG_READ_US μs per candle"
fi

echo ""
echo "3. STORAGE FILE SIZE"
# Check multiple possible locations
if [ -f "./extracted/data/chronicle-candles.dat" ]; then
  SIZE=$(du -h ./extracted/data/chronicle-candles.dat | awk '{print $1}')
  echo "   Chronicle Map file: $SIZE (extracted/data/chronicle-candles.dat)"
elif [ -f "./data/candles.dat" ]; then
  SIZE=$(du -h ./data/candles.dat | awk '{print $1}')
  echo "   Chronicle Map file: $SIZE (data/candles.dat)"
elif [ -f "./data/chronicle-candles.dat" ]; then
  SIZE=$(du -h ./data/chronicle-candles.dat | awk '{print $1}')
  echo "   Chronicle Map file: $SIZE (data/chronicle-candles.dat)"
else
  echo "   Chronicle Map file: Not found"
  echo "   (Checked: ./extracted/data/, ./data/)"
fi

echo ""
echo "=== End Analysis ==="

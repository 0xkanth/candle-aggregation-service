#!/bin/bash

# Candle Aggregation Service Test Script
# Run this in a SEPARATE terminal to test the service without interrupting it

echo "========================================="
echo "Candle Aggregation Service - Test Suite"
echo "========================================="
echo ""

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Wait for service to be ready
echo -e "${YELLOW}1. Checking if service is running...${NC}"
for i in {1..30}; do
    if curl -s http://localhost:8080/actuator/health > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Service is UP!${NC}"
        break
    fi
    if [ $i -eq 30 ]; then
        echo -e "${RED}✗ Service is not responding. Please start it first with: ./start-service.sh${NC}"
        exit 1
    fi
    echo -n "."
    sleep 1
done
echo ""

# Test 1: Health Check
echo -e "${BLUE}2. Health Check:${NC}"
curl -s http://localhost:8080/actuator/health | jq .
echo ""
echo ""

# Test 2: Available Metrics
echo -e "${BLUE}3. Available Metrics:${NC}"
echo "Getting list of custom metrics..."
curl -s http://localhost:8080/actuator/metrics | jq '.names[] | select(startswith("candle"))'
echo ""
echo ""

# Test 3: Events Processed Metric
echo -e "${BLUE}4. Events Processed Metric:${NC}"
curl -s http://localhost:8080/actuator/metrics/candle.events.processed | jq .
echo ""
echo ""

# Test 4: Candles Created Metric  
echo -e "${BLUE}5. Candles Created Metric:${NC}"
curl -s http://localhost:8080/actuator/metrics/candle.candles.created | jq .
echo ""
echo ""

# Wait a bit for some data to accumulate
echo -e "${YELLOW}Waiting 3 seconds for data to accumulate...${NC}"
sleep 3
echo ""

# Calculate time range for queries (last 30 seconds)
TO=$(date +%s)
FROM=$((TO - 30))

# Test 5: Get BTCUSD candles (S1 interval)
echo -e "${BLUE}6. BTCUSD Candles (1-second interval, last 30s):${NC}"
curl -s "http://localhost:8080/api/v1/history?symbol=BTCUSD&interval=1s&from=$FROM&to=$TO" | jq -c '{count: (.t | length), latest_5_times: .t[-5:], latest_5_close: .c[-5:]}'
echo ""
echo ""

# Test 7: Get BTCUSD candles (S5 interval)
echo -e "${BLUE}7. BTCUSD Candles (5-second interval, last 30s):${NC}"
curl -s "http://localhost:8080/api/v1/history?symbol=BTCUSD&interval=5s&from=$FROM&to=$TO" | jq -c '{count: (.t | length), latest_5_times: .t[-5:], latest_5_close: .c[-5:]}'
echo ""
echo ""

# Test 8: Get ETHUSD candles
echo -e "${BLUE}8. ETHUSD Candles (1-second interval, last 30s):${NC}"
curl -s "http://localhost:8080/api/v1/history?symbol=ETHUSD&interval=1s&from=$FROM&to=$TO" | jq -c '{count: (.t | length), latest_5_times: .t[-5:], latest_5_close: .c[-5:]}'
echo ""
echo ""

# Test 9: Get EURUSD candles (from production generator)
echo -e "${BLUE}9. EURUSD Candles (5-second interval, last 30s):${NC}"
curl -s "http://localhost:8080/api/v1/history?symbol=EURUSD&interval=5s&from=$FROM&to=$TO" | jq -c '{count: (.t | length), latest_5_times: .t[-5:], latest_5_close: .c[-5:]}'
echo ""
echo ""

# Test 10: Get XAUUSD (Gold) candles
echo -e "${BLUE}10. XAUUSD/Gold Candles (1-second interval, last 30s):${NC}"
curl -s "http://localhost:8080/api/v1/history?symbol=XAUUSD&interval=1s&from=$FROM&to=$TO" | jq -c '{count: (.t | length), latest_5_times: .t[-5:], latest_5_close: .c[-5:]}'
echo ""
echo ""

# Test 11: Performance Summary
echo -e "${BLUE}11. Performance Summary:${NC}"
echo "Total Events Processed:"
curl -s http://localhost:8080/actuator/metrics/candle.aggregator.events.processed 2>/dev/null | jq '.measurements[0].value // "N/A"'
echo ""
echo "Total Candles Created:"
curl -s http://localhost:8080/actuator/metrics/candle.aggregator.candles.completed 2>/dev/null | jq '.measurements[0].value // "N/A"'
echo ""
echo "Average Event Processing Time (microseconds):"
curl -s http://localhost:8080/actuator/metrics/candle.aggregator.event.processing.time 2>/dev/null | jq '.measurements[] | select(.statistic == "MEAN") | .value * 1000000'
echo ""
echo ""

# Test 12: All Available Symbols
echo -e "${BLUE}12. Sample from All Production Instruments (last 30s):${NC}"
echo ""

# Production Scale Instruments
SYMBOLS=("BTCUSD" "ETHUSD" "SOLUSD" "EURUSD" "GBPUSD" "XAUUSD" "XAGUSD" "SPX500")

for symbol in "${SYMBOLS[@]}"; do
    echo -e "${YELLOW}${symbol}:${NC}"
    result=$(curl -s "http://localhost:8080/api/v1/history?symbol=${symbol}&interval=1s&from=$FROM&to=$TO" 2>/dev/null)
    if [ ! -z "$result" ] && echo "$result" | jq -e '.t | length > 0' > /dev/null 2>&1; then
        echo "$result" | jq -c '{candle_count: (.t | length), latest_close: .c[-1], latest_time: .t[-1]}'
    else
        echo "  (No data yet or waiting for candles)"
    fi
    echo ""
done

echo ""
echo -e "${GREEN}=========================================${NC}"
echo -e "${GREEN}Test Suite Complete!${NC}"
echo -e "${GREEN}=========================================${NC}"
echo ""
echo -e "${YELLOW}Continuous monitoring commands:${NC}"
echo ""
echo "# Watch events being processed (run in another terminal):"
echo "watch -n 1 'curl -s http://localhost:8080/actuator/metrics/candle.aggregator.events.processed | jq'"
echo ""
echo "# Watch BTCUSD latest candles:"
echo "watch -n 1 'TO_NOW=\$(date +%s); FROM_NOW=\$((TO_NOW - 10)); curl -s \"http://localhost:8080/api/v1/history?symbol=BTCUSD&interval=1s&from=\$FROM_NOW&to=\$TO_NOW\" | jq -c \"{count: (.t | length), latest_close: .c[-1]}\"'"
echo ""
echo "# Get real-time performance stats:"
echo "watch -n 2 'curl -s http://localhost:8080/actuator/metrics | jq \".names[] | select(startswith(\\\"candle\\\"))\"'"
echo ""

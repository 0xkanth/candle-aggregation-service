#!/bin/bash

################################################################################
# Master Startup Script - Candle Aggregation Service
# Complete workflow: Clean → Build → Start TimescaleDB → Start Service → Test
################################################################################

set -e  # Exit on any error

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BOLD='\033[1m'
NC='\033[0m'

# Project directory
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

echo -e "${BOLD}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║        Candle Aggregation Service - Master Launcher           ║${NC}"
echo -e "${BOLD}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Step 1: Stop any running services
echo -e "${BLUE}[1/6]${NC} ${BOLD}Stopping existing services...${NC}"
./stop-service.sh 2>/dev/null || true
echo -e "${GREEN}   ✓ Services stopped${NC}"
echo ""

# Step 2: Clean and compile
echo -e "${BLUE}[2/6]${NC} ${BOLD}Cleaning and building project...${NC}"
echo -e "${YELLOW}   Running: mvn clean package -DskipTests${NC}"
mvn clean package -DskipTests

if [ $? -ne 0 ]; then
    echo -e "${RED}   ✗ Build failed${NC}"
    exit 1
fi
echo -e "${GREEN}   ✓ Build successful${NC}"
echo ""

# Step 3: Start TimescaleDB
echo -e "${BLUE}[3/6]${NC} ${BOLD}Starting TimescaleDB (PostgreSQL)...${NC}"

if ! command -v docker &> /dev/null; then
    echo -e "${RED}   ✗ Docker not found. Please install Docker first.${NC}"
    exit 1
fi

if ! docker-compose ps | grep -q "timescaledb.*Up"; then
    echo -e "${YELLOW}   Starting docker-compose...${NC}"
    docker-compose up -d
    
    if [ $? -ne 0 ]; then
        echo -e "${RED}   ✗ Failed to start TimescaleDB${NC}"
        exit 1
    fi
    
    echo -e "${YELLOW}   Waiting 5 seconds for database initialization...${NC}"
    sleep 5
else
    echo -e "${GREEN}   ✓ TimescaleDB already running${NC}"
fi

# Verify database connection
echo -e "${YELLOW}   Verifying database connection...${NC}"
for i in {1..10}; do
    if docker exec timescaledb pg_isready -U candles_user -d candles_db &>/dev/null; then
        echo -e "${GREEN}   ✓ TimescaleDB ready${NC}"
        break
    fi
    if [ $i -eq 10 ]; then
        echo -e "${RED}   ✗ Database connection timeout${NC}"
        exit 1
    fi
    echo -n "."
    sleep 1
done
echo ""

# Step 4: Start the application
echo -e "${BLUE}[4/6]${NC} ${BOLD}Starting Candle Aggregation Service...${NC}"
echo -e "${YELLOW}   ProductionScaleDataGenerator will generate 100K events/sec${NC}"
./start-service.sh

# Step 5: Wait for application startup
echo ""
echo -e "${BLUE}[5/6]${NC} ${BOLD}Waiting for application startup...${NC}"
echo -e "${YELLOW}   Checking health endpoint (max 30 seconds)...${NC}"

for i in {1..30}; do
    if curl -s http://localhost:8080/actuator/health &>/dev/null; then
        HEALTH=$(curl -s http://localhost:8080/actuator/health | jq -r '.status' 2>/dev/null)
        if [ "$HEALTH" = "UP" ]; then
            echo -e "${GREEN}   ✓ Application is UP and running!${NC}"
            break
        fi
    fi
    
    if [ $i -eq 30 ]; then
        echo -e "${RED}   ✗ Application failed to start${NC}"
        echo -e "${YELLOW}   Check logs: tail -f logs/application.log${NC}"
        exit 1
    fi
    echo -n "."
    sleep 1
done
echo ""

# Give generator a moment to produce data
echo -e "${YELLOW}   Waiting 5 seconds for data generation...${NC}"
sleep 5
echo ""

# Step 6: Test with curl requests
echo -e "${BLUE}[6/6]${NC} ${BOLD}Running verification tests...${NC}"
echo ""

# Test 1: Health Check
echo -e "${YELLOW}Test 1: Health Check${NC}"
HEALTH_RESPONSE=$(curl -s http://localhost:8080/actuator/health)
echo "$HEALTH_RESPONSE" | jq .
echo ""

# Test 2: Metrics Check
echo -e "${YELLOW}Test 2: Events Processed${NC}"
EVENTS=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.events.processed | jq -r '.measurements[0].value // 0' 2>/dev/null)
EVENTS_INT=$(printf "%.0f" $EVENTS 2>/dev/null || echo "0")
echo -e "   Total events processed: ${GREEN}${EVENTS_INT}${NC}"
echo ""

echo -e "${YELLOW}Test 3: Candles Completed${NC}"
CANDLES=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.candles.completed | jq -r '.measurements[0].value // 0' 2>/dev/null)
CANDLES_INT=$(printf "%.0f" $CANDLES 2>/dev/null || echo "0")
echo -e "   Total candles created: ${GREEN}${CANDLES_INT}${NC}"
echo ""

# Test 4: API Query Test
echo -e "${YELLOW}Test 4: API Query (BTCUSD - last 10 seconds)${NC}"
TO=$(date +%s)
FROM=$((TO - 10))
API_RESPONSE=$(curl -s "http://localhost:8080/api/v1/history?symbol=BTCUSD&interval=1s&from=$FROM&to=$TO")

if echo "$API_RESPONSE" | jq -e '.s == "ok"' &>/dev/null; then
    CANDLE_COUNT=$(echo "$API_RESPONSE" | jq '.t | length')
    if [ "$CANDLE_COUNT" -gt 0 ]; then
        echo -e "   ${GREEN}✓ API working - Retrieved $CANDLE_COUNT candles${NC}"
        echo -e "   Sample data:"
        echo "$API_RESPONSE" | jq -c '{candles: .t | length, latest_close: .c[-1], latest_time: .t[-1]}'
    else
        echo -e "   ${YELLOW}⚠ API working but no data yet (wait a few more seconds)${NC}"
    fi
else
    echo -e "   ${RED}✗ API response invalid${NC}"
    echo "$API_RESPONSE" | jq . || echo "$API_RESPONSE"
fi
echo ""

# Test 5: Multiple Symbols Test
echo -e "${YELLOW}Test 5: Multi-Symbol Check${NC}"
SYMBOLS=("BTCUSD" "ETHUSD" "EURUSD" "XAUUSD")
SUCCESS_COUNT=0

for symbol in "${SYMBOLS[@]}"; do
    RESPONSE=$(curl -s "http://localhost:8080/api/v1/history?symbol=${symbol}&interval=1s&from=$FROM&to=$TO" 2>/dev/null)
    COUNT=$(echo "$RESPONSE" | jq '.t | length' 2>/dev/null || echo "0")
    
    if [ "$COUNT" -gt 0 ]; then
        echo -e "   ${GREEN}✓${NC} $symbol: $COUNT candles"
        SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
    else
        echo -e "   ${YELLOW}○${NC} $symbol: No data yet"
    fi
done

if [ $SUCCESS_COUNT -eq ${#SYMBOLS[@]} ]; then
    echo -e "   ${GREEN}✓ All symbols generating data!${NC}"
elif [ $SUCCESS_COUNT -gt 0 ]; then
    echo -e "   ${YELLOW}⚠ $SUCCESS_COUNT/${#SYMBOLS[@]} symbols have data (others may need more time)${NC}"
fi
echo ""

# Test 6: Performance Check
echo -e "${YELLOW}Test 6: Real-Time Throughput${NC}"
BEFORE=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.events.processed | jq -r '.measurements[0].value')
sleep 2
AFTER=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.events.processed | jq -r '.measurements[0].value')
RATE=$(echo "scale=0; ($AFTER - $BEFORE) / 2" | bc)
RATE_INT=$(printf "%.0f" $RATE)

if [ $RATE_INT -gt 50000 ]; then
    echo -e "   ${GREEN}✓ Throughput: ${RATE_INT} events/sec (Excellent!)${NC}"
elif [ $RATE_INT -gt 10000 ]; then
    echo -e "   ${GREEN}✓ Throughput: ${RATE_INT} events/sec (Good)${NC}"
else
    echo -e "   ${YELLOW}⚠ Throughput: ${RATE_INT} events/sec (Lower than expected)${NC}"
fi
echo ""

# Summary
echo -e "${BOLD}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║                     STARTUP COMPLETE! ✓                        ║${NC}"
echo -e "${BOLD}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "${GREEN}Service Status:${NC}"
echo -e "   • Application:     ${GREEN}RUNNING${NC} (PID: $(cat logs/application.pid 2>/dev/null || echo 'N/A'))"
echo -e "   • TimescaleDB:     ${GREEN}RUNNING${NC}"
echo -e "   • Generator:       ${GREEN}ACTIVE${NC} (ProductionScaleDataGenerator @ 100K events/sec)"
echo -e "   • API:             ${GREEN}READY${NC} (http://localhost:8080)"
echo ""
echo -e "${YELLOW}Quick Commands:${NC}"
echo -e "   • View logs:       ${BLUE}tail -f logs/application.log${NC}"
echo -e "   • Monitor metrics: ${BLUE}./monitor-throughput.sh${NC}"
echo -e "   • Performance:     ${BLUE}./performance-report.sh${NC}"
echo -e "   • Test suite:      ${BLUE}./test-service.sh${NC}"
echo -e "   • Stop service:    ${BLUE}./stop-service.sh${NC}"
echo ""
echo -e "${YELLOW}Sample API Queries:${NC}"
echo ""
echo -e "   # Get BTCUSD last 30 seconds (1s candles)"
echo -e "   ${BLUE}NOW=\$(date +%s); curl \"http://localhost:8080/api/v1/history?symbol=BTCUSD&interval=1s&from=\$((NOW-30))&to=\$NOW\" | jq${NC}"
echo ""
echo -e "   # Get ETHUSD last 1 minute (5s candles)"
echo -e "   ${BLUE}NOW=\$(date +%s); curl \"http://localhost:8080/api/v1/history?symbol=ETHUSD&interval=5s&from=\$((NOW-60))&to=\$NOW\" | jq${NC}"
echo ""
echo -e "   # Real-time health check"
echo -e "   ${BLUE}curl http://localhost:8080/actuator/health | jq${NC}"
echo ""
echo -e "${GREEN}Happy Trading! 🚀${NC}"
echo ""

#!/bin/bash

################################################################################
# Docker-based Launch - Production-like Environment
# Uses: TimescaleDB in Docker + Java application
# Best for: Full integration testing, production simulation
################################################################################

set -e

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BOLD='\033[1m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_DIR"

echo -e "${BOLD}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║           Docker-based Production Launch                      ║${NC}"
echo -e "${BOLD}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Step 1: Stop existing services
echo -e "${BLUE}[1/5]${NC} ${BOLD}Stopping existing services...${NC}"
./stop-service.sh 2>/dev/null || true
echo -e "${GREEN}   ✓ Services stopped${NC}"
echo ""

# Step 2: Build application
echo -e "${BLUE}[2/5]${NC} ${BOLD}Building application...${NC}"
mvn clean package -DskipTests
if [ $? -ne 0 ]; then
    echo -e "${RED}   ✗ Build failed${NC}"
    exit 1
fi
echo -e "${GREEN}   ✓ Build successful${NC}"
echo ""

# Step 3: Ensure TimescaleDB is running and healthy
echo -e "${BLUE}[3/5]${NC} ${BOLD}Ensuring TimescaleDB is running...${NC}"

if ! command -v docker &> /dev/null; then
    echo -e "${RED}   ✗ Docker not found. TimescaleDB requires Docker.${NC}"
    echo -e "${YELLOW}   Install: https://docs.docker.com/get-docker/${NC}"
    exit 1
fi

# Check if container exists and is running
if docker ps | grep -q "timescaledb"; then
    echo -e "${GREEN}   ✓ TimescaleDB container already running${NC}"
else
    echo -e "${YELLOW}   Starting TimescaleDB container...${NC}"
    docker-compose up -d
    
    if [ $? -ne 0 ]; then
        echo -e "${RED}   ✗ Failed to start TimescaleDB${NC}"
        exit 1
    fi
    
    echo -e "${YELLOW}   Waiting for database initialization (5s)...${NC}"
    sleep 5
fi

# Verify database is accepting connections
echo -e "${YELLOW}   Verifying database health...${NC}"
for i in {1..15}; do
    if docker exec timescaledb pg_isready -U candles_user -d candles_db &>/dev/null; then
        echo -e "${GREEN}   ✓ Database healthy and accepting connections${NC}"
        break
    fi
    
    if [ $i -eq 15 ]; then
        echo -e "${RED}   ✗ Database health check timeout${NC}"
        echo -e "${YELLOW}   Check logs: docker logs timescaledb${NC}"
        exit 1
    fi
    
    echo -n "."
    sleep 1
done
echo ""

# Step 4: Start application
echo -e "${BLUE}[4/5]${NC} ${BOLD}Starting application...${NC}"
./start-service.sh
echo ""

# Step 5: Verify startup
echo -e "${BLUE}[5/5]${NC} ${BOLD}Verifying startup...${NC}"
for i in {1..30}; do
    if curl -s http://localhost:8080/actuator/health &>/dev/null; then
        HEALTH=$(curl -s http://localhost:8080/actuator/health | jq -r '.status' 2>/dev/null)
        if [ "$HEALTH" = "UP" ]; then
            echo -e "${GREEN}   ✓ Application ready${NC}"
            break
        fi
    fi
    [ $i -eq 30 ] && { echo -e "${RED}   ✗ Startup timeout${NC}"; exit 1; }
    sleep 1
done

sleep 5  # Wait for data generation

echo ""
echo -e "${BOLD}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║                    Verification Tests                         ║${NC}"
echo -e "${BOLD}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Health check
echo -e "${YELLOW}[1] Health Status${NC}"
curl -s http://localhost:8080/actuator/health | jq .
echo ""

# Metrics
echo -e "${YELLOW}[2] Performance Metrics${NC}"
EVENTS=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.events.processed | jq -r '.measurements[0].value // 0' 2>/dev/null)
CANDLES=$(curl -s http://localhost:8080/actuator/metrics/candle.aggregator.candles.completed | jq -r '.measurements[0].value // 0' 2>/dev/null)
echo -e "   Events processed: ${GREEN}$(printf "%.0f" $EVENTS 2>/dev/null || echo "0")${NC}"
echo -e "   Candles created:  ${GREEN}$(printf "%.0f" $CANDLES 2>/dev/null || echo "0")${NC}"
echo ""

# API test
echo -e "${YELLOW}[3] API Query Test${NC}"
TO=$(date +%s)
FROM=$((TO - 10))
RESPONSE=$(curl -s "http://localhost:8080/api/v1/history?symbol=BTCUSD&interval=1s&from=$FROM&to=$TO")
COUNT=$(echo "$RESPONSE" | jq '.t | length' 2>/dev/null || echo "0")
echo -e "   Retrieved: ${GREEN}$COUNT candles${NC}"
echo ""

# Swagger
echo -e "${YELLOW}[4] Opening Swagger UI...${NC}"
if command -v open &> /dev/null; then
    open http://localhost:8080/swagger-ui/index.html
    echo -e "${GREEN}   ✓ Swagger UI opened in browser${NC}"
else
    echo -e "${YELLOW}   URL: http://localhost:8080/swagger-ui/index.html${NC}"
fi
echo ""

echo -e "${BOLD}${GREEN}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}${GREEN}║              🚀 System Ready - Docker Mode                     ║${NC}"
echo -e "${BOLD}${GREEN}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  API:     http://localhost:8080/api/v1/history"
echo -e "  Swagger: http://localhost:8080/swagger-ui/index.html"
echo -e "  Health:  http://localhost:8080/actuator/health"
echo -e "  Logs:    tail -f \$PROJECT_DIR/scripts/deployment/logs/application.log"
echo ""

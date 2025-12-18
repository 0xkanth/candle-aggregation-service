#!/bin/bash

################################################################################
# Quick Restart - No Rebuild
# Uses: Existing JAR file (no Maven build)
# Best for: Configuration changes, quick restarts during development
################################################################################

set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BOLD='\033[1m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_DIR"

echo -e "${BOLD}Quick Restart (no rebuild)${NC}"
echo ""

# Step 1: Verify JAR exists
if [ ! -f target/candle-aggregation-service-1.0.0.jar ]; then
    echo -e "${RED}✗ JAR not found. Run ./docker-launch.sh or ./local-launch.sh first${NC}"
    exit 1
fi

# Step 2: CRITICAL - Ensure TimescaleDB is running
echo -e "${YELLOW}Ensuring TimescaleDB is running...${NC}"

if ! command -v docker &> /dev/null; then
    echo -e "${RED}   ✗ Docker is required for TimescaleDB${NC}"
    exit 1
fi

if docker ps | grep -q "timescaledb"; then
    echo -e "${GREEN}   ✓ TimescaleDB running${NC}"
else
    echo -e "${YELLOW}   Starting TimescaleDB...${NC}"
    docker-compose up -d
    sleep 5
    
    # Verify database health
    for i in {1..10}; do
        if docker exec timescaledb pg_isready -U candles_user -d candles_db &>/dev/null; then
            echo -e "${GREEN}   ✓ Database ready${NC}"
            break
        fi
        [ $i -eq 10 ] && { echo -e "${RED}   ✗ Database timeout${NC}"; exit 1; }
        sleep 1
    done
fi
echo ""

# Step 3: Restart service
echo -e "${YELLOW}Restarting service...${NC}"
./stop-service.sh
./start-service.sh

# Step 4: Verify health
sleep 10
HEALTH=$(curl -s http://localhost:8080/actuator/health | jq -r '.status' 2>/dev/null)

if [ "$HEALTH" = "UP" ]; then
    echo -e "${GREEN}✓ Service restarted successfully${NC}"
    echo "  Logs: tail -f logs/application.log"
else
    echo -e "${RED}✗ Service may not be healthy${NC}"
    echo "  Check logs: tail -f logs/application.log"
fi

#!/bin/bash

################################################################################
# Local Development Launch - Lightweight Docker
# Uses: TimescaleDB in Docker (same as production) + Java application
# Best for: Development without full rebuild, faster iteration
################################################################################

set -e

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BOLD='\033[1m'
NC='\033[0m'

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

echo -e "${BOLD}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║              Local Development Launch                         ║${NC}"
echo -e "${BOLD}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Step 1: Ensure TimescaleDB is running and healthy (CRITICAL)
echo -e "${BLUE}[1/3]${NC} ${BOLD}Ensuring TimescaleDB is running...${NC}"

if ! command -v docker &> /dev/null; then
    echo -e "${RED}   ✗ Docker is required for TimescaleDB${NC}"
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

# Step 2: Start application (using existing JAR or build if needed)
echo -e "${BLUE}[2/3]${NC} ${BOLD}Starting application...${NC}"

if [ ! -f target/candle-aggregation-service-1.0.0.jar ]; then
    echo -e "${YELLOW}   Building first...${NC}"
    mvn clean package -DskipTests
fi

./stop-service.sh 2>/dev/null || true
./start-service.sh
echo ""

# Step 3: Verify startup
echo -e "${BLUE}[3/3]${NC} ${BOLD}Verifying startup...${NC}"
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

sleep 3

echo ""
echo -e "${BOLD}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║                    Quick Status Check                         ║${NC}"
echo -e "${BOLD}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""

curl -s http://localhost:8080/actuator/health | jq .
echo ""

# Swagger
if command -v open &> /dev/null; then
    open http://localhost:8080/swagger-ui/index.html
    echo -e "${GREEN}✓ Swagger UI opened${NC}"
fi

echo ""
echo -e "${BOLD}${GREEN}╔════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}${GREEN}║                    🚀 System Ready                             ║${NC}"
echo -e "${BOLD}${GREEN}╚════════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  API:     http://localhost:8080/api/v1/history"
echo -e "  Swagger: http://localhost:8080/swagger-ui/index.html"
echo -e "  Logs:    tail -f logs/application.log"
echo ""

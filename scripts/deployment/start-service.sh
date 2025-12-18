#!/bin/bash

# Ensure we're in project root (2 levels up from scripts/deployment/)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

# Create logs directory
mkdir -p scripts/deployment/logs scripts/deployment/data

# Check if TimescaleDB is running
if command -v docker &> /dev/null; then
    if ! docker ps | grep -q timescaledb; then
        echo "⚠️  WARNING: TimescaleDB container not running"
        echo "   Starting TimescaleDB..."
        docker-compose up -d
        echo "   Waiting 3 seconds for database initialization..."
        sleep 3
    fi
fi

echo "Starting Candle Aggregation Service..."
echo "Using JVM configuration from config/jvm.options"

# Start service with JVM options file (includes all flags)
java @config/jvm.options \
  -jar target/candle-aggregation-service-1.0.0.jar \
  > scripts/deployment/logs/application.log 2>&1 &

PID=$!
echo $PID > scripts/deployment/logs/application.pid
echo "Service started with PID: $PID"
echo "Logs: tail -f $PROJECT_ROOT/scripts/deployment/logs/application.log"
echo ""
echo "Wait ~10 seconds for startup, then test:"
echo "  curl http://localhost:8080/actuator/health"

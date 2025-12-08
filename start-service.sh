#!/bin/bash

# Ensure we're in project root
cd "$(dirname "$0")"

# Create logs directory
mkdir -p logs data

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

# Start service directly from JAR
java \
  -Xmx4g \
  -jar target/candle-aggregation-service-1.0.0.jar \
  > logs/application.log 2>&1 &

PID=$!
echo $PID > logs/application.pid
echo "Service started with PID: $PID"
echo "Logs: tail -f $(pwd)/logs/application.log"
echo ""
echo "Wait ~10 seconds for startup, then test:"
echo "  curl http://localhost:8080/actuator/health"

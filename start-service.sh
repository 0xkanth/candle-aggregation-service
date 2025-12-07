#!/bin/bash

# Ensure we're in project root
cd "$(dirname "$0")"

# Extract JAR if not already done
if [ ! -d "extracted/BOOT-INF" ]; then
    echo "Extracting JAR..."
    mkdir -p extracted
    cd extracted
    jar -xf ../target/candle-aggregation-service-1.0.0.jar
    cd ..
    echo "Extraction complete."
fi

# Create logs directory
mkdir -p logs data

echo "Starting Candle Aggregation Service (extracted mode)..."

# Start service
java \
  -Xmx4g \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.io=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.ref=ALL-UNNAMED \
  --add-exports jdk.unsupported/sun.misc=ALL-UNNAMED \
  --add-exports java.base/sun.nio.ch=ALL-UNNAMED \
  -cp "extracted/BOOT-INF/classes:extracted/BOOT-INF/lib/*" \
  com.fintech.candles.CandleAggregationApplication \
  > logs/application.log 2>&1 &

PID=$!
echo $PID > logs/application.pid
echo "Service started with PID: $PID"
echo "Logs: tail -f $(pwd)/logs/application.log"
echo ""
echo "Wait ~10 seconds for startup, then test:"
echo "  curl http://localhost:8080/actuator/health"

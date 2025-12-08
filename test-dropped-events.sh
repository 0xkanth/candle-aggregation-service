#!/bin/bash

echo "=== Dropped Events Stress Test ==="
echo ""
echo "This test will:"
echo "1. Stop the current service"
echo "2. Reconfigure with a very small buffer (256) to trigger drops"
echo "3. Start the service with high load (200K events/sec)"
echo "4. Wait for events to accumulate"
echo "5. Query the dropped events metrics"
echo ""

# Stop current service
echo "Stopping current service..."
./stop-service.sh 2>/dev/null

# Backup original config
cp src/main/resources/application.yml src/main/resources/application.yml.backup

# Create stress test config with tiny buffer
cat > src/main/resources/application-stress.yml << 'EOF'
candle:
  aggregation:
    disruptor:
      buffer-size: 256          # Very small buffer to trigger drops
      wait-strategy: YIELDING
    late-event-tolerance-ms: 100  # Very short tolerance to trigger late drops
  simulation:
    simple-mode: false
    production-scale: true
    events-per-second: 200000   # 200K events/sec to overwhelm the system
EOF

# Rebuild with stress config
echo ""
echo "Rebuilding with stress test configuration..."
mvn clean package -DskipTests -q

# Remove old extracted files
rm -rf extracted

# Start service with stress profile
echo ""
echo "Starting service with stress configuration..."
export SPRING_PROFILES_ACTIVE=stress
./start-service.sh

# Wait for service to start
echo ""
echo "Waiting for service to start and generate load..."
sleep 12

# Monitor metrics for 10 seconds
echo ""
echo "Monitoring dropped events (checking every 2 seconds for 10 seconds)..."
for i in {1..5}; do
    echo ""
    echo "--- Check $i/5 ---"
    curl -s http://localhost:8080/api/v1/metrics/dropped-events | jq .
    sleep 2
done

# Final check with Prometheus endpoint
echo ""
echo "=== Final Prometheus Metrics ==="
curl -s http://localhost:8080/actuator/prometheus | grep -E "(late_events_dropped|ringbuffer_events_dropped|events_processed|candles_completed)"

# Restore original config
echo ""
echo "Restoring original configuration..."
mv src/main/resources/application.yml.backup src/main/resources/application.yml

echo ""
echo "=== Test Complete ==="
echo "To return to normal operation, run: ./stop-service.sh && mvn clean package -DskipTests && ./start-service.sh"

# Quick Start Guide

## Prerequisites
- Java 21+
- Maven 3.8+

## Running the Service

1. **Build the project:**
   ```bash
   mvn clean package -DskipTests
   ```

2. **Start the service:**
   ```bash
   ./start-service.sh
   ```

3. **Verify it's running:**
   ```bash
   curl http://localhost:8080/actuator/health
   ```

4. **Check performance:**
   ```bash
   ./performance-report.sh
   ```

5. **Monitor real-time throughput:**
   ```bash
   ./monitor-throughput.sh
   ```

6. **Stop the service:**
   ```bash
   ./stop-service.sh
   ```

## Configuration

By default, the service generates ~83K events/sec.

To change the throughput, create `config/application.properties`:
```properties
candle.simulation.events-per-second=100000
```

Then restart the service.

## API Endpoints

- Health: `http://localhost:8080/actuator/health`
- Metrics: `http://localhost:8080/actuator/metrics`
- Current candles: `http://localhost:8080/api/v1/candles?symbol=BTC-USD&interval=S1`
- Historical data: `http://localhost:8080/api/v1/history?symbol=BTC-USD&interval=S1&startTime=<timestamp>&endTime=<timestamp>`

## Performance Scripts

- `./measure-latency.sh` - Measure processing latency
- `./measure-chronicle-map.sh` - Measure storage performance
- `./monitor-throughput.sh` - Real-time throughput monitoring
- `./performance-report.sh` - Comprehensive performance report

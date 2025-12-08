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

- Health: `GET /actuator/health`
- Metrics: `GET /actuator/metrics`
- Prometheus: `GET /actuator/prometheus`
- All symbols: `GET /api/v1/symbols`
- Historical candles: `GET /api/v1/history?symbol=BTCUSD&interval=1s&from=<unix_sec>&to=<unix_sec>`

Example:
```bash
NOW=$(date +%s)
curl "http://localhost:8080/api/v1/history?symbol=BTCUSD&interval=1s&from=$((NOW-30))&to=$NOW" | jq
```

## Scripts

- `./run-all-tests.sh` — Run all tests, generate HTML report
- `./test-service.sh` — Smoke test API, metrics, and data
- `./performance-report.sh` — Full performance summary
- `./monitor-throughput.sh` — Real-time throughput
- `./measure-latency.sh` — Latency metrics
- `./measure-chronicle-map.sh` — Storage performance

# Quick Start Guide

## Prerequisites
- Java 21+
- Maven 3.8+
- Docker & Docker Compose (for TimescaleDB)

## Running the Service

1. **Start TimescaleDB:**
   ```bash
   docker-compose up -d
   ```

2. **Build the project:**
   ```bash
   mvn clean package -DskipTests
   ```

3. **Start the service:**
   ```bash
   ./scripts/deployment/start-service.sh
   ```

3. **Verify it's running:**
   ```bash
   curl http://localhost:8080/actuator/health
   ```

4. **Check performance:**
   ```bash
   ./scripts/performance/performance-report.sh
   ```

5. **Monitor real-time throughput:**
   ```bash
   ./scripts/monitoring/monitor-throughput.sh
   ```

6. **Stop the service:**
   ```bash
   ./scripts/deployment/stop-service.sh
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

- `./scripts/testing/run-all-tests.sh` — Run all tests, generate HTML report
- `./scripts/testing/test-service.sh` — Smoke test API, metrics, and data
- `./scripts/performance/performance-report.sh` — Full performance summary
- `./scripts/monitoring/monitor-throughput.sh` — Real-time throughput
- `./scripts/performance/measure-latency.sh` — Latency metrics
- `./scripts/testing/coverage-report.sh` — Generate test coverage report with JaCoCo

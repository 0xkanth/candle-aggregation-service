# Setup Guide for New Developers

## Prerequisites

Install Java 21 and Maven using SDKMAN (recommended):

```bash
# Install SDKMAN (if not already installed)
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# Install Java 21
sdk install java 21.0.1-tem
sdk use java 21.0.1-tem

# Install Maven 3.9+
sdk install maven 3.9.6

# Verify installations
java -version   # Should show: openjdk version "21.0.1"
mvn -version    # Should show: Apache Maven 3.9.6
```

**Also Required:**
- Docker & Docker Compose (for TimescaleDB/PostgreSQL)

## Quick Start (3 Commands)

```bash
# 1. Start TimescaleDB
docker-compose up -d

# 2. Build and start the service
./setup.sh
./start-service.sh

# 3. Test it works (in another terminal)
./test-service.sh
```

That's it! The service will be running on http://localhost:8080

## Manual Setup (If scripts don't work)

### Step 1: Start TimescaleDB

```bash
docker-compose up -d
```

Verify it's running:
```bash
docker ps | grep timescaledb
psql -h localhost -U candles_user -d candles_db -c "SELECT version();"
```

### Step 2: Clean Build

```bash
cd /path/to/candle-aggregation-service
mvn clean package -DskipTests
```

Expected output:
```
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  2-3 seconds
```

### Step 3: Start Service

```bash
java -jar target/candle-aggregation-service-1.0.0.jar
```

Expected output:
```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::                (v3.2.0)

Started CandleAggregationApplication in 2.5 seconds
Disruptor ring buffer started with 1024 slots
TimescaleDB connected: PostgreSQL 15 with TimescaleDB extension
Market data generator started: 6 instruments @ 100,000 events/sec
Service ready on http://localhost:8080
```

### Step 4: Verify It's Running

Open another terminal:

```bash
# Check health
curl http://localhost:8080/actuator/health

# Get some candle data
NOW=$(date +%s)
FROM=$((NOW - 30))
curl "http://localhost:8080/api/v1/history?symbol=BTCUSD&interval=1s&from=$FROM&to=$NOW" | jq
```

## Common Issues

### Issue: "command not found: java"

**Solution:** Install Java 21 using SDKMAN (see Prerequisites)

### Issue: "BUILD FAILURE" during mvn package

**Solution:** Make sure you're using Java 21:
```bash
java -version  # Should show 21.0.1
sdk use java 21.0.1-tem
```

### Issue: Service won't start - "Connection refused" to PostgreSQL

**Solution:** Ensure TimescaleDB container is running:
```bash
docker ps | grep timescaledb
# If not running:
docker-compose up -d
```

### Issue: "Port 8080 already in use"

**Solution:** Kill existing process:
```bash
lsof -ti:8080 | xargs kill -9
```

### Issue: Empty API results `{"s": "ok", "t": [], ...}`

**Solution:** Your timestamps are too old. Use current time:
```bash
NOW=$(date +%s)
FROM=$((NOW - 30))
curl "http://localhost:8080/api/v1/history?symbol=BTCUSD&interval=1s&from=$FROM&to=$NOW"
```

## What's Running?

Once started, the service:
- Generates mock market data for 6 instruments (BTCUSD, ETHUSD, SOLUSD, EURUSD, GBPUSD, XAUUSD)
- Processes ~100,000 events per second
- Aggregates into candles across 5 timeframes (1s, 5s, 1m, 15m, 1h)
- Stores everything in TimescaleDB (PostgreSQL with time-series optimizations)
- Exposes REST API on http://localhost:8080

**TimescaleDB Benefits:**
- ACID compliance - no data loss on crash
- Automatic time-based partitioning (hypertables)
- Compression for historical data (10-20x space savings)
- SQL query power for complex analytics
- Standard PostgreSQL tooling and ecosystem

## Next Steps

- Read [README.md](README.md) for architecture details
- Check [API examples](#api-examples) below
- Run the test suite: `./test-service.sh`
- Monitor metrics: `curl http://localhost:8080/actuator/metrics | jq`

## API Examples

```bash
# Current timestamp
NOW=$(date +%s)

# Bitcoin last 30 seconds (1s candles)
curl "http://localhost:8080/api/v1/history?symbol=BTCUSD&interval=1s&from=$((NOW-30))&to=$NOW" | jq

# Ethereum last 1 minute (5s candles)
curl "http://localhost:8080/api/v1/history?symbol=ETHUSD&interval=5s&from=$((NOW-60))&to=$NOW" | jq

# Gold last 15 minutes (5m candles)
curl "http://localhost:8080/api/v1/history?symbol=XAUUSD&interval=5m&from=$((NOW-900))&to=$NOW" | jq

# See all available metrics
curl http://localhost:8080/actuator/metrics | jq '.names[]'

# Check how many events processed
curl http://localhost:8080/actuator/metrics/candle.aggregator.events.processed | jq

# Monitor live (refresh every second)
while true; do clear; curl -s http://localhost:8080/actuator/metrics/candle.aggregator.events.processed | jq; sleep 1; done
```

## Available Instruments

- `BTCUSD` - Bitcoin/USD
- `ETHUSD` - Ethereum/USD
- `SOLUSD` - Solana/USD
- `EURUSD` - Euro/USD
- `GBPUSD` - British Pound/USD
- `XAUUSD` - Gold/USD
- `XAGUSD` - Silver/USD
- `SPX500` - S&P 500 Index

## Available Intervals

- `1s` - 1 second
- `5s` - 5 seconds
- `15s` - 15 seconds
- `1m` - 1 minute
- `5m` - 5 minutes
- `15m` - 15 minutes
- `1h` - 1 hour

## Stopping the Service

Press `Ctrl+C` in the terminal where the service is running.

Or kill by port:
```bash
lsof -ti:8080 | xargs kill -9
```

## Development Workflow

```bash
# Make code changes
vim src/main/java/com/fintech/candles/...

# Rebuild and restart
mvn clean package -DskipTests
./start-service.sh

# Or run tests before restart
mvn clean test
./start-service.sh
```

---

**Need help?** Check the troubleshooting section in [README.md](README.md)

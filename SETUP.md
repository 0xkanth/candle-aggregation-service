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

## Quick Start (3 Commands)

```bash
# 1. Build the project
./setup.sh

# 2. Start the service
./start-service.sh

# 3. Test it works (in another terminal)
./test-service.sh
```

That's it! The service will be running on http://localhost:8080

## Manual Setup (If scripts don't work)

### Step 1: Clean Build

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

### Step 2: Extract JAR

```bash
# Remove old extraction if exists
rm -rf extracted

# Create directory and extract
mkdir extracted
cd extracted
jar -xf ../target/candle-aggregation-service-1.0.0.jar
cd ..
```

You should now have an `extracted/` directory with `BOOT-INF/`, `META-INF/`, and `org/` folders.

### Step 3: Start Service

```bash
cd extracted

java \
  --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED \
  --add-exports=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  --add-modules=jdk.compiler \
  -XX:+UseZGC \
  -XX:MaxGCPauseMillis=10 \
  -Xmx4g \
  -XX:MaxDirectMemorySize=2g \
  -cp "BOOT-INF/classes:BOOT-INF/lib/*" \
  com.fintech.candles.CandleAggregationApplication
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
Chronicle Map initialized: 10,000,000 entries, 2GB off-heap
Market data generator started: 8 instruments @ 8,000 events/sec
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

### Issue: Service won't start - "ClassNotFoundException: VanillaGlobalMutableState$$Native"

**Solution:** You must extract the JAR and run from the extracted directory. Chronicle Map cannot work from a Spring Boot layered JAR.

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
- Generates mock market data for 8 instruments (BTCUSD, ETHUSD, SOLUSD, etc.)
- Processes ~8,000 events per second
- Aggregates into candles across 7 timeframes (1s, 5s, 15s, 1m, 5m, 15m, 1h)
- Stores everything in off-heap Chronicle Map (survives restarts)
- Exposes REST API on http://localhost:8080

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

# Rebuild
mvn clean package -DskipTests

# Re-extract
rm -rf extracted && mkdir extracted && cd extracted
jar -xf ../target/candle-aggregation-service-1.0.0.jar
cd ..

# Restart
./start-service.sh
```

---

**Need help?** Check the troubleshooting section in [README.md](README.md)

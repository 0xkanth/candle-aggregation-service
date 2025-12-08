# Candle Aggregation Service

Real-time OHLCV candle aggregation service processing 100K+ events/sec with sub-millisecond latency.

## Overview

High-performance candle aggregation service processing 100K+ events/sec with <50μs p99 latency.

**Tech Stack:**
- Java 21, Spring Boot 3.2
- LMAX Disruptor 4.0 (lock-free pipeline)
- Chronicle Map 3.25 (off-heap storage)

**Key Features:**
- Multi-interval aggregation (1s, 5s, 1m, 15m, 1h)
- Late event handling (configurable tolerance)
- Sub-microsecond persistence (Chronicle Map)
- TradingView-compatible REST API
- Production metrics (Prometheus)

## Quick Start

> For detailed setup instructions, see [QUICKSTART.md](QUICKSTART.md)

### Option 1: Docker (Recommended for Production & Reviewers)

```bash
# Build and start containers (detached)
docker-compose up -d --build

# To rebuild without cache (optional)
docker-compose build --no-cache

# To stop containers
docker-compose down

# Test API
NOW=$(date +%s)
curl "http://localhost:8080/api/v1/history?symbol=BTCUSD&interval=1s&from=$((NOW-30))&to=$NOW" | jq
```

### Option 2: Local Development

```bash
# Automated setup
./setup.sh

# Start service
./start-service.sh

# Or manual build and run:
mvn clean package -DskipTests

# Extract JAR (required for Chronicle Map)
mkdir extracted && cd extracted
jar -xf ../target/candle-aggregation-service-1.0.0.jar

# Run with required JVM flags
java -Xmx4g -XX:+UseZGC -XX:MaxGCPauseMillis=10 -XX:MaxDirectMemorySize=2g \
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
  -cp BOOT-INF/classes:BOOT-INF/lib/* \
  com.fintech.candles.CandleAggregationApplication
```

**Verify:**
```bash
NOW=$(date +%s)
curl "http://localhost:8080/api/v1/history?symbol=BTCUSD&interval=1s&from=$((NOW-30))&to=$NOW" | jq
```

## Architecture

**Design Philosophy:** Lock-free concurrency, zero-GC persistence, mechanical sympathy.

> **Deep dive:** [CANDLE_AGGREGATION_EXPLAINED.md](./CANDLE_AGGREGATION_EXPLAINED.md) | [DATA_GENERATOR_COMPARISON.md](./DATA_GENERATOR_COMPARISON.md)

### System Design

```mermaid
graph TB
    subgraph "Event Ingestion"
        EX[Exchange Feed]
        SIM[Market Simulator]
        PUB[DisruptorEventPublisher]
    end
    
    subgraph "LMAX Disruptor"
        RB[Ring Buffer<br/>1024 slots]
        EH[Event Handler]
    end
    
    subgraph "Aggregation Layer"
        AGG[CandleAggregator]
        TWM[TimeWindowManager]
        AC[Active Candles<br/>ConcurrentHashMap]
    end
    
    subgraph "Storage Layer"
        CM[Chronicle Map<br/>Off-heap]
        REPO[CandleRepository]
    end
    
    subgraph "API Layer"
        REST[REST Controller]
        SVC[CandleService]
    end
    
    EX --> PUB
    SIM --> PUB
    PUB --> RB
    RB --> EH
    EH --> AGG
    AGG --> TWM
    AGG --> AC
    AC --> REPO
    REPO --> CM
    REST --> SVC
    SVC --> REPO
    
    style RB fill:#ff6b6b
    style CM fill:#4ecdc4
    style AGG fill:#ffe66d
```

### Key Components

| Component | Technology | Purpose |
|-----------|------------|----------|
| DisruptorEventPublisher | LMAX Disruptor | Lock-free event pipeline |
| CandleAggregator | AtomicReference CAS | Lock-free OHLC updates |
| ChronicleMapRepository | Chronicle Map | Off-heap persistence |
| REST Controller | Spring WebMVC | Query API |

### Event Flow

```mermaid
sequenceDiagram
    participant Exchange
    participant Disruptor
    participant Aggregator
    participant Storage
    participant API

    Exchange->>Disruptor: BidAskEvent(BTC, 50000, t)
    Disruptor->>Aggregator: onEvent()
    
    Aggregator->>Aggregator: Calculate window start
    alt Same window
        Aggregator->>Aggregator: Update OHLC (CAS)
    else New window
        Aggregator->>Storage: Persist old candle
        Aggregator->>Aggregator: Create new candle
    else Late event (within tolerance)
        Aggregator->>Storage: Reopen old candle
        Aggregator->>Storage: Update and save
    else Late event (beyond tolerance)
        Aggregator->>Aggregator: Drop event
    end
    
    API->>Storage: Query candles
    Storage-->>API: Return OHLC array
```

### Multi-Interval Processing

```mermaid
flowchart LR
    Event[BidAskEvent<br/>10:37:23.456] --> AGG[Aggregator]
    
    AGG --> S1[S1: 10:37:23.000<br/>UPDATE]
    AGG --> S5[S5: 10:37:20.000<br/>UPDATE]
    AGG --> M1[M1: 10:37:00.000<br/>UPDATE]
    AGG --> M15[M15: 10:30:00.000<br/>UPDATE]
    AGG --> H1[H1: 10:00:00.000<br/>UPDATE]
    
    S1 --> CM[(Chronicle Map)]
    S5 --> CM
    M1 --> CM
    M15 --> CM
    H1 --> CM
    
    style Event fill:#95e1d3
    style CM fill:#4ecdc4
```

### Window Alignment Algorithm

```mermaid
flowchart TD
    Start[Event timestamp:<br/>10:37:23.456] --> Div[Divide by interval<br/>1733529443456 / 60000 = 28892157.391]
    Div --> Floor[Integer division<br/>28892157]
    Floor --> Mult[Multiply back<br/>28892157 × 60000 = 1733529420000]
    Mult --> Result[Window start:<br/>10:37:00.000]
    
    style Start fill:#95e1d3
    style Result fill:#ffe66d
```

### Late Event Decision Tree

```mermaid
flowchart TD
    Event[Event arrives] --> Calc[Calculate event window]
    Calc --> Compare{Compare windows}
    
    Compare -->|event == current| Same[Same Window:<br/>Update OHLC]
    Compare -->|event > current| New[New Window:<br/>Persist old<br/>Create new]
    Compare -->|event < current| Late[Late Event]
    
    Late --> Lag{Check lag}
    Lag -->|lag ≤ 30s| Accept[Accept:<br/>Reopen candle<br/>Update OHLC]
    Lag -->|lag > 30s| Drop[Drop:<br/>Increment counter<br/>Log warning]
    
    style Same fill:#51cf66
    style New fill:#4dabf7
    style Accept fill:#ffd43b
    style Drop fill:#ff6b6b
```

### Data Model

```mermaid
classDiagram
    class BidAskEvent {
        +String symbol
        +double bid
        +double ask
        +long timestamp
        +midPrice() double
    }
    
    class Candle {
        +long time
        +double open
        +double high
        +double low
        +double close
        +long volume
    }
    
    class Interval {
        <<enumeration>>
        S1(1000)
        S5(5000)
        M1(60000)
        M15(900000)
        H1(3600000)
        +alignTimestamp(long) long
    }
    
    class MutableCandle {
        -long windowStart
        -double open
        -double high
        -double low
        -double close
        -long volume
        +update(double price)
        +toCandle() Candle
    }
    
    BidAskEvent --> Interval: processed for each
    MutableCandle --> Candle: converts to
```

### Candle Class Architecture

The system uses **two distinct candle representations** optimized for different purposes:

#### 1. **Candle** (Immutable Record)
- **Location:** `src/main/java/com/fintech/candles/domain/Candle.java`
- **Type:** Java record (immutable by design)
- **Purpose:** Final representation for persistence and API responses
- **Key Features:**
  - Thread-safe (all fields final)
  - Serializable (required by Chronicle Map)
  - OHLC validation in compact constructor
  - Factory method: `Candle.of(time, price)`
  - Helper methods: `isValid()`, `containsTime()`

#### 2. **MutableCandle** (Private Inner Class)
- **Location:** `src/main/java/com/fintech/candles/aggregation/CandleAggregator.java` (line 558)
- **Type:** Private static inner class
- **Purpose:** Working copy during active aggregation (performance optimization)
- **Key Features:**
  - Mutable fields (`high`, `low`, `close`, `volume`) for in-place updates
  - `update(double price)` method (line 621) - zero-allocation mutations
  - `toImmutableCandle()` method (line 642) - conversion to `Candle` record
  - Never exposed outside `CandleAggregator`

**Note:** There is no separate "ImmutableCandle" class - the `Candle` record serves as the immutable representation.

#### Why Two Classes?

**Performance:** Avoid object allocation on the hot path
```java
// ❌ BAD: Creates new object for every price update
candle = new Candle(time, candle.open, newHigh, newLow, newClose, newVolume);

// ✅ GOOD: Mutates existing object in-place
mutableCandle.update(price);  // Zero allocation
```

**Memory Safety:** Mutability confined to aggregator
- `MutableCandle` exists only within `activeCandles` map
- Wrapped in `AtomicReference` for thread-safe updates
- Converted to immutable `Candle` before storage/API responses

#### Transformation Flow

```mermaid
graph LR
    A[BidAskEvent] -->|price| B[MutableCandle]
    B -->|update price| B
    B -->|window closes| C[toImmutableCandle]
    C --> D[Candle record]
    D --> E[Chronicle Map]
    D --> F[API Response]
    
    style B fill:#ff6b6b,color:#fff
    style D fill:#4ecdc4,color:#fff
```

**Lifecycle:** Create → Aggregate (in-place) → Convert → Persist → Query

**Concurrency:** AtomicReference + CAS inside ConcurrentHashMap.compute() lock

#### State Fields

| Field | Type | Purpose |
|-------|------|----------|
| `activeCandles` | `ConcurrentHashMap<String, AtomicReference<MutableCandle>>` | In-memory active candles (~1000 entries) |
| `eventsProcessed` | `AtomicLong` | Total events counter (Prometheus) |
| `candlesCompleted` | `AtomicLong` | Persisted candles counter (Prometheus) |
| `lateEventsDropped` | `AtomicLong` | Late events counter (Prometheus) |

### Complete State Management

Two-tier architecture: in-memory (hot path) + persistent (cold storage)

**Tier 1: In-Memory (CandleAggregator)**

**Location:** `src/main/java/com/fintech/candles/aggregation/CandleAggregator.java`

| Field | Type | Purpose | Thread Safety | Lifecycle |
|-------|------|---------|---------------|-----------|
| `activeCandles` | `ConcurrentHashMap<String, AtomicReference<MutableCandle>>` | Current candles being built | ConcurrentHashMap locks + CAS | Created on first event in window, removed after persistence |
| `eventsProcessed` | `AtomicLong` | Total events processed | Lock-free CAS increment | Monotonically increasing counter |
| `candlesCompleted` | `AtomicLong` | Total candles persisted | Lock-free CAS increment | Monotonically increasing counter |
| `lateEventsDropped` | `AtomicLong` | Late events beyond tolerance | Lock-free CAS increment | Monotonically increasing counter |

**Key:** `activeCandles` key = `"SYMBOL-INTERVAL"` (e.g., `"BTCUSD-M1"`)

**Tier 2: Persistent (Chronicle Map)**

**Location:** `src/main/java/com/fintech/candles/storage/ChronicleMapCandleRepository.java`

| Field | Type | Purpose | Thread Safety | Persistence |
|-------|------|---------|---------------|-------------|
| `candleMap` | `ChronicleMap<String, Candle>` | Off-heap persistent candle storage | Thread-safe (Chronicle Map internal locks) | Memory-mapped file (`./data/candles.dat`) |
| `writeCounter` | `AtomicLong` | Total writes to Chronicle Map | Lock-free CAS increment | In-memory only (reset on restart) |
| `readCounter` | `AtomicLong` | Total reads from Chronicle Map | Lock-free CAS increment | In-memory only (reset on restart) |

**Key:** `candleMap` key = `"SYMBOL-INTERVAL-TIMESTAMP"` (e.g., `"BTCUSD-M1-1733529420000"`)

#### Flow Diagram

```mermaid
graph TB
    subgraph "Event Processing Flow"
        E[BidAskEvent] --> AG[CandleAggregator.processEvent]
    end
    
    subgraph "In-Memory State (Tier 1)"
        AG --> EP[eventsProcessed++]
        AG --> AC{activeCandles.get<br/>SYMBOL-INTERVAL}
        
        AC -->|Not Found| CREATE[Create MutableCandle<br/>AtomicReference]
        AC -->|Found| UPDATE[updateAndGet<br/>CAS update OHLC]
        
        CREATE --> WRAP[Wrap in AtomicReference]
        WRAP --> PUT[activeCandles.put]
        UPDATE --> CHECK{New Window?}
    end
    
    subgraph "Persistence (Tier 2)"
        CHECK -->|Yes| PERSIST[persistCandle]
        PERSIST --> CONVERT[toImmutableCandle<br/>MutableCandle → Candle]
        CONVERT --> SAVE[repository.save]
        SAVE --> CM[candleMap.put<br/>Off-heap storage]
        CM --> CC[candlesCompleted++]
        CC --> REMOVE[activeCandles.remove<br/>Old window]
    end
    
    subgraph "Late Event Handling"
        CHECK -->|Late Event| LATE{Within<br/>Tolerance?}
        LATE -->|No| DROP[lateEventsDropped++]
        LATE -->|Yes| FIND[repository.findByExactTime]
        FIND --> UPDCM[Update Candle in Chronicle Map]
    end
    
    subgraph "API Query Flow"
        API[GET /api/v1/history] --> QUERY[repository.findByRange]
        QUERY --> SCAN[candleMap scan by prefix]
        SCAN --> RC[readCounter++]
        SCAN --> RESP[Return Candle list]
    end
    
    style AC fill:#ff6b6b,color:#fff
    style CM fill:#4ecdc4,color:#fff
    style EP fill:#ffd93d
    style CC fill:#ffd93d
    style RC fill:#ffd93d
```

#### State Update Operations

**1. Normal Event (Current Window)**
```java
// Step 1: Increment event counter (lock-free)
eventsProcessed.incrementAndGet();

// Step 2: Get or create active candle (ConcurrentHashMap.compute)
String key = "BTCUSD-M1";  // Symbol + Interval
activeCandles.compute(key, (k, atomicRef) -> {
    if (atomicRef == null) {
        // First event for this window
        MutableCandle newCandle = new MutableCandle(windowStart, price, ...);
        return new AtomicReference<>(newCandle);
    } else {
        // Update existing candle (CAS inside updateAndGet)
        atomicRef.updateAndGet(candle -> {
            candle.update(price);  // Mutate in-place
            return candle;         // Return same reference
        });
        return atomicRef;
    }
});

// Step 3: Check if window changed (time-based)
if (newWindowDetected) {
    persistCandle(oldMutableCandle);  // Save to Chronicle Map
    activeCandles.remove(oldKey);      // Clean up old window
    candlesCompleted.incrementAndGet(); // Increment counter
}
```

**2. Late Event (Previous Window)**
```java
// Step 1: Check tolerance
if (eventAge > tolerance) {
    lateEventsDropped.incrementAndGet();
    return;  // Drop event
}

// Step 2: Find existing candle in Chronicle Map
repository.findByExactTime(symbol, interval, windowStart).ifPresent(existing -> {
    // Step 3: Create updated immutable Candle
    Candle updated = new Candle(
        existing.time(),
        existing.open(),
        Math.max(existing.high(), price),
        Math.min(existing.low(), price),
        price,
        existing.volume() + 1
    );
    
    // Step 4: Overwrite in Chronicle Map
    repository.save(symbol, interval, updated);
    writeCounter.incrementAndGet();
});
```

**3. API Query (Read Path)**
```java
// Step 1: Construct key prefix
String prefix = "BTCUSD-M1-";

// Step 2: Scan Chronicle Map (off-heap iteration)
List<Candle> results = new ArrayList<>();
for (Entry<String, Candle> entry : candleMap.entrySet()) {
    if (entry.getKey().startsWith(prefix)) {
        Candle candle = entry.getValue();
        if (candle.time() >= fromTime && candle.time() <= toTime) {
            results.add(candle);
        }
    }
}

// Step 3: Increment counter
readCounter.incrementAndGet();

// Step 4: Sort and return
results.sort(Comparator.comparingLong(Candle::time));
return results;
```

#### Synchronization Guarantees

| Operation | Synchronization Mechanism | Consistency Guarantee |
|-----------|--------------------------|----------------------|
| `activeCandles.compute()` | ConcurrentHashMap internal lock | Atomic per key |
| `AtomicReference.updateAndGet()` | CAS loop | Linearizable |
| `eventsProcessed.incrementAndGet()` | CAS (lock-free) | Eventually consistent |
| `candleMap.put()` | Chronicle Map segment lock | Per-key atomic write |
| `candleMap.get()` | Lock-free read (memory-mapped) | Read committed |

**Key Insight:** 
- `activeCandles`: Hot path (100K ops/sec), per-key locks
- `candleMap`: Cold path (candles/sec), off-heap, survives restart
- Counters: Lock-free CAS, metrics only

#### Memory Layout

```
JVM Heap (4 GB)
├── CandleAggregator instance (~1 KB)
│   ├── activeCandles: ConcurrentHashMap (~100 KB)
│   │   └── ~1000 entries × (String key + AtomicReference + MutableCandle)
│   ├── eventsProcessed: AtomicLong (8 bytes)
│   ├── candlesCompleted: AtomicLong (8 bytes)
│   └── lateEventsDropped: AtomicLong (8 bytes)
│
├── ChronicleMapCandleRepository instance (~1 KB)
│   ├── writeCounter: AtomicLong (8 bytes)
│   └── readCounter: AtomicLong (8 bytes)

Off-Heap Memory (2 GB)
└── candleMap: ChronicleMap (memory-mapped file)
    └── ./data/candles.dat (up to 10M entries × ~200 bytes)
```

**Design Rationale:**
- Separation: Active (mutable, fast) vs Completed (immutable, persistent)
- Zero GC: Chronicle Map off-heap = no GC pauses
- Fast queries: Memory-mapped = OS page cache = <5μs
- Crash recovery: Chronicle Map auto-recovers
- Bounded memory: ~1000 active candles only

### Storage Schema

Chronicle Map key format:
```
"SYMBOL-INTERVAL-TIMESTAMP"

Examples:
"BTCUSD-S1-1733529443000"  → 1-second candle
"ETHUSD-M1-1733529420000"  → 1-minute candle
"XAUUSD-H1-1733529600000"  → 1-hour candle
```

### Thread Model

```mermaid
graph TB
    subgraph "Single Producer"
        MP[Market Data Thread]
    end
    
    subgraph "Disruptor Pipeline"
        MP --> RB[Ring Buffer]
        RB --> EH[Event Handler Thread]
    end
    
    subgraph "Single Consumer"
        EH --> AGG[Aggregator]
        AGG --> CM[Chronicle Map<br/>Memory-mapped I/O]
    end
    
    subgraph "Multi-threaded API"
        VT1[Virtual Thread 1]
        VT2[Virtual Thread 2]
        VTN[Virtual Thread N]
    end
    
    VT1 --> CM
    VT2 --> CM
    VTN --> CM
    
    style RB fill:#ff6b6b
    style CM fill:#4ecdc4
```

**Why:**
- Single producer/consumer = No locks
- Virtual threads = 10K+ concurrent API requests
- Memory-mapped I/O = Zero-copy reads

## API Reference

**Endpoint:** `GET /api/v1/history`

| Parameter | Type | Required | Values |
|-----------|------|----------|--------|
| `symbol` | String | Yes | BTCUSD, ETHUSD, SOLUSD, EURUSD, GBPUSD, XAUUSD |
| `interval` | String | Yes | 1s, 5s, 1m, 15m, 1h |
| `from` | Long | Yes | Unix timestamp (seconds) |
| `to` | Long | Yes | Unix timestamp (seconds) |

**Examples:**
```bash
# Last 1 minute of BTC 1-second candles
NOW=$(date +%s)
curl "http://localhost:8080/api/v1/history?symbol=BTCUSD&interval=1s&from=$((NOW-60))&to=$NOW"

# Last hour of ETH 1-minute candles
curl "http://localhost:8080/api/v1/history?symbol=ETHUSD&interval=1m&from=$((NOW-3600))&to=$NOW"

# Last day of Gold 15-minute candles
curl "http://localhost:8080/api/v1/history?symbol=XAUUSD&interval=15m&from=$((NOW-86400))&to=$NOW"
```

Response format (TradingView-compatible):
```json
{
  "s": "ok",           // status
  "t": [1733529600],   // timestamps
  "o": [50000.0],      // opens
  "h": [50100.0],      // highs
  "l": [49900.0],      // lows
  "c": [50050.0],      // closes
  "v": [1234]          // volumes
}
```

## Monitoring

```bash
# Health check
curl http://localhost:8080/actuator/health

# Key metrics
curl http://localhost:8080/actuator/metrics/candle.aggregator.events.processed
curl http://localhost:8080/actuator/metrics/candle.aggregator.late.events.dropped

# Live throughput
watch -n 1 'curl -s http://localhost:8080/actuator/metrics/candle.aggregator.events.processed | jq'
```

## Configuration

> **Generator options:** [DATA_GENERATOR_COMPARISON.md](./DATA_GENERATOR_COMPARISON.md#switching-between-generators)

```properties
# Chronicle Map
candle.storage.path=./data/candles.dat
candle.storage.entries=10000000

# Disruptor
disruptor.buffer.size=1024

# Late events
candle.late.event.tolerance.seconds=5

# Market data generators
candle.simulation.simple-mode=false           # MarketDataSimulator (disabled)
candle.simulation.production-scale=true       # ProductionScaleDataGenerator (enabled)
candle.simulation.events-per-second=100000    # Target throughput
candle.simulation.symbols=BTCUSD,ETHUSD,SOLUSD,EURUSD,GBPUSD,XAUUSD
```

## Performance

> **Benchmarking guide:** [PERFORMANCE_BENCHMARKING.md](./PERFORMANCE_BENCHMARKING.md)

| Metric | Value |
|--------|-------|
| Event throughput | 100K events/sec |
| Latency (p99) | < 50 μs |
| Chronicle Map read | < 5 μs |
| Chronicle Map write | < 20 μs |
| Memory footprint | 4 GB heap + 2 GB off-heap |

## Testing

### Run All Tests with Coverage Report

```bash
./run-all-tests.sh
```

Generates professional HTML report with:
- Test results (327 tests: unit + integration)
- Code coverage (line + branch)
- Execution time
- Visual dashboard

**Report locations:**
- Summary: `target/test-reports/test-summary.html` (auto-opens)
- Coverage: `target/site/jacoco/index.html`

### Manual Testing

```bash
# Unit + integration tests
mvn test

# Specific test suite
mvn test -Dtest=CandleAggregatorTest

# With coverage
mvn test jacoco:report
open target/site/jacoco/index.html
```

## Assignment Requirements → Implementation

### 1. Event Ingestion & Processing

**Implementation:**
- `BidAskEvent` record: `src/main/java/com/fintech/candles/domain/BidAskEvent.java`
- LMAX Disruptor: `src/main/java/com/fintech/candles/ingestion/DisruptorEventPublisher.java`
- Aggregator: `src/main/java/com/fintech/candles/aggregation/CandleAggregator.java`

**Verify:** `mvn test -Dtest=CandleAggregatorTest#testMultipleEventsInSameWindow`

---

### 2. Multi-Interval Candles

**Implementation:**
- Interval enum: `src/main/java/com/fintech/candles/domain/Interval.java`
- Single-pass processing: `CandleAggregator.processEvent()` loops through all intervals
- Epoch alignment: `Interval.alignTimestamp()` ensures consistent windows

**Verify:** `mvn test -Dtest=CandleAggregatorTest#testProcessesAllIntervals`

---

### 3. Late Event Handling

**Implementation:**
- Detection: `src/main/java/com/fintech/candles/util/TimeWindowManager.java`
- Config: `candle.late.event.tolerance.seconds=5` in `application.properties`
- Handler: `CandleAggregator.handleLateEvent()`

**Verify:** 
```bash
mvn test -Dtest=CandleAggregatorTest#testLateEventWithinTolerance
mvn test -Dtest=CucumberTestRunner -Dcucumber.filter.tags="@late-events"
```

---

### 4. Persistent Storage

**Implementation:**
- Chronicle Map: `src/main/java/com/fintech/candles/storage/ChronicleMapCandleRepository.java`
- Key format: `"SYMBOL-INTERVAL-TIMESTAMP"`
- Performance: <5μs reads, <20μs writes

**Verify:** `mvn test -Dtest=ChronicleMapCandleRepositoryTest`

---

### 5. REST Query API

**Implementation:**
- Endpoint: `GET /api/v1/history` in `src/main/java/com/fintech/candles/api/HistoryController.java`
- TradingView-compatible JSON format
- Params: symbol, interval, from, to (Unix timestamps)

**Verify:** `mvn test -Dtest=HistoryControllerTest`

---

### 6. Monitoring & Metrics

**Implementation:**
- Micrometer + Prometheus: `/actuator/metrics`, `/actuator/health`
- Counters: `events.processed`, `candles.completed`, `late.events.dropped`
- Latency: `event.processing.time` histogram

**Verify:** `curl http://localhost:8080/actuator/health`

## Design Decisions

**Why LMAX Disruptor?**  
Lock-free ring buffer = ~1μs latency vs ~100μs with ArrayBlockingQueue.

**Why Chronicle Map?**  
Off-heap + memory-mapped = zero GC + sub-microsecond access. No Redis network overhead.

**Why Lock-Free CAS?**  
AtomicReference compare-and-swap = no thread contention = scales linearly with cores.

**Why Extract JAR?**  
Chronicle Map needs compiler API. Spring Boot's layered JAR breaks classpath. Extract = flat classpath.

## Troubleshooting

**Service won't start:**
```bash
java -version  # Must be Java 21
lsof -i :8080  # Check port availability
```

**Chronicle Map errors:**
```bash
ls BOOT-INF/classes/com/fintech/candles  # Ensure JAR extracted
ps aux | grep add-opens  # Check JVM flags
rm -f ./data/candles.dat  # Delete corrupt file
```

**No data in queries:**
```bash
# Check simulator is running
curl http://localhost:8080/actuator/metrics/candle.aggregator.events.processed

# Use current timestamps
NOW=$(date +%s)
curl "http://localhost:8080/api/v1/history?symbol=BTCUSD&interval=1s&from=$((NOW-30))&to=$NOW"
```

---

## 📚 Additional Documentation

- **[Quickstart](QUICKSTART.md)** - Automated setup, environment configuration, troubleshooting
- **[Candle Aggregation explainer](./CANDLE_AGGREGATION_EXPLAINED.md)** - Visual examples, algorithm walkthrough, real-world scenarios
- **[Data Generators and Samplers](./DATA_GENERATOR_COMPARISON.md)** - Market data generators explained, configuration guide
- **[Performance Benchmarking](./PERFORMANCE_BENCHMARKING.md)** - Benchmarking instructions and metrics
- **[Hybrid Architecture Overview](./HYBRID_ARCHITECTURE.md)** - Hybrid Architecture Proposal Overview

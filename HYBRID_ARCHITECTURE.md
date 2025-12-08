
# Hybrid Candle Aggregation Architecture

## Overview
This architecture combines Kafka, LMAX Disruptor, Chronicle Map, and TimescaleDB for scalable, low-latency, and durable candle aggregation.

**Storage Strategy:**
- **Chronicle Map (Hot DB):** Stores last 24 hours of candles in off-heap memory for ultra-fast access and recovery.
- **TimescaleDB (Master DB):** Stores all historical candles for analytics and long-term queries.
- **Recovery:** On restart, Chronicle Map is memory-mapped from disk, restoring the exact state at crash/shutdown.

## Architecture Diagram
```mermaid
graph TD
	subgraph "Data Sources"
		FEED1[Exchange Feed 1]
		FEED2[Exchange Feed 2]
		FEEDN[Other Feeds]
	end
	subgraph "Ingestion Layer"
		KAFKA[Kafka]
	end
	subgraph "Processing Layer"
		CONSUMER[Kafka Consumer]
		DISRUPTOR[Disruptor Ring Buffer]
		AGG[Candle Aggregator]
	end
	subgraph "Persistence Layer"
		CM[Chronicle Map<br/>Hot DB: 24h]
		TSDB[TimescaleDB<br/>Master DB: All Time]
	end
	subgraph "API Layer"
		API[REST API]
	end
	FEED1 --> KAFKA
	FEED2 --> KAFKA
	FEEDN --> KAFKA
	KAFKA --> CONSUMER
	CONSUMER --> DISRUPTOR
	DISRUPTOR --> AGG
	AGG --> CM
	AGG --> TSDB
	CM --> API
	TSDB --> API
	style KAFKA fill:#ff6b6b
	style DISRUPTOR fill:#ffe66d
	style CM fill:#4ecdc4
	style TSDB fill:#48bfe3
```

## Event Flow
```mermaid
sequenceDiagram
	participant Feed
	participant Kafka
	participant Consumer
	participant Disruptor
	participant Aggregator
	participant ChronicleMap
	participant TimescaleDB
	participant API

	Feed->>Kafka: Publish market event
	Kafka->>Consumer: Deliver event
	Consumer->>Disruptor: Push to ring buffer
	Disruptor->>Aggregator: Process event
	Aggregator->>ChronicleMap: Persist candle (hot, 24h)
	Aggregator->>TimescaleDB: Batch persist (async, all time)
	API->>ChronicleMap: Query recent candles (fast)
	API->>TimescaleDB: Query historical candles (deep)
```

## Improvements Over Traditional Approaches
- **Multi-source support:** Plug in new feeds via Kafka.
- **No latency penalty:** Disruptor and Chronicle Map keep the hot path in-memory/off-heap. TimescaleDB writes are batched and async, so they never block event processing.
- **Durability:** Chronicle Map is memory-mapped to disk and survives restarts with instant recovery. TimescaleDB provides long-term persistence.
- **Crash recovery:** On restart, Chronicle Map reloads the exact state (last 24h of candles) from disk with zero rebuild time.
- **Scalability:** Kafka enables horizontal scaling and high availability.
- **Extensibility:** Add new sources, consumers, analytics easily.

## Data Lifecycle
1. **Real-time aggregation:** Events processed via Disruptor, candles updated in Chronicle Map (hot path, <50μs).
2. **Hot storage:** Chronicle Map stores last 24 hours of candles in off-heap memory for fast queries and crash recovery.
3. **Cold storage:** Candles older than 24 hours are asynchronously batched to TimescaleDB for analytics and long-term retention.
4. **Restart/recovery:** Chronicle Map is memory-mapped, so on restart, all candles from the last 24h are instantly available without rebuild.

## Use Cases
- Real-time candle aggregation from multiple exchanges.
- High-frequency trading analytics.
- Historical data queries and reporting.

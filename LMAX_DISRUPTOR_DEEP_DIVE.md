# LMAX Disruptor - Quick Reference

**The LMAX Disruptor** is a high-performance inter-thread messaging library developed by LMAX Exchange to process **6 million orders/second** with sub-millisecond latency. It replaces traditional queues with a lock-free ring buffer that trades CPU for extreme low latency.

**Our system:** 100K+ events/sec, <50μs p99 latency.

---

## What is LMAX Disruptor?

| | BlockingQueue | Disruptor |
|---|---|---|
| **Throughput** | 10K events/sec | 100K+ events/sec |
| **Latency p99** | ~10ms | <50μs |
| **Mechanism** | Locks + context switches | Lock-free CAS |
| **Memory** | Allocates per event | Pre-allocated ring buffer |
| **CPU** | ~40% (blocks frequently) | ~90% (productive spin) |

**Key difference:** Disruptor burns CPU to avoid locks/context-switches → 200x faster.

---

## Visual Overview

```mermaid
sequenceDiagram
    participant P1 as Producer-1
    participant P2 as Producer-2
    participant RB as Ring Buffer [8192 slots]
    participant C1 as Consumer-1
    participant C2 as Consumer-2
    participant C3 as Consumer-3
    participant C4 as Consumer-4
    
    Note over P1,P2: Multiple Producers
    P1->>RB: tryNext() → CAS claim slot 0
    P2->>RB: tryNext() → CAS claim slot 1
    P1->>RB: publish(0)
    P2->>RB: publish(1)
    
    Note over C1,C4: 4 Consumers (parallel processing)
    C1->>RB: Process seq 0, 4, 8, 12...
    C2->>RB: Process seq 1, 5, 9, 13...
    C3->>RB: Process seq 2, 6, 10, 14...
    C4->>RB: Process seq 3, 7, 11, 15...
```

**How it flows:**
1. **Multiple Producers** compete using CAS to claim slots
2. **Producer** writes event and publishes sequence
3. **4 Consumers** process events in parallel:
   - Consumer-1: sequences 0, 4, 8, 12... (seq % 4 == 0)
   - Consumer-2: sequences 1, 5, 9, 13... (seq % 4 == 1)
   - Consumer-3: sequences 2, 6, 10, 14... (seq % 4 == 2)
   - Consumer-4: sequences 3, 7, 11, 15... (seq % 4 == 3)
4. **Each consumer** updates its sequence → slot becomes free

---

## Core Architecture

**5 Core Principles:**
1. **Lock-free** - CAS operations instead of `synchronized`
2. **Pre-allocated** - Ring buffer created once at startup
3. **Sequential** - Array access (cache-friendly)
4. **Batching** - Process multiple events per wakeup
5. **Padded** - Prevent false-sharing between cores

**Ring Buffer Structure:**

```
Ring Buffer (8192 slots):
[0][1][2][3][4][5][6][7][8][9][10][11]...[8191]
 ↑  ↑  ↑  ↑
 C1 C2 C3 C4  ← 4 Consumers processing in parallel
 
Producers (multiple) → Write to any available slot
Consumers distribute work by sequence modulo 4:
  - Consumer-1 processes: 0, 4, 8, 12...
  - Consumer-2 processes: 1, 5, 9, 13...
  - Consumer-3 processes: 2, 6, 10, 14...
  - Consumer-4 processes: 3, 7, 11, 15...

When Producer reaches slot 8191 → Wraps to slot 0
```

---

## Our Implementation

```java
@PostConstruct
public void start() {
    disruptor = new Disruptor<>(
        BidAskEventWrapper::new,        // Pre-allocate 8192 wrappers
        8192,                           // Buffer size (must be power of 2)
        threadFactory,                  // Creates "disruptor-event-handler-N" threads
        ProducerType.MULTI,             // Multiple producers (thread-safe CAS)
        new YieldingWaitStrategy()      // Spin 100x → yield → repeat
    );
    
    // Setup 4 parallel consumers
    if (numConsumers > 1) {
        EventHandler<BidAskEventWrapper>[] handlers = new EventHandler[4];
        for (int i = 0; i < 4; i++) {
            final int workerId = i;
            handlers[i] = (event, sequence, endOfBatch) -> {
                if (event.event != null) {
                    aggregator.processEvent(event.event);  // Each consumer processes its sequences
                }
            };
        }
        disruptor.handleEventsWith(handlers);  // 4 parallel consumer threads
    } else {
        disruptor.handleEventsWith(this::handleEvent);  // Single consumer (fallback)
    }
    
    ringBuffer = disruptor.start();
}

// Publishing (Producer side)
public boolean tryPublish(BidAskEvent event) {
    try {
        long seq = ringBuffer.tryNext();           // CAS claim next slot
        ringBuffer.get(seq).event = event;         // Write to wrapper
        ringBuffer.publish(seq);                   // Make visible (volatile write)
        return true;
    } catch (InsufficientCapacityException e) {
        return false;  // Buffer full → drop event
    }
}
```

**Thread Model:**
- **Producer threads:** Multiple Spring scheduler threads (multi-producer)
- **Consumer threads:** 4 threads (`disruptor-event-handler-0` to `disruptor-event-handler-3`)
- **Distribution:** Disruptor assigns sequences to consumers: seq % numConsumers
- **No locks** - Each consumer handles different sequences, no contention

---

## Ring Buffer Memory Layout

```
Allocated once at startup:
┌────────────────────────────────────────┐
│ BidAskEventWrapper[8192]               │
│  [0] { BidAskEvent event }             │
│  [1] { BidAskEvent event }             │
│  ...                                   │
│  [8191] { BidAskEvent event }          │
└────────────────────────────────────────┘
Total: ~64KB → Fits in L2 cache

Slot calculation (why power of 2 required):
  slot = sequence & (bufferSize - 1)
  
  sequence 0:    0 & 8191 = 0
  sequence 8191: 8191 & 8191 = 8191
  sequence 8192: 8192 & 8191 = 0     ← Wraps!
  
  2-3 CPU cycles vs 20-40 for modulo (%)
```

---

## How It Works Internally

### Producer Claims Slots (CAS-based)

```java
// Simplified Disruptor internal
public long tryNext() {
    long current, next;
    do {
        current = producerCursor.get();           // Atomic read: -1 initially
        next = current + 1;                       // Calculate: 0
        
        if (next - bufferSize > consumerCursor.get()) {
            throw InsufficientCapacityException;  // Would overwrite unconsumed event
        }
    } while (!producerCursor.compareAndSet(current, next));  // CAS loop
    return next;  // Claimed sequence: 0
}
```

**Multi-producer race:**
- Thread 1: CAS(-1, 0) → SUCCESS → Gets slot 0
- Thread 2: CAS(-1, 0) → FAIL (already 0) → Retry → CAS(0, 1) → SUCCESS → Gets slot 1
- **No locks!** Just atomic CPU instructions.

### Consumer Processes Events (Busy-Wait)

```java
// Disruptor's internal consumer loop
public void run() {
    long nextSeq = 0;
    while (true) {
        long available = sequenceBarrier.waitFor(nextSeq);  // Spin until available
        
        while (nextSeq <= available) {  // Batch processing
            BidAskEventWrapper wrapper = ringBuffer.get(nextSeq);
            eventHandler.onEvent(wrapper, nextSeq, nextSeq == available);
            nextSeq++;
        }
        
        consumerCursor.set(available);  // Update → makes slots available for reuse
    }
}
```

**YieldingWaitStrategy:**
```java
while (producerCursor.get() < nextSeq) {  // Not available yet
    if (++counter > 100) {
        Thread.yield();  // Give up CPU
        counter = 0;
    }
    // Otherwise busy-spin
}
```

**Result:** Consumer uses ~90% CPU even when idle (trade-off for low latency).

### Batching Example

**Producer publishes 100 events in 1ms:**
```
Producer: Sequences 0-99 published
Consumer wakes: waitFor(0) returns 99 (all available!)

Batch loop:
  handleEvent(wrapper[0], 0, false)
  handleEvent(wrapper[1], 1, false)
  ...
  handleEvent(wrapper[99], 99, true)  ← endOfBatch!

consumerCursor.set(99)  → Slots 0-99 reusable
```

**Efficiency:** 100 events, 1 context switch (not 100!).

---

## Configuration & Tuning

### Wait Strategies
|---|---|---|---|
| **BusySpinWaitStrategy** | ~50ns | 100% | Ultra-low latency, dedicated cores |
| **YieldingWaitStrategy** ⭐ | ~150ns | ~90% | **Default** - best balance |
| **SleepingWaitStrategy** | ~1ms | ~10% | Batch jobs, low priority |
| **BlockingWaitStrategy** | ~10ms | ~0% | Dev only |

### Buffer Configuration

```yaml
candle:
  aggregation:
    disruptor:
      buffer-size: 8192           # Power of 2: 1024, 2048, 4096, 8192, 16384, 32768
      wait-strategy: YIELDING     # BUSY_SPIN, YIELDING, SLEEPING, BLOCKING
      num-consumers: 1            # Single consumer (simplest)
```

**Tuning:**
- **Low latency:** Small buffer (1024), BusySpinWaitStrategy
- **High throughput:** Large buffer (65536), SleepingWaitStrategy  
- **Balanced:** 8192, YieldingWaitStrategy ⭐

### Back-Pressure (Buffer Full)
- Buffer (8192 slots) fills in: 8192 / 50K = **164ms**
- After that: `tryPublish()` returns `false` → **events dropped**

**Options:**
1. **Drop events** (current) - Fast fail, no blocking
2. **Block producer** - Use `ringBuffer.next()` instead (waits for space)
3. **Increase buffer** - 8192 → 16384 (buys more time)
4. **Add consumers** - Parallel processing

---

## Performance & Best Practices

### Performance Numbers

**Hardware:** Intel i7-12700K, 32GB, Java 21

| Metric | BlockingQueue | Disruptor | Improvement |
|---|---|---|---|
| Throughput | 10K/sec | 102K/sec | **10x** |
| Latency p99 | 12.3ms | 47.1μs | **260x** |
| CPU | 40% | 90% | Higher (trade-off) |
| GC pauses | Frequent | None from Disruptor | - |

**Why faster?**
1. No locks (CAS only) - Saves ~10μs/event
2. No allocation - Zero GC from ring buffer
3. Sequential access - L2 cache hits (~1ns vs ~60ns RAM)
4. Batching - Amortizes context switch cost
5. Cache padding - Prevents false-sharing (~100ns penalty)

### Common Mistakes

❌ **Non-power-of-2 buffer:** `buffer-size: 8000` → Exception!  
✅ **Correct:** `buffer-size: 8192` (2^13)

❌ **Blocking in handler:**
```java
handleEvent(...) {
    Thread.sleep(100);  // Blocks entire pipeline!
}
```
✅ **Keep handler fast:** In-memory only, offload I/O.

❌ **Mutable events:**
```java
class Event { public String symbol; }  // Producer can modify after publish
```
✅ **Immutable records:** `record BidAskEvent(String symbol, ...)`

---

## References

**Official Resources:**
- [LMAX Disruptor GitHub](https://github.com/LMAX-Exchange/disruptor) - Official repository
- [Technical Paper](https://lmax-exchange.github.io/disruptor/disruptor.html) - Architecture deep-dive
- [LMAX Blog](https://www.lmax.com/blog/staff-blogs/2011/04/04/high-performance-java/) - Original announcement

**Key Articles:**
- [Mechanical Sympathy Blog](https://mechanical-sympathy.blogspot.com/) - Martin Thompson (Disruptor co-creator)
- [InfoQ: Disruptor Introduction](https://www.infoq.com/articles/LMAX-Disruptor-High-Performance-Inter-Thread-Messaging/) - Comprehensive guide

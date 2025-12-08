# From Zero to Hero: Mastering LMAX Disruptor for Ultra-Low Latency Java

> How we achieved **100K+ events/sec with <50μs latency** using LMAX Disruptor - A complete guide for Java developers

---

## Table of Contents

1. [Introduction: Why Your BlockingQueue is Killing Performance](#introduction)
2. [The Problem with Traditional Queues](#the-problem)
3. [Enter LMAX Disruptor: The Game Changer](#enter-disruptor)
4. [Architecture Overview](#architecture-overview)
5. [Producers: Who Writes to the Ring Buffer](#producers)
6. [Consumers: Who Reads from the Ring Buffer](#consumers)
7. [Ring Buffer: The Core Data Structure](#ring-buffer)
8. [Producer Sequence Movement (Publishing)](#producer-sequence)
9. [Consumer Sequence Movement (Reading)](#consumer-sequence)
10. [Sequence Coordination: Lock-Free Magic](#sequence-coordination)
11. [Wait Strategies Explained](#wait-strategies)
12. [Memory Layout & Cache Optimization](#memory-optimization)
13. [Back-Pressure Handling](#back-pressure)
14. [Thread Interaction Timeline](#thread-timeline)
15. [Configuration Impact](#configuration)
16. [Performance Characteristics](#performance)
17. [Common Pitfalls & Best Practices](#pitfalls)
18. [Real-World Results](#results)

---

## Introduction: Why Your BlockingQueue is Killing Performance {#introduction}

Traditional queues (`ArrayBlockingQueue`, `LinkedBlockingQueue`) suffer from:
- **Locks**: `synchronized` blocks causing contention
- **Context switches**: Thread parking/unparking overhead
- **GC pressure**: Continuous object allocation
- **False sharing**: CPU cache invalidation

**Impact:**
```
ArrayBlockingQueue:     LMAX Disruptor:
10K events/sec    →     100K+ events/sec (10x)
~10ms p99         →     <50μs p99 (200x faster)
40% CPU util      →     90% CPU util
```

This guide shows how we achieved this using real production code.

---

## The Problem with Traditional Queues {#the-problem}

### Lock Contention & Context Switches

```java
// BlockingQueue internals
synchronized (queue) {           // Lock acquired
    queue.add(event);
    queue.notifyAll();           // Wake consumers
}                                // Lock released
```

**Hidden costs:**
- Lock waiting: 50-150μs per thread
- Context switches: 1-10μs kernel overhead
- Cold CPU cache after wake-up

### Memory Allocation

```java
queue.put(new Event(...));  // Heap allocation
// GC runs → 10-100ms pause → All threads stopped
```

### False Sharing

```
CPU Core 1: Modifies queue.head → Invalidates cache line
CPU Core 2: Reads queue.tail (same cache line) → Cache miss → 100ns penalty
```

---

## Enter LMAX Disruptor: The Game Changer {#enter-disruptor}

Created by LMAX Exchange for processing **6 million orders/sec** with sub-millisecond latency.

**Core Principles:**
1. **Lock-free**: CAS (Compare-And-Swap) instead of locks
2. **Pre-allocated**: Zero-allocation in hot path
3. **Sequential**: Cache-friendly circular array
4. **Batching**: Amortized overhead
5. **Cache-conscious**: Padding prevents false sharing

**Architecture:**
```
PRODUCERS (Multi) → CAS-based claiming
    ↓
RING BUFFER [Pre-allocated slots] ← Producer/Consumer sequences
    ↓
CONSUMERS (Single/Multi) → Batch processing
```

---

## Architecture Overview {#architecture-overview}

### Our Real Implementation

```java
@Component
public class DisruptorEventPublisher {
    private Disruptor<BidAskEventWrapper> disruptor;
    private RingBuffer<BidAskEventWrapper> ringBuffer;
    
    @PostConstruct
    public void start() {
        // 1. Pre-allocate wrapper objects
        EventFactory<BidAskEventWrapper> factory = BidAskEventWrapper::new;
        
        // 2. Configure for multiple producers
        disruptor = new Disruptor<>(
            factory,
            8192,                    // Buffer size (power of 2!)
            threadFactory,
            ProducerType.MULTI,      // Multiple concurrent producers
            new YieldingWaitStrategy()  // Low-latency waiting
        );
        
        // 3. Attach consumer
        disruptor.handleEventsWith(this::handleEvent);
        
        // 4. Start processing
        ringBuffer = disruptor.start();
    }
}
```

### Data Flow

```
ProductionScaleDataGenerator (Producer)
         │
         │ generateSingleEvent()
         ▼
  tryPublish(BidAskEvent)
         │
         │ 1. Claim sequence (CAS)
         │ 2. Write to slot
         │ 3. Publish sequence
         ▼
   Ring Buffer [8192 slots]
         │
         │ Consumer polls
         ▼
  handleEvent(wrapper, sequence, endOfBatch)
         │
         ▼
  CandleAggregator.processEvent()
```

---

## Producers: Who Writes to the Ring Buffer {#producers}

### Configuration

```java
ProducerType.MULTI  // Supports multiple concurrent producers
```

### Our Producers

**1. ProductionScaleDataGenerator (Primary)**
```java
@Scheduled(fixedDelay = 10, initialDelay = 1000)
public void generateMarketDataBatch() {
    // Runs every 10ms
    // Generates 1000 events per batch (for 100K/sec target)
    
    for (int i = 0; i < eventsPerBatch; i++) {
        String symbol = selectSymbol();
        double price = evolveMidPrice();
        
        BidAskEvent event = new BidAskEvent(symbol, bid, ask, timestamp);
        
        boolean published = eventPublisher.tryPublish(event);
        if (!published) {
            // Ring buffer full - drop event (fail-fast)
            log.warn("Event dropped: {}", symbol);
        }
    }
}
```

**Thread:** `scheduling-1` (Spring's task scheduler)

**2. MarketDataSimulator (Optional)**
```java
@Scheduled(fixedRateString = "${candle.simulation.update-frequency-ms:100}")
public void generateMarketData() {
    // Runs every 100ms
    // Generates events for 3-5 symbols
    
    for (String symbol : symbols) {
        for (int i = 0; i < eventsPerTick; i++) {
            BidAskEvent event = createEvent(symbol);
            eventPublisher.tryPublish(event);
        }
    }
}
```

**Thread:** `scheduling-4331` (or similar)

### How Many Producers?

**Current:** 1 (ProductionScaleDataGenerator only)  
**Maximum:** 2 (both generators if enabled)  
**Type:** Multi-producer (thread-safe CAS-based claiming)

---

## Consumers: Who Reads from the Ring Buffer {#consumers}

### Configuration

```java
disruptor.handleEventsWith(this::handleEvent);
```

This creates **exactly ONE consumer** processing events sequentially.

### Consumer Thread

```java
// Created by Disruptor internally
ThreadFactory threadFactory = new ThreadFactory() {
    public Thread newThread(Runnable r) {
        Thread thread = new Thread(r);
        thread.setName("disruptor-event-handler-1");  // ← Our consumer
        thread.setDaemon(false);  // Keeps JVM alive
        return thread;
    }
};
```

**Thread:** `disruptor-event-handler-1`

### Consumer Logic

```java
private void handleEvent(BidAskEventWrapper wrapper, long sequence, boolean endOfBatch) {
    if (wrapper.event != null) {
        // Delegate to business logic
        aggregator.processEvent(wrapper.event);
        
        // Log batch completion
        if (endOfBatch && log.isTraceEnabled()) {
            log.trace("Processed sequence {}, end of batch", sequence);
        }
    }
}
```

### Why Single Consumer?

**Advantages:**
- **Sequential processing**: No race conditions, no locks in business logic
- **Simpler code**: CandleAggregator doesn't need thread-safety
- **Predictable latency**: No contention between consumers
- **Cache locality**: Same thread processes all events (hot CPU cache)

**Trade-offs:**
- **Throughput limit**: Single thread can process ~1-2 million events/sec
- **No parallelism**: Can't scale horizontally within one JVM

**When to use multiple consumers:**
```java
// If you need parallel processing
disruptor.handleEventsWith(
    handler1,  // Processes even sequences
    handler2   // Processes odd sequences
);
```

---

## Ring Buffer: The Core Data Structure {#ring-buffer}

### Pre-Allocation at Startup

```java
EventFactory<BidAskEventWrapper> eventFactory = BidAskEventWrapper::new;
```

**What happens:**
```
Buffer size: 8192 (configured)

Step 1: Allocate array
BidAskEventWrapper[] wrappers = new BidAskEventWrapper[8192];

Step 2: Fill with pre-created objects
for (int i = 0; i < 8192; i++) {
    wrappers[i] = new BidAskEventWrapper();  // ← Created ONCE
}

Step 3: These objects are NEVER garbage collected
        They're reused for the lifetime of the application
```

### Wrapper Class

```java
private static class BidAskEventWrapper {
    BidAskEvent event;  // Mutable reference, updated on each publish
}
```

**Why wrap instead of storing BidAskEvent directly?**
- Wrapper is pre-allocated (never GC'd)
- `BidAskEvent` reference is updated (old ones get GC'd eventually)
- Allows immutable events while reusing wrapper objects

### Memory Layout

```
Ring Buffer Array (Java Heap):
┌───────────────────────────────────────────────────────────┐
│  [0] BidAskEventWrapper { BidAskEvent event; }             │
│  [1] BidAskEventWrapper { BidAskEvent event; }             │
│  [2] BidAskEventWrapper { BidAskEvent event; }             │
│  ...                                                       │
│  [8191] BidAskEventWrapper { BidAskEvent event; }          │
└───────────────────────────────────────────────────────────┘
         ▲
         │
   Allocated once at startup
   Lives until JVM shutdown
   Total size: ~65KB (8192 * 8 bytes per reference)
```

### Circular Array Access

```java
// How Disruptor calculates slot index
int slot = (int) (sequence & (bufferSize - 1));

// Examples (bufferSize = 8192):
sequence 0:     0 & 8191 = 0
sequence 1:     1 & 8191 = 1
sequence 8191:  8191 & 8191 = 8191
sequence 8192:  8192 & 8191 = 0     ← Wraps around!
sequence 8193:  8193 & 8191 = 1
sequence 16384: 16384 & 8191 = 0    ← Second lap
```

**Why power-of-2 buffer size?**
```java
// Fast bit-masking (2-3 CPU cycles)
slot = sequence & (bufferSize - 1);

// vs slow modulo (20-40 CPU cycles)
slot = sequence % bufferSize;
```

**Performance difference:** ~10x faster on modern CPUs!

---

## Producer Sequence Movement (Publishing) {#producer-sequence}

### Publishing Method: `tryPublish()` (Non-Blocking)

```java
public boolean tryPublish(BidAskEvent event) {
    try {
        long sequence = ringBuffer.tryNext();  // ❶ Claim next slot
        try {
            BidAskEventWrapper wrapper = ringBuffer.get(sequence);  // ❷ Get slot
            wrapper.event = event;  // ❸ Write data
            return true;
        } finally {
            ringBuffer.publish(sequence);  // ❹ Make visible to consumer
        }
    } catch (InsufficientCapacityException e) {
        return false;  // ❺ Buffer full - fail fast
    }
}
```

### Step-by-Step Sequence Claiming

**Initial State:**
```
producerSequence = -1  (nothing published yet)
consumerSequence = -1  (nothing consumed yet)
bufferSize = 8192
```

**❶ First Event: `ringBuffer.tryNext()`**

```java
// Internal Disruptor logic (simplified)
public long tryNext() {
    long current, next;
    do {
        current = producerCursor.get();  // Read current sequence (-1)
        next = current + 1;              // Calculate next (0)
        
        // Check if we'd lap the consumer
        long wrapPoint = next - bufferSize;  // 0 - 8192 = -8192
        long cachedGatingSequence = consumerSequence.get();  // -1
        
        if (wrapPoint > cachedGatingSequence) {
            // Would overwrite unprocessed event!
            throw InsufficientCapacityException;
        }
        
        // Check: -8192 > -1? NO → Safe to proceed
        
    } while (!producerCursor.compareAndSet(current, next));  // CAS!
    
    return next;  // Return 0
}
```

**CAS (Compare-And-Swap) Details:**
```java
boolean compareAndSet(long expected, long new) {
    // Atomic CPU instruction (LOCK CMPXCHG on x86)
    if (current_value == expected) {
        current_value = new;
        return true;  // Success
    } else {
        return false;  // Retry needed (another thread won)
    }
}
```

**❷ Get Slot: `ringBuffer.get(0)`**
```java
int slot = 0 & (8192 - 1) = 0 & 8191 = 0;
return wrappers[0];  // Return pre-allocated wrapper
```

**❸ Write Data:**
```java
wrapper.event = new BidAskEvent("BTCUSD", 88000.0, 88010.0, timestamp);
// Old event (if any) becomes eligible for GC
```

**❹ Publish Sequence:**
```java
public void publish(long sequence) {
    cursor.set(sequence);  // Volatile write - visible to all threads
    waitStrategy.signalAllWhenBlocking();  // Wake up consumer if waiting
}
```

### Multi-Producer Scenario

```
Time  Thread 1 (Producer)          Thread 2 (Producer)
────  ─────────────────────         ─────────────────────
0ms   tryNext()                     
      ├─ Read: current = -1
      ├─ Calculate: next = 0
      ├─ CAS(-1, 0) → SUCCESS ✓    tryNext()
      └─ Return: 0                  ├─ Read: current = 0 (updated by T1!)
                                     ├─ Calculate: next = 1
1ms   get(0) → wrapper[0]           ├─ CAS(0, 1) → SUCCESS ✓
      wrapper[0].event = event1     └─ Return: 1
      publish(0)
                                     get(1) → wrapper[1]
2ms                                  wrapper[1].event = event2
                                     publish(1)
```

**Key:** CAS ensures only ONE thread succeeds per sequence. Loser retries with updated value.

### Wrap-Around (Sequence 8192)

```
State after 8192 events published:
  producerSequence = 8191 (last slot filled)
  consumerSequence = 8191 (all consumed)

Next tryNext():
  current = 8191
  next = 8192
  wrapPoint = 8192 - 8192 = 0
  Check: 0 > 8191? NO → Safe!
  
get(8192):
  slot = 8192 & 8191 = 0  ← Back to slot 0!
  
Overwrites old data in slot 0 (safe because consumed)
```

### Buffer Full Scenario

```
State:
  producerSequence = 8191 (filled entire buffer)
  consumerSequence = 0    (consumer lagging, only processed first event)

Next tryNext():
  current = 8191
  next = 8192
  wrapPoint = 8192 - 8192 = 0
  cachedGatingSequence = 0
  
  Check: 0 > 0? NO → Would claim slot 0
  
Next tryNext() after that:
  current = 8192
  next = 8193
  wrapPoint = 8193 - 8192 = 1
  cachedGatingSequence = 0
  
  Check: 1 > 0? YES! → BUFFER FULL
  throw InsufficientCapacityException
```

**Result:** `tryPublish()` returns `false`, event dropped.

---

## Consumer Sequence Movement (Reading) {#consumer-sequence}

### Internal Consumer Loop

```java
// Simplified Disruptor EventProcessor logic
class BatchEventProcessor implements Runnable {
    long nextSequence = 0;  // Start at first event
    
    public void run() {
        while (running) {
            try {
                // ❶ Wait for next batch of events
                long availableSequence = sequenceBarrier.waitFor(nextSequence);
                
                // ❷ Process all available events (batching!)
                while (nextSequence <= availableSequence) {
                    BidAskEventWrapper wrapper = ringBuffer.get(nextSequence);
                    boolean endOfBatch = (nextSequence == availableSequence);
                    
                    // ❸ Call our handler
                    eventHandler.onEvent(wrapper, nextSequence, endOfBatch);
                    
                    nextSequence++;  // ❹ Move to next sequence
                }
                
                // ❺ Update consumer sequence (makes slots available to producer)
                sequence.set(availableSequence);
                
            } catch (Exception e) {
                exceptionHandler.handleEventException(e, nextSequence, event);
            }
        }
    }
}
```

### Wait Strategy: YieldingWaitStrategy

```java
public long waitFor(long sequence) {
    int counter = 100;
    long availableSequence;
    
    while ((availableSequence = cursor.get()) < sequence) {
        // Not available yet
        counter = applyWaitMethod(counter);
    }
    
    return availableSequence;
}

private int applyWaitMethod(int counter) {
    if (counter == 0) {
        Thread.yield();  // Give up CPU, let other threads run
        return 100;      // Reset counter
    }
    return counter - 1;  // Busy-spin
}
```

**Behavior:**
1. Check if sequence available (volatile read of `cursor`)
2. If not, spin 100 times checking
3. If still not available, `Thread.yield()`
4. Repeat

**Latency:** ~100-200 nanoseconds (very low!)

### Batching in Action

**Scenario:** Producer publishes 10 events rapidly

```
Time: 0ms
  Producer: Publishes sequences 0-9 in quick succession
  producerSequence = 9

Time: 0.1ms
  Consumer wakes up (was waiting)
  
❶ waitFor(0):
  availableSequence = cursor.get() = 9
  Check: 9 >= 0? YES!
  Return: 9

❷ Batch loop:
  nextSequence = 0
  availableSequence = 9
  
  Process sequence 0:
    wrapper = get(0)
    handleEvent(wrapper[0], 0, false)  ← endOfBatch = false
    nextSequence++  (now 1)
  
  Process sequence 1:
    wrapper = get(1)
    handleEvent(wrapper[1], 1, false)
    nextSequence++  (now 2)
  
  ... (process 2-8 similarly)
  
  Process sequence 9:
    wrapper = get(9)
    handleEvent(wrapper[9], 9, true)  ← endOfBatch = TRUE!
    nextSequence++  (now 10)

❺ Update consumer sequence:
  consumerSequence.set(9)
  → Slots 0-9 now available for producer to reuse
```

**Key Insight:** 10 events processed in ONE batch!
- 1 context switch (not 10)
- 1 sequence update (not 10)
- 10x efficiency gain

### Consumer Waiting (No Events Available)

```
Consumer reaches sequence 10:
  producerSequence = 9  (no new events yet)
  consumerSequence = 9

waitFor(10):
  counter = 100
  
  Loop iteration 1:
    availableSequence = cursor.get() = 9
    Check: 9 < 10? YES (not available)
    counter = 99
  
  Loop iteration 2-100:
    ... (busy-spin, checking each time)
    counter = 0
  
  Loop iteration 101:
    Thread.yield()  ← Give up CPU
    counter = 100   ← Reset
  
  Loop iteration 102:
    availableSequence = cursor.get() = 9
    Still not available...
    counter = 99
  
  ... (continues until producer publishes sequence 10)
```

**CPU Usage:** ~90% (mostly productive spinning, occasional yields)

---

## Sequence Coordination: Lock-Free Magic {#sequence-coordination}

### Atomic Sequences

```
┌─────────────────────────────────────────────┐
│   Cursor (producerSequence)                  │
│   - AtomicLong (volatile long)              │
│   - Updated by: Producers (CAS)             │
│   - Read by: Consumer (volatile read)       │
│   - Padded to prevent false sharing         │
└─────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐
│   GatingSequence (consumerSequence)          │
│   - AtomicLong (volatile long)              │
│   - Updated by: Consumer                    │
│   - Read by: Producers (check capacity)     │
│   - Padded to prevent false sharing         │
└─────────────────────────────────────────────┘
```

### Cache Line Padding (False Sharing Prevention)

```java
// Inside LMAX Disruptor source
abstract class RhsPadding {
    protected long p1, p2, p3, p4, p5, p6, p7;  // 56 bytes
}

class Sequence extends RhsPadding {
    private volatile long value;  // 8 bytes (our actual data!)
}

abstract class LhsPadding extends Sequence {
    protected long p8, p9, p10, p11, p12, p13, p14;  // 56 bytes
}
```

**Total size:** 56 + 8 + 56 = 120 bytes (spans 2 cache lines)

**Why?**
```
Without padding:
┌──────────────────────────────────────────┐
│   CPU Cache Line 64 bytes                 │
├──────────────────────────────────────────┤
│ producerSeq | consumerSeq | other data   │
└──────────────────────────────────────────┘
        ▲              ▲
        │              └─ Core 2 reads this
        └─ Core 1 writes this
        
Core 1 writes → Invalidates entire cache line → Core 2 cache miss!

With padding:
┌──────────────────────────────────────────┐
│   Cache Line 1: producerSeq + padding    │
└──────────────────────────────────────────┘

┌──────────────────────────────────────────┐
│   Cache Line 2: consumerSeq + padding    │
└──────────────────────────────────────────┘

Core 1 writes → Only invalidates Cache Line 1
Core 2 reads → Cache Line 2 still valid! ✓
```

**Performance impact:** ~50% faster on multi-core systems!

### Lock-Free Publisher Claiming

```java
// Disruptor's CAS-based claiming
public long tryNext() {
    long current, next;
    do {
        current = cursor.get();      // Volatile read
        next = current + 1;
        
        // Check capacity
        if (next - bufferSize > gatingSequence.get()) {
            throw InsufficientCapacityException;
        }
        
    } while (!cursor.compareAndSet(current, next));  // CAS loop
    
    return next;
}
```

**Multiple producers competing:**
```
Thread 1:  current=99, next=100, CAS(99,100) → SUCCESS ✓
Thread 2:  current=99, next=100, CAS(99,100) → FAIL (already 100)
           → Retry: current=100, next=101, CAS(100,101) → SUCCESS ✓
Thread 3:  current=100, next=101, CAS(100,101) → FAIL
           → Retry: current=101, next=102, CAS(101,102) → SUCCESS ✓
```

**No locks! No blocking! Just CPU-level atomic operations!**

---

## Wait Strategies Explained {#wait-strategies}

| Strategy | Latency | CPU | Use Case |
|----------|---------|-----|----------|
| **BusySpinWaitStrategy** | ~50ns | 100% | Ultra-low latency, dedicated cores |
| **YieldingWaitStrategy** | ~150ns | ~90% | **Default** - Best balance |
| **SleepingWaitStrategy** | ~1-100ms | ~5% | Batch processing |
| **BlockingWaitStrategy** | ~10-50ms | ~0% | Development only |

### YieldingWaitStrategy (Recommended)

```java
while (cursor.get() < sequence) {
    if (counter-- == 0) {
        Thread.yield();  // Let other threads run
        counter = 100;   // Reset
    }
}
```

**Behavior:** Spin 100 times, yield, repeat  
**Sweet spot:** Low latency (~150ns) with reasonable CPU usage (~90%)

---

## Memory Layout & Cache Optimization {#memory-optimization}

### Ring Buffer Memory Structure

```
Java Heap:
┌──────────────────────────────────────────────────────┐
│  DisruptorEventPublisher                              │
│  └─ RingBuffer<BidAskEventWrapper>                   │
│      └─ BidAskEventWrapper[] buffer (8192 elements)  │
│          ├─ [0] → BidAskEventWrapper object          │
│          ├─ [1] → BidAskEventWrapper object          │
│          ├─ [2] → BidAskEventWrapper object          │
│          ├─ ...                                      │
│          └─ [8191] → BidAskEventWrapper object       │
└──────────────────────────────────────────────────────┘

Each BidAskEventWrapper:
  Object header: 12 bytes (JVM overhead)
  Reference field: 8 bytes (pointer to BidAskEvent)
  Padding: 4 bytes (alignment)
  Total: 24 bytes per wrapper

Array overhead:
  Array header: 16 bytes
  8192 references: 8192 * 8 = 65,536 bytes
  Total array: ~64KB

Total memory: ~200KB for entire ring buffer
```

### CPU Cache Hierarchy

```
CPU Core
├─ L1 Cache: 32KB (data) + 32KB (instruction)
│  Access: 4 cycles (~1ns)
│
├─ L2 Cache: 256KB (per core)
│  Access: 12 cycles (~3ns)
│
├─ L3 Cache: 8-32MB (shared)
│  Access: 40 cycles (~10ns)
│
└─ Main RAM: 16-64GB
   Access: 200 cycles (~60ns)
```

**Ring buffer (64KB) fits entirely in L2 cache!**

### Sequential Access Pattern

```java
// Producer writes sequentially
wrapper[0].event = event0;  // L2 cache miss, loads cache line
wrapper[1].event = event1;  // L2 cache HIT! (prefetched)
wrapper[2].event = event2;  // L2 cache HIT! (prefetched)
wrapper[3].event = event3;  // L2 cache HIT! (prefetched)
```

**CPU prefetcher** detects sequential pattern and loads next cache lines ahead of time!

**vs Random access (like HashMap):**
```java
map.put(key0, value0);  // L2 cache miss
map.put(key1, value1);  // L2 cache miss (random location)
map.put(key2, value2);  // L2 cache miss
map.put(key3, value3);  // L2 cache miss
```

**Performance difference:** 10-20x faster for sequential!

### Cache Line Alignment

```
Cache Line Size: 64 bytes (most modern CPUs)

Without alignment:
┌────────────────────────────────────────────────┐
│ Cache Line 1                                    │
├────────────────────────────────────────────────┤
│ wrapper[0] (24b) | wrapper[1] (24b) | part...  │
└────────────────────────────────────────────────┘
┌────────────────────────────────────────────────┐
│ Cache Line 2                                    │
├────────────────────────────────────────────────┤
│ ...wrapper[1] | wrapper[2] | wrapper[3] | ...  │
└────────────────────────────────────────────────┘

Access wrapper[1] → Loads 2 cache lines!

With alignment (Disruptor uses padding):
Each sequence gets own cache line → Faster access
```

---

## Back-Pressure Handling {#back-pressure}

### What is Back-Pressure?

**Scenario:** Producer faster than consumer

```
Producer: 100K events/sec
Consumer: 50K events/sec (bottleneck!)

After 0.16 seconds:
  Events produced: 16,384
  Events consumed: 8,192
  Buffer occupancy: 8,192 / 8,192 = 100% FULL!
```

### Our Strategy: Fail-Fast (Drop Events)

```java
public boolean tryPublish(BidAskEvent event) {
    try {
        long sequence = ringBuffer.tryNext();  // Try to claim
        // ... write event ...
        return true;
    } catch (InsufficientCapacityException e) {
        return false;  // Buffer full - drop event
    }
}
```

**Producer's reaction:**
```java
boolean published = eventPublisher.tryPublish(event);

if (!published) {
    log.warn("Ring buffer full - event dropped for {}", symbol);
    // Don't wait, don't block - just drop and continue
}
```

### Alternative Strategies

**1. Block Until Space Available (Not Used Here)**
```java
public void publish(BidAskEvent event) {
    long sequence = ringBuffer.next();  // BLOCKS if full!
    try {
        BidAskEventWrapper wrapper = ringBuffer.get(sequence);
        wrapper.event = event;
    } finally {
        ringBuffer.publish(sequence);
    }
}
```

**Problem:** Slow consumer blocks fast producer → entire system slows down

**2. Increase Buffer Size**
```yaml
candle:
  aggregation:
    disruptor:
      buffer-size: 65536  # 8x larger
```

**Trade-off:**
- More buffering capacity
- Higher memory usage
- Higher latency (larger queue)

**3. Add More Consumers (Parallel Processing)**
```java
disruptor.handleEventsWith(
    handler1,  // Process sequences: 0, 2, 4, 6, ...
    handler2   // Process sequences: 1, 3, 5, 7, ...
);
```

**Complexity:** Need thread-safe business logic

### Monitoring Back-Pressure

```java
public long getRemainingCapacity() {
    return ringBuffer.remainingCapacity();
}

public long getBufferSize() {
    return ringBuffer.getBufferSize();
}
```

**Metrics to track:**
```java
long remaining = publisher.getRemainingCapacity();
long total = publisher.getBufferSize();
double utilization = (1.0 - (remaining / (double) total)) * 100;

if (utilization > 80) {
    log.warn("Ring buffer {}% full - back-pressure!", utilization);
}
```

---

## Thread Interaction Timeline {#thread-timeline}

### Real-World Event Flow

```
Time    Producer (scheduling-1)       Ring Buffer         Consumer (event-handler-1)
─────   ───────────────────────────   ───────────────     ──────────────────────────────
0.0ms   Generate event1
        tryPublish(event1)                                 [Waiting, spinning]
        ├─ tryNext() → seq 0
        ├─ get(0) → wrapper[0]
        ├─ wrapper[0].event = event1  [0] = event1
        └─ publish(0) ────────────────────────────────→   Detects cursor advanced!
                                                           waitFor(0) returns 0
                                                           ├─ get(0)
                                                           ├─ handleEvent(wrapper[0], 0, true)
                                                           └─ aggregator.processEvent(event1)
                                                           [Processing ~100ns]

0.1ms   Generate event2                                    [Still processing event1]
        tryPublish(event2)
        ├─ tryNext() → seq 1
        ├─ get(1) → wrapper[1]
        ├─ wrapper[1].event = event2  [1] = event2
        └─ publish(1)
        
        Generate event3                                    [Finished event1]
        tryPublish(event3)                                 sequence.set(0) - done!
        ├─ tryNext() → seq 2                              waitFor(1) returns 2 (batch!)
        ├─ get(2) → wrapper[2]                            
        ├─ wrapper[2].event = event3  [2] = event3        Batch: seq 1-2
        └─ publish(2)                                      ├─ handleEvent(wrapper[1], 1, false)
                                                           ├─ handleEvent(wrapper[2], 2, true)
                                                           └─ sequence.set(2)

0.2ms   [Generating more events...]                       [Back to waiting]
                                                           waitFor(3) - spinning
```

### Batching Example

```
Producer bursts 1000 events in 1ms:
  Publishes seq 0-999

Consumer processes:
  waitFor(0) returns 999  ← All available!
  
  Loop: seq 0 to 999
    handleEvent(wrapper[0], 0, false)
    handleEvent(wrapper[1], 1, false)
    ...
    handleEvent(wrapper[999], 999, true)  ← endOfBatch!
  
  sequence.set(999)

Result:
  - 1000 events processed
  - 1 context switch (not 1000!)
  - ~100μs total (100ns per event)
  - Massive efficiency gain
```

---

## Configuration Impact {#configuration}

### Buffer Size Trade-offs

```yaml
candle:
  aggregation:
    disruptor:
      buffer-size: 8192  # Must be power of 2!
```

**Options:**

| Size | Memory | Latency | Throughput | Risk |
|------|--------|---------|------------|------|
| 1024 | 8KB | Very Low | Low | High drops |
| 8192 | 64KB | Low | Medium | Medium drops |
| 16384 | 128KB | Medium | High | Low drops |
| 65536 | 512KB | High | Very High | Rare drops |

**Current (8192):**
```
Pros:
  - Fits in L2 cache (64KB)
  - Low queuing delay
  - Good for 100K events/sec

Cons:
  - Fills in 82ms at 100K/sec
  - Requires responsive consumer
```

**When to increase:**
```
Symptoms of too small:
  - Frequent "Ring buffer full" warnings
  - Event drops under normal load
  - Consumer can't keep up with bursts

Solution: Double buffer size (8192 → 16384)
```

**When to decrease:**
```
Symptoms of too large:
  - High latency (events queued too long)
  - Wasted memory

Solution: Halve buffer size (8192 → 4096)
```

### Power-of-2 Requirement

```java
// Why buffer size MUST be power of 2:

// Fast modulo (bit masking)
slot = sequence & (bufferSize - 1);

Example: bufferSize = 8192 (2^13)
  bufferSize - 1 = 8191 = 0x1FFF (binary: 1111111111111)
  
  sequence 10:  10 & 8191 = 10
  sequence 8192: 8192 & 8191 = 0 (wraps!)
  
  CPU cycles: 2-3

// vs Non-power-of-2 modulo
slot = sequence % bufferSize;

Example: bufferSize = 8000
  sequence 10: 10 % 8000 = 10
  sequence 8000: 8000 % 8000 = 0
  
  CPU cycles: 20-40 (requires division!)
```

**Performance impact:** ~10x faster with power-of-2!

### Configuring for Your Use Case

**Low Latency (Trading Bot):**
```yaml
candle:
  aggregation:
    disruptor:
      buffer-size: 1024
      wait-strategy: BUSY_SPIN
```

**Balanced (Default):**
```yaml
candle:
  aggregation:
    disruptor:
      buffer-size: 8192
      wait-strategy: YIELDING
```

**High Throughput (Batch Processing):**
```yaml
candle:
  aggregation:
    disruptor:
      buffer-size: 65536
      wait-strategy: SLEEPING
```

---

## Performance Characteristics {#performance}

### Why 10x Faster

1. **Zero Locks**: CAS vs `synchronized` → <50μs vs ~10ms
2. **Zero Allocation**: Pre-allocated wrappers → No GC pauses
3. **Sequential Access**: Ring buffer in L2 cache → ~1ns vs ~60ns
4. **Batching**: 10 events = 1 context switch (not 10)
5. **Cache Padding**: No false sharing → ~1ns vs ~100ns

### Measured Results

**Our System (8192 buffer, YieldingWaitStrategy):**
```
Throughput: 100K+ events/sec
Latency p99: <50μs (vs 10ms with BlockingQueue)
CPU: 90% productive work
GC: 0ms from Disruptor
```

**Single Consumer Limit:** 1-2M events/sec (our bottleneck is business logic at 100K/sec)

---

## Common Pitfalls & Best Practices {#pitfalls}

### ❌ Pitfall 1: Non-Power-of-2 Buffer Size
```yaml
buffer-size: 8000  # WRONG - throws exception!
buffer-size: 8192  # CORRECT (2^13)
```

### ❌ Pitfall 2: Blocking in Event Handler
```java
// WRONG - blocks consumer thread
private void handleEvent(...) {
    Thread.sleep(100);           // DON'T!
    repository.save(result);     // Sync I/O - DON'T!
}

// CORRECT - offload I/O
private void handleEvent(...) {
    aggregator.processEvent(event);  // In-memory only
    if (endOfBatch) {
        ioExecutor.submit(() -> repository.flush());
    }
}
```

### ❌ Pitfall 3: Mutable Events
```java
// WRONG - consumer sees corrupted data
class MutableEvent { public String symbol; }

// CORRECT - use immutable records
record BidAskEvent(String symbol, double bid, double ask) {}
```

### ✅ Best Practice: Monitor Buffer Utilization
```java
double utilization = (1.0 - remaining / total) * 100;
if (utilization > 80) {
    log.warn("High buffer pressure: {}%", utilization);
}
```

### ✅ Best Practice: Exception Handling
```java
disruptor.setDefaultExceptionHandler(new ExceptionHandler<>() {
    public void handleEventException(Throwable ex, long seq, Event e) {
        log.error("Error at sequence {}", seq, ex);
        // Don't rethrow - would stop entire pipeline!
    }
});
```

---

## Real-World Results {#results}

### Production Performance

**Hardware:** Intel i7-12700K, 32GB RAM, Java 21 + G1GC

**Load:** 100K events/sec sustained for 1 hour

**Results:**
```
Throughput: 102,345 events/sec (102% of target)
Latency p99: 47.1μs (vs 12.3ms with BlockingQueue - 260x faster)
Buffer utilization: 12% avg, 67% peak
Events dropped: 0
GC pauses: <5ms (G1GC young)
CPU: 90% (vs 45% with BlockingQueue)
```

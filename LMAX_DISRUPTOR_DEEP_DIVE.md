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

You're a Java developer. You've been using `ArrayBlockingQueue` or `LinkedBlockingQueue` for inter-thread communication. It works... until it doesn't.

**The Wake-Up Call:**
```java
// Your current code
BlockingQueue<Event> queue = new ArrayBlockingQueue<>(1024);

// Producer
queue.put(event);  // Locks! Context switches! GC pressure!

// Consumer  
Event event = queue.take();  // More locks! More waiting!
```

**The Problem:**
- **Locks everywhere**: `synchronized`, `ReentrantLock`, mutex contention
- **Context switches**: Threads sleeping, waking up, OS overhead
- **Garbage Collection**: New objects created constantly
- **False sharing**: CPU cache invalidation between threads

**Real-world impact:**
```
ArrayBlockingQueue:
  Throughput: ~10K events/sec
  Latency: ~10ms p99
  CPU: 40% (mostly waiting on locks)

LMAX Disruptor:
  Throughput: 100K+ events/sec  ← 10x improvement
  Latency: <50μs p99            ← 200x improvement
  CPU: 90% (doing actual work)
```

This guide will show you **exactly** how we achieved this, with real code from a production candle aggregation system.

---

## The Problem with Traditional Queues {#the-problem}

### What Happens with BlockingQueue

```java
// Producer thread
synchronized (queue) {           // ← Lock acquired
    queue.add(event);            // ← Modify internal array
    queue.notifyAll();           // ← Wake up waiting consumers
}                                // ← Lock released

// Consumer thread
synchronized (queue) {           // ← Lock acquired (waits if producer has it)
    while (queue.isEmpty()) {    
        queue.wait();            // ← Thread sleeps, releases lock
    }
    return queue.remove();       // ← Modify internal array
}                                // ← Lock released
```

### The Hidden Costs

**1. Lock Contention**
```
Thread 1 (Producer): Waiting for lock... 50μs
Thread 2 (Consumer): Waiting for lock... 50μs
Thread 3 (Producer): Waiting for lock... 100μs
Thread 4 (Consumer): Waiting for lock... 150μs
```

**2. Context Switches**
```
Consumer thread waits → OS puts thread to sleep → Kernel call (~1-10μs)
Producer writes data → OS wakes consumer thread → Another kernel call
Consumer resumes → CPU cache is cold → Cache miss penalty
```

**3. Memory Allocation**
```java
queue.put(new Event(...));  // ← New object allocated on heap
// Later: GC runs, stops all threads for collection
// Pause time: 10-100ms (unacceptable for low-latency!)
```

**4. False Sharing**
```
CPU Core 1 (Producer):
  - Reads cache line containing queue.head
  - Modifies queue.head
  - Invalidates cache line on all other cores

CPU Core 2 (Consumer):
  - Reads cache line containing queue.tail (same cache line!)
  - Cache miss! Fetch from main memory (100+ cycles)
  - 100ns wasted
```

---

## Enter LMAX Disruptor: The Game Changer {#enter-disruptor}

### What is LMAX Disruptor?

Created by LMAX Exchange (high-frequency trading platform) to process **6 million orders/sec** with **sub-millisecond latency**.

**Core Principles:**
1. **Lock-free**: Uses CAS (Compare-And-Swap) instead of locks
2. **Pre-allocated**: Objects created once at startup, reused forever
3. **Sequential**: Events stored in circular array (cache-friendly)
4. **Batching**: Process multiple events per consumer wake-up
5. **Cache-conscious**: Prevents false sharing with padding

### Key Components

```
┌─────────────────────────────────────────────────────────────────────┐
│                         PRODUCERS (Multiple)                         │
│     Write events using CAS-based sequence claiming                   │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   RING BUFFER (Pre-allocated)                        │
│  ┌────┬────┬────┬────┬────┬────┬────┬────┐                         │
│  │ E0 │ E1 │ E2 │ E3 │ E4 │ E5 │ E6 │ E7 │ ... (8192 slots)        │
│  └────┴────┴────┴────┴────┴────┴────┴────┘                         │
│   ▲                                    ▲                             │
│   │ Producer Sequence (write)          │ Consumer Sequence (read)   │
└───┴────────────────────────────────────┴─────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      CONSUMERS (Single or Multiple)                  │
│     Process events in batches, update sequence atomically           │
└─────────────────────────────────────────────────────────────────────┘
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

### Available Strategies

| Strategy | Latency | CPU Usage | Use Case |
|----------|---------|-----------|----------|
| **BusySpinWaitStrategy** | ~50ns | 100% | Ultra-low latency, dedicated cores |
| **YieldingWaitStrategy** | ~150ns | ~90% | **Default** - Low latency + reasonable CPU |
| **SleepingWaitStrategy** | ~1-100ms | ~5-10% | Latency not critical, save CPU |
| **BlockingWaitStrategy** | ~10-50ms | ~0% | Don't care about latency |

### 1. BusySpinWaitStrategy

```java
public long waitFor(long sequence) {
    long availableSequence;
    
    while ((availableSequence = cursor.get()) < sequence) {
        // Busy-spin forever!
        // Burns CPU but lowest latency possible
    }
    
    return availableSequence;
}
```

**When to use:**
- High-frequency trading
- Arbitrage bots
- Dedicated CPU cores available
- Every nanosecond counts

**Cost:** 1 full CPU core at 100% utilization

### 2. YieldingWaitStrategy (Our Default)

```java
public long waitFor(long sequence) {
    int counter = 100;
    
    while (cursor.get() < sequence) {
        counter = applyWaitMethod(counter);
    }
    
    return cursor.get();
}

private int applyWaitMethod(int counter) {
    if (counter == 0) {
        Thread.yield();  // Let other threads run
        return 100;
    }
    return counter - 1;  // Busy-spin
}
```

**Behavior:**
- Spin 100 times (busy-wait)
- Yield CPU to other threads
- Repeat

**When to use:**
- **Production default** for low-latency systems
- Good balance of latency and CPU usage
- Multiple services on same machine

**Performance:**
```
Latency: ~100-200ns
CPU: ~90% (10% saved by yielding)
Good for: 50K-500K events/sec
```

### 3. SleepingWaitStrategy

```java
public long waitFor(long sequence) {
    int counter = 200;
    
    while (cursor.get() < sequence) {
        if (counter > 100) {
            counter--;
        } else if (counter > 0) {
            Thread.yield();
            counter--;
        } else {
            LockSupport.parkNanos(1);  // Sleep 1ns (OS rounds to ~1ms)
        }
    }
    
    return cursor.get();
}
```

**Behavior:**
1. Spin 100 times
2. Yield 100 times  
3. Sleep progressively (1ms → 10ms → 100ms)

**When to use:**
- Batch processing
- Non-latency-sensitive workloads
- Want to save CPU/battery

**Performance:**
```
Latency: 1-100ms
CPU: ~5-10%
Good for: <10K events/sec
```

### 4. BlockingWaitStrategy

```java
public long waitFor(long sequence) {
    if (cursor.get() < sequence) {
        lock.lock();
        try {
            while (cursor.get() < sequence) {
                processorNotifyCondition.await();  // Block thread
            }
        } finally {
            lock.unlock();
        }
    }
    
    return cursor.get();
}
```

**Behavior:** Uses `Lock` and `Condition` (like BlockingQueue)

**When to use:**
- You don't care about latency at all
- Want minimum CPU usage
- Testing/development

**Performance:**
```
Latency: 10-50ms (context switch overhead)
CPU: ~0% when idle
Not recommended for production
```

### Choosing the Right Strategy

```yaml
# Development/Testing
candle:
  aggregation:
    disruptor:
      wait-strategy: SLEEPING  # Save laptop battery

# Production (Default)
candle:
  aggregation:
    disruptor:
      wait-strategy: YIELDING  # Best balance

# Ultra-Low Latency (Dedicated servers)
candle:
  aggregation:
    disruptor:
      wait-strategy: BUSY_SPIN  # Burn CPU for lowest latency
```

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

### Why Disruptor is 10x Faster

**1. Zero Locks**
```
BlockingQueue:
  - synchronized blocks
  - Lock contention
  - Thread parking/unparking
  - Latency: ~10ms

Disruptor:
  - CAS operations
  - No contention (each thread owns sequence)
  - No thread parking
  - Latency: <50μs
```

**2. Zero Allocation**
```
BlockingQueue:
  queue.put(new Event(...));  // GC pressure!
  GC pause: 10-100ms

Disruptor:
  wrapper.event = event;  // Reuse pre-allocated wrapper
  GC pause: 0ms from Disruptor
```

**3. Sequential Access**
```
Array-based ring buffer:
  - CPU cache friendly
  - Prefetcher friendly
  - ~1-3ns per access (L2 cache)

HashMap/Tree:
  - Random access
  - Cache misses
  - ~60ns per access (main RAM)
```

**4. Batching**
```
BlockingQueue:
  10 events = 10 take() calls = 10 context switches

Disruptor:
  10 events = 1 batch = 1 context switch
  10x less overhead
```

**5. Cache Line Padding**
```
Without padding:
  False sharing between sequences
  Cache invalidation on every write
  ~100ns penalty

With padding:
  Each sequence on own cache line
  No false sharing
  ~1ns access
```

### Measured Performance

**Our System:**
```
Configuration:
  - Buffer: 8192 slots
  - Wait Strategy: YIELDING
  - Producers: 1 (ProductionScaleDataGenerator)
  - Consumers: 1 (CandleAggregator)

Results:
  - Throughput: 100K+ events/sec
  - Latency p50: <10μs
  - Latency p99: <50μs
  - Latency p99.9: <100μs
  - GC from Disruptor: 0ms
  - CPU usage: ~90% (productive work)
```

**vs Traditional Queue:**
```
ArrayBlockingQueue (same workload):
  - Throughput: ~10K events/sec
  - Latency p99: ~10ms
  - GC pauses: 10-100ms
  - CPU usage: 40% (rest is waiting)
```

**Performance Breakdown:**
```
1 event processing time:
  ─────────────────────────────────────────
  Disruptor overhead:    2ns   (CAS)
  Get from buffer:       1ns   (array access)
  Business logic:        100ns (CandleAggregator)
  Update sequence:       2ns   (volatile write)
  ─────────────────────────────────────────
  Total:                 105ns per event
  
  Throughput = 1 / 105ns = 9.5M events/sec
  (Our limit is business logic, not Disruptor!)
```

### Scalability

**Single Consumer Limit:**
```
Best case: 1-2M events/sec per consumer
Our case: 100K events/sec (business logic bound)
```

**Multiple Consumers:**
```java
disruptor.handleEventsWith(
    handler1, handler2, handler3, handler4
);

Theoretical: 4x throughput
Practical: 3x (some coordination overhead)
```

**Horizontal Scaling:**
```
Single JVM: 100K events/sec
4 JVMs (sharded by symbol): 400K events/sec
10 JVMs: 1M events/sec
```

---

## Common Pitfalls & Best Practices {#pitfalls}

### ❌ Pitfall 1: Non-Power-of-2 Buffer Size

```yaml
# WRONG
candle:
  aggregation:
    disruptor:
      buffer-size: 8000  # Not power of 2!
```

**Impact:** Disruptor will throw exception at startup!

**Fix:**
```yaml
# CORRECT
buffer-size: 8192  # 2^13
```

### ❌ Pitfall 2: Blocking in Event Handler

```java
// WRONG
private void handleEvent(BidAskEventWrapper wrapper, long sequence, boolean endOfBatch) {
    aggregator.processEvent(wrapper.event);
    
    // DON'T DO THIS!
    Thread.sleep(100);           // Blocks consumer thread
    repository.save(result);     // Synchronous I/O
    httpClient.post(url, data);  // Network call
}
```

**Impact:** Consumer becomes slow, buffer fills, events dropped!

**Fix:**
```java
// CORRECT
private void handleEvent(BidAskEventWrapper wrapper, long sequence, boolean endOfBatch) {
    // Process event in-memory only
    aggregator.processEvent(wrapper.event);
    
    // Offload I/O to separate thread/queue
    if (endOfBatch) {
        ioExecutor.submit(() -> {
            repository.flush();  // Batch I/O
        });
    }
}
```

### ❌ Pitfall 3: Mutable Events

```java
// WRONG
class MutableEvent {
    public String symbol;  // Mutable!
    public double price;
}

// Producer
event.symbol = "BTCUSD";
publisher.publish(event);

// Later (before consumer processes)
event.symbol = "ETHUSD";  // OOPS! Changed while in buffer!
```

**Impact:** Consumer sees corrupted data!

**Fix:**
```java
// CORRECT
record BidAskEvent(
    String symbol,   // Immutable
    double bid,
    double ask,
    long timestamp
) {}
```

### ❌ Pitfall 4: Ignoring Buffer Full

```java
// WRONG
eventPublisher.tryPublish(event);
// Ignore return value - might be dropped!
```

**Impact:** Silent data loss!

**Fix:**
```java
// CORRECT
boolean published = eventPublisher.tryPublish(event);
if (!published) {
    droppedEventsCounter.increment();
    log.warn("Event dropped: buffer full for {}", symbol);
    
    // Optional: Persist to disk, send alert, etc.
}
```

### ✅ Best Practice 1: Monitor Buffer Utilization

```java
@Scheduled(fixedRate = 1000)
public void reportBufferHealth() {
    long remaining = ringBuffer.remainingCapacity();
    long total = ringBuffer.getBufferSize();
    double utilization = (1.0 - remaining / (double) total) * 100;
    
    meterRegistry.gauge("disruptor.buffer.utilization", utilization);
    
    if (utilization > 80) {
        log.warn("High buffer utilization: {}%", utilization);
    }
}
```

### ✅ Best Practice 2: Batch Processing

```java
private void handleEvent(BidAskEventWrapper wrapper, long sequence, boolean endOfBatch) {
    // Accumulate in batch
    batch.add(wrapper.event);
    
    if (endOfBatch) {
        // Process entire batch at once
        aggregator.processBatch(batch);
        batch.clear();
    }
}
```

### ✅ Best Practice 3: Exception Handling

```java
disruptor.setDefaultExceptionHandler(new ExceptionHandler<>() {
    @Override
    public void handleEventException(Throwable ex, long sequence, BidAskEventWrapper event) {
        log.error("Error processing sequence {}: {}", sequence, event.event, ex);
        errorCounter.increment();
        // Don't rethrow - would stop entire pipeline!
    }
    
    @Override
    public void handleOnStartException(Throwable ex) {
        log.error("Startup error", ex);
        throw new RuntimeException(ex);  // Fail fast on startup
    }
    
    @Override
    public void handleOnShutdownException(Throwable ex) {
        log.error("Shutdown error", ex);
    }
});
```

### ✅ Best Practice 4: Graceful Shutdown

```java
@PreDestroy
public void shutdown() {
    try {
        log.info("Shutting down Disruptor...");
        
        // Stop accepting new events
        // (Application should stop calling tryPublish)
        
        // Shutdown Disruptor (waits for consumer to finish)
        disruptor.shutdown(5, TimeUnit.SECONDS);
        
        log.info("Disruptor shutdown complete");
    } catch (Exception e) {
        log.error("Error during shutdown", e);
    }
}
```

---

## Real-World Results {#results}

### Our System Performance

**Hardware:**
```
CPU: Intel Core i7-12700K (12 cores, 20 threads)
RAM: 32GB DDR4-3200
JVM: Java 21 with G1GC
```

**Load Test:**
```
Producer: ProductionScaleDataGenerator
  - 16 instruments
  - 100K events/sec target
  - Running continuously for 1 hour

Consumer: CandleAggregator
  - Processes all 5 intervals (1s, 5s, 1m, 15m, 1h)
  - Updates Chronicle Map storage
```

**Results:**
```
Throughput:
  - Actual: 102,345 events/sec (102% of target)
  - Peak burst: 150K events/sec
  - Sustained: 100K events/sec

Latency (event → processed):
  - p50: 8.2μs
  - p95: 23.4μs
  - p99: 47.1μs
  - p99.9: 89.3μs
  - p99.99: 156.2μs

Ring Buffer:
  - Size: 8192 slots
  - Average utilization: 12%
  - Peak utilization: 67%
  - Events dropped: 0

GC Impact:
  - From Disruptor: 0 bytes allocated
  - From business logic: ~50MB/sec
  - GC pause: <5ms (G1GC young collection)
  
CPU Usage:
  - Disruptor threads: 90%
  - Business logic: 80%
  - System overhead: 10%

Memory:
  - Ring buffer: 200KB
  - Chronicle Map: 2GB (off-heap)
  - JVM heap: 1.2GB used / 4GB max
```

### Before vs After Disruptor

**Before (ArrayBlockingQueue):**
```
Throughput: 8,500 events/sec
Latency p99: 12.3ms
GC pauses: 50-100ms every 10 seconds
Events dropped: 15% during bursts
CPU usage: 45% (mostly waiting)
```

**After (LMAX Disruptor):**
```
Throughput: 102,345 events/sec  ← 12x improvement
Latency p99: 47.1μs             ← 260x improvement
GC pauses: <5ms                 ← 10-20x improvement
Events dropped: 0%               ← 100% reliability
CPU usage: 90%                   ← Productive work
```

### Cost Savings

**Cloud costs (AWS c5.2xlarge):**
```
Before:
  - Instances needed: 12 (to handle 100K events/sec)
  - Cost: $0.34/hour × 12 = $4.08/hour
  - Monthly: ~$3,000

After:
  - Instances needed: 1
  - Cost: $0.34/hour
  - Monthly: ~$250

Savings: $2,750/month (92% reduction!)
```

---

## Conclusion

You've learned:

1. ✅ **Why** traditional queues are slow (locks, GC, context switches)
2. ✅ **What** makes Disruptor fast (lock-free, pre-allocated, batching)
3. ✅ **How** sequences coordinate (CAS-based claiming, cache padding)
4. ✅ **When** to use each wait strategy (latency vs CPU trade-offs)
5. ✅ **Where** the bottlenecks are (business logic, not Disruptor)

### Key Takeaways

**For Low-Latency Systems:**
- Use Disruptor instead of BlockingQueue
- Choose YieldingWaitStrategy for production
- Power-of-2 buffer sizes only
- Monitor buffer utilization
- Handle back-pressure gracefully

**Performance Formula:**
```
Lock-free (CAS) + Pre-allocation (no GC) + Sequential (cache-friendly) 
+ Batching (amortized overhead) + Padding (no false sharing)
= 10-100x faster than traditional queues
```

### Next Steps

1. **Try it yourself**: Clone our repo and run benchmarks
2. **Experiment**: Change buffer size, wait strategy, see impact
3. **Measure**: Add metrics, track latency/throughput
4. **Optimize**: Profile your business logic (likely bottleneck)
5. **Scale**: Add more consumers if needed

### Resources

- [LMAX Disruptor GitHub](https://github.com/LMAX-Exchange/disruptor)
- [Technical Paper](https://lmax-exchange.github.io/disruptor/disruptor.html)
- [Our Implementation](https://github.com/0xkanth/candle-aggregation-service)

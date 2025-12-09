# Candle Aggregation System - Technical Explanation

## 1. What Are Candles?

A **candle** aggregates price movements over a fixed time window.

Instead of storing every tick:
```
10:00:00.100 - BTC: $50,000
10:00:00.200 - BTC: $50,050
... (thousands more)
```

We create one candle per window:
```
10:00:00 to 10:01:00 - BTC M1 Candle:
  Open:  $50,000 (first price)
  High:  $50,100 (max price)
  Low:   $49,980 (min price)
  Close: $50,050 (last price)
  Volume: 1,234
```

---

## 2. Time Window Alignment

### The Problem

Without alignment, each event creates its own window:
```
Event A: 10:37:23.123 → Window: 10:37:23.123
Event B: 10:37:23.456 → Window: 10:37:23.456
Result: 2 candles instead of 1! ❌
```

### The Solution

**Alignment Formula:**
```java
windowStart = (timestamp / intervalMillis) * intervalMillis
```

**What it does:** Rounds timestamp DOWN to nearest interval boundary.

**Example (M1 = 60,000ms):**
```
Event: 10:37:23.456

Step 1: 1733529443456 / 60000 = 28892157.391
Step 2: Floor to integer: 28892157
Step 3: 28892157 * 60000 = 1733529420000
Result: 10:37:00.000 ✓
```

**All events in 10:37:xx align to `10:37:00.000`.**

---

## 3. Supported Intervals

```java
S1  (1 second)   = 1,000 ms
S5  (5 seconds)  = 5,000 ms
M1  (1 minute)   = 60,000 ms
M15 (15 minutes) = 900,000 ms
H1  (1 hour)     = 3,600,000 ms
```

**One event updates 5 candles simultaneously** (one per interval).

---

## 4. Event Processing - Three Cases

### Case 1: Same Window (Update OHLC)

```
Current window: 10:37:00.000
Event window:   10:37:00.000
Action: Update open/high/low/close ✓
```

### Case 2: New Window (Persist & Create)

```
Current window: 10:37:00.000
Event window:   10:38:00.000
Action:
  1. Persist old candle (10:37:00)
  2. Create new candle (10:38:00)
```

### Case 3: Late Event (Reopen or Drop)

```
Current window: 10:38:00.000
Event window:   10:37:00.000 (older!)

Calculate lag:
  lag = currentWindow - eventTimestamp

If lag ≤ tolerance (30s):
  → Reopen old candle, update ✓

If lag > tolerance:
  → DROP event ✗
```

**Why drop?** Reopening old candles is expensive. Beyond tolerance = too late.

---

## 5. Multi-Interval Example

```java
Event: timestamp = 10:37:25.456, price = $50,000

S1:  windowStart = (10:37:25.456 / 1000) * 1000    = 10:37:25.000
S5:  windowStart = (10:37:25.456 / 5000) * 5000    = 10:37:20.000
M1:  windowStart = (10:37:25.456 / 60000) * 60000  = 10:37:00.000
M15: windowStart = (10:37:25.456 / 900000) * 900000 = 10:30:00.000
H1:  windowStart = (10:37:25.456 / 3600000) * 3600000 = 10:00:00.000
```

**Same event, 5 different windows.**

---

## 6. Processing Logic

```java
long eventWindow = alignTimestamp(event.timestamp);
long currentWindow = activeCandle.getWindowStart();

if (eventWindow == currentWindow) {
    // CASE 1: Same window
    updateOHLC(event);
    
} else if (eventWindow > currentWindow) {
    // CASE 2: New window
    persistCandle(activeCandle);
    createNewCandle(eventWindow, event);
    
} else {
    // CASE 3: Late event
    long lag = currentWindow - event.timestamp;
    
    if (lag <= toleranceMillis) {
        reopenCandle(eventWindow);
        updateOHLC(event);
    } else {
        dropEvent(event);
    }
}
```

---

## 7. Why Events Get Dropped

**The timestamp field = WHEN EVENT WAS CREATED, not when it arrived.**

```
Event created:  10:37:55.000
Network delay:  8 seconds
Arrives at:     10:38:03.000

For S5 interval:
  Event window:   10:37:55.000
  Current window: 10:38:00.000
  Lag: 8,000 ms > 5,000 ms tolerance
  → DROPPED ✗
```

Network/processing delays cause out-of-order arrival.

---

## 8. Visual Timeline

```
Time:     10:00:00    10:01:00    10:02:00
          |           |           |
M1:       [- Win1  -][- Win2  -][- Win3  -]

Events (by timestamp):
  A: 10:00:15 → Win1 (update)
  B: 10:00:45 → Win1 (update)
  C: 10:01:10 → Win2 (new)
  D: 10:00:20 → Late!
  
Event D processing:
  timestamp = 10:00:20
  window = 10:00:00
  currentWindow = 10:01:00
  lag = 40,000 ms
  
  tolerance = 30,000 ms?
    40,000 > 30,000 → DROP ✗
  
  tolerance = 60,000 ms?
    40,000 < 60,000 → REOPEN ✓
```

---

## 9. Same Event, Different Behavior Per Interval

```java
// State at 10:38:05.000:
activeCandles["BTC-S1"]  = { window: 10:38:05.000 }
activeCandles["BTC-M1"]  = { window: 10:38:00.000 }
activeCandles["BTC-H1"]  = { window: 10:00:00.000 }

// Late event arrives:
event.timestamp = 10:37:58.500

S1:  eventWindow = 10:37:58.000 → LATE (7s ago)
M1:  eventWindow = 10:37:00.000 → LATE (1min ago)
H1:  eventWindow = 10:00:00.000 → SAME WINDOW ✓
```

**Smaller intervals = higher chance of late events.**

---

## 10. Key Takeaways

1. **Alignment:** `(timestamp / interval) * interval` snaps to boundaries
2. **Three Cases:** Same (update), New (persist+create), Late (reopen/drop)
3. **Multi-Interval:** One event → 5 candles
4. **Late Events:** Dropped if lag > tolerance
5. **Timestamp:** Event creation time, not arrival time

---

## 11. Configuration

```yaml
candle:
  aggregation:
    late-event-tolerance-millis: 30000  # 30 seconds
    intervals: S1, S5, M1, M15, H1
```

**Tolerance tradeoff:**
- Higher → More accurate, more reopens
- Lower → Faster, fewer reopens

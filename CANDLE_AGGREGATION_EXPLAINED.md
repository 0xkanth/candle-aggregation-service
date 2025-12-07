# Candle Aggregation System - Technical Explanation

## 1. What Are Candles?

A **candle** is an aggregation of price movements over a fixed time period.

Instead of storing every single price tick:
```
10:00:00.100 - BTC: $50,000
10:00:00.200 - BTC: $50,050
10:00:00.300 - BTC: $49,980
10:00:00.400 - BTC: $50,100
... (thousands more)
```

We aggregate them into one candle:
```
10:00:00 to 10:01:00 - BTC Candle:
  Open:  $50,000 (first price in the window)
  High:  $50,100 (highest price in the window)
  Low:   $49,980 (lowest price in the window)
  Close: $50,050 (last price in the window)
  Volume: 1,234   (number of events)
```

## 2. Time Window Alignment - The Core Concept

### The Problem Without Alignment

Without alignment, each event would create its own window:

```java
Event A arrives: timestamp = 10:37:23.123
→ Create candle for window starting at 10:37:23.123

Event B arrives: timestamp = 10:37:23.456
→ Create candle for window starting at 10:37:23.456

Event C arrives: timestamp = 10:37:23.789
→ Create candle for window starting at 10:37:23.789

Result: 3 separate candles instead of 1! ❌
```

### The Solution: Alignment Formula

**The formula:**
```java
public long alignTimestamp(long timestamp) {
    return (timestamp / intervalPeriodInMilliseconds) * intervalPeriodInMilliseconds;
}
```

Where:
- `timestamp` = Event's timestamp in epoch milliseconds (e.g., 1733529443456)
- `intervalPeriodInMilliseconds` = Duration of the interval in milliseconds:
  - S1 = 1,000 ms (1 second)
  - S5 = 5,000 ms (5 seconds)
  - M1 = 60,000 ms (1 minute = 60 seconds × 1000)
  - M15 = 900,000 ms (15 minutes = 15 × 60 × 1000)
  - H1 = 3,600,000 ms (1 hour = 60 × 60 × 1000)

**What this does:** Rounds DOWN to the nearest interval boundary.

### The Rationale: Why This Formula Works

**The goal:** Group events into fixed-duration buckets.

**The problem:** Timestamps are continuous (milliseconds tick forever), but we need discrete windows.

**The insight:** Use integer division to "floor" timestamps to bucket boundaries.

#### Step-by-Step Reasoning

```java
// Let's say we want 1-minute buckets
// intervalPeriod = 60,000 ms

// Question: Which 1-minute bucket does timestamp 10:37:23.456 belong to?
// Answer: The bucket starting at 10:37:00.000

// How do we calculate this?

// Step 1: Convert timestamp to "how many complete intervals since epoch"
timestamp / intervalPeriod = number of complete intervals (with decimals)

Example:
10:37:23.456 = 1733529443456 ms since epoch
1733529443456 / 60000 = 28892157.391 intervals

// Step 2: Drop the decimal part (integer division)
// This gives us: "How many COMPLETE intervals have passed?"
28892157.391 → 28892157 complete intervals

// Step 3: Convert back to milliseconds
28892157 intervals × 60000 ms/interval = 1733529420000 ms
= 10:37:00.000

// The decimal .391 represented the 23.456 seconds into the current interval
// By dropping it, we get the START of the interval!
```

#### Visual Explanation

```
Timeline (1-minute intervals):
|---------|---------|---------|---------|
10:35:00  10:36:00  10:37:00  10:38:00  10:39:00
Interval: 28892155  28892156  28892157  28892158

Event at 10:37:23.456:
|---------|---------|---X-----|---------|
                    ↑ 23.456 seconds into interval 28892157
                    
1733529443456 / 60000 = 28892157.391
                                 ^^^
                                 This .391 means "39.1% into the interval"
                                 
Drop decimal: 28892157 (the interval number)
Multiply back: 28892157 × 60000 = 10:37:00.000 (interval start)
```

#### Why Not Just Round?

**You might ask:** Why not round to the nearest interval instead of flooring?

```java
// Rounding would give us:
28892157.391 → 28892157 (rounds down)
28892157.789 → 28892158 (rounds up!) ❌

// Problem:
Event at 10:37:45.000 would round UP to 10:38:00
Event at 10:37:46.000 would round UP to 10:38:00
→ Events from 10:37:xx ending up in 10:38:00 bucket!
→ Buckets would overlap! ❌
```

**Flooring ensures:**
- Every timestamp from 10:37:00.000 to 10:37:59.999 → 10:37:00.000
- Every timestamp from 10:38:00.000 to 10:38:59.999 → 10:38:00.000
- No overlap, clean boundaries ✓

### Real-World Trading System Interval Strategy

#### Why These Specific Intervals?

Our system supports:
```java
S1  = 1 second     // Ultra high-frequency trading (HFT) / tick charts
S5  = 5 seconds    // Scalping strategies
M1  = 1 minute     // Day trading (most common)
M15 = 15 minutes   // Swing trading, technical analysis
H1  = 1 hour       // Position trading, trend analysis
```

**Industry Standard Intervals:**

1. **Seconds (S1, S5):**
   - Used by: HFT firms, market makers
   - Purpose: Detect micro-movements, order book changes
   - Volume: Crypto exchanges, forex during high volatility

2. **Minutes (M1, M5, M15, M30):**
   - Used by: Day traders, retail traders
   - Purpose: Intraday patterns, support/resistance levels
   - Most popular: M1 and M15
   - Reason: Human-friendly timeframes (coffee break = 15 min chart check)

3. **Hours (H1, H4):**
   - Used by: Swing traders, institutional traders
   - Purpose: Daily trends, multi-day strategies
   - Reason: Reduces noise, shows clearer trends

4. **Days (D1), Weeks (W1), Months (MN):**
   - Used by: Long-term investors
   - Purpose: Macro trends, fundamental analysis
   - Not in our system (focus on real-time aggregation)

#### Why Multiple Intervals Simultaneously?

**Trading Reality:** Different strategies need different perspectives.

Example trader workflow:
```
Morning routine:
1. Check H1 chart → Overall trend (bullish/bearish?)
2. Check M15 chart → Entry/exit zones
3. Check M1 chart → Precise entry timing
4. Monitor S1 chart → Execute trade when pattern forms
```

**Technical Analysis Principle:** 
- Higher timeframes = Context (trend direction)
- Lower timeframes = Execution (entry points)

A trader might say:
> "H1 shows bullish trend, M15 shows pullback to support, M1 shows reversal candle pattern → BUY"

**System Design:** Pre-compute all intervals so traders can instantly switch views.

#### Why Not 2 Minutes? Or 7 Minutes?

**Convention over configuration:**

- **Base-60 time system:** Our clocks divide hours into 60 minutes
  - Intervals that divide 60 evenly: 1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30
  - Most popular: 1, 5, 15, 30 (nice round numbers)

- **Base-12 for hours:** 12 hours in half-day
  - Intervals: 1H, 2H, 4H, 12H align with day/night cycles

- **Fibonacci-inspired:** Some platforms offer 3min, 8min, 13min (Fibonacci numbers)
  - But not standard

**Industry consensus:** Everyone uses 1/5/15/30/60 to ensure:
- Charts look the same across platforms (TradingView, Binance, Bloomberg)
- Indicators are comparable (RSI on 15min means same everywhere)
- Educational materials are universal

#### Crypto-Specific Considerations

**Why we focus on seconds/minutes:**
- Crypto markets: 24/7 (never close)
- High volatility: BTC can move 5% in minutes
- High frequency: Binance processes 100K+ trades/minute
- Retail-heavy: Individual traders need minute-level precision

**Traditional stocks:**
- Market hours: 9:30 AM - 4:00 PM (6.5 hours)
- Lower volatility: S&P 500 moves 1-2% per day
- Lower frequency: NYSE ~100K trades/day across all stocks
- Focus: H1, D1, W1 intervals

**System optimizes for crypto/forex:** Hence S1, S5 (not common in stock systems).

## 3. Real Example Walkthrough

### Using M1 (1-minute interval)

```java
// M1 interval = 60,000 milliseconds

Event timestamp: 10:37:23.456
Convert to epoch millis: 1733529443456

Step 1: Divide by interval
1733529443456 / 60000 = 28892157.391 (with decimals)

Step 2: Integer division drops decimals
28892157.391 → 28892157 (integer)

Step 3: Multiply back
28892157 * 60000 = 1733529420000

Step 4: Convert back to time
1733529420000 = 10:37:00.000

Result: Event at 10:37:23.456 belongs to window 10:37:00.000 ✓
```

### Why This Works

```java
// ALL events in the same minute align to the same window:

10:37:00.000 / 60000 = 28892157.00000 → 28892157 * 60000 = 10:37:00.000
10:37:00.001 / 60000 = 28892157.00001 → 28892157 * 60000 = 10:37:00.000
10:37:15.555 / 60000 = 28892157.25925 → 28892157 * 60000 = 10:37:00.000
10:37:30.000 / 60000 = 28892157.50000 → 28892157 * 60000 = 10:37:00.000
10:37:59.999 / 60000 = 28892157.99998 → 28892157 * 60000 = 10:37:00.000

10:38:00.000 / 60000 = 28892158.00000 → 28892158 * 60000 = 10:38:00.000 ← NEW WINDOW!
```

**Integer division truncates decimals, effectively "flooring" to the start of the interval.**

## 4. Multiple Intervals - Same Event, Different Windows

Our system supports 5 intervals simultaneously:

```java
public enum Interval {
    S1(1_000L),      // 1 second
    S5(5_000L),      // 5 seconds
    M1(60_000L),     // 1 minute
    M15(900_000L),   // 15 minutes
    H1(3_600_000L);  // 1 hour
}
```

### Example: One Event Updates 5 Candles

```java
Event arrives:
  Symbol: "BTC-USD"
  Price: $50,000
  Timestamp: 10:37:23.456 (1733529443456 ms)

Processing S1 (1 second):
  windowStart = (1733529443456 / 1000) * 1000
              = 1733529443000
              = 10:37:23.000 ✓
  Key: "BTC-USD-S1"
  Candle window: 10:37:23.000 to 10:37:23.999

Processing S5 (5 seconds):
  windowStart = (1733529443456 / 5000) * 5000
              = 1733529440000
              = 10:37:20.000 ✓
  Key: "BTC-USD-S5"
  Candle window: 10:37:20.000 to 10:37:24.999

Processing M1 (1 minute):
  windowStart = (1733529443456 / 60000) * 60000
              = 1733529420000
              = 10:37:00.000 ✓
  Key: "BTC-USD-M1"
  Candle window: 10:37:00.000 to 10:37:59.999

Processing M15 (15 minutes):
  windowStart = (1733529443456 / 900000) * 900000
              = 1733529000000
              = 10:30:00.000 ✓
  Key: "BTC-USD-M15"
  Candle window: 10:30:00.000 to 10:44:59.999

Processing H1 (1 hour):
  windowStart = (1733529443456 / 3600000) * 3600000
              = 1733529600000
              = 10:00:00.000 ✓
  Key: "BTC-USD-H1"
  Candle window: 10:00:00.000 to 10:59:59.999
```

**One event updates 5 different candles, each aligned to its interval!**

## 5. Critical Concept: Same Event, Different Outcomes Per Interval

### The Key Insight

**The SAME event can:**
- Update an existing window in one interval (CASE 1)
- Create a new window in another interval (CASE 2)
- Be a late event in yet another interval (CASE 3)

**All simultaneously!** Because each interval has its own independent current window.

### Real Example: One Event, Three Different Behaviors

```java
// System State at 10:37:25.000:

activeCandles["BTC-USD-S1"] = { windowStart: 10:37:25.000 }  // 1-second candle
activeCandles["BTC-USD-M1"] = { windowStart: 10:37:00.000 }  // 1-minute candle
activeCandles["BTC-USD-H1"] = { windowStart: 10:00:00.000 }  // 1-hour candle

// Event arrives:
Event {
    timestamp: 10:37:25.456
    price: $50,000
}

// Processing for S1 interval (1 second):
eventWindow = align(10:37:25.456) = 10:37:25.000
currentWindow = 10:37:25.000
eventWindow == currentWindow ✓
→ CASE 1: SAME WINDOW - Update existing candle

// Processing for M1 interval (1 minute):
eventWindow = align(10:37:25.456) = 10:37:00.000
currentWindow = 10:37:00.000
eventWindow == currentWindow ✓
→ CASE 1: SAME WINDOW - Update existing candle

// Processing for H1 interval (1 hour):
eventWindow = align(10:37:25.456) = 10:00:00.000
currentWindow = 10:00:00.000
eventWindow == currentWindow ✓
→ CASE 1: SAME WINDOW - Update existing candle
```

**All three intervals: Same window behavior! But wait...**

### Now The Interesting Case

```java
// System State at 10:37:25.000:

activeCandles["BTC-USD-S5"] = { windowStart: 10:37:20.000 }  // 5-second candle
activeCandles["BTC-USD-M1"] = { windowStart: 10:37:00.000 }  // 1-minute candle
activeCandles["BTC-USD-H1"] = { windowStart: 10:00:00.000 }  // 1-hour candle

// Event arrives:
Event {
    timestamp: 10:37:26.123  // ← Just crossed into new 5-second window!
    price: $50,100
}

// Processing for S5 interval (5 seconds):
eventWindow = align(10:37:26.123)
            = (10:37:26.123 / 5000ms) * 5000ms
            = 10:37:25.000  // ← NEW WINDOW!
currentWindow = 10:37:20.000
eventWindow (10:37:25.000) > currentWindow (10:37:20.000) ✓
→ CASE 2: NEW WINDOW
   - Persist old candle (10:37:20.000)
   - Create new candle (10:37:25.000)

// Processing for M1 interval (1 minute):
eventWindow = align(10:37:26.123)
            = (10:37:26.123 / 60000ms) * 60000ms
            = 10:37:00.000  // ← SAME WINDOW
currentWindow = 10:37:00.000
eventWindow == currentWindow ✓
→ CASE 1: SAME WINDOW - Update existing candle

// Processing for H1 interval (1 hour):
eventWindow = align(10:37:26.123)
            = (10:37:26.123 / 3600000ms) * 3600000ms
            = 10:00:00.000  // ← SAME WINDOW
currentWindow = 10:00:00.000
eventWindow == currentWindow ✓
→ CASE 1: SAME WINDOW - Update existing candle
```

**Same event, different behavior per interval!**
- S5: Creates NEW window ✓
- M1: Updates SAME window ✓
- H1: Updates SAME window ✓

### Even More Complex: Late Event in One Interval

```java
// System State at 10:38:05.000:

activeCandles["BTC-USD-S1"] = { windowStart: 10:38:05.000 }  // Latest second
activeCandles["BTC-USD-M1"] = { windowStart: 10:38:00.000 }  // Latest minute
activeCandles["BTC-USD-H1"] = { windowStart: 10:00:00.000 }  // Current hour

// Late event arrives (was delayed in network):
Event {
    timestamp: 10:37:58.500  // ← From previous minute!
    price: $49,950
}

// Processing for S1 interval (1 second):
eventWindow = align(10:37:58.500) = 10:37:58.000
currentWindow = 10:38:05.000
eventWindow (10:37:58.000) < currentWindow (10:38:05.000) ✓
lag = 10:38:05.000 - 10:37:58.500 = 6,500 ms
6,500 ms > 30,000 ms? NO ✓
→ CASE 3: LATE EVENT - Within tolerance, reopen and update

// Processing for M1 interval (1 minute):
eventWindow = align(10:37:58.500) = 10:37:00.000
currentWindow = 10:38:00.000
eventWindow (10:37:00.000) < currentWindow (10:38:00.000) ✓
lag = 10:38:00.000 - 10:37:58.500 = 1,500 ms
1,500 ms > 30,000 ms? NO ✓
→ CASE 3: LATE EVENT - Within tolerance, reopen and update

// Processing for H1 interval (1 hour):
eventWindow = align(10:37:58.500) = 10:00:00.000
currentWindow = 10:00:00.000
eventWindow == currentWindow ✓
→ CASE 1: SAME WINDOW - Update existing candle (NOT late for hourly!)
```

**Same late event:**
- S1: Late event (from 7 seconds ago) ✓
- M1: Late event (from previous minute) ✓
- H1: **NOT late** - still in same hour! ✓

### The Pattern

```
Event timestamp: 10:37:58.500

For different intervals:
┌─────────┬──────────────┬──────────────┬─────────────────┐
│Interval │ Event Window │Current Window│ Behavior        │
├─────────┼──────────────┼──────────────┼─────────────────┤
│ S1      │ 10:37:58.000 │ 10:38:05.000 │ LATE EVENT      │
│ S5      │ 10:37:55.000 │ 10:38:05.000 │ LATE EVENT      │
│ M1      │ 10:37:00.000 │ 10:38:00.000 │ LATE EVENT      │
│ M15     │ 10:30:00.000 │ 10:30:00.000 │ SAME WINDOW     │
│ H1      │ 10:00:00.000 │ 10:00:00.000 │ SAME WINDOW     │
└─────────┴──────────────┴──────────────┴─────────────────┘
```

**The smaller the interval, the more likely an event is "late"!**

### Why This Matters

```java
// Each interval maintains its OWN state independently

// S1 candles rotate every 1 second
// M1 candles rotate every 60 seconds
// H1 candles rotate every 3600 seconds

// An event from 30 seconds ago:
// - Definitely late for S1 (30 windows have passed!)
// - Maybe late for M1 (if we've crossed minute boundary)
// - Probably NOT late for H1 (still in same hour)
```

### Code Implementation

```java
// From CandleAggregator.java line 81-87

public void processEvent(BidAskEvent event) {
    double price = event.midPrice();
    
    // Process for all intervals - EACH INDEPENDENTLY!
    for (Interval interval : Interval.values()) {
        processForInterval(event.symbol(), interval, price, event.timestamp());
    }
}

// Each call to processForInterval() has:
// - Its own key: "BTC-USD-S1", "BTC-USD-M1", etc.
// - Its own current window state
// - Its own decision: same/new/late
```

**This is the magic of multi-interval aggregation!**

### Visual Timeline: One Event, Multiple Outcomes

```
Time:      10:37:20    10:37:25    10:37:30    10:38:00    10:38:05
           |           |           |           |           |

S5 Windows:  [-----W1-----][-----W2-----][-----W3-----][-
           10:37:20     10:37:25     10:37:30     10:37:35

M1 Windows:  [----------------- W1 -----------------][---- W2 ----
           10:37:00                             10:38:00

H1 Windows:  [-------------------------------- W1 ----------------
           10:00:00


Event Arrives at 10:37:26.123 (price=$50,100)
           |           |           ↓           |           |

S5:        [-----W1-----][-----W2-----][-----W3-----]
                        ↑ Event aligns here
           Current window: 10:37:20
           Event window:   10:37:25
           DECISION: CREATE NEW WINDOW (W2) ✓

M1:        [----------------- W1 -----------------]
                               ↑ Event aligns here
           Current window: 10:37:00
           Event window:   10:37:00
           DECISION: UPDATE SAME WINDOW (W1) ✓

H1:        [-------------------------------- W1 -------
                               ↑ Event aligns here
           Current window: 10:00:00
           Event window:   10:00:00
           DECISION: UPDATE SAME WINDOW (W1) ✓


Later... Event Arrives at 10:38:05.000, but event has timestamp 10:37:58.500
           |           |           |           ↓ arrives  |

S5:                                            [-----W?-----]
                                                     ↑ Event should be here
           Event window:   10:37:55 (belongs to old window!)
           Current window: 10:38:05
           DECISION: LATE EVENT - reopen 10:37:55 window ✓

M1:                                        [---- W2 ----]
                                                 ↑ Event should be here
           Event window:   10:37:00 (belongs to old window!)
           Current window: 10:38:00
           DECISION: LATE EVENT - reopen 10:37:00 window ✓

H1:        [-------------------------------- W1 -----------------
                                                 ↑ Event aligns here
           Event window:   10:00:00
           Current window: 10:00:00
           DECISION: SAME WINDOW - still in the same hour! ✓
```

### The Key Difference

**Small intervals (S1, S5):**
- Windows rotate frequently
- Higher chance of events being "late"
- More sensitive to event ordering

**Large intervals (M15, H1):**
- Windows stay open longer
- Most events fall in current window
- Less sensitive to event ordering

**Example:**
```java
Event from 10 seconds ago:
  S1:  Late (10 windows have passed!)
  S5:  Late (2 windows have passed)
  M1:  Same window (still in same minute)
  M15: Same window (still in same 15-min block)
  H1:  Same window (still in same hour)
```

### Real System Trace: Multi-Interval Processing

```java
// Initial state at 10:37:24.999
activeCandles = {
    "BTC-USD-S1":  { windowStart: 10:37:24.000, open: 50000, high: 50000, low: 50000, close: 50000 }
    "BTC-USD-S5":  { windowStart: 10:37:20.000, open: 49950, high: 50050, low: 49900, close: 50000 }
    "BTC-USD-M1":  { windowStart: 10:37:00.000, open: 49800, high: 50100, low: 49700, close: 50000 }
    "BTC-USD-M15": { windowStart: 10:30:00.000, open: 49500, high: 50200, low: 49400, close: 50000 }
    "BTC-USD-H1":  { windowStart: 10:00:00.000, open: 48000, high: 51000, low: 47500, close: 50000 }
}

// Event arrives
BidAskEvent {
    symbol: "BTC-USD",
    bid: 50095,
    ask: 50105,
    timestamp: 10:37:25.123
}
price = (50095 + 50105) / 2 = 50100

// Process S1 (1 second)
eventWindow = (10:37:25.123 / 1000) * 1000 = 10:37:25.000
currentWindow = 10:37:24.000
10:37:25.000 > 10:37:24.000 → NEW WINDOW!

Actions:
  1. Persist old candle: save { 10:37:24.000, 50000, 50000, 50000, 50000 } to Chronicle Map
  2. Create new candle: { 10:37:25.000, 50100, 50100, 50100, 50100 }
  3. Update activeCandles["BTC-USD-S1"] = new candle

// Process S5 (5 seconds)
eventWindow = (10:37:25.123 / 5000) * 5000 = 10:37:25.000
currentWindow = 10:37:20.000
10:37:25.000 > 10:37:20.000 → NEW WINDOW!

Actions:
  1. Persist old candle: save { 10:37:20.000, 49950, 50050, 49900, 50000 } to Chronicle Map
  2. Create new candle: { 10:37:25.000, 50100, 50100, 50100, 50100 }
  3. Update activeCandles["BTC-USD-S5"] = new candle

// Process M1 (1 minute)
eventWindow = (10:37:25.123 / 60000) * 60000 = 10:37:00.000
currentWindow = 10:37:00.000
10:37:00.000 == 10:37:00.000 → SAME WINDOW!

Actions:
  1. Update existing candle:
     high = max(50100, 50100) = 50100
     close = 50100
  2. activeCandles["BTC-USD-M1"] = { 10:37:00.000, 49800, 50100, 49700, 50100 }

// Process M15 (15 minutes)
eventWindow = (10:37:25.123 / 900000) * 900000 = 10:30:00.000
currentWindow = 10:30:00.000
10:30:00.000 == 10:30:00.000 → SAME WINDOW!

Actions:
  1. Update existing candle:
     high = max(50200, 50100) = 50200
     close = 50100
  2. activeCandles["BTC-USD-M15"] = { 10:30:00.000, 49500, 50200, 49400, 50100 }

// Process H1 (1 hour)
eventWindow = (10:37:25.123 / 3600000) * 3600000 = 10:00:00.000
currentWindow = 10:00:00.000
10:00:00.000 == 10:00:00.000 → SAME WINDOW!

Actions:
  1. Update existing candle:
     high = max(51000, 50100) = 51000
     close = 50100
  2. activeCandles["BTC-USD-H1"] = { 10:00:00.000, 48000, 51000, 47500, 50100 }

// Final state after processing ONE event:
activeCandles = {
    "BTC-USD-S1":  { windowStart: 10:37:25.000, open: 50100, high: 50100, low: 50100, close: 50100 }  ← NEW
    "BTC-USD-S5":  { windowStart: 10:37:25.000, open: 50100, high: 50100, low: 50100, close: 50100 }  ← NEW
    "BTC-USD-M1":  { windowStart: 10:37:00.000, open: 49800, high: 50100, low: 49700, close: 50100 }  ← UPDATED
    "BTC-USD-M15": { windowStart: 10:30:00.000, open: 49500, high: 50200, low: 49400, close: 50100 }  ← UPDATED
    "BTC-USD-H1":  { windowStart: 10:00:00.000, open: 48000, high: 51000, low: 47500, close: 50100 }  ← UPDATED
}

Chronicle Map now has:
  - "BTC-USD-S1-1733529444000"  (old S1 candle at 10:37:24)
  - "BTC-USD-S5-1733529440000"  (old S5 candle at 10:37:20)
  ... (thousands of other completed candles)
```

**Result:** One event caused 2 new windows (S1, S5) and 3 updates (M1, M15, H1)!


## 6. When Is A New Window Created?

### The Logic

```java
// From CandleAggregator.java line 98-132

private void processForInterval(String symbol, Interval interval, double price, long eventTimestamp) {
    String key = createKey(symbol, interval);
    long windowStart = timeWindowManager.getWindowStart(eventTimestamp, interval);
    
    activeCandles.compute(key, (k, candleRef) -> {
        // First event for this symbol-interval
        if (candleRef == null) {
            MutableCandle newCandle = new MutableCandle(symbol, interval, windowStart, price);
            return new AtomicReference<>(newCandle);
        }
        
        MutableCandle currentCandle = candleRef.get();
        
        // CASE 1: Same window - just update
        if (currentCandle.windowStart == windowStart) {
            candleRef.updateAndGet(candle -> {
                candle.update(price);
                return candle;
            });
        } 
        // CASE 2: New window - rotate candle
        else if (timeWindowManager.isNewWindow(eventTimestamp, currentCandle.windowStart, interval)) {
            persistCandle(currentCandle);  // Save old candle
            MutableCandle newCandle = new MutableCandle(symbol, interval, windowStart, price);
            candleRef.set(newCandle);      // Create new candle
        } 
        // CASE 3: Late event - drop or reprocess
        else if (timeWindowManager.isLateEvent(eventTimestamp, currentCandle.windowStart, interval)) {
            if (timeWindowManager.shouldProcessLateEvent(eventTimestamp, currentCandle.windowStart)) {
                handleLateEvent(symbol, interval, price, windowStart);
            } else {
                lateEventsDropped.incrementAndGet();  // DROP IT!
            }
        }
        
        return candleRef;
    });
}
```

### Real Example: New Window Creation

```java
// System state:
activeCandles["BTC-USD-M1"] = {
    windowStart: 10:37:00.000,
    open: 50000,
    high: 50100,
    low: 49900,
    close: 50050
}

// Event A arrives: timestamp = 10:37:45.123, price = $50,200
eventWindow = alignTimestamp(10:37:45.123)
            = 10:37:00.000
currentCandle.windowStart = 10:37:00.000

CASE 1: Same window ✓
eventWindow (10:37:00.000) == currentCandle.windowStart (10:37:00.000)
→ Update existing candle:
  high = max(50100, 50200) = 50200
  close = 50200

// Event B arrives: timestamp = 10:38:12.456, price = $50,300
eventWindow = alignTimestamp(10:38:12.456)
            = 10:38:00.000
currentCandle.windowStart = 10:37:00.000

CASE 2: New window ✓
eventWindow (10:38:00.000) > currentCandle.windowStart (10:37:00.000)
→ isNewWindow() returns true
→ Persist old candle to Chronicle Map
→ Create new candle:
  activeCandles["BTC-USD-M1"] = {
      windowStart: 10:38:00.000,
      open: 50300,
      high: 50300,
      low: 50300,
      close: 50300
  }
```

## 6. When Is An Event Dropped?

### The Late Event Problem

**Key Insight:** Events don't arrive in perfect chronological order!

```java
// Timeline of event CREATION at exchange:

10:00:00.100 - Exchange creates Event A (price=$50,000)
               BidAskEvent { timestamp: 10:00:00.100 }

10:00:01.500 - Exchange creates Event B (price=$50,100)
               BidAskEvent { timestamp: 10:00:01.500 }

// Network delivers OUT OF ORDER!

10:00:02.000 - Our system receives Event B FIRST
               eventWindow = 10:00:00.000
               Create candle for window 10:00:00.000

10:00:03.000 - Processing continues, window advances
               Current window is now 10:01:00.000

10:00:35.000 - Our system receives Event A (arrived 35 seconds late!)
               eventTimestamp = 10:00:00.100
               eventWindow = 10:00:00.000
               currentWindowStart = 10:01:00.000
               
               This is a LATE EVENT!
```

### The Decision Logic

```java
// From TimeWindowManager.java line 67-80

public boolean shouldProcessLateEvent(long eventTimestamp, long currentWindowStart) {
    long lag = currentWindowStart - eventTimestamp;
    
    if (lag <= lateEventToleranceMs) {  // e.g., 30 seconds
        log.debug("Late event within tolerance: lag={}ms", lag);
        return true;  // ✓ Reopen historical candle and update it
    }
    
    log.warn("Dropping late event: lag={}ms exceeds tolerance={}ms", lag, lateEventToleranceMs);
    return false;  // ✗ DROP IT - too old!
}
```

### Real Example: Event Drop Scenario

```java
// Configuration
late.event.tolerance.seconds = 30  // From application-test.properties

// Scenario 1: Late event WITHIN tolerance (ACCEPTED)

Current time: 10:01:00.000
Current window: 10:01:00.000

Late event arrives:
  timestamp: 10:00:50.000  // 10 seconds old
  
Check:
  lag = 10:01:00.000 - 10:00:50.000 = 10,000 ms
  10,000 ms <= 30,000 ms? ✓ YES
  
Action: ACCEPT
  → Find candle for window 10:00:00.000 in Chronicle Map
  → Update its OHLC values
  → Re-save to Chronicle Map

// Scenario 2: Late event BEYOND tolerance (DROPPED)

Current time: 10:01:00.000
Current window: 10:01:00.000

Late event arrives:
  timestamp: 10:00:05.000  // 55 seconds old
  
Check:
  lag = 10:01:00.000 - 10:00:05.000 = 55,000 ms
  55,000 ms <= 30,000 ms? ✗ NO
  
Action: DROP
  → Increment lateEventsDropped counter
  → Log warning: "Dropping late event: lag=55000ms exceeds tolerance=30000ms"
  → Continue processing (don't create new window)
```

### From Your Test Output

```
2025-12-07 00:36:16.220 [disruptor-event-handler-1] WARN  
Dropping late event: lag=9780ms exceeds tolerance=5000ms
```

What happened:
```java
Test publishes late event: 8000 milliseconds late
  currentTime = 00:36:16.220
  lateTimestamp = 00:36:16.220 - 8000ms = 00:36:08.220

System check:
  lag = 00:36:16.220 - 00:36:08.220 = 8,000 ms
  8,000 ms > 5,000 ms (simulator uses 5s, tests use 30s)
  
Result: DROPPED ✓
```


## 7. Complete Event Processing Flow

```
┌─────────────────────────────────────────────────────────┐
│ Event Arrives: BTC-USD, price=$50,000, time=10:37:23.456│
└─────────────────────────────────────────────────────────┘
                        ↓
        ┌───────────────────────────────┐
        │ Calculate Window Start         │
        │ windowStart = align(timestamp) │
        └───────────────────────────────┘
                        ↓
        ┌───────────────────────────────┐
        │ Check Active Candle Exists?    │
        └───────────────────────────────┘
              ↙                    ↘
         NO ✓                    YES ✓
              ↓                        ↓
    ┌────────────────┐     ┌─────────────────────────┐
    │ Create New     │     │ Compare Windows         │
    │ Candle         │     └─────────────────────────┘
    └────────────────┘              ↓
                            ┌───────────────┐
                            │ Same Window?  │
                            └───────────────┘
                                ↙       ↘
                           YES ✓       NO ✗
                                ↓           ↓
                    ┌──────────────┐  ┌────────────┐
                    │ Update OHLC  │  │ New Window?│
                    └──────────────┘  └────────────┘
                                          ↙       ↘
                                     YES ✓       NO ✗
                                          ↓           ↓
                            ┌──────────────────┐  ┌──────────┐
                            │ Persist Old      │  │ Late     │
                            │ Create New       │  │ Event?   │
                            └──────────────────┘  └──────────┘
                                                      ↓
                                          ┌────────────────────┐
                                          │ Within Tolerance?  │
                                          └────────────────────┘
                                              ↙           ↘
                                         YES ✓           NO ✗
                                              ↓               ↓
                                  ┌─────────────────┐  ┌─────────┐
                                  │ Reopen & Update │  │ DROP IT │
                                  └─────────────────┘  └─────────┘
```


## 8. Key Takeaways

### Time Alignment
- **Formula:** `(timestamp / interval) * interval`
- **Purpose:** Snap timestamps to interval boundaries
- **Result:** All events in same interval → same window

### New Window Creation
- **Trigger:** Event's window > current window
- **Action:** Persist old candle, create new candle
- **Example:** Event at 10:38:xx arrives when current window is 10:37:00

### Event Dropping
- **Trigger:** Event's window < current window AND lag > tolerance
- **Reason:** Too expensive to keep reopening old candles
- **Example:** Event from 10:00:05 arrives at 10:01:00 (55s lag > 30s tolerance)

### NOT Dropped
- **Same window:** Event aligns to current window → update OHLC ✓
- **Within tolerance:** Late event but < 30s old → reopen and update ✓
- **Future events:** Event from future → create new window ✓


## 9. Real Code Example from Your System

```java
// From your test: CandleAggregationSteps.java

@When("I publish a late event {int} milliseconds late")
public void iPublishALateEventMillisecondsLate(int delayMs) {
    long currentTime = Instant.now().toEpochMilli();
    long lateTimestamp = currentTime - delayMs;  // ← Artificially old timestamp!
    
    BidAskEvent lateEvent = new BidAskEvent("LATEPAIR", 50000.0, 50000.0, lateTimestamp);
    publisher.publish(lateEvent);
    sleep(200);
}

// Test calls: iPublishALateEventMillisecondsLate(8000)
// Creates event with timestamp 8 seconds in the past
// System checks: Is this event's window older than current window?
// If yes: Is lag > tolerance? If yes: DROP IT!
```

## 10. Visual Timeline Example

```
Time:     10:00:00  10:00:30  10:01:00  10:01:30  10:02:00
          |         |         |         |         |
M1 Windows: [---- Window 1 ----][---- Window 2 ----][---- Window 3 ----]
          10:00:00          10:01:00          10:02:00

Events (by timestamp):
Event A: 10:00:15 ──┐
Event B: 10:00:45 ────┐
Event C: 10:01:10 ────────┐
Event D: 10:00:20 ──────────┐ ← Arrives late! (timestamp < current window)
                    │       │       │       │
                    ↓       ↓       ↓       ↓
Windows:         10:00   10:00   10:01   10:00
                  NEW     UPDATE   NEW    LATE!

Event D processing:
  eventWindow = 10:00:00.000
  currentWindow = 10:01:00.000
  lag = 10:01:00.000 - 10:00:20.000 = 40,000 ms
  
  If tolerance = 30,000 ms:
    40,000 > 30,000 → DROP ✗
  
  If tolerance = 60,000 ms:
    40,000 < 60,000 → REOPEN AND UPDATE ✓
```

## Conclusion

**Three Rules:**
1. **Alignment:** Events snap to interval boundaries (integer division magic)
2. **New Window:** Future events create new candles
3. **Late Events:** Old events get dropped if lag exceeds tolerance

**The timestamp field is WHEN THE EVENT WAS CREATED, not when it arrived at our system.**

This is what causes "late events" - network/processing delays mean events arrive out of chronological order!

# Data Generator Comparison

Two market data generators for different use cases:

| Generator | Use Case | Complexity | Realism |
|-----------|----------|------------|---------|
| **MarketDataSimulator** | Testing/Demos | Simple | Basic |
| **ProductionScaleDataGenerator** | Benchmarking | Advanced | High |

---

## MarketDataSimulator (Simple)

**Price Model:** Random Walk (coin flip up/down)
**Symbols:** 3-5 crypto pairs
**Throughput:** ~100K events/sec
**CPU Usage:** Low

---

## ProductionScaleDataGenerator (Production)

**Price Model:** Geometric Brownian Motion (real market math)
**Symbols:** 16 instruments (crypto, FX, commodities, indices)
**Throughput:** 50K-100K events/sec (configurable)
**CPU Usage:** Medium
**Realism:** High (volatility clustering, mean reversion, dynamic spreads)

### Why It's Faster for Benchmarking

1. **Optimized Event Generation** - Batch processing every 10ms vs 100ms intervals
2. **Lock-Free Market States** - ConcurrentHashMap for parallel symbol updates
3. **Efficient GBM Calculation** - Pre-computed volatility factors
4. **Smart Throughput Control** - Dynamic event distribution across symbols

---

## Quick Comparison

| Feature | MarketDataSimulator | ProductionScaleDataGenerator |
|---------|---------------------|------------------------------|
| **Math Model** | Random walk | Geometric Brownian Motion |
| **Instruments** | 3 crypto | 16 multi-asset |
| **Volatility** | Static | Dynamic clustering |
| **Spreads** | Fixed | Dynamic (volatility-based) |
| **Performance** | ~100K/sec | 50K-100K/sec |
| **Use Case** | Testing | **Production Benchmarking** |

---

## Current Configuration

**ProductionScaleDataGenerator** - ENABLED (default)
- 16 instruments with real-time prices (Dec 7, 2025)
- 100K events/sec target
- Realistic GBM price evolution

❌ **MarketDataSimulator** - DISABLED
- Test class has `@Disabled` annotation
- Won't load unless `simple-mode: true`

**Why?** Prevents ring buffer overflow from both generators running simultaneously.

---

## Market Prices (Updated Dec 7, 2025)

**Cryptocurrencies:**
- BTCUSD: $88,000 | ETHUSD: $2,950 | SOLUSD: $220 | BNBUSD: $875 | ADAUSD: $1.08

**Forex:**
- EURUSD: 1.0550 | GBPUSD: 1.2750 | USDJPY: 149.50 | AUDUSD: 0.6450 | USDCHF: 0.8820

**Commodities:**
- XAUUSD: $2,650 | XAGUSD: $31.50 | WTICRD: $68.50

**Indices:**
- SPX500: 6,050 | NAS100: 21,500 | UK100: 8,350

---

## Configuration

**Enable Production Mode (Default):**
```yaml
candle:
  simulation:
    production-scale: true
    events-per-second: 100000
    symbols: BTCUSD,ETHUSD,SOLUSD,EURUSD,GBPUSD,XAUUSD
```

**Verification:**
```bash
# Check which generator is running
tail -f logs/application.log | grep "generator initialized"

# Monitor throughput
curl -s http://localhost:8080/actuator/metrics/candle.aggregator.events.processed
```

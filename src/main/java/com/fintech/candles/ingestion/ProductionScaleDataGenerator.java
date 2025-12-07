package com.fintech.candles.ingestion;

import com.fintech.candles.domain.BidAskEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production-scale market data generator for benchmarking candle aggregation performance.
 * 
 * @notice This is NOT a simple random number generator - it simulates REAL market behavior
 *         using the same math models that actual stock/crypto prices follow in production.
 * 
 * WHY WE NEED THIS:
 * ----------------
 * Think of this like a stress test for a smart contract:
 * - Just like you'd test a DEX with realistic trade patterns (not just random buys/sells)
 * - We need to test candle aggregation with realistic price movements
 * - Random walk = too simple, doesn't catch real-world edge cases
 * - GBM (Geometric Brownian Motion) = what real markets actually do
 * 
 * WHAT IT SIMULATES:
 * -----------------
 * 1. Multiple asset classes (Crypto, FX, Commodities, Indices)
 *    - Like testing multiple token pairs in a DEX
 * 
 * 2. Realistic price movements (Geometric Brownian Motion)
 *    - Same math as real stock prices: drift (mean reversion) + random shocks
 *    - Analogy: ETH price tends to hover around a "fair value" but gets pushed by news/events
 * 
 * 3. Dynamic spreads (bid-ask gap changes with volatility)
 *    - Like gas prices: higher when network is congested (high volatility)
 *    - Lower when markets are calm (low volatility)
 * 
 * 4. Volatility clustering (volatility stays high after big moves)
 *    - Real markets: after a crash, prices stay jumpy for days
 *    - Like after a major hack, crypto markets stay volatile
 * 
 * PERFORMANCE OPTIMIZATIONS:
 * -------------------------
 * @dev Key optimization: Batch processing every 10ms instead of per-event
 *      - Like batching transactions instead of sending one at a time
 *      - Reduces scheduler overhead, increases throughput
 *      - Target: 50K-100K events/sec (similar to high-frequency DEX volume)
 * 
 * @dev ConcurrentHashMap for market states = lock-free parallel updates
 *      - Like multiple validators processing different transactions simultaneously
 *      - Each symbol can evolve independently without blocking others
 * 
 * @dev AtomicLong counters = thread-safe without synchronized blocks
 *      - Like using atomic operations in Solidity instead of locks
 *      - Prevents race conditions in multi-threaded environment
 * 
 * This is the DEFAULT generator because it reveals real performance bottlenecks
 * that simple random data would miss.
 */
@Component
@ConditionalOnProperty(name = "candle.simulation.production-scale", havingValue = "true", matchIfMissing = true)
public class ProductionScaleDataGenerator {
    
    private static final Logger log = LoggerFactory.getLogger(ProductionScaleDataGenerator.class);
    
    private final DisruptorEventPublisher eventPublisher;
    
    // Configuration
    @Value("${candle.simulation.events-per-second:10000}")
    private int targetEventsPerSecond;
    
    @Value("#{'${candle.simulation.symbols:}'.split(',')}")
    private List<String> configuredSymbols;
    
    private List<String> symbols;
    
    // State tracking
    private final Map<String, MarketState> marketStates = new ConcurrentHashMap<>();
    private final AtomicLong totalEventsGenerated = new AtomicLong(0);
    private final AtomicLong lastReportTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastReportCount = new AtomicLong(0);
    
    // Market instrument definitions (prices updated: Dec 7, 2025)
    private static final List<InstrumentConfig> INSTRUMENTS = List.of(
        // Cryptocurrencies (high volatility, wide spreads)
        new InstrumentConfig("BTCUSD", 88000.0, 0.15, 0.0001, 100),
        new InstrumentConfig("ETHUSD", 2950.0, 0.18, 0.0002, 150),
        new InstrumentConfig("SOLUSD", 220.0, 0.25, 0.0005, 200),
        new InstrumentConfig("BNBUSD", 875.0, 0.20, 0.0003, 120),
        new InstrumentConfig("ADAUSD", 1.08, 0.22, 0.0004, 180),
        
        // Major FX Pairs (low volatility, tight spreads)
        new InstrumentConfig("EURUSD", 1.0550, 0.05, 0.00001, 50),
        new InstrumentConfig("GBPUSD", 1.2750, 0.06, 0.00001, 60),
        new InstrumentConfig("USDJPY", 149.50, 0.05, 0.001, 40),
        new InstrumentConfig("AUDUSD", 0.6450, 0.06, 0.00001, 70),
        new InstrumentConfig("USDCHF", 0.8820, 0.05, 0.00001, 50),
        
        // Commodities (medium volatility, medium spreads)
        new InstrumentConfig("XAUUSD", 2650.0, 0.08, 0.01, 80),
        new InstrumentConfig("XAGUSD", 31.50, 0.10, 0.001, 100),
        new InstrumentConfig("WTICRD", 68.50, 0.12, 0.01, 90),
        
        // Stock Indices (medium volatility, variable spreads)
        new InstrumentConfig("SPX500", 6050.0, 0.10, 0.1, 75),
        new InstrumentConfig("NAS100", 21500.0, 0.12, 0.5, 85),
        new InstrumentConfig("UK100", 8350.0, 0.09, 0.1, 65)
    );
    
    public ProductionScaleDataGenerator(DisruptorEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }
    
    @PostConstruct
    public void initialize() {
        // Set symbols from config or use defaults
        this.symbols = (configuredSymbols == null || configuredSymbols.isEmpty() || configuredSymbols.get(0).isEmpty()) 
            ? getDefaultSymbols() 
            : configuredSymbols;
        
        // Initialize market states
        for (String symbol : this.symbols) {
            InstrumentConfig config = findInstrumentConfig(symbol);
            marketStates.put(symbol, new MarketState(config));
        }
        
        log.info("Production-scale data generator initialized:");
        log.info("  - Symbols: {}", this.symbols);
        log.info("  - Target throughput: {} events/sec", targetEventsPerSecond);
        log.info("  - Market instruments: {}", INSTRUMENTS.size());
    }
    
    /**
     * Main event generation loop - the heart of the stress test.
     * 
     * @notice Runs every 10ms (100 times per second) to simulate high-frequency trading
     * 
     * WHY 10ms BATCHES?
     * ----------------
     * Think about blockchain block times:
     * - Ethereum: 12 seconds per block
     * - Polygon: 2 seconds per block
     * - This: 0.01 seconds (10ms) per batch
     * 
     * We're simulating MUCH faster than blockchains because:
     * 1. Traditional markets trade at microsecond speeds
     * 2. Need to stress-test the candle aggregator at realistic throughput
     * 3. 100K events/sec = similar to Binance/Coinbase peak volumes
     * 
     * BATCHING OPTIMIZATION:
     * ---------------------
     * @dev Instead of scheduling 100,000 individual events:
     *      - Schedule 100 batches per second
     *      - Each batch generates 1,000 events (for 100K target)
     *      - Reduces scheduler overhead by 1000x
     * 
     * Analogy: Like processing transactions in blocks instead of one-by-one.
     *          More efficient, same result.
     * 
     * @dev Performance check: Warns if batch takes >10ms
     *      - Like checking if block validation is too slow
     *      - Helps identify if event generation becomes the bottleneck
     */
    @Scheduled(fixedDelay = 10, initialDelay = 1000)
    public void generateMarketDataBatch() {
        long startTime = System.nanoTime();
        
        // Calculate events per batch (10ms intervals = 100 batches/sec)
        int eventsPerBatch = targetEventsPerSecond / 100;
        
        // Generate events for this batch
        for (int i = 0; i < eventsPerBatch; i++) {
            generateSingleEvent();
        }
        
        // Performance reporting every 5 seconds
        long now = System.currentTimeMillis();
        if (now - lastReportTime.get() >= 5000) {
            reportPerformance();
        }
        
        long duration = System.nanoTime() - startTime;
        if (duration > 10_000_000) { // >10ms
            log.warn("Batch generation took {}ms (target: 10ms)", duration / 1_000_000);
        }
    }
    
    /**
     * Generate a single market data event (one price tick).
     * 
     * @notice This is like creating a single swap event in a DEX
     * 
     * WHAT HAPPENS:
     * ------------
     * 1. Pick a random symbol (like picking a random token pair)
     * 2. Evolve its price using GBM (realistic price movement)
     * 3. Calculate bid/ask spread (like buy/sell slippage)
     * 4. Create BidAskEvent (like emitting a Swap event)
     * 5. Publish to Disruptor ring buffer (like adding to mempool)
     * 
     * @dev Ring buffer can fill up under extreme load
     *      - Like mempool getting full during NFT drops
     *      - We drop events instead of blocking (fail-fast pattern)
     *      - Better to drop data than slow down the entire system
     */
    private void generateSingleEvent() {
        // Select random symbol (weighted by activity)
        String symbol = selectSymbol();
        MarketState state = marketStates.get(symbol);
        
        // Evolve price using Geometric Brownian Motion
        double newMid = state.evolveMidPrice();
        
        // Calculate bid/ask from mid price
        double spread = state.calculateSpread();
        double bid = newMid - (spread / 2.0);
        double ask = newMid + (spread / 2.0);
        
        // Create event (volume is generated internally by the aggregator)
        BidAskEvent event = new BidAskEvent(
            symbol,
            bid,
            ask,
            Instant.now().toEpochMilli()
        );
        
        // Publish to Disruptor
        boolean published = eventPublisher.tryPublish(event);
        
        if (published) {
            totalEventsGenerated.incrementAndGet();
        } else {
            log.warn("Ring buffer full - event dropped for {}", symbol);
        }
    }
    
    /**
     * Select symbol based on weighted activity.
     * More active symbols (lower config.baseVolume) are selected more frequently.
     */
    private String selectSymbol() {
        // Simple uniform distribution for now
        // TODO: Weight by activity level
        int index = ThreadLocalRandom.current().nextInt(symbols.size());
        return symbols.get(index);
    }
    
    /**
     * Report generation performance.
     */
    private void reportPerformance() {
        long now = System.currentTimeMillis();
        long currentCount = totalEventsGenerated.get();
        long elapsed = now - lastReportTime.get();
        long eventsSinceLastReport = currentCount - lastReportCount.get();
        
        double actualThroughput = (eventsSinceLastReport * 1000.0) / elapsed;
        double targetRatio = (actualThroughput / targetEventsPerSecond) * 100.0;
        
        log.info("Market Data Generator Performance:");
        log.info("  - Total events: {}", currentCount);
        log.info("  - Actual throughput: {}/sec", String.format("%.0f", actualThroughput));
        log.info("  - Target throughput: {}/sec", targetEventsPerSecond);
        log.info("  - Achievement: {}%", String.format("%.1f", targetRatio));
        
        // Update for next report
        lastReportTime.set(now);
        lastReportCount.set(currentCount);
    }
    
    /**
     * Find instrument configuration by symbol.
     */
    private static InstrumentConfig findInstrumentConfig(String symbol) {
        return INSTRUMENTS.stream()
            .filter(i -> i.symbol.equals(symbol))
            .findFirst()
            .orElse(new InstrumentConfig(symbol, 100.0, 0.10, 0.01, 100));
    }
    
    /**
     * Get default symbols if none specified.
     */
    private static List<String> getDefaultSymbols() {
        return INSTRUMENTS.stream()
            .map(i -> i.symbol)
            .toList();
    }
    
    /**
     * Configuration for each trading instrument (symbol/pair).
     * 
     * @notice Like defining a token pair in a DEX with its characteristics
     * 
     * WHY DIFFERENT CONFIGS PER INSTRUMENT?
     * ------------------------------------
     * Just like in DeFi:
     * - ETH/USDC pool: high volume, tight spread, medium volatility
     * - SHIB/USDC pool: lower volume, wide spread, extreme volatility
     * 
     * Same in TradFi:
     * - BTCUSD: high volatility (15% annual), wider spreads
     * - EURUSD: low volatility (5% annual), super tight spreads
     * - Gold: medium volatility (8% annual), medium spreads
     * 
     * PARAMETERS EXPLAINED:
     * --------------------
     * @param symbol - Trading pair (BTCUSD, ETHUSD, etc)
     *                 Like "tokenA/tokenB" in Uniswap
     * 
     * @param basePrice - Starting price (updated Dec 7, 2025 to real market values)
     *                    Like initial pool price in AMM
     * 
     * @param volatility - Annual volatility (σ in GBM formula)
     *                     0.15 = 15% expected price swing per year
     *                     Crypto: 15-25% (wild)
     *                     Forex: 5-6% (stable)
     *                     Like implied volatility in options pricing
     * 
     * @param baseSpreadPct - Base spread as % of price
     *                        0.0001 = 0.01% spread
     *                        Like LP fee in Uniswap (0.3% = 0.003)
     * 
     * @param baseVolume - Base volume per tick
     *                     Like average trade size
     *                     Higher = more active instrument
     */
    private static class InstrumentConfig {
        final String symbol;
        final double basePrice;          // Starting price
        final double volatility;         // Annual volatility (σ)
        final double baseSpreadPct;      // Base spread as % of price
        final int baseVolume;            // Base volume per tick
        final int priceScale;            // Decimal places
        
        InstrumentConfig(String symbol, double basePrice, double volatility, 
                        double baseSpreadPct, int baseVolume) {
            this.symbol = symbol;
            this.basePrice = basePrice;
            this.volatility = volatility;
            this.baseSpreadPct = baseSpreadPct;
            this.baseVolume = baseVolume;
            this.priceScale = calculateScale(basePrice);
        }
        
        private int calculateScale(double price) {
            if (price >= 10000) return 2;
            if (price >= 1000) return 2;
            if (price >= 100) return 3;
            if (price >= 10) return 4;
            if (price >= 1) return 5;
            return 6;
        }
    }
    
    /**
     * Market state for each symbol.
     * Tracks current price, volatility, and generates realistic movements.
     */
    private static class MarketState {
        final InstrumentConfig config;
        private double currentMidPrice;
        private double currentVolatility;
        private long lastUpdateTime;
        
        // Microstructure
        private double lastTradePrice;
        private boolean lastWasBuy;
        
        MarketState(InstrumentConfig config) {
            this.config = config;
            this.currentMidPrice = config.basePrice;
            this.currentVolatility = config.volatility;
            this.lastUpdateTime = System.nanoTime();
            this.lastTradePrice = config.basePrice;
            this.lastWasBuy = false;
        }
        
        /**
         * Evolve mid price using Geometric Brownian Motion (GBM).
         * 
         * @notice This is THE CORE algorithm that makes prices realistic
         * 
         * FORMULA: dS = μ·S·dt + σ·S·√dt·dW
         * --------
         * @param dS = price change (what we're calculating)
         * @param μ  = drift (mean reversion force, pulls price back to base)
         * @param σ  = volatility (how crazy the market is)
         * @param S  = current price
         * @param dt = time step
         * @param dW = random gaussian noise (bell curve, not uniform)
         * 
         * WHY THIS MATH?
         * -------------
         * Web3 analogy: Think of ETH price:
         * - DRIFT (μ·S·dt): Like gravity pulling price toward "fair value"
         *   Example: If ETH crashes to $1000 but fair value is $2000,
         *            drift slowly pushes it back up
         * 
         * - DIFFUSION (σ·S·√dt·dW): Random shocks from news/events
         *   Example: "SEC approves ETH ETF" -> big random jump up
         *            "Exchange hack" -> big random jump down
         * 
         * VOLATILITY CLUSTERING:
         * ---------------------
         * @dev After big moves (|dW| > 2 standard deviations), volatility increases
         *      - Like after Terra/Luna crash, all crypto stayed jumpy
         *      - Markets don't instantly calm down - takes time to settle
         *      - We increase volatility 10% after shocks, then slowly decay it
         * 
         * This creates realistic "aftershocks" that simple random walk misses.
         * 
         * @return new mid-market price after this time step
         */
        double evolveMidPrice() {
            long now = System.nanoTime();
            double dt = (now - lastUpdateTime) / 1_000_000_000.0; // seconds
            lastUpdateTime = now;
            
            // Drift (slightly mean-reverting to base price)
            double drift = (config.basePrice - currentMidPrice) * 0.01 * dt;
            
            // Diffusion (random walk)
            double dW = ThreadLocalRandom.current().nextGaussian();
            double diffusion = currentVolatility * currentMidPrice * Math.sqrt(dt) * dW;
            
            // Update price
            currentMidPrice += drift + diffusion;
            
            // Add intraday volatility clustering
            if (Math.abs(dW) > 2.0) {
                currentVolatility = Math.min(config.volatility * 1.5, 
                                            currentVolatility * 1.1);
            } else {
                currentVolatility = config.volatility + 
                    (currentVolatility - config.volatility) * 0.95; // decay
            }
            
            return currentMidPrice;
        }
        
        /**
         * Calculate bid-ask spread based on current market conditions.
         * 
         * @notice Spread = difference between buy and sell prices
         * 
         * WEB3 ANALOGY:
         * ------------
         * Think of Uniswap pool:
         * - Tight spread (0.01%) = deep liquidity, stable prices (USDC/USDT)
         * - Wide spread (0.5%) = shallow liquidity, volatile prices (shitcoins)
         * 
         * DYNAMIC PRICING:
         * ---------------
         * @dev Spread widens when volatility spikes (80%-120% of base)
         *      - Like Uniswap slippage increasing during high volatility
         *      - Market makers protect themselves by widening spreads
         *      - Prevents getting caught in rapid price swings
         * 
         * @dev Random 10% widening (market microstructure)
         *      - Real markets: spreads fluctuate tick-by-tick
         *      - Sometimes a big order temporarily widens the spread
         *      - Like a whale trade temporarily draining liquidity
         * 
         * EXAMPLES:
         * --------
         * - BTC in calm market: 0.01% spread = $8.80 on $88k price
         * - BTC during crash: 0.012% spread = $10.56 (20% wider)
         * - EUR/USD always tight: 0.001% = $0.000105 on 1.0550
         * 
         * @return spread in absolute price terms (not percentage)
         */
        double calculateSpread() {
            // Base spread
            double spread = currentMidPrice * config.baseSpreadPct;
            
            // Widen spread during high volatility
            double volFactor = currentVolatility / config.volatility;
            spread *= (0.8 + 0.4 * volFactor); // 80%-120% of base
            
            // Add bid-ask bounce (market microstructure)
            if (ThreadLocalRandom.current().nextBoolean()) {
                spread *= 1.1; // Slightly wider occasionally
            }
            
            return spread;
        }
        
        /**
         * Generate realistic volume.
         */
        int generateVolume() {
            // Base volume with random variation
            double factor = 0.5 + ThreadLocalRandom.current().nextDouble() * 1.5;
            int volume = (int) (config.baseVolume * factor);
            
            // Higher volume during volatile periods
            if (currentVolatility > config.volatility * 1.2) {
                volume *= 2;
            }
            
            return Math.max(10, volume);
        }
    }
}

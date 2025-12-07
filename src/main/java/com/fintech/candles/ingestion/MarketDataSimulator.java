package com.fintech.candles.ingestion;

import com.fintech.candles.config.CandleProperties;
import com.fintech.candles.domain.BidAskEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates real-time market data for testing and demonstration.
 * 
 * Generates realistic price movements using a random walk model with:
 * - Configurable volatility
 * - Realistic bid-ask spreads
 * - Continuous price evolution
 * 
 * Can be disabled via configuration for production use.
 * 
 * NOTE: This simple simulator is disabled by default. Use ProductionScaleDataGenerator for benchmarking.
 */
@Component
@ConditionalOnProperty(name = "candle.simulation.simple-mode", havingValue = "true", matchIfMissing = false)
public class MarketDataSimulator {
    
    private static final Logger log = LoggerFactory.getLogger(MarketDataSimulator.class);
    
    // Initial prices for different symbols
    private static final Map<String, Double> INITIAL_PRICES = Map.of(
        "BTC-USD", 42500.0,
        "ETH-USD", 2250.0,
        "SOL-USD", 95.5,
        "ADA-USD", 0.58,
        "DOT-USD", 7.25
    );
    
    // Volatility parameters (as percentage of price)
    private static final Map<String, Double> VOLATILITIES = Map.of(
        "BTC-USD", 0.0002,  // 0.02% per tick
        "ETH-USD", 0.0003,  // 0.03% per tick
        "SOL-USD", 0.0005,  // 0.05% per tick
        "ADA-USD", 0.0008,  // 0.08% per tick
        "DOT-USD", 0.0006   // 0.06% per tick
    );
    
    private final DisruptorEventPublisher eventPublisher;
    private final CandleProperties properties;
    private final Map<String, Double> currentPrices;
    private volatile long eventsGenerated = 0;
    
    public MarketDataSimulator(DisruptorEventPublisher eventPublisher, CandleProperties properties) {
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.currentPrices = new ConcurrentHashMap<>();
        initializePrices();
    }
    
    private void initializePrices() {
        for (String symbol : properties.getSimulation().getSymbols()) {
            Double initialPrice = INITIAL_PRICES.getOrDefault(symbol, 100.0);
            currentPrices.put(symbol, initialPrice);
            log.info("Initialized {} at ${}", symbol, initialPrice);
        }
    }
    
    /**
     * Generates market data at configured frequency.
     * Uses fixed-rate scheduling to maintain consistent event rate.
     * Generates multiple events per symbol per tick to achieve high throughput.
     */
    @Scheduled(fixedRateString = "${candle.simulation.update-frequency-ms:100}")
    public void generateMarketData() {
        long timestamp = System.currentTimeMillis();
        int eventsPerSymbol = properties.getSimulation().getEventsPerTick();
        
        for (String symbol : properties.getSimulation().getSymbols()) {
            // Generate multiple price updates per symbol per tick for high throughput
            for (int i = 0; i < eventsPerSymbol; i++) {
                double price = updatePrice(symbol);
                double spread = calculateSpread(symbol, price);
                
                BidAskEvent event = new BidAskEvent(
                    symbol,
                    price - spread / 2.0,  // Bid is below mid
                    price + spread / 2.0,  // Ask is above mid
                    timestamp + i  // Slight timestamp offset to maintain order
                );
                
                // Publish to Disruptor
                boolean published = eventPublisher.tryPublish(event);
                if (!published) {
                    log.warn("Failed to publish event for {} - ring buffer full", symbol);
                    break;  // Stop generating if buffer is full
                } else {
                    eventsGenerated++;
                }
            }
        }
        
        // Log stats periodically
        if (eventsGenerated % 100000 == 0) {
            log.info("Generated {} market data events", eventsGenerated);
        }
    }
    
    /**
     * Updates price using random walk model.
     * Price can go up or down within volatility bounds.
     * 
     * @param symbol The trading pair
     * @return New mid-market price
     */
    private double updatePrice(String symbol) {
        double currentPrice = currentPrices.get(symbol);
        double volatility = VOLATILITIES.getOrDefault(symbol, 0.0003);
        
        // Random walk: price change is normally distributed
        // Using simple model: change = price * volatility * randomValue[-1, 1]
        double maxChange = currentPrice * volatility;
        double change = ThreadLocalRandom.current().nextDouble(-maxChange, maxChange);
        
        double newPrice = currentPrice + change;
        
        // Prevent negative prices
        if (newPrice < 0) {
            newPrice = currentPrice;
        }
        
        currentPrices.put(symbol, newPrice);
        return newPrice;
    }
    
    /**
     * Calculates a realistic bid-ask spread.
     * Spread is typically 0.01% to 0.05% of price depending on liquidity.
     * 
     * @param symbol The trading pair
     * @param price Current mid price
     * @return Spread in absolute terms
     */
    private double calculateSpread(String symbol, double price) {
        // More liquid pairs have tighter spreads
        double spreadPercentage = switch (symbol) {
            case "BTC-USD", "ETH-USD" -> 0.0001;  // 0.01%
            case "SOL-USD", "DOT-USD" -> 0.0002;  // 0.02%
            default -> 0.0005;  // 0.05%
        };
        
        return price * spreadPercentage;
    }
    
    public long getEventsGenerated() {
        return eventsGenerated;
    }
}

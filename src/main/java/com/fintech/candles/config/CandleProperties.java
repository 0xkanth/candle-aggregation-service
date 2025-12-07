package com.fintech.candles.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Externalized configuration for the candle aggregation service.
 * Maps to 'candle.*' properties in application.yml.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "candle")
public class CandleProperties {
    
    private Storage storage = new Storage();
    private Aggregation aggregation = new Aggregation();
    private Simulation simulation = new Simulation();
    
    @Data
    public static class Storage {
        private ChronicleMapConfig chronicleMap = new ChronicleMapConfig();
        
        @Data
        public static class ChronicleMapConfig {
            private String path = "./data/chronicle-candles.dat";
            private long entries = 10_000_000L;
            private int averageKeySize = 30;
        }
    }
    
    @Data
    public static class Aggregation {
        private DisruptorConfig disruptor = new DisruptorConfig();
        private long lateEventToleranceMs = 5000L;
        
        @Data
        public static class DisruptorConfig {
            private int bufferSize = 8192;
            private String waitStrategy = "YIELDING";
        }
    }
    
    @Data
    public static class Simulation {
        private boolean enabled = true;
        private List<String> symbols = List.of("BTC-USD", "ETH-USD", "SOL-USD");
        private long updateFrequencyMs = 100L;
        private int eventsPerTick = 1;  // Number of events per symbol per tick (for high-throughput testing)
    }
}

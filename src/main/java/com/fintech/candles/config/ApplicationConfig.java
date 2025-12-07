package com.fintech.candles.config;

import com.fintech.candles.util.TimeWindowManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for core application beans.
 */
@Configuration
public class ApplicationConfig {
    
    @Bean
    public TimeWindowManager timeWindowManager(CandleProperties properties) {
        long tolerance = properties.getAggregation().getLateEventToleranceMs();
        return new TimeWindowManager(tolerance);
    }
}

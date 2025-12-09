package com.fintech.candles.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI/Swagger configuration for API documentation.
 * 
 * Access the interactive API documentation at:
 * - Swagger UI: http://localhost:8080/swagger-ui/index.html
 * - OpenAPI JSON: http://localhost:8080/v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI candleAggregationOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Candle Aggregation Service API")
                        .description("""
                                High-performance real-time OHLCV candle aggregation service.
                                
                                **Features:**
                                - Multi-interval aggregation (1s, 5s, 1m, 15m, 1h)
                                - 100K+ events/sec throughput
                                - <50μs p99 latency
                                - Late event handling
                                - TimescaleDB persistence
                                
                                **Tech Stack:**
                                - LMAX Disruptor (lock-free ring buffer)
                                - TimescaleDB (time-series PostgreSQL)
                                - Spring Boot 3.2 with Virtual Threads
                                """)
                        .version("1.0.0"))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("Local Development Server"),
                        new Server()
                                .url("https://api.production.example.com")
                                .description("Production Server (example)")
                ));
    }
}

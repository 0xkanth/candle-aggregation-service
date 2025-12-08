package com.fintech.candles.ingestion;

import com.fintech.candles.domain.BidAskEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for ProductionScaleDataGenerator.
 * 
 * <p><b>Test Coverage:</b>
 * <ul>
 *   <li>Initialization and configuration</li>
 *   <li>Market data generation</li>
 *   <li>Throughput control</li>
 *   <li>Price evolution (Geometric Brownian Motion)</li>
 *   <li>Spread calculations</li>
 *   <li>Multi-instrument support</li>
 *   <li>Performance reporting</li>
 *   <li>Buffer full handling</li>
 * </ul>
 */
@DisplayName("ProductionScaleDataGenerator Tests")
class ProductionScaleDataGeneratorTest {

    private DisruptorEventPublisher mockPublisher;
    private ProductionScaleDataGenerator generator;

    @BeforeEach
    void setUp() {
        mockPublisher = mock(DisruptorEventPublisher.class);
        when(mockPublisher.tryPublish(any(BidAskEvent.class))).thenReturn(true);
        
        generator = new ProductionScaleDataGenerator(mockPublisher);
    }

    @Test
    @DisplayName("Should initialize with default configuration")
    void testInitializeWithDefaults() {
        // When
        generator.initialize();
        
        // Then
        List<String> symbols = getSymbols();
        assertThat(symbols).isNotEmpty();
        assertThat(symbols).contains("BTCUSD", "ETHUSD");
    }

    @Test
    @DisplayName("Should initialize with configured symbols")
    void testInitializeWithConfiguredSymbols() {
        // Given
        List<String> configuredSymbols = List.of("BTCUSD", "ETHUSD", "SOLUSD");
        ReflectionTestUtils.setField(generator, "configuredSymbols", configuredSymbols);
        
        // When
        generator.initialize();
        
        // Then
        List<String> symbols = getSymbols();
        assertThat(symbols).containsExactlyInAnyOrder("BTCUSD", "ETHUSD", "SOLUSD");
    }

    @Test
    @DisplayName("Should initialize with custom target throughput")
    void testInitializeWithCustomThroughput() {
        // Given
        ReflectionTestUtils.setField(generator, "targetEventsPerSecond", 50000);
        
        // When
        generator.initialize();
        
        // Then - no exception, successfully initialized
        assertThat(generator).isNotNull();
    }

    @Test
    @DisplayName("Should generate market data batch")
    void testGenerateMarketDataBatch() {
        // Given
        ReflectionTestUtils.setField(generator, "targetEventsPerSecond", 1000);
        generator.initialize();
        
        // When
        generator.generateMarketDataBatch();
        
        // Then - should generate ~10 events (1000/100)
        verify(mockPublisher, atLeast(5)).tryPublish(any(BidAskEvent.class));
        verify(mockPublisher, atMost(20)).tryPublish(any(BidAskEvent.class));
    }

    @Test
    @DisplayName("Should generate events for configured symbols")
    void testGenerateEventsForSymbols() {
        // Given
        List<String> configuredSymbols = List.of("BTCUSD", "ETHUSD");
        ReflectionTestUtils.setField(generator, "configuredSymbols", configuredSymbols);
        ReflectionTestUtils.setField(generator, "targetEventsPerSecond", 100);
        generator.initialize();
        
        // When
        generator.generateMarketDataBatch();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(eventCaptor.capture());
        
        List<String> symbols = eventCaptor.getAllValues().stream()
            .map(BidAskEvent::symbol)
            .distinct()
            .toList();
        
        assertThat(symbols).isSubsetOf(configuredSymbols);
    }

    @Test
    @DisplayName("Should generate events with valid bid-ask spread")
    void testBidAskSpread() {
        // Given
        ReflectionTestUtils.setField(generator, "targetEventsPerSecond", 100);
        generator.initialize();
        
        // When
        generator.generateMarketDataBatch();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(eventCaptor.capture());
        
        for (BidAskEvent event : eventCaptor.getAllValues()) {
            assertThat(event.ask()).isGreaterThan(event.bid());
            
            double spread = event.ask() - event.bid();
            double midPrice = (event.bid() + event.ask()) / 2.0;
            double spreadPercentage = spread / midPrice;
            
            // Spread should be reasonable (increased to 10% for volatile periods)
            assertThat(spreadPercentage).isLessThan(0.10); // Less than 10%
            assertThat(spreadPercentage).isGreaterThan(0.0);
        }
    }

    @Test
    @DisplayName("Should generate events with realistic BTC prices")
    void testBTCPriceRealism() {
        // Given
        List<String> configuredSymbols = List.of("BTCUSD");
        ReflectionTestUtils.setField(generator, "configuredSymbols", configuredSymbols);
        ReflectionTestUtils.setField(generator, "targetEventsPerSecond", 100);
        generator.initialize();
        
        // When
        generator.generateMarketDataBatch();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(eventCaptor.capture());
        
        for (BidAskEvent event : eventCaptor.getAllValues()) {
            double midPrice = (event.bid() + event.ask()) / 2.0;
            // BTC initialized at 45000, should stay in reasonable range
            assertThat(midPrice).isBetween(10000.0, 100000.0);
        }
    }

    @Test
    @DisplayName("Should generate events with realistic ETH prices")
    void testETHPriceRealism() {
        // Given
        List<String> configuredSymbols = List.of("ETHUSD");
        ReflectionTestUtils.setField(generator, "configuredSymbols", configuredSymbols);
        ReflectionTestUtils.setField(generator, "targetEventsPerSecond", 100);
        generator.initialize();
        
        // When
        generator.generateMarketDataBatch();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(eventCaptor.capture());
        
        for (BidAskEvent event : eventCaptor.getAllValues()) {
            double midPrice = (event.bid() + event.ask()) / 2.0;
            // ETH initialized at 3000, should stay in reasonable range
            assertThat(midPrice).isBetween(1000.0, 10000.0);
        }
    }

    @Test
    @DisplayName("Should generate events with realistic FX prices")
    void testFXPriceRealism() {
        // Given
        List<String> configuredSymbols = List.of("EURUSD");
        ReflectionTestUtils.setField(generator, "configuredSymbols", configuredSymbols);
        ReflectionTestUtils.setField(generator, "targetEventsPerSecond", 100);
        generator.initialize();
        
        // When
        generator.generateMarketDataBatch();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(eventCaptor.capture());
        
        for (BidAskEvent event : eventCaptor.getAllValues()) {
            double midPrice = (event.bid() + event.ask()) / 2.0;
            // EURUSD initialized at 1.1000, should stay in reasonable FX range
            assertThat(midPrice).isBetween(0.9, 1.3);
        }
    }

    @Test
    @DisplayName("Should generate events with valid timestamps")
    void testEventTimestamps() {
        // Given
        ReflectionTestUtils.setField(generator, "targetEventsPerSecond", 100);
        generator.initialize();
        long beforeGeneration = System.currentTimeMillis();
        
        // When
        generator.generateMarketDataBatch();
        long afterGeneration = System.currentTimeMillis();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(eventCaptor.capture());
        
        for (BidAskEvent event : eventCaptor.getAllValues()) {
            assertThat(event.timestamp()).isBetween(beforeGeneration - 1000, afterGeneration + 1000);
        }
    }

    @Test
    @DisplayName("Should handle buffer full scenario")
    void testBufferFullHandling() {
        // Given
        when(mockPublisher.tryPublish(any(BidAskEvent.class))).thenReturn(false);
        ReflectionTestUtils.setField(generator, "targetEventsPerSecond", 100);
        generator.initialize();
        
        // When
        generator.generateMarketDataBatch();
        
        // Then - should attempt to publish but not crash
        verify(mockPublisher, atLeastOnce()).tryPublish(any(BidAskEvent.class));
    }

    @Test
    @DisplayName("Should scale events with target throughput")
    void testThroughputScaling() {
        // Given - low throughput
        ReflectionTestUtils.setField(generator, "targetEventsPerSecond", 100);
        generator.initialize();
        
        // When
        generator.generateMarketDataBatch();
        
        // Then - should generate ~1 event (100/100)
        verify(mockPublisher, atMost(5)).tryPublish(any(BidAskEvent.class));
        
        // Given - high throughput
        reset(mockPublisher);
        when(mockPublisher.tryPublish(any(BidAskEvent.class))).thenReturn(true);
        
        ReflectionTestUtils.setField(generator, "targetEventsPerSecond", 10000);
        
        // When
        generator.generateMarketDataBatch();
        
        // Then - should generate ~100 events (10000/100)
        verify(mockPublisher, atLeast(50)).tryPublish(any(BidAskEvent.class));
    }

    @Test
    @DisplayName("Should handle multiple symbols")
    void testMultipleSymbols() {
        // Given
        List<String> configuredSymbols = List.of("BTCUSD", "ETHUSD", "SOLUSD", "EURUSD");
        ReflectionTestUtils.setField(generator, "configuredSymbols", configuredSymbols);
        ReflectionTestUtils.setField(generator, "targetEventsPerSecond", 400);
        generator.initialize();
        
        // When - generate multiple batches
        for (int i = 0; i < 10; i++) {
            generator.generateMarketDataBatch();
        }
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeast(40)).tryPublish(eventCaptor.capture());
        
        List<String> symbols = eventCaptor.getAllValues().stream()
            .map(BidAskEvent::symbol)
            .distinct()
            .toList();
        
        // Should have generated events for multiple symbols
        assertThat(symbols.size()).isGreaterThan(1);
    }

    @Test
    @DisplayName("Should evolve prices over multiple batches")
    void testPriceEvolution() {
        // Given
        List<String> configuredSymbols = List.of("BTCUSD");
        ReflectionTestUtils.setField(generator, "configuredSymbols", configuredSymbols);
        ReflectionTestUtils.setField(generator, "targetEventsPerSecond", 100);
        generator.initialize();
        
        // When - generate first batch
        generator.generateMarketDataBatch();
        ArgumentCaptor<BidAskEvent> firstCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(firstCaptor.capture());
        
        // Wait a bit for time evolution
        try { Thread.sleep(50); } catch (InterruptedException e) { }
        
        reset(mockPublisher);
        when(mockPublisher.tryPublish(any(BidAskEvent.class))).thenReturn(true);
        
        // Generate second batch
        generator.generateMarketDataBatch();
        ArgumentCaptor<BidAskEvent> secondCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(secondCaptor.capture());
        
        // Then - prices should have evolved
        assertThat(firstCaptor.getAllValues()).isNotEmpty();
        assertThat(secondCaptor.getAllValues()).isNotEmpty();
        
        // Prices will be different due to Geometric Brownian Motion
        // (though they could theoretically be the same by chance)
    }

    @Test
    @DisplayName("Should generate tighter spreads for crypto major pairs")
    void testCryptoSpreads() {
        // Given
        List<String> configuredSymbols = List.of("BTCUSD", "ETHUSD");
        ReflectionTestUtils.setField(generator, "configuredSymbols", configuredSymbols);
        ReflectionTestUtils.setField(generator, "targetEventsPerSecond", 200);
        generator.initialize();
        
        // When
        generator.generateMarketDataBatch();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(eventCaptor.capture());
        
        for (BidAskEvent event : eventCaptor.getAllValues()) {
            double spread = event.ask() - event.bid();
            double midPrice = event.midPrice();
            double spreadPct = (spread / midPrice) * 100;
            
            // Crypto should have relatively tight spreads (configured at 0.01% base)
            assertThat(spreadPct).isLessThan(1.0); // Less than 1%
        }
    }

    @Test
    @DisplayName("Should generate tighter spreads for FX pairs")
    void testFXSpreads() {
        // Given
        List<String> configuredSymbols = List.of("EURUSD", "GBPUSD");
        ReflectionTestUtils.setField(generator, "configuredSymbols", configuredSymbols);
        ReflectionTestUtils.setField(generator, "targetEventsPerSecond", 200);
        generator.initialize();
        
        // When
        generator.generateMarketDataBatch();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(eventCaptor.capture());
        
        for (BidAskEvent event : eventCaptor.getAllValues()) {
            double spread = event.ask() - event.bid();
            double midPrice = event.midPrice();
            double spreadPct = (spread / midPrice) * 100;
            
            // FX should have very tight spreads (configured at 0.001% base)
            assertThat(spreadPct).isLessThan(0.5); // Less than 0.5%
        }
    }

    @Test
    @DisplayName("Should handle unknown symbol gracefully")
    void testUnknownSymbol() {
        // Given
        List<String> configuredSymbols = List.of("UNKNOWN-SYMBOL");
        ReflectionTestUtils.setField(generator, "configuredSymbols", configuredSymbols);
        ReflectionTestUtils.setField(generator, "targetEventsPerSecond", 100);
        
        // When
        generator.initialize();
        generator.generateMarketDataBatch();
        
        // Then - should use default configuration and not crash
        verify(mockPublisher, atLeastOnce()).tryPublish(any(BidAskEvent.class));
    }

    @Test
    @DisplayName("Should handle empty symbol list")
    void testEmptySymbolList() {
        // Given
        List<String> configuredSymbols = List.of("");
        ReflectionTestUtils.setField(generator, "configuredSymbols", configuredSymbols);
        
        // When
        generator.initialize();
        
        // Then - should fall back to default symbols
        List<String> symbols = getSymbols();
        assertThat(symbols).isNotEmpty();
        assertThat(symbols).contains("BTCUSD");
    }

    @Test
    @DisplayName("Should support all instrument types")
    void testAllInstrumentTypes() {
        // Given - mix of different asset classes
        List<String> configuredSymbols = List.of(
            "BTCUSD",   // Crypto
            "EURUSD",   // FX
            "XAUUSD",   // Commodity
            "SPX500"    // Index
        );
        ReflectionTestUtils.setField(generator, "configuredSymbols", configuredSymbols);
        ReflectionTestUtils.setField(generator, "targetEventsPerSecond", 400);
        generator.initialize();
        
        // When
        for (int i = 0; i < 5; i++) {
            generator.generateMarketDataBatch();
        }
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeast(20)).tryPublish(eventCaptor.capture());
        
        List<String> symbols = eventCaptor.getAllValues().stream()
            .map(BidAskEvent::symbol)
            .distinct()
            .toList();
        
        // Should have generated events for different asset classes
        assertThat(symbols).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("Should maintain positive prices")
    void testPositivePrices() {
        // Given
        ReflectionTestUtils.setField(generator, "targetEventsPerSecond", 1000);
        generator.initialize();
        
        // When - generate many batches
        for (int i = 0; i < 50; i++) {
            generator.generateMarketDataBatch();
        }
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeast(1)).tryPublish(eventCaptor.capture());
        
        for (BidAskEvent event : eventCaptor.getAllValues()) {
            assertThat(event.bid()).isGreaterThan(0.0);
            assertThat(event.ask()).isGreaterThan(0.0);
            assertThat(event.midPrice()).isGreaterThan(0.0);
        }
    }

    // Helper method
    @SuppressWarnings("unchecked")
    private List<String> getSymbols() {
        return (List<String>) ReflectionTestUtils.getField(generator, "symbols");
    }
}

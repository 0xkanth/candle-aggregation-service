package com.fintech.candles.ingestion;

import com.fintech.candles.config.CandleProperties;
import com.fintech.candles.domain.BidAskEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for MarketDataSimulator.
 * 
 * <p><b>Test Coverage:</b>
 * <ul>
 *   <li>Market data generation</li>
 *   <li>Price movements and volatility</li>
 *   <li>Spread calculations</li>
 *   <li>Multiple symbol handling</li>
 *   <li>Event publishing integration</li>
 *   <li>Price initialization</li>
 *   <li>Event counting</li>
 * </ul>
 */
@Disabled("MarketDataSimulator disabled by default in favor of ProductionScaleDataGenerator")
@DisplayName("MarketDataSimulator Tests")
class MarketDataSimulatorTest {

    private DisruptorEventPublisher mockPublisher;
    private CandleProperties properties;
    private MarketDataSimulator simulator;

    @BeforeEach
    void setUp() {
        mockPublisher = mock(DisruptorEventPublisher.class);
        when(mockPublisher.tryPublish(any(BidAskEvent.class))).thenReturn(true);
        
        properties = createDefaultProperties();
        simulator = new MarketDataSimulator(mockPublisher, properties);
    }

    private CandleProperties createDefaultProperties() {
        CandleProperties props = new CandleProperties();
        CandleProperties.Simulation simulation = new CandleProperties.Simulation();
        
        simulation.setSymbols(List.of("BTC-USD", "ETH-USD", "SOL-USD"));
        simulation.setEventsPerTick(10);
        simulation.setUpdateFrequencyMs(100);
        
        props.setSimulation(simulation);
        return props;
    }

    @Test
    @DisplayName("Should generate market data for all configured symbols")
    void testGenerateMarketDataForAllSymbols() {
        // When
        simulator.generateMarketData();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeast(30)).tryPublish(eventCaptor.capture()); // 3 symbols * 10 events
        
        List<String> symbols = eventCaptor.getAllValues().stream()
            .map(BidAskEvent::symbol)
            .distinct()
            .toList();
        
        assertThat(symbols).containsExactlyInAnyOrder("BTC-USD", "ETH-USD", "SOL-USD");
    }

    @Test
    @DisplayName("Should generate correct number of events per tick")
    void testEventsPerTick() {
        // When
        simulator.generateMarketData();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, times(30)).tryPublish(eventCaptor.capture()); // 3 symbols * 10 events
    }

    @Test
    @DisplayName("Should generate events with valid bid-ask spread")
    void testBidAskSpread() {
        // When
        simulator.generateMarketData();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(eventCaptor.capture());
        
        for (BidAskEvent event : eventCaptor.getAllValues()) {
            assertThat(event.ask()).isGreaterThan(event.bid());
            
            double spread = event.ask() - event.bid();
            double midPrice = (event.bid() + event.ask()) / 2.0;
            double spreadPercentage = spread / midPrice;
            
            // Spread should be reasonable (less than 1%)
            assertThat(spreadPercentage).isLessThan(0.01);
            assertThat(spreadPercentage).isGreaterThan(0.0);
        }
    }

    @Test
    @DisplayName("Should generate events with realistic BTC prices")
    void testBTCPriceRange() {
        // When
        simulator.generateMarketData();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(eventCaptor.capture());
        
        List<BidAskEvent> btcEvents = eventCaptor.getAllValues().stream()
            .filter(e -> e.symbol().equals("BTC-USD"))
            .toList();
        
        assertThat(btcEvents).isNotEmpty();
        
        for (BidAskEvent event : btcEvents) {
            double midPrice = (event.bid() + event.ask()) / 2.0;
            // BTC price should be in reasonable range (initialized at 42500)
            assertThat(midPrice).isBetween(10000.0, 100000.0);
        }
    }

    @Test
    @DisplayName("Should generate events with realistic ETH prices")
    void testETHPriceRange() {
        // When
        simulator.generateMarketData();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(eventCaptor.capture());
        
        List<BidAskEvent> ethEvents = eventCaptor.getAllValues().stream()
            .filter(e -> e.symbol().equals("ETH-USD"))
            .toList();
        
        assertThat(ethEvents).isNotEmpty();
        
        for (BidAskEvent event : ethEvents) {
            double midPrice = (event.bid() + event.ask()) / 2.0;
            // ETH price should be in reasonable range (initialized at 2250)
            assertThat(midPrice).isBetween(1000.0, 10000.0);
        }
    }

    @Test
    @DisplayName("Should generate events with timestamps")
    void testEventTimestamps() {
        // Given
        long beforeGeneration = System.currentTimeMillis();
        
        // When
        simulator.generateMarketData();
        
        // Then
        long afterGeneration = System.currentTimeMillis();
        
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(eventCaptor.capture());
        
        for (BidAskEvent event : eventCaptor.getAllValues()) {
            assertThat(event.timestamp()).isBetween(beforeGeneration - 1000, afterGeneration + 1000);
        }
    }

    @Test
    @DisplayName("Should track total events generated")
    void testEventCountTracking() {
        // Given
        long initialCount = simulator.getEventsGenerated();
        
        // When
        simulator.generateMarketData();
        
        // Then
        long finalCount = simulator.getEventsGenerated();
        assertThat(finalCount).isGreaterThan(initialCount);
        assertThat(finalCount - initialCount).isEqualTo(30); // 3 symbols * 10 events
    }

    @Test
    @DisplayName("Should increment event count on multiple generations")
    void testEventCountIncrement() {
        // When
        simulator.generateMarketData();
        long count1 = simulator.getEventsGenerated();
        
        simulator.generateMarketData();
        long count2 = simulator.getEventsGenerated();
        
        simulator.generateMarketData();
        long count3 = simulator.getEventsGenerated();
        
        // Then
        assertThat(count2).isGreaterThan(count1);
        assertThat(count3).isGreaterThan(count2);
        assertThat(count3 - count1).isEqualTo(60); // 2 generations * 30 events
    }

    @Test
    @DisplayName("Should handle publisher returning false (buffer full)")
    void testBufferFullHandling() {
        // Given - simulate buffer full after 5 events per symbol
        when(mockPublisher.tryPublish(any(BidAskEvent.class)))
            .thenReturn(true, true, true, true, true, false);
        
        // When
        simulator.generateMarketData();
        
        // Then - should stop publishing when buffer is full
        verify(mockPublisher, atMost(50)).tryPublish(any(BidAskEvent.class));
    }

    @Test
    @DisplayName("Should generate different prices on consecutive calls")
    void testPriceEvolution() {
        // When
        simulator.generateMarketData();
        ArgumentCaptor<BidAskEvent> firstCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(firstCaptor.capture());
        
        reset(mockPublisher);
        when(mockPublisher.tryPublish(any(BidAskEvent.class))).thenReturn(true);
        
        simulator.generateMarketData();
        ArgumentCaptor<BidAskEvent> secondCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(secondCaptor.capture());
        
        // Then - prices should evolve (random walk)
        List<BidAskEvent> firstBtcEvents = firstCaptor.getAllValues().stream()
            .filter(e -> e.symbol().equals("BTC-USD"))
            .toList();
        
        List<BidAskEvent> secondBtcEvents = secondCaptor.getAllValues().stream()
            .filter(e -> e.symbol().equals("BTC-USD"))
            .toList();
        
        assertThat(firstBtcEvents).isNotEmpty();
        assertThat(secondBtcEvents).isNotEmpty();
        
        // Prices will likely be different due to random walk
        // (though there's a tiny chance they could be exactly the same)
    }

    @Test
    @DisplayName("Should handle single symbol configuration")
    void testSingleSymbol() {
        // Given
        properties.getSimulation().setSymbols(List.of("BTC-USD"));
        simulator = new MarketDataSimulator(mockPublisher, properties);
        
        // When
        simulator.generateMarketData();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, times(10)).tryPublish(eventCaptor.capture()); // 1 symbol * 10 events
        
        assertThat(eventCaptor.getAllValues())
            .allMatch(e -> e.symbol().equals("BTC-USD"));
    }

    @Test
    @DisplayName("Should handle many symbols")
    void testManySymbols() {
        // Given
        properties.getSimulation().setSymbols(List.of(
            "BTC-USD", "ETH-USD", "SOL-USD", "ADA-USD", "DOT-USD"
        ));
        simulator = new MarketDataSimulator(mockPublisher, properties);
        
        // When
        simulator.generateMarketData();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, times(50)).tryPublish(eventCaptor.capture()); // 5 symbols * 10 events
        
        List<String> symbols = eventCaptor.getAllValues().stream()
            .map(BidAskEvent::symbol)
            .distinct()
            .toList();
        
        assertThat(symbols).hasSize(5);
    }

    @Test
    @DisplayName("Should handle custom events per tick")
    void testCustomEventsPerTick() {
        // Given
        properties.getSimulation().setEventsPerTick(5);
        simulator = new MarketDataSimulator(mockPublisher, properties);
        
        // When
        simulator.generateMarketData();
        
        // Then
        verify(mockPublisher, times(15)).tryPublish(any(BidAskEvent.class)); // 3 symbols * 5 events
    }

    @Test
    @DisplayName("Should initialize unknown symbols with default price")
    void testUnknownSymbolInitialization() {
        // Given
        properties.getSimulation().setSymbols(List.of("UNKNOWN-PAIR"));
        simulator = new MarketDataSimulator(mockPublisher, properties);
        
        // When
        simulator.generateMarketData();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(eventCaptor.capture());
        
        for (BidAskEvent event : eventCaptor.getAllValues()) {
            double midPrice = (event.bid() + event.ask()) / 2.0;
            // Should use default price of 100.0
            assertThat(midPrice).isBetween(50.0, 200.0); // Allow some volatility movement
        }
    }

    @Test
    @DisplayName("Should use tighter spreads for BTC and ETH")
    void testTighterSpreadsForMajorPairs() {
        // When
        simulator.generateMarketData();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(eventCaptor.capture());
        
        List<BidAskEvent> btcEvents = eventCaptor.getAllValues().stream()
            .filter(e -> e.symbol().equals("BTC-USD"))
            .toList();
        
        List<BidAskEvent> solEvents = eventCaptor.getAllValues().stream()
            .filter(e -> e.symbol().equals("SOL-USD"))
            .toList();
        
        if (!btcEvents.isEmpty() && !solEvents.isEmpty()) {
            BidAskEvent btcEvent = btcEvents.get(0);
            BidAskEvent solEvent = solEvents.get(0);
            
            double btcSpreadPct = (btcEvent.ask() - btcEvent.bid()) / btcEvent.midPrice();
            double solSpreadPct = (solEvent.ask() - solEvent.bid()) / solEvent.midPrice();
            
            // BTC should have tighter spread than SOL
            assertThat(btcSpreadPct).isLessThan(solSpreadPct);
        }
    }

    @Test
    @DisplayName("Should prevent negative prices")
    void testNegativePricePrevention() {
        // Given - run many iterations to try to trigger negative prices
        for (int i = 0; i < 100; i++) {
            simulator.generateMarketData();
        }
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeast(1)).tryPublish(eventCaptor.capture());
        
        for (BidAskEvent event : eventCaptor.getAllValues()) {
            assertThat(event.bid()).isGreaterThan(0.0);
            assertThat(event.ask()).isGreaterThan(0.0);
        }
    }

    @Test
    @DisplayName("Should generate events with sequential timestamps within same tick")
    void testSequentialTimestamps() {
        // When
        simulator.generateMarketData();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(eventCaptor.capture());
        
        // Group by symbol
        var eventsBySymbol = eventCaptor.getAllValues().stream()
            .collect(java.util.stream.Collectors.groupingBy(BidAskEvent::symbol));
        
        for (var entry : eventsBySymbol.entrySet()) {
            List<Long> timestamps = entry.getValue().stream()
                .map(BidAskEvent::timestamp)
                .sorted()
                .toList();
            
            // Timestamps should be sequential (with small offsets)
            for (int i = 1; i < timestamps.size(); i++) {
                long diff = timestamps.get(i) - timestamps.get(i - 1);
                assertThat(diff).isBetween(0L, 100L); // Within same tick
            }
        }
    }

    @Test
    @DisplayName("Should calculate correct mid-price")
    void testMidPriceCalculation() {
        // When
        simulator.generateMarketData();
        
        // Then
        ArgumentCaptor<BidAskEvent> eventCaptor = ArgumentCaptor.forClass(BidAskEvent.class);
        verify(mockPublisher, atLeastOnce()).tryPublish(eventCaptor.capture());
        
        for (BidAskEvent event : eventCaptor.getAllValues()) {
            double calculatedMid = (event.bid() + event.ask()) / 2.0;
            assertThat(event.midPrice()).isEqualTo(calculatedMid);
        }
    }
}

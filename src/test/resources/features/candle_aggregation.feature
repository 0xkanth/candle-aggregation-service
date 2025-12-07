@critical
Feature: Candle Aggregation
  As a trading platform
  I want to aggregate bid/ask events into OHLCV candles
  So that traders can analyze price movements across different time intervals

  Background:
    Given the Candle Aggregation Service is running
    And the Chronicle Map is initialized

  Scenario: Drop late events beyond tolerance
    When I publish a late event 8000 milliseconds late
    Then the late event should be dropped

  Scenario: Persist candles to Chronicle Map
    Given I have aggregated candles for "EURUSD"
    When I restart the application
    Then the previously aggregated candles should still exist

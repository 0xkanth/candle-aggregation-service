package com.fintech.candles.util;

import com.fintech.candles.domain.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages time window calculations for candle aggregation.
 * Handles epoch alignment, window boundaries, and late event detection.
 * 
 * Thread-safe and stateless - all methods are pure functions.
 */
public class TimeWindowManager {
    
    private static final Logger log = LoggerFactory.getLogger(TimeWindowManager.class);
    
    private final long lateEventToleranceMs;
    
    public TimeWindowManager(long lateEventToleranceMs) {
        this.lateEventToleranceMs = lateEventToleranceMs;
    }
    
    /**
     * Determines the window start time for a given timestamp and interval.
     * Always returns an epoch-aligned timestamp.
     * 
     * @param timestamp The event timestamp
     * @param interval The candle interval
     * @return The aligned window start timestamp
     */
    public long getWindowStart(long timestamp, Interval interval) {
        return interval.alignTimestamp(timestamp);
    }
    
    /**
     * Checks if an event should start a new candle window.
     * 
     * @param eventTimestamp The incoming event's timestamp
     * @param currentWindowStart The currently active window's start time
     * @param interval The candle interval
     * @return true if event is in a new window
     */
    public boolean isNewWindow(long eventTimestamp, long currentWindowStart, Interval interval) {
        long eventWindow = getWindowStart(eventTimestamp, interval);
        return eventWindow > currentWindowStart;
    }
    
    /**
     * Checks if an event arrived late (belongs to a previous window).
     * 
     * @param eventTimestamp The event's timestamp
     * @param currentWindowStart The currently active window's start time
     * @param interval The candle interval
     * @return true if event is from a previous window
     */
    public boolean isLateEvent(long eventTimestamp, long currentWindowStart, Interval interval) {
        long eventWindow = getWindowStart(eventTimestamp, interval);
        return eventWindow < currentWindowStart;
    }
    
    /**
     * Determines if a late event should be processed or dropped.
     * Events arriving within tolerance window are reprocessed.
     * Events beyond tolerance are logged and dropped.
     * 
     * @param eventTimestamp The event's timestamp
     * @param currentWindowStart The currently active window's start time
     * @return true if event should be processed
     */
    public boolean shouldProcessLateEvent(long eventTimestamp, long currentWindowStart) {
        long lag = currentWindowStart - eventTimestamp;
        
        if (lag <= lateEventToleranceMs) {
            log.debug("Late event within tolerance: lag={}ms, tolerance={}ms", 
                     lag, lateEventToleranceMs);
            return true;
        }
        
        log.warn("Dropping late event: lag={}ms exceeds tolerance={}ms", 
                lag, lateEventToleranceMs);
        return false;
    }
    
    /**
     * Calculates how many complete windows fit between two timestamps.
     * Useful for detecting missed windows or validating continuity.
     * 
     * @param fromTimestamp Start timestamp
     * @param toTimestamp End timestamp
     * @param interval The candle interval
     * @return Number of complete windows
     */
    public long windowsBetween(long fromTimestamp, long toTimestamp, Interval interval) {
        long fromWindow = getWindowStart(fromTimestamp, interval);
        long toWindow = getWindowStart(toTimestamp, interval);
        return (toWindow - fromWindow) / interval.toMillis();
    }
    
    /**
     * Checks if a timestamp falls within a specific window.
     * 
     * @param timestamp The timestamp to check
     * @param windowStart The window's start time
     * @param interval The candle interval
     * @return true if timestamp is in the window
     */
    public boolean isInWindow(long timestamp, long windowStart, Interval interval) {
        long windowEnd = windowStart + interval.toMillis();
        return timestamp >= windowStart && timestamp < windowEnd;
    }
}

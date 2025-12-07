#!/bin/bash

# Stop Candle Aggregation Service

if [ -f logs/application.pid ]; then
    PID=$(cat logs/application.pid)
    echo "Stopping service (PID: $PID)..."
    kill $PID 2>/dev/null
    rm -f logs/application.pid
    echo "Service stopped"
else
    echo "No PID file found. Searching for running process..."
    PID=$(ps aux | grep 'com.fintech.candles.CandleAggregationApplication' | grep -v grep | awk '{print $2}')
    if [ -n "$PID" ]; then
        echo "Found process $PID, stopping..."
        kill $PID
        echo "Service stopped"
    else
        echo "No running service found"
    fi
fi

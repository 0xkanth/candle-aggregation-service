#!/bin/bash

# Quick GC monitoring script for interviews/production
# Usage: ./monitor-gc.sh

set -e

# Ensure we're in project root
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

APP_NAME="candle-aggregation"
PID=$(pgrep -f "$APP_NAME" || echo "")

if [ -z "$PID" ]; then
    echo "❌ Application not running"
    exit 1
fi

echo "==================================="
echo "   ZGC MONITORING DASHBOARD"
echo "==================================="
echo "PID: $PID"
echo "Time: $(date)"
echo ""

# 1. Heap Status
echo "📊 HEAP STATUS:"
jcmd $PID GC.heap_info | grep -E "ZHeap|Metaspace|class space" | while read line; do
    echo "  $line"
done
# Calculate heap utilization percentage
HEAP_INFO=$(jcmd $PID GC.heap_info | grep "ZHeap")
USED=$(echo "$HEAP_INFO" | awk '{print $3}' | sed 's/[MG,]//g')
CAPACITY=$(echo "$HEAP_INFO" | awk '{print $5}' | sed 's/[MG,]//g')
if [ ! -z "$USED" ] && [ ! -z "$CAPACITY" ] && [ "$CAPACITY" != "0" ]; then
    PERCENT=$(awk "BEGIN {printf \"%.1f\", ($USED/$CAPACITY)*100}")
    echo "  📈 Heap Utilization: $PERCENT% (${USED}M / ${CAPACITY}M)"
    
    # Warning if heap usage is high
    if [ $(echo "$PERCENT > 80" | bc -l) -eq 1 ]; then
        echo "  ⚠️  WARNING: Heap usage above 80%"
    fi
fi
echo ""

# 2. GC Pause Statistics
echo "⚡ GC PAUSE STATISTICS:"
if [ -f logs/gc.log ]; then
    grep "Pause Mark End" logs/gc.log | awk '{print $9}' | sed 's/ms//' | sort -n | \
    awk 'BEGIN{c=0;s=0}{v[c++]=$1;s+=$1}END{
        printf "  Total Pauses: %d\n", c
        printf "  Min Pause:    %.3f ms\n", v[0]
        printf "  p50 Pause:    %.3f ms\n", v[int(c*0.5)]
        printf "  p95 Pause:    %.3f ms\n", v[int(c*0.95)]
        printf "  p99 Pause:    %.3f ms\n", v[int(c*0.99)]
        printf "  Max Pause:    %.3f ms\n", v[c-1]
        printf "  Avg Pause:    %.3f ms\n", s/c
    }'
else
    echo "  ⚠️  GC log not found at logs/gc.log"
fi
echo ""

# 3. Recent GC Activity (last 10 pauses)
echo "🕒 RECENT GC ACTIVITY (Last 10 pauses):"
if [ -f logs/gc.log ]; then
    grep "Pause Mark End" logs/gc.log | tail -10 | \
    awk '{print "  " $1, $6, $7, $8, $9}'
else
    echo "  ⚠️  GC log not found"
fi
echo ""

# 4. Memory Usage Summary
echo "💾 MEMORY SUMMARY:"
jcmd $PID VM.native_memory summary | grep -E "^Total|^Java Heap|^Class|^Thread|^Code" || echo "  ⚠️  NMT not enabled"
echo ""

# 5. Thread Count
echo "🧵 THREAD COUNT:"
jcmd $PID Thread.print | grep -c "^\"" | awk '{print "  Active Threads: " $1}'
echo ""

# 6. JFR Analysis (if recording exists)
echo "📹 JAVA FLIGHT RECORDER:"
JFR_STATUS=$(jcmd $PID JFR.check 2>&1)
if echo "$JFR_STATUS" | grep -q "No available recordings"; then
    echo "  ⚠️  No active JFR recording"
    echo "  💡 To start: jcmd $PID JFR.start duration=60s filename=monitoring.jfr"
else
    echo "$JFR_STATUS" | grep -E "Recording|name|duration|maxage|maxsize" | sed 's/^/  /'
fi
echo ""

# 7. Quick Actions
echo "==================================="
echo "   QUICK ACTIONS"
echo "==================================="
echo "📊 Generate heap graph (60s recording):"
echo "   jcmd $PID JFR.start duration=60s filename=heap-analysis.jfr"
echo "   # Then analyze in JDK Mission Control"
echo ""
echo "🔍 Check for memory leaks:"
echo "   jcmd $PID GC.heap_dump filename=heap-dump.hprof"
echo "   # Analyze with: jvisualvm or Eclipse MAT"
echo ""
echo "⚡ Profile hot methods:"
echo "   jcmd $PID JFR.start settings=profile duration=30s filename=cpu-profile.jfr"
echo ""

echo "==================================="
echo "✅ Monitoring complete"
echo "==================================="

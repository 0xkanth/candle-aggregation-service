#!/bin/bash

# Quick ASCII visualization of JFR heap data
# Shows heap usage over time as a graph
# Usage: ./visualize-jfr.sh <jfr-file>

set -e

JFR_FILE=${1:-""}

if [ -z "$JFR_FILE" ] || [ ! -f "$JFR_FILE" ]; then
    echo "Usage: $0 <jfr-file>"
    echo "Example: $0 jfr-recordings/heap-analysis-20241218-143000.jfr"
    exit 1
fi

echo "=========================================="
echo "   JFR HEAP USAGE VISUALIZATION"
echo "=========================================="
echo "File: $JFR_FILE"
echo ""

# Extract GC events with heap usage
echo "📊 Extracting GC events..."
jfr print --events jdk.GCHeapSummary "$JFR_FILE" 2>/dev/null | \
    grep -E "heapUsed|startTime" | \
    awk '/startTime/{time=$3} /heapUsed/{print time, $3}' | \
    sort -n | \
    awk '
    BEGIN {
        print "Time (s)    Heap Used (MB)    Graph"
        line = "--------    --------------    "
        for (i=0; i<50; i++) line = line "-"
        print line
        min=999999999; max=0
    }
    {
        time=$1; gsub(/s$/, "", time)
        heap=$2; gsub(/MB$/, "", heap)
        
        if (heap < min) min = heap
        if (heap > max) max = heap
        
        times[NR] = time
        heaps[NR] = heap
    }
    END {
        range = max - min
        if (range == 0) range = 1
        
        for (i=1; i<=NR; i++) {
            # Calculate bar length (0-50 chars)
            normalized = (heaps[i] - min) / range
            bar_len = int(normalized * 50)
            
            # Create bar
            bar = ""
            for (j=0; j<bar_len; j++) bar = bar "#"
            
            printf "%-11s %-17.1f %s %.1fM\n", times[i], heaps[i], bar, heaps[i]
        }
        
        print ""
        print "📈 Heap Statistics:"
        printf "   Min: %.1f MB\n", min
        printf "   Max: %.1f MB\n", max
        printf "   Range: %.1f MB\n", range
    }
    '

echo ""
echo "=========================================="
echo "   GC PAUSE ANALYSIS"
echo "=========================================="

# Extract pause times
jfr print --events jdk.GarbageCollection "$JFR_FILE" 2>/dev/null | \
    grep -E "startTime|sumOfPauses" | \
    awk '/startTime/{time=$3} /sumOfPauses/{print time, $3}' | \
    sort -n | \
    awk '
    BEGIN {
        print "Time (s)    Pause (ms)    Impact"
        line = "--------    ----------    "
        for (i=0; i<30; i++) line = line "-"
        print line
    }
    {
        time=$1; gsub(/s$/, "", time)
        pause=$2; gsub(/ms$/, "", pause)
        
        # Visual indicator
        if (pause < 1) indicator = "✅ Excellent"
        else if (pause < 10) indicator = "👍 Good"
        else if (pause < 50) indicator = "⚠️  High"
        else indicator = "❌ Critical"
        
        printf "%-11s %-13.3f %s\n", time, pause, indicator
        
        sum += pause
        count++
    }
    END {
        if (count > 0) {
            print ""
            print "📊 Pause Statistics:"
            printf "   Total Pauses: %d\n", count
            printf "   Avg Pause: %.3f ms\n", sum/count
        }
    }
    '

echo ""
echo "=========================================="
echo "   TOP ALLOCATION HOTSPOTS"
echo "=========================================="

# Show top allocation sites
jfr print --events jdk.ObjectAllocationInNewTLAB "$JFR_FILE" 2>/dev/null | \
    grep -E "objectClass|weight" | \
    awk '
    /objectClass/{class=$3} 
    /weight/{
        weight=$3
        gsub(/.*\./, "", class)  # Remove package prefix
        counts[class] += weight
    }
    END {
        print "Class Name                           Allocations"
        line = ""
        for (i=0; i<70; i++) line = line "-"
        print line
        
        # Sort and print top 10
        PROCINFO["sorted_in"] = "@val_num_desc"
        i = 0
        for (class in counts) {
            if (i++ >= 10) break
            printf "%-40s %10.0f\n", class, counts[class]
        }
    }
    ' 2>/dev/null || echo "  ⚠️  No allocation samples found"

echo ""
echo "=========================================="
echo "💡 INTERVIEW INSIGHTS"
echo "=========================================="
echo "1. SAW PATTERN: Each spike = allocation, each drop = GC"
echo "2. SMOOTH WAVES (ZGC): Concurrent collection prevents STW spikes"
echo "3. SHARP DROPS (G1/Serial): Stop-the-world pauses cause sudden drops"
echo "4. ALLOCATION RATE: Steeper slope = faster allocation = more GC pressure"
echo "=========================================="

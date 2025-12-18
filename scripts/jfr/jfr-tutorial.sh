#!/bin/bash

# Interactive JFR Tutorial - Follow Along!
# This will guide you through using JFR step-by-step

set -e

echo "========================================"
echo "   JFR HANDS-ON TUTORIAL"
echo "========================================"
echo ""
echo "This tutorial will guide you through:"
echo "  1. Finding your running application"
echo "  2. Recording JFR data"
echo "  3. Analyzing the recording"
echo "  4. Understanding the output"
echo ""
echo "Press ENTER to start..."
read

# ========================================
# STEP 1: Find Running Application
# ========================================
clear
echo "========================================"
echo "   STEP 1: Find Your Running App"
echo "========================================"
echo ""
echo "First, we need to find your Java application's PID (Process ID)"
echo ""
echo "Running: jps -l"
echo "----------------------------------------"
jps -l
echo "----------------------------------------"
echo ""
echo "Look for your application in the list above."
echo "The number on the left is the PID."
echo ""
echo "Example:"
echo "  53157 candle-aggregation-service-1.0.0.jar"
echo "  ^^^^^"
echo "  This is the PID (53157)"
echo ""
echo "What is your application's PID?"
read -p "Enter PID: " PID

if [ -z "$PID" ]; then
    echo "❌ No PID entered. Exiting."
    exit 1
fi

# Verify PID exists
if ! ps -p $PID > /dev/null 2>&1; then
    echo "❌ PID $PID not found. Please run tutorial again with correct PID."
    exit 1
fi

echo ""
echo "✅ Found process $PID"
echo ""
echo "Press ENTER to continue..."
read

# ========================================
# STEP 2: Check Current Status
# ========================================
clear
echo "========================================"
echo "   STEP 2: Quick Health Check"
echo "========================================"
echo ""
echo "Let's see current heap and GC status"
echo ""
echo "Running: jcmd $PID GC.heap_info | head -20"
echo "----------------------------------------"
jcmd $PID GC.heap_info | head -20
echo "----------------------------------------"
echo ""
echo "WHAT YOU'RE LOOKING AT:"
echo "  - ZHeap: Shows current heap usage"
echo "  - Metaspace: Memory for class metadata"
echo ""
echo "The key metric: Heap usage percentage"
echo "  ✅ < 80% = Healthy"
echo "  ⚠️  > 80% = High memory pressure"
echo ""
echo "Press ENTER to continue..."
read

# ========================================
# STEP 3: Start JFR Recording
# ========================================
clear
echo "========================================"
echo "   STEP 3: Record JFR Data"
echo "========================================"
echo ""
echo "Now we'll record 30 seconds of JFR data."
echo "The recording has < 1% overhead - your app will run normally."
echo ""
echo "Recording will capture:"
echo "  - Heap usage over time (the sawtooth!)"
echo "  - GC pause times"
echo "  - Object allocations"
echo "  - CPU samples"
echo ""

RECORDING_FILE="tutorial-recording-$(date +%Y%m%d-%H%M%S).jfr"

echo "Press ENTER to start 30-second recording..."
read

echo ""
echo "Running: jcmd $PID JFR.start duration=30s filename=$RECORDING_FILE settings=profile"
echo "----------------------------------------"
jcmd $PID JFR.start duration=30s filename=$RECORDING_FILE settings=profile
echo "----------------------------------------"
echo ""
echo "✅ Recording started!"
echo ""
echo "⏳ Recording for 30 seconds..."
echo "   (You can use your app normally during this time)"
echo ""

# Show countdown
for i in {30..1}; do
    printf "\r   Time remaining: %2d seconds" $i
    sleep 1
done
printf "\r   Time remaining:  0 seconds\n"

echo ""
echo "✅ Recording complete!"
echo ""
echo "File created: $RECORDING_FILE"
ls -lh $RECORDING_FILE
echo ""
echo "Press ENTER to continue..."
read

# ========================================
# STEP 4: View Recording Summary
# ========================================
clear
echo "========================================"
echo "   STEP 4: Quick Summary"
echo "========================================"
echo ""
echo "Let's see a high-level summary of what was recorded"
echo ""
echo "Running: jfr summary $RECORDING_FILE"
echo "----------------------------------------"
jfr summary $RECORDING_FILE
echo "----------------------------------------"
echo ""
echo "WHAT YOU'RE LOOKING AT:"
echo "  - Event Type: Different kinds of events recorded"
echo "  - Count: How many times that event occurred"
echo "  - Size: How much data was recorded"
echo ""
echo "Key events to notice:"
echo "  - jdk.GarbageCollection: GC events"
echo "  - jdk.ObjectAllocationSample: Object creations"
echo "  - jdk.ExecutionSample: CPU profiling"
echo ""
echo "Press ENTER to continue..."
read

# ========================================
# STEP 5: Analyze GC Pauses
# ========================================
clear
echo "========================================"
echo "   STEP 5: GC Pause Analysis"
echo "========================================"
echo ""
echo "Let's see how long GC paused your application"
echo ""
echo "Running: jfr print --events jdk.GarbageCollection $RECORDING_FILE | grep -E 'startTime|sumOfPauses|longestPause'"
echo "----------------------------------------"
jfr print --events jdk.GarbageCollection $RECORDING_FILE | grep -E "startTime|sumOfPauses|longestPause" | head -20
echo "----------------------------------------"
echo ""
echo "UNDERSTANDING THE OUTPUT:"
echo ""
echo "  sumOfPauses = 0.0295 ms"
echo "               ^^^^^^^"
echo "  This is how long your app was FROZEN"
echo "  (This is what users feel as 'lag')"
echo ""
echo "  INTERPRETATION:"
echo "    ✅ < 1ms    = Excellent (low-latency apps)"
echo "    ✅ < 10ms   = Good (normal apps)"
echo "    ⚠️  < 100ms = Acceptable (batch processing)"
echo "    ❌ > 100ms  = Users will notice lag"
echo ""
echo "Press ENTER to continue..."
read

# ========================================
# STEP 6: Heap Usage Graph
# ========================================
clear
echo "========================================"
echo "   STEP 6: Heap Usage Graph"
echo "========================================"
echo ""
echo "Now let's see the famous SAWTOOTH PATTERN!"
echo ""
echo "This shows heap memory over time"
echo ""
echo "Press ENTER to generate graph..."
read

echo ""
echo "Extracting heap data..."
jfr print --events jdk.GCHeapSummary "$RECORDING_FILE" 2>/dev/null | \
    grep -E "heapUsed|startTime" | \
    awk '/startTime/{time=$3} /heapUsed/{print time, $3}' | \
    sort -n | \
    awk '
    BEGIN {
        print ""
        print "Time           Heap Used      Graph"
        print "────────────   ─────────      " sprintf("%40s", "") 
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
            normalized = (heaps[i] - min) / range
            bar_len = int(normalized * 40)
            
            bar = ""
            for (j=0; j<bar_len; j++) bar = bar "#"
            
            printf "%-14s %-12.1f MB  %s\n", times[i], heaps[i], bar
        }
        
        print ""
        print "📈 Statistics:"
        printf "   Min:   %8.1f MB\n", min
        printf "   Max:   %8.1f MB\n", max
        printf "   Range: %8.1f MB\n", range
    }
    '

echo ""
echo "UNDERSTANDING THE GRAPH:"
echo ""
echo "The pattern should look like this:"
echo ""
echo "  Low  │      ╱╲      ╱╲      ╱╲"
echo "  Heap │     ╱  ╲    ╱  ╲    ╱  ╲"
echo "       │    ╱    ╲  ╱    ╲  ╱    ╲"
echo "  High │   ╱      ╲╱      ╲╱      ╲"
echo "       └─────────────────────────────"
echo "           Time →"
echo ""
echo "INTERPRETATION:"
echo "  ╱ Rising  = Your app allocating memory (normal!)"
echo "  ╲ Falling = GC cleaning up (normal!)"
echo ""
echo "  ✅ Sawtooth pattern = Healthy"
echo "  ❌ Only rising      = Memory leak!"
echo ""
echo "Press ENTER to continue..."
read

# ========================================
# STEP 7: Allocation Hotspots
# ========================================
clear
echo "========================================"
echo "   STEP 7: Allocation Hotspots"
echo "========================================"
echo ""
echo "Let's see which objects are created most frequently"
echo ""
echo "Press ENTER to analyze..."
read

echo ""
echo "Running: jfr print --events jdk.ObjectAllocationInNewTLAB $RECORDING_FILE"
echo "----------------------------------------"
jfr print --events jdk.ObjectAllocationInNewTLAB "$RECORDING_FILE" 2>/dev/null | \
    grep -E "objectClass|weight" | \
    paste - - | \
    awk '{
        class=$3
        weight=$6
        gsub(/.*\./, "", class)
        counts[class] += weight
    }
    END {
        print ""
        print "Class Name                    Allocations"
        print "────────────────────────────  ───────────"
        
        # Simple bubble sort and print top 10
        n = asorti(counts, sorted_classes)
        for (i=n; i>n-10 && i>0; i--) {
            class = sorted_classes[i]
            printf "%-30s %10.0f\n", class, counts[class]
        }
    }' 2>/dev/null || echo "No allocation samples found (recording too short)"

echo ""
echo "UNDERSTANDING THE OUTPUT:"
echo ""
echo "  BigDecimal                     450,000"
echo "             ^^^^^^^^^^^^^^^^^^^^"
echo "  This class was created 450,000 times"
echo ""
echo "INTERPRETATION:"
echo "  - High allocation count = Potential optimization target"
echo "  - Your domain classes (TradeEvent, Candle) = normal"
echo "  - Millions of Strings/BigDecimals = might be inefficient"
echo ""
echo "OPTIMIZATION IDEAS:"
echo "  - Use object pooling"
echo "  - Replace BigDecimal with primitive longs"
echo "  - Cache frequently used objects"
echo ""
echo "Press ENTER to continue..."
read

# ========================================
# STEP 8: Next Steps
# ========================================
clear
echo "========================================"
echo "   STEP 8: What's Next?"
echo "========================================"
echo ""
echo "🎉 Congratulations! You've completed the JFR tutorial!"
echo ""
echo "You've learned how to:"
echo "  ✅ Find your application's PID"
echo "  ✅ Record JFR data"
echo "  ✅ Analyze GC pauses"
echo "  ✅ Visualize heap usage (sawtooth)"
echo "  ✅ Find allocation hotspots"
echo ""
echo "Your recording file: $RECORDING_FILE"
echo ""
echo "────────────────────────────────────────"
echo "NEXT STEPS:"
echo "────────────────────────────────────────"
echo ""
echo "1. DOWNLOAD JDK MISSION CONTROL (Visual GUI)"
echo "   https://www.oracle.com/java/technologies/jdk-mission-control.html"
echo ""
echo "   Then open your recording:"
echo "     JMC → File → Open → $RECORDING_FILE"
echo ""
echo "   Navigate to:"
echo "     Memory → Garbage Collections (see beautiful graphs!)"
echo "     Code → Hot Methods (see CPU hotspots)"
echo ""
echo "2. USE PROJECT SCRIPTS (Easier than raw jfr commands)"
echo "   ./monitor-gc.sh                      # Quick health check"
echo "   ./generate-heap-graph.sh 60          # Record 60s"
echo "   ./visualize-jfr.sh $RECORDING_FILE   # ASCII graph"
echo "   ./jfr-commands.sh gc $RECORDING_FILE # Analyze GC"
echo ""
echo "3. READ THE MANUAL"
echo "   cat DEVELOPER_JFR_MANUAL.md"
echo ""
echo "4. PRACTICE EXERCISES"
echo "   - Record during load testing"
echo "   - Compare recordings before/after optimization"
echo "   - Export to CSV and plot in Excel"
echo ""
echo "────────────────────────────────────────"
echo "KEY TAKEAWAYS:"
echo "────────────────────────────────────────"
echo ""
echo "• JFR has < 1% overhead → Safe for production"
echo "• sumOfPauses = what users feel as lag"
echo "• Sawtooth pattern = healthy GC"
echo "• High allocations = optimization opportunity"
echo "• JMC GUI = easiest way to analyze recordings"
echo ""
echo "════════════════════════════════════════"
echo "Tutorial complete! 🚀"
echo "════════════════════════════════════════"

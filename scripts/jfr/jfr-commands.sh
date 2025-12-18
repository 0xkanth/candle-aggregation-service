#!/bin/bash

# Quick JFR Commands Reference
# 
# USAGE:
#   Source this file to load functions:
#     $ source jfr-commands.sh
#   
#   Then use the functions:
#     $ jfr-gc recording.jfr
#     $ jfr-alloc recording.jfr
#
#   Or run directly with arguments:
#     $ ./jfr-commands.sh gc recording.jfr
#     $ ./jfr-commands.sh alloc recording.jfr

# ==================================
# RECORDING COMMANDS
# ==================================

# Start 60-second recording with profiling
alias jfr-start='jcmd $(pgrep -f candle-aggregation) JFR.start duration=60s filename=recording.jfr settings=profile'

# Start continuous recording (manual stop)
alias jfr-continuous='jcmd $(pgrep -f candle-aggregation) JFR.start name=continuous settings=default'

# Stop continuous recording
alias jfr-stop='jcmd $(pgrep -f candle-aggregation) JFR.stop name=continuous'

# Dump continuous recording (without stopping)
alias jfr-dump='jcmd $(pgrep -f candle-aggregation) JFR.dump name=continuous filename=snapshot-$(date +%s).jfr'

# Check recording status
alias jfr-check='jcmd $(pgrep -f candle-aggregation) JFR.check'

# ==================================
# HEAP ANALYSIS
# ==================================

# Generate heap graph (60s)
alias heap-graph='./generate-heap-graph.sh 60'

# Quick heap viz (last recording)
alias heap-viz='./visualize-jfr.sh $(ls -t jfr-recordings/*.jfr | head -1)'

# ==================================
# COMMAND-LINE ANALYSIS
# ==================================

# Show GC events
jfr-gc() {
    jfr print --events jdk.GarbageCollection "$1" | grep -E "startTime|duration|sumOfPauses|longestPause"
}

# Show top allocations
jfr-alloc() {
    jfr print --events jdk.ObjectAllocationInNewTLAB "$1" | \
        grep -E "objectClass|weight" | \
        paste - - | \
        sort -k4 -nr | \
        head -20
}

# Show CPU hotspots
jfr-cpu() {
    jfr print --events jdk.ExecutionSample "$1" | \
        grep -E "stackTrace|sampledThread" | \
        head -50
}

# Show lock contention
jfr-locks() {
    jfr print --events jdk.JavaMonitorEnter "$1" | \
        grep -E "monitorClass|duration" | \
        paste - - | \
        sort -k3 -nr | \
        head -20
}

# Full summary
jfr-summary() {
    echo "=========================================="
    echo "   JFR RECORDING SUMMARY"
    echo "=========================================="
    echo "File: $1"
    echo ""
    jfr summary "$1"
}

# ==================================
# INTERVIEW DEMO COMMANDS
# ==================================

# Complete demo workflow
jfr-demo() {
    echo "🎬 Starting JFR interview demo..."
    echo ""
    
    # 1. Show current state
    echo "Step 1: Current GC state"
    ./monitor-gc.sh
    
    # 2. Record
    echo ""
    echo "Step 2: Recording 30 seconds of runtime..."
    ./generate-heap-graph.sh 30
    
    # 3. Visualize
    echo ""
    echo "Step 3: Visualizing results..."
    ./visualize-jfr.sh $(ls -t jfr-recordings/*.jfr | head -1)
    
    echo ""
    echo "✅ Demo complete! Show this output in interview."
}

# ==================================
# EXPORT FOR EXTERNAL TOOLS
# ==================================

# Export heap data to CSV
jfr-export-heap() {
    jfr print --events jdk.GCHeapSummary "$1" | \
        grep -E "startTime|heapUsed" | \
        awk '/startTime/{time=$3} /heapUsed/{print time "," $3}' > heap-export.csv
    echo "✅ Exported to heap-export.csv (import into Excel/Python)"
}

# Export GC pauses to CSV
jfr-export-gc() {
    jfr print --events jdk.GarbageCollection "$1" | \
        grep -E "startTime|sumOfPauses" | \
        awk '/startTime/{time=$3} /sumOfPauses/{print time "," $3}' > gc-pauses.csv
    echo "✅ Exported to gc-pauses.csv"
}

# ==================================
# USAGE EXAMPLES
# ==================================

# Check if script is being sourced or executed
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    # Script is being executed directly, handle command-line args
    COMMAND="$1"
    shift
    
    case "$COMMAND" in
        gc)
            jfr print --events jdk.GarbageCollection "$1" | grep -E "startTime|duration|sumOfPauses|longestPause"
            ;;
        alloc)
            jfr print --events jdk.ObjectAllocationInNewTLAB "$1" | \
                grep -E "objectClass|weight" | \
                paste - - | \
                sort -k4 -nr | \
                head -20
            ;;
        cpu)
            jfr print --events jdk.ExecutionSample "$1" | \
                grep -E "stackTrace|sampledThread" | \
                head -50
            ;;
        locks)
            jfr print --events jdk.JavaMonitorEnter "$1" | \
                grep -E "monitorClass|duration" | \
                paste - - | \
                sort -k3 -nr | \
                head -20
            ;;
        summary)
            echo "=========================================="
            echo "   JFR RECORDING SUMMARY"
            echo "=========================================="
            echo "File: $1"
            echo ""
            jfr summary "$1"
            ;;
        export-heap)
            jfr print --events jdk.GCHeapSummary "$1" | \
                grep -E "startTime|heapUsed" | \
                awk '/startTime/{time=$3} /heapUsed/{print time "," $3}' > heap-export.csv
            echo "✅ Exported to heap-export.csv (import into Excel/Python)"
            ;;
        export-gc)
            jfr print --events jdk.GarbageCollection "$1" | \
                grep -E "startTime|sumOfPauses" | \
                awk '/startTime/{time=$3} /sumOfPauses/{print time "," $3}' > gc-pauses.csv
            echo "✅ Exported to gc-pauses.csv"
            ;;
        demo)
            echo "🎬 Starting JFR interview demo..."
            echo ""
            
            # 1. Show current state
            echo "Step 1: Current GC state"
            ./monitor-gc.sh
            
            # 2. Record
            echo ""
            echo "Step 2: Recording 30 seconds of runtime..."
            ./generate-heap-graph.sh 30
            
            # 3. Visualize
            echo ""
            echo "Step 3: Visualizing results..."
            ./visualize-jfr.sh $(ls -t jfr-recordings/*.jfr | head -1)
            
            echo ""
            echo "✅ Demo complete! Show this output in interview."
            ;;
        *)
            cat << 'EOF'

📚 JFR COMMANDS - QUICK REFERENCE

USAGE (choose one method):

Method 1 - Direct execution:
  $ ./jfr-commands.sh gc recording.jfr
  $ ./jfr-commands.sh alloc recording.jfr
  $ ./jfr-commands.sh cpu recording.jfr

Method 2 - Source and use functions:
  $ source jfr-commands.sh
  $ jfr-gc recording.jfr
  $ jfr-alloc recording.jfr

AVAILABLE COMMANDS:

1. Analyze JFR recordings:
   $ ./jfr-commands.sh gc <file.jfr>           # GC events
   $ ./jfr-commands.sh alloc <file.jfr>        # Top allocations
   $ ./jfr-commands.sh cpu <file.jfr>          # CPU hotspots
   $ ./jfr-commands.sh locks <file.jfr>        # Lock contention
   $ ./jfr-commands.sh summary <file.jfr>      # Full summary

2. Export data:
   $ ./jfr-commands.sh export-heap <file.jfr>  # → heap-export.csv
   $ ./jfr-commands.sh export-gc <file.jfr>    # → gc-pauses.csv

3. Generate recordings:
   $ ./generate-heap-graph.sh 60               # Record 60s
   $ ./visualize-jfr.sh <file.jfr>             # Visualize

4. Interview demo:
   $ ./jfr-commands.sh demo                    # Complete workflow

5. Continuous monitoring (after sourcing):
   $ source jfr-commands.sh
   $ jfr-continuous                            # Start recording
   $ jfr-dump                                  # Take snapshot
   $ jfr-stop                                  # Stop recording

6. Download JDK Mission Control (best visualization):
   https://www.oracle.com/java/technologies/jdk-mission-control.html

EXAMPLES:

  # Analyze the latest recording
  $ ./jfr-commands.sh gc jfr-recordings/heap-analysis-*.jfr
  
  # Export heap data for Excel
  $ ./jfr-commands.sh export-heap jfr-recordings/heap-analysis-*.jfr
  
  # Run full demo for interview
  $ ./jfr-commands.sh demo

EOF
            ;;
    esac
else
    # Script is being sourced, functions are available
    echo "✅ JFR commands loaded! Available functions:"
    echo "   - jfr-start, jfr-continuous, jfr-stop, jfr-dump, jfr-check"
    echo "   - jfr-gc, jfr-alloc, jfr-cpu, jfr-locks, jfr-summary"
    echo "   - jfr-export-heap, jfr-export-gc"
    echo "   - jfr-demo, heap-graph, heap-viz"
    echo ""
    echo "Example: jfr-gc recording.jfr"
fi

#!/usr/bin/env python3
"""
Plot JFR heap data from CSV export
Usage: python3 plot-heap.py heap-export.csv
"""

import sys
import re
from datetime import datetime

def parse_time(time_str):
    """Convert JFR timestamp to seconds since start"""
    # Format: "13:54:41.193" -> seconds since midnight
    match = re.match(r'(\d+):(\d+):(\d+)\.(\d+)', time_str)
    if match:
        h, m, s, ms = match.groups()
        return int(h) * 3600 + int(m) * 60 + int(s) + int(ms) / 1000
    return 0

def plot_ascii(times, heaps):
    """Generate ASCII art graph"""
    if not times or not heaps:
        print("No data to plot")
        return
    
    # Normalize times to start from 0
    start_time = times[0]
    times = [t - start_time for t in times]
    
    # Calculate statistics
    min_heap = min(heaps)
    max_heap = max(heaps)
    heap_range = max_heap - min_heap if max_heap > min_heap else 1
    
    print("\n" + "="*70)
    print("   HEAP USAGE OVER TIME - SAWTOOTH PATTERN")
    print("="*70)
    print()
    
    # Plot each point
    print(f"{'Time (s)':<12} {'Heap (MB)':<12} Graph")
    print("-" * 70)
    
    for t, h in zip(times, heaps):
        # Calculate bar length (0-40 chars)
        normalized = (h - min_heap) / heap_range
        bar_len = int(normalized * 40)
        bar = "#" * bar_len
        
        print(f"{t:>10.1f}s  {h:>10.1f} MB  {bar}")
    
    print()
    print("📈 Heap Statistics:")
    print(f"   Min:   {min_heap:>8.1f} MB")
    print(f"   Max:   {max_heap:>8.1f} MB")
    print(f"   Range: {heap_range:>8.1f} MB")
    print(f"   Duration: {times[-1]:.1f} seconds")
    print()
    
    # Detect GC events (sudden drops)
    gc_count = 0
    for i in range(1, len(heaps)):
        if heaps[i] < heaps[i-1] * 0.8:  # 20% drop
            gc_count += 1
    
    print("🗑️  GC Events Detected:")
    print(f"   Collections: {gc_count}")
    if gc_count > 0 and times[-1] > 0:
        print(f"   Frequency: ~{times[-1]/gc_count:.1f}s per collection")
    
    print()
    print("💡 Interview Insights:")
    if gc_count == 0:
        print("   - No major GC events in this period (short duration or low allocation)")
    elif heap_range / max_heap < 0.3:
        print("   - Small heap fluctuations (<30%) = efficient GC")
    else:
        print("   - Sawtooth pattern visible = normal allocation/collection cycle")
    
    if gc_count > 0:
        avg_drop = heap_range / gc_count if gc_count > 0 else 0
        print(f"   - Average heap reclaimed per GC: {avg_drop:.1f} MB")
    
    print("="*70)

def main():
    if len(sys.argv) < 2:
        print("Usage: python3 plot-heap.py heap-export.csv")
        print("\nFirst export heap data:")
        print("  ./jfr-commands.sh export-heap recording.jfr")
        sys.exit(1)
    
    csv_file = sys.argv[1]
    
    times = []
    heaps = []
    
    try:
        with open(csv_file, 'r') as f:
            for line in f:
                line = line.strip()
                if not line or ',' not in line:
                    continue
                
                parts = line.split(',')
                if len(parts) != 2:
                    continue
                
                time_str, heap_str = parts
                
                # Parse time (format: "13:54:41.193")
                time_val = parse_time(time_str)
                
                # Parse heap (format: "114.0" or "114.0MB")
                heap_val = float(heap_str.replace('MB', '').strip())
                
                times.append(time_val)
                heaps.append(heap_val)
        
        if not times:
            print(f"❌ No valid data found in {csv_file}")
            sys.exit(1)
        
        plot_ascii(times, heaps)
        
    except FileNotFoundError:
        print(f"❌ File not found: {csv_file}")
        print("\nFirst export heap data:")
        print("  ./jfr-commands.sh export-heap recording.jfr")
        sys.exit(1)
    except Exception as e:
        print(f"❌ Error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()

# Scripts Directory

All operational scripts organized by purpose.

## Directory Structure

```
scripts/
├── deployment/      # Application lifecycle & deployment
├── monitoring/      # Runtime monitoring & observability
├── performance/     # Performance measurement & benchmarking
├── jfr/            # Java Flight Recorder profiling
└── testing/        # Test execution & validation
```

---

## Deployment Scripts

**Location:** `scripts/deployment/`

| Script | Purpose | Usage |
|--------|---------|-------|
| `setup.sh` | Initial project setup | `./scripts/deployment/setup.sh` |
| `start-service.sh` | Start application | `./scripts/deployment/start-service.sh` |
| `stop-service.sh` | Stop application | `./scripts/deployment/stop-service.sh` |
| `quick-restart.sh` | Fast restart (rebuild + restart) | `./scripts/deployment/quick-restart.sh` |
| `launch.sh` | Build and launch with Maven | `./scripts/deployment/launch.sh` |
| `local-launch.sh` | Launch for local development | `./scripts/deployment/local-launch.sh` |
| `docker-launch.sh` | Launch with Docker | `./scripts/deployment/docker-launch.sh` |

**Common Workflows:**
```bash
# First time setup
./scripts/deployment/setup.sh

# Start application
./scripts/deployment/start-service.sh

# Quick restart during development
./scripts/deployment/quick-restart.sh

# Stop application
./scripts/deployment/stop-service.sh
```

---

## Monitoring Scripts

**Location:** `scripts/monitoring/`

| Script | Purpose | Usage |
|--------|---------|-------|
| `monitor-gc.sh` | Comprehensive GC monitoring dashboard | `./scripts/monitoring/monitor-gc.sh` |
| `monitor-throughput.sh` | Real-time throughput monitoring | `./scripts/monitoring/monitor-throughput.sh` |

**Features:**
- **monitor-gc.sh**: Heap usage, GC pauses (p50/p95/p99), thread count, JFR status
- **monitor-throughput.sh**: Events/sec, candles/sec, live updates

**Usage:**
```bash
# Quick health check
./scripts/monitoring/monitor-gc.sh

# Watch throughput live (updates every 5s)
./scripts/monitoring/monitor-throughput.sh
```

---

## Performance Scripts

**Location:** `scripts/performance/`

| Script | Purpose | Usage |
|--------|---------|-------|
| `measure-latency.sh` | Measure event processing latency | `./scripts/performance/measure-latency.sh` |
| `measure-percentiles.sh` | Calculate latency percentiles (p50/p95/p99) | `./scripts/performance/measure-percentiles.sh` |
| `performance-report.sh` | Generate complete performance report | `./scripts/performance/performance-report.sh` |

**What They Measure:**
- **Latency**: Average, max, p50, p95, p99
- **Throughput**: Events/sec, candles/sec
- **Resource Usage**: Heap, CPU, GC pauses
- **Dropped Events**: Late events, buffer overflows

**Usage:**
```bash
# Quick latency check
./scripts/performance/measure-latency.sh

# Detailed percentile analysis
./scripts/performance/measure-percentiles.sh

# Complete performance report
./scripts/performance/performance-report.sh
```

---

## JFR (Java Flight Recorder) Scripts

**Location:** `scripts/jfr/`

| Script | Purpose | Usage |
|--------|---------|-------|
| `jfr-tutorial.sh` | Interactive JFR tutorial | `./scripts/jfr/jfr-tutorial.sh` |
| `generate-heap-graph.sh` | Record JFR data for heap analysis | `./scripts/jfr/generate-heap-graph.sh 60` |
| `visualize-jfr.sh` | Visualize JFR recording (ASCII graph) | `./scripts/jfr/visualize-jfr.sh <file.jfr>` |
| `jfr-commands.sh` | JFR analysis utilities | `./scripts/jfr/jfr-commands.sh <command>` |
| `plot-heap.py` | Plot heap graph from CSV | `python3 scripts/jfr/plot-heap.py heap-export.csv` |

**Quick Start:**
```bash
# Interactive tutorial (recommended for first time)
./scripts/jfr/jfr-tutorial.sh

# Record 60 seconds of JFR data
./scripts/jfr/generate-heap-graph.sh 60

# Visualize the recording
./scripts/jfr/visualize-jfr.sh jfr-recordings/heap-analysis-*.jfr

# Analyze specific aspects
./scripts/jfr/jfr-commands.sh gc jfr-recordings/heap-analysis-*.jfr
./scripts/jfr/jfr-commands.sh alloc jfr-recordings/heap-analysis-*.jfr
./scripts/jfr/jfr-commands.sh cpu jfr-recordings/heap-analysis-*.jfr

# Export and plot
./scripts/jfr/jfr-commands.sh export-heap jfr-recordings/heap-analysis-*.jfr
python3 scripts/jfr/plot-heap.py heap-export.csv
```

**See Also:**
- `DEVELOPER_JFR_MANUAL.md` - Complete JFR guide
- `JFR_QUICK_REFERENCE.md` - Cheat sheet
- `JFR_VISUAL_GUIDE.md` - Tool diagrams
- `HEAP_GRAPH_GUIDE.md` - Heap visualization guide

---

## Testing Scripts

**Location:** `scripts/testing/`

| Script | Purpose | Usage |
|--------|---------|-------|
| `run-all-tests.sh` | Run all unit + integration tests | `./scripts/testing/run-all-tests.sh` |
| `test-service.sh` | Test service endpoints | `./scripts/testing/test-service.sh` |
| `test-swagger.sh` | Test Swagger UI | `./scripts/testing/test-swagger.sh` |
| `test-dropped-events.sh` | Test back-pressure & event dropping | `./scripts/testing/test-dropped-events.sh` |
| `coverage-report.sh` | Generate code coverage report | `./scripts/testing/coverage-report.sh` |

**Usage:**
```bash
# Run all tests
./scripts/testing/run-all-tests.sh

# Test API endpoints
./scripts/testing/test-service.sh

# Test Swagger UI
./scripts/testing/test-swagger.sh

# Test event dropping behavior
./scripts/testing/test-dropped-events.sh

# Generate coverage report
./scripts/testing/coverage-report.sh
```

---

## Script Dependencies

Most scripts require the application to be running. Start it first:

```bash
./scripts/deployment/start-service.sh
```

**Common Requirements:**
- `curl` - API testing, metrics gathering
- `jq` - JSON parsing
- `bc` - Calculations
- `awk` - Text processing
- `jcmd` - JVM commands (JFR, GC)
- `jfr` - JFR analysis tool
- `python3` - Heap plotting (optional)

---

## Quick Reference

### Daily Development Workflow
```bash
# 1. Start service
./scripts/deployment/start-service.sh

# 2. Check health
./scripts/monitoring/monitor-gc.sh

# 3. Run tests
./scripts/testing/run-all-tests.sh

# 4. Restart after changes
./scripts/deployment/quick-restart.sh
```

### Performance Investigation
```bash
# 1. Check current performance
./scripts/performance/performance-report.sh

# 2. Measure latency
./scripts/performance/measure-percentiles.sh

# 3. Record JFR for deep analysis
./scripts/jfr/generate-heap-graph.sh 120

# 4. Analyze recording
./scripts/jfr/visualize-jfr.sh jfr-recordings/heap-analysis-*.jfr
```

### Interview Preparation
```bash
# 1. Start service
./scripts/deployment/start-service.sh

# 2. Generate performance report
./scripts/performance/performance-report.sh

# 3. Monitor GC live
./scripts/monitoring/monitor-gc.sh

# 4. Run JFR tutorial
./scripts/jfr/jfr-tutorial.sh
```

---

## Making Scripts Executable

All scripts should already be executable. If not:

```bash
chmod +x scripts/**/*.sh scripts/**/*.py
```

---

## Troubleshooting

### "Command not found"
Ensure you run scripts from the project root:
```bash
cd /path/to/candle-aggregation-service
./scripts/monitoring/monitor-gc.sh
```

### "Application not running"
Most monitoring/performance scripts require the app to be running:
```bash
./scripts/deployment/start-service.sh
```

### "jfr: command not found"
JFR tools require Java 11+. Check Java version:
```bash
java -version  # Should be 11 or later
```

### "Permission denied"
Make scripts executable:
```bash
chmod +x scripts/monitoring/monitor-gc.sh
```

---

## Contributing New Scripts

When adding new scripts:

1. **Choose correct directory** based on purpose
2. **Add shebang**: `#!/bin/bash`
3. **Add description** at top of file
4. **Make executable**: `chmod +x script.sh`
5. **Update this README** with new script details
6. **Test thoroughly** before committing

**Example:**
```bash
#!/bin/bash
# Brief description of what this script does
# Usage: ./script.sh [args]

set -e  # Exit on error

# Script logic here
```

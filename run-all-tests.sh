#!/bin/bash

#==============================================================================
# Comprehensive Test Runner with Coverage Report
# Runs all unit, integration tests and generates HTML coverage report
#==============================================================================

set -e  # Exit on error

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color
BOLD='\033[1m'

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TIMESTAMP=$(date +"%Y-%m-%d_%H-%M-%S")
REPORT_DIR="${PROJECT_DIR}/target/test-reports"
COVERAGE_DIR="${PROJECT_DIR}/target/site/jacoco"

echo -e "${BOLD}========================================${NC}"
echo -e "${BOLD}  Test Execution & Coverage Report${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""

# Clean previous reports
echo -e "${BLUE}[1/4]${NC} Cleaning previous build and reports..."
mvn clean > /dev/null 2>&1
rm -rf "${REPORT_DIR}" 2>/dev/null || true
mkdir -p "${REPORT_DIR}"

# Run all tests with coverage
echo -e "${BLUE}[2/4]${NC} Running test suite (unit + integration)..."
echo ""

START_TIME=$(date +%s)

# Run tests and capture output
if mvn test jacoco:report -DskipTests=false > "${REPORT_DIR}/maven-output.log" 2>&1; then
    TEST_STATUS="PASSED"
    STATUS_COLOR="${GREEN}"
else
    TEST_STATUS="FAILED"
    STATUS_COLOR="${RED}"
    cat "${REPORT_DIR}/maven-output.log"
    exit 1
fi

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

# Parse test results
echo -e "${BLUE}[3/4]${NC} Analyzing test results..."

# Extract test summary from surefire reports
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0
SKIPPED_TESTS=0

if [ -d "${PROJECT_DIR}/target/surefire-reports" ]; then
    # Count tests from XML reports
    for xml_file in ${PROJECT_DIR}/target/surefire-reports/TEST-*.xml; do
        if [ -f "$xml_file" ]; then
            tests=$(grep -o 'tests="[0-9]*"' "$xml_file" | head -1 | grep -o '[0-9]*')
            failures=$(grep -o 'failures="[0-9]*"' "$xml_file" | head -1 | grep -o '[0-9]*')
            skipped=$(grep -o 'skipped="[0-9]*"' "$xml_file" | head -1 | grep -o '[0-9]*')
            
            TOTAL_TESTS=$((TOTAL_TESTS + tests))
            FAILED_TESTS=$((FAILED_TESTS + failures))
            SKIPPED_TESTS=$((SKIPPED_TESTS + skipped))
        fi
    done
    PASSED_TESTS=$((TOTAL_TESTS - FAILED_TESTS - SKIPPED_TESTS))
fi

# Parse JaCoCo coverage report
COVERAGE_LINE="N/A"
COVERAGE_BRANCH="N/A"
COVERAGE_CLASS="N/A"
COVERAGE_METHOD="N/A"

if [ -f "${COVERAGE_DIR}/index.html" ]; then
    # Extract coverage percentages from JaCoCo HTML report
    if command -v perl > /dev/null 2>&1; then
        COVERAGE_LINE=$(perl -ne 'print $1 if /<td>Total<\/td>.*?<td.*?>(\d+)%<\/td>/' "${COVERAGE_DIR}/index.html" | head -1)
        COVERAGE_BRANCH=$(perl -ne 'print $1 if /<td>Total<\/td>.*?<td.*?>\d+%<\/td>.*?<td.*?>(\d+)%<\/td>/' "${COVERAGE_DIR}/index.html" | head -1)
    fi
    
    # Fallback to grep if perl not available
    if [ "$COVERAGE_LINE" = "N/A" ]; then
        COVERAGE_LINE=$(grep -A 5 ">Total<" "${COVERAGE_DIR}/index.html" | grep -o '[0-9]\+%' | head -1 | tr -d '%')
    fi
fi

# Generate HTML summary report
echo -e "${BLUE}[4/4]${NC} Generating HTML report..."

cat > "${REPORT_DIR}/test-summary.html" << EOF
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Test & Coverage Report - ${TIMESTAMP}</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { 
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Arial, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            padding: 20px;
            min-height: 100vh;
        }
        .container { 
            max-width: 1200px; 
            margin: 0 auto; 
            background: white;
            border-radius: 12px;
            box-shadow: 0 20px 60px rgba(0,0,0,0.3);
            overflow: hidden;
        }
        .header { 
            background: linear-gradient(135deg, #1e3c72 0%, #2a5298 100%);
            color: white; 
            padding: 30px;
            text-align: center;
        }
        .header h1 { font-size: 32px; margin-bottom: 10px; }
        .header p { opacity: 0.9; font-size: 14px; }
        .content { padding: 40px; }
        
        .stats-grid { 
            display: grid; 
            grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
            gap: 20px;
            margin-bottom: 40px;
        }
        .stat-card {
            background: #f8f9fa;
            border-radius: 8px;
            padding: 25px;
            border-left: 4px solid #667eea;
            transition: transform 0.2s;
        }
        .stat-card:hover { transform: translateY(-2px); }
        .stat-card.success { border-left-color: #10b981; }
        .stat-card.warning { border-left-color: #f59e0b; }
        .stat-card.danger { border-left-color: #ef4444; }
        .stat-card.info { border-left-color: #3b82f6; }
        
        .stat-label { 
            font-size: 12px; 
            text-transform: uppercase; 
            color: #6b7280;
            font-weight: 600;
            letter-spacing: 0.5px;
            margin-bottom: 8px;
        }
        .stat-value { 
            font-size: 36px; 
            font-weight: bold;
            color: #111827;
            margin-bottom: 5px;
        }
        .stat-sub { 
            font-size: 13px; 
            color: #6b7280;
        }
        
        .coverage-bars {
            background: #f8f9fa;
            border-radius: 8px;
            padding: 30px;
            margin-bottom: 30px;
        }
        .coverage-item {
            margin-bottom: 20px;
        }
        .coverage-item:last-child { margin-bottom: 0; }
        .coverage-label {
            display: flex;
            justify-content: space-between;
            margin-bottom: 8px;
            font-size: 14px;
            font-weight: 600;
            color: #374151;
        }
        .progress-bar {
            height: 10px;
            background: #e5e7eb;
            border-radius: 10px;
            overflow: hidden;
        }
        .progress-fill {
            height: 100%;
            background: linear-gradient(90deg, #10b981 0%, #059669 100%);
            transition: width 1s ease;
            border-radius: 10px;
        }
        .progress-fill.low { background: linear-gradient(90deg, #ef4444 0%, #dc2626 100%); }
        .progress-fill.medium { background: linear-gradient(90deg, #f59e0b 0%, #d97706 100%); }
        
        .test-suites {
            background: #f8f9fa;
            border-radius: 8px;
            padding: 30px;
        }
        .suite-item {
            display: flex;
            justify-content: space-between;
            padding: 15px;
            background: white;
            margin-bottom: 10px;
            border-radius: 6px;
            border-left: 3px solid #10b981;
        }
        .suite-name { font-weight: 600; color: #111827; }
        .suite-count { color: #6b7280; font-size: 14px; }
        
        .links {
            margin-top: 40px;
            padding-top: 30px;
            border-top: 1px solid #e5e7eb;
            text-align: center;
        }
        .btn {
            display: inline-block;
            padding: 12px 30px;
            margin: 0 10px;
            background: #667eea;
            color: white;
            text-decoration: none;
            border-radius: 6px;
            font-weight: 600;
            transition: background 0.2s;
        }
        .btn:hover { background: #5568d3; }
        .btn-secondary { background: #6b7280; }
        .btn-secondary:hover { background: #4b5563; }
        
        .footer {
            text-align: center;
            padding: 20px;
            color: #6b7280;
            font-size: 13px;
            background: #f8f9fa;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>🧪 Test & Coverage Report</h1>
            <p>Candle Aggregation Service - ${TIMESTAMP}</p>
        </div>
        
        <div class="content">
            <div class="stats-grid">
                <div class="stat-card success">
                    <div class="stat-label">Test Status</div>
                    <div class="stat-value">${TEST_STATUS}</div>
                    <div class="stat-sub">All test suites</div>
                </div>
                
                <div class="stat-card info">
                    <div class="stat-label">Total Tests</div>
                    <div class="stat-value">${TOTAL_TESTS}</div>
                    <div class="stat-sub">${PASSED_TESTS} passed, ${FAILED_TESTS} failed, ${SKIPPED_TESTS} skipped</div>
                </div>
                
                <div class="stat-card success">
                    <div class="stat-label">Line Coverage</div>
                    <div class="stat-value">${COVERAGE_LINE}%</div>
                    <div class="stat-sub">Production code</div>
                </div>
                
                <div class="stat-card info">
                    <div class="stat-label">Execution Time</div>
                    <div class="stat-value">${DURATION}s</div>
                    <div class="stat-sub">Total duration</div>
                </div>
            </div>
            
            <div class="coverage-bars">
                <h2 style="margin-bottom: 25px; color: #111827;">📊 Code Coverage Breakdown</h2>
                
                <div class="coverage-item">
                    <div class="coverage-label">
                        <span>Line Coverage</span>
                        <span>${COVERAGE_LINE}%</span>
                    </div>
                    <div class="progress-bar">
                        <div class="progress-fill" style="width: ${COVERAGE_LINE}%"></div>
                    </div>
                </div>
                
                <div class="coverage-item">
                    <div class="coverage-label">
                        <span>Branch Coverage</span>
                        <span>${COVERAGE_BRANCH}%</span>
                    </div>
                    <div class="progress-bar">
                        <div class="progress-fill" style="width: ${COVERAGE_BRANCH}%"></div>
                    </div>
                </div>
            </div>
            
            <div class="links">
                <a href="../site/jacoco/index.html" class="btn">📈 View Full Coverage Report</a>
                <a href="../surefire-reports" class="btn btn-secondary">📋 View Test Reports</a>
            </div>
        </div>
        
        <div class="footer">
            Generated on ${TIMESTAMP} | Candle Aggregation Service
        </div>
    </div>
</body>
</html>
EOF

# Print formatted console summary
echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${BOLD}       TEST EXECUTION SUMMARY${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""

# Test Results
echo -e "${BOLD}📊 Test Results:${NC}"
echo -e "   Total Tests:     ${BOLD}${TOTAL_TESTS}${NC}"
echo -e "   ${GREEN}✓${NC} Passed:        ${GREEN}${PASSED_TESTS}${NC}"
if [ ${FAILED_TESTS} -gt 0 ]; then
    echo -e "   ${RED}✗${NC} Failed:        ${RED}${FAILED_TESTS}${NC}"
fi
if [ ${SKIPPED_TESTS} -gt 0 ]; then
    echo -e "   ${YELLOW}⊘${NC} Skipped:       ${YELLOW}${SKIPPED_TESTS}${NC}"
fi
echo -e "   Status:          ${STATUS_COLOR}${TEST_STATUS}${NC}"
echo ""

# Coverage Metrics
echo -e "${BOLD}📈 Code Coverage:${NC}"
if [ "$COVERAGE_LINE" != "N/A" ]; then
    # Determine coverage quality
    if [ "$COVERAGE_LINE" -ge 80 ]; then
        COV_COLOR="${GREEN}"
        COV_BADGE="Excellent"
    elif [ "$COVERAGE_LINE" -ge 60 ]; then
        COV_COLOR="${BLUE}"
        COV_BADGE="Good"
    elif [ "$COVERAGE_LINE" -ge 40 ]; then
        COV_COLOR="${YELLOW}"
        COV_BADGE="Fair"
    else
        COV_COLOR="${RED}"
        COV_BADGE="Needs Improvement"
    fi
    
    echo -e "   Line Coverage:   ${COV_COLOR}${COVERAGE_LINE}%${NC} (${COV_BADGE})"
    if [ "$COVERAGE_BRANCH" != "N/A" ]; then
        echo -e "   Branch Coverage: ${COV_COLOR}${COVERAGE_BRANCH}%${NC}"
    fi
else
    echo -e "   ${YELLOW}Coverage report not available${NC}"
fi
echo ""

# Performance
echo -e "${BOLD}⏱️  Performance:${NC}"
echo -e "   Execution Time:  ${DURATION}s"
echo ""

# Report locations
echo -e "${BOLD}📁 Reports Generated:${NC}"
echo -e "   HTML Summary:    ${BLUE}file://${REPORT_DIR}/test-summary.html${NC}"
echo -e "   Coverage Report: ${BLUE}file://${COVERAGE_DIR}/index.html${NC}"
echo -e "   Test Reports:    ${BLUE}file://${PROJECT_DIR}/target/surefire-reports/index.html${NC}"
echo ""

# Quick view command
echo -e "${BOLD}🚀 Quick Actions:${NC}"
echo -e "   View summary:    ${BLUE}open ${REPORT_DIR}/test-summary.html${NC}"
echo -e "   View coverage:   ${BLUE}open ${COVERAGE_DIR}/index.html${NC}"
echo ""

echo -e "${BOLD}========================================${NC}"

# Auto-open report if on macOS
if [[ "$OSTYPE" == "darwin"* ]]; then
    echo -e "${YELLOW}Opening HTML report in browser...${NC}"
    open "${REPORT_DIR}/test-summary.html"
fi

# Success indicator
if [ "$TEST_STATUS" = "PASSED" ]; then
    echo -e "${GREEN}${BOLD}✓ All tests passed successfully!${NC}"
    exit 0
else
    echo -e "${RED}${BOLD}✗ Some tests failed!${NC}"
    exit 1
fi

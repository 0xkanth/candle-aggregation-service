#!/bin/bash

# Coverage Report Generator
# Runs tests, generates JaCoCo coverage report, and opens in browser

set -e  # Exit on error

echo "=================================================="
echo "  Candle Aggregation Service - Coverage Report"
echo "=================================================="
echo ""

# Step 1: Clean
echo "🧹 Cleaning previous build..."
mvn clean -q

# Step 2: Compile
echo "🔨 Compiling source code..."
mvn compile -q

# Step 3: Run tests and generate coverage
echo "🧪 Running tests and generating coverage report..."
mvn test jacoco:report

# Step 4: Check if report exists
REPORT_PATH="target/site/jacoco/index.html"
if [ ! -f "$REPORT_PATH" ]; then
    echo "❌ Error: Coverage report not found at $REPORT_PATH"
    exit 1
fi

# Step 5: Display summary
echo ""
echo "=================================================="
echo "✅ Coverage report generated successfully!"
echo "=================================================="
echo ""
echo "📊 Report location: $REPORT_PATH"
echo ""

# Step 6: Open in default browser
echo "🌐 Opening coverage report in browser..."
if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS
    open "$REPORT_PATH"
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    # Linux
    xdg-open "$REPORT_PATH" 2>/dev/null || firefox "$REPORT_PATH" 2>/dev/null || google-chrome "$REPORT_PATH" 2>/dev/null
elif [[ "$OSTYPE" == "msys" ]] || [[ "$OSTYPE" == "cygwin" ]]; then
    # Windows
    start "$REPORT_PATH"
else
    echo "⚠️  Could not detect OS. Please open manually: $REPORT_PATH"
fi

echo ""
echo "✨ Done!"

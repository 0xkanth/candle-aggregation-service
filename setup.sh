#!/bin/bash

################################################################################
# Candle Aggregation Service - Simple Setup Script
# Builds the project and extracts JAR for Chronicle Map compatibility
################################################################################

set -e  # Exit on error

echo "========================================="
echo "Candle Aggregation Service - Setup"
echo "========================================="
echo ""

# Check Java version
echo "Checking Java version..."
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)

if [ "$JAVA_VERSION" != "21" ]; then
    echo "❌ ERROR: Java 21 is required, but found version $JAVA_VERSION"
    echo ""
    echo "Please install Java 21 using SDKMAN:"
    echo "  curl -s \"https://get.sdkman.io\" | bash"
    echo "  source \"\$HOME/.sdkman/bin/sdkman-init.sh\""
    echo "  sdk install java 21.0.1-tem"
    echo "  sdk use java 21.0.1-tem"
    exit 1
fi

echo "✓ Java 21 detected"
echo ""

# Check Maven
echo "Checking Maven..."
if ! command -v mvn &> /dev/null; then
    echo "❌ ERROR: Maven not found"
    echo ""
    echo "Please install Maven using SDKMAN:"
    echo "  sdk install maven 3.9.6"
    exit 1
fi

echo "✓ Maven detected"
echo ""

# Clean build
echo "Building project (this may take 1-2 minutes)..."
mvn clean package -DskipTests

if [ $? -ne 0 ]; then
    echo ""
    echo "❌ Build failed"
    echo ""
    echo "Common fixes:"
    echo "  - Make sure you're using Java 21: java -version"
    echo "  - Try: sdk use java 21.0.1-tem"
    echo "  - Check for syntax errors in code"
    exit 1
fi

echo "✓ Build successful"
echo ""

# Extract JAR
echo "Extracting JAR for Chronicle Map compatibility..."
rm -rf extracted
mkdir extracted
cd extracted
jar -xf ../target/candle-aggregation-service-1.0.0.jar
cd ..

echo "✓ JAR extracted to ./extracted/"
echo ""

# Create data directory
mkdir -p data

echo "✓ Data directory created"
echo ""

echo "========================================="
echo "✓ Setup Complete!"
echo "========================================="
echo ""
echo "Next steps:"
echo ""
echo "  1. Start the service:"
echo "     ./start-service.sh"
echo ""
echo "  2. In another terminal, test it:"
echo "     ./test-service.sh"
echo ""
echo "  3. Query the API:"
echo "     NOW=\$(date +%s)"
echo "     curl \"http://localhost:8080/api/v1/history?symbol=BTCUSD&interval=1s&from=\$((NOW-30))&to=\$NOW\" | jq"
echo ""
echo "See SETUP.md for detailed documentation."
echo ""

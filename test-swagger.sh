#!/bin/bash

################################################################################
# Swagger UI Test Script
# Verifies that Swagger/OpenAPI documentation is accessible
################################################################################

set -e

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}Testing Swagger/OpenAPI Documentation...${NC}"
echo ""

# Check if service is running
if ! curl -s http://localhost:8080/actuator/health &>/dev/null; then
    echo -e "${RED}✗ Service is not running${NC}"
    echo -e "${YELLOW}  Start the service first: ./start-service.sh${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Service is running${NC}"
echo ""

# Test OpenAPI JSON endpoint
echo -e "${YELLOW}[1/3] Testing OpenAPI JSON endpoint...${NC}"
OPENAPI_RESPONSE=$(curl -s http://localhost:8080/v3/api-docs)

if echo "$OPENAPI_RESPONSE" | jq -e '.openapi' &>/dev/null; then
    VERSION=$(echo "$OPENAPI_RESPONSE" | jq -r '.openapi')
    TITLE=$(echo "$OPENAPI_RESPONSE" | jq -r '.info.title')
    echo -e "${GREEN}   ✓ OpenAPI JSON accessible${NC}"
    echo -e "     Version: $VERSION"
    echo -e "     Title: $TITLE"
else
    echo -e "${RED}   ✗ OpenAPI JSON not accessible${NC}"
    exit 1
fi
echo ""

# Test Swagger UI HTML endpoint
echo -e "${YELLOW}[2/3] Testing Swagger UI HTML endpoint...${NC}"
SWAGGER_HTML=$(curl -s http://localhost:8080/swagger-ui/index.html)

if echo "$SWAGGER_HTML" | grep -q "Swagger UI"; then
    echo -e "${GREEN}   ✓ Swagger UI HTML accessible${NC}"
else
    echo -e "${RED}   ✗ Swagger UI HTML not accessible${NC}"
    exit 1
fi
echo ""

# Count available API endpoints
echo -e "${YELLOW}[3/3] Counting available API endpoints...${NC}"
PATHS=$(echo "$OPENAPI_RESPONSE" | jq '.paths | keys | length')
echo -e "${GREEN}   ✓ Found $PATHS API endpoints documented${NC}"

ENDPOINTS=$(echo "$OPENAPI_RESPONSE" | jq -r '.paths | keys[]')
echo -e "\n${BLUE}   Available Endpoints:${NC}"
echo "$ENDPOINTS" | while read endpoint; do
    METHODS=$(echo "$OPENAPI_RESPONSE" | jq -r ".paths[\"$endpoint\"] | keys[]" | tr '\n' ', ' | sed 's/,$//')
    echo -e "     • $endpoint [$METHODS]"
done
echo ""

# Summary
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}All tests passed! ✓${NC}"
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
echo -e "${YELLOW}Access Swagger UI:${NC}"
echo -e "   ${BLUE}http://localhost:8080/swagger-ui/index.html${NC}"
echo ""
echo -e "${YELLOW}Access OpenAPI JSON:${NC}"
echo -e "   ${BLUE}http://localhost:8080/v3/api-docs${NC}"
echo ""

# Try to open in browser
if command -v open &> /dev/null; then
    echo -e "${YELLOW}Opening Swagger UI in browser...${NC}"
    open "http://localhost:8080/swagger-ui/index.html"
elif command -v xdg-open &> /dev/null; then
    echo -e "${YELLOW}Opening Swagger UI in browser...${NC}"
    xdg-open "http://localhost:8080/swagger-ui/index.html"
else
    echo -e "${YELLOW}Open the URL above in your browser to use Swagger UI${NC}"
fi

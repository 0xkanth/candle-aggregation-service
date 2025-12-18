# Multi-stage Dockerfile for Candle Aggregation Service
# Demonstrates production-ready deployment with TimescaleDB persistence

# Stage 1: Build the application
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

# Copy Maven files for dependency caching
COPY pom.xml .
COPY src ./src

# Build the application (skip tests in image build - run in CI separately)
RUN apt-get update && apt-get install -y maven && \
    mvn clean package -DskipTests && \
    ls -lh target/

# Stage 2: Extract JAR for faster startup
FROM eclipse-temurin:21-jdk AS extractor

WORKDIR /extract

# Copy the built JAR from builder stage
COPY --from=builder /build/target/candle-aggregation-service-1.0.0.jar app.jar

# Extract JAR contents for optimized class loading
RUN jar -xf app.jar && \
    ls -lah && \
    echo "Extracted JAR structure:" && \
    du -sh BOOT-INF/classes BOOT-INF/lib

# Stage 3: Create runtime image
# Note: Using JDK for optimal JIT compilation and runtime performance
FROM eclipse-temurin:21-jdk-alpine

LABEL maintainer="candle-aggregation-team"
LABEL description="High-performance OHLCV candle aggregation service"
LABEL version="1.0.0"

# Create non-root user for security
RUN addgroup -S candle && adduser -S candle -G candle

WORKDIR /app

# Copy extracted JAR from extractor stage
COPY --from=extractor /extract/BOOT-INF/classes ./classes
COPY --from=extractor /extract/BOOT-INF/lib ./lib
COPY --from=extractor /extract/META-INF ./META-INF

# Copy JVM options configuration from build context
COPY config/jvm.options ./config/jvm.options

# Create directories for data and logs
RUN mkdir -p /app/data /app/logs && \
    chown -R candle:candle /app

# Switch to non-root user
USER candle

# Expose application port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Start application using JVM options file (single source of truth)
ENTRYPOINT ["sh", "-c", "exec java @config/jvm.options -cp classes:lib/* com.fintech.candles.CandleAggregationApplication"]

# Default CMD is empty (ENTRYPOINT handles everything)
# But you can override with Spring profiles: docker run ... --spring.profiles.active=production

# Simple single-stage Dockerfile for local development
# Pre-built uberjar must be placed in target/executor-server.jar

FROM eclipse-temurin:21-jre-jammy

# Install curl for healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy pre-built uberjar
COPY target/executor-server.jar /app/executor-server.jar

# Set default environment variables
ENV PORT=8080
ENV STORAGE_TYPE=postgres
ENV DB_POOL_SIZE=10

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

# Run the application with optimized JVM settings
CMD ["java", \
     "-XX:+UseContainerSupport", \
     "-XX:MaxRAMPercentage=75.0", \
     "-jar", "/app/executor-server.jar"]

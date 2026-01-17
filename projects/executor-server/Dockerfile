# Simple single-stage Dockerfile for local development
# Pre-built uberjar must be placed in target/executor-server.jar

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Copy pre-built uberjar
COPY target/executor-server.jar /app/executor-server.jar

# Set default port
ENV PORT=8080

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s \
  CMD curl -f http://localhost:8080/health || exit 1

# Run the application
CMD ["java", "-jar", "/app/executor-server.jar"]

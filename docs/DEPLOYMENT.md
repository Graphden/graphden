# Deployment Guide

This guide covers deploying Graphden executor server.

## Prerequisites

- Java 21+ (JRE or JDK)
- PostgreSQL with Apache AGE extension
- Docker (optional, for containerized deployment)

## Building

### Build Uberjar

```bash
clojure -T:build uber
```

This produces `target/executor-server.jar` containing all dependencies.

### Build Docker Image

```bash
# First build the uberjar
clojure -T:build uber

# Then build Docker image
docker build -t graphden/executor-server .
```

## Deployment Options

### Option 1: Docker Compose (Recommended)

The simplest deployment method using the included `docker-compose.yml`:

```bash
# Build and start
docker compose up -d

# View logs
docker compose logs -f executor

# Stop
docker compose down
```

This starts:

- **age**: PostgreSQL with Apache AGE extension
- **executor**: Graphden executor server

The executor is available at `http://localhost:9002`.

### Option 2: Docker with External Database

If you have an existing PostgreSQL/AGE instance:

```bash
docker run -d \
  --name graphden \
  -e JDBC_URL=jdbc:postgresql://your-host:5432/graphden \
  -e DB_USERNAME=graphden \
  -e DB_PASSWORD=your-password \
  -e PORT=8080 \
  -p 8080:8080 \
  graphden/executor-server
```

### Option 3: Direct JAR Execution

Run the uberjar directly:

```bash
JDBC_URL=jdbc:postgresql://localhost:5432/graphden \
DB_USERNAME=graphden \
DB_PASSWORD=graphden \
java -jar target/executor-server.jar
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `JDBC_URL` | `jdbc:postgresql://localhost:5432/graphden` | PostgreSQL connection URL |
| `DB_USERNAME` | `graphden` | Database username |
| `DB_PASSWORD` | `graphden` | Database password |
| `DB_POOL_SIZE` | `10` | HikariCP connection pool size |
| `PORT` | `8080` | HTTP server port |
| `EXEC_MAX_DEPTH` | `1000` | Maximum function call depth |
| `EXEC_TIMEOUT_MS` | `30000` | Execution timeout in milliseconds |

## Database Setup

### Apache AGE Installation

Graphden requires PostgreSQL with the Apache AGE extension:

```sql
CREATE EXTENSION IF NOT EXISTS age;
LOAD 'age';
SET search_path = ag_catalog, "$user", public;
```

The executor automatically initializes the graph schema on startup.

### Using Docker AGE Image

The recommended approach is using the official Apache AGE Docker image:

```bash
docker run -d \
  --name graphden-age \
  -e POSTGRES_DB=graphden \
  -e POSTGRES_USER=graphden \
  -e POSTGRES_PASSWORD=graphden \
  -p 5432:5432 \
  apache/age:latest
```

## Health Checks

The server exposes a health endpoint:

```bash
curl http://localhost:8080/health
```

Docker health check is configured to probe this endpoint every 30 seconds.

## JVM Configuration

The Docker image includes optimized JVM settings:

- `-XX:+UseContainerSupport` - Respect container CPU/memory limits
- `-XX:MaxRAMPercentage=75.0` - Use 75% of container memory for heap

For bare-metal deployment, consider:

```bash
java \
  -Xms512m \
  -Xmx2g \
  -XX:+UseG1GC \
  -jar target/executor-server.jar
```

## Production Checklist

- [ ] Set strong database password via `DB_PASSWORD`
- [ ] Configure appropriate `DB_POOL_SIZE` based on expected load
- [ ] Set `EXEC_TIMEOUT_MS` to prevent runaway functions
- [ ] Enable HTTPS via reverse proxy (nginx, traefik)
- [ ] Configure log aggregation (stdout is JSON-compatible)
- [ ] Set up monitoring for `/health` endpoint
- [ ] Configure database backups for PostgreSQL

## Troubleshooting

### Connection Refused to Database

Check that AGE container is healthy:

```bash
docker exec graphden-age pg_isready -U graphden -d graphden
```

### Out of Memory Errors

Increase JVM heap or container memory:

```bash
docker run -m 4g -e JAVA_OPTS="-Xmx3g" ...
```

### Slow Startup

Initial startup loads and compiles all function definitions. This is normal and takes 10-30 seconds depending on the number of functions.

## Scaling

For horizontal scaling:

1. Use a shared PostgreSQL/AGE instance
2. Run multiple executor containers
3. Load balance with nginx/traefik
4. Consider read replicas for heavy read workloads

Note: Each executor maintains its own in-memory function cache, so changes propagate on next restart or via cache invalidation endpoints (when implemented).

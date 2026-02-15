# Executor Server

HTTP server for executing graphden functions, with PostgreSQL backend and full storage stack.

## Storage Stack

When running with PostgreSQL (`STORAGE_TYPE=postgres`), the full stack is enabled:

```
CachedStorageWithMetrics
    └── CachedStorage (execution graph caching, O(1) lookup)
            └── VersionedStorage (Git-like branches)
                    └── PostgresStorage (persistence)
```

Features:
- **Execution graph caching** - O(1) graph resolution instead of O(depth)
- **Git-like versioning** - branches, merges, conflict detection
- **Cache metrics** - hit/miss statistics logged on shutdown
- **Connection pooling** - HikariCP with configurable pool size

## Quick Start with Docker Compose

```bash
# Build the uberjar first
clojure -T:build uber

# Start PostgreSQL + Executor
docker-compose up -d

# Check logs
docker-compose logs -f executor

# Stop
docker-compose down
```

## Local Development

### Memory Mode (no database)

```bash
clojure -M:run
```

### PostgreSQL Mode

Start PostgreSQL:
```bash
docker run -d --name graphden-pg \
  -e POSTGRES_DB=graphden \
  -e POSTGRES_USER=graphden \
  -e POSTGRES_PASSWORD=graphden \
  -p 5432:5432 \
  postgres:16-alpine
```

Run the server:
```bash
STORAGE_TYPE=postgres \
JDBC_URL=jdbc:postgresql://localhost:5432/graphden \
DB_USERNAME=graphden \
DB_PASSWORD=graphden \
clojure -M:run
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | 8080 | HTTP server port |
| `STORAGE_TYPE` | memory | `memory` or `postgres` |
| `JDBC_URL` | - | PostgreSQL JDBC URL (required for postgres) |
| `DB_USERNAME` | - | Database username |
| `DB_PASSWORD` | - | Database password |
| `DB_POOL_SIZE` | 10 | HikariCP connection pool size |

## Endpoints

- `GET /` - Hello response
- `GET /health` - Health check

## Building

```bash
# Build uberjar
clojure -T:build uber

# Run uberjar
java -jar target/executor-server.jar
```

## Docker

```bash
# Build image (requires uberjar)
docker build -t graphden-executor .

# Run with docker-compose
docker-compose up
```

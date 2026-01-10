# cache-postgres

PostgreSQL implementation of CacheStorage protocol. Best for production and multi-process deployments.

## Usage

```clojure
(require '[graphden.cache-postgres.interface :as cache-pg]
         '[graphden.cached-storage.interface :as cached])

;; Create cache (uses same connection pool as storage)
(def cache (cache-pg/create-cache {:datasource datasource}))

;; Wrap storage
(def cached-storage (cached/wrap-with-cache storage cache))
```

## Schema

The cache uses dedicated tables:

```sql
-- Cached execution graphs
CREATE TABLE graph_cache (
  fn_id UUID PRIMARY KEY,
  graph JSONB NOT NULL,
  created_at TIMESTAMP DEFAULT NOW()
);

-- Dependency tracking
CREATE TABLE graph_cache_deps (
  cache_id UUID REFERENCES graph_cache(fn_id),
  dep_type TEXT NOT NULL,  -- 'fn', 'fn-schema', 'arg-schema'
  dep_id UUID NOT NULL,
  ref_count INTEGER NOT NULL,
  PRIMARY KEY (cache_id, dep_type, dep_id)
);

CREATE INDEX ON graph_cache_deps (dep_type, dep_id);
```

## Characteristics

- **Storage**: PostgreSQL tables with JSONB
- **Persistence**: Durable across restarts
- **Concurrency**: PostgreSQL transaction isolation
- **Use case**: Production, multi-process, distributed systems

## Initialization

Tables are created automatically via `cache-data-schema` when storage is initialized.

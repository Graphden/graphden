# graph-with-base-fns-postgres

Complete graphden stack with PostgreSQL backend. Includes storage, graph schema, executor, and all base functions pre-registered.

## Usage

```clojure
(require '[graphden.graph-with-base-fns-postgres.interface :as gwbf]
         '[graphden.storage-protocol.interface :as sp]
         '[graphden.executor.interface :as exec])

;; Create complete environment
(def storage (gwbf/create-storage {:jdbcUrl "jdbc:postgresql://localhost:5432/graphden"
                                   :username "user"
                                   :password "pass"}))

;; Base functions are already registered and synced
(sp/query-entities storage :fn-schema {})
;; => [{:name "add", :returned-type :numeric, ...} ...]

;; Create and execute functions
;; ... same as memory version ...

;; Cleanup
(sp/close storage)
```

## Configuration

```clojure
{:jdbcUrl "jdbc:postgresql://host:port/database"
 :username "user"
 :password "password"
 ;; Optional HikariCP settings:
 :maximumPoolSize 10
 :minimumIdle 2
 :connectionTimeout 30000}
```

## What's Included

1. **PostgreSQL storage** - Production-ready implementation with connection pooling
2. **Graph schema** - Tables for fn, fn-schema, arg-schema, arg-value, fn-result-value
3. **Base functions** - 50+ functions registered (same as memory version)

## When to Use

- Production deployments
- Multi-process / distributed systems
- Persistent storage required
- Need PostgreSQL features (transactions, constraints, indexes)

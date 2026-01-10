# cache-datomic

Datomic implementation of CacheStorage protocol. Best for systems requiring immutable history and audit trails.

## Usage

```clojure
(require '[graphden.cache-datomic.interface :as cache-dat]
         '[graphden.cached-storage.interface :as cached])

;; Create cache
(def cache (cache-dat/create-cache {:conn conn}))

;; Wrap storage
(def cached-storage (cached/wrap-with-cache storage cache))
```

## Schema

Uses Datomic entities:

```clojure
;; Cache entity
{:graph-cache/fn-id      uuid     ; Primary key
 :graph-cache/graph      string   ; EDN-encoded graph
 :graph-cache/created-at instant}

;; Dependency entity
{:graph-cache-dep/cache-id     ref    ; Reference to cache
 :graph-cache-dep/dep-type     keyword
 :graph-cache-dep/dep-id       uuid
 :graph-cache-dep/ref-count    long}
```

## Characteristics

- **Storage**: Datomic entities
- **Persistence**: Durable with full history
- **Concurrency**: Datomic ACID transactions
- **Use case**: Systems requiring audit trails, time-travel queries

## Initialization

Schema is transacted automatically via `cache-data-schema` when storage is initialized.

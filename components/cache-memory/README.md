# cache-memory

In-memory implementation of CacheStorage protocol. Best for tests and single-process applications.

## Usage

```clojure
(require '[graphden.cache-memory.interface :as cache-mem]
         '[graphden.cached-storage.interface :as cached])

;; Create cache
(def cache (cache-mem/create-cache))

;; Wrap any storage
(def cached-storage (cached/wrap-with-cache storage cache))
```

## Characteristics

- **Storage**: In-memory atoms
- **Persistence**: None (lost on process restart)
- **Concurrency**: Thread-safe via atoms
- **Use case**: Tests, development, single-process apps

## Implementation

Uses atoms for storage:
- `caches` - Map of fn-id to cached graph
- `fn-deps` - Map of dep-fn-id to set of cache-ids
- `fn-schema-deps` - Map of dep-fn-schema-id to set of cache-ids
- `arg-schema-deps` - Map of dep-arg-schema-id to set of cache-ids

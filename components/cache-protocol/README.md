# cache-protocol

Protocol for execution graph caching. Provides O(1) access to precomputed execution graphs instead of O(depth) recursive queries.

## Protocol

```clojure
(defprotocol CacheStorage
  (get-cached-graph [this fn-id])
  (cache-exists? [this fn-id])
  (save-cache! [this fn-id graph dependencies])
  (delete-cache! [this fn-id])
  (find-caches-by-fn-dep [this dep-fn-id])
  (find-caches-by-fn-schema-dep [this dep-fn-schema-id])
  (find-caches-by-arg-schema-dep [this dep-arg-schema-id]))
```

## Graph Structure

Cached graphs have the same structure as `resolve-execution-graph`:

```clojure
{:fns {fn-id -> fn-record}
 :fn-schemas {schema-id -> schema-record}
 :arg-schemas {arg-schema-id -> arg-schema-record}
 :fn-result-values {frv-id -> frv-record}
 :resolved-args {fn-id -> {arg-schema-id -> resolved-value}}}
```

## Dependencies

Cache dependencies track which entities the graph depends on:

```clojure
{:fn-ids {fn-id -> count}
 :fn-schema-ids {schema-id -> count}
 :arg-schema-ids {arg-schema-id -> count}}
```

The ref-counts enable proper invalidation when an entity is used multiple times.

## Implementations

- [cache-memory](../cache-memory/) - In-memory (tests, single-process)
- [cache-postgres](../cache-postgres/) - PostgreSQL (production)
- [cache-datomic](../cache-datomic/) - Datomic (immutable history)

## Utilities

```clojure
(require '[graphden.cache-protocol.interface :as cache])

;; Check if storage supports caching
(cache/cached-storage? storage)

;; Validation helpers
(cache/validate-graph! graph)
(cache/validate-dependencies! deps)
(cache/validate-uuid! value "fn-id")

;; Build graph from cache data
(cache/build-cached-graph fns fn-schemas arg-schemas resolved-args)
```

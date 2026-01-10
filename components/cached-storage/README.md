# cached-storage

Decorator that wraps any storage with execution graph caching and automatic invalidation.

## Usage

```clojure
(require '[graphden.postgres-storage.interface :as pg]
         '[graphden.cache-postgres.interface :as cache-pg]
         '[graphden.cached-storage.interface :as cached])

;; Create base storage and cache
(def storage (pg/create-storage config))
(def cache (cache-pg/create-cache config))

;; Wrap with caching
(def cached-storage (cached/wrap-with-cache storage cache))

;; Use normally - caching is transparent
(sp/create-entity cached-storage :fn {...})
(sp/resolve-execution-graph cached-storage fn-id)  ; O(1) after first call
```

## How It Works

The wrapped storage:
1. Delegates all CRUD operations to base storage
2. Uses cache for `resolve-execution-graph` (O(1) instead of O(depth))
3. Invalidates affected caches after mutations

## Cache Invalidation

| Entity | Operation | Action |
|--------|-----------|--------|
| fn | created | Create cache for new fn |
| fn | updated (parent changed) | Invalidate fn + all dependents |
| fn | deleted | Delete cache + invalidate dependents |
| arg-value | created/updated/deleted | Invalidate owner-fn + dependents |
| fn-schema | updated | Invalidate all caches using this schema |
| arg-schema | updated | Invalidate all caches using this arg-schema |

## API

```clojure
(require '[graphden.cached-storage.interface :as cached])

;; Wrap storage with caching
(cached/wrap-with-cache base-storage cache-storage)

;; Check if storage is wrapped
(cached/cached-storage? storage)

;; Access underlying components
(cached/unwrap storage)      ; Returns base storage
(cached/get-cache storage)   ; Returns cache storage
```

## Extending Invalidation

Invalidation is implemented via multimethods, allowing extension for custom entity types:

```clojure
(defmethod cached/invalidate-after-create! :my-entity
  [base-storage cache-storage entity-name result]
  ;; Custom invalidation logic
  )
```

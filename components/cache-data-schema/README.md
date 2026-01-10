# cache-data-schema

Data schema definitions for cache storage tables/entities.

## Usage

```clojure
(require '[graphden.cache-data-schema.interface :as cache-schema])

;; Get schema for cache tables
(def schema (cache-schema/create-schema))

;; Initialize storage with cache schema
(sp/initialize cache-storage schema)
```

## Entities

### graph-cache

Stores cached execution graphs.

| Field | Type | Description |
|-------|------|-------------|
| id | uuid | Cache ID (same as fn-id) |
| fn-id | uuid | Function this cache is for |
| graph | jsonb | Cached execution graph |
| created-at | instant | When cache was created |

### graph-cache-dep

Tracks dependencies for cache invalidation.

| Field | Type | Description |
|-------|------|-------------|
| id | uuid | Dependency record ID |
| cache-id | ref | Reference to graph-cache |
| dep-type | keyword | Type: :fn, :fn-schema, :arg-schema |
| dep-id | uuid | ID of the dependency |
| ref-count | int | How many times this dep is used |

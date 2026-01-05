# graph-storage-datomic

Datomic storage pre-initialized with the function graph schema.

## Purpose

Datomic-based storage for working with the function graph. Combines:
- `datomic-storage` — Datomic storage
- `graph-data-schema` — fn-schema, arg-schema, fn, arg-value schema

No manual `sp/initialize` call required.

## Dependencies

- `datomic-storage` — storage implementation
- `graph-data-schema` — data schema
- `malli-data-schema` — schema builder
- `storage-protocol` — protocols

## API

### create-storage

Creates a ready-to-use storage:

```clojure
(require '[graphden.graph-storage-datomic.interface :as gsd]
         '[graphden.storage-protocol.interface :as sp])

;; Without parameters — auto-generated db-name
(let [storage (gsd/create-storage)]
  (sp/current-entities storage)
  ;; => #{:fn-schema :arg-schema :fn :arg-value}
  (sp/close storage))

;; With db-name specified
(let [storage (gsd/create-storage {:db-name "my-graph"})]
  ;; ...
  (sp/close storage))
```

### Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `:db-name` | string | auto-generated | Database name |

## Auto-generated DB Name

When called without parameters, a unique name is created:

```clojure
(str "graph-" (System/currentTimeMillis) "-" (rand-int 10000))
;; => "graph-1703936400000-4521"
```

This is useful for:
- Isolated tests
- Parallel execution
- Ephemeral environments

## Created Attributes

```clojure
;; fn-schema
:fn-schema/name         ; :db.type/string, :db.unique/value
:fn-schema/returned-type ; :db.type/ref (enum)

;; arg-schema
:arg-schema/fn-schema-id ; :db.type/ref
:arg-schema/name         ; :db.type/string
:arg-schema/type         ; :db.type/ref (enum)

;; fn
:fn/name                 ; :db.type/string, :db.unique/value
:fn/fn-schema-id         ; :db.type/ref

;; arg-value
:arg-value/owner-fn-id   ; :db.type/ref
:arg-value/arg-schema-id ; :db.type/ref
:arg-value/value         ; :db.type/string (EDN)

;; enum values
:value-kind.value/null
:value-kind.value/uuid
:value-kind.value/text
;; ... etc.
```

## Error Handling

On initialization error, storage is closed:

```clojure
(try
  (sp/initialize storage schema)
  storage
  (catch Exception e
    (sp/close storage)
    (throw e)))
```

## Usage for Tests

```clojure
(deftest graph-operations-test
  (let [storage (gsd/create-storage)]  ; Unique DB for test
    (try
      ;; Tests...
      (finally
        (sp/close storage)))))  ; Deletes DB
```

## Datomic Advantages

- **Immutable history** — all changes are preserved
- **Time queries** — view state at any point in time
- **ACID** — full transactions
- **Datalog** — powerful query language

## Tests

```bash
bb test
```

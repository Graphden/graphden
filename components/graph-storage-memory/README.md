# graph-storage-memory

In-memory storage pre-initialized with the function graph schema.

## Purpose

Ready-to-use storage for working with the function graph. Combines:
- `memory-storage` — in-memory storage
- `graph-data-schema` — fn-schema, arg-schema, fn, arg-value schema

No manual `sp/initialize` call required.

## Dependencies

- `memory-storage` — storage implementation
- `graph-data-schema` — data schema
- `malli-data-schema` — schema builder
- `storage-protocol` — protocols

## API

### create-storage

Creates a ready-to-use storage:

```clojure
(require '[graphden.graph-storage-memory.interface :as gsm]
         '[graphden.storage-protocol.interface :as sp])

(let [storage (gsm/create-storage)]
  ;; Ready to use immediately
  (sp/current-entities storage)
  ;; => #{:fn-schema :arg-schema :fn :arg-value}

  ;; ... work with storage ...

  (sp/close storage))
```

## Entities

After creation, storage contains all graph entities:

| Entity | Description |
|--------|-------------|
| `:fn-schema` | Function schema (signature) |
| `:arg-schema` | Argument schema |
| `:fn` | Function instance |
| `:arg-value` | Argument value |

## Enum Types

| Enum | Values |
|------|--------|
| `:value-kind` | `:null`, `:uuid`, `:text`, `:int`, `:bool`, `:numeric`, `:timestamptz`, `:jsonb`, `:bytes` |

## Error Handling

On initialization error, storage is automatically closed:

```clojure
;; Inside create-storage:
(try
  (sp/initialize storage schema)
  storage
  (catch Exception e
    (sp/close storage)  ; Cleanup on error
    (throw e)))
```

## Usage

### For Development

```clojure
(require '[graphden.graph-storage-memory.interface :as gsm])

(def storage (gsm/create-storage))
;; Ready to use
```

### For Tests

```clojure
(deftest my-test
  (let [storage (gsm/create-storage)]
    (try
      ;; Tests...
      (finally
        (sp/close storage)))))
```

## Tests

```bash
bb test
```

Tests verify:
- Presence of all graph entities
- Presence of enum `:value-kind`
- Correctness of each entity's fields
- Cleanup on initialization error

# field-types

Centralized definition of supported field types.

## Purpose

Single source of truth for data types supported by all system components. This ensures consistency between:

- `data-schema-protocol` — schema definitions
- `malli-data-schema` — validation
- `*-storage` — database storage

## Dependencies

No external dependencies (leaf component).

## API

### types

Map with metadata for each type:

```clojure
(def types
  {:uuid        {:description "UUID identifier"}
   :text        {:description "Text/string value"}
   :int         {:description "Integer number"}
   :bool        {:description "Boolean true/false"}
   :numeric     {:description "Numeric value (int or double)"}
   :timestamptz {:description "Timestamp with timezone"}
   :jsonb       {:description "JSON data"}
   :bytes       {:description "Binary data"}})
```

### supported-types

Set of supported types:

```clojure
(def supported-types
  #{:uuid :text :int :bool :numeric :timestamptz :jsonb :bytes})
```

## Data Types

| Type | Clojure | PostgreSQL | Datomic |
|------|---------|------------|---------|
| `:uuid` | `java.util.UUID` | `uuid` | `:db.type/uuid` |
| `:text` | `String` | `text` | `:db.type/string` |
| `:int` | `Long` | `bigint` | `:db.type/long` |
| `:bool` | `Boolean` | `boolean` | `:db.type/boolean` |
| `:numeric` | `Number` | `numeric` | `:db.type/double` |
| `:timestamptz` | `java.time.Instant` | `timestamptz` | `:db.type/instant` |
| `:jsonb` | Clojure data | `jsonb` | EDN string |
| `:bytes` | `byte[]` | `bytea` | `:db.type/bytes` |

## Special Types (not in this component)

The following types are defined in `data-schema-protocol`, but not in `field-types`:

| Type | Description | Storage |
|------|-------------|---------|
| `:ref` | Entity reference | UUID |
| `:enum` | Enumeration | Depends on storage |
| `:union` | One of types | JSONB |

## Usage Example

```clojure
(require '[graphden.field-types.interface :as ft])

;; Check type support
(contains? ft/supported-types :text) ; => true
(contains? ft/supported-types :xml)  ; => false

;; Get description
(:description (get ft/types :uuid))
; => "UUID identifier"

;; Iterate over types
(doseq [[type-kw info] ft/types]
  (println type-kw "->" (:description info)))
```

## Extending Types

To add a new type:

1. Add to `types` map in this component
2. Add Malli schema in `malli-data-schema`
3. Add mapping in each storage (`memory`, `postgres`, `datomic`)

## Tests

```bash
bb test
```

Tests verify consistency between `types` and `supported-types`.

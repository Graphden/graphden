# graph-data-schema

Data schema for the function graph.

## Purpose

Defines entity schema for the visual functional programming environment:

- **fn-schema** — function schema (signature)
- **arg-schema** — function argument schema
- **fn** — function instance
- **arg-value** — argument value (literal or reference)

## Dependencies

- `data-schema-protocol` — DataSchema and DataSchemaBuilder protocols
- `field-types` — supported data types

## Entities

### fn-schema

Function schema — defines the signature:

| Field | Type | Description |
|-------|------|-------------|
| `id` | uuid | Primary key (implicit) |
| `name` | text | Unique function name |
| `returned-type` | enum:value-kind | Return type |

**Constraints:** `UNIQUE(name)`

### arg-schema

Argument schema — defines a function parameter:

| Field | Type | Description |
|-------|------|-------------|
| `id` | uuid | Primary key (implicit) |
| `fn-schema-id` | ref:fn-schema | Which function this belongs to |
| `name` | text | Argument name |
| `type` | enum:value-kind | Argument type |

**Constraints:** `UNIQUE(fn-schema-id, name)`

### fn

Function instance — concrete application of a schema:

| Field | Type | Description |
|-------|------|-------------|
| `id` | uuid | Primary key (implicit) |
| `name` | text | Unique instance name |
| `fn-schema-id` | ref:fn-schema | Which schema it implements |

**Constraints:** `UNIQUE(name)`

### arg-value

Argument value for a function instance:

| Field | Type | Description |
|-------|------|-------------|
| `id` | uuid | Primary key (implicit) |
| `owner-fn-id` | ref:fn | Which fn it belongs to |
| `arg-schema-id` | ref:arg-schema | Which argument |
| `value` | union | Value (see below) |

**Constraints:** `UNIQUE(owner-fn-id, arg-schema-id)`

## Union Type for Value

The `value` field can contain:

1. **ref:fn** — reference to another function (result will be computed)
2. **Literals** — uuid, text, int, bool, numeric, timestamptz, jsonb, bytes

```clojure
;; Literal
{:value 42}

;; Function reference
{:value #uuid "fn-id-here"}
```

## Enum value-kind

Enumeration of supported types:

```clojure
#{:null          ; void/nil
  :uuid          ; UUID
  :text          ; String
  :int           ; Integer
  :bool          ; Boolean
  :numeric       ; Number
  :timestamptz   ; Timestamp
  :jsonb         ; JSON
  :bytes}        ; Binary
```

`:null` is used for functions without return value (side-effects).

## Example Graph

```
fn-schema: http-request
  returned-type: :jsonb
  arg-schemas:
    - url: :text
    - method: :text
    - body: :jsonb

fn: get-users (schema: http-request)
  arg-values:
    - url: "https://api.example.com/users"
    - method: "GET"

fn: create-user (schema: http-request)
  arg-values:
    - url: ref<get-users>  ; Use URL from another fn
    - method: "POST"
    - body: {...}
```

## API

### build-schema

Builds the graph schema using a provided builder:

```clojure
(require '[graphden.graph-data-schema.interface :as graph]
         '[graphden.malli-data-schema.interface :as mds])

(def schema
  (graph/build-schema (mds/create-builder)))

;; Check
(ds/entities schema)
;; => (:fn-schema :arg-schema :fn :arg-value)

(ds/enums schema)
;; => {:value-kind {:uuid #uuid "..." :values {...}}}
```

## Stable UUIDs

Each schema element has a fixed UUID, generated once:

```clojure
;; Entities
fn-schema-entity-uuid  = #uuid "dc2df695-..."
arg-schema-entity-uuid = #uuid "946c1f9c-..."
fn-entity-uuid         = #uuid "986e8a2a-..."
arg-value-entity-uuid  = #uuid "afb02fb7-..."

;; Enum
value-kind-enum-uuid   = #uuid "b79e6e8b-..."
```

This allows storage backends to track renames via UUID.

## Extensions

### base-fn-name for Base Functions

```clojure
fn-schema:
  base-fn-name: text (nullable)
```

Clojure function name for execution. `null` means composite function.

### required for Arguments

```clojure
arg-schema:
  required: bool (default true)
```

## Tests

```bash
bb test
```

Tests cover:
- Presence of all entities
- Presence of enum value-kind
- Correctness of each entity's fields
- Data validation

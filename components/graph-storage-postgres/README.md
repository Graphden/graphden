# graph-storage-postgres

PostgreSQL storage pre-initialized with the function graph schema.

## Purpose

Production-ready storage for working with the function graph. Combines:
- `postgres-storage` — PostgreSQL storage
- `graph-data-schema` — fn-schema, arg-schema, fn, arg-value schema

No manual `sp/initialize` call required.

## Dependencies

- `postgres-storage` — storage implementation
- `graph-data-schema` — data schema
- `malli-data-schema` — schema builder
- `storage-protocol` — protocols

## API

### create-storage

Creates a ready-to-use storage:

```clojure
(require '[graphden.graph-storage-postgres.interface :as gsp]
         '[graphden.storage-protocol.interface :as sp])

(let [storage (gsp/create-storage {:jdbc-url "jdbc:postgresql://localhost:5432/graphden"
                                   :username "graphden"
                                   :password "secret"})]
  ;; Ready to use immediately
  (sp/current-entities storage)
  ;; => #{:fn-schema :arg-schema :fn :arg-value}

  ;; ... work with storage ...

  (sp/close storage))
```

### Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `:jdbc-url` | string | JDBC URL (required) |
| `:username` | string | Username (required) |
| `:password` | string | Password (required) |
| `:pool-size` | int | Pool size (default: 10) |

## Created Tables

```sql
-- Function schemas
CREATE TABLE fn_schema (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text UNIQUE NOT NULL,
  returned_type value_kind NOT NULL
);

-- Argument schemas
CREATE TABLE arg_schema (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  fn_schema_id uuid NOT NULL REFERENCES fn_schema(id),
  name text NOT NULL,
  type value_kind NOT NULL,
  UNIQUE(fn_schema_id, name)
);

-- Function instances
CREATE TABLE fn (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text UNIQUE NOT NULL,
  fn_schema_id uuid NOT NULL REFERENCES fn_schema(id)
);

-- Argument values
CREATE TABLE arg_value (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_fn_id uuid NOT NULL REFERENCES fn(id),
  arg_schema_id uuid NOT NULL REFERENCES arg_schema(id),
  value jsonb NOT NULL,
  UNIQUE(owner_fn_id, arg_schema_id)
);

-- Enum for value types
CREATE TYPE value_kind AS ENUM (
  'null', 'uuid', 'text', 'int', 'bool',
  'numeric', 'timestamptz', 'jsonb', 'bytes'
);
```

## Error Handling

On initialization error (e.g., invalid credentials), storage is closed:

```clojure
(try
  (sp/initialize storage schema)
  storage
  (catch Exception e
    (sp/close storage)
    (throw e)))
```

## Usage Example

```clojure
(require '[graphden.graph-storage-postgres.interface :as gsp]
         '[graphden.storage-protocol.interface :as sp])

;; Configuration from environment
(def config
  {:jdbc-url (System/getenv "DATABASE_URL")
   :username (System/getenv "DB_USER")
   :password (System/getenv "DB_PASS")
   :pool-size 20})

;; Create storage
(def storage (gsp/create-storage config))

;; Usage...

;; Close (release connection pool)
(sp/close storage)
```

## Requirements

- PostgreSQL 12+
- CREATE TABLE, CREATE TYPE permissions

## Tests

```bash
# Requires PostgreSQL
bb test
```

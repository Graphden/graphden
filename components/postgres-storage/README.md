# postgres-storage

PostgreSQL implementation of Storage and StorageIntrospection protocols.

## Purpose

Production-ready storage based on PostgreSQL with:
- Connection pooling (HikariCP)
- DDL migrations
- Support for all field types
- Metadata caching

## Dependencies

- `storage-protocol` — Storage and StorageIntrospection protocols
- `data-schema-protocol` — DataSchema protocol
- PostgreSQL 12+

### Clojure Dependencies

- `com.zaxxer/HikariCP` — connection pool
- `org.postgresql/postgresql` — JDBC driver
- `com.github.seancorfield/next.jdbc` — JDBC wrapper

## API

### create-storage

Creates a new PostgreSQL storage instance:

```clojure
(require '[graphden.postgres-storage.interface :as pg]
         '[graphden.storage-protocol.interface :as sp])

(def storage
  (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost:5432/mydb"
                      :username "user"
                      :password "pass"}))

(sp/initialize storage my-schema)

;; Usage...

(sp/close storage)
```

### Connection Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `:jdbc-url` | string | (required) | JDBC URL |
| `:username` | string | (required) | Username |
| `:password` | string | (required) | Password |
| `:pool-size` | int | 10 | Pool size |
| `:min-idle` | int | 2 | Min idle connections |
| `:connection-timeout` | ms | 30000 | Connection timeout |
| `:idle-timeout` | ms | 600000 | Idle timeout |
| `:max-lifetime` | ms | 1800000 | Max connection lifetime |
| `:leak-detection-threshold` | ms | 60000 | Leak detection |

## Type Mapping

| field-types | PostgreSQL |
|-------------|------------|
| `:uuid` | `uuid` |
| `:text` | `text` |
| `:int` | `bigint` |
| `:bool` | `boolean` |
| `:numeric` | `numeric` |
| `:timestamptz` | `timestamptz` |
| `:jsonb` | `jsonb` |
| `:bytes` | `bytea` |
| `:ref` | `uuid` (FK) |
| `:enum` | PostgreSQL ENUM |
| `:union` | `jsonb` |

## DDL Operations

### Create Table

```sql
CREATE TABLE IF NOT EXISTS user (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL,
  email text,
  role user_role NOT NULL
);
```

### Create Enum

```sql
CREATE TYPE user_role AS ENUM ('admin', 'user', 'guest');
```

### Add Field

```sql
ALTER TABLE user ADD COLUMN bio text;
```

### Rename

```sql
ALTER TABLE old_name RENAME TO new_name;
ALTER TABLE user RENAME COLUMN old_field TO new_field;
```

## Metadata

Stored in `_schema_metadata` table:

```sql
CREATE TABLE _schema_metadata (
  uuid uuid PRIMARY KEY,
  kind text NOT NULL,        -- 'entity', 'field', 'enum', 'enum_value'
  name text NOT NULL,
  parent_uuid uuid,          -- for field: entity uuid
  field_type text,           -- for field: field type
  field_nullable boolean     -- for field: nullable?
);
```

## Thread Safety

- Connection pool managed by HikariCP
- Metadata cached with locking
- `initialize` invalidates cache

## Migrations

### Supported Changes

| Operation | DDL |
|-----------|-----|
| Add entity | `CREATE TABLE` |
| Add field | `ALTER TABLE ADD COLUMN` |
| Rename entity | `ALTER TABLE RENAME` |
| Rename field | `ALTER TABLE RENAME COLUMN` |
| Widen type | `ALTER TABLE ALTER COLUMN TYPE` |
| Nullable: false->true | `ALTER TABLE ALTER COLUMN DROP NOT NULL` |

### Pre-migration Checks

- Remove entity -> error
- Remove field -> error
- Narrow type -> error
- Nullable: true->false -> error

## Full Usage Example

```clojure
(require '[graphden.postgres-storage.interface :as pg]
         '[graphden.storage-protocol.interface :as sp]
         '[graphden.graph-data-schema.interface :as graph]
         '[graphden.malli-data-schema.interface :as mds])

;; Create graph schema
(def schema (graph/build-schema (mds/create-builder)))

;; Create storage
(def storage
  (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost:5432/graphden"
                      :username "graphden"
                      :password "secret"}))

;; Initialize (create tables)
(try
  (let [changes (sp/initialize storage schema)]
    (println "Created entities:" (get-in changes [:entities :created]))
    (println "Created enums:" (get-in changes [:enums :created])))
  (finally
    (sp/close storage)))
```

## Introspection

```clojure
;; Current entities (tables)
(sp/current-entities storage)
;; => #{:fn-schema :arg-schema :fn :arg-value}

;; Entity fields
(sp/current-fields storage :fn-schema)
;; => {:name {:type :text :nullable? false}
;;     :returned-type {:type :enum :nullable? false}}

;; Enum types
(sp/current-enums storage)
;; => #{:value-kind}

;; Enum values
(sp/current-enum-values storage :value-kind)
;; => #{:null :uuid :text :int :bool :numeric :timestamptz :jsonb :bytes}
```

## Module Structure

| Module | Purpose |
|--------|---------|
| `core.clj` | Main Storage record, pool management |
| `util.clj` | Type mapping, SQL helpers |
| `metadata.clj` | `_schema_metadata` operations |
| `introspection.clj` | DB structure reading |
| `ddl.clj` | DDL operations |
| `migration.clj` | Migration logic |

## Naming Restrictions

### Kebab-case -> Snake_case

All identifiers (entity names, fields, enums) are converted
from kebab-case (`:my-field`) to snake_case (`my_field`) for SQL.

**Collisions are forbidden:**

```clojure
;; These names produce the same SQL identifier
:my-field  ; -> my_field
:my_field  ; -> my_field (collision!)

;; When trying to use both:
(sp/initialize storage schema) ; => throws "Snake_case naming collision"
```

### Valid SQL Identifiers

Names must match pattern `^[a-z][a-z0-9_]*$`:
- Start with letter a-z
- Contain only letters, digits, and underscores
- Max length — 63 characters (PostgreSQL limit)

**Examples:**

```clojure
;; Valid
:user
:user-profile
:user_profile
:item123

;; Invalid (will error)
:123user      ; starts with digit
:User         ; uppercase letters
:user-name!   ; special characters
```

## Requirements

- PostgreSQL 12+ (for `gen_random_uuid()`)
- CREATE TABLE, CREATE TYPE, ALTER TABLE permissions

## Tests

```bash
# Requires running PostgreSQL
docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=test postgres:15

bb test
```

Tests cover:
- Connection pool
- DDL operations
- Migrations
- Introspection
- Metadata

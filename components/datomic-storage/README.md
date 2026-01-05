# datomic-storage

Datomic implementation of Storage and StorageIntrospection protocols.

## Purpose

Storage based on Datomic with:
- Immutable history
- EAVT data model
- Support for Datomic Local and Pro

## Dependencies

- `storage-protocol` — Storage and StorageIntrospection protocols
- `data-schema-protocol` — DataSchema protocol
- Datomic Local or Datomic Pro/Cloud

### Clojure Dependencies

- `com.datomic/local` — Datomic Local (for development)
- `com.datomic/client-cloud` — Datomic Cloud (optional)

## API

### create-storage

Creates a new Datomic storage instance:

```clojure
(require '[graphden.datomic-storage.interface :as datomic]
         '[graphden.storage-protocol.interface :as sp])

;; In-memory (default)
(def storage (datomic/create-storage {:db-name "my-db"}))

(sp/initialize storage my-schema)

;; Usage...

(sp/close storage)
```

### Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `:db-name` | string | "graphden" | Database name |
| `:client-config` | map | in-memory | Client configuration |

### Configurations

```clojure
;; In-memory (for tests and development)
(datomic/create-storage {:db-name "test-db"})

;; File storage
(datomic/create-storage
  {:db-name "my-db"
   :client-config {:server-type :datomic-local
                   :storage-dir "/path/to/data"
                   :system "my-system"}})

;; Peer-server (Datomic Pro)
(datomic/create-storage
  {:db-name "my-db"
   :client-config {:server-type :peer-server
                   :endpoint "localhost:8998"
                   :secret "your-secret"
                   :access-key "your-key"}})
```

### default-local-config

Predefined configuration for development:

```clojure
datomic/default-local-config
;; => {:server-type :datomic-local
;;     :storage-dir :mem
;;     :system "graphden-dev"}
```

## Type Mapping

| field-types | Datomic |
|-------------|---------|
| `:uuid` | `:db.type/uuid` |
| `:text` | `:db.type/string` |
| `:int` | `:db.type/long` |
| `:bool` | `:db.type/boolean` |
| `:numeric` | `:db.type/bigdec` |
| `:timestamptz` | `:db.type/instant` |
| `:jsonb` | `:db.type/string` (EDN) |
| `:bytes` | `:db.type/bytes` |
| `:ref` | `:db.type/ref` |
| `:enum` | `:db.type/ref` (idents) |
| `:union` | `:db.type/string` (EDN) |

### Type Limitations

**Byte arrays (`:bytes`)**: Datomic has a size limit for byte array values.
For Datomic Local this is ~10MB. Attempting to save a larger array will result in
`:datomic-error` with a message about exceeding the limit. Size validation on the
application side is not yet implemented — error occurs during transaction.

## Attribute Schema

### Naming

Attributes are named as `entity/field`:

```clojure
:user/name      ; name field of user entity
:user/email     ; email field of user entity
```

### Enum Values

Enum values are created as idents:

```clojure
:status.value/active    ; :active value of :status enum
:status.value/inactive  ; :inactive value of :status enum
```

### Metadata

Metadata stored in `graphden.metadata/*` attributes:

```clojure
{:graphden.metadata/uuid #uuid "..."
 :graphden.metadata/kind :entity    ; :entity, :field, :enum, :enum-value
 :graphden.metadata/name :user
 :graphden.metadata/parent-uuid #uuid "..."     ; for field/enum-value
 :graphden.metadata/field-type :text            ; for field
 :graphden.metadata/field-nullable false}       ; for field
```

## Unique Constraints

Only single-field unique constraints are supported:

```clojure
;; Works
(ds/add-constraint :user {:type :unique :fields [:email]})
;; => :db/unique :db.unique/value

;; Composite unique NOT directly supported
(ds/add-constraint :user {:type :unique :fields [:tenant-id :name]})
;; => Ignored (requires application-level check)
```

## Migrations

### Supported Changes

| Operation | Implementation |
|-----------|----------------|
| Add entity | Add attributes |
| Add field | `d/transact` new attribute |
| Add enum value | Create new ident |
| Rename | Via metadata (attributes are not renamed) |

### Datomic Specifics

In Datomic attributes cannot be deleted or renamed directly. Renames are tracked via metadata.

## Thread Safety

- All operations protected by lock
- Connection created at `initialize`
- Client and connection stored in atoms

## Full Usage Example

```clojure
(require '[graphden.datomic-storage.interface :as datomic]
         '[graphden.storage-protocol.interface :as sp]
         '[graphden.graph-data-schema.interface :as graph]
         '[graphden.malli-data-schema.interface :as mds])

;; Create graph schema
(def schema (graph/build-schema (mds/create-builder)))

;; Create storage
(def storage (datomic/create-storage {:db-name "graphden-dev"}))

;; Initialize
(try
  (let [changes (sp/initialize storage schema)]
    (println "Created:" (get-in changes [:entities :created])))
  (finally
    (sp/close storage)))
```

## Introspection

```clojure
;; Current entities (attribute namespaces)
(sp/current-entities storage)
;; => #{:fn-schema :arg-schema :fn :arg-value}

;; Entity fields (from metadata)
(sp/current-fields storage :fn-schema)
;; => {:name {:type :text :nullable? false}
;;     :returned-type {:type :enum :nullable? false}}

;; Enum types (from .value namespaces)
(sp/current-enums storage)
;; => #{:value-kind}

;; Enum values
(sp/current-enum-values storage :value-kind)
;; => #{:null :uuid :text :int :bool :numeric :timestamptz :jsonb :bytes}
```

## Close Behavior

`sp/close` deletes the database. This is done for:
- Cleaning up test data
- Idempotency in dev environment

For production with persistence use a separate DB management strategy.

## Limitations

- Composite unique constraints not supported (Datomic limitation)
- `:jsonb` and `:union` stored as EDN strings (not native JSON)
- Renames require metadata update, not attribute update
- **Non-atomic metadata update**: When updating schema, metadata is updated
  in two transactions (retract old, assert new), as Datomic doesn't allow
  retract and assert of the same unique value in one transaction.
  In case of failure between transactions, metadata may be lost.

## Tests

```bash
bb test
```

Tests cover:
- Initialization with in-memory storage
- Attribute schema creation
- Introspection
- Migrations (adding fields/values)
- Metadata

## Requirements

- Java 11+
- Datomic Local (included) or Datomic Pro license

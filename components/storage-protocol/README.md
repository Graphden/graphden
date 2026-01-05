# storage-protocol

Protocols for implementing storage backends.

## Purpose

Defines interfaces that all storage backends (memory, PostgreSQL, Datomic) must implement. Provides:

- Unified contract for all storage backends
- UUID-based migrations (rename detection)
- Safe schema changes (destructive operations forbidden)
- Utilities for computing schema diffs

## Dependencies

- `data-schema-protocol` — DataSchema protocol for reading schema

## Protocols

### Storage

```clojure
(defprotocol Storage
  (initialize [this schema]
    "Synchronizes storage with DataSchema.
     Returns map of changes or throws on destructive operations.")

  (close [this]
    "Releases resources (connections, handles)."))
```

### StorageIntrospection

```clojure
(defprotocol StorageIntrospection
  (current-entities [this]
    "Returns set of entity names in storage.")

  (current-fields [this entity-name]
    "Returns map of entity fields: {field-name {:type :text ...}}")

  (current-enums [this]
    "Returns set of enum type names.")

  (current-enum-values [this enum-name]
    "Returns set of enum values.")

  (schema-metadata [this]
    "Returns stored UUID->name mappings."))
```

## Migration Safety

### Allowed Changes

| Change | Example |
|--------|---------|
| Add entity | New table |
| Add field | New column |
| Rename | UUID stays, name changes |
| Widen type | `int` -> `numeric`, `text` -> `jsonb` |
| Nullable: false->true | Allow NULL |

### Forbidden Changes (throw exception)

| Change | Reason |
|--------|--------|
| Remove entity | Data loss |
| Remove field | Data loss |
| Narrow type | `text` -> `int` — impossible conversion |
| Nullable: true->false | Existing NULLs become invalid |

## Types and Equivalence

```clojure
;; Type widening (no data loss)
(def type-widening
  {:int #{:numeric :text :jsonb}
   :bool #{:text :jsonb}
   :numeric #{:text :jsonb}
   :text #{:jsonb}
   :uuid #{:text}
   :timestamptz #{:text}})

;; Equivalent types (stored the same way)
(def type-equivalents
  #{#{:uuid :ref}      ; :ref stored as UUID
    #{:jsonb :union}}) ; :union stored as JSONB
```

## Utilities

### Safety Checks

```clojure
(safe-type-change? :int :numeric)   ; => true
(safe-type-change? :text :int)      ; => false

(safe-nullable-change? false true)  ; => true
(safe-nullable-change? true false)  ; => false
```

### Checks with Exceptions

```clojure
(check-type-change! :user :email :text :int)
;; => throws ExceptionInfo {:type :destructive-change ...}

(check-removed! "entities" old-uuids new-uuids name-fn)
;; => throws if any UUID removed
```

### Computing Diff

```clojure
(build-metadata-from-schema schema)
;; => {:entities {uuid->name}
;;     :fields {uuid->{:entity :field}}
;;     :enums {uuid->name}
;;     :enum-values {uuid->{:enum :value}}}

(compute-entity-changes old-metadata schema)
;; => {:created [:new-entity] :renamed {:old :new}}

(compute-field-changes old-metadata schema)
;; => {:created [{:entity :e :field :f}]
;;     :renamed [{:entity :e :old-field :o :new-field :n}]}
```

## Usage Example

```clojure
(require '[graphden.storage-protocol.interface :as sp])

;; Implementation creates storage
(def storage (create-my-storage))

;; Initialize/migrate
(let [changes (sp/initialize storage my-schema)]
  (println "Created entities:" (get-in changes [:entities :created]))
  (println "Renamed fields:" (get-in changes [:fields :renamed])))

;; Introspection
(sp/current-entities storage)     ; => #{:user :post}
(sp/current-fields storage :user) ; => {:name {:type :text} ...}

;; Close
(sp/close storage)
```

## Planned Extensions

### StorageCRUD (in development)

```clojure
(defprotocol StorageCRUD
  (create [this entity-name data])
  (read-by-id [this entity-name id])
  (update-by-id [this entity-name id data])
  (delete-by-id [this entity-name id])
  (query [this entity-name where]))
```

### GraphConstraints (in development)

Protocol for function graph integrity constraints:

```clojure
(defprotocol GraphConstraints
  (validate-parent-same-schema! [this fn-id parent-fn-id])
  (validate-no-arg-override! [this fn-id arg-schema-id])
  (validate-arg-schema-belongs-to-fn! [this fn-id arg-schema-id])
  (validate-no-inheritance-cycle! [this fn-id parent-fn-id])
  (validate-no-dependency-cycle! [this owner-fn-id target-fn-id]))
```

## Implementations

- [memory-storage](../memory-storage/) — In-memory (for tests and development)
- [postgres-storage](../postgres-storage/) — PostgreSQL
- [datomic-storage](../datomic-storage/) — Datomic

## Tests

```bash
bb test
```

Tests cover:
- Type safety checks (`safe-type-change?`, `safe-nullable-change?`)
- Validation utilities (`check-type-change!`, `check-nullable-change!`)
- Metadata building (`build-metadata-from-schema`)
- Change computation (`build-first-init-changes`, `check-all-removals!`)

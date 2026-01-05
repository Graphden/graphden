# memory-storage

In-memory implementation of Storage and StorageIntrospection protocols.

## Purpose

In-memory storage for:
- Development and debugging
- Unit tests
- Prototyping

Data is lost when the process terminates.

## Dependencies

- `storage-protocol` — Storage and StorageIntrospection protocols
- `data-schema-protocol` — DataSchema protocol

## API

### create-storage

Creates a new in-memory storage instance:

```clojure
(require '[graphden.memory-storage.interface :as mem]
         '[graphden.storage-protocol.interface :as sp])

(def storage (mem/create-storage))

;; Initialize schema
(sp/initialize storage my-schema)

;; Usage...

;; Close (clear data)
(sp/close storage)
```

## Internal Structure

### State Structure

```clojure
{:entities {:user {:fields {:name {:type :text :nullable? false}
                            :email {:type :text :nullable? true}}}}
 :enums {:status {:values #{:active :inactive}}}
 :metadata {:entities {uuid :entity-name}
            :fields {uuid {:entity :e :field :f}}
            :enums {uuid :enum-name}
            :enum-values {uuid {:enum :e :value :v}}}
 :data {:user {id-1 {:id id-1 :name "Alice" :email nil}
               id-2 {:id id-2 :name "Bob" :email "bob@example.com"}}}}
```

### Thread Safety

Uses an atom for state storage. All operations are atomic.

## Migrations

### Supported Changes

| Operation | Support |
|-----------|---------|
| Add entity | Yes |
| Add field | Yes |
| Rename entity | Yes (by UUID) |
| Rename field | Yes (by UUID) |
| Widen type | Yes (int->numeric) |
| Nullable: false->true | Yes |

### Data Migration on Rename

When renaming an entity or field, data is automatically migrated:

```clojure
;; Before: {:user {:name "Alice"}}
;; After renaming :name -> :full-name
;; Data: {:user {:full-name "Alice"}}
```

### Forbidden Changes

| Operation | Reason |
|-----------|--------|
| Remove entity | Data loss |
| Remove field | Data loss |
| Narrow type | Impossible conversion |
| Nullable: true->false | Existing NULLs |

## Type Checks

Uses utilities from `storage-protocol`:

```clojure
;; Safe changes
(sp/safe-type-change? :int :numeric)  ; => true
(sp/safe-type-change? :text :jsonb)   ; => true

;; Unsafe changes
(sp/safe-type-change? :text :int)     ; => false
```

## Full Usage Example

```clojure
(require '[graphden.memory-storage.interface :as mem]
         '[graphden.storage-protocol.interface :as sp]
         '[graphden.malli-data-schema.interface :as mds]
         '[graphden.data-schema-protocol.interface :as ds])

;; Create schema
(def schema
  (-> (mds/create-builder)
      (ds/add-entity :user #uuid "..."
                     {:name {:uuid #uuid "..." :type :text}})
      ds/build))

;; Create storage and initialize
(def storage (mem/create-storage))
(def changes (sp/initialize storage schema))

;; Check result
(:created (:entities changes))  ; => [:user]

;; Introspection
(sp/current-entities storage)   ; => #{:user}
(sp/current-fields storage :user) ; => {:name {:type :text :nullable? false}}

;; Close
(sp/close storage)
```

## Limitations

- No persistence (data in memory)
- No transactions (atomic operations only at atom level)
- No indexes (linear search)

For production use `postgres-storage` or `datomic-storage`.

## Tests

```bash
bb test
```

Tests cover:
- Schema initialization
- Introspection
- Migrations (renaming, adding fields)
- Destructive change validation

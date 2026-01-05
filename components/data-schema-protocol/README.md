# data-schema-protocol

Protocol for defining data schemas.

## Purpose

Defines an abstract interface for describing entities, their fields, and constraints. Schema is used by storage backends to create tables/collections.

## Dependencies

No external dependencies (base protocol).

## Protocols

### DataSchema

Protocol for reading data schema:

```clojure
(defprotocol DataSchema
  (entities [this]
    "Returns sequence of entity names.")

  (entity-uuid [this entity-name]
    "Returns entity UUID (for rename detection).")

  (entity-fields [this entity-name]
    "Returns map of fields: {field-name {:uuid ... :type ... :nullable? ...}}")

  (enums [this]
    "Returns map of enum types: {enum-name {:uuid ... :values {...}}}")

  (enum-uuid [this enum-name]
    "Returns enum type UUID.")

  (validate-entity [this entity-name data]
    "Validates data. Returns nil or {:errors {...}}")

  (entity-constraints [this entity-name]
    "Returns vector of constraints: [{:type :unique :fields [:f1 :f2]}]"))
```

### DataSchemaBuilder

Protocol for building schema:

```clojure
(defprotocol DataSchemaBuilder
  (add-enum [this enum-name enum-uuid values]
    "Adds enum type.")

  (add-entity [this entity-name entity-uuid fields]
    "Adds entity with fields.")

  (add-constraint [this entity-name constraint]
    "Adds constraint to entity.")

  (build [this]
    "Builds and validates final DataSchema."))
```

## Field Types

### Base Types

| Type | Description | Attributes |
|------|-------------|------------|
| `:uuid` | UUID identifier | `:uuid`, `:type`, `:nullable?` |
| `:text` | String | `:uuid`, `:type`, `:nullable?` |
| `:int` | Integer number | `:uuid`, `:type`, `:nullable?` |
| `:bool` | Boolean | `:uuid`, `:type`, `:nullable?` |
| `:numeric` | Number (int or double) | `:uuid`, `:type`, `:nullable?` |
| `:timestamptz` | Timestamp with timezone | `:uuid`, `:type`, `:nullable?` |
| `:jsonb` | JSON data | `:uuid`, `:type`, `:nullable?` |
| `:bytes` | Binary data | `:uuid`, `:type`, `:nullable?` |

### Special Types

| Type | Description | Additional Attributes |
|------|-------------|----------------------|
| `:ref` | Reference to another entity | `:ref-entity` (entity name) |
| `:enum` | Enumeration | `:enum-name` (enum type name) |
| `:union` | One of several types | `:variants` (vector of specs) |

## Implicit :id Field

Each entity automatically gets an `:id` field of type `:uuid` — the primary key.

## UUID for Identification

Each schema element has a stable UUID:

- **Entities** — `entity-uuid`
- **Fields** — `:uuid` in field spec
- **Enum types** — `:uuid` in enum description
- **Enum values** — UUID for each value

UUID allows storage backends to distinguish renames from deletions/creations.

## Usage Example

```clojure
(require '[graphden.data-schema-protocol.interface :as ds])

;; Reading schema
(ds/entities schema)
;; => [:user :post :comment]

(ds/entity-fields schema :user)
;; => {:name {:uuid #uuid "..." :type :text}
;;     :email {:uuid #uuid "..." :type :text :nullable? true}
;;     :role {:uuid #uuid "..." :type :enum :enum-name :user-role}}

(ds/enums schema)
;; => {:user-role {:uuid #uuid "..."
;;                 :values {:admin #uuid "..."
;;                          :user #uuid "..."}}}

;; Validation
(ds/validate-entity schema :user {:id (random-uuid) :name "Alice"})
;; => nil (valid)

(ds/validate-entity schema :user {:id (random-uuid)})
;; => {:errors {:name ["missing required key"]}}

;; Constraints
(ds/entity-constraints schema :user)
;; => [{:type :unique :fields [:email]}]
```

## Building Schema

```clojure
(require '[graphden.data-schema-protocol.interface :as ds])

(-> builder
    ;; First enum types
    (ds/add-enum :user-role #uuid "..."
                 [{:uuid #uuid "..." :value :admin}
                  {:uuid #uuid "..." :value :user}])

    ;; Then entities
    (ds/add-entity :user #uuid "..."
                   {:name {:uuid #uuid "..." :type :text}
                    :role {:uuid #uuid "..." :type :enum :enum-name :user-role}})

    ;; Constraints
    (ds/add-constraint :user {:type :unique :fields [:name]})

    ;; Build
    ds/build)
```

## Implementations

- [malli-data-schema](../malli-data-schema/) — Malli-based implementation

## Tests

Contract tests are in implementations (e.g., `malli-data-schema`).

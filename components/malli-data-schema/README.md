# malli-data-schema

DataSchema protocol implementation based on [Malli](https://github.com/metosin/malli).

## Purpose

Provides concrete implementation of `DataSchemaBuilder` and `DataSchema` protocols using Malli for data validation.

## Dependencies

- `data-schema-protocol` — DataSchema and DataSchemaBuilder protocols
- `metosin/malli` — validation library

## API

### create-builder

Creates a new builder for schema construction:

```clojure
(require '[graphden.malli-data-schema.interface :as mds])

(def builder (mds/create-builder))
```

### schema->malli

Returns Malli schema for an entity (for advanced introspection):

```clojure
(mds/schema->malli schema :user)
;; => [:map {:closed true}
;;     [:id :uuid]
;;     [:name :string]
;;     ...]
```

## Type Mapping

| field-types | Malli |
|-------------|-------|
| `:uuid` | `:uuid` |
| `:text` | `:string` |
| `:int` | `:int` |
| `:bool` | `:boolean` |
| `:numeric` | `[:or :int :double]` |
| `:timestamptz` | `inst?` |
| `:jsonb` | Recursive JSON schema |
| `:bytes` | `bytes?` |
| `:ref` | `:uuid` (stored as UUID) |
| `:enum` | `[:enum :val1 :val2 ...]` |
| `:union` | `[:or schema1 schema2 ...]` |

## JSONB Schema

For type `:jsonb` a recursive Malli schema is used:

```clojure
[:or
 :nil
 :boolean
 :int
 :double
 :string
 [:vector [:ref ::json]]
 [:map-of :string [:ref ::json]]]
```

## Build-time Validations

### add-enum Checks

- `enum-name` must be keyword
- `enum-uuid` must be UUID
- `values` — non-empty vector of `{:uuid ... :value ...}`
- No duplicate names or UUIDs

### add-entity Checks

- `entity-name` must be keyword
- `:id` is reserved
- Each field must have `:uuid` and `:type`
- No duplicate UUIDs (globally)

### add-constraint Checks

- `:type` must be known (`:unique`)
- `:fields` — non-empty vector of keywords
- No duplicate constraints

### build Checks

- All `:ref-entity` references point to existing entities
- All `:enum-name` references point to existing enums
- Union variants are non-empty and not duplicated

## Full Usage Example

```clojure
(require '[graphden.malli-data-schema.interface :as mds]
         '[graphden.data-schema-protocol.interface :as ds])

(def schema
  (-> (mds/create-builder)
      ;; Enum
      (ds/add-enum :role #uuid "10000000-0000-0000-0000-000000000001"
                   [{:uuid #uuid "10000000-0000-0000-0000-000000000002" :value :admin}
                    {:uuid #uuid "10000000-0000-0000-0000-000000000003" :value :user}])
      ;; Entity
      (ds/add-entity :user #uuid "20000000-0000-0000-0000-000000000001"
                     {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002"
                             :type :text}
                      :role {:uuid #uuid "20000000-0000-0000-0000-000000000003"
                             :type :enum
                             :enum-name :role}
                      :manager-id {:uuid #uuid "20000000-0000-0000-0000-000000000004"
                                   :type :ref
                                   :ref-entity :user
                                   :nullable? true}})
      ;; Constraint
      (ds/add-constraint :user {:type :unique :fields [:name]})
      ;; Build
      ds/build))

;; Usage
(ds/entities schema)
;; => (:user)

(ds/validate-entity schema :user
  {:id (random-uuid)
   :name "Alice"
   :role :admin
   :manager-id nil})
;; => nil (valid)

(ds/validate-entity schema :user
  {:id (random-uuid)
   :role :admin})
;; => {:errors {:name ["missing required key"]}}
```

## Union Types

```clojure
(ds/add-entity builder :arg-value #uuid "..."
  {:value {:uuid #uuid "..."
           :type :union
           :variants [{:type :ref :ref-entity :fn}  ; Function reference
                      {:type :int}                   ; int literal
                      {:type :text}                  ; text literal
                      {:type :bool}]}})              ; bool literal
```

Union variants cannot have `:nullable?` or `:uuid` — these are attributes only for top-level fields.

## Tests

```bash
bb test
```

Tests cover:
- Type mapping
- Data validation
- Build errors (duplicates, invalid references)
- Union types
- Constraints

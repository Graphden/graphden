(ns graphden.cache-datomic.schema
  "Datomic schema definitions for cache tables.

   Contains:
   - Attribute naming helper
   - Schema definitions for cached graph data
   - Schema initialization function"
  (:require
    [datomic.client.api :as d]))


;; === Attribute naming ===

(defn cache-attr
  "Creates a cache attribute ident.
   E.g., :graphden.cache/fn-id"
  [attr-name]
  (keyword "graphden.cache" (name attr-name)))


;; === Schema definition ===

(def cache-schema
  "Datomic schema for cache tables.
   This should be transacted once when setting up the database."
  [;; cached-fn entity
   {:db/ident (cache-attr :cached-fn-cache-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Cache ID (root fn-id for this cache)"}
   {:db/ident (cache-attr :cached-fn-fn-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Function ID within the cached graph"}
   {:db/ident (cache-attr :cached-fn-name)
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Function name"}
   {:db/ident (cache-attr :cached-fn-fn-schema-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Function schema ID"}
   {:db/ident (cache-attr :cached-fn-parent-fn-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Parent function ID (optional)"}

   ;; cached-fn-schema entity
   {:db/ident (cache-attr :cached-fn-schema-cache-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Cache ID"}
   {:db/ident (cache-attr :cached-fn-schema-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Function schema ID"}
   {:db/ident (cache-attr :cached-fn-schema-name)
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Function schema name"}
   {:db/ident (cache-attr :cached-fn-schema-base-fn-name)
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Base function name"}
   {:db/ident (cache-attr :cached-fn-schema-returned-type)
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Returned type keyword"}

   ;; cached-arg-schema entity
   {:db/ident (cache-attr :cached-arg-schema-cache-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Cache ID"}
   {:db/ident (cache-attr :cached-arg-schema-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Argument schema ID"}
   {:db/ident (cache-attr :cached-arg-schema-fn-schema-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Function schema ID this arg belongs to"}
   {:db/ident (cache-attr :cached-arg-schema-name)
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Argument name"}
   {:db/ident (cache-attr :cached-arg-schema-type)
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Argument type keyword"}
   {:db/ident (cache-attr :cached-arg-schema-required)
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Whether argument is required"}

   ;; cached-merged-arg entity
   {:db/ident (cache-attr :cached-merged-arg-cache-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Cache ID"}
   {:db/ident (cache-attr :cached-merged-arg-fn-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Function ID"}
   {:db/ident (cache-attr :cached-merged-arg-arg-schema-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Argument schema ID"}
   {:db/ident (cache-attr :cached-merged-arg-value)
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Serialized value (EDN string)"}

   ;; cache-fn-dep entity
   {:db/ident (cache-attr :cache-fn-dep-cache-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Cache ID"}
   {:db/ident (cache-attr :cache-fn-dep-dep-fn-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Dependent function ID"}
   {:db/ident (cache-attr :cache-fn-dep-ref-count)
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Reference count"}

   ;; cache-fn-schema-dep entity
   {:db/ident (cache-attr :cache-fn-schema-dep-cache-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Cache ID"}
   {:db/ident (cache-attr :cache-fn-schema-dep-dep-fn-schema-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Dependent function schema ID"}
   {:db/ident (cache-attr :cache-fn-schema-dep-ref-count)
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Reference count"}

   ;; cache-arg-schema-dep entity
   {:db/ident (cache-attr :cache-arg-schema-dep-cache-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Cache ID"}
   {:db/ident (cache-attr :cache-arg-schema-dep-dep-arg-schema-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Dependent argument schema ID"}
   {:db/ident (cache-attr :cache-arg-schema-dep-ref-count)
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Reference count"}

   ;; cache-fn-result-value-dep entity
   {:db/ident (cache-attr :cache-fn-result-value-dep-cache-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Cache ID"}
   {:db/ident (cache-attr :cache-fn-result-value-dep-dep-fn-result-value-id)
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc "Dependent fn-result-value ID"}
   {:db/ident (cache-attr :cache-fn-result-value-dep-ref-count)
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Reference count"}])


;; === Schema initialization ===

(defn ensure-cache-schema!
  "Ensures cache schema is transacted to the database.
   Safe to call multiple times - Datomic ignores duplicate schema definitions."
  [conn]
  (d/transact conn {:tx-data cache-schema}))

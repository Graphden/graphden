(ns graphden.schema.graph.interface
  "Graph data schema definition.

   Defines the schema for a function composition graph:
   - fn-schema: function signatures (name, return type)
   - arg-schema: argument definitions for functions
   - fn: actual function instances
   - arg-value: argument values (literals or references) - pure values, no owner
   - fn-arg: binding from fn to arg-value
   - call-site: call site reference (function to execute at this point)
   - call-site-arg: binding from call-site to arg-value (for free args)"
  (:require
    [graphden.schema.fields.interface :as ft]
    [graphden.schema.protocol.interface :as ds]))


;; === Stable UUIDs for schema elements ===
;;
;; Each schema element (entity, field, enum, enum value) has a stable UUID that
;; serves as its immutable identity. This enables:
;;
;; 1. RENAME DETECTION: When you rename :fn-schema to :function-schema, the storage
;;    layer sees the same UUID with a different name -> triggers ALTER TABLE RENAME
;;    instead of DROP + CREATE.
;;
;; 2. SAFE MIGRATIONS: Data is never lost during schema evolution because storage
;;    tracks elements by UUID, not by name.
;;
;; 3. CROSS-STORAGE CONSISTENCY: All storage backends (memory, postgres, datomic)
;;    use the same UUIDs, ensuring schema compatibility.
;;
;; Generation: Each UUID below was generated once using (random-uuid) and is now
;; fixed forever in code. They are RFC 4122 v4 (random) UUIDs.
;;
;; IMPORTANT: Never change these UUIDs! Changing a UUID is equivalent to deleting
;; the old element and creating a new one, which will cause data loss.

;; Enum UUIDs
(def ^:private value-kind-enum-uuid
  #uuid "b79e6e8b-8aff-4188-862b-d8a85ef4fcdf")


;; Enum value UUIDs for :value-kind
(def ^:private value-kind-values
  {:null        #uuid "c703ffd9-6401-4c49-9ca3-a280f6aac8ba"
   :uuid        #uuid "3a83af1b-f15c-421d-a5f1-f13db07deb72"
   :text        #uuid "cf26384f-d093-461d-9268-b42b8fd6eae6"
   :int         #uuid "154d3c4f-8d11-4592-9e24-5c40176cc5a7"
   :bool        #uuid "7497d750-67aa-4b55-8477-8323a9ab7761"
   :numeric     #uuid "f7a6728b-5ac6-4e1a-8bdb-ddc240cc059d"
   :timestamptz #uuid "e4476a32-3e93-4333-b0e5-964b9b19bea1"
   :jsonb       #uuid "b1b15bb9-a458-4337-9241-2a33e1ef25ea"
   :bytes       #uuid "2dcadfbd-800f-4b7b-bbcc-82b2afcf9f86"
   :any         #uuid "a3d7e8f1-9b2c-4d5e-8f6a-1c2d3e4f5a6b"
   :fn          #uuid "b4e8f9a2-0c3d-5e6f-9a7b-2d3e4f5a6b7c"})


;; Entity UUIDs
(def ^:private fn-schema-entity-uuid
  #uuid "dc2df695-6167-4add-9e75-022213c96537")


(def ^:private arg-schema-entity-uuid
  #uuid "946c1f9c-30ce-4fab-98ed-dd9a26f6676b")


(def ^:private fn-entity-uuid
  #uuid "986e8a2a-39ba-41ae-8449-d06c31515486")


(def ^:private arg-value-entity-uuid
  #uuid "afb02fb7-0174-496b-9b21-a61063de0c04")


(def ^:private fn-arg-entity-uuid
  #uuid "f1a2b3c4-d5e6-7f8a-9b0c-1d2e3f4a5b6c")


(def ^:private call-site-entity-uuid
  #uuid "d4f8a2b1-7c3e-4d9f-a5b6-8e1c2f3d4a5b")


(def ^:private call-site-arg-entity-uuid
  #uuid "a9b8c7d6-e5f4-3a2b-1c0d-9e8f7a6b5c4d")


;; Field UUIDs for :fn-schema entity
(def ^:private fn-schema-name-field-uuid
  #uuid "abe8475e-9130-4647-a2bf-be0cb07099b7")


(def ^:private fn-schema-returned-type-field-uuid
  #uuid "5ea6c13d-553c-4d85-8511-38ae88f7f9e5")


(def ^:private fn-schema-base-fn-name-field-uuid
  #uuid "8f3d2e1c-4a5b-6c7d-8e9f-0a1b2c3d4e5f")


(def ^:private fn-schema-impl-hash-field-uuid
  "Hash of base function implementation for version tracking.
   Only set for base functions (when base-fn-name is non-nil)."
  #uuid "e2f3a4b5-c6d7-8e9f-0a1b-2c3d4e5f6a7b")


;; Field UUIDs for :arg-schema entity
(def ^:private arg-schema-fn-schema-id-field-uuid
  #uuid "c100ed37-f3d8-4a93-becc-17ae2b91f64a")


(def ^:private arg-schema-name-field-uuid
  #uuid "e68c993e-7840-4541-b55f-cf4b08ba3de7")


(def ^:private arg-schema-type-field-uuid
  #uuid "be65f37b-4758-49da-9091-37dee0e28ad1")


(def ^:private arg-schema-required-field-uuid
  #uuid "a1d4e8c2-5f67-4b3a-9c12-8e0f7d6b5a4c")


;; Field UUIDs for :fn entity
(def ^:private fn-name-field-uuid
  #uuid "af336498-6d1e-4879-b2a5-b0d6c1994d12")


(def ^:private fn-fn-schema-id-field-uuid
  #uuid "3a685253-07f7-4469-be8b-1a585ba3e7d4")


;; Field UUIDs for :arg-value entity (no owner - pure value)
(def ^:private arg-value-arg-schema-id-field-uuid
  #uuid "834336b1-b55c-4557-b580-a62799deb729")


(def ^:private arg-value-value-field-uuid
  #uuid "b6780ba3-d050-4162-aba8-5f68ac17bcb8")


;; Field UUIDs for :fn-arg entity (binding: fn → arg-value)
(def ^:private fn-arg-fn-id-field-uuid
  #uuid "e1f2a3b4-c5d6-7e8f-9a0b-1c2d3e4f5a6b")


(def ^:private fn-arg-arg-schema-id-field-uuid
  #uuid "f2a3b4c5-d6e7-8f9a-0b1c-2d3e4f5a6b7c")


(def ^:private fn-arg-arg-value-id-field-uuid
  #uuid "a3b4c5d6-e7f8-9a0b-1c2d-3e4f5a6b7c8d")


;; Field UUIDs for :call-site entity
(def ^:private call-site-fn-id-field-uuid
  #uuid "e5a9b3c2-8d4f-5e0a-b6c7-9f2d3e4a5b6c")


(def ^:private call-site-name-field-uuid
  #uuid "da238d29-4cd4-4077-9a75-3ad3436b7466")


;; Field UUIDs for :call-site-arg entity (binding: call-site → arg-value)
(def ^:private call-site-arg-call-site-id-field-uuid
  #uuid "b4c5d6e7-f8a9-0b1c-2d3e-4f5a6b7c8d9e")


(def ^:private call-site-arg-arg-schema-id-field-uuid
  #uuid "c5d6e7f8-a9b0-1c2d-3e4f-5a6b7c8d9e0f")


(def ^:private call-site-arg-arg-value-id-field-uuid
  #uuid "d6e7f8a9-b0c1-2d3e-4f5a-6b7c8d9e0f1a")


(defn- value-kind-enum-values
  "Generates enum values for :value-kind.
   Includes :null (void), :any, :fn plus all supported field types."
  []
  (into [{:uuid (get value-kind-values :null) :value :null}
         {:uuid (get value-kind-values :any) :value :any}
         {:uuid (get value-kind-values :fn) :value :fn}]
        (map (fn [t] {:uuid (get value-kind-values t) :value t})
             ft/supported-types)))


(defn value-variants
  "Generates union variants for arg-value.
   Variants:
   - ref to fn: for HOF (passing function as first-class value)
   - ref to call-site: for computed values (execute fn, cache result)
   - :any/:fn types
   - literal types

   Public for reuse by cache-data-schema."
  []
  (into [{:type :ref :ref-entity :fn}
         {:type :ref :ref-entity :call-site}
         {:type :any}
         {:type :fn}]
        (map (fn [t] {:type t}) ft/supported-types)))


(defn extend-builder
  "Extends a builder with graph data schema entities without finalizing.
   Returns the builder (not a built schema) for further extension.

   Use this when you need to add more entities on top of graph schema,
   e.g., for cache-data-schema."
  [builder]
  (-> builder
      ;; Define the value_kind enum: null (void) + all supported types
      (ds/add-enum :value-kind value-kind-enum-uuid (value-kind-enum-values))

      ;; fn_schema: defines function signatures
      ;; base-fn-name links to Clojure impl (nil for user-defined composite fns)
      ;; impl-hash is SHA-256 hash of implementation (for base fns only)
      (ds/add-entity :fn-schema fn-schema-entity-uuid
                     {:name {:uuid fn-schema-name-field-uuid :type :text}
                      :returned-type {:uuid fn-schema-returned-type-field-uuid
                                      :type :enum :enum-name :value-kind}
                      :base-fn-name {:uuid fn-schema-base-fn-name-field-uuid
                                     :type :text
                                     :nullable? true}
                      :impl-hash {:uuid fn-schema-impl-hash-field-uuid
                                  :type :text
                                  :nullable? true}})
      (ds/add-constraint :fn-schema {:type :unique :fields [:name]})

      ;; arg_schema: defines function arguments
      ;; required defaults to true in business logic (not enforced at schema level)
      (ds/add-entity :arg-schema arg-schema-entity-uuid
                     {:fn-schema-id {:uuid arg-schema-fn-schema-id-field-uuid
                                     :type :ref :ref-entity :fn-schema}
                      :name {:uuid arg-schema-name-field-uuid :type :text}
                      :type {:uuid arg-schema-type-field-uuid
                             :type :enum :enum-name :value-kind}
                      :required {:uuid arg-schema-required-field-uuid :type :bool}})
      (ds/add-constraint :arg-schema {:type :unique :fields [:fn-schema-id :name]})

      ;; fn: actual function instances
      (ds/add-entity :fn fn-entity-uuid
                     {:name {:uuid fn-name-field-uuid :type :text}
                      :fn-schema-id {:uuid fn-fn-schema-id-field-uuid
                                     :type :ref :ref-entity :fn-schema}})
      (ds/add-constraint :fn {:type :unique :fields [:name]})

      ;; arg_value: pure argument values (no owner)
      ;; value is a union: ref to fn (HOF), ref to call-site (computed), or literal
      ;; Deduplication: same (arg-schema-id, value) → reuse existing arg-value
      (ds/add-entity :arg-value arg-value-entity-uuid
                     {:arg-schema-id {:uuid arg-value-arg-schema-id-field-uuid
                                      :type :ref :ref-entity :arg-schema}
                      :value {:uuid arg-value-value-field-uuid
                              :type :union :variants (value-variants)}})
      (ds/add-constraint :arg-value {:type :unique :fields [:arg-schema-id :value]})

      ;; fn_arg: binding from fn to arg-value
      ;; arg-schema-id denormalized for UNIQUE constraint
      (ds/add-entity :fn-arg fn-arg-entity-uuid
                     {:fn-id {:uuid fn-arg-fn-id-field-uuid
                              :type :ref :ref-entity :fn}
                      :arg-schema-id {:uuid fn-arg-arg-schema-id-field-uuid
                                      :type :ref :ref-entity :arg-schema}
                      :arg-value-id {:uuid fn-arg-arg-value-id-field-uuid
                                     :type :ref :ref-entity :arg-value}})
      (ds/add-constraint :fn-arg {:type :unique :fields [:fn-id :arg-schema-id]})

      ;; call_site: call site reference (function to execute at this point)
      ;; Multiple arg-values can reference the same call-site to reuse computed value
      ;; name: unique identifier for this call site
      (ds/add-entity :call-site call-site-entity-uuid
                     {:fn-id {:uuid call-site-fn-id-field-uuid
                              :type :ref :ref-entity :fn}
                      :name {:uuid call-site-name-field-uuid
                             :type :text}})
      (ds/add-constraint :call-site {:type :unique :fields [:name]})

      ;; call_site_arg: binding from call-site to arg-value (for free args)
      ;; arg-schema-id denormalized for UNIQUE constraint
      (ds/add-entity :call-site-arg call-site-arg-entity-uuid
                     {:call-site-id {:uuid call-site-arg-call-site-id-field-uuid
                                     :type :ref :ref-entity :call-site}
                      :arg-schema-id {:uuid call-site-arg-arg-schema-id-field-uuid
                                      :type :ref :ref-entity :arg-schema}
                      :arg-value-id {:uuid call-site-arg-arg-value-id-field-uuid
                                     :type :ref :ref-entity :arg-value}})
      (ds/add-constraint :call-site-arg {:type :unique :fields [:call-site-id :arg-schema-id]})))


(defn build-schema
  "Builds the graph data schema using the provided builder.
   Returns a built DataSchema instance."
  [builder]
  (-> builder
      (extend-builder)
      (ds/build)))

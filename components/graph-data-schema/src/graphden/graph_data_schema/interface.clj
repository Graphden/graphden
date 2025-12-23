(ns graphden.graph-data-schema.interface
  "Graph data schema definition.

   Defines the schema for a function composition graph:
   - fn-schema: function signatures (name, return type)
   - arg-schema: argument definitions for functions
   - fn: actual function instances
   - arg-value: argument values (literals or references to other functions)"
  (:require
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.field-types.interface :as ft]))


;; === Stable UUIDs for schema elements ===
;; These are cryptographically random RFC 4122 v4 UUIDs.
;; Generated once with (random-uuid) and fixed forever in code.
;; UUID is the stable identity of an element - names can change, UUIDs cannot.
;; This allows storage to detect renames vs delete+create.

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
   :bytes       #uuid "2dcadfbd-800f-4b7b-bbcc-82b2afcf9f86"})


;; Entity UUIDs
(def ^:private fn-schema-entity-uuid
  #uuid "dc2df695-6167-4add-9e75-022213c96537")


(def ^:private arg-schema-entity-uuid
  #uuid "946c1f9c-30ce-4fab-98ed-dd9a26f6676b")


(def ^:private fn-entity-uuid
  #uuid "986e8a2a-39ba-41ae-8449-d06c31515486")


(def ^:private arg-value-entity-uuid
  #uuid "afb02fb7-0174-496b-9b21-a61063de0c04")


;; Field UUIDs for :fn-schema entity
(def ^:private fn-schema-name-field-uuid
  #uuid "abe8475e-9130-4647-a2bf-be0cb07099b7")


(def ^:private fn-schema-returned-type-field-uuid
  #uuid "5ea6c13d-553c-4d85-8511-38ae88f7f9e5")


;; Field UUIDs for :arg-schema entity
(def ^:private arg-schema-fn-schema-id-field-uuid
  #uuid "c100ed37-f3d8-4a93-becc-17ae2b91f64a")


(def ^:private arg-schema-name-field-uuid
  #uuid "e68c993e-7840-4541-b55f-cf4b08ba3de7")


(def ^:private arg-schema-type-field-uuid
  #uuid "be65f37b-4758-49da-9091-37dee0e28ad1")


;; Field UUIDs for :fn entity
(def ^:private fn-name-field-uuid
  #uuid "af336498-6d1e-4879-b2a5-b0d6c1994d12")


(def ^:private fn-fn-schema-id-field-uuid
  #uuid "3a685253-07f7-4469-be8b-1a585ba3e7d4")


;; Field UUIDs for :arg-value entity
(def ^:private arg-value-owner-fn-id-field-uuid
  #uuid "d9331598-36b3-4238-83f8-16558d8b3a7e")


(def ^:private arg-value-arg-schema-id-field-uuid
  #uuid "834336b1-b55c-4557-b580-a62799deb729")


(def ^:private arg-value-value-field-uuid
  #uuid "b6780ba3-d050-4162-aba8-5f68ac17bcb8")


(defn- value-kind-enum-values
  "Generates enum values for :value-kind.
   Includes :null (void) plus all supported field types."
  []
  (into [{:uuid (get value-kind-values :null) :value :null}]
        (map (fn [t] {:uuid (get value-kind-values t) :value t})
             ft/supported-types)))


(defn- value-variants
  "Generates union variants for arg-value.
   First variant is fn reference, rest are literal types from field-types."
  []
  (into [{:type :ref :ref-entity :fn}]
        (map (fn [t] {:type t}) ft/supported-types)))


(defn build-schema
  "Builds the graph data schema using the provided builder.
   Returns a built DataSchema instance."
  [builder]
  (-> builder
      ;; Define the value_kind enum: null (void) + all supported types
      (ds/add-enum :value-kind value-kind-enum-uuid (value-kind-enum-values))

      ;; fn_schema: defines function signatures
      (ds/add-entity :fn-schema fn-schema-entity-uuid
                     {:name {:uuid fn-schema-name-field-uuid :type :text}
                      :returned-type {:uuid fn-schema-returned-type-field-uuid
                                      :type :enum :enum-name :value-kind}})
      (ds/add-constraint :fn-schema {:type :unique :fields [:name]})

      ;; arg_schema: defines function arguments
      (ds/add-entity :arg-schema arg-schema-entity-uuid
                     {:fn-schema-id {:uuid arg-schema-fn-schema-id-field-uuid
                                     :type :ref :ref-entity :fn-schema}
                      :name {:uuid arg-schema-name-field-uuid :type :text}
                      :type {:uuid arg-schema-type-field-uuid
                             :type :enum :enum-name :value-kind}})
      (ds/add-constraint :arg-schema {:type :unique :fields [:fn-schema-id :name]})

      ;; fn: actual function instances
      (ds/add-entity :fn fn-entity-uuid
                     {:name {:uuid fn-name-field-uuid :type :text}
                      :fn-schema-id {:uuid fn-fn-schema-id-field-uuid
                                     :type :ref :ref-entity :fn-schema}})
      (ds/add-constraint :fn {:type :unique :fields [:name]})

      ;; arg_value: argument values for function instances
      ;; value is a union: either a reference to another fn, or a literal value
      (ds/add-entity :arg-value arg-value-entity-uuid
                     {:owner-fn-id {:uuid arg-value-owner-fn-id-field-uuid
                                    :type :ref :ref-entity :fn}
                      :arg-schema-id {:uuid arg-value-arg-schema-id-field-uuid
                                      :type :ref :ref-entity :arg-schema}
                      :value {:uuid arg-value-value-field-uuid
                              :type :union :variants (value-variants)}})
      (ds/add-constraint :arg-value {:type :unique :fields [:owner-fn-id :arg-schema-id]})

      (ds/build)))

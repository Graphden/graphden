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
;; These UUIDs never change, allowing storage to track identity across renames.

;; Enum UUIDs
(def ^:private value-kind-enum-uuid
  #uuid "10000000-0000-0000-0000-000000000001")


;; Enum value UUIDs for :value-kind
(def ^:private value-kind-values
  {:null        #uuid "10000000-0000-0000-0001-000000000001"
   :uuid        #uuid "10000000-0000-0000-0001-000000000002"
   :text        #uuid "10000000-0000-0000-0001-000000000003"
   :int         #uuid "10000000-0000-0000-0001-000000000004"
   :bool        #uuid "10000000-0000-0000-0001-000000000005"
   :numeric     #uuid "10000000-0000-0000-0001-000000000006"
   :timestamptz #uuid "10000000-0000-0000-0001-000000000007"
   :jsonb       #uuid "10000000-0000-0000-0001-000000000008"
   :bytes       #uuid "10000000-0000-0000-0001-000000000009"})


;; Entity UUIDs
(def ^:private fn-schema-entity-uuid
  #uuid "20000000-0000-0000-0000-000000000001")


(def ^:private arg-schema-entity-uuid
  #uuid "20000000-0000-0000-0000-000000000002")


(def ^:private fn-entity-uuid
  #uuid "20000000-0000-0000-0000-000000000003")


(def ^:private arg-value-entity-uuid
  #uuid "20000000-0000-0000-0000-000000000004")


;; Field UUIDs for :fn-schema
(def ^:private fn-schema-name-field-uuid
  #uuid "30000000-0001-0000-0000-000000000001")


(def ^:private fn-schema-returned-type-field-uuid
  #uuid "30000000-0001-0000-0000-000000000002")


;; Field UUIDs for :arg-schema
(def ^:private arg-schema-fn-schema-id-field-uuid
  #uuid "30000000-0002-0000-0000-000000000001")


(def ^:private arg-schema-name-field-uuid
  #uuid "30000000-0002-0000-0000-000000000002")


(def ^:private arg-schema-type-field-uuid
  #uuid "30000000-0002-0000-0000-000000000003")


;; Field UUIDs for :fn
(def ^:private fn-name-field-uuid
  #uuid "30000000-0003-0000-0000-000000000001")


(def ^:private fn-fn-schema-id-field-uuid
  #uuid "30000000-0003-0000-0000-000000000002")


;; Field UUIDs for :arg-value
(def ^:private arg-value-owner-fn-id-field-uuid
  #uuid "30000000-0004-0000-0000-000000000001")


(def ^:private arg-value-arg-schema-id-field-uuid
  #uuid "30000000-0004-0000-0000-000000000002")


(def ^:private arg-value-value-field-uuid
  #uuid "30000000-0004-0000-0000-000000000003")


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

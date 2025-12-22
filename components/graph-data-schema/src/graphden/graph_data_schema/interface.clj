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
      (ds/add-enum :value-kind (into [:null] ft/supported-types))

      ;; fn_schema: defines function signatures
      (ds/add-entity :fn-schema
                     {:name {:type :text :nullable? false :unique? true}
                      :returned-type {:type :enum :enum-name :value-kind :nullable? false}})

      ;; arg_schema: defines function arguments
      (ds/add-entity :arg-schema
                     {:fn-schema-id {:type :ref :ref-entity :fn-schema :nullable? false}
                      :name {:type :text :nullable? false}
                      :type {:type :enum :enum-name :value-kind :nullable? false}})

      ;; fn: actual function instances
      (ds/add-entity :fn
                     {:name {:type :text :nullable? false :unique? true}
                      :fn-schema-id {:type :ref :ref-entity :fn-schema :nullable? false}})

      ;; arg_value: argument values for function instances
      ;; value is a union: either a reference to another fn, or a literal value
      (ds/add-entity :arg-value
                     {:owner-fn-id {:type :ref :ref-entity :fn :nullable? false}
                      :arg-schema-id {:type :ref :ref-entity :arg-schema :nullable? false}
                      :value {:type :union :variants (value-variants)}})

      (ds/build)))

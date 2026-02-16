(ns graphden.schema.malli.test-helpers
  "Test helpers and example schema for malli-data-schema tests."
  (:require
    [graphden.schema.malli.interface :as mds]
    [graphden.schema.protocol.interface :as ds]))


;; Helper to generate UUIDs for tests
(defn uuid
  []
  (random-uuid))


(def example-schema
  "Example schema representing a function definition system.
   Based on the following structure:

   Enum value_kind {null, bool, int, numeric, text, uuid, timestamptz, jsonb, bytes}

   Table fn_schema {id, name, returned_type}
   Table arg_schema {id, fn_schema_id, name, type}
   Table fn {id, name, fn_schema_id}
   Table arg_value {id, owner_fn_id, arg_schema_id, value}

   The value field is a union type - it can be either a reference to another
   function (for composition) or a literal value of any supported type."
  (-> (mds/create-builder)

      ;; Define the value_kind enum
      (ds/add-enum :value-kind (uuid)
                   [{:uuid (uuid) :value :null}
                    {:uuid (uuid) :value :bool}
                    {:uuid (uuid) :value :int}
                    {:uuid (uuid) :value :numeric}
                    {:uuid (uuid) :value :text}
                    {:uuid (uuid) :value :uuid}
                    {:uuid (uuid) :value :timestamptz}
                    {:uuid (uuid) :value :jsonb}
                    {:uuid (uuid) :value :bytes}])

      ;; fn_schema: defines function signatures
      (ds/add-entity :fn-schema (uuid)
                     {:name {:uuid (uuid) :type :text}
                      :returned-type {:uuid (uuid) :type :enum :enum-name :value-kind}})
      (ds/add-constraint :fn-schema {:type :unique :fields [:name]})

      ;; arg_schema: defines function arguments
      (ds/add-entity :arg-schema (uuid)
                     {:fn-schema-id {:uuid (uuid) :type :ref :ref-entity :fn-schema}
                      :name {:uuid (uuid) :type :text}
                      :type {:uuid (uuid) :type :enum :enum-name :value-kind}})

      ;; fn: actual function instances
      (ds/add-entity :fn (uuid)
                     {:name {:uuid (uuid) :type :text}
                      :fn-schema-id {:uuid (uuid) :type :ref :ref-entity :fn-schema}})
      (ds/add-constraint :fn {:type :unique :fields [:name]})

      ;; arg_value: argument values for function instances
      ;; value is a union: either a reference to another fn, or a literal value
      (ds/add-entity :arg-value (uuid)
                     {:owner-fn-id {:uuid (uuid) :type :ref :ref-entity :fn}
                      :arg-schema-id {:uuid (uuid) :type :ref :ref-entity :arg-schema}
                      :value {:uuid (uuid) :type :union
                              :variants [{:type :ref :ref-entity :fn}
                                         {:type :bool}
                                         {:type :int}
                                         {:type :numeric}
                                         {:type :text}
                                         {:type :uuid}
                                         {:type :timestamptz}
                                         {:type :jsonb}
                                         {:type :bytes}]}})

      (ds/build)))

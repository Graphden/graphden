(ns graphden.schema.malli.test-helpers
  "Test helpers and example schema for malli-data-schema tests.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]))


;; Helper to generate UUIDs for tests
(defn uuid
  []
  (random-uuid))


(def example-schema
  "Example schema representing a function composition system.
   Uses the 2-entity model:

   Enum value_kind {null, bool, int, numeric, text, uuid, timestamptz, jsonb, bytes, any, fn}

   Table fn {id, name, parent-id, return-type, impl-hash}
   Table arg {id, fn-id, name, type, source-id, value, ref-id, is-fn, required}

   - fn: parent-id=nil for base-fn, parent-id=ref to parent fn for composed fn
   - arg: fn-id = owner fn, source-id = parent arg for inheritance,
          value = literal, ref-id = fn reference, is-fn = HOF flag"
  (-> (mds/create-builder)

      ;; Define the value_kind enum (includes :any and :fn)
      (ds/add-enum :value-kind (uuid)
                   [{:uuid (uuid) :value :null}
                    {:uuid (uuid) :value :bool}
                    {:uuid (uuid) :value :int}
                    {:uuid (uuid) :value :numeric}
                    {:uuid (uuid) :value :text}
                    {:uuid (uuid) :value :uuid}
                    {:uuid (uuid) :value :timestamptz}
                    {:uuid (uuid) :value :jsonb}
                    {:uuid (uuid) :value :bytes}
                    {:uuid (uuid) :value :any}
                    {:uuid (uuid) :value :fn}])

      ;; fn: function entity with parent-id for composition
      (ds/add-entity :fn (uuid)
                     {:name {:uuid (uuid) :type :text}
                      :parent-id {:uuid (uuid) :type :ref :ref-entity :fn :nullable? true}
                      :return-type {:uuid (uuid) :type :enum :enum-name :value-kind :nullable? true}
                      :impl-hash {:uuid (uuid) :type :text :nullable? true}})
      (ds/add-constraint :fn {:type :unique :fields [:name]})

      ;; arg: argument entity with all data fields
      (ds/add-entity :arg (uuid)
                     {:fn-id {:uuid (uuid) :type :ref :ref-entity :fn}
                      :name {:uuid (uuid) :type :text}
                      :type {:uuid (uuid) :type :enum :enum-name :value-kind :nullable? true}
                      :source-id {:uuid (uuid) :type :ref :ref-entity :arg :nullable? true}
                      :value {:uuid (uuid) :type :jsonb :nullable? true}
                      :ref-id {:uuid (uuid) :type :ref :ref-entity :fn :nullable? true}
                      :is-fn {:uuid (uuid) :type :bool :nullable? true}
                      :required {:uuid (uuid) :type :bool :nullable? true}})

      (ds/build)))

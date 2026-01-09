(ns graphden.storage-protocol.test-helpers
  "Shared test utilities for storage implementations.

   This namespace provides common helpers used across postgres-storage,
   datomic-storage, and memory-storage test suites."
  (:require
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]))


(defn make-schema
  "Creates a simple schema with one entity and optional enum for testing.

   Options:
   - :entity-name - keyword for entity name (default :user)
   - :entity-uuid - UUID for entity (default fixed UUID)
   - :fields - map of field definitions (default {:name {:uuid ... :type :text}})
   - :enum-name - keyword for enum name (optional)
   - :enum-uuid - UUID for enum (required if enum-name provided)
   - :enum-values - set of enum values (required if enum-name provided)
   - :constraints - vector of constraint definitions (optional)

   Example:
   (make-schema)
   (make-schema :entity-name :person :fields {:age {:uuid (random-uuid) :type :int}})
   (make-schema :enum-name :status :enum-uuid (random-uuid) :enum-values #{:active :inactive})"
  [& {:keys [entity-name entity-uuid fields enum-name enum-uuid enum-values constraints]
      :or {entity-name :user
           entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
           fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                          :type :text}}}}]
  (let [builder (cond-> (mds/create-builder)
                  (and enum-name enum-uuid enum-values)
                  (ds/add-enum enum-name enum-uuid enum-values)

                  true
                  (ds/add-entity entity-name entity-uuid fields))
        builder-with-constraints (reduce
                                   (fn [b c] (ds/add-constraint b entity-name c))
                                   builder
                                   (or constraints []))]
    (ds/build builder-with-constraints)))


(defn make-graph-schema
  "Creates a schema for graph testing with fn, fn-schema, arg-schema, arg-value entities.

   This is useful for testing ExecutionGraph resolution and related functionality."
  []
  (-> (mds/create-builder)
      (ds/add-entity :fn-schema
                     #uuid "00000000-0000-0000-0000-000000000010"
                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000000011"
                             :type :text}
                      :returned-type {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                      :type :text}})
      (ds/add-entity :arg-schema
                     #uuid "00000000-0000-0000-0000-000000000020"
                     {:fn-schema-id {:uuid #uuid "00000000-0000-0000-0000-000000000021"
                                     :type :ref
                                     :ref-entity :fn-schema}
                      :name {:uuid #uuid "00000000-0000-0000-0000-000000000022"
                             :type :text}
                      :type {:uuid #uuid "00000000-0000-0000-0000-000000000023"
                             :type :text}
                      :required {:uuid #uuid "00000000-0000-0000-0000-000000000024"
                                 :type :bool}})
      (ds/add-entity :fn
                     #uuid "00000000-0000-0000-0000-000000000030"
                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000000031"
                             :type :text}
                      :fn-schema-id {:uuid #uuid "00000000-0000-0000-0000-000000000032"
                                     :type :ref
                                     :ref-entity :fn-schema}
                      :parent-fn-id {:uuid #uuid "00000000-0000-0000-0000-000000000033"
                                     :type :ref
                                     :ref-entity :fn
                                     :nullable? true}})
      (ds/add-entity :arg-value
                     #uuid "00000000-0000-0000-0000-000000000040"
                     {:owner-fn-id {:uuid #uuid "00000000-0000-0000-0000-000000000041"
                                    :type :ref
                                    :ref-entity :fn}
                      :arg-schema-id {:uuid #uuid "00000000-0000-0000-0000-000000000042"
                                      :type :ref
                                      :ref-entity :arg-schema}
                      :value {:uuid #uuid "00000000-0000-0000-0000-000000000043"
                              :type :jsonb
                              :nullable? true}
                      :value-fn-id {:uuid #uuid "00000000-0000-0000-0000-000000000044"
                                    :type :ref
                                    :ref-entity :fn
                                    :nullable? true}})
      ds/build))

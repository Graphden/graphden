(ns graphden.storage-protocol.test-helpers
  "Shared test utilities for storage implementations.

   This namespace provides common helpers used across postgres-storage,
   datomic-storage, and memory-storage test suites.

   Key utilities:
   - make-schema / make-graph-schema: Create test schemas
   - create-test-storage: Create pre-initialized memory storage
   - with-test-storage: Macro for test storage lifecycle
   - create-spy-storage: Wrap storage to track method calls"
  (:require
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.memory-storage.interface :as mem]
    [graphden.storage-protocol.interface :as sp]))


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


;; ============================================================================
;; Test Storage Utilities
;; ============================================================================

(defn create-test-storage
  "Creates an in-memory storage instance for testing.
   Optionally initializes with a schema.

   Arguments:
   - schema: Optional DataSchema to initialize storage with.
             If not provided, creates uninitialized storage.

   Returns:
   A MemoryStorage instance ready for testing.

   Example:
     (create-test-storage)                    ; uninitialized
     (create-test-storage (make-schema))      ; with simple schema
     (create-test-storage (make-graph-schema)); with graph schema"
  ([]
   (mem/create-storage))
  ([schema]
   (let [storage (mem/create-storage)]
     (sp/initialize storage schema)
     storage)))


(defmacro with-test-storage
  "Executes body with a test storage bound to sym.
   Automatically closes storage when done.

   Arguments:
   - binding: Vector of [sym schema] or [sym] for uninitialized
   - body: Forms to execute with storage available

   Example:
     (with-test-storage [s (make-schema)]
       (sp/create-entity s :user {:id (random-uuid) :name \"Alice\"})
       (is (= 1 (count (sp/query-entities s :user {})))))"
  [[sym schema] & body]
  `(let [~sym (if ~schema
                (create-test-storage ~schema)
                (create-test-storage))]
     (try
       (do ~@body)
       (finally
         (sp/close ~sym)))))


;; ============================================================================
;; Spy Storage Wrapper
;; ============================================================================
;;
;; Wraps a storage to track method calls for testing assertions.
;; Useful for verifying that certain methods were called with expected args.

(defn create-spy-storage
  "Wraps a storage instance to track method calls.

   Returns a map with:
   - :storage - The wrapped storage (use this for operations)
   - :calls - Atom containing a vector of {:method :args} maps
   - :reset-calls! - Function to clear the call log
   - :get-calls - Function to get calls for a specific method

   Example:
     (let [{:keys [storage calls get-calls]} (create-spy-storage (create-test-storage (make-schema)))]
       (sp/create-entity storage :user {:id (random-uuid) :name \"Test\"})
       (is (= 1 (count (get-calls :create-entity))))
       (is (= :user (-> (get-calls :create-entity) first :args second))))"
  [base-storage]
  (let [calls (atom [])
        record-call! (fn [method args]
                       (swap! calls conj {:method method :args (vec args)}))
        get-calls (fn [method]
                    (filterv #(= method (:method %)) @calls))
        reset-calls! (fn [] (reset! calls []))
        ;; Create a wrapper that delegates to base-storage and records calls
        wrapped (reify
                  sp/Storage
                  (initialize
                    [_ schema]
                    (record-call! :initialize [schema])
                    (sp/initialize base-storage schema))

                  (close
                    [_]
                    (record-call! :close [])
                    (sp/close base-storage))


                  sp/StorageCRUD

                  (create-entity
                    [_ entity-name data]
                    (record-call! :create-entity [entity-name data])
                    (sp/create-entity base-storage entity-name data))

                  (read-entity
                    [_ entity-name id]
                    (record-call! :read-entity [entity-name id])
                    (sp/read-entity base-storage entity-name id))

                  (update-entity
                    [_ entity-name id data]
                    (record-call! :update-entity [entity-name id data])
                    (sp/update-entity base-storage entity-name id data))

                  (delete-entity
                    [_ entity-name id]
                    (record-call! :delete-entity [entity-name id])
                    (sp/delete-entity base-storage entity-name id))

                  (query-entities
                    [_ entity-name where]
                    (record-call! :query-entities [entity-name where])
                    (sp/query-entities base-storage entity-name where))


                  sp/StorageBatchCRUD

                  (create-entities
                    [_ entity-name data-seq]
                    (record-call! :create-entities [entity-name data-seq])
                    (sp/create-entities base-storage entity-name data-seq))

                  (read-entities
                    [_ entity-name ids]
                    (record-call! :read-entities [entity-name ids])
                    (sp/read-entities base-storage entity-name ids))

                  (delete-entities
                    [_ entity-name ids]
                    (record-call! :delete-entities [entity-name ids])
                    (sp/delete-entities base-storage entity-name ids))


                  sp/StorageIntrospection

                  (current-entities
                    [_]
                    (record-call! :current-entities [])
                    (sp/current-entities base-storage))

                  (current-fields
                    [_ entity-name]
                    (record-call! :current-fields [entity-name])
                    (sp/current-fields base-storage entity-name))

                  (current-enums
                    [_]
                    (record-call! :current-enums [])
                    (sp/current-enums base-storage))

                  (current-enum-values
                    [_ enum-name]
                    (record-call! :current-enum-values [enum-name])
                    (sp/current-enum-values base-storage enum-name))

                  (schema-metadata
                    [_]
                    (record-call! :schema-metadata [])
                    (sp/schema-metadata base-storage)))]
    {:storage wrapped
     :calls calls
     :get-calls get-calls
     :reset-calls! reset-calls!}))

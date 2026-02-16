(ns graphden.storage-protocol.test-helpers
  "Shared test utilities for storage implementations.

   This namespace provides common helpers used across postgres-storage
   and other storage test suites using PostgreSQL testcontainers.

   Key utilities:
   - make-schema / make-graph-schema: Create test schemas
   - create-test-storage: Create pre-initialized PostgreSQL storage
   - with-test-storage: Macro for test storage lifecycle
   - Container management via postgres-test-helpers"
  (:require
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.postgres-storage.interface :as pg]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.postgres-test-helpers :as pth]))


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
  "Creates a schema for graph testing with normalized entities:
   fn-schema, arg-schema, fn, arg-value (pure value), fn-arg (binding).

   Schema (normalized):
   - arg-value: pure value (arg-schema-id, value) - no owner
   - fn-arg: binding (fn-id, arg-schema-id, arg-value-id)

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
                                     :ref-entity :fn-schema}})
      ;; arg-value: pure value (no owner-fn-id)
      (ds/add-entity :arg-value
                     #uuid "00000000-0000-0000-0000-000000000040"
                     {:arg-schema-id {:uuid #uuid "00000000-0000-0000-0000-000000000042"
                                      :type :ref
                                      :ref-entity :arg-schema}
                      :value {:uuid #uuid "00000000-0000-0000-0000-000000000043"
                              :type :jsonb
                              :nullable? true}})
      ;; fn-arg: binding (fn -> arg-value)
      (ds/add-entity :fn-arg
                     #uuid "00000000-0000-0000-0000-000000000050"
                     {:fn-id {:uuid #uuid "00000000-0000-0000-0000-000000000051"
                              :type :ref
                              :ref-entity :fn}
                      :arg-schema-id {:uuid #uuid "00000000-0000-0000-0000-000000000052"
                                      :type :ref
                                      :ref-entity :arg-schema}
                      :arg-value-id {:uuid #uuid "00000000-0000-0000-0000-000000000053"
                                     :type :ref
                                     :ref-entity :arg-value}})
      ds/build))


;; ============================================================================
;; Container Management (re-exports from postgres-test-helpers)
;; ============================================================================

(def create-container-fixture pth/create-container-fixture)
(def create-clean-db-fixture pth/create-clean-db-fixture)
(def get-container-config pth/get-container-config)


;; Note: with-postgres-container is a macro, use pth/with-postgres-container directly


;; ============================================================================
;; Test Storage Utilities
;; ============================================================================

(defn create-test-storage
  "Creates a PostgreSQL storage instance for testing.
   Optionally initializes with a schema.

   Arguments:
   - container: A running PostgreSQLContainer instance
   - schema: Optional DataSchema to initialize storage with.
             If not provided, creates uninitialized storage.

   Returns:
   A PostgresStorage instance ready for testing.

   Example:
     (create-test-storage container)                    ; uninitialized
     (create-test-storage container (make-schema))      ; with simple schema
     (create-test-storage container (make-graph-schema)); with graph schema"
  ([container]
   (pg/create-storage (pth/get-container-config container)))
  ([container schema]
   (let [storage (pg/create-storage (pth/get-container-config container))]
     (sp/initialize storage schema)
     storage)))


(defmacro with-test-storage
  "Executes body with a test storage bound to sym.
   Automatically closes storage when done.
   Requires a container to be available.

   Arguments:
   - binding: Vector of [sym container] or [sym container schema]
   - body: Forms to execute with storage available

   Example:
     (with-test-storage [s *container* (make-schema)]
       (sp/create-entity s :user {:id (random-uuid) :name \"Alice\"})
       (is (= 1 (count (sp/query-entities s :user {})))))"
  [[sym container schema] & body]
  `(let [~sym (if ~schema
                (create-test-storage ~container ~schema)
                (create-test-storage ~container))]
     (try
       (do ~@body)
       (finally
         (sp/close ~sym)))))


(defn create-arg-value-with-binding!
  "Creates an arg-value and fn-arg binding in one operation.
   This is the normalized way to bind an argument value to a function.

   Arguments:
   - storage: initialized storage
   - fn-id: UUID of the owning function
   - arg-schema-id: UUID of the arg-schema
   - value: the argument value

   Returns the created arg-value entity.

   Example:
     (create-arg-value-with-binding! storage fn-id arg-schema-id 42)"
  [storage fn-id arg-schema-id value]
  (let [arg-value (sp/create-entity storage :arg-value
                                    {:arg-schema-id arg-schema-id
                                     :value value})
        _ (sp/create-entity storage :fn-arg
                            {:fn-id fn-id
                             :arg-schema-id arg-schema-id
                             :arg-value-id (:id arg-value)})]
    arg-value))

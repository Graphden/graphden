(ns graphden.storage.protocol.test-helpers
  "Shared test utilities for storage implementations.

   This namespace provides common helpers used across postgres-storage
   and other storage test suites using PostgreSQL testcontainers.

   Key utilities:
   - make-schema / make-graph-schema: Create test schemas
   - create-test-storage: Create pre-initialized PostgreSQL storage
   - with-test-storage: Macro for test storage lifecycle
   - Container management via postgres-test-helpers

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]))


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
  "Creates a schema for graph testing with 2-entity model:
   - fn: with parent-id for composition
   - arg: with source-id for inheritance, value/ref-id for data

   This is useful for testing ExecutionGraph resolution and related functionality."
  []
  (-> (mds/create-builder)
      ;; fn entity: parent-id=nil for base-fn, parent-id set for composed fn
      (ds/add-entity :fn
                     #uuid "00000000-0000-0000-0000-000000000010"
                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000000011"
                             :type :text}
                      :parent-ids {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                   :type :ref-many
                                   :ref-entity :fn
                                   :nullable? true}
                      :return-type {:uuid #uuid "00000000-0000-0000-0000-000000000013"
                                    :type :text
                                    :nullable? true}
                      :impl-hash {:uuid #uuid "00000000-0000-0000-0000-000000000014"
                                  :type :text
                                  :nullable? true}})
      ;; arg entity: fn-id (owner), source-id (inheritance), value/ref-id (data)
      (ds/add-entity :arg
                     #uuid "00000000-0000-0000-0000-000000000020"
                     {:fn-id {:uuid #uuid "00000000-0000-0000-0000-000000000021"
                              :type :ref
                              :ref-entity :fn}
                      :name {:uuid #uuid "00000000-0000-0000-0000-000000000022"
                             :type :text}
                      :type {:uuid #uuid "00000000-0000-0000-0000-000000000023"
                             :type :text
                             :nullable? true}
                      :source-id {:uuid #uuid "00000000-0000-0000-0000-000000000024"
                                  :type :ref
                                  :ref-entity :arg
                                  :nullable? true}
                      :value {:uuid #uuid "00000000-0000-0000-0000-000000000025"
                              :type :jsonb
                              :nullable? true}
                      :ref-id {:uuid #uuid "00000000-0000-0000-0000-000000000026"
                               :type :ref
                               :ref-entity :fn
                               :nullable? true}
                      :is-fn {:uuid #uuid "00000000-0000-0000-0000-000000000027"
                              :type :bool
                              :nullable? true}
                      :required {:uuid #uuid "00000000-0000-0000-0000-000000000028"
                                 :type :bool
                                 :nullable? true}})
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


(defn create-arg-with-value!
  "Creates an arg with a literal value for the 2-entity schema.

   Arguments:
   - storage: initialized storage
   - fn-id: UUID of the owning function
   - name: arg name
   - value: the argument value

   Returns the created arg entity.

   Example:
     (create-arg-with-value! storage fn-id \"x\" 42)"
  [storage fn-id arg-name value]
  (sp/create-entity storage :arg
                    {:fn-id fn-id
                     :name arg-name
                     :value value}))


(defn create-arg-with-ref!
  "Creates an arg with a function reference for the 2-entity schema.

   Arguments:
   - storage: initialized storage
   - fn-id: UUID of the owning function
   - name: arg name
   - ref-id: UUID of the referenced function
   - is-fn: true if passing fn as first-class value (HOF)

   Returns the created arg entity.

   Example:
     (create-arg-with-ref! storage fn-id \"f\" other-fn-id true)"
  [storage fn-id arg-name ref-id is-fn]
  (sp/create-entity storage :arg
                    {:fn-id fn-id
                     :name arg-name
                     :ref-id ref-id
                     :is-fn is-fn}))

(ns graphden.storage.postgres.test-setup
  "Shared test setup and helpers for postgres-storage tests.

   This namespace provides:
   - Testcontainer fixture functions
   - Dynamic var for container binding
   - Helper function for creating test storage
   - Common test schemas (make-graph-schema)

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)

   Usage in test namespaces:
   ```clojure
   (use-fixtures :once (setup/container-fixture))
   (use-fixtures :each (setup/clean-db-fixture))

   ;; In tests:
   (let [storage (setup/create-test-storage)]
     ...)
   ```"
  (:require
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]))


(def ^:dynamic *container*
  "Dynamic var holding the testcontainer for PostgreSQL.
   Bound by container-fixture."
  nil)


(defn container-fixture
  "Creates a fixture that starts PostgreSQL testcontainer once per test namespace.
   Returns a function suitable for use with `use-fixtures :once`."
  []
  (pth/create-container-fixture #'*container*))


(defn clean-db-fixture
  "Creates a fixture that cleans the database before each test.
   Returns a function suitable for use with `use-fixtures :each`."
  []
  (pth/create-clean-db-fixture #'*container*))


(defn create-test-storage
  "Creates a test storage with a clean database.
   Cleans DB to ensure isolation when multiple storages created in one test."
  []
  (pth/clean-database-fast! *container*)
  (pg/create-storage (pth/get-container-config *container*)))


(defn get-container-config
  "Returns the connection config for the current testcontainer."
  []
  (pth/get-container-config *container*))


(defn make-graph-schema
  "Creates schema with fn + arg entities.
   This is the standard 2-entity graph schema used by executor and constraint tests.

   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  []
  (-> (mds/create-builder)
      ;; fn entity
      (ds/add-entity :fn #uuid "00000000-0000-0000-0001-000000000001"
                     {:name {:uuid #uuid "00000000-0000-0000-0001-000000000002"
                             :type :text}
                      :parent-id {:uuid #uuid "00000000-0000-0000-0001-000000000003"
                                  :type :uuid
                                  :nullable? true}
                      :return-type {:uuid #uuid "00000000-0000-0000-0001-000000000004"
                                    :type :text
                                    :nullable? true}})
      (ds/add-constraint :fn {:type :unique :fields [:name]})
      ;; arg entity
      (ds/add-entity :arg #uuid "00000000-0000-0000-0002-000000000001"
                     {:fn-id {:uuid #uuid "00000000-0000-0000-0002-000000000002"
                              :type :uuid}
                      :name {:uuid #uuid "00000000-0000-0000-0002-000000000003"
                             :type :text}
                      :type {:uuid #uuid "00000000-0000-0000-0002-000000000004"
                             :type :text}
                      :required {:uuid #uuid "00000000-0000-0000-0002-000000000005"
                                 :type :bool}
                      :is-fn {:uuid #uuid "00000000-0000-0000-0002-000000000006"
                              :type :bool}
                      :source-id {:uuid #uuid "00000000-0000-0000-0002-000000000007"
                                  :type :uuid
                                  :nullable? true}
                      :value {:uuid #uuid "00000000-0000-0000-0002-000000000008"
                              :type :jsonb
                              :nullable? true}
                      :ref-id {:uuid #uuid "00000000-0000-0000-0002-000000000009"
                               :type :uuid
                               :nullable? true}})
      (ds/add-constraint :arg {:type :unique :fields [:fn-id :name]})
      ds/build))


(defn create-base-fn!
  "Creates a base fn entity. Returns the fn record.
   Base fns have parent-id=nil. The name field is used for registry lookup."
  [storage entity-name return-type]
  (sp/create-entity storage :fn
                    {:name entity-name
                     :parent-id nil
                     :return-type (clojure.core/name return-type)}))


(defn create-composed-fn!
  "Creates a composed fn entity. Returns the fn record.
   Composed fns have parent-id set to their base fn."
  [storage entity-name parent-id]
  (sp/create-entity storage :fn
                    {:name entity-name
                     :parent-id parent-id}))


(defn create-arg!
  "Creates an arg entity. Returns the arg record.

   Required:
   - fn-id: the fn this arg belongs to
   - opts map with :name, :type, :required, :is-fn

   Optional in opts:
   - :source-id - parent arg this inherits from
   - :value - literal value (mutually exclusive with ref-id)
   - :ref-id - reference to another fn (mutually exclusive with value)"
  [storage fn-id {:keys [required is-fn source-id value ref-id]
                  arg-name :name
                  arg-type :type}]
  (sp/create-entity storage :arg
                    {:fn-id fn-id
                     :name arg-name
                     :type (clojure.core/name arg-type)
                     :required required
                     :is-fn is-fn
                     :source-id source-id
                     :value value
                     :ref-id ref-id}))

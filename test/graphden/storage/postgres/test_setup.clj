(ns graphden.storage.postgres.test-setup
  "Shared test setup and helpers for postgres-storage tests.

   This namespace provides:
   - Testcontainer fixture functions
   - Dynamic var for container binding
   - Helper function for creating test storage
   - Common test schemas (make-graph-schema)

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
    [graphden.schema.protocol.interface :as ds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.interface :as sp]
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
  "Creates schema with fn-schema, arg-schema, fn, fn-usage, arg-value, and fn-arg entities.
   This is the standard graph schema used by executor and constraint tests.
   Uses normalized schema where arg-value has no owner, and fn-arg binds fn to arg-value."
  []
  (-> (mds/create-builder)
      (ds/add-entity :fn-schema #uuid "00000000-0000-0000-0001-000000000001"
                     {:name {:uuid #uuid "00000000-0000-0000-0001-000000000002"
                             :type :text}
                      :returned-type {:uuid #uuid "00000000-0000-0000-0001-000000000003"
                                      :type :text}})
      (ds/add-entity :arg-schema #uuid "00000000-0000-0000-0002-000000000001"
                     {:fn-schema-id {:uuid #uuid "00000000-0000-0000-0002-000000000002"
                                     :type :ref :ref-entity :fn-schema}
                      :name {:uuid #uuid "00000000-0000-0000-0002-000000000003"
                             :type :text}
                      :type {:uuid #uuid "00000000-0000-0000-0002-000000000004"
                             :type :text}
                      :required {:uuid #uuid "00000000-0000-0000-0002-000000000005"
                                 :type :bool}
                      :first-class {:uuid #uuid "00000000-0000-0000-0002-000000000006"
                                    :type :bool}})
      (ds/add-entity :fn #uuid "00000000-0000-0000-0003-000000000001"
                     {:name {:uuid #uuid "00000000-0000-0000-0003-000000000002"
                             :type :text}
                      :fn-schema-id {:uuid #uuid "00000000-0000-0000-0003-000000000003"
                                     :type :ref :ref-entity :fn-schema}
                      :owner-fn-id {:uuid #uuid "00000000-0000-0000-0003-000000000004"
                                    :type :ref :ref-entity :fn
                                    :nullable? true}})
      (ds/add-entity :fn-usage #uuid "00000000-0000-0000-0005-000000000001"
                     {:fn-id {:uuid #uuid "00000000-0000-0000-0005-000000000002"
                              :type :ref :ref-entity :fn}
                      :name {:uuid #uuid "00000000-0000-0000-0005-000000000003"
                             :type :text}
                      :owner-fn-id {:uuid #uuid "00000000-0000-0000-0005-000000000004"
                                    :type :ref :ref-entity :fn
                                    :nullable? true}})
      ;; arg-value: pure value (no owner-fn-id)
      (ds/add-entity :arg-value #uuid "00000000-0000-0000-0004-000000000001"
                     {:arg-schema-id {:uuid #uuid "00000000-0000-0000-0004-000000000003"
                                      :type :ref :ref-entity :arg-schema}
                      :value {:uuid #uuid "00000000-0000-0000-0004-000000000004"
                              :type :jsonb}})
      ;; fn-arg: binding from fn to arg-value
      (ds/add-entity :fn-arg #uuid "00000000-0000-0000-0006-000000000001"
                     {:fn-id {:uuid #uuid "00000000-0000-0000-0006-000000000002"
                              :type :ref :ref-entity :fn}
                      :arg-schema-id {:uuid #uuid "00000000-0000-0000-0006-000000000003"
                                      :type :ref :ref-entity :arg-schema}
                      :arg-value-id {:uuid #uuid "00000000-0000-0000-0006-000000000004"
                                     :type :ref :ref-entity :arg-value}})
      ds/build))


(defn create-arg-value-with-binding!
  "Creates arg-value and fn-arg binding. Returns the arg-value.
   Helper for tests that need to create argument values."
  [storage fn-id arg-schema-id value]
  (let [av (sp/create-entity storage :arg-value
                             {:arg-schema-id arg-schema-id
                              :value value})]
    (sp/create-entity storage :fn-arg
                      {:fn-id fn-id
                       :arg-schema-id arg-schema-id
                       :arg-value-id (:id av)})
    av))

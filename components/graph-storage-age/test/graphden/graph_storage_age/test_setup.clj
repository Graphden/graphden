(ns graphden.graph-storage-age.test-setup
  "Test setup for graph-storage-age using Apache AGE testcontainer.

   Apache AGE requires the AGE extension to be loaded in PostgreSQL.
   We use the official `apache/age:PG16-latest` Docker image.

   Usage:
   ```clojure
   (use-fixtures :once (setup/container-fixture))
   (use-fixtures :each (setup/clean-db-fixture))

   (deftest my-test
     (let [storage (setup/create-test-storage)]
       (try
         ;; ... test body
         (finally
           (sp/close storage)))))
   ```"
  (:require
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.graph-storage-age.interface :as age]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.storage-protocol.interface :as sp]
    [next.jdbc :as jdbc])
  (:import
    (org.testcontainers.containers
      PostgreSQLContainer)
    (org.testcontainers.utility
      DockerImageName)))


;; Apache AGE Docker image - must be declared as compatible with postgres
(def age-image-name
  "DockerImageName for Apache AGE, declared compatible with postgres."
  (-> (DockerImageName/parse "apache/age:latest")
      (.asCompatibleSubstituteFor "postgres")))


(def ^:dynamic *container*
  "Dynamic var holding the AGE testcontainer.
   Bound by container-fixture."
  nil)


(defn get-container-config
  "Returns connection configuration map from a running container."
  [container]
  {:jdbc-url (PostgreSQLContainer/.getJdbcUrl container)
   :username (PostgreSQLContainer/.getUsername container)
   :password (PostgreSQLContainer/.getPassword container)
   :pool-size 2})


(defn clean-database-fast!
  "Cleans AGE database: drops AGE graph and recreates public schema."
  [container]
  (let [{:keys [jdbc-url username password]} (get-container-config container)]
    (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                           :user username
                                           :password password})]
      ;; Load AGE extension first
      (try
        (jdbc/execute! conn ["LOAD 'age'"])
        (jdbc/execute! conn ["SET search_path = ag_catalog, public, \"$user\""])
        ;; Try to drop existing graph
        (jdbc/execute! conn ["SELECT drop_graph('graphden', true)"])
        (catch Exception _
          ;; Graph might not exist
          nil))
      ;; Clean public schema
      (jdbc/execute! conn ["DROP SCHEMA IF EXISTS public CASCADE"])
      (jdbc/execute! conn ["CREATE SCHEMA public"])
      (jdbc/execute! conn ["GRANT ALL ON SCHEMA public TO PUBLIC"]))))


(defn container-fixture
  "Creates a fixture that starts AGE testcontainer once per test namespace."
  []
  (fn [f]
    (let [container (doto (PostgreSQLContainer. age-image-name)
                      (PostgreSQLContainer/.withStartupAttempts 3))]
      (PostgreSQLContainer/.start container)
      (when-not (PostgreSQLContainer/.isRunning container)
        (throw (ex-info "Failed to start AGE test container"
                        {:image (str age-image-name)})))
      (try
        (binding [*container* container]
          (f))
        (finally
          (PostgreSQLContainer/.stop container))))))


(defn clean-db-fixture
  "Creates a fixture that cleans the database before each test."
  []
  (fn [f]
    (clean-database-fast! *container*)
    (f)))


(defn create-test-storage
  "Creates a test AGE storage with a clean database.
   Returns initialized storage ready for use."
  []
  (clean-database-fast! *container*)
  (age/create-storage (get-container-config *container*)))


(defn make-graph-schema
  "Creates schema with fn-schema, arg-schema, fn, call-site, arg-value, and fn-arg entities.
   This is the standard graph schema used by executor and constraint tests."
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
                                 :type :bool}})
      (ds/add-entity :fn #uuid "00000000-0000-0000-0003-000000000001"
                     {:name {:uuid #uuid "00000000-0000-0000-0003-000000000002"
                             :type :text}
                      :fn-schema-id {:uuid #uuid "00000000-0000-0000-0003-000000000003"
                                     :type :ref :ref-entity :fn-schema}})
      (ds/add-entity :call-site #uuid "00000000-0000-0000-0005-000000000001"
                     {:fn-id {:uuid #uuid "00000000-0000-0000-0005-000000000002"
                              :type :ref :ref-entity :fn}})
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
  "Creates arg-value and fn-arg binding. Returns the arg-value."
  [storage fn-id arg-schema-id value]
  (let [av (sp/create-entity storage :arg-value
                             {:arg-schema-id arg-schema-id
                              :value value})]
    (sp/create-entity storage :fn-arg
                      {:fn-id fn-id
                       :arg-schema-id arg-schema-id
                       :arg-value-id (:id av)})
    av))

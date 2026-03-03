(ns graphden.storage.age.test-setup
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
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.storage.age.core :as age]
    [graphden.storage.protocol.core :as sp]
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
      (DockerImageName/.asCompatibleSubstituteFor "postgres")))


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
                      (PostgreSQLContainer/.withStartupAttempts 3)
                      ;; Increase max_connections for parallel test execution
                      (PostgreSQLContainer/.withCommand "postgres -c max_connections=500"))]
      (PostgreSQLContainer/.start container)
      (when-not (PostgreSQLContainer/.isRunning container)
        (throw (ex-info "Failed to start AGE test container"
                        {:image (str age-image-name)})))
      (try
        (binding [*container* container]
          (f))
        (finally
          (PostgreSQLContainer/.stop container))))))


(defn create-container-fixture
  "Creates a fixture that binds a container to a dynamic var.
   Compatible with postgres-test-helpers API."
  [container-var]
  (fn [f]
    (let [container (doto (PostgreSQLContainer. age-image-name)
                      (PostgreSQLContainer/.withStartupAttempts 3)
                      ;; Increase max_connections for parallel test execution
                      (PostgreSQLContainer/.withCommand "postgres -c max_connections=500"))]
      (PostgreSQLContainer/.start container)
      (when-not (PostgreSQLContainer/.isRunning container)
        (throw (ex-info "Failed to start AGE test container"
                        {:image (str age-image-name)})))
      (try
        (alter-var-root container-var (constantly container))
        (f)
        (finally
          (PostgreSQLContainer/.stop container)
          (alter-var-root container-var (constantly nil)))))))


(defn clean-db-fixture
  "Creates a fixture that cleans the database before each test."
  []
  (fn [f]
    (clean-database-fast! *container*)
    (f)))


(defn create-clean-db-fixture
  "Creates a fixture that cleans the database before each test.
   Compatible with postgres-test-helpers API."
  [container-var]
  (fn [f]
    (clean-database-fast! @container-var)
    (f)))


(defn make-graph-schema
  "Creates standard graph schema with fn-schema, arg-schema, fn, fn-usage, arg-value, and fn-arg entities.
   Uses the official graph-data-schema for full compatibility with fn-registry and executor."
  []
  (gds/build-schema (mds/create-builder)))


(defn create-raw-storage
  "Creates a test AGE storage with a clean database but WITHOUT schema initialization.
   Use this for tests that want to initialize their own schema.
   Returns uninitialized storage."
  ([]
   (create-raw-storage *container*))
  ([container]
   (clean-database-fast! container)
   (age/create-storage (get-container-config container))))


(defn create-test-storage
  "Creates a test AGE storage with a clean database and initialized schema.
   Returns initialized storage ready for use."
  ([]
   (create-test-storage *container*))
  ([container]
   (clean-database-fast! container)
   (-> (age/create-storage (get-container-config container))
       (sp/initialize-with-cleanup! (make-graph-schema)))))


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

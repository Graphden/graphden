(ns graphden.graph-with-base-fns-postgres.interface-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.graph-with-base-fns-postgres.interface :as gwbf]
    [graphden.storage-protocol.interface :as sp]
    [next.jdbc :as jdbc])
  (:import
    (org.testcontainers.containers
      PostgreSQLContainer)))


;; === Testcontainers setup ===

(def ^:dynamic *container* nil)


(defn- clean-database!
  "Drops all user-created objects in public schema."
  [container]
  (let [jdbc-url (PostgreSQLContainer/.getJdbcUrl container)
        username (PostgreSQLContainer/.getUsername container)
        password (PostgreSQLContainer/.getPassword container)]
    (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                           :user username
                                           :password password})]
      (let [tables (jdbc/execute! conn ["SELECT tablename FROM pg_tables WHERE schemaname = 'public'"])]
        (doseq [{:pg_tables/keys [tablename]} tables]
          (jdbc/execute! conn [(str "DROP TABLE IF EXISTS \"" tablename "\" CASCADE")])))
      (let [enums (jdbc/execute! conn
                                 ["SELECT t.typname FROM pg_type t
                                    JOIN pg_namespace n ON n.oid = t.typnamespace
                                    WHERE n.nspname = 'public' AND t.typtype = 'e'"])]
        (doseq [{:pg_type/keys [typname]} enums]
          (jdbc/execute! conn [(str "DROP TYPE IF EXISTS \"" typname "\" CASCADE")]))))))


(defn with-postgres-container
  [f]
  (let [container (PostgreSQLContainer. "postgres:16-alpine")]
    (PostgreSQLContainer/.start container)
    (try
      (binding [*container* container]
        (f))
      (finally
        (PostgreSQLContainer/.stop container)))))


(defn with-clean-state
  [f]
  (clean-database! *container*)
  (exec/clear-base-fns!)
  (try
    (f)
    (finally
      (exec/clear-base-fns!))))


(use-fixtures :once with-postgres-container)
(use-fixtures :each with-clean-state)


(defn- create-test-storage
  "Creates a test storage with a clean database."
  []
  (clean-database! *container*)
  (gwbf/create-storage {:jdbc-url (PostgreSQLContainer/.getJdbcUrl *container*)
                        :username (PostgreSQLContainer/.getUsername *container*)
                        :password (PostgreSQLContainer/.getPassword *container*)
                        :pool-size 2}))


(deftest create-storage-test
  (testing "create-storage creates storage with base functions"
    (let [storage (create-test-storage)]
      (try
        ;; Check storage is initialized
        (is (some? storage))

        ;; Check graph entities are available
        (is (contains? (sp/current-entities storage) :fn-schema))
        (is (contains? (sp/current-entities storage) :arg-schema))
        (is (contains? (sp/current-entities storage) :fn))
        (is (contains? (sp/current-entities storage) :arg-value))

        ;; Check base functions are registered in executor
        (is (some? (exec/get-base-fn :add)))
        (is (some? (exec/get-base-fn :map)))
        (is (some? (exec/get-base-fn :if)))

        ;; Check fn-schemas are synced to storage
        (let [fn-schemas (sp/query-entities storage :fn-schema {})]
          (is (pos? (count fn-schemas)))
          (is (some #(= "add" (:name %)) fn-schemas))
          (is (some #(= "map" (:name %)) fn-schemas)))

        ;; Check arg-schemas are synced to storage
        (let [arg-schemas (sp/query-entities storage :arg-schema {})]
          (is (pos? (count arg-schemas))))

        (finally
          (sp/close storage))))))

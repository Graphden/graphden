(ns ^:integration graphden.integration.external-sql-pool-test
  "Real PostgreSQL behavior of the managed external SQL path."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.clients.sql-pool :as pool]
    [graphden.test-infra.impls :as impls]
    [graphden.test-infra.shared-container :as sc]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (com.zaxxer.hikari
      HikariDataSource)))


(def ^:dynamic *container* nil)


(use-fixtures :once (sc/shared-container-fixture #'*container*)
  (impls/impls-fixture "web" "sql"))


(defn- sql-fn
  [name]
  @(ns-resolve 'graphden.packages.web.sql.impls name))


(deftest external-sql-reuses-a-connection-and-preserves-query-results
  (let [{:keys [jdbc-url username password]} (sc/get-config)
        manager (pool/create-manager {:pool-size 1 :sweep-ms 60000})
        ctx {:storage {:branch-id "main"} :sql-pool-manager manager}
        args {:url jdbc-url :user username :password password :params []}
        exec (sql-fn 'sql-exec)
        query (sql-fn 'sql-query)]
    (try
      (is (zero? (exec (assoc args :sql "CREATE TABLE sql_pool_probe (n integer)") ctx)))
      (is (= 1 (exec (assoc args :sql "INSERT INTO sql_pool_probe VALUES (7)") ctx)))
      (is (= [{:n 7}] (query (assoc args :sql "SELECT n FROM sql_pool_probe") ctx)))
      (is (zero? (exec (assoc args :sql "CREATE TEMP TABLE sql_pool_temp (n integer)") ctx)))
      (is (= [{:present nil}]
             (query (assoc args :sql
                           "SELECT to_regclass('pg_temp.sql_pool_temp') AS present") ctx))
          "temporary tables must not survive the next borrow")
      (is (zero? (exec (assoc args :sql "BEGIN") ctx)))
      (is (= [{:count 1}]
             (query (assoc args :sql "SELECT count(*) FROM sql_pool_probe") ctx))
          "a user BEGIN is rolled back before returning the connection")
      (let [^HikariDataSource ds (-> @(:state manager) :entries vals first :pool)]
        (is (= 1 (-> ds .getHikariPoolMXBean .getTotalConnections))))
      (finally (pool/close! manager)))))


(deftest anonymous-sql-stays-one-shot
  (let [{:keys [jdbc-url username password]} (sc/get-config)
        manager (pool/create-manager {:sweep-ms 60000})
        ctx {:storage {:branch-id "main"} :sql-pool-manager manager
             :sql-pool-scope (constantly nil)}
        query (sql-fn 'sql-query)]
    (try
      (is (= [{:n 1}]
             (query {:url jdbc-url :user username :password password
                     :sql "SELECT 1 AS n" :params []} ctx)))
      (is (empty? (:entries @(:state manager))))
      (finally (pool/close! manager)))))


(deftest public-sql-result-contains-only-declared-columns
  (let [{:keys [jdbc-url username password]} (sc/get-config)
        manager (pool/create-manager {:sweep-ms 60000})
        ctx {:storage {:branch-id "main"} :sql-pool-manager manager}
        args {:url jdbc-url :user username :password password :params []
              :sql "SELECT 200 AS status, 'hidden-value' AS private_token"}
        query (sql-fn 'sql-query-public)]
    (try
      (is (= [{:status 200}]
             (query (assoc args :public-columns ["status"]) ctx)))
      (is (re-find #"created_at"
                   (json/generate-string
                     (query (assoc args
                                   :sql "SELECT CURRENT_TIMESTAMP AS created_at"
                                   :public-columns ["created_at"]) ctx))))
      (is (= :sql/public-column-missing
             (:type (ex-data
                      (try (query (assoc args :public-columns ["missing"]) ctx)
                           nil
                           (catch clojure.lang.ExceptionInfo e e))))))
      (finally (pool/close! manager)))))


(deftest external-sql-invalid-credentials-do-not-reuse-an-old-pool
  (let [{:keys [jdbc-url username password]} (sc/get-config)
        manager (pool/create-manager {:pool-size 1 :borrow-timeout-ms 500
                                      :sweep-ms 60000})
        ctx {:storage {:branch-id "main"} :sql-pool-manager manager}
        args {:url jdbc-url :user username :params [] :sql "SELECT 1 AS n"}
        query (sql-fn 'sql-query)]
    (try
      (is (= [{:n 1}] (query (assoc args :password password) ctx)))
      (is (thrown? Exception (query (assoc args :password "wrong-password") ctx)))
      (is (empty? (:entries @(:state manager)))
          "a failed credential generation must release its global pool slot")
      (is (= [{:n 1}] (query (assoc args :password password) ctx)))
      (finally (pool/close! manager)))))


(deftest a-borrow-waits-only-for-its-declared-timeout
  (let [{:keys [jdbc-url username password]} (sc/get-config)
        manager (pool/create-manager {:pool-size 1 :borrow-timeout-ms 500
                                      :sweep-ms 60000})
        scope {:org :platform :principal :system :branch "main"}
        args {:url jdbc-url :user username :password password
              :params [] :sql "SELECT 1 AS n"}
        ctx {:storage {:branch-id "main"} :sql-pool-manager manager}
        query (sql-fn 'sql-query)
        ds (pool/with-datasource manager scope jdbc-url username password identity)]
    (try
      (with-open [_conn (jdbc/get-connection ds)]
        (let [failure (try (query args ctx) nil (catch Exception e e))]
          (is (some? failure))
          (is (= 500 (HikariDataSource/.getConnectionTimeout ds)))))
      (is (= [{:n 1}] (query args ctx)) "the connection is reusable after release")
      (finally (pool/close! manager)))))


(deftest concurrent-calls-use-separate-bounded-postgres-connections
  (let [{:keys [jdbc-url username password]} (sc/get-config)
        manager (pool/create-manager {:pool-size 2 :sweep-ms 60000})
        scope {:org :platform :principal :system :branch "main"}
        first-id (promise)
        release-first (promise)
        first-call (future
                     (pool/with-connection manager scope jdbc-url username password
                                           (fn [conn]
                                             (deliver first-id (:backend_pid
                                                                 (jdbc/execute-one! conn ["SELECT pg_backend_pid() AS backend_pid"]
                                                                                    {:builder-fn rs/as-unqualified-maps})))
                                             (deref release-first 30000 nil))))]
    (try
      (let [held-id (deref first-id 10000 nil)
            second-id (pool/with-connection manager scope jdbc-url username password
                                            (fn [conn]
                                              (:backend_pid
                                                (jdbc/execute-one! conn ["SELECT pg_backend_pid() AS backend_pid"]
                                                                   {:builder-fn rs/as-unqualified-maps}))))]
        (is (some? held-id) "the first call acquired a live database connection")
        (is (not= held-id second-id) "overlapping calls must use distinct connections")
        (is (<= (-> @(:state manager) :entries vals first :pool
                    .getHikariPoolMXBean .getTotalConnections) 2)))
      (finally
        (deliver release-first true)
        (deref first-call 30000 nil)
        (pool/close! manager)))))


(deftest cancelled-statement-releases-its-lease
  (let [{:keys [jdbc-url username password]} (sc/get-config)
        manager (pool/create-manager {:pool-size 1 :sweep-ms 60000})
        scope {:org :platform :principal :system :branch "main"}]
    (try
      (is (thrown? java.sql.SQLException
            (pool/with-connection manager scope jdbc-url username password
                                  #(jdbc/execute! % ["SELECT pg_sleep(5)"] {:timeout 1})))
          "the JDBC statement timeout cancels a long running query")
      (is (= 1 (:n (pool/with-connection manager scope jdbc-url username password
                                         #(jdbc/execute-one! % ["SELECT 1 AS n"]
                                                             {:builder-fn rs/as-unqualified-maps}))))
          "a cancelled statement cannot strand the only pooled connection")
      (finally (pool/close! manager)))))

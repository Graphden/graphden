(ns ^:integration ^:serial graphden.storage.postgres.source-session-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.storage.postgres.pool :as pool]
    [graphden.storage.postgres.source-session :as session]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.postgres.util :as pg]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (java.lang.reflect
      InvocationHandler
      Proxy)
    (java.sql
      Connection
      SQLException)
    (org.postgresql
      PGConnection)))


(def ^:private maps {:builder-fn rs/as-unqualified-lower-maps})
(def ^:dynamic *config* nil)


(defn- connect
  [role]
  (jdbc/get-connection {:jdbcUrl (:jdbc-url *config*) :user role :password "fixture-password"}))


(defn- roles
  [f]
  (let [config (setup/get-container-config)]
    (binding [*config* config]
      (with-open [admin (jdbc/get-connection {:jdbcUrl (:jdbc-url config)
                                              :user (:username config) :password (:password config)})]
        (try
          (jdbc/execute! admin ["CREATE ROLE source_session_same LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'fixture-password'"])
          (jdbc/execute! admin ["CREATE ROLE source_session_other LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'fixture-password'"])
          (jdbc/execute! admin ["CREATE TABLE source_session_row(id integer)"])
          (jdbc/execute! admin ["GRANT SELECT ON source_session_row TO source_session_same,source_session_other"])
          (f)
          (finally
            (jdbc/execute! admin ["DROP TABLE IF EXISTS source_session_row"])
            (jdbc/execute! admin ["DROP ROLE IF EXISTS source_session_same"])
            (jdbc/execute! admin ["DROP ROLE IF EXISTS source_session_other"])))))))


(use-fixtures :once (setup/container-fixture) roles)


(defn- manager
  []
  (session/create-manager {:mode :direct :max-sessions 8}))


(defn- snapshot
  [connection f]
  (jdbc/with-transaction [tx connection {:isolation :repeatable-read :read-only true}]
                         (jdbc/execute! tx ["SET LOCAL statement_timeout = '1000ms'"])
                         (jdbc/execute! tx ["LOCK TABLE public.source_session_row IN ACCESS SHARE MODE"])
                         (f tx)))


(defn- capture
  [marker connection]
  (snapshot connection #(session/seal-origin marker % (constantly nil))))


(defn- live?
  [origin connection]
  (snapshot connection #(session/origin-live? origin % (constantly nil))))


(defn- lock-count
  [connection]
  (:n (jdbc/execute-one! connection
                         ["SELECT count(*) AS n FROM pg_locks WHERE pid=pg_backend_pid() AND locktype='advisory' AND granted"] maps)))


(deftest acquire-once-without-open-transaction-and-replay-cross-connection
  (with-open [origin (connect "source_session_same") destination (connect "source_session_same")]
    (let [owner (manager)
          queries (atom [])
          marker (binding [pg/*jdbc-override*
                           {:execute! (fn [connection query opts]
                                        (swap! queries conj (first query))
                                        (jdbc/execute! connection query opts))}]
                   (let [m (session/ensure-marker! owner origin (constantly nil))]
                     (dotimes [_ 10] (is (identical? m (session/ensure-marker! owner origin (constantly nil)))))
                     m))
          token (capture marker origin)]
      (is (false? (live? token destination)) "An origin does not approve an unregistered destination pool")
      (is (some? (session/ensure-marker! owner destination (constantly nil))))
      (is (= 1 (count @queries)) "No repeated or reentrant lock acquisition")
      (is (= 2 (lock-count origin)))
      (is (= {:registered 2 :active 2 :closed? false} (session/stats owner)))
      (is (some? token))
      (is (= "SourceSessionOrigin" (str token)))
      (is (true? (live? token destination)))
      (let [row (jdbc/execute-one! destination
                                   ["SELECT a.state,a.xact_start FROM pg_stat_activity a WHERE a.pid<>pg_backend_pid() AND a.usename='source_session_same'"] maps)]
        (is (= "idle" (:state row)))
        (is (nil? (:xact_start row))))
      (session/close-manager! owner)
      (is (false? (live? token destination)))
      (is (nil? (session/ensure-marker! (manager) origin (constantly nil))) "Another manager cannot stack marker pairs")
      (is (= 2 (lock-count origin))))))


(deftest guard-boundary-and-opaque-evidence
  (with-open [origin (connect "source_session_same") destination (connect "source_session_same")]
    (let [owner (manager) marker (session/ensure-marker! owner origin (constantly nil))]
      (is (nil? (session/seal-origin marker origin (constantly nil))) "No seal outside RR")
      (jdbc/with-transaction [tx origin {:isolation :repeatable-read :read-only true}]
                             (is (nil? (session/ensure-marker! owner tx (constantly nil))) "Acquisition never enters RR")
                             (is (nil? (session/seal-origin marker tx (constantly nil))) "No query before first LOCK")
                             (jdbc/execute! tx ["LOCK TABLE public.source_session_row IN ACCESS SHARE MODE"])
                             (is (some? (session/seal-origin marker tx (constantly nil)))))
      (is (nil? (capture marker destination)) "Wrong physical capture origin is refused")
      (is (false? (live? {:pid 1 :keys [1 2]} destination)))
      (let [token (capture marker origin)]
        (is (false? (session/origin-live? token destination (constantly nil)))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expired"
            (snapshot origin #(session/seal-origin marker % (fn [] (throw (ex-info "expired" {}))))))))))


(deftest lost-marker-and-closed-session-never-resurrect
  (with-open [origin (connect "source_session_same") destination (connect "source_session_same")]
    (let [owner (manager) marker (session/ensure-marker! owner origin (constantly nil))
          token (capture marker origin)]
      (session/ensure-marker! owner destination (constantly nil))
      (jdbc/execute! origin ["SELECT pg_advisory_unlock_all()"])
      (is (false? (live? token destination)))
      (is (nil? (capture marker origin)))
      (is (nil? (session/ensure-marker! owner origin (constantly nil))))
      (is (= 0 (lock-count origin)))
      (.close origin)
      (is (false? (live? token destination)))
      (with-open [replacement (connect "source_session_same")]
        (let [new-marker (session/ensure-marker! owner replacement (constantly nil))
              new-token (capture new-marker replacement)]
          (is (not (identical? marker new-marker)))
          (is (true? (live? new-token destination)))
          (is (false? (live? token destination))))))))


(deftest hidden-origin-statistics-conservatively-miss
  (with-open [origin (connect "source_session_same") other (connect "source_session_other")]
    (let [owner (manager)
          marker (session/ensure-marker! owner origin (constantly nil))
          token (capture marker origin)]
      (session/ensure-marker! owner other (constantly nil))
      (is (false? (live? token other)))
      (is (true? (live? token origin)) "Other-role miss does not retire a valid origin"))))


(deftest dead-backend-does-not-reuse-cached-activity-statistics
  (with-open [origin (connect "source_session_same") destination (connect "source_session_same")
              admin (jdbc/get-connection {:jdbcUrl (:jdbc-url *config*)
                                          :user (:username *config*) :password (:password *config*)})]
    (let [owner (manager)
          marker (session/ensure-marker! owner origin (constantly nil))
          token (capture marker origin)
          pid (.getBackendPID (.unwrap ^Connection origin PGConnection))]
      (session/ensure-marker! owner destination (constantly nil))
      (snapshot destination
                (fn [tx]
                  (is (true? (session/origin-live? token tx (constantly nil))))
                  (is (true? (:terminated (jdbc/execute-one! admin
                                                             ["SELECT pg_terminate_backend(?, 1000) AS terminated" pid] maps))))
                  (is (false? (session/origin-live? token tx (constantly nil)))
                      "Activity cache from the earlier success is cleared before each check"))))))


(deftest partial-acquisition-is-released-and-not-retried
  (with-open [blocker (connect "source_session_same") origin (connect "source_session_same")]
    (let [lock-keys [907123456789 907123456790] owner (manager)]
      (jdbc/execute! blocker ["SELECT pg_advisory_lock(?)" (first lock-keys)])
      (with-redefs [session/random-keys (constantly lock-keys)]
        (is (nil? (session/ensure-marker! owner origin (constantly nil)))))
      (is (= 0 (lock-count origin)))
      (is (= 1 (lock-count blocker)) "Cleanup never releases another backend's collision")
      (is (nil? (session/ensure-marker! owner origin (constantly nil))))
      (is (= {:registered 1 :active 0 :closed? false} (session/stats owner))))))


(deftest acquisition-errors-unwind-session-locks
  (with-open [origin (connect "source_session_same")]
    (let [owner (manager) error (SQLException. "fixture cancellation" "57014")]
      (is (identical?
            error
            (binding [pg/*jdbc-override*
                      {:execute! (fn [connection query opts]
                                   (let [result (jdbc/execute! connection query opts)]
                                     (when (str/includes? (first query) "pg_try_advisory_lock") (throw error))
                                     result))}]
              (try (session/ensure-marker! owner origin (constantly nil)) nil
                   (catch SQLException e e)))))
      (is (= 0 (lock-count origin)))
      (is (nil? (session/ensure-marker! owner origin (constantly nil)))))))


(deftest explicit-mode-bounds-and-unknown-wrappers
  (is (nil? (session/create-manager {:mode :transaction :max-sessions 8})))
  (is (nil? (session/create-manager {:max-sessions 8})))
  (is (thrown? clojure.lang.ExceptionInfo (session/create-manager {:mode :direct :max-sessions 0})))
  (let [unknown (Proxy/newProxyInstance
                  (.getClassLoader Connection) (into-array Class [Connection])
                  (reify InvocationHandler
                    (invoke [_ _ _ _] (throw (AssertionError. "No duck typing")))))]
    (is (nil? (session/ensure-marker! (manager) unknown (constantly nil)))))
  (with-open [a (connect "source_session_same") b (connect "source_session_same")]
    (let [owner (session/create-manager {:mode :session :max-sessions 1})]
      (is (some? (session/ensure-marker! owner a (constantly nil))))
      (is (nil? (session/ensure-marker! owner b (constantly nil))))
      (is (= 0 (lock-count b)))
      (.close a)
      (is (= 0 (:registered (session/stats owner))))
      (is (some? (session/ensure-marker! owner b (constantly nil)))))))


(deftest cleanup-failure-closes-owned-physical-and-keeps-primary-error
  (with-open [origin (connect "source_session_same")]
    (let [owner (manager)
          primary (SQLException. "fixture acquired then cancelled" "57014")
          cleanup (SQLException. "fixture unlock failed" "08006")
          result (binding [pg/*jdbc-override*
                           {:execute! (fn [connection query opts]
                                        (if (str/includes? (first query) "pg_advisory_unlock")
                                          (throw cleanup)
                                          (do (jdbc/execute! connection query opts) (throw primary))))}]
                   (try (session/ensure-marker! owner origin (constantly nil)) nil
                        (catch SQLException error error)))]
      (is (identical? primary result))
      (is (= [cleanup] (vec (.getSuppressed ^Throwable result))))
      (is (.isClosed origin))
      (is (= 0 (:registered (session/stats owner)))))))


(deftest close-during-acquisition-unwinds-pending-markers
  (with-open [origin (connect "source_session_same")]
    (let [owner (manager)]
      (is (nil?
            (binding [pg/*jdbc-override*
                      {:execute! (fn [connection query opts]
                                   (let [result (jdbc/execute! connection query opts)]
                                     (when (str/includes? (first query) "pg_try_advisory_lock")
                                       (session/close-manager! owner))
                                     result))}]
              (session/ensure-marker! owner origin (constantly nil)))))
      (is (= 0 (lock-count origin)))
      (is (= {:registered 1 :active 0 :closed? true} (session/stats owner))))))


(deftest hikari-borrows-retain-exact-physical-registration
  (let [ds (pool/create-pool (assoc *config* :username "source_session_same" :password "fixture-password"
                                    :pool-size 2 :min-idle 1))
        owner (manager)]
    (try
      (with-open [a (jdbc/get-connection ds) b (jdbc/get-connection ds)]
        (let [marker (session/ensure-marker! owner a (constantly nil)) token (capture marker a)]
          (session/ensure-marker! owner b (constantly nil))
          (is (= 2 (lock-count a)))
          (is (= 2 (lock-count b)))
          (is (true? (live? token b)))
          (is (identical? marker (session/ensure-marker! owner a (constantly nil))))))
      (finally (session/close-manager! owner) (pool/close-pool ds)))))

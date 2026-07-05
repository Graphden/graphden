(ns graphden.storage.postgres.notify-test
  "Tests for the LISTEN/NOTIFY transport.

   `payload-roundtrip-test` is a pure codec round-trip (unit-suite).
   The 3 other deftests are tagged per-deftest `^:integration` —
   they run against the shared PG test container using TWO listeners
   sharing one channel to simulate the multi-pod case: one writer
   pod NOTIFYs, sibling pod's listener thread observes."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.postgres.notify :as pg-notify]
    [next.jdbc :as jdbc])
  (:import
    (com.zaxxer.hikari
      HikariDataSource)
    (java.sql
      Connection)))


(use-fixtures :once (setup/create-container-fixture))


(defn- pg-opts-from-fixture
  "Extract the JDBC connection details the shared test container is
   running against. The fixture exposes `setup/pg-opts` for the
   pool — we reuse the same coords for our dedicated listener
   connections."
  []
  ;; `getJdbcUrl` / `getUsername` / `getPassword` are declared on
  ;; `HikariConfig` (parent class of `HikariDataSource`), NOT on
  ;; the `HikariConfigMXBean` interface — call them directly on
  ;; the pool instead of round-tripping through the MXBean.
  (let [pool (-> (setup/create-test-storage) :pool)
        u (HikariDataSource/.getJdbcUrl pool)
        un (HikariDataSource/.getUsername pool)
        pw (HikariDataSource/.getPassword pool)]
    (HikariDataSource/.close pool)
    {:jdbc-url u :username un :password pw}))


;; ============================================================================
;; payload codec
;; ============================================================================

(deftest payload-roundtrip-test
  (testing "format then parse → identity"
    (let [event {:kind :service :op :write :id "abc-123"}]
      (is (= event (pg-notify/parse-payload (pg-notify/format-payload event))))))

  (testing "parse on a malformed payload → nil"
    (is (nil? (pg-notify/parse-payload "")))
    (is (nil? (pg-notify/parse-payload "no-colons")))
    (is (nil? (pg-notify/parse-payload "only:one-colon")))
    (is (nil? (pg-notify/parse-payload nil)))
    (is (nil? (pg-notify/parse-payload 42)))))


;; ============================================================================
;; end-to-end: emitter → channel → listener callback
;; ============================================================================

(deftest ^:integration emit-then-listen-roundtrip-test
  (testing "writer pod NOTIFYs, sibling pod's listener observes the event"
    (let [pg-opts (pg-opts-from-fixture)
          ;; Sibling pod's listener — receives events the writer
          ;; emits.
          ;; Shorter poll-timeout cuts the post-emit Thread/sleep wait —
          ;; default 1000 ms forces tests to sleep 1500 ms per emit, 250 ms
          ;; lets us sleep 400 ms instead.
          listener (pg-notify/create-listener pg-opts {:poll-timeout-ms 250})
          received (atom [])
          callback (pg-notify/register! listener
                                        (fn [event] (swap! received conj event)))]
      (try
        (testing "before any emit the listener is quiet"
          (Thread/sleep 200)
          (is (= [] @received)))

        (testing "after a NOTIFY the listener's callback fires within ~1s"
          ;; Writer pod's side: just issue a `SELECT pg_notify(...)`
          ;; via any connection — here we open one directly. In prod
          ;; the emitter would use the main pool.
          (let [writer-conn (java.sql.DriverManager/getConnection
                              ^String (:jdbc-url pg-opts)
                              (:username pg-opts)
                              (:password pg-opts))]
            (try
              (jdbc/execute! writer-conn
                             ["SELECT pg_notify('graphden_events', ?)"
                              "service:write:event-1"])
              (finally (Connection/.close writer-conn))))
          ;; Allow the listener's 250 ms poll window to fire +
          ;; dispatch headroom.
          (Thread/sleep 400)
          (is (= [{:kind :service :op :write :id "event-1"}] @received)))

        (finally
          (pg-notify/unregister! listener callback)
          (pg-notify/close-listener! listener))))))


(deftest ^:integration multiple-callbacks-fan-out-test
  (testing "every registered callback receives every NOTIFY"
    (let [pg-opts (pg-opts-from-fixture)
          ;; Shorter poll-timeout cuts the post-emit Thread/sleep wait —
          ;; default 1000 ms forces tests to sleep 1500 ms per emit, 250 ms
          ;; lets us sleep 400 ms instead.
          listener (pg-notify/create-listener pg-opts {:poll-timeout-ms 250})
          seen-a (atom [])
          seen-b (atom [])
          cb-a (pg-notify/register! listener (fn [e] (swap! seen-a conj e)))
          cb-b (pg-notify/register! listener (fn [e] (swap! seen-b conj e)))]
      (try
        (let [writer-conn (java.sql.DriverManager/getConnection
                            ^String (:jdbc-url pg-opts)
                            (:username pg-opts)
                            (:password pg-opts))]
          (try
            (jdbc/execute! writer-conn
                           ["SELECT pg_notify('graphden_events', ?)"
                            "fn:invalidate:abc"])
            (finally (Connection/.close writer-conn))))
        (Thread/sleep 400)
        (is (= [{:kind :fn :op :invalidate :id "abc"}] @seen-a))
        (is (= [{:kind :fn :op :invalidate :id "abc"}] @seen-b))
        (finally
          (pg-notify/unregister! listener cb-a)
          (pg-notify/unregister! listener cb-b)
          (pg-notify/close-listener! listener))))))


(deftest ^:integration reconnects-after-connection-drop-test
  (testing "a dropped LISTEN connection reconnects + keeps delivering events"
    (let [pg-opts (pg-opts-from-fixture)
          listener (pg-notify/create-listener pg-opts {:poll-timeout-ms 250})
          received (atom [])
          cb (pg-notify/register! listener (fn [e] (swap! received conj e)))]
      (try
        ;; Simulate a DB restart / network blip — close the listener's
        ;; dedicated connection out from under the poll loop. Pre-fix the
        ;; loop would spin on the dead conn forever (permanently deaf).
        (Connection/.close @(:conn-atom listener))
        ;; The loop hits the dead conn, classifies it as a connection
        ;; error, and reconnects (first backoff is 1s) — give it headroom.
        (Thread/sleep 2000)
        ;; Emit AFTER the reconnect: only a connection that has re-run
        ;; LISTEN on a fresh session
        ;; will observe this notification.
        (let [writer-conn (java.sql.DriverManager/getConnection
                            ^String (:jdbc-url pg-opts)
                            (:username pg-opts)
                            (:password pg-opts))]
          (try
            (jdbc/execute! writer-conn
                           ["SELECT pg_notify('graphden_events', ?)"
                            "fn:invalidate:after-reconnect"])
            (finally (Connection/.close writer-conn))))
        (Thread/sleep 500)
        (is (= [{:kind :fn :op :invalidate :id "after-reconnect"}] @received)
            "event delivered on the reconnected connection")
        (finally
          (pg-notify/unregister! listener cb)
          (pg-notify/close-listener! listener))))))


(deftest ^:integration unregister-stops-dispatching-test
  (testing "after `unregister!` the callback no longer fires"
    (let [pg-opts (pg-opts-from-fixture)
          ;; Shorter poll-timeout cuts the post-emit Thread/sleep wait —
          ;; default 1000 ms forces tests to sleep 1500 ms per emit, 250 ms
          ;; lets us sleep 400 ms instead.
          listener (pg-notify/create-listener pg-opts {:poll-timeout-ms 250})
          received (atom [])
          callback (pg-notify/register! listener
                                        (fn [event] (swap! received conj event)))]
      (try
        (pg-notify/unregister! listener callback)
        (let [writer-conn (java.sql.DriverManager/getConnection
                            ^String (:jdbc-url pg-opts)
                            (:username pg-opts)
                            (:password pg-opts))]
          (try
            (jdbc/execute! writer-conn
                           ["SELECT pg_notify('graphden_events', ?)"
                            "service:write:should-be-ignored"])
            (finally (Connection/.close writer-conn))))
        (Thread/sleep 400)
        (is (= [] @received) "no fire because the callback was unregistered")
        (finally
          (pg-notify/close-listener! listener))))))

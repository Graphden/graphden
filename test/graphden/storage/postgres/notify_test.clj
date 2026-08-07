(ns graphden.storage.postgres.notify-test
  "Tests for the LISTEN/NOTIFY transport.

   `payload-roundtrip-test` and `make-emitter-swallows-a-notify-failure`
   are pure unit tests. The rest are tagged per-deftest `^:integration` —
   they run against the shared PG test container using TWO listeners
   sharing one channel to simulate the multi-pod case: one writer
   pod NOTIFYs, sibling pod's listener thread observes."
  (:require
    [clojure.string :as str]
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


(defn- await-until
  "Poll `pred` every 20 ms until it returns truthy or `timeout-ms`
   elapses. Returns the final `pred` result — truthy on success,
   falsey on timeout. Deterministic replacement for the fixed
   post-emit `Thread/sleep`: waits exactly as long as delivery takes
   (typically one ~250 ms poll window) yet never flakes on a loaded
   host, where a fixed sleep either wastes time or expires early."
  [timeout-ms pred]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 20)
            (recur))))))


;; ============================================================================
;; payload codec
;; ============================================================================

(deftest payload-roundtrip-test
  (testing "format then parse → identity (no branch)"
    (let [event {:kind :service :op :write :id "abc-123"}]
      (is (= event (pg-notify/parse-payload (pg-notify/format-payload event))))))

  (testing "format then parse → identity (with branch)"
    (let [event {:kind :fn :op :invalidate :id "fn-1" :branch-id "br-9"}]
      (is (= event (pg-notify/parse-payload (pg-notify/format-payload event))))))

  (testing "full-clear: empty id, branch still carried"
    (let [payload (pg-notify/format-payload
                    {:kind :fn :op :invalidate :id "" :branch-id "br-9"})]
      (is (= "fn:invalidate:|br-9" payload))
      (is (= {:kind :fn :op :invalidate :id "" :branch-id "br-9"}
             (pg-notify/parse-payload payload)))))

  (testing "a payload from a pod that predates :branch-id omits the key"
    (is (= {:kind :fn :op :invalidate :id "fn-1"}
           (pg-notify/parse-payload "fn:invalidate:fn-1"))))

  (testing "org-id rides in the third slot; branch + org round-trip"
    (let [event {:kind :fn :op :invalidate :id "fn-1" :branch-id "br-9" :org-id "acme"}]
      (is (= "fn:invalidate:fn-1|br-9|acme" (pg-notify/format-payload event)))
      (is (= event (pg-notify/parse-payload (pg-notify/format-payload event))))))

  (testing "org with NO branch forces an empty branch slot so positions align"
    (let [event {:kind :fn :op :invalidate :id "fn-1" :org-id "acme"}]
      (is (= "fn:invalidate:fn-1||acme" (pg-notify/format-payload event)))
      (is (= event (pg-notify/parse-payload "fn:invalidate:fn-1||acme")))))

  (testing "an older payload without the org slot omits :org-id"
    (is (= {:kind :fn :op :invalidate :id "fn-1" :branch-id "br-9"}
           (pg-notify/parse-payload "fn:invalidate:fn-1|br-9"))))

  (testing "parse on a malformed payload → nil"
    (is (nil? (pg-notify/parse-payload "")))
    (is (nil? (pg-notify/parse-payload "no-colons")))
    (is (nil? (pg-notify/parse-payload "only:one-colon")))
    (is (nil? (pg-notify/parse-payload nil)))
    (is (nil? (pg-notify/parse-payload 42)))))


(deftest make-emitter-swallows-a-notify-failure
  ;; A NOTIFY is best-effort: the row write already committed, so a transient
  ;; pg_notify failure must NOT throw back into the caller (which could roll it
  ;; back). The reconcile/mutation path is the correctness mechanism; NOTIFY
  ;; only speeds propagation.
  (let [bad-ds (reify javax.sql.DataSource
                 (getConnection [_] (throw (java.sql.SQLException. "pool exhausted"))))
        emit (pg-notify/make-emitter bad-ds)]
    (is (nil? (emit {:kind :fn :op :invalidate :id "x"}))
        "the SQLException is swallowed, emit returns nil")))


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
          (await-until 10000 #(seq @received))
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
        (await-until 10000 #(and (seq @seen-a) (seq @seen-b)))
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
        ;; error, and reconnects (first backoff is 1s). Reconnect
        ;; completion isn't observable directly, so emit a FRESH event
        ;; every 200 ms until one is observed: an emit is only delivered
        ;; to a session that has re-run LISTEN, so the first observed
        ;; event proves the reconnect worked — no fixed sleep to outgrow
        ;; the backoff on a loaded host.
        (let [writer-conn (java.sql.DriverManager/getConnection
                            ^String (:jdbc-url pg-opts)
                            (:username pg-opts)
                            (:password pg-opts))
              deadline (+ (System/currentTimeMillis) 15000)]
          (try
            (loop [i 0]
              (jdbc/execute! writer-conn
                             ["SELECT pg_notify('graphden_events', ?)"
                              (str "fn:invalidate:after-reconnect-" i)])
              (when (and (empty? @received)
                         (< (System/currentTimeMillis) deadline))
                (Thread/sleep 200)
                (recur (inc i))))
            (finally (Connection/.close writer-conn))))
        (is (seq @received)
            "event delivered on the reconnected connection")
        (is (every? (fn [e]
                      (and (= :fn (:kind e))
                           (= :invalidate (:op e))
                           (str/starts-with? (:id e) "after-reconnect-")))
                    @received)
            "every delivered event is one of the post-drop emits")
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
        ;; A fixed post-emit sleep can only ever prove "nothing arrived
        ;; YET". Instead register a sentinel callback and emit a second
        ;; event AFTER the should-be-ignored one: dispatch is ordered, so
        ;; once the sentinel event is observed the ignored one has
        ;; already been dispatched — to nobody.
        (let [sentinel (atom [])
              sentinel-cb (pg-notify/register!
                            listener (fn [e] (swap! sentinel conj e)))
              writer-conn (java.sql.DriverManager/getConnection
                            ^String (:jdbc-url pg-opts)
                            (:username pg-opts)
                            (:password pg-opts))]
          (try
            (jdbc/execute! writer-conn
                           ["SELECT pg_notify('graphden_events', ?)"
                            "service:write:should-be-ignored"])
            (jdbc/execute! writer-conn
                           ["SELECT pg_notify('graphden_events', ?)"
                            "service:write:sentinel"])
            (finally (Connection/.close writer-conn)))
          (is (await-until 10000 #(seq @sentinel))
              "sentinel callback observed the later event")
          (pg-notify/unregister! listener sentinel-cb))
        (is (= [] @received) "no fire because the callback was unregistered")
        (finally
          (pg-notify/close-listener! listener))))))

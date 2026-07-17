(ns ^:integration graphden.storage.postgres.advisory-lock-test
  "Integration tests for per-service Postgres advisory locks.

   Multi-pod safety boils down to: when two pods try to start the
   same service at the same time, exactly one wins the
   `pg_try_advisory_lock`. We simulate this here with two dedicated
   connections against the shared test container."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.postgres.advisory-lock :as pg-lock])
  (:import
    (com.zaxxer.hikari
      HikariDataSource)
    (java.sql
      Connection
      DriverManager)
    (java.util
      Properties)))


(use-fixtures :once (setup/create-container-fixture))


(defn- pg-opts-from-fixture
  []
  ;; `getJdbcUrl` / `getUsername` / `getPassword` are declared on
  ;; `HikariConfig` (the parent class of `HikariDataSource`), NOT on
  ;; the `HikariConfigMXBean` interface — call them directly on the
  ;; pool instead of round-tripping through the MXBean.
  (let [pool (-> (setup/create-test-storage) :pool)
        u (HikariDataSource/.getJdbcUrl pool)
        un (HikariDataSource/.getUsername pool)
        pw (HikariDataSource/.getPassword pool)]
    (HikariDataSource/.close pool)
    {:jdbc-url u :username un :password pw}))


(defn- open-conn
  [{:keys [jdbc-url username password]}]
  (let [props (Properties.)]
    (Properties/.setProperty props "user" username)
    (Properties/.setProperty props "password" password)
    (DriverManager/getConnection ^String jdbc-url props)))


(defn- wait-for
  "Poll `pred` until truthy or `ms` elapses; returns the truthy value or
   nil on timeout. Used where the condition becomes true ASYNCHRONOUSLY
   (Postgres reaping an orphaned backend) so the assert doesn't race the
   server-side cleanup under host load."
  [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (or (pred) (when (< (System/currentTimeMillis) deadline)
                          (Thread/sleep 25) (recur))))))


(deftest two-pods-only-one-wins-test
  (testing "first try-lock! wins, second sees false (same service-id)"
    (let [pg-opts (pg-opts-from-fixture)
          pod-a (open-conn pg-opts)
          pod-b (open-conn pg-opts)
          svc-id (random-uuid)]
      (try
        (is (true? (pg-lock/try-lock! pod-a svc-id))
            "pod A acquires the lock")
        (is (false? (pg-lock/try-lock! pod-b svc-id))
            "pod B can NOT acquire the lock — sibling holds it")
        (testing "after A releases, B can acquire"
          (is (true? (pg-lock/release-lock! pod-a svc-id)))
          (is (true? (pg-lock/try-lock! pod-b svc-id))))
        (finally
          (try (pg-lock/release-all! pod-a) (catch Exception _ nil))
          (try (pg-lock/release-all! pod-b) (catch Exception _ nil))
          (Connection/.close pod-a) (Connection/.close pod-b))))))


(deftest different-services-independent-test
  (testing "advisory locks are per-key — distinct service-ids never contend"
    (let [pg-opts (pg-opts-from-fixture)
          pod-a (open-conn pg-opts)
          pod-b (open-conn pg-opts)
          svc-1 (random-uuid)
          svc-2 (random-uuid)]
      (try
        (is (true? (pg-lock/try-lock! pod-a svc-1)) "A owns svc-1")
        (is (true? (pg-lock/try-lock! pod-b svc-2))
            "B owns svc-2 independently — no contention")
        (finally
          (try (pg-lock/release-all! pod-a) (catch Exception _ nil))
          (try (pg-lock/release-all! pod-b) (catch Exception _ nil))
          (Connection/.close pod-a) (Connection/.close pod-b))))))


(deftest connection-close-releases-lock-test
  (testing "closing the lock-holding connection auto-releases — sibling can take over"
    (let [pg-opts (pg-opts-from-fixture)
          svc-id (random-uuid)]
      (let [pod-a (open-conn pg-opts)]
        (is (true? (pg-lock/try-lock! pod-a svc-id)))
        (Connection/.close pod-a))
      ;; Now a fresh pod B tries — Postgres released A's lock when the
      ;; session ended. Without this property the pod-crash recovery
      ;; story (advertised in the ns docstring) doesn't hold.
      (let [pod-b (open-conn pg-opts)]
        (try
          (is (true? (pg-lock/try-lock! pod-b svc-id))
              "B can acquire after A's connection close released the lock")
          (finally
            (try (pg-lock/release-all! pod-b) (catch Exception _ nil))
            (Connection/.close pod-b)))))))


;; =============================================================================
;; Reconnecting holder — the drop-and-re-acquire hardening.
;; =============================================================================

(deftest ensure-live-reconnects-a-dead-connection-test
  (testing "ensure-live! is a no-op on a healthy conn, reconnects a dead one"
    (let [pg-opts (pg-opts-from-fixture)
          holder (pg-lock/create-lock-holder pg-opts)]
      (try
        (let [c0 (pg-lock/holder-conn holder)]
          (is (false? (pg-lock/ensure-live! holder))
              "healthy connection → no reconnect")
          (is (identical? c0 (pg-lock/holder-conn holder))
              "same connection object after a no-op ensure-live!")
          ;; Simulate a drop: close the underlying connection out from under
          ;; the holder (DB restart / network blip look the same to isValid).
          (Connection/.close c0)
          (is (true? (pg-lock/ensure-live! holder))
              "dead connection → reconnected")
          (is (not (identical? c0 (pg-lock/holder-conn holder)))
              "a fresh connection replaced the dead one")
          (is (false? (Connection/.isClosed (pg-lock/holder-conn holder)))
              "the fresh connection is open"))
        (finally (pg-lock/close-holder! holder))))))


(deftest dropped-lock-is-lost-until-reconnect-reacquires-test
  (testing "a dropped conn releases the lock (the vulnerability); the fresh "
    (let [pg-opts (pg-opts-from-fixture)
          svc-id (random-uuid)
          holder (pg-lock/create-lock-holder pg-opts)
          sibling (open-conn pg-opts)]
      (try
        (is (true? (pg-lock/try-lock! (pg-lock/holder-conn holder) svc-id))
            "pod holds the lock")
        ;; Drop the pod's lock connection — Postgres releases its lock
        ;; when it reaps the orphaned backend. That reap is ASYNCHRONOUS:
        ;; closing the client socket does not synchronously free the
        ;; session lock, so poll until the sibling can take it rather
        ;; than racing the server-side cleanup (a fixed immediate assert
        ;; flakes under host load, when the reap lags behind it).
        (Connection/.close (pg-lock/holder-conn holder))
        (is (true? (wait-for 10000 #(pg-lock/try-lock! sibling svc-id)))
            "with the pod disconnected, a sibling CAN take the lock once PG reaps the dead backend")
        ;; Sibling releases; pod reconnects and re-acquires successfully
        ;; (nobody holds it now).
        (is (true? (pg-lock/release-lock! sibling svc-id)))
        (is (true? (pg-lock/ensure-live! holder)) "pod reconnects")
        (is (true? (pg-lock/try-lock! (pg-lock/holder-conn holder) svc-id))
            "re-acquire succeeds on the fresh session — pod owns it again")
        (finally
          (try (pg-lock/release-all! sibling) (catch Exception _ nil))
          (Connection/.close sibling)
          (pg-lock/close-holder! holder))))))

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

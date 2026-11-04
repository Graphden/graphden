(ns graphden.clients.sql-pool-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.clients.sql-pool :as pool])
  (:import
    (com.zaxxer.hikari
      HikariDataSource)))


(def ^:private url "jdbc:postgresql://127.0.0.1:5432/test")


(defn- call
  [manager scope password f]
  (pool/with-datasource manager scope url "test-user" password f))


(deftest reuses-only-the-same-scope-and-credential-generation
  (let [manager (pool/create-manager {:sweep-ms 60000})
        a {:org "one" :principal "alice" :branch "main"}
        b (assoc a :principal "bob")]
    (try
      (let [first-pool (call manager a "first" identity)
            reused (call manager a "first" identity)
            other-principal (call manager b "first" identity)
            other-branch (call manager (assoc a :branch "feature") "first" identity)
            rotated (call manager a "second" identity)]
        (is (identical? first-pool reused))
        (is (not (identical? first-pool other-principal)))
        (is (not (identical? first-pool other-branch)))
        (is (not (identical? first-pool rotated)))
        (is (HikariDataSource/.isClosed first-pool))
        (is (not (HikariDataSource/.isClosed rotated))))
      (finally (pool/close! manager)))))


(deftest rotation-waits-for-an-outstanding-user
  (let [manager (pool/create-manager {:sweep-ms 60000})
        scope {:org "one" :principal "alice" :branch "main"}
        old-pool (atom nil)]
    (try
      (call manager scope "first"
            (fn [^HikariDataSource old]
              (reset! old-pool old)
              (call manager scope "second" identity)
              (is (not (HikariDataSource/.isClosed old)))))
      (is (HikariDataSource/.isClosed ^HikariDataSource @old-pool))
      (finally (pool/close! manager)))))


(deftest global-limit-and-idle-retirement
  (let [clock (atom 0)
        manager (pool/create-manager {:max-pools 1 :idle-ms 30000
                                      :sweep-ms 60000 :clock #(deref clock)})
        a {:org "one" :principal "alice" :branch "main"}
        b {:org "two" :principal "alice" :branch "main"}]
    (try
      (testing "a live lease cannot be evicted to make room"
        (call manager a "first"
              (fn [_]
                (let [failure (try (call manager b "first" identity) nil
                                   (catch clojure.lang.ExceptionInfo e e))]
                  (is (= :sql/pool-limit (:type (ex-data failure))))))))
      (swap! clock + 30000)
      (is (= 1 (pool/reap! manager)))
      (is (instance? HikariDataSource (call manager b "first" identity)))
      (finally (pool/close! manager)))))


(deftest close-denies-new-leases-and-drains-active-one
  (let [manager (pool/create-manager {:sweep-ms 60000})
        scope {:org "one" :principal "alice" :branch "main"}
        old (atom nil)]
    (call manager scope "first"
          (fn [^HikariDataSource ds]
            (reset! old ds)
            (pool/close! manager)
            (is (not (HikariDataSource/.isClosed ds)))))
    (is (HikariDataSource/.isClosed ^HikariDataSource @old))
    (let [failure (try (call manager scope "first" identity) nil
                       (catch clojure.lang.ExceptionInfo e e))]
      (is (= :sql/pool-closed (:type (ex-data failure)))))))


(deftest checkpoint-closes-sockets-and-resumes-with-a-fresh-generation
  (let [manager (pool/create-manager {:sweep-ms 60000})
        scope {:org "one" :principal "alice" :branch "main"}]
    (try
      (let [old (call manager scope "first" identity)]
        (is (= 1 (pool/quiesce! manager)))
        (is (HikariDataSource/.isClosed old))
        (is (= :sql/pool-paused
               (:type (ex-data
                        (try (call manager scope "first" identity) nil
                             (catch clojure.lang.ExceptionInfo e e))))))
        (pool/resume! manager)
        (is (not (identical? old (call manager scope "first" identity)))))
      (finally (pool/close! manager)))))


(deftest checkpoint-refuses-an-active-external-query
  (let [manager (pool/create-manager {:sweep-ms 60000})
        scope {:org "one" :principal "alice" :branch "main"}]
    (try
      (call manager scope "first"
            (fn [^HikariDataSource ds]
              (is (= :sql/pool-busy
                     (:type (ex-data
                              (try (pool/quiesce! manager) nil
                                   (catch clojure.lang.ExceptionInfo e e))))))
              (is (not (HikariDataSource/.isClosed ds)))
              (is (identical? ds (call manager scope "first" identity)))))
      (finally (pool/close! manager)))))

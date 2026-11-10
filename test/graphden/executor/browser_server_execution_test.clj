(ns ^:serial graphden.executor.browser-server-execution-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.executor.browser-server-execution :as execution]
    [graphden.executor.source-recipe-lifecycle :as recipes]
    [graphden.executor.source-recipe-store :as recipe-store]
    [graphden.storage.remote.core :as remote]
    [graphden.tenancy.context :as tenancy])
  (:import
    (java.util.concurrent
      CountDownLatch
      ExecutorService
      TimeUnit)))


(deftest timed-out-worker-keeps-its-owned-lifetime-and-admission-until-it-exits
  (let [pool (persist/make-execution-pool 1 1)
        ctx {:storage (remote/from-bundle {})}
        started (promise)
        closed (promise)
        finish (CountDownLatch. 1)]
    (try
      (binding [persist/*execution-pool-override* pool
                persist/*max-concurrent-executions-per-org* 1
                tenancy/*current-org* tenancy/public-org]
        (with-redefs-fn
          {(ns-resolve 'graphden.executor.browser-server-execution 'wall-ms) 100}
          (fn []
            (let [waiting (future
                            (try
                              (execution/run
                                ctx
                                (fn []
                                  (deliver started true)
                                  (try
                                    ;; Model JDBC that needs time to unwind after
                                    ;; cancellation. Its connection remains owned.
                                    (try (CountDownLatch/.await finish) (catch InterruptedException _ (CountDownLatch/.await finish)))
                                    :late-value
                                    (finally (deliver closed true)))))
                              (catch Exception error (:reason (ex-data error)))))]
              (is (true? (deref started 1000 false)))
              (is (= :server-timeout (deref waiting 1000 :did-not-time-out)))
              (is (not (realized? closed)))
              (is (= :server-capacity
                     (try (execution/run ctx (constantly :not-admitted))
                          (catch Exception error (:reason (ex-data error))))))
              (CountDownLatch/.countDown finish)
              (is (true? (deref closed 1000 false)))))))
      (finally
        (CountDownLatch/.countDown finish)
        (ExecutorService/.shutdown pool)
        (is (ExecutorService/.awaitTermination pool 2 TimeUnit/SECONDS))))))


(deftest interrupted-caller-does-not-release-a-still-running-workers-recipe-budget
  (let [pool (persist/make-execution-pool 1 1)
        ctx {:storage (remote/from-bundle {})}
        cache (recipe-store/create-store {:max-bytes 100 :max-entries 4 :max-pending 4
                                          :max-families 4 :max-leases 4 :max-ttl-ms 100
                                          :clock-ms (constantly 0) :closed? (constantly false)})
        family (recipe-store/create-family! cache (Object.) 100)
        physical (Object.) caller (promise) started (promise) late-recorded (promise)
        finish (CountDownLatch. 1)]
    (try
      (binding [persist/*execution-pool-override* pool
                persist/*max-concurrent-executions-per-org* 1
                tenancy/*current-org* tenancy/public-org]
        (let [waiting (future
                        (deliver caller (Thread/currentThread))
                        (try
                          (recipes/call-with-operation {:store cache}
                                                       (fn []
                                                         (execution/run ctx
                                                                        (fn []
                                                                          (let [ticket (recipe-store/reserve! cache family physical 80)]
                                                                            (recipes/track-reservation! ticket)
                                                                            (deliver started true)
                                                                            ;; A cancelled JDBC worker may still own resources.
                                                                            (try (.await finish) (catch InterruptedException _ (.await finish)))
                                                                            (deliver late-recorded (recipes/complete-record! ticket :late 40))
                                                                            {:ok true})))))
                          (catch InterruptedException _ :interrupted)))]
          (is (true? (deref started 2000 false)))
          (.interrupt ^Thread @caller)
          (is (= :interrupted (deref waiting 2000 :still-waiting)))
          (is (= {:entries 0 :pending 1 :charged-bytes 80}
                 (select-keys (recipe-store/stats cache) [:entries :pending :charged-bytes])))
          (.countDown finish)
          (.shutdown ^ExecutorService pool)
          (is (.awaitTermination ^ExecutorService pool 2 TimeUnit/SECONDS))
          (is (false? (deref late-recorded 1000 :missing)))
          (is (= {:entries 0 :pending 0 :charged-bytes 0}
                 (select-keys (recipe-store/stats cache) [:entries :pending :charged-bytes])))))
      (finally
        (.countDown finish)
        (.shutdown ^ExecutorService pool)
        (.awaitTermination ^ExecutorService pool 2 TimeUnit/SECONDS)))))

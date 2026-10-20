(ns ^:serial graphden.executor.browser-server-execution-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.executor.browser-server-execution :as execution]
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
                                    (try (.await finish) (catch InterruptedException _ (.await finish)))
                                    :late-value
                                    (finally (deliver closed true)))))
                              (catch Exception error (:reason (ex-data error)))))]
              (is (= true (deref started 1000 false)))
              (is (= :server-timeout (deref waiting 1000 :did-not-time-out)))
              (is (not (realized? closed)))
              (is (= :server-capacity
                     (try (execution/run ctx (constantly :not-admitted))
                          (catch Exception error (:reason (ex-data error))))))
              (.countDown finish)
              (is (= true (deref closed 1000 false)))))))
      (finally
        (.countDown finish)
        (ExecutorService/.shutdown pool)
        (is (ExecutorService/.awaitTermination pool 2 TimeUnit/SECONDS))))))

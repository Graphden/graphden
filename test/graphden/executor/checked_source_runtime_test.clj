(ns ^:serial graphden.executor.checked-source-runtime-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.executor.browser-server-execution :as execution]
    [graphden.executor.checked-source-memo :as memo]
    [graphden.executor.checked-source-runtime :as runtime]
    [graphden.executor.compile-runtime :as compile]
    [graphden.storage.remote.core :as remote]
    [graphden.system.init.exec]
    [graphden.tenancy.context :as tenancy]
    [integrant.core :as ig])
  (:import
    (java.util.concurrent
      CountDownLatch
      ExecutorService
      TimeUnit)))


(deftest runtime-cache-is-explicit-owned-and-raw-replay-stays-off
  (doseq [enabled [nil false "false" true "true"]]
    (let [ctx (ig/init-key :exec/context {:storage (remote/from-bundle {})
                                          :checker-cache? enabled})
          cache (:checker-cache ctx)]
      (try
        (is (= (contains? #{true "true"} enabled) (some? cache)))
        (is (nil? (:source-replay ctx)))
        (when cache
          (is (zero? (:charged-bytes (memo/stats cache))) "No eager 512 MiB allocation")
          (is (= (* 256 1024 1024) (:entry-bytes cache))))
        (finally (ig/halt-key! :exec/context ctx)))
      (when cache (is (false? @(:active? cache)))))))


(deftest cancelled-worker-keeps-checker-reservation-until-unwind
  (let [cache (runtime/create-cache)
        pool (persist/make-execution-pool 1 1)
        ctx {:storage (remote/from-bundle {}) :checker-cache cache}
        caller (promise) entered (promise) finish (CountDownLatch. 1)
        original {}]
    (try
      (binding [persist/*execution-pool-override* pool
                persist/*max-concurrent-executions-per-org* 1
                tenancy/*current-org* tenancy/public-org]
        (let [waiting (future
                        (deliver caller (Thread/currentThread))
                        (try
                          (execution/run
                            ctx
                            #(memo/call-with-artifact
                               (:checker-cache ctx)
                               {:family [:runtime :cancel] :original-rich original
                                :prepare (constantly {:input 1})
                                :build identity :restore identity
                                :consume (fn []
                                           (deliver entered true)
                                           ;; Model a worker that still owns its source after
                                           ;; the waiting HTTP thread has been cancelled.
                                           (try (.await finish)
                                                (catch InterruptedException _ (.await finish)))
                                           :late)
                                :fallback (constantly :unexpected-fallback)
                                :check-cancel compile/check-cancel!}))
                          (catch InterruptedException _ :interrupted)))]
          (is (true? (deref entered 2000 false)))
          (.interrupt ^Thread @caller)
          (is (= :interrupted (deref waiting 2000 :waiting)))
          (is (= {:pending 1 :entries 0 :charged-bytes (* 256 1024 1024)}
                 (select-keys (memo/stats cache) [:pending :entries :charged-bytes])))
          (testing "runtime retirement cannot free a still-running reservation"
            (memo/retire! cache)
            (is (= 1 (:pending (memo/stats cache)))))
          (.countDown finish)
          (.shutdown ^ExecutorService pool)
          (is (.awaitTermination ^ExecutorService pool 2 TimeUnit/SECONDS))
          (is (= {:pending 0 :entries 0 :leases 0 :charged-bytes 0}
                 (select-keys (memo/stats cache) [:pending :entries :leases :charged-bytes])))))
      (finally
        (.countDown finish)
        (.shutdown ^ExecutorService pool)
        (.awaitTermination ^ExecutorService pool 2 TimeUnit/SECONDS)
        (memo/retire! cache)))))

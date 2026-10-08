(ns graphden.crud.fn-execution.authorization-test
  "Independent submitted roots must not inherit a trusted handler's authority."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.fn-execution :as fn-exec]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.executor.compile-runtime :as cr]
    [graphden.tenancy.context :as tc]))


(def ^:private selected-id (java.util.UUID/randomUUID))
(def ^:private principal {:user-id (java.util.UUID/randomUUID) :api-token? true})


(defn- call-with-pool
  [body]
  (let [pool (persist/make-execution-pool 1 2)]
    (try
      (binding [persist/*execution-pool-override* pool
                persist/*max-execution-wall-ms* nil]
        (body pool))
      (finally
        (java.util.concurrent.ExecutorService/.shutdownNow pool)))))


(deftest submit-refuses-before-storage-slot-row-or-future
  (doseq [scope [nil :execute]]
    (testing (if scope "token ceiling" "namespace grant")
      (call-with-pool
        (fn [pool]
          (let [seen (atom [])
                ctx {:execute-guard
                     (fn [_ id]
                       (swap! seen conj [id tc/*current-principal*])
                       (throw (ex-info "Do not publish guard details"
                                       (cond-> {:type :authz/forbidden}
                                         scope (assoc :token-scope scope)))))}
                reason (if scope :token-scope :forbidden)
                outcome (binding [cr/*execute-authorized* true
                                  tc/*current-principal* principal]
                          ;; Missing arguments and a persist request do not
                          ;; authorize a different root or create an audit row.
                          (fn-exec/apply-execute ctx {:args {} :persist? true}
                                                 {:id selected-id}))]
            ;; There is deliberately no storage: touching the plan would fail.
            (is (= {:ok false :status :rejected :http-status 403
                    :error (name reason) :error-data {:reason reason}}
                   outcome))
            (is (= [[selected-id principal]] @seen))
            (is (zero? (java.util.concurrent.ThreadPoolExecutor/.getTaskCount pool)))))))))


(deftest unexpected-guard-errors-are-not-labelled-forbidden
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"guard unavailable"
        (fn-exec/apply-execute
          {:execute-guard (fn [_ _] (throw (ex-info "guard unavailable" {:type :test/fault})))}
          {:args {}} {:id selected-id}))))


(deftest worker-rechecks-the-selected-root-before-effects
  (call-with-pool
    (fn [_]
      (let [effects (atom 0)
            seen (atom [])
            ctx {:compiled-registry (atom {selected-id (fn [_ _] (swap! effects inc))})
                 :execute-guard (fn [_ id]
                                  (swap! seen conj [id tc/*current-principal*])
                                  (throw (ex-info "denied" {:type :authz/forbidden})))}
            [task] (binding [cr/*execute-authorized* true
                             tc/*current-principal* principal]
                     (persist/run-future ctx selected-id {} (atom false) nil))
            cause (try
                    (java.util.concurrent.Future/.get task 5 java.util.concurrent.TimeUnit/SECONDS)
                    nil
                    (catch java.util.concurrent.ExecutionException error
                      (ex-cause error)))]
        (is (= :authz/forbidden (:type (ex-data cause))))
        (is (= [[selected-id principal]] @seen))
        (is (zero? @effects))))))


(deftest allowed-worker-keeps-the-submitting-principal
  (call-with-pool
    (fn [_]
      (let [entered (promise)
            proceed (promise)
            seen (atom [])
            ctx {:graph-cache (atom {:fns [] :slots [] :fn-slots [] :bindings [] :binding-list-items []})
                 :compiled-registry (atom {selected-id (fn [_ _] tc/*current-principal*)})
                 :execute-guard (fn [_ id]
                                  (swap! seen conj [id tc/*current-principal*])
                                  (deliver entered true)
                                  (when (= :timeout (deref proceed 5000 :timeout))
                                    (throw (ex-info "test barrier timed out" {}))))}
            [task] (binding [cr/*execute-authorized* true
                             tc/*current-principal* principal]
                     (persist/run-future ctx selected-id {} (atom false) nil))]
        (try
          (is (true? (deref entered 5000 :timeout)))
          (finally (deliver proceed true)))
        (is (= principal (java.util.concurrent.Future/.get task 5 java.util.concurrent.TimeUnit/SECONDS)))
        (is (= [[selected-id principal]] @seen))))))

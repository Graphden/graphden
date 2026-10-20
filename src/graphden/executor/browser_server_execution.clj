(ns graphden.executor.browser-server-execution
  "Bounded, nonpersisting execution for certified read-only browser targets.
   A worker owns its snapshot until its actual computation exits."
  (:require
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.executor.browser-source :as source]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.tenancy.context :as tenancy])
  (:import
    (java.util.concurrent
      ExecutionException
      ExecutorService
      Future
      RejectedExecutionException
      TimeUnit
      TimeoutException)))


(def ^:private wall-ms 5000)


(defn- reject!
  [reason]
  (throw (ex-info "Read-only server execution is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn run
  "Run a server-only thunk on the existing bounded pool/admission budget.
   The thunk opens its OWN snapshot. Timeout cancels waiting and work; a
   still-unwinding JDBC operation retains both its snapshot and its permit."
  [ctx thunk]
  (let [org (tenancy/current-org)
        release (persist/acquire-execution-slot! (:storage ctx) org (not= org tenancy/public-org))]
    (when-not release (reject! :server-capacity))
    (let [cancelled? (atom false)
          deadline (+ (System/nanoTime) (* wall-ms 1000000))
          check! #(when (or @cancelled? (> (System/nanoTime) deadline))
                    (throw (InterruptedException. "Read-only server execution expired")))
          task (persist/release-on-done-task
                 (bound-fn []
                   (binding [runtime/*execute-authorized* false
                             runtime/*cancel-check* check!
                             source/*snapshot-check* check!
                             source/*statement-timeout-ms* 1000]
                     (check!)
                     (let [result (thunk)] (check!) result)))
                 release)]
      (try
        (ExecutorService/.execute (persist/current-execution-pool) task)
        (Future/.get task wall-ms TimeUnit/MILLISECONDS)
        (catch RejectedExecutionException _
          (release)
          (reject! :server-capacity))
        (catch TimeoutException _
          (reset! cancelled? true)
          (Future/.cancel task true)
          (reject! :server-timeout))
        (catch InterruptedException error
          (reset! cancelled? true)
          (Future/.cancel task true)
          (throw error))
        (catch ExecutionException error
          (throw (.getCause error)))))))

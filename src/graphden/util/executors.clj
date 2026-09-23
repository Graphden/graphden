(ns graphden.util.executors
  "Lifecycle helper for the scheduled executors the integrant halt-keys own."
  (:import
    (java.util.concurrent
      ExecutorService
      TimeUnit)))


(def ^:private await-seconds
  "How long a halt waits for an in-flight tick before moving on."
  5)


(defn shutdown-and-await!
  "Stop `executor` taking new ticks and wait up to 5 s for the one in flight,
   so a halt does not tear down what a still-running tick is using (the
   pool, the lock connection, the running-services map). nil is a no-op."
  [^ExecutorService executor]
  (when executor
    (.shutdown executor)
    (try (.awaitTermination executor await-seconds TimeUnit/SECONDS)
         (catch InterruptedException _ nil))))

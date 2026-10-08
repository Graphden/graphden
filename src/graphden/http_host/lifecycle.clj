(ns graphden.http-host.lifecycle
  "Platform-owned cleanup, tied to the existing branch router's lifecycle.
   Tenant graphs never own this timer or install persistent workers."
  (:require
    [clojure.tools.logging :as log]
    [graphden.http-host.core :as host]
    [graphden.http-host.lease :as lease]
    [graphden.services.endpoint :as endpoint]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.route-collection :as routes]
    [graphden.tenancy.context :as tc]
    [graphden.versioning.storage.core :as vs])
  (:import
    (java.util.concurrent
      Executors
      ScheduledExecutorService
      ThreadFactory
      TimeUnit)))


(defn- reap!
  [storage]
  (tc/with-org tc/public-org
               (lease/reap! storage)
               (when-let [f (:reap! @host/adapter)] (f storage))))


(defn start!
  "Install routes and endpoint resolution, reap stale leases on restart,
   then clean up every minute. Small test schemas may omit sessions."
  [router]
  (let [storage (vs/unwrap (:storage (:base-ctx router)))]
    (when (contains? (sp/current-entities storage) :session)
      (reap! storage)
      (let [timer (Executors/newSingleThreadScheduledExecutor
                    (reify ThreadFactory
                      (newThread
                        [_ runnable]
                        (doto (Thread. ^Runnable runnable "temporary-http-cleanup")
                          (Thread/.setDaemon true)))))
            sweep (fn []
                    (try (reap! storage)
                         (catch Exception _ (log/warn "Temporary HTTP cleanup will retry"))))]
        (routes/install-router! :temporary-http (host/make-router router))
        (reset! endpoint/temporary-resolver host/endpoint)
        (ScheduledExecutorService/.scheduleWithFixedDelay timer ^Runnable sweep 60 60 TimeUnit/SECONDS)
        timer))))


(defn stop!
  [timer]
  (routes/remove-router! :temporary-http)
  (reset! endpoint/temporary-resolver nil)
  (when timer (ScheduledExecutorService/.shutdownNow timer)))

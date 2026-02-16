(ns graphden.system.interface
  "System lifecycle management using Integrant.

   Provides declarative component initialization with explicit dependencies.

   Usage:
     ;; Start system with profile
     (def system (start! :prod))

     ;; Stop system
     (stop! system)

     ;; REPL development (see dev.clj)
     (go)    ; start
     (halt)  ; stop
     (reset) ; reload config and restart"
  (:require
    [graphden.system.config :as config]
    [graphden.system.core]
    [integrant.core :as ig]))


(defn read-config
  "Reads system config for given profile (:dev, :test, :prod).
   Returns a prepared Integrant configuration map."
  [profile]
  (config/read-config profile))


(defn start!
  "Starts the system with given profile. Returns running system map.

   Optionally accepts component-keys to start only subset of components:
   (start! :prod [:db/age :db/versioned])"
  ([profile]
   (ig/init (read-config profile)))
  ([profile component-keys]
   (ig/init (read-config profile) component-keys)))


(defn start-with-overrides!
  "Starts system with config overrides. Useful for tests.

   Example:
   (start-with-overrides! :test
     {:db/age {:jdbc-url \"jdbc:postgresql://localhost:5433/test\"}})"
  [profile overrides]
  (let [base-config (read-config profile)
        merged-config (reduce-kv
                        (fn [cfg k v]
                          (update cfg k merge v))
                        base-config
                        overrides)]
    (ig/init merged-config)))


(defn stop!
  "Stops the system gracefully."
  [system]
  (ig/halt! system))


(defn suspend!
  "Suspends the system (keeps state for resume)."
  [system]
  (ig/suspend! system))


(defn resume!
  "Resumes a suspended system with new config."
  [system profile]
  (ig/resume (read-config profile) system))

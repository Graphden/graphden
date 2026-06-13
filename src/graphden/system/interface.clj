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


(defn ^:dynamic read-config
  "Reads system config for given profile (:dev, :test, :prod).
   Returns a prepared Integrant configuration map.

   `^:dynamic` so the runtime-lifecycle tests can rebind this via
   `binding [read-config …]` without using `with-redefs` (which
   mutates a root binding and races with sibling tests under the
   parallel kaocha runner). The plugin's `isolation-vars` list
   already covers per-thread atom isolation; this Var goes through
   the standard `^:dynamic` thread-local path because the rebind is
   needed only inside the test body."
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

   - 2-arity: `(start-with-overrides! :test overrides)` — start ALL
     components.
   - 3-arity: `(start-with-overrides! :test component-keys overrides)`
     — start only `component-keys` (and their dependencies).

   Overrides merge into the integrant config per top-level key:
   `(update cfg k merge override-for-k)`. This is the supported
   replacement for the test pattern `(with-redefs [sys/read-config …]
   (sys/start! :dev component-keys))` — `with-redefs` mutates a global
   root binding which races with concurrent test invocations on the
   same JVM; explicit overrides scope the change to one start! call.

   Example:
     (start-with-overrides!
       :dev
       [:db/schema :db/postgres :db/versioned :exec/context]
       {:db/postgres {:jdbc-url \"jdbc:postgresql://…/test\"
                      :username \"…\" :password \"…\"}})"
  ([profile overrides]
   (let [base-config (read-config profile)
         merged-config (reduce-kv
                         (fn [cfg k v] (update cfg k merge v))
                         base-config
                         overrides)]
     (ig/init merged-config)))
  ([profile component-keys overrides]
   (let [base-config (read-config profile)
         merged-config (reduce-kv
                         (fn [cfg k v] (update cfg k merge v))
                         base-config
                         overrides)]
     (ig/init merged-config component-keys))))


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

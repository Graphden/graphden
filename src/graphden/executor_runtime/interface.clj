(ns graphden.executor-runtime.interface
  "Public API for the executor runtime.

   Provides lifecycle management for running graphden as a web server.

   ## Quick Start

   ```clojure
   (require '[graphden.executor-runtime.interface :as rt])

   ;; Start with default profile (:prod)
   (rt/start!)

   ;; Start with specific profile
   (rt/start! :dev)

   ;; Stop the system
   (rt/stop!)

   ;; Restart with profile
   (rt/restart! :dev)
   ```

   ## Profiles

   - :prod - Production configuration (default)
   - :dev  - Development configuration
   - :test - Test configuration

   See `graphden.executor-runtime.core` for configuration details."
  (:require
    [graphden.executor-runtime.core :as core]))


(defn start!
  "Starts the executor runtime system.

   Arguments:
   - profile: (optional) :dev, :test, or :prod (default: :prod)
   - overrides: (optional) integrant config overrides — merged per
     top-level key into the loaded profile. Use this in tests to
     inject DB credentials etc. without `with-redefs` on the
     `sys/read-config` var.

   Returns the running system map."
  ([]
   (core/start!))
  ([profile]
   (core/start! profile))
  ([profile overrides]
   (core/start! profile overrides)))


(defn stop!
  "Stops the running executor runtime system."
  []
  (core/stop!))


(defn restart!
  "Restarts the executor runtime system.

   Arguments:
   - profile: (optional) :dev, :test, or :prod (default: :prod)
   - overrides: (optional) see `start!`."
  ([]
   (core/restart!))
  ([profile]
   (core/restart! profile))
  ([profile overrides]
   (core/restart! profile overrides)))

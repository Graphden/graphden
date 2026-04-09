(ns dev
  "REPL development helpers.

   Usage:
     (go)    - start system with :dev profile
     (halt)  - stop system
     (reset) - stop, reload config, restart

   System state is available via:
     integrant.repl.state/system - the running system map
     integrant.repl.state/config - the prepared config"
  (:require
    [graphden.system.interface :as sys]
    [integrant.repl :refer [go halt reset]]
    [integrant.repl.state :refer [config system]]))


;; Configure integrant.repl to use our config loader
(integrant.repl/set-prep! #(sys/read-config :dev))


;; Convenience functions for accessing system components

(defn storage
  "Returns the versioned storage from running system."
  []
  (:db/versioned system))


(defn context
  "Returns the executor context from running system."
  []
  (:exec/context system))


(defn server
  "Returns the HTTP server from running system."
  []
  (:http/server system))


(comment
  ;; REPL workflow examples:

  ;; Start the system
  (go)

  ;; Stop the system
  (halt)

  ;; Restart with config reload
  (reset)

  ;; Access components
  (storage)
  (context)

  ;; Inspect system state
  system
  config

  :end)

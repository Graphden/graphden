(ns kaocha.plugin.shared-container
  "Kaocha plugin for managing the shared PostgreSQL test container.

   This plugin starts a single PostgreSQL container before all tests run
   and stops it after all tests complete. This replaces per-namespace
   container fixtures with a single shared container, dramatically
   reducing test startup time.

   Registered as :kaocha.plugin/shared-container in tests.edn"
  {:clj-kondo/config '{:linters {:unresolved-symbol {:level :off}}}}
  (:require
    [graphden.test-infra.shared-container :as sc]
    [kaocha.plugin :refer [defplugin]]))


(defplugin kaocha.plugin/shared-container
  "Manages shared PostgreSQL container lifecycle for tests."

  ;; Called before the entire test run starts
  (pre-run [config]
           ;; Start container eagerly before any tests run
           ;; This ensures container is ready when parallel namespaces start
           (sc/get-container)
           config)

  ;; Called after the entire test run completes
  (post-run [result]
            ;; Stop the container after all tests finish
            (sc/stop-container!)
            result))

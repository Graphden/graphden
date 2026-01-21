(ns graphden.http-kit-fns.interface
  "HTTP server base functions using http-kit.

   Provides:
   - http-server: Start server with handler function
   - http-stop: Stop a running server"
  (:require
    [graphden.http-kit-fns.core :as core]))


(def all-defs
  "All http-kit base function definitions."
  core/all-defs)


;; For backwards compatibility
(defn get-all-defs
  "Returns all http-kit base function definitions.
   Deprecated: use `all-defs` directly."
  []
  all-defs)

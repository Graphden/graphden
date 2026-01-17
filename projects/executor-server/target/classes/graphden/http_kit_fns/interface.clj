(ns graphden.http-kit-fns.interface
  "HTTP server base functions using http-kit.

   Provides base functions for running HTTP servers:
   - http-server: Start an HTTP server with a handler
   - http-stop: Stop a running server

   These are low-level primitives. For typical web applications,
   use the higher-level web-server component which combines these
   with reitit routing."
  (:require
    [graphden.http-kit-fns.core :as core]))


(defn get-all-defs
  "Returns all http-kit base function definitions.
   Each entry is {fn-name {:args {...} :return-type :type :impl fn}}."
  []
  (core/get-all-defs))

(ns graphden.reitit-fns.interface
  "Reitit routing base functions.

   Provides base functions for building HTTP routers:
   - reitit-router: Create a router from route definitions
   - reitit-match: Match a request against a router
   - reitit-routes: Get routes from a router (for debugging)

   Routes are defined as vectors: [[path handler-data] ...]"
  (:require
    [graphden.reitit-fns.core :as core]))


(defn get-all-defs
  "Returns all reitit base function definitions.
   Each entry is {fn-name {:args {...} :return-type :type :impl fn}}."
  []
  (core/get-all-defs))

(ns graphden.reitit-fns.interface
  "Reitit routing base functions.

   Provides:
   - router: Creates Ring handler from routes and handlers map"
  (:require
    [graphden.reitit-fns.core :as core]))


(def all-defs
  "All reitit base function definitions."
  core/all-defs)

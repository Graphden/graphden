(ns graphden.web.reitit.interface
  "Reitit routing base functions.

   Provides:
   - router: Creates Ring handler from routes and handlers map"
  (:require
    [graphden.web.reitit.core :as core]))


(def all-defs
  "All reitit base function definitions."
  core/all-defs)

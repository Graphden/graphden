(ns graphden.reitit-fns.interface
  "Reitit routing base functions.

   Provides:
   - reitit-matcher: Create a matcher function from routes"
  (:require
    [graphden.reitit-fns.core :as core]))


(def all-defs
  "All reitit base function definitions."
  core/all-defs)

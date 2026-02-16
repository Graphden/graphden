(ns graphden.web.html.interface
  "Public interface for HTML templating base functions.

   Re-exports from core for consistent public API."
  (:require
    [graphden.web.html.core :as core]))


(def all-defs
  "All HTML base function definitions."
  core/all-defs)

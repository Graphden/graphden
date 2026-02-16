(ns graphden.web.crud.interface
  "Public interface for CRUD base functions.

   Re-exports from core for consistent public API."
  (:require
    [graphden.web.crud.core :as core]))


(def all-defs
  "All CRUD base function definitions."
  core/all-defs)

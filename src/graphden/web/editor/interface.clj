(ns graphden.web.editor.interface
  "Public interface for graph editor fn-defs.

   Re-exports from core for consistent public API."
  (:require
    [graphden.web.editor.core :as core]))


(def fn-defs
  "Fn definitions for graph editor UI."
  core/fn-defs)


(def startup-fn-name
  "Name of the function to execute at startup."
  core/startup-fn-name)

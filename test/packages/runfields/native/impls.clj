(ns graphden.packages.runfields.native.impls
  (:require
    [graphden.executor.defbase :refer [defbase]]))


(defbase run-field-transform [payload]
  ((:callback payload) (:item payload)))


(def impls {:run-field-transform {:impl run-field-transform :taint-propagate? true}})

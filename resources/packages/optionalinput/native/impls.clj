(ns graphden.packages.optionalinput.native.impls
  (:require
    [graphden.executor.defbase :refer [defbase]]))


(defbase render-label [label]
  (if (nil? label) "explicit nil" (str "text: " label)))


(def impls {:render-nullable-label render-label})

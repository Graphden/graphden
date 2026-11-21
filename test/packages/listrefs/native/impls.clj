(ns graphden.packages.listrefs.native.impls
  (:require
    [graphden.executor.defbase :refer [defbase]]))


(defbase first-integer [items]
  (first items))


(def impls {:first-integer {:impl first-integer :taint-propagate? true}})

(ns graphden.packages.nesteddefaultfixture.native.impls
  (:require
    [graphden.executor.defbase :refer [defbase]]))


(defbase describe-label-pair [left right]
  (pr-str (mapv #(some-> % (select-keys [:label :hint])) [left right])))


(def impls {:describe-label-pair {:impl describe-label-pair :taint-propagate? true}})

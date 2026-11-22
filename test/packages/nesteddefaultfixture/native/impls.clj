(ns graphden.packages.nesteddefaultfixture.native.impls
  (:require
    [clojure.string :as str]
    [graphden.executor.defbase :refer [defbase]]))


(defbase describe-label-pair [left right]
  (str/join "|" (map #(if (nil? %) "absent" (or (:label %) "nil")) [left right])))


(def impls {:describe-label-pair {:impl describe-label-pair :taint-propagate? true}})

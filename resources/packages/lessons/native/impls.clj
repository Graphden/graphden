(ns graphden.packages.lessons.native.impls
  (:require
    [graphden.executor.defbase :refer [defbase]]))


(defbase profile-value [profile]
  (select-keys profile [:id :name :email]))


(def impls {:profile-value {:impl profile-value :taint-propagate? true}})

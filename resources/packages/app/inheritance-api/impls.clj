(ns graphden.packages.app.inheritance-api.impls
  (:require
    [graphden.crud.inheritance :as inheritance]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]))


(defbase _preview-inheritance-model
  [command _request]
  (cr/record-effect! :db)
  (inheritance/preview ctx command))


(defbase _apply-inheritance-operation
  [command _request]
  (cr/record-effect! :db)
  (inheritance/apply-operation! ctx command))


(def impls
  {:_preview-inheritance-model {:impl _preview-inheritance-model :taint-propagate? true}
   :_apply-inheritance-operation {:impl _apply-inheritance-operation :taint-propagate? true}})

(ns graphden.packages.app.inheritance-api.impls
  (:require
    [graphden.crud.inheritance :as inheritance]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]))


(defbase parse-inheritance-command
  [body]
  (inheritance/parse-command body))


(defbase preview-inheritance
  [command _request]
  (cr/record-effect! :db)
  (inheritance/preview ctx command))


(defbase apply-inheritance
  [command _request]
  (cr/record-effect! :db)
  (inheritance/apply! ctx command))


(def impls
  {:parse-inheritance-command {:impl parse-inheritance-command :taint-propagate? true}
   :preview-inheritance {:impl preview-inheritance :taint-propagate? true}
   :apply-inheritance {:impl apply-inheritance :taint-propagate? true}})

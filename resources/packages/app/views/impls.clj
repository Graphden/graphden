(ns graphden.packages.app.views.impls
  "Canonical cohort reads and guarded writes for graph-composed Explorer views."
  (:require
    [graphden.crud.entities.views :as view-read]
    [graphden.crud.views :as views]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]))


(defbase _project-view-cohort
  "One concealed metadata snapshot, pure graph selection and checked bounded annotation."
  [filters selector]
  (cr/record-effect! :db)
  (view-read/project-view-cohort ctx filters selector))


(defbase _explorer-view-facts
  []
  ;; Every fn-def extending `:explorer-view`, filters decoded.
  (cr/record-effect! :db)
  (view-read/explorer-view-facts ctx))


(defbase _save-explorer-view-operation
  [command _request]
  (cr/record-effect! :db)
  (views/save-operation! ctx command))


(defbase _save-explorer-view-safe-error
  "Canonical safe error envelope; no raw exception details reach graph response assembly."
  [exception]
  (views/safe-save-error exception))


(def impls
  {:_project-view-cohort {:impl _project-view-cohort :taint-propagate? true}
   :_explorer-view-facts _explorer-view-facts
   :_save-explorer-view-operation {:impl _save-explorer-view-operation :taint-propagate? true}
   :_save-explorer-view-safe-error {:impl _save-explorer-view-safe-error :taint-propagate? true}})

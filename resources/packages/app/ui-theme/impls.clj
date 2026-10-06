(ns graphden.packages.app.ui-theme.impls
  "The restricted server execution boundary for personal theme graphs."
  (:require
    [graphden.editor.theme :as theme]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.defbase :refer [defbase]]))


(defbase _ui-theme-evaluate
  [input]
  (runtime/record-effect! :db)
  (runtime/record-effect! :time)
  (theme/evaluate ctx input))


(def impls
  {:_ui-theme-evaluate {:impl _ui-theme-evaluate :taint-propagate? true}})

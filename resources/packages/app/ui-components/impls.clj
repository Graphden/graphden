(ns graphden.packages.app.ui-components.impls
  "Identity-only configuration. No selected component is executed here."
  (:require
    [graphden.editor.component-creation :as creation]
    [graphden.editor.components :as components]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.defbase :refer [defbase]]))


(defbase ui-components
  [menu-initial menu-update menu-view picker-view]
  {:menu-initial menu-initial :menu-update menu-update :menu-view menu-view
   :picker-view picker-view})


(defbase _ui-components-plan
  [input]
  (runtime/record-effect! :db)
  (components/export-current ctx input))


(defbase _ui-components-create-preview
  [input]
  (runtime/record-effect! :db)
  (creation/preview ctx input))


(defbase _ui-components-create-apply
  [input]
  (runtime/record-effect! :db)
  (creation/apply! ctx input))


(def impls
  {:ui-components ui-components
   :_ui-components-plan {:impl _ui-components-plan :taint-propagate? true}
   :_ui-components-create-preview {:impl _ui-components-create-preview :taint-propagate? true}
   :_ui-components-create-apply {:impl _ui-components-create-apply :taint-propagate? true}})

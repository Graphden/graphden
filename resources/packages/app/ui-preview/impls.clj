(ns graphden.packages.app.ui-preview.impls
  "The preview's atomic compiler boundary; menu content and behavior are graphs."
  (:require
    [graphden.executor.browser-preview :as preview]
    [graphden.executor.defbase :refer [defbase]]))


(defbase _ui-preview-export
  [initial update view]
  (preview/export-current ctx {:initial initial :update update :view view}))


(def impls
  ;; The result contains the caller's entry identities and the content they
  ;; select. Snapshot visibility checks do not declassify tainted arguments.
  {:_ui-preview-export {:impl _ui-preview-export :taint-propagate? true}
   ;; The HTTP graph parses UUID values instead of using identity bindings.
   ;; Both signatures share this same atomic export boundary.
   :_ui-preview-export-ids {:impl _ui-preview-export :taint-propagate? true}})

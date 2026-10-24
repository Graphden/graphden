(ns graphden.editor.root-legacy
  "The finite read-only bridge from pre-root component selections. Its entries
   are used only by the compatibility reader and explicit migration."
  (:require
    [graphden.packages.records.ids :as ids]))


(def ^:private legacy-role-inputs
  {:initial #{"inputs"}
   :update #{"state" "event" "inputs" "context"}
   :view #{"state" "inputs" "theme" "server"}})


(def components
  {:account-menu {:namespace "app.ui-account-menu" :prefix "account-menu"
                  :roles [:initial :update :view]
                  :inputs legacy-role-inputs
                  :wrapper {:initial :root-initial :update :root-update :view :_root-base-view}
                  :root-component :account-menu-component}
   :recents {:namespace "app.ui-recents" :prefix "recents"
             :roles [:initial :update :view]
             :inputs legacy-role-inputs
             :wrapper {:initial :root-initial :update :root-update :view :root-view}
             :root-component :recents-component}
   :fn-picker {:namespace "app.ui-fn-picker" :prefix "picker"
               :roles [:view] :inputs {:view #{"model"}}
               :wrapper {:view :root-view} :root-component :picker-component}
   :tutorial-catalog {:namespace "app.ui-tutorial-catalog" :prefix "catalog"
                      :roles [:view] :inputs {:view #{"inputs"}}
                      :wrapper {:view :root-view} :root-component :catalog-component}})


(defn default-role-id
  [component role]
  (let [{path :namespace :keys [prefix]} (get components component)]
    (when (and path prefix)
      (ids/fn-id path (str prefix "-" (name role))))))

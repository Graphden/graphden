(ns graphden.ui.component-contracts
  "Fixed shipped component identities shared by editor selection and browser
   source authorization. These declarations never come from tenant graphs."
  (:require
    [graphden.packages.records.ids :as ids]))


(def component-contracts
  "Each discriminator is a shipped view identity, with its exact public
   namespace and roles. Source access still requires a separate grant."
  {:explorer {:id (ids/fn-id "app.ui-explorer" :explorer-toolbar-view)
              :reference :app.ui-explorer/explorer-toolbar-view :roles #{:initial :update :view}}
   :account-menu {:id (ids/fn-id "app.ui-account-menu" :account-menu-view)
                  :reference :app.ui-account-menu/account-menu-view
                  :roles #{:initial :update :view}}
   :org-switcher {:id (ids/fn-id "app.ui-org-switcher" :org-switcher-view)
                  :reference :app.ui-org-switcher/org-switcher-view :roles #{:view}}
   :fn-picker {:id (ids/fn-id "app.ui-fn-picker" :picker-view)
               :reference :app.ui-fn-picker/picker-view :roles #{:view}}
   :inspector {:id (ids/fn-id "app.ui-inspector" :inspector-view)
               :reference :app.ui-inspector/inspector-view :roles #{:initial :update :view}}
   :recents {:id (ids/fn-id "app.ui-recents" :recents-view)
             :reference :app.ui-recents/recents-view
             :roles #{:initial :update :view}}
   :canvas {:id (ids/fn-id "app.ui-canvas" :canvas-view)
            :reference :app.ui-canvas/canvas-view :roles #{:view}}
   :canvas-values {:id (ids/fn-id "app.ui-canvas-values" :canvas-values-view)
                   :reference :app.ui-canvas-values/canvas-values-view :roles #{:view}}
   :canvas-inheritance {:id (ids/fn-id "app.ui-canvas-inheritance" :inheritance-view)
                        :reference :app.ui-canvas-inheritance/inheritance-view :roles #{:initial :view}}
   :canvas-metadata {:id (ids/fn-id "app.ui-canvas-metadata" :metadata-view)
                     :reference :app.ui-canvas-metadata/metadata-view :roles #{:view}}
   :canvas-type-structure {:id (ids/fn-id "app.ui-canvas-type-structure" :type-structure-view)
                           :reference :app.ui-canvas-type-structure/type-structure-view :roles #{:initial :view}}
   :canvas-bindings {:id (ids/fn-id "app.ui-canvas-bindings" :bindings-view)
                     :reference :app.ui-canvas-bindings/bindings-view :roles #{:view}}
   :tutorial-catalog {:id (ids/fn-id "app.ui-tutorial-catalog" :catalog-view)
                      :reference :app.ui-tutorial-catalog/catalog-view :roles #{:view}}
   :execution-status {:id (ids/fn-id "app.ui-execution" :status-view)
                      :reference :app.ui-execution/status-view :roles #{:view}}
   :execution-path {:id (ids/fn-id "app.ui-execution" :path-view)
                    :reference :app.ui-execution/path-view :roles #{:view}}
   :feedback {:id (ids/fn-id "app.ui-feedback" :feedback-view)
              :reference :app.ui-feedback/feedback-view :roles #{:view}}
   :keymap {:id (ids/fn-id "app.ui-keymap" :keymap-view)
            :reference :app.ui-keymap/keymap-view :roles #{:view}}
   :appearance {:id (ids/fn-id "app.ui-appearance" :appearance-view)
                :reference :app.ui-appearance/appearance-view :roles #{:view}}})

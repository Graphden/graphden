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
   :fn-picker {:id (ids/fn-id "app.ui-fn-picker" :picker-view)
               :reference :app.ui-fn-picker/picker-view :roles #{:view}}
   :inspector {:id (ids/fn-id "app.ui-inspector" :inspector-view)
               :reference :app.ui-inspector/inspector-view :roles #{:initial :update :view}}
   :recents {:id (ids/fn-id "app.ui-recents" :recents-view)
             :reference :app.ui-recents/recents-view
             :roles #{:initial :update :view}}
   :tutorial-catalog {:id (ids/fn-id "app.ui-tutorial-catalog" :catalog-view)
                      :reference :app.ui-tutorial-catalog/catalog-view :roles #{:view}}})

(ns graphden.packages.app.ui-components.impls
  "Identity-only configuration. No selected component is executed here."
  (:require
    [graphden.editor.component-creation :as creation]
    [graphden.editor.components :as components]
    [graphden.editor.config-activation :as activation]
    [graphden.editor.config-preparation :as preparation]
    [graphden.editor.config-receipts :as receipts]
    [graphden.editor.config-undo :as undo]
    [graphden.editor.root-migration :as root-migration]
    [graphden.editor.root-plan :as root-plan]
    [graphden.executor.browser-server :as server]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.defbase :refer [defbase]]))


(defbase ui-components
  ;; Structural identity constructor: component-config/entries validates the
  ;; exact owned fn-ref slot IDs without executing a graph. Composing this
  ;; record would change that authority schema for stored legacy selections.
  [menu-initial menu-update menu-view picker-view]
  {:menu-initial menu-initial :menu-update menu-update :menu-view menu-view
   :picker-view picker-view})


(defbase _editor-configuration-plan-facts
  [input]
  (runtime/record-effect! :db)
  (components/export-resolved ctx input))


(defbase _editor-root-plan-facts
  [request]
  (runtime/record-effect! :db)
  (root-plan/export-current ctx request))


(defbase _editor-server-call-facts
  [request input]
  (runtime/record-effect! :db)
  (server/invoke-current ctx request input))


(defn- configuration-outcome
  "Capture the native operation without exporting a Throwable, message, identity
   or arbitrary ex-data. Only finite public failure tags cross into the graph;
   response policy lives in configuration-result. Success receipts are unchanged."
  [operation ctx input]
  (try
    {:succeeded? true :value (operation ctx input)
     :failure-type nil :failure-reason nil}
    (catch Exception error
      (let [{:keys [type reason]} (ex-data error)]
        {:succeeded? false :value nil
         :failure-type (case type
                         :authz/forbidden "forbidden"
                         :browser-plan/unsupported "unsupported"
                         :quota/entity-limit "quota"
                         :validation-error/delete-receipts "delete-receipts"
                         :personal-home/unavailable "personal-home"
                         nil)
         :failure-reason (case reason
                           :invalid-request "invalid-request"
                           :stale-configuration "stale-configuration"
                           :personal-home-unavailable "personal-home-unavailable"
                           :component-missing "component-missing"
                           :policy-refresh-required "policy-refresh-required"
                           nil)}))))


(defbase _editor-root-migration-preview-outcome
  [input]
  (runtime/record-effect! :db)
  (configuration-outcome root-migration/preview! ctx input))


(defbase _editor-root-migration-apply-outcome
  [input]
  (runtime/record-effect! :db)
  (configuration-outcome root-migration/apply! ctx input))


(defbase _editor-configuration-prepare-outcome
  [input]
  (runtime/record-effect! :db)
  (configuration-outcome preparation/prepare! ctx input))


(defbase _editor-configuration-preview-outcome
  [input]
  (runtime/record-effect! :db)
  (configuration-outcome activation/preview! ctx input))


(defbase _editor-configuration-undo-outcome
  [input]
  (runtime/record-effect! :db)
  (configuration-outcome undo/undo! ctx input))


(defbase _editor-configuration-apply-outcome
  [input]
  (runtime/record-effect! :db)
  (configuration-outcome activation/apply-proposal! ctx input))


(defbase _editor-configuration-reconcile-outcome
  [input]
  (runtime/record-effect! :db)
  (configuration-outcome receipts/reconcile! ctx input))


(defbase _editor-configuration-reconcile-undo-outcome
  [input]
  (runtime/record-effect! :db)
  (configuration-outcome receipts/reconcile-undo! ctx input))


(defbase _editor-configuration-cleanup-preview-outcome
  [input]
  (runtime/record-effect! :db)
  (configuration-outcome receipts/cleanup-preview! ctx input))


(defbase _ui-components-create-preview-facts
  [input]
  (runtime/record-effect! :db)
  (creation/preview ctx input))


(defbase _ui-components-create-apply-facts
  [input]
  (runtime/record-effect! :db)
  (creation/apply! ctx input))


(def impls
  {:ui-components ui-components
   :_editor-configuration-plan-facts {:impl _editor-configuration-plan-facts :taint-propagate? true}
   ;; No caller values enter this source-authorized, server-selected export.
   :_editor-root-plan-facts _editor-root-plan-facts
   :_editor-root-migration-preview-outcome {:impl _editor-root-migration-preview-outcome :taint-propagate? true}
   :_editor-root-migration-apply-outcome {:impl _editor-root-migration-apply-outcome :taint-propagate? true}
   :_editor-server-call-facts {:impl _editor-server-call-facts :taint-propagate? true}
   :_editor-configuration-prepare-outcome {:impl _editor-configuration-prepare-outcome :taint-propagate? true}
   :_editor-configuration-preview-outcome {:impl _editor-configuration-preview-outcome :taint-propagate? true}
   :_editor-configuration-reconcile-outcome {:impl _editor-configuration-reconcile-outcome :taint-propagate? true}
   :_editor-configuration-reconcile-undo-outcome {:impl _editor-configuration-reconcile-undo-outcome :taint-propagate? true}
   :_editor-configuration-cleanup-preview-outcome {:impl _editor-configuration-cleanup-preview-outcome :taint-propagate? true}
   :_editor-configuration-undo-outcome {:impl _editor-configuration-undo-outcome :taint-propagate? true}
   :_editor-configuration-apply-outcome {:impl _editor-configuration-apply-outcome :taint-propagate? true}
   :_ui-components-create-preview-facts {:impl _ui-components-create-preview-facts :taint-propagate? true}
   :_ui-components-create-apply-facts {:impl _ui-components-create-apply-facts :taint-propagate? true}})

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
  [menu-initial menu-update menu-view picker-view]
  {:menu-initial menu-initial :menu-update menu-update :menu-view menu-view
   :picker-view picker-view})


(defbase _ui-components-plan
  [input]
  (runtime/record-effect! :db)
  (components/export-current ctx input))


(defbase _editor-configuration-plan
  [input]
  (runtime/record-effect! :db)
  (components/export-resolved ctx input))


(defbase _editor-root-plan
  [request]
  (runtime/record-effect! :db)
  (root-plan/export-current ctx request))


(defbase _editor-server-call
  [request input]
  (runtime/record-effect! :db)
  (server/invoke-current ctx request input))


(defn- configuration-result
  [operation ctx input]
  (try
    (assoc (operation ctx input) :ok true)
    (catch Exception error
      (cond-> (components/failure-response error)
        (or (= :invalid-request (:reason (ex-data error)))
            (= :validation-error/delete-receipts (:type (ex-data error)))) (assoc :http-status 400)
        (= :stale-configuration (:reason (ex-data error)))
        (assoc :http-status 409 :code "stale-configuration"
               :reason "The editor configuration changed. Refresh before selecting another configuration.")
        (and (contains? #{:personal-home/unavailable :browser-plan/unsupported} (:type (ex-data error)))
             (= :personal-home-unavailable (:reason (ex-data error))))
        (assoc :code "personal-home-unavailable"
               :reason "Your deployment has no available personal graph namespace.")))))


(defbase _editor-root-migration-preview
  [input]
  (runtime/record-effect! :db)
  (configuration-result root-migration/preview! ctx input))


(defbase _editor-root-migration-apply
  [input]
  (runtime/record-effect! :db)
  (configuration-result root-migration/apply! ctx input))


(defbase _editor-configuration-prepare
  [input]
  (runtime/record-effect! :db)
  (configuration-result preparation/prepare! ctx input))


(defbase _editor-configuration-preview
  [input]
  (runtime/record-effect! :db)
  (configuration-result activation/preview! ctx input))


(defbase _editor-configuration-undo
  [input]
  (runtime/record-effect! :db)
  (configuration-result undo/undo! ctx input))


(defbase _editor-configuration-apply
  [input]
  (runtime/record-effect! :db)
  (configuration-result activation/apply-proposal! ctx input))


(defbase _editor-configuration-reconcile
  [input]
  (runtime/record-effect! :db)
  (configuration-result receipts/reconcile! ctx input))


(defbase _editor-configuration-reconcile-undo
  [input]
  (runtime/record-effect! :db)
  (configuration-result receipts/reconcile-undo! ctx input))


(defbase _editor-configuration-cleanup-preview
  [input]
  (runtime/record-effect! :db)
  (configuration-result receipts/cleanup-preview! ctx input))


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
   :_editor-configuration-plan {:impl _editor-configuration-plan :taint-propagate? true}
   ;; No caller values enter this source-authorized, server-selected export.
   :_editor-root-plan _editor-root-plan
   :_editor-root-migration-preview {:impl _editor-root-migration-preview :taint-propagate? true}
   :_editor-root-migration-apply {:impl _editor-root-migration-apply :taint-propagate? true}
   :_editor-server-call {:impl _editor-server-call :taint-propagate? true}
   :_editor-configuration-prepare {:impl _editor-configuration-prepare :taint-propagate? true}
   :_editor-configuration-preview {:impl _editor-configuration-preview :taint-propagate? true}
   :_editor-configuration-reconcile {:impl _editor-configuration-reconcile :taint-propagate? true}
   :_editor-configuration-reconcile-undo {:impl _editor-configuration-reconcile-undo :taint-propagate? true}
   :_editor-configuration-cleanup-preview {:impl _editor-configuration-cleanup-preview :taint-propagate? true}
   :_editor-configuration-undo {:impl _editor-configuration-undo :taint-propagate? true}
   :_editor-configuration-apply {:impl _editor-configuration-apply :taint-propagate? true}
   :_ui-components-create-preview {:impl _ui-components-create-preview :taint-propagate? true}
   :_ui-components-create-apply {:impl _ui-components-create-apply :taint-propagate? true}})

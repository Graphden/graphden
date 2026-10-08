(ns graphden.editor.components
  "Personal editor components exported as bounded data, never tenant HTML or
   code. The server preference selects identities; source guards precede every
   literal read and cached visibility may only veto a fresh snapshot check."
  (:require
    [graphden.crud.request :as request]
    [graphden.editor.component-config :as config]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.context :as context]
    [graphden.executor.registry.core :as registry]
    [graphden.storage.postgres.graph-epoch :as graph-epoch]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router.epoch :as router-epoch]
    [graphden.tenancy.context :as tenancy]
    [graphden.versioning.storage.core :as versioned]))


(def ^:private component-keys
  {"account-menu" :account-menu "fn-picker" :fn-picker})


(defn- reject!
  [reason]
  (throw (ex-info "Personal UI graph is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn- field
  [document key]
  ;; Preferences are JSON documents; the codec can retain string or keyword
  ;; keys. An ambiguous document is never a privileged identity selector.
  (when (and (contains? document key) (contains? document (name key)))
    (reject! :invalid-selection))
  (get document key (get document (name key))))


(defn selected-id
  "Resolve this user's authoritative selection inside the request snapshot.
   Client mirrors cannot select another owner, organization or branch."
  [storage]
  (let [rows (sp/query-entities storage :ui-pref
                                {:owner-id (tenancy/current-user-id) :key "components"}
                                {:limit 2})
        selection (:value (first rows))
        id (when (map? selection) (request/parse-uuid-or-clear (field selection :fn-id)))
        branch-id (when (map? selection) (request/parse-uuid-or-clear (field selection :branch-id)))]
    (when-not (and (= 1 (count rows)) id branch-id
                   (= (tenancy/current-org) (field selection :org))
                   (= branch-id (versioned/current-branch-id storage)))
      (reject! :invalid-selection))
    id))


(defn assert-current-policy!
  "No cached plain classification can authorize export after an unpublished
   write. Sequence reads are deliberately uncached and may conservatively
   refuse an in-flight/rolled-back writer until the router heals its policy."
  [storage]
  (let [epoch (graph-epoch/current (graph-epoch/epoch-handle storage))]
    (when-not (and (integer? epoch) (<= epoch (router-epoch/validated-watermark)))
      (reject! :policy-refresh-required))))


(defn- export-in-snapshot
  [ctx component storage authorize!]
  (let [id (selected-id storage)
        row (sp/read-entity storage :fn id)]
    (when-not row (reject! :source-missing))
    ;; This selected root is distinct from the HTTP handler's own identity.
    (authorize! row true)
    (let [graph (source/collect-closure storage [id] authorize!)
          configuration (config/entries graph id)
          entries (get configuration component)
          captured {:graph graph :namespaces (source/collect-namespaces storage graph)}
          epoch (context/invalidation-epoch ctx)]
      ;; Authorization above precedes visibility/registry reads. A fresh check
      ;; must never reinterpret an erased secret annotation as public data.
      (assert-current-policy! storage)
      (let [policy (snapshot/capture-policy
                     @(or (:rich-types-atom ctx) (registry/active-rich-types-atom)))
            plan (snapshot/export-snapshot captured (:base-fns ctx) entries policy)]
        (assert-current-policy! storage)
        (when-not (= epoch (context/invalidation-epoch ctx))
          (reject! :policy-refresh-required))
        {:ok true :selection-id (str id) :plan plan
         :roots {:configuration-id (str id)
                 :menu-id (str (get-in configuration [:account-menu :view]))
                 :menu-update-id (str (get-in configuration [:account-menu :update]))
                 :picker-id (str (get-in configuration [:fn-picker :view]))}}))))


(defn export-current
  "Export only a selected personal component. The generic whole-graph preview
   remains tenancy-disabled. Refusals contain no diagnostics, source or values."
  [ctx input]
  (try
    (when-not (and (map? input) (= #{:component} (set (keys input))))
      (reject! :invalid-request))
    (let [component (get component-keys (:component input))]
      (when-not component (reject! :invalid-request))
      (source/with-snapshot (request/require-storage ctx)
                            #(export-in-snapshot ctx component %1 %2)))
    (catch Exception error
      {:ok false :reason "Personal UI graph is unavailable. Using built-in components."
       :http-status (if (= :authz/forbidden (:type (ex-data error))) 403 422)})))

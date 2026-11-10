(ns graphden.editor.components
  "Personal editor components exported as bounded data, never tenant HTML or
   code. The server preference selects identities; source guards precede every
   literal read and cached visibility may only veto a fresh snapshot check."
  (:require
    [graphden.crud.request :as request]
    [graphden.editor.component-config :as config]
    [graphden.editor.config-resolution :as resolution]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.context :as context]
    [graphden.storage.postgres.graph-epoch :as graph-epoch]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router.epoch :as router-epoch]
    [graphden.tenancy.context :as tenancy]
    [graphden.versioning.storage.core :as versioned]))


(def ^:private component-keys
  (into {} (map (juxt name identity)) (keys config/component-contracts)))


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


(defn selected-id-from-rows
  "Validate the one authoritative owner preference read by the caller. Reusing
   its rows keeps a captured comparison and its selected manifest coherent."
  [storage rows]
  (let [selection (:value (first rows))
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
  [ctx component storage authorize! policy epoch]
  (let [resolved (resolution/capture storage authorize! policy)
        id (:id resolved)
        row (sp/read-entity storage :fn id)]
    (when-not row (reject! :source-missing))
    ;; This selected root is distinct from the HTTP handler's own identity.
    (authorize! row true)
    (let [configuration (:configuration resolved)
          entries (get configuration component)]
      ;; The original policy was frozen before opening this transaction.
      ;; A fresh check may veto it, never relax erased secret annotations.
      (assert-current-policy! storage)
      (let [_ (when-not entries (reject! :component-missing))
            body (source/collect-closure storage (vals entries) authorize! (context/cached-graph ctx))
            body-snapshot {:graph body :namespaces (source/collect-namespaces storage body)}
            plan (snapshot/export-snapshot body-snapshot (:base-fns ctx) entries policy)
            preference-rows (sp/query-entities storage :ui-pref
                                               {:owner-id (tenancy/current-user-id) :key "components"} {:limit 2})]
        (when (> (count preference-rows) 1) (reject! :invalid-selection))
        (assert-current-policy! storage)
        (when-not (= epoch (context/invalidation-epoch ctx))
          (reject! :policy-refresh-required))
        (assoc {:ok true :selection-id (str id) :plan plan
                :roots {:configuration-id (str id)
                        :menu-id (str (get-in configuration [:account-menu :view]))
                        :menu-update-id (str (get-in configuration [:account-menu :update]))
                        :picker-id (str (get-in configuration [:fn-picker :view]))
                        :explorer-id (some-> (get-in configuration [:explorer :view]) str)
                        :explorer-initial-id (some-> (get-in configuration [:explorer :initial]) str)
                        :explorer-update-id (some-> (get-in configuration [:explorer :update]) str)
                        :inspector-id (some-> (get-in configuration [:inspector :view]) str)
                        :inspector-initial-id (some-> (get-in configuration [:inspector :initial]) str)
                        :inspector-update-id (some-> (get-in configuration [:inspector :update]) str)
                        :recents-id (some-> (get-in configuration [:recents :view]) str)
                        :recents-initial-id (some-> (get-in configuration [:recents :initial]) str)
                        :recents-update-id (some-> (get-in configuration [:recents :update]) str)
                        :canvas-id (some-> (get-in configuration [:canvas :view]) str)
                        :canvas-values-id (some-> (get-in configuration [:canvas-values :view]) str)
                        :canvas-inheritance-id (some-> (get-in configuration [:canvas-inheritance :view]) str)
                        :canvas-metadata-id (some-> (get-in configuration [:canvas-metadata :view]) str)
                        :canvas-type-structure-id (some-> (get-in configuration [:canvas-type-structure :view]) str)
                        :canvas-type-structure-initial-id (some-> (get-in configuration [:canvas-type-structure :initial]) str)
                        :canvas-bindings-id (some-> (get-in configuration [:canvas-bindings :view]) str)
                        :tutorial-catalog-id (some-> (get-in configuration [:tutorial-catalog :view]) str)
                        :execution-status-id (some-> (get-in configuration [:execution-status :view]) str)
                        :execution-path-id (some-> (get-in configuration [:execution-path :view]) str)
                        :feedback-id (some-> (get-in configuration [:feedback :view]) str)
                        :keymap-id (some-> (get-in configuration [:keymap :view]) str)
                        :appearance-id (some-> (get-in configuration [:appearance :view]) str)}}
               :configuration-revision (:revision resolved)
               :configuration-preference {:present (boolean (seq preference-rows))
                                          :value (:value (first preference-rows))}
               :configuration-branch-id (str (get-in resolved [:scope :branch-id]))
               :configuration-defaults-id (str resolution/defaults-id)
               :custom-components (resolution/owned-writable-components storage resolved))))))


(defn failure-facts
  "Finite native refusal facts. No message, identity, arbitrary reason or
   exception data crosses into response graphs. The exact server-stale proof
   must remain native: extra exception data must never acquire retry semantics."
  [error]
  (let [{:keys [type reason http-status] :as data} (ex-data error)]
    {:ok false
     :failure-type (case type
                     :authz/forbidden "forbidden"
                     :quota/entity-limit "quota"
                     :browser-plan/unsupported "unsupported"
                     :storage-error/candidate-limit "bounded-storage"
                     :storage-error/bounded-query-unsupported "bounded-storage"
                     nil)
     :failure-reason (case reason
                       :component-missing "component-missing"
                       :policy-refresh-required "policy-refresh-required"
                       nil)
     :failure-status (when (integer? http-status) http-status)
     :server-stale? (= {:type :browser-plan/unsupported :reason :server-epoch-changed} data)}))


(defn export-resolved
  "Export default or personal components through the ordinary authorized resolver."
  [ctx input]
  (try
    (when-not (and (map? input) (= #{:component} (set (keys input))))
      (reject! :invalid-request))
    (let [component (get component-keys (:component input))]
      (when-not component (reject! :invalid-request))
      (let [epoch (context/invalidation-epoch ctx)
            policy (snapshot/capture-context-policy ctx)]
        (source/with-hinted-snapshot (request/require-storage ctx) (context/cached-graph ctx)
                                     #(export-in-snapshot ctx component %1 %2 policy epoch))))
    (catch Exception error
      (failure-facts error))))

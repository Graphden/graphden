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


(defn- capture-configuration
  [storage authorize! policy]
  (let [resolved (resolution/capture storage authorize! policy)
        row (sp/read-entity storage :fn (:id resolved))]
    (when-not row (reject! :source-missing))
    ;; The selected root is distinct from the HTTP handler's own identity.
    (authorize! row true)
    (assert-current-policy! storage)
    resolved))


(defn- configuration-receipt
  [storage resolved]
  (let [id (:id resolved)
        configuration (:configuration resolved)
        preference-rows (sp/query-entities storage :ui-pref
                                           {:owner-id (tenancy/current-user-id) :key "components"} {:limit 2})]
    (when (> (count preference-rows) 1) (reject! :invalid-selection))
    {:ok true :selection-id (str id)
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
             :appearance-id (some-> (get-in configuration [:appearance :view]) str)}
     :configuration-revision (:revision resolved)
     :configuration-preference {:present (boolean (seq preference-rows))
                                :value (:value (first preference-rows))}
     :configuration-branch-id (str (get-in resolved [:scope :branch-id]))
     :configuration-defaults-id (str resolution/defaults-id)
     :custom-components (resolution/owned-writable-components storage resolved)}))


(defn- export-component-plan
  [ctx component storage authorize! policy resolved]
  (let [entries (get-in resolved [:configuration component])]
    (when-not entries (reject! :component-missing))
    (let [body (source/collect-closure storage (vals entries) authorize! (context/cached-graph ctx))
          body-snapshot {:graph body :namespaces (source/collect-namespaces storage body)}]
      (snapshot/export-snapshot body-snapshot (:base-fns ctx) entries policy))))


(defn- assert-export-current!
  [ctx storage epoch]
  (assert-current-policy! storage)
  (when-not (= epoch (context/invalidation-epoch ctx))
    (reject! :policy-refresh-required)))


(defn- export-in-snapshot
  [ctx component storage authorize! policy epoch]
  (let [resolved (capture-configuration storage authorize! policy)
        plan (export-component-plan ctx component storage authorize! policy resolved)
        receipt (configuration-receipt storage resolved)]
    (assert-export-current! ctx storage epoch)
    (assoc receipt :plan plan)))


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
        (source/with-snapshot (request/require-storage ctx)
                              #(export-in-snapshot ctx component %1 %2 policy epoch))))
    (catch Exception error
      (failure-facts error))))


(defn- snapshot-failure?
  "A failed SQL transaction or stale policy invalidates every item. Never
   continue reading a connection after a nested SQLException or interruption."
  [error]
  (or (= {:type :browser-plan/unsupported :reason :policy-refresh-required}
         (select-keys (ex-data error) [:type :reason]))
      (some #(or (instance? java.sql.SQLException %)
                 (instance? InterruptedException %)
                 (instance? java.util.concurrent.CancellationException %))
            (take-while some? (iterate ex-cause error)))))


(defn- export-component-result
  [ctx component storage authorize! policy resolved receipt]
  (try
    (assoc receipt :plan (export-component-plan ctx component storage authorize! policy resolved))
    (catch Exception error
      (if (and (not (snapshot-failure? error))
               (contains? #{:authz/forbidden :quota/entity-limit :browser-plan/unsupported
                            :storage-error/candidate-limit :storage-error/bounded-query-unsupported}
                          (:type (ex-data error))))
        (failure-facts error)
        (throw error)))))


(defn- export-batch-in-snapshot
  [ctx components storage authorize! policy epoch]
  (let [resolved (capture-configuration storage authorize! policy)
        receipt (configuration-receipt storage resolved)
        results (into {} (map (fn [component]
                                [component (export-component-result ctx (get component-keys component)
                                                                    storage authorize! policy resolved receipt)]))
                      components)]
    ;; Check after ALL source, metadata and plan work, before any result escapes.
    (assert-export-current! ctx storage epoch)
    {:ok true :results results}))


(defn export-resolved-batch
  "Export a finite set of components with one fresh authorized configuration
   snapshot. Each component retains its own closure/plan budgets and isolated
   checker registry. No plans or authorization grants survive this request."
  [ctx input]
  (try
    (let [components (:components input)]
      (when-not (and (map? input) (= #{:components} (set (keys input)))
                     (vector? components) (seq components)
                     (<= (count components) (count component-keys))
                     (= (count components) (count (set components)))
                     (every? #(contains? component-keys %) components))
        (reject! :invalid-request))
      (let [epoch (context/invalidation-epoch ctx)
            policy (snapshot/capture-context-policy ctx)]
        (source/with-snapshot (request/require-storage ctx)
                              #(export-batch-in-snapshot ctx components %1 %2 policy epoch))))
    (catch Exception error
      (failure-facts error))))

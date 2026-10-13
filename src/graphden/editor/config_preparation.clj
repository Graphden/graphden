(ns graphden.editor.config-preparation
  "Atomic compatibility migration into ordinary personal configuration. The
   trusted resolver stays read-only; this explicit editor operation writes
   only the owner's home using decorated CRUD and existing writer locks."
  (:require
    [graphden.crud.entities.invalidation :as invalidation]
    [graphden.crud.request :as request]
    [graphden.editor.component-bundle :as bundle]
    [graphden.editor.component-config :as config]
    [graphden.editor.components :as components]
    [graphden.editor.config-resolution :as resolution]
    [graphden.editor.config-templates :as templates]
    [graphden.editor.preference-write :as preferences]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.context :as context]
    [graphden.executor.registry.core :as registry]
    [graphden.graph.metadata :as metadata]
    [graphden.graph.personal-home :as personal-home]
    [graphden.packages.rebase :as rebase]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.system.branch-router.epoch :as epoch]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.tenancy.context :as tenancy]
    [graphden.types.check :as check]
    [graphden.versioning.storage.core :as versioned]
    [graphden.versioning.storage.resolution :as branch-resolution]))


(defn- refuse!
  [reason]
  (throw (ex-info "Personal editor configuration is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn- selection
  [storage authorize! policy]
  (let [rows (sp/query-entities storage :ui-pref
                                {:owner-id (tenancy/current-user-id) :key "components"} {:limit 2})]
    (when (> (count rows) 1) (refuse! :invalid-selection))
    (when (some? (:value (first rows)))
      (let [id (components/selected-id-from-rows storage rows)
            graph (source/collect-manifest storage id authorize!)
            captured {:graph graph :namespaces (source/collect-namespaces storage graph)}]
        (snapshot/validate-source! captured id policy)
        {:id id :record? (case (:format (config/configuration-info graph id))
                           :record true :map :map :list false)}))))


(defn prepared-records
  "Validate a fresh ordinary configuration cohort before any CREATE. Selected
   and prior references are authenticated identities, never client proof.
   A reserved nonce fixes identities for preview/APPLY, not authorization."
  ([storage home selected authorize! policy]
   (prepared-records storage home selected authorize! policy (random-uuid)))
  ([storage home selected authorize! policy nonce]
   (when-not (uuid? nonce) (refuse! :invalid-request))
   ;; The supported writer stack has VersionedStorage outermost. An unknown
   ;; decorator around it cannot prove raw identity absence: its resolved
   ;; reads may hide tombstones. Refuse before any source reads or writes.
   (when-not (versioned/versioned-storage? storage) (refuse! :unsupported-storage))
   (let [parser-root (str "editor-config." nonce)
         parsed (if (:component selected)
                  (templates/personalize parser-root (:component selected) (:roles selected) (:prior-id selected))
                  (templates/parse parser-root (:id selected) (:record? selected) (:prior-id selected)))
         records (mapv #(cond-> %
                          (= :fn (:kind %)) (assoc :namespace-id (:id home))
                          (and (= (:root-id parsed) (:id %)) (contains? selected :description))
                          (assoc :description (:description selected))) (:records parsed))
         external (source/collect-manifests storage (bundle/external-identities records) authorize!)
         captured {:graph external :namespaces (source/collect-namespaces storage external)}]
     ;; Shipped parse declarations are not authority. Check their ACTUAL
     ;; installed source and original classifications before any CREATE.
     (snapshot/validate-source! captured resolution/defaults-id policy)
     (config/configuration (merge-with into external (bundle/graph records)) (:root-id parsed))
     ;; Type-check the exact generated compositions before writing them, in a
     ;; private registry. Retained aliases resolve to the authenticated UUID,
     ;; including anonymous roots. This grants nothing and publishes no cache
     ;; certificate for uncommitted rows.
     (let [references (:reference-identities parsed)
           definitions (mapv #(rebase/rewrite-references
                                % (fn [reference]
                                    (or (get references reference)
                                        (when (and (keyword? reference) (nil? (namespace reference)))
                                          (some (fn [[reference-name id]]
                                                  (when (= (name reference-name) (name reference)) id)) references))
                                        reference))) (:definitions parsed))]
       (binding [registry/*rich-types-override* (atom @(registry/active-rich-types-atom))]
         (check/check-all-defs! (deps/topological-sort definitions))))
     (doseq [row (filter #(= :fn (:kind %)) records)]
       (writer/assert-creation-authorized! storage row (versioned/current-branch-id storage)))
     ;; Fresh preview identities must stay fresh even after an earlier APPLY
     ;; was undone. Resolved absence hides tombstones; identity rows do not.
     ;; Keep any remaining decorator around the unwrapped versioning layer.
     (let [base (versioned/unwrap storage)]
       (when (some (fn [[entity rows]]
                     (seq (sp/read-entities base entity (mapv :id rows))))
                   (group-by :kind records))
         (refuse! :identity-collision)))
     {:records records :id (:root-id parsed)
      :snapshot {:graph (merge-with into external (bundle/graph records))}})))


(defn create-records!
  "Write only the already validated fresh cohort through decorated CRUD."
  [storage records]
  (doseq [entity [:fn :slot :fn-slot :binding :binding-list-item]
          row (filter #(= entity (:kind %)) records)]
    (when-not (sp/create-entity storage entity (dissoc row :kind))
      (refuse! :write-refused)))
  (mapv :id (filter #(= :fn (:kind %)) records)))


(defn- prepare-in-transaction
  [storage create? policy]
  (let [home (tenancy/current-personal-namespace)
        authorize! (tenancy/browser-source-policy storage)
        namespace-row (when home (metadata/namespace-row storage (:namespace-path home) (tenancy/current-org)))
        current (when namespace-row
                  (metadata/function-row storage (:id namespace-row) "editor" (tenancy/current-org) authorize!))]
    (if current
      ;; An authored root wins. Never rewrite it from a legacy preference.
      {:id (:id current) :created false :functions [] :namespaces []}
      (let [selected (selection storage authorize! policy)]
        (if (or create? selected)
          (do
            (when-not home (refuse! :personal-home-unavailable))
            (let [created-home? (nil? namespace-row)
                  namespace-row (personal-home/ensure-current! storage)
                  prepared (prepared-records storage namespace-row selected authorize! policy)
                  ids (create-records! storage (:records prepared))]
              {:id (:id prepared) :created true :committed true :functions ids
               :namespaces (if created-home? [(:id namespace-row)] [])}))
          {:id resolution/defaults-id :created false :functions [] :namespaces []})))))


(defn publish!
  "Publish committed type/cache changes while preserving the create-only
   cleanup receipt even if derived-state publication fails."
  [ctx storage result]
  (let [ids (vec (distinct (concat (:functions result) (:changed-functions result))))
        namespaces (:changed-namespaces result)]
    (if (and (empty? ids) (empty? namespaces))
      result
      (try
        (when (seq ids) (context/invalidate-graph-cache! ctx ids))
        (when (seq ids) (recheck/record-imported-fn-types! ctx (versioned/current-branch-id storage) ids))
        (doseq [id ids]
          (invalidation/notify-after-write! ctx storage :fn :write
                                            {:id id :org-id (tenancy/current-org)}))
        ;; A newly materialized empty namespace changes no function/type
        ;; closure. Acknowledge its committed request bump without pretending
        ;; that the function-only NOTIFY adapter publishes namespace events.
        (epoch/note-graph-epoch-validated! storage)
        result
        (catch Exception _
          ;; Committed records and their cleanup receipt survive failure of
          ;; derived-state publication; never invite an accidental re-CREATE.
          (assoc result :publication-warnings
                 [{:reason "Your editor configuration was created. Refresh the editor to reload its derived state."}]))))))


(defn prepare!
  "Migrate a legacy selection by reference, or explicitly create the ordinary
   inherited editor root. Preference and canonical-root absence are checked
   under SAME graph-writer/current-owner preference locks. No preference is
   deleted or marked migrated. Receipts contain newly created IDs only."
  [ctx input]
  (when-not (and (map? input) (or (empty? input)
                                  (and (= #{:create} (set (keys input))) (boolean? (:create input)))
                                  (= {:home true} input)))
    (refuse! :invalid-request))
  (let [storage (request/require-storage ctx)
        context-epoch (context/invalidation-epoch ctx)
        policy (snapshot/capture-policy @(or (:rich-types-atom ctx) (registry/active-rich-types-atom)))]
    (when-not (tx/datasource storage) (refuse! :snapshot-storage-unsupported))
    (tx/assert-owns-commit! storage)
    (let [result (writer/with-write [storage :graph]
                                    (preferences/call-with-lock
                                      storage "components"
                                      (fn [storage]
                                        (branch-resolution/call-with-fresh-memos
                                          #(do
                                             (components/assert-current-policy! storage)
                                             (let [result (if (:home input)
                                                            (let [home (personal-home/ensure-current! storage)]
                                                              (cond-> {:setup true :home (select-keys home [:id :name])}
                                                                (::personal-home/created? (meta home))
                                                                (assoc :changed-namespaces [(:id home)])))
                                                            (prepare-in-transaction storage (:create input) policy))]
                                               (when-not (= context-epoch (context/invalidation-epoch ctx))
                                                 (refuse! :policy-refresh-required))
                                               result))))))]
      (publish! ctx storage result))))

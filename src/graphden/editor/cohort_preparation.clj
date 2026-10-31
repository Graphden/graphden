(ns graphden.editor.cohort-preparation
  "One validation boundary for fresh personal graph cohorts. Parsing a shipped
   template never grants source or write authority over installed definitions."
  (:require
    [graphden.editor.component-bundle :as bundle]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.source-contract :as contract]
    [graphden.packages.rebase :as rebase]
    [graphden.storage.expected-source :as expected-source]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.remote.core :as remote]
    [graphden.types.check :as check]
    [graphden.types.check.provenance :as provenance]
    [graphden.versioning.storage.core :as versioned]))


(defn- refuse!
  [reason]
  (throw (ex-info "Personal graph cohort is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn- merge-source
  [left right]
  (merge-with
    (fn [a b]
      (:rows
        (reduce (fn [{:keys [rows by-id] :as state} row]
                  (if (contains? by-id (:id row))
                    (do (when-not (= (get by-id (:id row)) row)
                          (refuse! :staged-source-mismatch))
                        state)
                    {:rows (conj rows row) :by-id (assoc by-id (:id row) row)}))
                {:rows (vec a) :by-id (into {} (map (juxt :id identity)) a)} b)))
    left right))


(defn- staged-closure
  [snapshot roots]
  (when (seq roots)
    (let [graph (:graph snapshot)
          storage (remote/from-bundle {:fn (:fns graph) :slot (:slots graph)
                                       :fn-slot (:fn-slots graph) :binding (:bindings graph)
                                       :binding-list-item (:list-items graph)})]
      (source/collect-client-closure storage roots (constantly nil)))))


(defn prepare
  "Validate source, types, creation ACL and raw identity absence before any
   CREATE. `validate-result!` checks the caller's root-specific shape."
  [storage home parsed {:keys [authorize! policy source-root source-roots validate-result! root-metadata
                               collect-external! normalize-records staged-source derive-policy?]
                        :or {collect-external! source/collect-manifests}}]
  ;; Unknown decorators may hide tombstones on resolved reads. The supported
  ;; writer stack has VersionedStorage outermost and preserves raw checks.
  (when-not (versioned/versioned-storage? storage) (refuse! :unsupported-storage))
  (let [initial-records (mapv #(cond-> %
                                 (= :fn (:kind %)) (assoc :namespace-id (:id home))
                                 (and (= (:root-id parsed) (:id %))
                                      (contains? root-metadata :description))
                                 (assoc :description (:description root-metadata))) (:records parsed))
        external-ids (bundle/external-identities initial-records)
        staged-ids (set (map :id (get-in staged-source [:graph :fns])))
        staged-roots (filterv staged-ids external-ids)
        external (collect-external! storage (remove staged-ids external-ids) authorize!)
        external (if (seq staged-roots)
                   (merge-source external (staged-closure staged-source staged-roots))
                   external)
        captured {:graph external :namespaces (source/collect-namespaces storage external)}]
    (doseq [id (or source-roots [source-root])]
      (snapshot/validate-source! captured id policy))
    (let [records (if normalize-records (normalize-records initial-records external) initial-records)
          expected (or (expected-source/canonical-graph storage (bundle/graph records))
                       (refuse! :expected-source-unavailable))]
      (validate-result! (merge-with into external expected) (:root-id parsed))
      ;; Retained aliases resolve only to the already source-authorized UUIDs.
      ;; The private registry proves types without publishing uncommitted rows.
      (let [references (:reference-identities parsed)
            definitions (mapv #(rebase/rewrite-references
                                 % (fn [reference]
                                     (or (get references reference)
                                         (when (and (keyword? reference) (nil? (namespace reference)))
                                           (some (fn [[reference-name id]]
                                                   (when (= (name reference-name) (name reference)) id)) references))
                                         reference))) (:definitions parsed))
            rich (atom (:rich policy))]
        ;; Type inference uses the same frozen type view as source validation.
        (binding [registry/*rich-types-override* rich]
          (check/check-all-defs! (deps/topological-sort definitions)))
        (when derive-policy?
          (let [fingerprints (contract/fingerprints expected)]
            (swap! rich
                   (fn [rich]
                     (reduce (fn [rich definition]
                               (let [id (registry/fn-def-registry-id (:name definition) definition)
                                     signature (get-in rich [:by-id id])
                                     fingerprint (get fingerprints id)]
                                 (if (and signature fingerprint)
                                   (assoc-in rich [:by-id id]
                                             (provenance/stamp-storage-input
                                               signature (check/checker-view definition) fingerprint))
                                   rich)))
                             rich definitions)))))
        (let [derived-policy (when derive-policy?
                               (let [new-ids (set (map :id (filter #(= :fn (:kind %)) records)))
                                     derived (snapshot/capture-policy @rich)]
                                 (assoc policy :rich @rich
                                        :classes (merge (:classes policy)
                                                        (select-keys (:classes derived) new-ids)))))]
          (doseq [row (filter #(= :fn (:kind %)) records)]
            (writer/assert-creation-authorized! storage row (versioned/current-branch-id storage)))
          ;; Resolved absence hides tombstones. A replayed nonce must fail here.
          (let [base (versioned/unwrap storage)]
            (when (some (fn [[entity rows]]
                          (seq (sp/read-entities base entity (mapv :id rows))))
                        (group-by :kind records))
              (refuse! :identity-collision)))
          {:records records :id (:root-id parsed)
           :derived-policy derived-policy
           :snapshot {:graph (merge-with into external expected)}})))))

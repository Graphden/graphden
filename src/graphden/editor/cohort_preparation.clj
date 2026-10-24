(ns graphden.editor.cohort-preparation
  "One validation boundary for fresh personal graph cohorts. Parsing a shipped
   template never grants source or write authority over installed definitions."
  (:require
    [graphden.editor.component-bundle :as bundle]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.rebase :as rebase]
    [graphden.storage.expected-source :as expected-source]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.check :as check]
    [graphden.versioning.storage.core :as versioned]))


(defn- refuse!
  [reason]
  (throw (ex-info "Personal graph cohort is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn prepare
  "Validate source, types, creation ACL and raw identity absence before any
   CREATE. `validate-result!` checks the caller's root-specific shape."
  [storage home parsed {:keys [authorize! policy source-root source-roots validate-result! root-metadata
                               collect-external! normalize-records]
                        :or {collect-external! source/collect-manifests}}]
  ;; Unknown decorators may hide tombstones on resolved reads. The supported
  ;; writer stack has VersionedStorage outermost and preserves raw checks.
  (when-not (versioned/versioned-storage? storage) (refuse! :unsupported-storage))
  (let [initial-records (mapv #(cond-> %
                                 (= :fn (:kind %)) (assoc :namespace-id (:id home))
                                 (and (= (:root-id parsed) (:id %))
                                      (contains? root-metadata :description))
                                 (assoc :description (:description root-metadata))) (:records parsed))
        external (collect-external! storage (bundle/external-identities initial-records) authorize!)
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
                                         reference))) (:definitions parsed))]
        ;; Type inference uses the same frozen type view as source validation.
        (binding [registry/*rich-types-override* (atom (:rich policy))]
          (check/check-all-defs! (deps/topological-sort definitions))))
      (doseq [row (filter #(= :fn (:kind %)) records)]
        (writer/assert-creation-authorized! storage row (versioned/current-branch-id storage)))
      ;; Resolved absence hides tombstones. A replayed nonce must fail here.
      (let [base (versioned/unwrap storage)]
        (when (some (fn [[entity rows]]
                      (seq (sp/read-entities base entity (mapv :id rows))))
                    (group-by :kind records))
          (refuse! :identity-collision)))
      {:records records :id (:root-id parsed)
       :snapshot {:graph (merge-with into external expected)}})))

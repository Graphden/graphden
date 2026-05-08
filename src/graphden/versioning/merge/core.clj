(ns graphden.versioning.merge.core
  "Merge protection for bindings carrying sensitive values.

   When merging branches, bindings marked `merge-protected` should not
   transfer from source to target. The trait is associated via the
   `binding-trait` entity (see `graphden.schema.traits.schema`).

   Use cases:
   - Production credentials should stay on production branch.
   - Environment-specific secrets shouldn't leak across branches."
  (:require
    [graphden.schema.traits.schema :as vts]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]))


(defn- get-merge-protected-binding-ids
  [base-storage]
  (let [trait-rows (sp/query-entities base-storage :binding-trait
                                      {:trait-id vts/merge-protected-trait-uuid})]
    (into #{} (map :binding-id) trait-rows)))


(defn- transferred-source-versions
  "Returns the source-branch versions whose `binding-id` doesn't appear
   on the target — these are the rows that would become visible on
   target after merge."
  [base-storage source-branch-id target-branch-id]
  (let [source-versions (sp/query-entities base-storage :binding-version
                                           {:branch-id source-branch-id})
        target-versions (sp/query-entities base-storage :binding-version
                                           {:branch-id target-branch-id})
        target-binding-ids (into #{} (map :binding-id) target-versions)]
    (vec (remove #(contains? target-binding-ids (:binding-id %)) source-versions))))


(defn detect-protected-transfers
  "Returns `{:protected-transfers [{:binding-id … :version …}] :blocked?
   bool}`. Blocked? true when any protected binding would transfer."
  [versioned-storage source-branch-id]
  (let [base-storage (vs/unwrap versioned-storage)
        target-branch-id (vs/current-branch-id versioned-storage)
        protected-ids (get-merge-protected-binding-ids base-storage)
        transfers (when (seq protected-ids)
                    (let [transferred (transferred-source-versions
                                        base-storage source-branch-id target-branch-id)]
                      (vec (for [v transferred
                                 :when (contains? protected-ids (:binding-id v))]
                             {:binding-id (:binding-id v)
                              :entity-type :binding
                              :version v}))))]
    {:protected-transfers (or transfers [])
     :blocked? (boolean (seq transfers))}))


(defn validate-merge!
  [versioned-storage source-branch-id]
  (let [{:keys [protected-transfers blocked?]}
        (detect-protected-transfers versioned-storage source-branch-id)]
    (when blocked?
      (throw (ex-info "Merge blocked: protected bindings would be transferred"
                      {:type :merge-protection-violation
                       :protected-transfers protected-transfers
                       :source-branch-id source-branch-id
                       :target-branch-id (vs/current-branch-id versioned-storage)})))))


(defn safe-merge-branch!
  ([versioned-storage source-branch-id]
   (safe-merge-branch! versioned-storage source-branch-id {}))
  ([versioned-storage source-branch-id {:keys [skip-protection-check] :as opts}]
   (when-not skip-protection-check
     (validate-merge! versioned-storage source-branch-id))
   (vs/merge-branch! versioned-storage source-branch-id
                     (dissoc opts :skip-protection-check))))


(defn has-merge-protected-trait?
  "True if `binding-id` carries the merge-protected trait."
  [storage binding-id]
  (let [base-storage (if (vs/versioned-storage? storage)
                       (vs/unwrap storage)
                       storage)]
    (boolean (seq (sp/query-entities base-storage :binding-trait
                                     {:binding-id binding-id
                                      :trait-id vts/merge-protected-trait-uuid})))))


(defn add-merge-protection!
  "Adds the merge-protected trait to a binding. Idempotent."
  [storage binding-id]
  (let [base-storage (if (vs/versioned-storage? storage)
                       (vs/unwrap storage)
                       storage)]
    (vts/seed-traits! base-storage)
    (when-not (has-merge-protected-trait? base-storage binding-id)
      (sp/create-entity base-storage :binding-trait
                        {:binding-id binding-id
                         :trait-id vts/merge-protected-trait-uuid}))))


(defn remove-merge-protection!
  [storage binding-id]
  (let [base-storage (if (vs/versioned-storage? storage)
                       (vs/unwrap storage)
                       storage)
        rows (sp/query-entities base-storage :binding-trait
                                {:binding-id binding-id
                                 :trait-id vts/merge-protected-trait-uuid})]
    (when (seq rows)
      (sp/delete-entities base-storage :binding-trait (mapv :id rows)))
    (boolean (seq rows))))

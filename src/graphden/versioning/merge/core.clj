(ns graphden.versioning.merge.core
  "Merge protection policies.

   Two independent gates, both surfaced as `:merge-protection-violation`:

   1. Trait-based — bindings marked `merge-protected` should not
      transfer from source to target. The trait is associated via the
      `binding-trait` entity (see `graphden.schema.traits.schema`).
      Use cases: production credentials stay on the production branch;
      environment-specific secrets don't leak across branches.

   2. Branch policy (error-tolerance Phase 5) — a branch whose row has
      `:forbid-invalid?` truthy refuses merges INTO it while recorded
      type diagnostics (`graphden.types.diagnostics`) exist on either
      the source or the target branch. Pragmatic v1: the diagnostics
      store is DERIVED state (empty after a JVM restart until checks
      re-record), so the gate judges what is RECORDED — absence means
      allow. See docs/ROADMAP.md § Error Tolerance."
  (:require
    [clojure.string :as str]
    [graphden.schema.traits.schema :as vts]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.diagnostics :as diag]
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
  (let [;; LATEST-per-binding minus tombstones (audit-4): the raw
        ;; full-history scan counted a source-side DELETED binding
        ;; (tombstone version) as a live transfer — falsely blocking
        ;; the merge of a branch that would only propagate the
        ;; deletion. Same fix shape as the reparent gate.
        source-versions (into []
                              (remove :deleted-at)
                              (sp/query-latest-per-group
                                base-storage :binding-version
                                {:branch-id source-branch-id}
                                [:binding-id :branch-id]))
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


(defn validate-branch-policy!
  "Error-tolerance Phase 5 gate. When the TARGET branch (the wrapper's
   current branch) has `:forbid-invalid?` truthy, throw
   `:merge-protection-violation` if either the source or the target
   branch currently has recorded type diagnostics — naming the broken
   fns. Judged on what is RECORDED in the derived per-branch store
   (`graphden.types.diagnostics`): absence of entries means allow (the
   honest contract — see the ns docstring). No-op when the flag is
   unset. Wired both here (for `validate-merge!` / `safe-merge-branch!`)
   and in the live `:merge-branch!` base-fn (`app/branches/impls.clj`),
   which calls it after switching to the target."
  [versioned-storage source-branch-id]
  (let [base-storage (vs/unwrap versioned-storage)
        target-branch-id (vs/current-branch-id versioned-storage)
        target-row (when target-branch-id
                     (sp/read-entity base-storage :branch target-branch-id))]
    (when (:forbid-invalid? target-row)
      (let [errors-by-fn (merge (diag/branch-errors source-branch-id)
                                (diag/branch-errors target-branch-id))]
        (when (seq errors-by-fn)
          (let [fn-ids (vec (keys errors-by-fn))
                ;; read-entities returns {id → row} already.
                rows-by-id (sp/read-entities base-storage :fn fn-ids)
                fn-names (mapv #(or (:name (get rows-by-id %)) (str %)) fn-ids)]
            (throw (ex-info (str "Merge blocked: target branch forbids invalid fns"
                                 " — unresolved type errors on: "
                                 (str/join ", " (sort fn-names)))
                            {:type :merge-protection-violation
                             :reason :forbid-invalid
                             :invalid-fn-names fn-names
                             :invalid-fn-ids fn-ids
                             :source-branch-id source-branch-id
                             :target-branch-id target-branch-id}))))))))


(defn validate-merge!
  [versioned-storage source-branch-id]
  (validate-branch-policy! versioned-storage source-branch-id)
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

(ns graphden.versioning.merge.core
  "Merge protection policies.

   NOTE (2026-08): only gate 2 (branch policy) is LIVE. Gate 1
   (trait-based `merge-protected`) is DORMANT — nothing production
   SETS the trait (`add-merge-protection!` is called from tests only),
   nothing on the live merge path CALLS `validate-merge!`/
   `safe-merge-branch!` (the `:merge/*` base-fn runs
   `validate-branch-policy!` then `vs/merge-branch!` directly), and its
   stated use case (keep secrets on their branch) is superseded by
   `:branch-local?` seeding `:secret-leaf` (CLAUDE.md § branch-local),
   which the RESOLVER enforces on every read. The trait functions below
   are kept for reference / a future re-wire but are a dead-code
   removal candidate; VERSIONING.md no longer claims the endpoint 409s
   on a protected-binding transfer.

   1. Trait-based (DORMANT) — bindings marked `merge-protected` should
      not transfer from source to target. The trait is associated via
      the `binding-trait` entity (see `graphden.schema.traits.schema`).
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
    [graphden.storage.protocol.traits-seed :as traits-seed]
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
          ;; Judge ONLY the fns the identity read returns. `base-storage`
          ;; is deliberately the UNWRAPPED handle — identity rows are
          ;; cross-branch, so a fn that exists only on the SOURCE branch
          ;; still resolves — but on a multi-tenant pod the unwrap lands
          ;; on OrgScopedStorage (it sits BENEATH versioning, ADR §3.0),
          ;; so the read is org-scoped: a foreign org's fn-ids in the
          ;; shared branch's diagnostics bucket come back absent. Those
          ;; must neither block this requester's merge nor surface (even
          ;; as bare UUIDs) in the violation message — the store itself
          ;; has no org dimension to consult.
          (let [all-ids (vec (keys errors-by-fn))
                ;; read-entities returns {id → row} already.
                rows-by-id (sp/read-entities base-storage :fn all-ids)
                fn-ids (filterv #(contains? rows-by-id %) all-ids)
                fn-names (mapv #(or (:name (get rows-by-id %)) (str %)) fn-ids)]
            (when (seq fn-ids)
              (throw (ex-info (str "Merge blocked: target branch forbids invalid fns"
                                   " — unresolved type errors on: "
                                   (str/join ", " (sort fn-names)))
                              {:type :merge-protection-violation
                               :reason :forbid-invalid
                               :invalid-fn-names fn-names
                               :invalid-fn-ids fn-ids
                               :source-branch-id source-branch-id
                               :target-branch-id target-branch-id})))))))))


(def ^:private branch-content-version-entities
  [:fn-version :fn-slot-version :binding-version
   :binding-list-item-version :resource-override-version])


(defn branch-content-stamp
  "A cheap content fingerprint of a branch's OWN version rows: the count
   plus the max `:created-at` across the versioned-entity tables, filtered
   by `branch-id`. Any edit or tombstone on the branch appends a version
   row (newer `:created-at`, higher count), so the stamp advances — which
   is how an approval recorded against an older stamp is detected as STALE
   at merge time. `base-storage` is the UNWRAPPED handle (still org-scoped
   beneath versioning on a multi-tenant pod)."
  [base-storage branch-id]
  (let [rows (mapcat (fn [ve] (sp/query-entities base-storage ve {:branch-id branch-id}))
                     branch-content-version-entities)
        max-ts (->> rows (keep :created-at) (map str) sort last)]
    (str (count rows) "|" (or max-ts "0"))))


(defn self-approval-allowed?
  "Whether a proposal author's OWN approval counts toward the target's
   `:required-approvals`. Default (nil `:allow-self-approval?`) is TRUE —
   a solo author / small team isn't locked out; a team wanting strict
   review sets the flag false. Single source of truth for the default so
   the gate and the status projection agree."
  [target-row]
  (if (some? (:allow-self-approval? target-row))
    (boolean (:allow-self-approval? target-row))
    true))


(defn count-valid-approvals*
  "Pure core of `count-valid-approvals`: given the already-fetched
   `current-stamp` and `approvals` rows, keep only those whose
   `:content-stamp` still matches (drops stale ones after a post-approval
   edit), drop the author's own unless `allow-self?`, count distinct
   approver ids. Split out so a caller that ALSO needs the stamp + the
   approval rows (the status projection) computes them once instead of
   re-running the 5-table stamp query + the approvals query twice."
  [current-stamp approvals author-id allow-self?]
  (->> approvals
       (filter #(= current-stamp (:content-stamp %)))
       (remove #(and (not allow-self?) author-id (= author-id (:approver-id %))))
       (map :approver-id)
       distinct
       count))


(defn count-valid-approvals
  "How many DISTINCT, non-stale approvals the proposal `source-branch-id`
   currently has toward a merge into the target. WHO may approve was
   enforced when each `:branch-approval` row was written (the approve
   endpoint), so counting here stays pure/open-core. Fetches the current
   stamp + approvals, then delegates to `count-valid-approvals*`."
  [base-storage source-branch-id author-id allow-self?]
  (count-valid-approvals*
    (branch-content-stamp base-storage source-branch-id)
    (sp/query-entities base-storage :branch-approval
                       {:source-branch-id source-branch-id})
    author-id allow-self?))


(defn validate-approval-policy!
  "Review-policy gate. When the TARGET branch (the wrapper's current
   branch) sets `:required-approvals` > 0, refuse the merge unless the
   proposal `source-branch-id` has at least that many valid approvals
   (`count-valid-approvals`) — throwing `:branch/approval-required` (409)
   naming the shortfall. No-op when the target requires no approvals.
   Open-core: judged purely on recorded `:branch-approval` rows. Called
   on the live merge path right after `validate-branch-policy!`."
  [versioned-storage source-branch-id]
  (let [base-storage (vs/unwrap versioned-storage)
        target-branch-id (vs/current-branch-id versioned-storage)
        target-row (when target-branch-id
                     (sp/read-entity base-storage :branch target-branch-id))
        required (or (:required-approvals target-row) 0)]
    (when (pos? required)
      (let [source-row (sp/read-entity base-storage :branch source-branch-id)
            author-id (:owner-id source-row)
            allow-self? (self-approval-allowed? target-row)
            have (count-valid-approvals base-storage source-branch-id author-id allow-self?)]
        (when (< have required)
          (throw (ex-info (str "Merge blocked: this branch requires " required
                               " approval(s) to merge, but has " have
                               ". Get the change reviewed and approved, then merge.")
                          {:type :branch/approval-required
                           :reason :insufficient-approvals
                           :required required
                           :have have
                           :source-branch-id source-branch-id
                           :target-branch-id target-branch-id})))))))


(defn validate-merge!
  [versioned-storage source-branch-id]
  (validate-branch-policy! versioned-storage source-branch-id)
  ;; Run the review-approval gate here too, so `safe-merge-branch!` (and any
  ;; future route wired to it) can never skip required-approvals — the live
  ;; merge base-fn calls it directly; this closes the latent bypass.
  (validate-approval-policy! versioned-storage source-branch-id)
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
    (traits-seed/seed-traits! base-storage)
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

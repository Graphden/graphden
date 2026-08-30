(ns graphden.versioning.storage.merge
  "Branch merge operations with conflict detection.

   Merge mechanism: inserting a branch-merge record makes source branch versions
   visible on the target branch via the resolution algorithm. No version records
   are copied.

   Conflict detection: an entity is conflicted when it has been modified on both
   the source and target branches after the fork point (branch creation time or
   last merge between the two).

   Detects conflicts in any versioned entity — see
   `graphden.versioning.storage.resolution/entity-config` for the
   authoritative list (fn / fn-slot / binding / binding-list-item /
   resource-override)."
  (:require
    [clojure.set :as set]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.branch-local :as bl]
    [graphden.versioning.storage.resolution :as res]
    [graphden.versioning.storage.uniqueness :as uniq]
    [next.jdbc :as jdbc]
    [next.jdbc.transaction :as jdbc-tx])
  (:import
    (java.time
      Instant)))


(defn- now
  []
  (Instant/now))


(defn- fork-point
  "The point after which own-branch changes on source and target count as
   divergent for conflict detection. The later of:
   - the branches' DIVERGENCE from their lowest common ancestor, and
   - the latest prior merge timestamp between the two (if any).

   Divergence comes from the `:base-branch-id` chains (`collect-branch-
   chain`): each side's divergence branch is the deepest chain element the
   OTHER side doesn't share — the branch that forked off the common line —
   and its `created-at` is when that side diverged.

   - feature→main (feature.base = main): only feature diverged, so the fork
     is feature's created-at; main is an ancestor and never diverged.
     (Forking at main's root created-at would count every already-inherited
     main change as \"modified after fork\" → spurious conflicts.)
   - siblings A, B off main: BOTH diverged; the fork is the EARLIER of the
     two creations, so no own-change on either side predates the fork and
     gets silently dropped. This replaced an `:else` fallback to
     source.created-at that MISSED a conflict when the target sibling was
     edited before the source branch existed (a false negative → silent
     clobber). See `sibling-merge-detects-conflict-…-test`.

   Residual (documented, not solved here): `detect-conflicts` inspects only
   the two ENDPOINTS' own version rows, so a change on an INTERMEDIATE
   branch of a multi-level merge (grandchild→grandparent) that is inherited
   but not re-stamped on an endpoint is still not compared — that needs
   examining inherited rows, a larger change than the fork-point."
  [base-storage source-branch-id target-branch-id]
  (let [source-chain (res/collect-branch-chain base-storage source-branch-id)
        target-chain (res/collect-branch-chain base-storage target-branch-id)
        target-set   (set target-chain)
        source-set   (set source-chain)
        ;; The deepest chain element the other side doesn't share = the
        ;; branch that forked off the common line. nil when this side is an
        ;; ancestor of the other (its whole chain is shared) → it never
        ;; diverged.
        divergence-created (fn [chain other-set]
                             (when-let [b (first (remove other-set chain))]
                               (:created-at (sp/read-entity base-storage :branch b))))
        src-div (divergence-created source-chain target-set)
        tgt-div (divergence-created target-chain source-set)
        branch-created (cond
                         (and src-div tgt-div) (if (neg? (compare src-div tgt-div))
                                                 src-div tgt-div)
                         src-div src-div
                         tgt-div tgt-div
                         ;; Degenerate: source == target (self-merge, no
                         ;; conflicts anyway). Keep the ts non-nil.
                         :else (:created-at (sp/read-entity base-storage :branch
                                                            source-branch-id)))
        ;; Previous merges between these branches in EITHER direction.
        ;; One SQL roundtrip via `WHERE source IN (a, b) AND target IN
        ;; (a, b)` covers the 4 (source,target) combos; in-memory we
        ;; reject the two self-pair cases (a,a) / (b,b).
        pair (vec (distinct [source-branch-id target-branch-id]))
        candidate-merges (sp/query-entities base-storage :branch-merge
                                            {:source-branch-id pair
                                             :target-branch-id pair})
        between-branches (filter (fn [m]
                                   (not= (:source-branch-id m)
                                         (:target-branch-id m)))
                                 candidate-merges)
        all-merge-times (map :target-timestamp between-branches)
        latest-merge (when (seq all-merge-times)
                       (reduce (fn [a b] (if (pos? (compare b a)) b a))
                               (first all-merge-times)
                               (rest all-merge-times)))]
    (if (and latest-merge (pos? (compare latest-merge branch-created)))
      latest-merge
      branch-created)))


(defn- modified-entities-after
  "Returns set of entity ids that have been modified on a branch after the fork point.
   Checks all versioned entity types (fn, slot, fn-slot, binding, list-item)."
  [base-storage branch-id after-ts]
  (reduce-kv
    (fn [acc entity-name {:keys [version-entity version-id-field]}]
      (let [all-versions (sp/query-entities base-storage version-entity
                                            {:branch-id branch-id})
            modified (filter #(pos? (compare (:created-at %) after-ts)) all-versions)]
        (reduce (fn [m v]
                  (update m entity-name (fnil conj #{}) (get v version-id-field)))
                acc
                modified)))
    {}
    res/entity-config))


(defn batch-resolve
  "Resolve every `entity-id` of every `entity-name` on `branch-id` in
   one query per type. Returns `{[entity-name entity-id] resolved}`.
   Public so `diff-view` can resolve display names for fns a diff
   references but does not itself contain."
  [base-storage entity-name->ids branch-id]
  ;; Chain cache is process-wide (`resolution/global-chain-cache`)
  ;; — no per-call binding required.
  (reduce-kv
    (fn [acc entity-name ids]
      (if (empty? ids)
        acc
        (let [identity-records (vals (sp/read-entities base-storage
                                                       entity-name (vec ids)))
              resolved (res/resolve-entities-batch base-storage entity-name
                                                   identity-records branch-id)]
          (reduce-kv (fn [m eid r] (assoc m [entity-name eid] r))
                     acc
                     resolved))))
    {}
    entity-name->ids))


(defn- conflict-owning-fn-id
  "The fn-id whose `:branch-local?` flag governs a conflicting entity.
   `:fn` is its own owner; `:binding`/`:fn-slot` resolved rows carry
   `:fn-id`; `:binding-list-item` chains through its owning binding.
   `:slot` is a global identity shared across fns (no single owner),
   so nil — never filtered as branch-local. Returns nil when no owner
   can be determined."
  [base-storage entity-name entity-id resolved]
  (case entity-name
    :fn entity-id
    (:fn-slot :binding) (:fn-id resolved)
    :binding-list-item (some->> (:binding-id resolved)
                                (sp/read-entity base-storage :binding)
                                :fn-id)
    nil))


(defn detect-conflicts
  "Finds entities modified in both source and target branches after fork point.

   Returns a map:
   {:conflicts [{:entity-name :fn
                 :entity-id uuid
                 :source-version <resolved version data>
                 :target-version <resolved version data>}]
    :fork-point <Instant>}"
  [base-storage source-branch-id target-branch-id]
  (let [fp (fork-point base-storage source-branch-id target-branch-id)
        source-modified (modified-entities-after base-storage source-branch-id fp)
        target-modified (modified-entities-after base-storage target-branch-id fp)
        ;; Conflicting entity-ids per type — modified on BOTH branches.
        conflict-ids (reduce-kv
                       (fn [acc entity-name source-ids]
                         (let [target-ids (get target-modified entity-name #{})
                               common (set/intersection source-ids target-ids)]
                           (cond-> acc
                             (seq common) (assoc entity-name common))))
                       {}
                       source-modified)
        ;; Two branch resolutions, batched per entity type.
        source-resolved (batch-resolve base-storage conflict-ids source-branch-id)
        target-resolved (batch-resolve base-storage conflict-ids target-branch-id)
        conflicts (for [[entity-name ids] conflict-ids
                        entity-id ids
                        :let [src-v (get source-resolved [entity-name entity-id])
                              owner (conflict-owning-fn-id base-storage entity-name
                                                           entity-id src-v)]
                        ;; A branch-local fn's config is intentionally
                        ;; per-branch — it must NEVER surface as a merge
                        ;; conflict. The resolver already drops its
                        ;; cross-branch version rows on read; without
                        ;; this the user is forced to resolve a phantom
                        ;; conflict and, by picking `:source`, would
                        ;; leak the source branch's value onto the
                        ;; target (exactly what branch-local forbids).
                        :when (not (and owner
                                        (bl/effective-branch-local? base-storage owner)))]
                    {:entity-name entity-name
                     :entity-id entity-id
                     :source-version src-v
                     :target-version (get target-resolved [entity-name entity-id])})]
    {:conflicts (vec conflicts)
     :fork-point fp}))


(defn- assert-resolutions-cover-conflicts!
  "Throws `:merge-conflict` if any detected conflict has no entry in
   `conflict-resolutions`. No-op when there are no conflicts."
  [conflicts conflict-resolutions]
  (when (seq conflicts)
    (let [resolved-keys (set (keys (or conflict-resolutions {})))
          conflict-keys (set (map (juxt :entity-name :entity-id) conflicts))
          unresolved (set/difference conflict-keys resolved-keys)]
      (when (seq unresolved)
        (throw (ex-info "Unresolved merge conflicts"
                        {:type :merge-conflict
                         :conflicts conflicts
                         :unresolved (vec unresolved)}))))))


(defn- create-merge-record!
  "Insert the branch-merge row that lets resolution surface source
   branch versions on target. Returns the inserted record."
  [base-storage source-branch-id target-branch-id ts]
  (let [merge-record {:id (random-uuid)
                      :source-branch-id source-branch-id
                      :source-timestamp ts
                      :target-branch-id target-branch-id
                      :target-timestamp ts
                      :created-at ts}]
    (sp/create-entity base-storage :branch-merge merge-record)
    merge-record))


(defn- conflict-resolution->version-record
  "Build the version record for a single resolution. Same shape as
   `prepare-version-record` in the versioning storage core, but
   inlined here so the merge module stays decoupled."
  [entity-name entity-id chosen-data target-branch-id ts]
  (let [{:keys [version-id-field version-data-fields]}
        (get res/entity-config entity-name)]
    (-> (select-keys chosen-data version-data-fields)
        (assoc :id (random-uuid)
               version-id-field entity-id
               :branch-id target-branch-id
               :created-at ts))))


(defn- apply-resolutions!
  "For each conflict whose resolution is `:source` or `:target`, create
   a fresh version record on target branch with the chosen data.
   `nil` resolutions (no key) silently skip. Records are grouped by
   version entity and inserted in one batch per type. Stamped at
   `merge-ts` + 1ms so resolutions are DETERMINISTICALLY strictly-later
   than the merge record (target-timestamp = `merge-ts`) →
   `pick-latest-candidate` prefers the resolution over the
   merge-surfaced source — a second `(now)` read here would make
   'resolution wins' depend on wall-clock monotonicity between the two
   writes. The +1ms epsilon survives Postgres timestamptz microsecond
   truncation.

   A chosen side that DELETED the entity resolves to nil (tombstone
   winner omitted by `resolve-entities-batch`). Explicitly choosing
   that side means 'keep the deletion': we must write a TOMBSTONE
   version on target, not skip — otherwise the merge surfaces the
   OTHER (live) side and the entity resurrects against the user's
   choice. Non-null version columns are filled from the sibling live
   side. If both sides are nil (both deleted) target already resolves
   absent → nothing to write."
  [base-storage conflicts conflict-resolutions target-branch-id merge-ts]
  (let [ts (Instant/.plusMillis merge-ts 1)
        by-version-entity
        (reduce
          (fn [acc {:keys [entity-name entity-id source-version target-version]}]
            (let [resolution (get conflict-resolutions [entity-name entity-id])
                  chosen (case resolution
                           :source source-version
                           :target target-version
                           nil)
                  {:keys [version-entity]} (get res/entity-config entity-name)]
              (cond
                ;; Live chosen side → normal resolution version.
                (some? chosen)
                (update acc version-entity (fnil conj [])
                        (conflict-resolution->version-record
                          entity-name entity-id chosen target-branch-id ts))

                ;; Explicit :source/:target but chosen is nil ⇒ that side
                ;; deleted. Write a tombstone (data from the live sibling
                ;; to satisfy non-null columns); no-op if both nil.
                (and (#{:source :target} resolution)
                     (some? (or source-version target-version)))
                (update acc version-entity (fnil conj [])
                        (-> (conflict-resolution->version-record
                              entity-name entity-id
                              (or source-version target-version)
                              target-branch-id ts)
                            (assoc :deleted-at ts)))

                ;; No resolution key, or both-deleted → skip.
                :else acc)))
          {}
          conflicts)]
    (doseq [[version-entity records] by-version-entity]
      (sp/create-entities base-storage version-entity records))))


(defn- branch-ancestor-ids
  "Set of branch-ids along the inheritance chain from `branch-id`
   upward (inclusive). Used by `diff-branches` to decide whether a
   modification visible on one branch was actually authored there or
   inherited from an ancestor."
  [base-storage branch-id]
  (loop [acc #{} cur branch-id]
    (if (or (nil? cur) (contains? acc cur))
      acc
      (let [b (sp/read-entity base-storage :branch cur)]
        (recur (conj acc cur) (:base-branch-id b))))))


(defn- branch-visibility-ids
  "Set of branch-ids whose version rows may appear in `branch-id`'s
   resolved view. Includes the ancestor chain plus the source-branch
   of every `branch-merge` whose target lands on some ancestor in
   that chain — mirrors the resolver's two-hop algorithm in
   `resolution.clj/resolve-version` (own → merges-into-this →
   recurse-to-parent). Without this, post-merge diffs would
   understate the visible-via-merge entity set."
  [base-storage branch-id]
  (let [ancestor-set (branch-ancestor-ids base-storage branch-id)
        merge-sources (when (seq ancestor-set)
                        (->> (sp/query-entities base-storage :branch-merge
                                                {:target-branch-id (vec ancestor-set)})
                             (map :source-branch-id)
                             (filter some?)
                             set))]
    (set/union ancestor-set (or merge-sources #{}))))


(defn- touched-entities-by-side
  "Combined source+target visibility scan. ONE query per entity-type
   (4 total) against `WHERE :branch-id IN (source-vis ∪ target-vis)`,
   then partitions client-side. Rows on branches present in BOTH chains
   (e.g. the shared `main` ancestor) contribute to both sides
   correctly. Returns `{entity-name {:source #{eid} :target #{eid}}}`."
  [base-storage source-vis target-vis]
  (let [union-branches (vec (set/union source-vis target-vis))]
    (reduce-kv
      (fn [acc entity-name {:keys [version-entity version-id-field]}]
        (let [vs (sp/query-entities base-storage version-entity
                                    {:branch-id union-branches})
              partitioned
              (reduce
                (fn [m v]
                  (let [eid (get v version-id-field)
                        bid (:branch-id v)
                        m (if (contains? source-vis bid)
                            (update m :source conj eid)
                            m)]
                    (if (contains? target-vis bid)
                      (update m :target conj eid)
                      m)))
                {:source #{} :target #{}}
                vs)]
          (assoc acc entity-name partitioned)))
      {}
      res/entity-config)))


(defn diff-branches
  "Resolved-view diff between `source-branch-id` and `target-branch-id`.

   For every versioned entity-type, finds every entity-id that has at
   least one version record on EITHER branch's ancestor chain, then
   resolves each on both branches. An entry is included only when the
   resolved views actually differ.

   `resolve-entities-batch` returns the bare identity row when an
   entity has no version on the chain (used by the executor for
   base-fns); we treat that as `nil` here so a fn that exists only on
   one branch reports `:added-in-source` / `:added-in-target` rather
   than `:modified`.

   Returns:
     {:source-branch-id <uuid>
      :target-branch-id <uuid>
      :diffs [{:entity-name :fn
               :entity-id   <uuid>
               :source-version <resolved map or nil>
               :target-version <resolved map or nil>
               :change      :added-in-source | :added-in-target | :modified}
              …]}

   Branch-symmetric: doesn't privilege the source as the side bringing
   in changes (use `detect-conflicts` for the merge-oriented framing).

   `branch-merge` records contribute to visibility — each branch's
   visibility set is its ancestor chain PLUS the source-branch of
   every merge that landed on any ancestor. Mirrors the resolver's
   two-hop algorithm exactly."
  [base-storage source-branch-id target-branch-id]
  (let [source-vis (branch-visibility-ids base-storage source-branch-id)
        target-vis (branch-visibility-ids base-storage target-branch-id)
        touched (touched-entities-by-side base-storage source-vis target-vis)
        touched-source (reduce-kv (fn [m k v] (assoc m k (:source v))) {} touched)
        touched-target (reduce-kv (fn [m k v] (assoc m k (:target v))) {} touched)
        all-touched (merge-with set/union touched-source touched-target)
        source-resolved (batch-resolve base-storage all-touched source-branch-id)
        target-resolved (batch-resolve base-storage all-touched target-branch-id)
        in-chain? (fn [touched ename eid]
                    (contains? (get touched ename #{}) eid))
        diffs (for [[entity-name ids] all-touched
                    eid ids
                    :let [sv (when (in-chain? touched-source entity-name eid)
                               (get source-resolved [entity-name eid]))
                          tv (when (in-chain? touched-target entity-name eid)
                               (get target-resolved [entity-name eid]))
                          ;; created-at differs on every version row; comparing
                          ;; data-only avoids spurious entries.
                          sv-data (some-> sv (dissoc :created-at))
                          tv-data (some-> tv (dissoc :created-at))]
                    :when (not= sv-data tv-data)]
                {:entity-name entity-name
                 :entity-id eid
                 :source-version sv
                 :target-version tv
                 :change (cond
                           (and (nil? tv) (some? sv)) :added-in-source
                           (and (nil? sv) (some? tv)) :added-in-target
                           :else :modified)})]
    {:source-branch-id source-branch-id
     :target-branch-id target-branch-id
     :diffs (vec diffs)}))


(defn untransferable-inherited-entities
  "Entities a by-reference merge of `source-branch-id` into `target-branch-id`
   would SILENTLY DROP. A merge inserts a `branch-merge` record that surfaces
   only the SOURCE's OWN version rows; content the source shows by INHERITANCE
   — from an ancestor it forked off, or a branch it merged — that the target
   does not already share is NOT carried, so it would vanish from the merged
   result without a conflict or any signal.

   Returns `[{:entity-name :entity-id} …]`; EMPTY in the common cases where the
   merge is complete: a branch forked off the target, or a sibling of it, shares
   all its inherited content with the target (those entities resolve equal on
   both sides, so they never enter the diff). Non-empty only for a genuine
   cross-base / stacked / chained merge.

   Computed from the resolved-view `diff-branches` (which already accounts for
   ancestor + merge visibility): a diff entry the source contributes
   (`:source-version` present) whose entity the source does NOT own a version
   row for is inherited — the record won't carry it."
  [base-storage source-branch-id target-branch-id]
  (let [{:keys [diffs]} (diff-branches base-storage source-branch-id target-branch-id)
        source-owned (reduce-kv
                       (fn [acc entity-name {:keys [version-entity version-id-field]}]
                         (assoc acc entity-name
                                (into #{} (map version-id-field)
                                      (sp/query-entities base-storage version-entity
                                                         {:branch-id source-branch-id}))))
                       {} res/entity-config)]
    (vec
      (for [{:keys [entity-name entity-id source-version]} diffs
            :when (some? source-version)                       ; the source brings a value here
            :when (not (contains? (get source-owned entity-name #{}) entity-id))]
        {:entity-name entity-name :entity-id entity-id}))))


(defn merge-affected-fn-ids
  "The set of `:fn` ids whose resolved definition (and therefore
   compiled closure) changes on the target when `source-branch-id` is
   merged in — every fn that owns a version row on the source branch.

   Used to seed a DELTA cache invalidation after a merge instead of a
   full registry clear: a merge only shifts the fns touched on the
   source, so recompiling those (+ their reverse-deps, which
   `delta-recompile!` expands) is far cheaper than recompiling the
   whole ~thousands-of-fns registry (the recompile the next request
   would otherwise pay is the post-merge stall).

   Mapping to the owner fn: `fn-version` / `fn-slot-version` /
   `binding-version` all denormalise `:fn-id`; `binding-list-item-
   version` carries only `:binding-id`, so we read those bindings to
   recover their `:fn-id`. `:slot` is not versioned (absent from
   `entity-config`), so there is no shared-slot fan-out to worry
   about."
  [base-storage source-branch-id]
  (let [own-fn-ids (fn [version-entity]
                     (into #{} (keep :fn-id)
                           (sp/query-entities base-storage version-entity
                                              {:branch-id source-branch-id})))
        item-binding-ids (into #{} (keep :binding-id)
                               (sp/query-entities base-storage :binding-list-item-version
                                                  {:branch-id source-branch-id}))
        item-fn-ids (when (seq item-binding-ids)
                      (into #{} (keep :fn-id)
                            (vals (sp/read-entities base-storage :binding
                                                    (vec item-binding-ids)))))]
    (set/union (own-fn-ids :fn-version)
               (own-fn-ids :fn-slot-version)
               (own-fn-ids :binding-version)
               (or item-fn-ids #{}))))


(defn- assert-merge-preserves-uniqueness!
  "Guards the per-branch RESOLVED-VIEW uniqueness invariants across a
   merge. `detect-conflicts` keys on `[entity-name entity-id]`, so two
   DISTINCT entities independently created on divergent branches with the
   same per-branch-unique key — a `:fn`'s `(namespace-id, name)` or a
   `:binding-list-item`'s `(binding-id, position)` — are NOT a conflict.
   Merging one branch into the other then surfaces BOTH onto the target,
   leaving a resolved view that direct create/update reject and that
   name→id resolution / sync (`:packages/ambiguous-ref`) treat as an error.

   Runs INSIDE the merge transaction, AFTER the merge record + any
   resolutions are written on `storage` — so it resolves the actual
   POST-merge target view (the surfaced source rows now win/appear there).
   Re-uses the SAME resolved-view checks the create/update path uses
   (`uniqueness.clj`); a collision throws `:constraint-violation/*`, which
   rolls the whole merge tx back rather than committing a corrupt view.

   Only entities the merge SURFACES need checking — those with a version
   row on the merged-in source branch. Branch-local source fns resolve to
   nil / the target's own row on the target and so can't introduce a
   collision (they never propagate), which resolving on the target handles
   for free."
  [storage source-branch-id target-branch-id]
  ;; :fn — (namespace-id, name)
  (let [src-fn-ids (into #{} (keep :fn-id)
                         (sp/query-entities storage :fn-version
                                            {:branch-id source-branch-id}))]
    (doseq [fid src-fn-ids
            :let [resolved (res/resolve-entity storage :fn fid target-branch-id)]
            :when (:name resolved)]
      (uniq/check-fn-name-collision! storage target-branch-id :fn resolved)))
  ;; :binding-list-item — (binding-id, position)
  (let [src-item-ids (into #{} (keep :item-id)
                           (sp/query-entities storage :binding-list-item-version
                                              {:branch-id source-branch-id}))
        resolved (keep #(res/resolve-entity storage :binding-list-item % target-branch-id)
                       src-item-ids)]
    (when (seq resolved)
      (uniq/check-list-item-position-collisions! storage target-branch-id
                                                 :binding-list-item (vec resolved)))))


(defn branch-lock-key
  "Advisory-lock key serializing branch-level MERGE and DELETE writes that
   touch a single branch id. A merge locks BOTH its endpoints; a
   `delete-branch!` locks the branch it removes — so a merge-into-X and a
   delete-of-X-as-source contend on the shared key for X and cannot
   interleave between one op's check-read and the other's commit."
  [branch-id]
  (str "branch|" branch-id))


(defn lock-branches!
  "Take a per-branch `pg_advisory_xact_lock` (auto-released at commit/
   rollback) on each given branch id, from INSIDE the caller's write tx, so
   the check-then-write of a merge/delete is atomic w.r.t. a concurrent
   merge/delete on the same branch.

   Deadlock-free ordering: distinct ids, `nil`s dropped, acquired sorted by
   UUID string. A merge passes both endpoints (source+target); a delete
   passes its single branch. Single-lock callers never wait while holding
   another lock, and every multi-lock caller acquires in the same sorted
   order, so no wait cycle can form. No-op off a pooled backend (`:pool`
   nil) — matches the create/update advisory-lock path in `.core`."
  [storage & branch-ids]
  (when-let [tx (:pool storage)]
    (doseq [bid (->> branch-ids (remove nil?) distinct (sort-by str))]
      (jdbc/execute! tx ["SELECT pg_advisory_xact_lock(hashtext(?)::bigint)"
                         (branch-lock-key bid)]))))


(defn merge-branch!
  "Merges source branch into the current (target) branch.

   Creates a branch-merge record that makes source versions visible on target
   via the resolution algorithm. No version records are copied.

   If conflicts exist (entities modified on both branches after fork point),
   throws unless conflict-resolutions are provided.

   Arguments:
   - versioned-storage: VersionedStorage instance (target branch)
   - source-branch-id: Branch to merge from
   - opts: Optional map with :conflict-resolutions

   conflict-resolutions is a map of {[entity-name entity-id] :source | :target}
   - :source — keep source branch version (create version record on target)
   - :target — keep target branch version (no action needed, target already has it)

   Returns the branch-merge record."
  ([versioned-storage source-branch-id]
   (merge-branch! versioned-storage source-branch-id {}))
  ([versioned-storage source-branch-id {:keys [conflict-resolutions]}]
   (let [base-storage (:base-storage versioned-storage)
         target-branch-id (:branch-id versioned-storage)
         ;; A branch cannot be merged into itself. `fork-point` degenerates
         ;; on source == target (neither side diverges from the other, so
         ;; every own-branch edit reads back as "modified on both sides"),
         ;; producing phantom self-conflicts; committing the merge record
         ;; would then plant a self-referential `branch-merge` the resolver
         ;; has to walk on every read. Reject it up front rather than let it
         ;; corrupt the merge graph.
         _ (when (= source-branch-id target-branch-id)
             (throw (ex-info "Cannot merge a branch into itself"
                             {:type :constraint-violation/self-merge
                              :branch-id source-branch-id})))
         ;; ATOMIC: the merge record (which surfaces every source version on
         ;; target) and the conflict-resolution overrides (which beat that
         ;; surfacing for entities the user resolved as `:target`) must land
         ;; together. Committing the merge record but losing the resolutions
         ;; would silently discard the user's decisions — the source value
         ;; would surface where they chose to keep target. `PostgresStorage`
         ;; exposes its `:pool` as the Connectable `sp/create-entity` runs on,
         ;; so binding the storage to a `with-transaction` connection makes
         ;; both writes share one commit. Non-PG storages (no `:pool`) fall
         ;; back to the prior sequential behaviour. Single `merge-ts` so the
         ;; resolution-wins ordering is structural, not clock-dependent.
         merge-ts (now)
         write!
         (fn [storage]
           ;; L5: serialize concurrent merges into the SAME target (and a
           ;; racing delete of either endpoint) on per-branch advisory
           ;; locks taken FIRST, inside this tx. Without them
           ;; `detect-conflicts` reads a snapshot outside any write lock,
           ;; so a second merge committing between this merge's read and
           ;; its commit isn't observed. Locking both endpoints (sorted,
           ;; deadlock-free) makes the read-then-write below one
           ;; serialized critical section per branch pair.
           (lock-branches! storage source-branch-id target-branch-id)
           ;; Under the lock, re-confirm both endpoints still exist — a
           ;; delete of source/target that committed just before we won
           ;; the lock must abort this merge rather than plant a
           ;; branch-merge dangling at a removed branch.
           (when-not (sp/read-entity storage :branch source-branch-id)
             (throw (ex-info "Merge source branch not found"
                             {:type :not-found :branch-id source-branch-id})))
           (when-not (sp/read-entity storage :branch target-branch-id)
             (throw (ex-info "Merge target branch not found"
                             {:type :not-found :branch-id target-branch-id})))
           ;; Conflict detection now reads a lock-stable view (inside the
           ;; tx, after the lock) instead of the pre-tx snapshot.
           (let [{:keys [conflicts]} (detect-conflicts storage source-branch-id
                                                       target-branch-id)]
             (assert-resolutions-cover-conflicts! conflicts conflict-resolutions)
             ;; A by-reference merge carries only the source's OWN rows, so
             ;; content the source shows by inheritance from a branch the target
             ;; does not share would be SILENTLY dropped. Refuse rather than lose
             ;; it — the user merges the intermediate branch into the target
             ;; first. Empty (no-op) for the common forked-off-target / sibling
             ;; merges; fires only on a genuine cross-base / stacked merge.
             (let [dropped (untransferable-inherited-entities storage source-branch-id
                                                              target-branch-id)]
               (when (seq dropped)
                 (throw (ex-info
                          (str "Merge blocked: this branch shows " (count dropped)
                               " change(s) inherited from a branch the target does not "
                               "share. A merge transfers only this branch's own changes, "
                               "so that content would be lost. Merge the intermediate "
                               "branch into the target first, then merge this one.")
                          {:type :merge/inherited-content-not-transferable
                           :entities dropped
                           :source-branch-id source-branch-id
                           :target-branch-id target-branch-id}))))
             (let [merge-record (create-merge-record! storage source-branch-id
                                                      target-branch-id merge-ts)]
               (apply-resolutions! storage conflicts conflict-resolutions
                                   target-branch-id merge-ts)
               ;; POST-merge, still inside the tx: reject a merge that
               ;; would leave two live entities sharing a per-branch
               ;; unique key (fn name / list-item position) on the
               ;; target — a collision detect-conflicts can't see
               ;; (distinct ids). Throwing rolls the whole merge back.
               (assert-merge-preserves-uniqueness! storage source-branch-id
                                                   target-branch-id)
               merge-record)))]
     (if-let [pool (:pool base-storage)]
       ;; `:ignore` so a nested `with-transaction` in an inner write
       ;; can't commit early and release the advisory locks before the
       ;; check-then-write finishes — same reason as the `.core` create
       ;; path and `delete-branch!`.
       (binding [jdbc-tx/*nested-tx* :ignore]
         (jdbc/with-transaction [tx pool]
                                (write! (assoc base-storage :pool tx))))
       (write! base-storage)))))


(defn skipped-as-branch-local
  "Enumerate the `:fn` rows that have a version on `source-branch-id`
   but won't surface on `target-branch-id` after a merge because their
   effective `:branch-local?` is true. Returned shape:

     [{:entity-name :fn :entity-id <uuid> :fn-name \"my-server\"} …]

   The resolver filter in `versioning.storage.resolution` is the
   load-bearing path — this fn is the audit-log front-end so API
   consumers can SEE what didn't propagate without having to diff
   pre / post-merge views themselves. Sorted by fn-name for
   determinism.

   Doesn't observe the merge transaction directly — runs against
   `base-storage` AFTER the merge record lands, which is fine since
   the filter is deterministic from the (fn-id, branch-local? flag)
   pair and neither changes during merge."
  [base-storage source-branch-id]
  (let [;; Fns whose only version overlay on the entire chain landed
        ;; on the merged-in source branch.
        src-fn-versions (sp/query-entities base-storage :fn-version
                                           {:branch-id source-branch-id})
        ;; Stable, distinct fn-ids — one fn can have many versions on
        ;; the source.
        src-fn-ids (into [] (distinct) (map :fn-id src-fn-versions))
        local-ids (filter #(bl/effective-branch-local? base-storage %) src-fn-ids)
        rows (when (seq local-ids)
               (sp/read-entities base-storage :fn (vec local-ids)))
        named (->> local-ids
                   (keep (fn [fid]
                           (when-let [row (get rows fid)]
                             {:entity-name :fn
                              :entity-id fid
                              :fn-name (:name row)}))))]
    (vec (sort-by (fn [r] (or (:fn-name r) "")) named))))

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
   authoritative list (fn / fn-slot / binding / binding-list-item)."
  (:require
    [clojure.set :as set]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.branch-local :as bl]
    [graphden.versioning.storage.resolution :as res])
  (:import
    (java.time
      Instant)))


(defn- now
  []
  (Instant/now))


(defn- fork-point
  "Determines the fork point between source and target branches.
   Fork point is the later of:
   - the CHILD branch's created-at (when it forked off its parent)
   - latest merge timestamp between the two branches (if any previous merges)

   The fork base is the created-at of whichever branch DESCENDS from the
   other (its `:base-branch-id` points at the other) — for a feature→main
   merge that's the source (feature), but for a main→feature (pull) merge it
   is the TARGET (feature). Using the source unconditionally made a
   main→feature merge fork at main's root created-at, so every already-
   inherited main change counted as \"modified after fork\" → spurious
   conflicts. Unrelated / multi-level branches fall back to the source
   (unchanged behaviour) until a true common-ancestor walk is added."
  [base-storage source-branch-id target-branch-id]
  (let [source-branch (sp/read-entity base-storage :branch source-branch-id)
        target-branch (sp/read-entity base-storage :branch target-branch-id)
        child-branch (cond
                       (= (:base-branch-id source-branch) target-branch-id) source-branch
                       (= (:base-branch-id target-branch) source-branch-id) target-branch
                       :else source-branch)
        branch-created (:created-at child-branch)
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


(defn- batch-resolve
  "Resolve every `entity-id` of every `entity-name` on `branch-id` in
   one query per type. Returns `{[entity-name entity-id] resolved}`."
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
                        entity-id ids]
                    {:entity-name entity-name
                     :entity-id entity-id
                     :source-version (get source-resolved [entity-name entity-id])
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
   `nil` resolutions silently skip. Records are grouped by version
   entity and inserted in one batch per type. Single timestamp shared
   so per-type ordering is deterministic; the merge record's timestamp
   is strictly earlier so resolutions win."
  [base-storage conflicts conflict-resolutions target-branch-id]
  (let [ts (now)
        by-version-entity
        (reduce
          (fn [acc {:keys [entity-name entity-id source-version target-version]}]
            (if-let [chosen (case (get conflict-resolutions [entity-name entity-id])
                              :source source-version
                              :target target-version
                              nil)]
              (let [{:keys [version-entity]} (get res/entity-config entity-name)
                    record (conflict-resolution->version-record
                             entity-name entity-id chosen target-branch-id ts)]
                (update acc version-entity (fnil conj []) record))
              acc))
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
   then partitions client-side. Pre-fix the diff did 4 + 4 = 8
   queries (one per side). Rows on branches present in BOTH chains
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
         {:keys [conflicts]} (detect-conflicts base-storage source-branch-id
                                               target-branch-id)]
     (assert-resolutions-cover-conflicts! conflicts conflict-resolutions)
     (let [merge-record (create-merge-record! base-storage source-branch-id
                                              target-branch-id (now))]
       (apply-resolutions! base-storage conflicts conflict-resolutions
                           target-branch-id)
       merge-record))))


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

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
   - source branch created-at (when it was forked)
   - latest merge timestamp between the two branches (if any previous merges)"
  [base-storage source-branch-id target-branch-id]
  (let [source-branch (sp/read-entity base-storage :branch source-branch-id)
        branch-created (:created-at source-branch)
        ;; Find previous merges between these branches (in either direction)
        merges-to-target (sp/query-entities base-storage :branch-merge
                                            {:source-branch-id source-branch-id
                                             :target-branch-id target-branch-id})
        merges-to-source (sp/query-entities base-storage :branch-merge
                                            {:source-branch-id target-branch-id
                                             :target-branch-id source-branch-id})
        all-merge-times (concat
                          (map :target-timestamp merges-to-target)
                          (map :target-timestamp merges-to-source))
        latest-merge (when (seq all-merge-times)
                       (reduce (fn [a b] (if (pos? (compare b a)) b a))
                               (first all-merge-times)
                               (rest all-merge-times)))]
    (if (and latest-merge (pos? (compare latest-merge branch-created)))
      latest-merge
      branch-created)))


(defn- modified-entities-after
  "Returns set of entity ids that have been modified on a branch after the fork point.
   Checks all versioned entity types (fn and arg)."
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
  (binding [res/*branch-chain-cache* (atom {})]
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
      entity-name->ids)))


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

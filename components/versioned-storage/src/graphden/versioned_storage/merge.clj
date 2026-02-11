(ns graphden.versioned-storage.merge
  "Branch merge operations with conflict detection.

   Merge mechanism: inserting a branch-merge record makes source branch versions
   visible on the target branch via the resolution algorithm. No version records
   are copied.

   Conflict detection: an entity is conflicted when it has been modified on both
   the source and target branches after the fork point (branch creation time or
   last merge between the two)."
  (:require
    [clojure.set]
    [graphden.storage-protocol.interface :as sp]
    [graphden.versioned-storage.resolution :as res])
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
   Checks all versioned entity types."
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
        ;; Find intersection: entities modified on both branches
        conflicts
        (for [[entity-name source-ids] source-modified
              :let [target-ids (get target-modified entity-name #{})]
              entity-id source-ids
              :when (contains? target-ids entity-id)]
          {:entity-name entity-name
           :entity-id entity-id
           :source-version (res/resolve-entity base-storage entity-name
                                               entity-id source-branch-id)
           :target-version (res/resolve-entity base-storage entity-name
                                               entity-id target-branch-id)})]
    {:conflicts (vec conflicts)
     :fork-point fp}))


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

     ;; Check for unresolved conflicts
     (when (seq conflicts)
       (let [resolved-keys (set (keys (or conflict-resolutions {})))
             conflict-keys (set (map (juxt :entity-name :entity-id) conflicts))
             unresolved (clojure.set/difference conflict-keys resolved-keys)]
         (when (seq unresolved)
           (throw (ex-info "Unresolved merge conflicts"
                           {:type :merge-conflict
                            :conflicts conflicts
                            :unresolved (vec unresolved)})))))

     ;; Create the branch-merge record FIRST
     (let [ts (now)
           merge-record {:id (random-uuid)
                         :source-branch-id source-branch-id
                         :source-timestamp ts
                         :target-branch-id target-branch-id
                         :target-timestamp ts
                         :created-at ts}]
       (sp/create-entity base-storage :branch-merge merge-record)

       ;; Apply conflict resolutions AFTER the merge record
       ;; Both :source and :target create a new version record on target branch
       ;; with a timestamp newer than the merge record, ensuring the chosen
       ;; version wins over any version brought in via the merge
       (doseq [{:keys [entity-name entity-id source-version target-version]} conflicts
               :let [resolution (get conflict-resolutions
                                     [entity-name entity-id])
                     chosen-data (case resolution
                                   :source source-version
                                   :target target-version
                                   nil)]
               :when chosen-data]
         (let [{:keys [version-entity version-id-field version-data-fields]}
               (get res/entity-config entity-name)
               version-data (-> (select-keys chosen-data version-data-fields)
                                (assoc :id (random-uuid)
                                       version-id-field entity-id
                                       :branch-id target-branch-id
                                       :created-at (now)))]
           (sp/create-entity base-storage version-entity version-data)))

       merge-record))))

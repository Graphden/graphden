(ns graphden.cached-versioned-storage.interface
  "Connector between cached-storage and versioned-storage.

   This is a combining module (per PHILOSOPHY.md): it knows about both
   cached-storage and versioned-storage and wires their interaction.

   Neither cached-storage nor versioned-storage import each other.
   This connector handles cache invalidation for branch operations:
   - merge-branch! invalidates caches for entities affected by the merge
   - switch-branch returns a new cached+versioned storage on the new branch
   - delete-branch! invalidates all caches

   ## Stack Architecture

   CachedStorage(VersionedStorage(BaseStorage)):
   - CachedStorage intercepts CRUD, handles cache invalidation
   - VersionedStorage intercepts CRUD, handles version resolution
   - BaseStorage performs actual persistence

   Normal CRUD operations flow through CachedStorage -> VersionedStorage
   and invalidation happens automatically. Branch operations (merge, switch,
   delete) bypass CachedStorage, so this connector provides wrapper functions
   that add cache invalidation."
  (:require
    [graphden.cache-protocol.interface :as cache]
    [graphden.cached-storage.interface :as cs]
    [graphden.cached-storage.invalidation :as inv]
    [graphden.storage-protocol.interface :as sp]
    [graphden.versioned-storage.interface :as vs]
    [graphden.versioned-storage.resolution :as res]))


(defn- extract-components
  "Extracts versioned-storage and cache-storage from a CachedStorage wrapping
   a VersionedStorage."
  [cached-storage]
  (let [versioned (:base-storage cached-storage)
        cache (:cache-storage cached-storage)]
    {:versioned versioned
     :cache cache}))


(defn- invalidate-fn-ids!
  "Invalidates caches for a set of fn-ids."
  [versioned-storage cache-storage fn-ids]
  (doseq [fn-id fn-ids]
    (inv/invalidate-fn-and-dependents! versioned-storage cache-storage fn-id)))


(defn- invalidate-all-affected-entities!
  "Invalidates caches for all entity types affected by a merge.
   Finds fn-ids from the source branch's modified entities and invalidates them."
  [versioned-storage cache-storage source-branch-id _target-branch-id]
  (let [base (:base-storage versioned-storage)]
    ;; Find all fn entities modified on source branch
    ;; These are the ones whose resolution may change on target after merge
    (doseq [[entity-name {:keys [version-entity version-id-field]}] res/entity-config]
      (let [versions (sp/query-entities base version-entity
                                        {:branch-id source-branch-id})
            entity-ids (set (map version-id-field versions))]
        (when (= entity-name :fn)
          (invalidate-fn-ids! versioned-storage cache-storage entity-ids))
        (when (= entity-name :fn-schema)
          (doseq [schema-id entity-ids]
            (inv/invalidate-entity-dependents!
              versioned-storage cache-storage :fn-schema schema-id)))
        (when (= entity-name :arg-schema)
          (doseq [schema-id entity-ids]
            (inv/invalidate-entity-dependents!
              versioned-storage cache-storage :arg-schema schema-id)))))))


(defn merge-branch!
  "Merges source branch into current branch with cache invalidation.

   Delegates to versioned-storage merge-branch!, then invalidates
   caches for all entities that may have changed resolution on the
   target branch.

   Arguments: same as versioned-storage/merge-branch!"
  ([cached-storage source-branch-id]
   (merge-branch! cached-storage source-branch-id {}))
  ([cached-storage source-branch-id opts]
   (let [{:keys [versioned cache]} (extract-components cached-storage)
         target-branch-id (:branch-id versioned)
         result (vs/merge-branch! versioned source-branch-id opts)]
     (invalidate-all-affected-entities!
       versioned cache source-branch-id target-branch-id)
     result)))


(defn switch-branch
  "Switches to a different branch, returning a new CachedStorage wrapping
   a VersionedStorage on the new branch.

   Uses the same cache-storage instance (caches are keyed by fn-id, and
   the execution graph content depends on branch context, so existing
   caches may be stale). Clears all caches to prevent stale reads."
  [cached-storage branch-id]
  (let [{:keys [versioned cache]} (extract-components cached-storage)
        new-versioned (vs/switch-branch versioned branch-id)]
    ;; Clear all caches — execution graphs may resolve differently on the new branch
    ;; We find and delete all cached fn-ids
    (let [all-fns (sp/query-entities new-versioned :fn {})
          fn-ids (map :id all-fns)]
      (doseq [fn-id fn-ids]
        (cache/delete-cache! cache fn-id)))
    (cs/wrap-with-cache new-versioned cache)))


(defn delete-branch!
  "Deletes a branch with cache invalidation.

   Delegates to versioned-storage delete-branch!, then clears all caches
   since branch merge records referencing the deleted branch are removed."
  [cached-storage branch-id]
  (let [{:keys [versioned cache]} (extract-components cached-storage)
        result (vs/delete-branch! versioned branch-id)
        all-fns (sp/query-entities versioned :fn {})
        fn-ids (map :id all-fns)]
    ;; Clear all caches since merge records may have been removed,
    ;; changing resolution for entities on remaining branches
    (doseq [fn-id fn-ids]
      (cache/delete-cache! cache fn-id))
    result))


;; === Convenience Wrappers ===
;; These delegate to versioned-storage functions that don't need cache invalidation

(defn create-branch!
  "Creates a new branch. No cache invalidation needed.
   See versioned-storage/create-branch! for details."
  ([cached-storage branch-name]
   (create-branch! cached-storage branch-name {}))
  ([cached-storage branch-name opts]
   (let [{:keys [versioned]} (extract-components cached-storage)]
     (vs/create-branch! versioned branch-name opts))))


(defn current-branch-id
  "Returns the current branch-id."
  [cached-storage]
  (let [{:keys [versioned]} (extract-components cached-storage)]
    (vs/current-branch-id versioned)))


(defn list-branches
  "Lists all branches."
  [cached-storage]
  (let [{:keys [versioned]} (extract-components cached-storage)]
    (vs/list-branches versioned)))


(defn get-branch
  "Returns branch record by id, or nil."
  [cached-storage branch-id]
  (let [{:keys [versioned]} (extract-components cached-storage)]
    (vs/get-branch versioned branch-id)))


(defn detect-conflicts
  "Finds entities modified in both source and target branches after fork point."
  [cached-storage source-branch-id]
  (let [{:keys [versioned]} (extract-components cached-storage)]
    (vs/detect-conflicts versioned source-branch-id)))

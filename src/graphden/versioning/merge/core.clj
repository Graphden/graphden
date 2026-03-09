(ns graphden.versioning.merge.core
  "Merge protection for args with sensitive values.

   When merging branches, args marked with the 'merge-protected' trait
   should not be transferred from source to target branch. This module provides:

   - Detection of protected args that would be transferred during merge
   - Validation that prevents merge of protected values
   - Integration with versioned-storage merge flow

   Use cases:
   - Production database credentials should stay on production branch only
   - Environment-specific secrets shouldn't leak across branches
   - Sensitive data isolation between branches

   ## 2-Entity Schema

   In the 2-entity schema:
   - arg: stores value directly in the entity
   - value-trait: marks specific arg-ids as merge-protected

   Usage:
   1. Mark args with merge-protected trait using value-traits-schema
   2. Before merging, call detect-protected-transfers to find violations
   3. Either block merge or exclude protected values from transfer"
  (:require
    [clojure.set :as set]
    [graphden.schema.traits.schema :as vts]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]))


(defn- get-merge-protected-arg-ids
  "Returns set of arg-ids that have the merge-protected trait."
  [base-storage]
  (let [arg-traits (sp/query-entities base-storage :arg-trait
                                      {:trait-id vts/merge-protected-trait-uuid})]
    (set (map :arg-id arg-traits))))


(defn- find-transferred-arg-ids
  "Finds arg-ids referenced by versions that would become visible on target.

   A version is 'transferred' when:
   - It exists only on source branch (not on target)
   - It will become visible on target after merge via the branch-merge record

   Checks arg-version table."
  [base-storage source-branch-id target-branch-id]
  (let [;; Get all arg-versions on source branch
        source-arg-versions (sp/query-entities base-storage :arg-version
                                               {:branch-id source-branch-id})
        ;; Get all arg-versions on target branch
        target-arg-versions (sp/query-entities base-storage :arg-version
                                               {:branch-id target-branch-id})
        target-arg-ids (set (map :arg-id target-arg-versions))

        ;; Versions that exist only on source (would be transferred)
        transferred-arg-versions (remove #(contains? target-arg-ids (:arg-id %))
                                         source-arg-versions)]

    (set (map :arg-id transferred-arg-versions))))


(defn detect-protected-transfers
  "Detects merge-protected args that would be transferred during merge.

   Returns a map:
   {:protected-transfers [{:arg-id uuid
                           :version <version record>}]
    :blocked? boolean}

   If :protected-transfers is non-empty, merge should be blocked or handled."
  [versioned-storage source-branch-id]
  (let [base-storage (vs/unwrap versioned-storage)
        target-branch-id (vs/current-branch-id versioned-storage)
        protected-ids (get-merge-protected-arg-ids base-storage)

        ;; Only check if there are any protected values
        transfers (when (seq protected-ids)
                    (let [transferred-ids (find-transferred-arg-ids
                                            base-storage source-branch-id target-branch-id)
                          violations (set/intersection protected-ids transferred-ids)]

                      ;; Find which versions reference these protected args
                      (when (seq violations)
                        (let [source-arg-versions
                              (sp/query-entities base-storage :arg-version
                                                 {:branch-id source-branch-id})

                              arg-transfers
                              (for [v source-arg-versions
                                    :when (contains? violations (:arg-id v))]
                                {:arg-id (:arg-id v)
                                 :entity-type :arg
                                 :version v})]
                          (vec arg-transfers)))))]

    {:protected-transfers (or transfers [])
     :blocked? (boolean (seq transfers))}))


(defn validate-merge!
  "Validates that merge doesn't transfer merge-protected args.
   Throws if protected values would be transferred."
  [versioned-storage source-branch-id]
  (let [{:keys [protected-transfers blocked?]}
        (detect-protected-transfers versioned-storage source-branch-id)]
    (when blocked?
      (throw (ex-info "Merge blocked: protected args would be transferred"
                      {:type :merge-protection-violation
                       :protected-transfers protected-transfers
                       :source-branch-id source-branch-id
                       :target-branch-id (vs/current-branch-id versioned-storage)})))))


(defn safe-merge-branch!
  "Merges source branch into target with merge-protection validation.

   Same as vs/merge-branch! but first validates that no merge-protected
   args would be transferred.

   Arguments:
   - versioned-storage: VersionedStorage instance (target branch)
   - source-branch-id: Branch to merge from
   - opts: Optional map with :conflict-resolutions, :skip-protection-check

   Throws if:
   - Merge-protected values would be transferred (unless :skip-protection-check)
   - Unresolved conflicts exist

   Returns the branch-merge record."
  ([versioned-storage source-branch-id]
   (safe-merge-branch! versioned-storage source-branch-id {}))
  ([versioned-storage source-branch-id {:keys [skip-protection-check] :as opts}]
   (when-not skip-protection-check
     (validate-merge! versioned-storage source-branch-id))
   (vs/merge-branch! versioned-storage source-branch-id
                     (dissoc opts :skip-protection-check))))


(defn has-merge-protected-trait?
  "Returns true if the given arg has the merge-protected trait."
  [storage arg-id]
  (let [base-storage (if (vs/versioned-storage? storage)
                       (vs/unwrap storage)
                       storage)]
    (boolean (seq (sp/query-entities base-storage :arg-trait
                                     {:arg-id arg-id
                                      :trait-id vts/merge-protected-trait-uuid})))))


(defn add-merge-protection!
  "Adds the merge-protected trait to an arg.
   Idempotent - safe to call multiple times."
  [storage arg-id]
  (let [base-storage (if (vs/versioned-storage? storage)
                       (vs/unwrap storage)
                       storage)]
    ;; First ensure the trait exists (call seed if needed)
    (vts/seed-traits! base-storage)
    ;; Check if already protected
    (when-not (has-merge-protected-trait? base-storage arg-id)
      (sp/create-entity base-storage :arg-trait
                        {:arg-id arg-id
                         :trait-id vts/merge-protected-trait-uuid}))))


(defn remove-merge-protection!
  "Removes the merge-protected trait from an arg."
  [storage arg-id]
  (let [base-storage (if (vs/versioned-storage? storage)
                       (vs/unwrap storage)
                       storage)
        arg-traits (sp/query-entities base-storage :arg-trait
                                      {:arg-id arg-id
                                       :trait-id vts/merge-protected-trait-uuid})]
    (doseq [at arg-traits]
      (sp/delete-entity base-storage :arg-trait (:id at)))
    (boolean (seq arg-traits))))

(ns graphden.versioning.merge.interface
  "Merge protection for arg-values with the merge-protected trait.

   When merging branches, arg-values marked with the 'merge-protected' trait
   should not be transferred from source to target branch. This module provides:

   - Detection of protected arg-values that would be transferred during merge
   - Validation that prevents merge of protected values
   - Integration with versioned-storage merge flow

   Use cases:
   - Production database credentials should stay on production branch only
   - Environment-specific secrets shouldn't leak across branches
   - Sensitive data isolation between branches

   Usage:
   1. Mark arg-values with merge-protected trait using value-traits-schema
   2. Before merging, call detect-protected-transfers to find violations
   3. Either block merge or exclude protected values from transfer"
  (:require
    [clojure.set]
    [graphden.schema.traits.interface :as vts]
    [graphden.storage.protocol.interface :as sp]
    [graphden.versioning.storage.interface :as vs]))


(defn- get-merge-protected-arg-value-ids
  "Returns set of arg-value-ids that have the merge-protected trait."
  [base-storage]
  (let [value-traits (sp/query-entities base-storage :value-trait
                                        {:trait-id vts/merge-protected-trait-uuid})]
    (set (map :arg-value-id value-traits))))


(defn- find-transferred-arg-value-ids
  "Finds arg-value-ids referenced by versions that would become visible on target.

   A version is 'transferred' when:
   - It exists only on source branch (not on target)
   - It will become visible on target after merge via the branch-merge record

   Checks fn-arg-version and call-site-arg-version tables."
  [base-storage source-branch-id target-branch-id]
  (let [;; Get all fn-arg-versions on source branch
        source-fn-arg-versions (sp/query-entities base-storage :fn-arg-version
                                                  {:branch-id source-branch-id})
        ;; Get all fn-arg-versions on target branch
        target-fn-arg-versions (sp/query-entities base-storage :fn-arg-version
                                                  {:branch-id target-branch-id})
        target-fn-arg-ids (set (map :fn-arg-id target-fn-arg-versions))

        ;; Versions that exist only on source (would be transferred)
        transferred-fn-arg-versions (remove #(contains? target-fn-arg-ids (:fn-arg-id %))
                                            source-fn-arg-versions)

        ;; Same for call-site-arg-versions
        source-csa-versions (sp/query-entities base-storage :call-site-arg-version
                                               {:branch-id source-branch-id})
        target-csa-versions (sp/query-entities base-storage :call-site-arg-version
                                               {:branch-id target-branch-id})
        target-csa-ids (set (map :call-site-arg-id target-csa-versions))

        transferred-csa-versions (remove #(contains? target-csa-ids (:call-site-arg-id %))
                                         source-csa-versions)

        ;; Collect all arg-value-ids from transferred versions
        fn-arg-value-ids (keep :arg-value-id transferred-fn-arg-versions)
        csa-value-ids (keep :arg-value-id transferred-csa-versions)]
    (set (concat fn-arg-value-ids csa-value-ids))))


(defn detect-protected-transfers
  "Detects merge-protected arg-values that would be transferred during merge.

   Returns a map:
   {:protected-transfers [{:arg-value-id uuid
                           :entity-type :fn-arg | :call-site-arg
                           :version <version record>}]
    :blocked? boolean}

   If :protected-transfers is non-empty, merge should be blocked or handled."
  [versioned-storage source-branch-id]
  (let [base-storage (vs/unwrap versioned-storage)
        target-branch-id (vs/current-branch-id versioned-storage)
        protected-ids (get-merge-protected-arg-value-ids base-storage)

        ;; Only check if there are any protected values
        transfers (when (seq protected-ids)
                    (let [transferred-ids (find-transferred-arg-value-ids
                                            base-storage source-branch-id target-branch-id)
                          violations (clojure.set/intersection protected-ids transferred-ids)]

                      ;; Find which versions reference these protected values
                      (when (seq violations)
                        (let [source-fn-arg-versions
                              (sp/query-entities base-storage :fn-arg-version
                                                 {:branch-id source-branch-id})
                              source-csa-versions
                              (sp/query-entities base-storage :call-site-arg-version
                                                 {:branch-id source-branch-id})

                              fn-arg-transfers
                              (for [v source-fn-arg-versions
                                    :when (contains? violations (:arg-value-id v))]
                                {:arg-value-id (:arg-value-id v)
                                 :entity-type :fn-arg
                                 :entity-id (:fn-arg-id v)
                                 :version v})

                              csa-transfers
                              (for [v source-csa-versions
                                    :when (contains? violations (:arg-value-id v))]
                                {:arg-value-id (:arg-value-id v)
                                 :entity-type :call-site-arg
                                 :entity-id (:call-site-arg-id v)
                                 :version v})]
                          (vec (concat fn-arg-transfers csa-transfers))))))]

    {:protected-transfers (or transfers [])
     :blocked? (boolean (seq transfers))}))


(defn validate-merge!
  "Validates that merge doesn't transfer merge-protected arg-values.
   Throws if protected values would be transferred."
  [versioned-storage source-branch-id]
  (let [{:keys [protected-transfers blocked?]}
        (detect-protected-transfers versioned-storage source-branch-id)]
    (when blocked?
      (throw (ex-info "Merge blocked: protected arg-values would be transferred"
                      {:type :merge-protection-violation
                       :protected-transfers protected-transfers
                       :source-branch-id source-branch-id
                       :target-branch-id (vs/current-branch-id versioned-storage)})))))


(defn safe-merge-branch!
  "Merges source branch into target with merge-protection validation.

   Same as vs/merge-branch! but first validates that no merge-protected
   arg-values would be transferred.

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
  "Returns true if the given arg-value has the merge-protected trait."
  [storage arg-value-id]
  (let [base-storage (if (vs/versioned-storage? storage)
                       (vs/unwrap storage)
                       storage)]
    (boolean (seq (sp/query-entities base-storage :value-trait
                                     {:arg-value-id arg-value-id
                                      :trait-id vts/merge-protected-trait-uuid})))))


(defn add-merge-protection!
  "Adds the merge-protected trait to an arg-value.
   Idempotent - safe to call multiple times."
  [storage arg-value-id]
  (let [base-storage (if (vs/versioned-storage? storage)
                       (vs/unwrap storage)
                       storage)]
    ;; First ensure the trait exists (call seed if needed)
    (vts/seed-traits! base-storage)
    ;; Check if already protected
    (when-not (has-merge-protected-trait? base-storage arg-value-id)
      (sp/create-entity base-storage :value-trait
                        {:arg-value-id arg-value-id
                         :trait-id vts/merge-protected-trait-uuid}))))


(defn remove-merge-protection!
  "Removes the merge-protected trait from an arg-value."
  [storage arg-value-id]
  (let [base-storage (if (vs/versioned-storage? storage)
                       (vs/unwrap storage)
                       storage)
        value-traits (sp/query-entities base-storage :value-trait
                                        {:arg-value-id arg-value-id
                                         :trait-id vts/merge-protected-trait-uuid})]
    (doseq [vt value-traits]
      (sp/delete-entity base-storage :value-trait (:id vt)))
    (boolean (seq value-traits))))

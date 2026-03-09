(ns graphden.versioning.storage.resolution
  "Version resolution algorithm for branch-aware entity reads.

   Implements the version resolution algorithm:
   1. Find latest own version on this branch
   2. Check branch-merge records for incoming merges
   3. For each merge, find latest version in source branch
   4. Pick candidate with greatest effective timestamp
   5. If nothing found, recurse to parent branch (base-branch-id)

   ## 2-Entity Schema

   Only two entities are versioned:
   - fn: function entity (parent-id for inheritance)
   - arg: argument entity (source-id for inheritance, value/ref-id for data)"
  (:require
    [graphden.storage.protocol.core :as sp]))


;; === Entity Configuration ===
;;
;; Maps each versioned base entity to its version table metadata.
;; version-data-fields: the fields that live in the version table (mutable per branch).
;; Everything else (minus :id) stays in the identity table (immutable).

(def entity-config
  "Configuration for versioned entities.
   Maps base entity name to version table metadata."
  {:fn {:version-entity :fn-version
        :version-id-field :fn-id
        :version-data-fields #{:name :parent-id :return-type :impl-hash}}

   :arg {:version-entity :arg-version
         :version-id-field :arg-id
         :version-data-fields #{:fn-id :name :type :source-id :value :ref-id :is-fn :required}}})


(defn versioned-entity?
  "Returns true if entity-name is a versioned entity that needs CRUD interception."
  [entity-name]
  (contains? entity-config entity-name))


;; === Resolution Helpers ===

(defn- latest-by-created-at
  "Returns the record with the latest :created-at, or nil if empty."
  [records]
  (when (seq records)
    (reduce (fn [best r]
              (if (pos? (compare (:created-at r) (:created-at best))) r best))
            (first records)
            (rest records))))


(defn- extract-version-data
  "Extracts data fields from a version record, stripping version metadata."
  [version-record version-id-field]
  (dissoc version-record :id :branch-id :created-at version-id-field))


;; === Core Resolution Algorithm ===

(defn resolve-version
  "Resolves the current version of an entity on a branch.

   Algorithm:
   1. Find latest own version on this branch
   2. Find merges into this branch (branch-merge with target-branch-id = branch-id)
   3. For each merge after our own version, find latest source version
   4. Pick candidate with greatest effective timestamp
   5. If nothing found, recurse to parent branch

   Returns the version record or nil."
  [base-storage entity-name entity-id branch-id]
  (let [{:keys [version-entity version-id-field]} (get entity-config entity-name)

        ;; Step 1: Find latest own version on this branch
        own-versions (sp/query-entities base-storage version-entity
                                        {version-id-field entity-id :branch-id branch-id})
        own-latest (latest-by-created-at own-versions)

        ;; Step 2: Find merges into this branch
        merges (sp/query-entities base-storage :branch-merge
                                  {:target-branch-id branch-id})

        ;; Step 3: For each merge, check source branch for versions
        merge-candidates
        (for [m merges
              :let [src-versions (sp/query-entities base-storage version-entity
                                                    {version-id-field entity-id
                                                     :branch-id (:source-branch-id m)})
                    ;; Only versions created at or before source-timestamp
                    eligible (filter #(not (pos? (compare (:created-at %)
                                                          (:source-timestamp m))))
                                     src-versions)
                    best (latest-by-created-at eligible)]
              :when best
              ;; Only consider merge if it happened after our own latest version
              :when (or (nil? own-latest)
                        (pos? (compare (:target-timestamp m)
                                       (:created-at own-latest))))]
          {:version best :effective-ts (:target-timestamp m)})

        ;; Step 4: Pick best candidate overall
        all-candidates (cond-> []
                         own-latest
                         (conj {:version own-latest
                                :effective-ts (:created-at own-latest)})
                         (seq merge-candidates)
                         (into merge-candidates))
        best (when (seq all-candidates)
               (:version (reduce (fn [a b]
                                   (if (pos? (compare (:effective-ts b)
                                                      (:effective-ts a)))
                                     b a))
                                 (first all-candidates)
                                 (rest all-candidates))))]

    (or best
        ;; Step 5: Recurse to parent branch
        (when-let [branch (sp/read-entity base-storage :branch branch-id)]
          (when-let [parent-id (:base-branch-id branch)]
            (resolve-version base-storage entity-name entity-id parent-id))))))


;; === High-Level Resolution Functions ===

(defn resolve-entity
  "Resolves a versioned entity by merging identity and version data.
   Returns the merged entity record, or nil if entity has no version on this branch."
  [base-storage entity-name entity-id branch-id]
  (when-let [identity-rec (sp/read-entity base-storage entity-name entity-id)]
    (let [{:keys [version-id-field]} (get entity-config entity-name)]
      (when-let [version (resolve-version base-storage entity-name entity-id branch-id)]
        (merge identity-rec (extract-version-data version version-id-field))))))


(defn resolve-all-entities
  "Resolves all entities of a type visible on the current branch.
   Filters by where clause after resolution.
   Returns sequence of merged entity records."
  [base-storage entity-name branch-id where]
  (let [{:keys [version-id-field]} (get entity-config entity-name)
        all-identities (sp/query-entities base-storage entity-name {})
        resolved (keep (fn [identity-rec]
                         (when-let [version (resolve-version base-storage entity-name
                                                             (:id identity-rec) branch-id)]
                           (merge identity-rec
                                  (extract-version-data version version-id-field))))
                       all-identities)]
    (if (empty? where)
      resolved
      (filter (fn [record]
                (every? (fn [[k v]] (= (get record k) v)) where))
              resolved))))

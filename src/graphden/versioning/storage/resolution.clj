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
    [clojure.set :as set]
    [graphden.storage.protocol.core :as sp]))


;; === Branch Chain Cache ===
;; Thread-local cache for branch chains within a request.
;; Prevents N+1 queries when resolving multiple entity types.

(def ^:dynamic *branch-chain-cache*
  "Thread-local cache: {branch-id -> [branch-chain]}"
  nil)


;; Forward declarations for batch resolution functions used by resolve-all-entities
(declare resolve-entities-batch)
(declare collect-branch-chain)
(declare resolve-version-from-cache)


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
   3. Batch load versions from all source branches, filter by timestamp
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

        ;; Step 3: Batch load versions from ALL source branches in single query
        merge-candidates
        (when (seq merges)
          (let [source-branch-ids (mapv :source-branch-id merges)
                ;; Single batch query for all source branches
                all-src-versions (sp/query-entities base-storage version-entity
                                                    {version-id-field entity-id
                                                     :branch-id source-branch-ids})
                ;; Index by branch-id for O(1) lookup
                src-versions-by-branch (group-by :branch-id all-src-versions)]
            (for [m merges
                  :let [branch-versions (get src-versions-by-branch (:source-branch-id m) [])
                        ;; Only versions created at or before source-timestamp
                        eligible (filter #(not (pos? (compare (:created-at %)
                                                              (:source-timestamp m))))
                                         branch-versions)
                        best (latest-by-created-at eligible)]
                  :when best
                  ;; Only consider merge if it happened after our own latest version
                  :when (or (nil? own-latest)
                            (pos? (compare (:target-timestamp m)
                                           (:created-at own-latest))))]
              {:version best :effective-ts (:target-timestamp m)})))

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
   Returns sequence of merged entity records.

   Only returns entities that have at least one version visible on the branch chain.
   This is different from resolve-entities-batch which returns all entities.

   Optimized: uses batch version loading instead of N+1 queries."
  [base-storage entity-name branch-id where]
  (let [{:keys [version-entity version-id-field]} (get entity-config entity-name)
        branch-chain (collect-branch-chain base-storage branch-id)
        ;; Load versions on branch chain first - this tells us which entities are visible
        all-versions (sp/query-entities base-storage version-entity
                                        {:branch-id (vec branch-chain)})
        ;; Get unique entity IDs that have versions on this branch chain
        entity-ids-with-versions (into #{} (map version-id-field) all-versions)
        ;; Load only the identity records for entities that have versions
        identities-map (if (empty? entity-ids-with-versions)
                         {}
                         (sp/read-entities base-storage entity-name
                                           (vec entity-ids-with-versions)))
        ;; Group versions by entity-id for resolution
        versions-by-id (group-by version-id-field all-versions)
        ;; Resolve each entity
        resolved (for [[eid identity-rec] identities-map
                       :let [version (resolve-version-from-cache versions-by-id eid branch-chain)]
                       :when version]
                   (merge identity-rec (extract-version-data version version-id-field)))]
    (if (empty? where)
      resolved
      ;; Pre-convert collection values to sets once, not per-record
      (let [where-prepared (into {}
                                 (map (fn [[k v]]
                                        [k (if (and (coll? v) (not (map? v)))
                                             (set v)
                                             v)]))
                                 where)]
        (filter (fn [record]
                  (every? (fn [[k prepared-v]]
                            (let [rv (get record k)]
                              (if (set? prepared-v)
                                (contains? prepared-v rv)
                                (= rv prepared-v))))
                          where-prepared))
                resolved)))))


;; === Batch Resolution for ExecutionGraph ===
;;
;; Optimized batch resolution: instead of N+1 queries (one per entity),
;; we load all versions in a single query and resolve in memory.

(defn- collect-branch-chain-impl
  "Internal: fetches branch chain from storage."
  [base-storage branch-id]
  (loop [chain [branch-id]
         current-id branch-id]
    (if-let [branch (sp/read-entity base-storage :branch current-id)]
      (if-let [parent-id (:base-branch-id branch)]
        (recur (conj chain parent-id) parent-id)
        chain)
      chain)))


(defn- collect-branch-chain
  "Returns vector of branch-ids from current to root (for inheritance lookup).
   Uses thread-local cache when *branch-chain-cache* is bound."
  [base-storage branch-id]
  (if *branch-chain-cache*
    ;; Use cached value if available
    (if-let [cached (get @*branch-chain-cache* branch-id)]
      cached
      (let [chain (collect-branch-chain-impl base-storage branch-id)]
        (swap! *branch-chain-cache* assoc branch-id chain)
        chain))
    ;; No cache - compute directly
    (collect-branch-chain-impl base-storage branch-id)))


(defn- load-all-versions-for-ids
  "Loads all version records for given entity-ids on the branch chain.
   Returns map: {entity-id -> [version-record, ...]}"
  [base-storage entity-name entity-ids branch-chain]
  (let [{:keys [version-entity version-id-field]} (get entity-config entity-name)]
    (if (empty? entity-ids)
      {}
      ;; Load versions using WHERE IN for entity-ids and branch-ids
      (let [versions (sp/query-entities base-storage version-entity
                                        {version-id-field (vec entity-ids)
                                         :branch-id (vec branch-chain)})]
        (group-by version-id-field versions)))))


(defn- resolve-version-from-cache
  "Resolves version for an entity from pre-loaded versions map.
   Uses simplified algorithm (no merge support - just branch chain priority).

   Optimization: Index versions by branch-id for O(1) lookup per branch instead of O(n) filter."
  [versions-by-id entity-id branch-chain]
  (let [versions (get versions-by-id entity-id)]
    (when (seq versions)
      ;; Index by branch-id once, then O(1) lookup per branch in chain
      (let [by-branch (group-by :branch-id versions)]
        (some (fn [bid]
                (when-let [on-branch (get by-branch bid)]
                  (latest-by-created-at on-branch)))
              branch-chain)))))


(defn resolve-entities-batch
  "Batch resolves entities by merging identity records with version data.
   Much faster than calling resolve-entity for each id.

   Arguments:
   - base-storage: Base storage (not versioned)
   - entity-name: :fn or :arg
   - identity-records: Collection of identity records (from base-storage)
   - branch-id: Current branch id

   Returns: map {entity-id -> merged-record} for ALL entities.
   Entities with versions get merged data, entities without versions
   return identity record as-is (e.g., base functions without compositions)."
  [base-storage entity-name identity-records branch-id]
  (if (empty? identity-records)
    {}
    (let [{:keys [version-id-field]} (get entity-config entity-name)
          entity-ids (mapv :id identity-records)
          branch-chain (collect-branch-chain base-storage branch-id)
          versions-by-id (load-all-versions-for-ids base-storage entity-name
                                                    entity-ids branch-chain)
          identity-by-id (into {} (map (fn [r] [(:id r) r])) identity-records)]
      (into {}
            (map (fn [eid]
                   (let [identity-rec (get identity-by-id eid)]
                     (if-let [version (resolve-version-from-cache versions-by-id eid branch-chain)]
                       ;; Has version - merge identity + version data
                       [eid (merge identity-rec
                                   (extract-version-data version version-id-field))]
                       ;; No version - return identity as-is
                       [eid identity-rec])))
                 entity-ids)))))


;; === Batch Execution Graph Resolution ===
;;
;; Optimized algorithm that loads ALL data in 4 queries, then does BFS in memory.
;; This avoids the N+1 query problem of generic BFS.

(defn- load-all-resolved-fns
  "Loads all fn records and resolves versions in memory.
   Returns map {fn-id -> resolved-fn-record}.

   Optimized: uses WHERE IN for branch-chain instead of full scan + filter."
  [base-storage branch-id]
  (let [all-identities (sp/query-entities base-storage :fn {})
        branch-chain (collect-branch-chain base-storage branch-id)
        {:keys [version-id-field]} (get entity-config :fn)
        ;; Use WHERE IN for branch-chain instead of full table scan
        relevant-versions (sp/query-entities base-storage :fn-version
                                             {:branch-id (vec branch-chain)})
        versions-by-id (group-by :fn-id relevant-versions)]
    (into {}
          (map (fn [identity-rec]
                 (let [eid (:id identity-rec)]
                   (if-let [version (resolve-version-from-cache versions-by-id eid branch-chain)]
                     [eid (merge identity-rec
                                 (extract-version-data version version-id-field))]
                     [eid identity-rec]))))
          all-identities)))


(defn- load-all-resolved-args
  "Loads all arg records and resolves versions in memory.
   Returns map {arg-id -> resolved-arg-record}.

   Optimized: uses WHERE IN for branch-chain instead of full scan + filter."
  [base-storage branch-id]
  (let [all-identities (sp/query-entities base-storage :arg {})
        branch-chain (collect-branch-chain base-storage branch-id)
        {:keys [version-id-field]} (get entity-config :arg)
        ;; Use WHERE IN for branch-chain instead of full table scan
        relevant-versions (sp/query-entities base-storage :arg-version
                                             {:branch-id (vec branch-chain)})
        versions-by-id (group-by :arg-id relevant-versions)]
    (into {}
          (map (fn [identity-rec]
                 (let [eid (:id identity-rec)]
                   (if-let [version (resolve-version-from-cache versions-by-id eid branch-chain)]
                     [eid (merge identity-rec
                                 (extract-version-data version version-id-field))]
                     [eid identity-rec]))))
          all-identities)))


(defn- extract-fn-refs-from-args
  "Extracts fn-ids referenced in args.
   Returns set of fn-ids."
  [args]
  (->> args
       (mapcat (fn [arg]
                 (cond-> []
                   (some? (:ref-id arg)) (conj (:ref-id arg))
                   (and (some? (:value arg)) (uuid? (:value arg))) (conj (:value arg)))))
       (remove nil?)
       (set)))


(defn- build-args-index
  "Builds index of fn-id -> [args] from resolved args map."
  [resolved-args-map]
  (reduce-kv
    (fn [acc _arg-id arg]
      (update acc (:fn-id arg) (fnil conj []) arg))
    {}
    resolved-args-map))


(defn resolve-execution-graph-batch
  "Batch resolves execution graph using in-memory BFS.

   Algorithm:
   1. Load ALL fn identity + versions (2 queries)
   2. Load ALL arg identity + versions (2 queries)
   3. Resolve versions in memory
   4. BFS traversal on resolved data

   This reduces ~400 queries to 4 queries.

   Arguments:
   - base-storage: Base storage (not VersionedStorage)
   - fn-id: Starting function UUID
   - branch-id: Current branch id

   Returns: ExecutionGraphResult record (from graph.clj)."
  [base-storage fn-id branch-id]
  (let [;; Load all resolved data (4 queries total)
        all-fns (load-all-resolved-fns base-storage branch-id)
        all-args-map (load-all-resolved-args base-storage branch-id)
        args-by-fn (build-args-index all-args-map)

        ;; BFS traversal in memory
        result (loop [to-visit #{fn-id}
                      visited #{fn-id}
                      fns {}
                      args []
                      iter-count 0]
                 (when (> iter-count 10000)
                   (throw (ex-info "Execution graph resolution exceeded maximum iterations"
                                   {:type :execution-error/graph-too-large
                                    :fn-id fn-id
                                    :iteration-count iter-count})))
                 (if (empty? to-visit)
                   {:fns fns :args args}
                   (let [current-fn-id (first to-visit)
                         rest-to-visit (disj to-visit current-fn-id)]
                     (if-let [fn-rec (get all-fns current-fn-id)]
                       (let [fn-args (get args-by-fn current-fn-id [])
                             new-fn-refs (extract-fn-refs-from-args fn-args)
                             parent-ref (when-let [parent-id (:parent-id fn-rec)]
                                          #{parent-id})
                             all-refs (set/union new-fn-refs (or parent-ref #{}))
                             new-to-visit (set/difference all-refs visited)
                             new-visited (set/union visited new-to-visit)]
                         (recur (set/union rest-to-visit new-to-visit)
                                new-visited
                                (assoc fns current-fn-id fn-rec)
                                (into args fn-args)
                                (inc iter-count)))
                       ;; fn not found - skip
                       (recur rest-to-visit visited fns args (inc iter-count))))))]
    ;; Return ExecutionGraphResult-compatible structure
    result))

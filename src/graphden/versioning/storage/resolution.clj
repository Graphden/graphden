(ns graphden.versioning.storage.resolution
  "Version resolution for branch-aware entity reads in the
   slot/fn-slot/binding model.

   Versioned entities (mutable):
     fn → fn-version
     fn-slot → fn-slot-version
     binding → binding-version
     binding-list-item → binding-list-item-version

   `slot` is intentionally NOT versioned (immutable post-create).

   Algorithm: find own latest version on the branch, fall back through
   branch-merge records, then recurse to the parent branch."
  (:require
    [clojure.set :as set]
    [graphden.storage.protocol.core :as sp]))


;; === Branch Chain Cache ===
;;
;; Branch chains are immutable for a branch's lifetime: `:base-branch-id`
;; is set at branch-creation and never updated, and a branch with
;; child branches can't be deleted. So once we've walked
;; `b -> parent -> ... -> root` once, the result stays valid until
;; `delete-branch!` removes one of those nodes.
;;
;; A process-wide atom replaces the old per-request dynamic-var
;; binding: chain walks cross HTTP requests (every versioned CRUD
;; call on a non-default branch walks the chain), so the cache pays
;; off most when shared across calls. The dynamic var is kept as an
;; OPTIONAL override — when bound it wins over the global, so test
;; setups that need an isolated cache can still scope one. Standard
;; CRUD paths simply omit the binding and hit the global cache.

(defonce ^:private global-chain-cache
  ;; {branch-id -> [branch-id, ..., root-id]}. Cleared on branch
  ;; delete + on the test harness's database-wipe.
  (atom {}))


(def ^:dynamic *branch-chain-cache*
  "Optional per-call override: when bound to an atom, `collect-branch-chain`
   uses it instead of the global cache. Test harnesses can bind this
   when they need to observe / clear the cache without disturbing
   other concurrent calls."
  nil)


(defn invalidate-chain-cache!
  "Drop cached chains for a branch (or all branches when called with
   no args). Call after `delete-branch!` to clear stale entries that
   referenced the deleted branch as an ancestor.

   Also called by the test harness after wiping the DB so cached
   chains don't survive into a fresh schema. UUID collisions across
   test cycles are essentially impossible, but the cache also grows
   unboundedly without periodic clearing — better to drop it on
   schema reset."
  ([] (reset! global-chain-cache {}))
  ([branch-id]
   ;; Drop the branch itself + any cached chain that included it
   ;; (i.e. any descendant branch's chain).
   (swap! global-chain-cache
          (fn [m]
            (into {} (remove (fn [[_ chain]]
                               (some #(= % branch-id) chain)))
                  m)))))


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
  "Configuration for versioned entities. Maps base entity name to
   version table metadata.

   Notes:
   - `:parent-ids` is NOT in fn version-data-fields — it's a :ref-many
     stored in a junction table, not versioned.
   - `:slot` is intentionally absent: slots are immutable post-create
     (changing the (name, type-fn-id) pair = creating a new slot)."
  {:fn {:version-entity :fn-version
        :version-id-field :fn-id
        :version-data-fields #{:name :impl-hash :description :constraint
                               :base-fn-id :element-fn-id :return-type-fn-id
                               :anonymous-hash :expects-effects}}

   :fn-slot {:version-entity :fn-slot-version
             :version-id-field :fn-slot-id
             :version-data-fields #{:fn-id :slot-id :position}}

   :binding {:version-entity :binding-version
             :version-id-field :binding-id
             :version-data-fields #{:fn-id :slot-id :value :ref-fn-id
                                    :override-kind
                                    :type-override-fn-id :description
                                    :list-append :list-closed}}

   :binding-list-item {:version-entity :binding-list-item-version
                       :version-id-field :item-id
                       :version-data-fields #{:binding-id :position :value
                                              :ref-fn-id :literal}}})


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

(defn- own-latest-version
  "Latest version record this branch wrote directly for the entity, or nil."
  [base-storage version-entity version-id-field entity-id branch-id]
  (latest-by-created-at
    (sp/query-entities base-storage version-entity
                       {version-id-field entity-id :branch-id branch-id})))


(defn- merge-candidates
  "For each branch-merge that lands on `branch-id`, return a
   `{:version :effective-ts}` candidate carrying the source branch's
   latest version that's still ≤ source-timestamp AND landed AFTER
   our own latest. Empty when no merges match."
  [base-storage version-entity version-id-field entity-id own-latest merges]
  (when (seq merges)
    (let [source-branch-ids (mapv :source-branch-id merges)
          ;; Single batch query for all source branches.
          all-src-versions (sp/query-entities base-storage version-entity
                                              {version-id-field entity-id
                                               :branch-id source-branch-ids})
          src-versions-by-branch (group-by :branch-id all-src-versions)]
      (for [m merges
            :let [branch-versions (get src-versions-by-branch
                                       (:source-branch-id m) [])
                  ;; Only versions created at or before source-timestamp.
                  eligible (filter #(not (pos? (compare (:created-at %)
                                                        (:source-timestamp m))))
                                   branch-versions)
                  best (latest-by-created-at eligible)]
            :when best
            ;; Only consider merge if it happened after our own latest.
            :when (or (nil? own-latest)
                      (pos? (compare (:target-timestamp m)
                                     (:created-at own-latest))))]
        {:version best :effective-ts (:target-timestamp m)}))))


(defn- pick-latest-candidate
  "Return the `:version` whose `:effective-ts` is greatest, or nil
   when the candidate seq is empty."
  [candidates]
  (when-let [candidates (seq candidates)]
    (:version (reduce (fn [a b]
                        (if (pos? (compare (:effective-ts b)
                                           (:effective-ts a)))
                          b
                          a))
                      (first candidates)
                      (rest candidates)))))


(defn- parent-branch-id
  "The base-branch-id of `branch-id`, or nil at the root.

   Backed by the same `global-chain-cache` as `collect-branch-chain`
   — the cached `[branch-id parent-id ...]` chain encodes parent for
   every ancestor in one walk. Avoids an `sp/read-entity` per
   recursive `resolve-version` step (3+ PG roundtrips per chain
   walk eliminated for a 3-deep branch hierarchy)."
  [base-storage branch-id]
  (let [chain (collect-branch-chain base-storage branch-id)]
    (when (> (count chain) 1)
      (second chain))))


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
        own-latest (own-latest-version base-storage version-entity
                                       version-id-field entity-id branch-id)
        merges (sp/query-entities base-storage :branch-merge
                                  {:target-branch-id branch-id})
        merge-cands (merge-candidates base-storage version-entity
                                      version-id-field entity-id
                                      own-latest merges)
        all-candidates (cond-> []
                         own-latest
                         (conj {:version own-latest
                                :effective-ts (:created-at own-latest)})
                         (seq merge-cands)
                         (into merge-cands))]
    (or (pick-latest-candidate all-candidates)
        (when-let [parent-id (parent-branch-id base-storage branch-id)]
          (resolve-version base-storage entity-name entity-id parent-id)))))


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
  "Returns vector of branch-ids from current to root (for inheritance
   lookup). When `*branch-chain-cache*` is bound it wins (test
   isolation); otherwise consults the process-wide
   `global-chain-cache`, populating on miss."
  [base-storage branch-id]
  (let [cache (or *branch-chain-cache* global-chain-cache)]
    (if-let [cached (get @cache branch-id)]
      cached
      (let [chain (collect-branch-chain-impl base-storage branch-id)]
        (swap! cache assoc branch-id chain)
        chain))))


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
   - entity-name: any versioned entity (see `entity-config`)
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
          identity-by-id (into {} (map (juxt :id identity)) identity-records)]
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

(defn- load-all-resolved
  "Loads all identity rows of `entity-name` and overlays the latest
   version on each. Returns a map `{id → resolved-row}`."
  [base-storage entity-name branch-id]
  (let [{:keys [version-entity version-id-field]} (get entity-config entity-name)
        all-identities (sp/query-entities base-storage entity-name {})
        branch-chain (collect-branch-chain base-storage branch-id)
        relevant-versions (sp/query-entities base-storage version-entity
                                             {:branch-id (vec branch-chain)})
        versions-by-id (group-by version-id-field relevant-versions)]
    (into {}
          (map (fn [identity-rec]
                 (let [eid (:id identity-rec)]
                   (if-let [version (resolve-version-from-cache versions-by-id eid branch-chain)]
                     [eid (merge identity-rec
                                 (extract-version-data version version-id-field))]
                     [eid identity-rec]))))
          all-identities)))


(defn- extract-fn-refs-from-bindings
  "Extracts fn-ids referenced via binding rows."
  [bindings]
  (->> bindings
       (mapcat (fn [b]
                 (cond-> []
                   (some? (:ref-fn-id b)) (conj (:ref-fn-id b))
                   (some? (:type-override-fn-id b)) (conj (:type-override-fn-id b)))))
       (remove nil?)
       set))


(defn- extract-fn-refs-from-list-items
  [items]
  (->> items
       (keep :ref-fn-id)
       set))


(defn- index-by
  [k coll]
  (reduce (fn [acc r]
            (update acc (get r k) (fnil conj []) r))
          {}
          coll))


(defn resolve-execution-graph-batch
  "Batch resolves execution graph using in-memory BFS over the
   slot/fn-slot/binding model. Loads fn / fn-slot / binding /
   binding-list-item identities + their version overlays in a
   constant number of queries, then walks the parent-ids and
   ref-fn-id graph from `fn-id`.

   Returns
     {:fns        {fn-id → fn-row}
      :slots      [slot-row …]            — all slots (small set)
      :fn-slots   [fn-slot-row …]         — junctions for visited fns
      :bindings   [binding-row …]         — bindings for visited fns
      :list-items [item-row …]            — items of visited bindings}"
  [base-storage fn-id branch-id]
  (let [all-fns        (load-all-resolved base-storage :fn branch-id)
        all-fn-slots   (load-all-resolved base-storage :fn-slot branch-id)
        all-bindings   (load-all-resolved base-storage :binding branch-id)
        all-items      (load-all-resolved base-storage :binding-list-item branch-id)
        ;; Slots are immutable so they're not versioned — query directly.
        slots          (sp/query-entities base-storage :slot {})
        fn-slots-by-fn (index-by :fn-id (vals all-fn-slots))
        bindings-by-fn (index-by :fn-id (vals all-bindings))
        items-by-binding (index-by :binding-id (vals all-items))
        slot-by-id     (into {} (map (juxt :id identity)) slots)
        collect-fn-refs
        (fn [fn-rec fn-fs fn-bs fn-items]
          (reduce into #{}
                  [(extract-fn-refs-from-bindings fn-bs)
                   (extract-fn-refs-from-list-items fn-items)
                   (set (remove nil? (:parent-ids fn-rec)))
                   (into #{} (keep #(get fn-rec %))
                         [:base-fn-id :element-fn-id :return-type-fn-id])
                   ;; Slots themselves reference fn-rows via :type-fn-id —
                   ;; pull those in too so type-checker / editor see the
                   ;; complete sub-graph.
                   (into #{} (keep #(:type-fn-id (slot-by-id (:slot-id %))))
                         fn-fs)]))]
    (loop [to-visit #{fn-id}
           visited #{fn-id}
           fns {}
           fn-slots-acc []
           bindings-acc []
           items-acc []
           iter-count 0]
      (when (> iter-count 10000)
        (throw (ex-info "Execution graph resolution exceeded maximum iterations"
                        {:type :execution-error/graph-too-large
                         :fn-id fn-id
                         :iteration-count iter-count})))
      (if (empty? to-visit)
        {:fns fns :slots slots :fn-slots fn-slots-acc
         :bindings bindings-acc :list-items items-acc}
        (let [current (first to-visit)
              rest-to-visit (disj to-visit current)]
          (if-let [fn-rec (get all-fns current)]
            (let [fn-fs (get fn-slots-by-fn current [])
                  fn-bs (get bindings-by-fn current [])
                  fn-binding-ids (set (map :id fn-bs))
                  fn-items (mapcat #(get items-by-binding % []) fn-binding-ids)
                  new-to-visit (set/difference
                                 (collect-fn-refs fn-rec fn-fs fn-bs fn-items)
                                 visited)]
              (recur (set/union rest-to-visit new-to-visit)
                     (set/union visited new-to-visit)
                     (assoc fns current fn-rec)
                     (into fn-slots-acc fn-fs)
                     (into bindings-acc fn-bs)
                     (into items-acc fn-items)
                     (inc iter-count)))
            (recur rest-to-visit visited fns fn-slots-acc bindings-acc
                   items-acc (inc iter-count))))))))

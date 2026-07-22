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
   branch-merge records, then recurse to the parent branch.

   Branch-local filter: fn rows whose effective `:branch-local?` is
   true (per `graphden.versioning.branch-local`) get foreign-branch
   merge candidates dropped. Identity remains visible on every
   branch (it's non-versioned), but the version data stays scoped
   to the originating branch — so runtime-config fn-defs (web-server
   with a dev port, vault path) don't leak across merges."
  (:require
    [clojure.set :as set]
    [graphden.schema.versioned.schema :as vts]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.branch-local :as bl]))


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
(declare load-merge-aware-cache)


;; === Entity Configuration ===
;;
;; Maps each versioned base entity to its version table metadata.
;; version-data-fields: the fields that live in the version table (mutable per branch).
;; Everything else (minus :id) stays in the identity table (immutable).

(def entity-config
  "Configuration for versioned entities. Maps base entity name to
   version table metadata.

   `:version-data-fields` is DERIVED (`vts/version-data-fields`) from the
   same base field map the `-version` mirror entity derives from — one
   source, so the decorator's select-keys can never silently disagree
   with the mirror's columns (the old hand-kept-triple footgun).
   `:candidate-bound-keys` stays hand-curated: it is a read-bounding
   OPTIMIZATION choice (which version-row columns are useful to bound a
   candidate query on), not derivable from the schema.

   Notes:
   - `:parent-ids` is NOT in fn version-data-fields — it's a :ref-many
     stored in a junction table, not versioned (declared identity-level
     in `vts/mirror-config`).
   - `:slot` is intentionally absent: slots are immutable post-create
     (changing the (name, type-fn-id) pair = creating a new slot)."
  {:fn {:version-entity :fn-version
        :version-id-field :fn-id
        :version-data-fields (vts/version-data-fields :fn)
        :candidate-bound-keys #{:name :anonymous-hash :base-fn-id
                                :element-fn-id :return-type-fn-id}}

   :fn-slot {:version-entity :fn-slot-version
             :version-id-field :fn-slot-id
             :version-data-fields (vts/version-data-fields :fn-slot)
             :candidate-bound-keys #{:fn-id :slot-id}}

   :binding {:version-entity :binding-version
             :version-id-field :binding-id
             :version-data-fields (vts/version-data-fields :binding)
             :candidate-bound-keys #{:fn-id :slot-id :ref-fn-id
                                     :type-override-fn-id}}

   :binding-list-item {:version-entity :binding-list-item-version
                       :version-id-field :item-id
                       :version-data-fields (vts/version-data-fields :binding-list-item)
                       :candidate-bound-keys #{:binding-id :ref-fn-id}}})


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

(defn- owning-fn-id
  "Find the fn-id whose `:branch-local?` flag governs filtering for
   a given version row. The fn itself is its own owner; `:fn-slot` +
   `:binding` version rows carry `:fn-id` in their data fields.
   `:binding-list-item` carries only `:binding-id`, so we read the
   binding IDENTITY row (its `:fn-id` is immutable) to chain up —
   otherwise an ITEM-ONLY edit on a branch-local fn's list arg
   (`:schedule` cron / `:env` multi-value) would leak across a merge:
   the binding itself resolves via ancestor inheritance (not filtered),
   but the source branch's new item slipped through unfiltered. That PK
   read only fires during MERGE-aware resolution of a list-item that
   has a merge candidate — a narrow, infrequent path, NOT a per-read
   hot loop. Returns nil when the entity exposes no owning fn."
  [base-storage entity-name entity-id version-row]
  (case entity-name
    :fn entity-id
    (:fn-slot :binding) (:fn-id version-row)
    :binding-list-item (some->> (:binding-id version-row)
                                (sp/read-entity base-storage :binding)
                                :fn-id)
    nil))


(defn- branch-local-version?
  "True iff `version-row` belongs to an effective-branch-local
   owning fn. Wraps `bl/effective-branch-local?` with the
   entity-aware owner-lookup so child-row version rows
   (`:binding`, `:fn-slot`, `:binding-list-item`) are filtered
   alongside `:fn` itself."
  [base-storage entity-name entity-id version-row]
  (when-let [fid (owning-fn-id base-storage entity-name entity-id version-row)]
    (bl/effective-branch-local? base-storage fid)))


(defn tombstone?
  "A version row is a tombstone when its `:deleted-at` is set — it marks
   the entity DELETED at that chain level (written by a user-facing
   `delete-entity` under `*tombstone-delete?*`). A tombstone that wins the
   latest-on-chain race resolves the entity ABSENT and, crucially, shadows
   any inherited ancestor version (so a delete of an inherited entity is
   not a silent no-op)."
  [version-row]
  (some? (:deleted-at version-row)))


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


(defn resolve-version
  "Resolves the current version of an entity on a branch.

   Loads the branch chain + every relevant `branch-merge` for the whole
   chain in ONE batched pass (`load-merge-aware-cache`), then resolves
   through `resolve-version-from-cache` — the SAME code the batch / graph
   path already uses. This used to recurse level-by-level, re-querying
   `:branch-merge` and the parent link at each step (~3 queries × chain
   depth for a single-entity read on a feature branch); the batched load
   is a fixed handful of queries regardless of depth, and it collapses
   the two parallel resolution algorithms (this recursion vs
   `resolve-version-from-cache`) into one tested implementation.

   Returns the version record or nil."
  [base-storage entity-name entity-id branch-id]
  (let [{:keys [version-entity version-id-field]} (get entity-config entity-name)
        {:keys [versions-by-id merges-by-target branch-chain]}
        (load-merge-aware-cache base-storage version-entity version-id-field
                                [entity-id] branch-id)
        version (resolve-version-from-cache base-storage entity-name
                                            versions-by-id merges-by-target
                                            entity-id branch-chain)]
    ;; A tombstone-winner means the entity is deleted on this branch — the
    ;; public API treats it as absent (nil), same as no version at all.
    (when-not (tombstone? version) version)))


;; === High-Level Resolution Functions ===

(defn resolve-entity
  "Resolves a versioned entity by merging identity and version data.
   Returns the merged entity record, or nil if entity has no version on this branch."
  [base-storage entity-name entity-id branch-id]
  (when-let [identity-rec (sp/read-entity base-storage entity-name entity-id)]
    (let [{:keys [version-id-field]} (get entity-config entity-name)]
      (when-let [version (resolve-version base-storage entity-name entity-id branch-id)]
        (merge identity-rec (extract-version-data version version-id-field))))))


(defn- bounded-entity-ids
  "A bounding id-vector for the version load, extracted from `where` —
   or nil when `where` cannot bound it (full-table load, the pre-2026-07
   behaviour for every call). Three strata, safest first:

   1. `:id` — the entity ids verbatim (immutable, exact).
   2. Identity-level keys (present in `where`, absent from
      `version-data-fields`, not the ref-many `:parent-ids`) — one SQL
      query on the identity table. Exact: identity columns cannot drift
      across versions (that is what makes the raw-read gotcha in
      feedback_raw_storage_query_reads_are_create_time SAFE here).
   3. One `:candidate-bound-keys` key — every entity-id with ANY version
      row matching it. A SUPERSET of the truth (an old version on some
      branch may match while the resolved row does not), which is safe
      because the caller re-applies the FULL where in memory after
      resolution; version-data keys outside the whitelist (jsonb
      `:value`, booleans) stay unbounded rather than risk an encoding
      mismatch in the version-table query.

   Returns [] when a bound exists and is empty — the caller can skip
   the version load entirely."
  [base-storage entity-name where]
  (let [{:keys [version-entity version-id-field version-data-fields
                candidate-bound-keys]} (get entity-config entity-name)]
    (cond
      (contains? where :id)
      (let [v (:id where)]
        (vec (if (and (coll? v) (not (map? v))) v [v])))

      :else
      (let [identity-keys (into []
                                (comp (remove #(contains? version-data-fields %))
                                      (remove #{:parent-ids}))
                                (keys where))]
        (if (seq identity-keys)
          (mapv :id (sp/query-entities base-storage entity-name
                                       (select-keys where identity-keys)))
          (when-some [k (some candidate-bound-keys (keys where))]
            (into []
                  (comp (map version-id-field) (distinct))
                  (sp/query-entities base-storage version-entity
                                     (select-keys where [k])))))))))


(declare resolve-all-entities*)


(defn resolve-all-entities
  "Resolves all entities of a type visible on the current branch.
   Filters by where clause after resolution.
   Returns sequence of merged entity records.

   Only returns entities that have at least one version visible on the branch chain.
   This is different from resolve-entities-batch which returns all entities.

   Optimized: uses batch version loading instead of N+1 queries, and
   bounds the load to the ids `where` implies when it can
   (`bounded-entity-ids`) — a `{:id #{x}}` query used to cost the same
   whole-version-table scan as `{}`. Merge-aware as of #52 — versions
   on merged-in source branches surface here too."
  [base-storage entity-name branch-id where]
  (let [{:keys [version-entity version-id-field]} (get entity-config entity-name)
        entity-ids (when (seq where)
                     (bounded-entity-ids base-storage entity-name where))]
    (if (and (some? entity-ids) (empty? entity-ids))
      []
      (resolve-all-entities* base-storage entity-name branch-id where
                             version-entity version-id-field entity-ids))))


(defn- resolve-all-entities*
  [base-storage entity-name branch-id where version-entity version-id-field entity-ids]
  (let [{:keys [versions-by-id merges-by-target branch-chain]}
        (load-merge-aware-cache base-storage version-entity version-id-field
                                entity-ids branch-id)
        ;; Entities visible: those with at least one loaded version
        ;; (loaded set already covers chain + merge sources).
        entity-ids-with-versions (set (keys versions-by-id))
        identities-map (if (empty? entity-ids-with-versions)
                         {}
                         (sp/read-entities base-storage entity-name
                                           (vec entity-ids-with-versions)))
        resolved (for [[eid identity-rec] identities-map
                       :let [version (resolve-version-from-cache
                                       base-storage entity-name
                                       versions-by-id merges-by-target
                                       eid branch-chain)]
                       ;; A tombstone-winner ⇒ deleted on this branch ⇒ omit.
                       :when (and version (not (tombstone? version)))]
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


(defn collect-branch-chain
  "Returns vector of branch-ids from current to root (for inheritance
   lookup). When `*branch-chain-cache*` is bound it wins (test
   isolation); otherwise consults the process-wide
   `global-chain-cache`, populating on miss.

   Public because cache invalidation needs the same reachability
   question the resolver asks: a write on branch W is visible from
   branch C exactly when W ∈ (collect-branch-chain … C). See
   `system.branch-router/invalidate-affected-ctxs!`."
  [base-storage branch-id]
  (let [cache (or *branch-chain-cache* global-chain-cache)]
    (if-let [cached (get @cache branch-id)]
      cached
      (let [chain (collect-branch-chain-impl base-storage branch-id)]
        (swap! cache assoc branch-id chain)
        chain))))


(defn- load-merge-aware-cache
  "Pre-load every row needed to resolve `entity-ids` (or every entity
   of the type if `entity-ids` is nil) on `branch-id` with full
   `branch-merge` support.

   Returns `{:versions-by-id :merges-by-target :branch-chain}`:

   - `:branch-chain` — `[branch-id parent-id … root-id]`.
   - `:merges-by-target` — every `branch-merge` row landing on any
     branch in chain, grouped by `:target-branch-id`. Tells
     `resolve-version-from-cache` what to walk per chain level.
   - `:versions-by-id` — versions grouped by entity-id.

   The version loader is two-phase to keep the working set bounded:

   * Chain branches: `query-latest-per-group` returns ONE row per
     (entity-id, branch-id) — the latest. That's all
     `resolve-version-from-cache` ever consults via
     `latest-by-created-at`. Without this dedup the full version
     history is materialised per call; on a long-running executor
     `/api/graph/entities` and friends OOM the cheshire-encode step
     (root cause of the 2026-06-20 e2e cascade).
   * Source-merge branches: full history fetch with `query-entities`.
     `merge-candidates-from-cache` needs every version with
     `created-at <= merge.source-timestamp` per merge; if two merges
     from the same source have different timestamps, the latest-per-
     branch row may post-date the older merge's cutoff and we'd lose
     visibility into what that merge actually carried. Source-merge
     volume is small in practice (1-2 merges per chain in normal
     use), so the full fetch on this slice is OK.

   Both slices are filtered by `entity-ids` when the caller passes
   them (batch path). Without entity-ids (used by `resolve-all-
   entities`) the chain-branches fetch returns latest-per-entity
   across the whole branch — still O(entities × branches), but no
   longer multiplied by version history depth."
  [base-storage version-entity version-id-field entity-ids branch-id]
  (let [branch-chain (collect-branch-chain base-storage branch-id)
        all-merges (sp/query-entities base-storage :branch-merge
                                      {:target-branch-id (vec branch-chain)})
        source-branch-ids (into #{} (map :source-branch-id) all-merges)
        entity-where (when (seq entity-ids)
                       {version-id-field (vec entity-ids)})
        ;; Phase 1: chain branches — only the latest per (entity, branch).
        chain-versions (sp/query-latest-per-group
                         base-storage version-entity
                         (merge entity-where {:branch-id (vec branch-chain)})
                         [version-id-field :branch-id])
        ;; Phase 2: merge source branches — full history (small slice).
        source-versions (if (seq source-branch-ids)
                          (sp/query-entities
                            base-storage version-entity
                            (merge entity-where
                                   {:branch-id (vec source-branch-ids)}))
                          [])
        all-versions (into chain-versions source-versions)]
    {:versions-by-id (group-by version-id-field all-versions)
     :merges-by-target (group-by :target-branch-id all-merges)
     :branch-chain branch-chain}))


(defn- merge-candidates-from-cache
  "In-memory mirror of `merge-candidates`. `versions-by-branch` is
   the entity's versions grouped by `:branch-id` (just for this
   entity-id — caller does the per-id slice). Pure-ish: only hits
   storage through `bl/effective-branch-local?`, which is process-
   cached per `(storage, fn-id)`.

   Branch-local filter: per-candidate. For `:fn` the entity-id is
   the fn-id; for `:fn-slot` / `:binding` the version row carries
   `:fn-id` in its data fields, so the same flag suppresses child
   rows whose owning fn is sticky-local."
  [base-storage entity-name entity-id versions-by-branch own-latest merges]
  (when (seq merges)
    (for [m merges
          :let [src-versions (get versions-by-branch (:source-branch-id m) [])
                eligible (filter #(not (pos? (compare (:created-at %)
                                                      (:source-timestamp m))))
                                 src-versions)
                best (latest-by-created-at eligible)]
          :when best
          :when (not (branch-local-version? base-storage entity-name
                                            entity-id best))
          :when (or (nil? own-latest)
                    (pos? (compare (:target-timestamp m)
                                   (:created-at own-latest))))]
      {:version best :effective-ts (:target-timestamp m)})))


(defn- resolve-version-from-cache
  "Resolves version for an entity using pre-loaded `versions-by-id`
   + `merges-by-target` (built by `load-merge-aware-cache`). Mirrors
   `resolve-version`'s recursion: at each chain level, combine
   own-latest with merge-candidates and pick the latest by
   effective-ts; recurse to parent if nothing matched.

   The old simplified algorithm (chain priority only, no merge
   support) silently dropped merge-record visibility on the batch
   path — `/api/graph/entities` and the executor's compiled graph
   load both went through here, so a `POST /api/branches/X/merge`
   created the branch-merge row but never affected reads (#52).

   `entity-name` + `base-storage` are threaded down to
   `merge-candidates-from-cache` so the `:fn` branch-local filter can
   call `bl/effective-branch-local?`. Non-fn entities skip the check."
  [base-storage entity-name versions-by-id merges-by-target entity-id branch-chain]
  (when-let [versions (get versions-by-id entity-id)]
    (let [by-branch (group-by :branch-id versions)]
      (loop [chain branch-chain]
        (when-let [bid (first chain)]
          (let [own-latest (latest-by-created-at (get by-branch bid))
                merges (get merges-by-target bid)
                merge-cands (merge-candidates-from-cache base-storage entity-name
                                                         entity-id by-branch
                                                         own-latest merges)
                all-candidates (cond-> []
                                 own-latest
                                 (conj {:version own-latest
                                        :effective-ts (:created-at own-latest)})
                                 (seq merge-cands)
                                 (into merge-cands))]
            (or (pick-latest-candidate all-candidates)
                (recur (rest chain)))))))))


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
    (let [{:keys [version-entity version-id-field]} (get entity-config entity-name)
          entity-ids (mapv :id identity-records)
          {:keys [versions-by-id merges-by-target branch-chain]}
          (load-merge-aware-cache base-storage version-entity version-id-field
                                  entity-ids branch-id)
          identity-by-id (into {} (map (juxt :id identity)) identity-records)]
      (into {}
            (keep (fn [eid]
                    (let [identity-rec (get identity-by-id eid)
                          version (resolve-version-from-cache
                                    base-storage entity-name
                                    versions-by-id merges-by-target
                                    eid branch-chain)]
                      (cond
                        ;; No version on the chain — a base-fn (or a fn whose
                        ;; only version lives off this branch): identity as-is.
                        (nil? version) [eid identity-rec]
                        ;; Tombstone-winner ⇒ deleted ⇒ OMIT. Without this a
                        ;; deleted COMPOSED entity (identity row survives) would
                        ;; leak back through the identity-as-is fallback.
                        (tombstone? version) nil
                        ;; Live version — merge identity + version data.
                        :else [eid (merge identity-rec
                                          (extract-version-data version version-id-field))])))
                  entity-ids)))))


;; === Batch Execution Graph Resolution ===
;;
;; Optimized algorithm that loads ALL data in 4 queries, then does BFS in memory.
;; This avoids the N+1 query problem of generic BFS.

(defn- load-all-resolved
  "Loads all identity rows of `entity-name` and overlays the latest
   version on each. Returns a map `{id → resolved-row}`. Merge-aware
   via `load-merge-aware-cache` — the executor's compiled-graph load
   goes through here, so merging branch A into B must affect B's
   compiled view (#52)."
  [base-storage entity-name branch-id]
  (let [{:keys [version-entity version-id-field]} (get entity-config entity-name)
        all-identities (sp/query-entities base-storage entity-name {})
        {:keys [versions-by-id merges-by-target branch-chain]}
        (load-merge-aware-cache base-storage version-entity version-id-field
                                nil branch-id)]
    (into {}
          (keep (fn [identity-rec]
                  (let [eid (:id identity-rec)
                        version (resolve-version-from-cache
                                  base-storage entity-name
                                  versions-by-id merges-by-target
                                  eid branch-chain)]
                    (cond
                      (nil? version) [eid identity-rec]
                      ;; Tombstone ⇒ omit from the compiled executor graph +
                      ;; `/api/graph/entities` (both ride this loader).
                      (tombstone? version) nil
                      :else [eid (merge identity-rec
                                        (extract-version-data version version-id-field))]))))
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

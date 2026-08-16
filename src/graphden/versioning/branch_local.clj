(ns graphden.versioning.branch-local
  "Effective `:branch-local?` for fn entities — monotonic OR over the
   `:parent-ids` transitive closure on the identity-side `:fn` row.

   Mirrors the `effective-required?` shape in
   `graphden.executor.compile.bindings`: nil ≡ false ≡ inherit; any
   ancestor true on the chain ⇒ effective true forever. The widening
   guard lives in `graphden.types.check`.

   Used by `graphden.versioning.storage.resolution` to filter
   `merge-candidates` — foreign-branch `:fn-version` rows whose
   identity has effective-`:branch-local?` true are SKIPPED, so
   runtime-config functions (web-server with a dev port, vault path,
   etc.) never propagate to sibling branches on merge.

   Cache: per-storage atom, keyed by a STABLE content key (see
   `storage-key`). Lazy compute on first access; cleared via
   `invalidate!` on any write to the `:fn` table (CRUD layer). The
   cache is intentionally a `defonce` process-wide map rather than an
   extra field on `VersionedStorage` because the resolution algorithm
   doesn't carry the versioned wrapper — it operates over
   `base-storage`."
  (:require
    [clojure.set :as set]
    [graphden.storage.protocol.core :as sp]))


(defonce ^:private storage-caches
  ;; {storage-key → atom of {fn-id → bool}}
  (atom {}))


(defn- storage-key
  "Stable cache/identity key for a storage handle.

   The advisory-lock write paths in `versioning.storage.core` open a
   `with-transaction` and run every read on a per-transaction storage
   handle `(assoc base-storage :pool tx)` — a DISTINCT object whose
   `System/identityHashCode` differs from the base on every write. The
   original key-by-identity-hash therefore (a) never hit the base
   handle's cached entry from inside a transaction, and (b) LEAKED a
   fresh `storage-caches` entry per write transaction that `invalidate!`
   (keyed on the base handle) could never clear — unbounded growth on a
   long-running executor.

   Elide `:pool` so the base handle and all its transaction transients
   collapse to ONE stable, per-storage-unique entry: the remaining
   fields (the metadata-cache / rw-lock / slot-row-cache atoms, plus the
   epoch-ledger atoms) are shared by identity across the `assoc`, so a
   storage record's value-hash is stable across transactions yet
   distinct between two real storages. Non-associative handles (opaque
   test doubles) fall back to identity."
  [storage]
  (if (map? storage)
    (dissoc storage :pool)
    (System/identityHashCode storage)))


(defn- cache-for-storage
  "Returns the per-storage cache atom, creating one on first use."
  [base-storage]
  (let [k (storage-key base-storage)]
    (or (get @storage-caches k)
        (let [fresh (atom {})]
          (swap! storage-caches assoc k fresh)
          fresh))))


(defn invalidate!
  "Drop the cached `effective-branch-local?` map for `base-storage`.
   Call after any write to the `:fn` table — both `:branch-local?`
   itself and `:parent-ids` writes can shift the effective set."
  [base-storage]
  (let [k (storage-key base-storage)]
    (when-let [cache (get @storage-caches k)]
      (reset! cache {}))))


(defn invalidate-all!
  "Drop all per-storage caches. Used by test harness on DB wipe so
   stale identity-hashes from killed storages don't linger."
  []
  (reset! storage-caches {}))


(defn- compute-effective
  "Walks the `:parent-ids` closure of `fn-id` until one ancestor (or
   `fn-id` itself) carries `:branch-local? true`, then short-circuits.
   When the whole closure is false / nil, returns false."
  [base-storage fn-id]
  (loop [to-visit #{fn-id}
         visited #{}]
    (if (empty? to-visit)
      false
      (let [current (first to-visit)
            rest-set (disj to-visit current)
            row (sp/read-entity base-storage :fn current)]
        (cond
          (true? (:branch-local? row))
          true

          :else
          (let [parents (or (:parent-ids row) [])
                new-visited (conj visited current)
                new-to-visit (set/difference (set parents) new-visited)]
            (recur (set/union rest-set new-to-visit) new-visited)))))))


(defn effective-branch-local?
  "True iff `fn-id` (an identity-side `:fn` row id) or any ancestor
   in its `:parent-ids` closure has `:branch-local? true`.

   Memoized per storage. Callers in resolution.clj batch this against
   the same storage handle repeatedly, so the cache keeps the walk
   O(1) after the first hit. `nil` fn-id returns false (defensive —
   resolve-entity may pass nil for missing rows)."
  [base-storage fn-id]
  (if (nil? fn-id)
    false
    (let [cache (cache-for-storage base-storage)]
      (if (contains? @cache fn-id)
        (get @cache fn-id)
        (let [result (compute-effective base-storage fn-id)]
          (swap! cache assoc fn-id result)
          result)))))


(defn branch-local-seed
  "First row in `fn-id`'s `:parent-ids` closure (BFS, self included)
   with `:branch-local? true`, resolved against an in-memory
   `{fn-id → fn-row}` map; nil when the closure carries no seed. The
   map-based sibling of `effective-branch-local?` for callers that
   already batch-loaded the fn graph (the layout strip-facts pass,
   which also needs the seed's NAME for the editor tooltip, not just
   the boolean)."
  [fns-by-id fn-id]
  (loop [queue [fn-id]
         visited #{}]
    (when-let [cur (first queue)]
      (if (contains? visited cur)
        (recur (rest queue) visited)
        (let [row (get fns-by-id cur)]
          (if (true? (:branch-local? row))
            row
            (recur (concat (rest queue) (:parent-ids row))
                   (conj visited cur))))))))


(defn build-branch-local-set
  "Pre-compute the set of effective-branch-local fn-ids from a map
   `{fn-id → fn-row}` (loaded in batch). Used by the batch-resolution
   path so it doesn't need per-id walks; the in-memory map provides
   the entire parent-id graph already.

   Algorithm: any node `:branch-local? true` is local; then any node
   whose `:parent-ids` includes a local node is local. Iterate until
   the set stops growing."
  [fns-by-id]
  (let [seed (into #{}
                   (keep (fn [[fid row]] (when (true? (:branch-local? row)) fid)))
                   fns-by-id)]
    (loop [local seed]
      (let [grown (reduce (fn [acc [fid row]]
                            (if (and (not (contains? acc fid))
                                     (some local (or (:parent-ids row) [])))
                              (conj acc fid)
                              acc))
                          local
                          fns-by-id)]
        (if (= grown local)
          local
          (recur grown))))))

(ns graphden.crud.fn-execution.free-arg-cache
  "Process-wide memo for `lookup/free-arg-slot-map`.

   WHY THIS EXISTS
   ---------------
   `free-arg-slot-map` is called once per `/api/execute` request (in
   `crud.fn-execution/apply-execute`). Its implementation walks the
   target fn's reachable subgraph by issuing versioned-storage queries
   per BFS level per entity type — measured at **1.3–1.9 s per call**
   on a warm production instance, for even a trivial fn. That cost was
   invisible to the executor micro-benchmarks, which time `execute` by
   fn-id (a pure in-memory closure call, ~1 µs/node) and never touch
   this resolution path.

   The result is a pure function of the graph state for a given
   `[branch-id fn-id]`: a fn's free-arg surface only changes when
   something in its own-org∪public reachable closure changes, which is
   exactly the signal that fires `context/invalidate-graph-cache!`.
   So we memoize on `[branch-id fn-id]` and drop the whole memo at that
   one choke point. Never stale (any mutation clears it); the ~1.5 s
   recompute is paid at most once per fn after each edit, not per
   request.

   LEAF NAMESPACE ON PURPOSE
   -------------------------
   No requires. Both `lookup` (writer/reader) and `context`
   (invalidator) depend on this; keeping it dependency-free means
   `context → free-arg-cache` adds no risk of a namespace cycle back
   through the versioned-storage / request stack that `lookup` pulls in.

   KEYING
   ------
   `[branch-id fn-id]`. `fn-id` is a globally-unique UUID and its
   reachable closure is confined to own-org∪public (enforced by
   `reject-cross-org-refs!`), so the free-arg map is invariant across
   requesters who can see the fn — org need not be in the key. Branch
   IS in the key because versioned bindings differ per branch.

   FUTURE: delta-clear. `invalidate-graph-cache!`'s delta arity already
   knows the changed fn-ids and the `:compile-deps` reverse-index gives
   the affected dependers; clearing only those keys would avoid the
   full drop. Kept simple (full drop) until measured to matter — the
   recompute only lands on the first post-edit execute of each fn."
  (:import
    (java.util.concurrent
      ConcurrentHashMap)))


;; ConcurrentHashMap over an atom: the read path is hot (every execute)
;; and lock-free reads matter more here than the value-semantics of an
;; atom. `clear!` and `computeIfAbsent` are both safe under concurrency.
(defonce ^:private cache (ConcurrentHashMap.))


;; Defence against unbounded growth WITHIN a single generation (between
;; invalidations) on a very large graph. A full clear happens on every
;; mutation, so in practice the map only ever holds fns executed since
;; the last edit; this cap just bounds a pathological read-only burst.
(def ^:private max-entries 8192)


(defn get-or-compute
  "Return the memoized value for key `k`, computing and caching it via
   `compute-fn` (a 0-arg thunk) on a miss.

   `compute-fn` must be a pure function of the graph state for that
   key — the whole contract of this cache. Callers key on
   `[storage-identity branch-id fn-id]` (see `lookup`): storage-identity
   keeps two different graphs (e.g. per-test storages) from colliding
   even without an intervening `clear!`."
  [k compute-fn]
  (let [;; ConcurrentHashMap can't hold a null value, so a genuine nil
        ;; result is stored as the `::nil` sentinel. `.get` therefore
        ;; returns: a real value / the sentinel (both HITS) / Java null
        ;; only when the key is absent (a MISS). Unwrap on every path —
        ;; not just the compute path — or a cached nil comes back as the
        ;; sentinel. (`free-arg-slot-map` returns `{}` not nil, so the
        ;; sentinel is belt-and-braces, but the contract must hold.)
        hit (ConcurrentHashMap/.get cache k)]
    (cond
      (= hit ::nil) nil
      (some? hit)   hit
      :else
      (do
        (when (>= (ConcurrentHashMap/.size cache) max-entries)
          (ConcurrentHashMap/.clear cache))
        ;; computeIfAbsent so a concurrent miss on the same key
        ;; computes once.
        (let [v (ConcurrentHashMap/.computeIfAbsent
                  cache k (fn [_]
                            (let [r (compute-fn)]
                              (if (nil? r) ::nil r))))]
          (when-not (= v ::nil) v))))))


(defn clear!
  "Drop the whole memo. Called by `context/invalidate-graph-cache!` on
   every graph mutation."
  []
  (ConcurrentHashMap/.clear cache))


(defn size
  "Current entry count — for tests / diagnostics."
  []
  (ConcurrentHashMap/.size cache))

(ns graphden.crud.fn-execution.lookup
  "Read-only helpers for the /api/execute pipeline — resolve fn-id /
   fn-version-id from the request shape, look up the free-arg slot
   map for a fn. No DB writes happen here."
  (:require
    [graphden.crud.fn-execution.free-arg-cache :as fac]
    [graphden.crud.request :as request]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.core :as types]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as res]))


(defn resolve-fn-version-id
  "Find the current `:fn-version-id` for logical `fn-id` on the active
   branch. Walks the branch chain (+ branch-merge records) via the
   versioned-storage resolver, so a fn inherited from a parent
   branch without an own override on the current branch correctly
   resolves to the parent's version. Returns nil when the fn has no
   version row visible on this branch (shouldn't happen for any
   loaded fn — every create goes through the versioned-storage
   decorator).

   Pre-fix this filtered by `:branch-id` direct-match only, so
   inherited-from-main fns on branch X returned nil — every
   fn-execution write on a non-creator branch then had a nil
   version-id, breaking history filtering."
  [ctx fn-id]
  (let [storage (request/require-storage ctx)]
    (when (vs/versioned-storage? storage)
      (let [base (vs/unwrap storage)
            branch-id (vs/current-branch-id storage)]
        ;; Chain cache is now process-wide (`global-chain-cache`);
        ;; no per-call binding needed.
        (:id (res/resolve-version base :fn fn-id branch-id))))))


(defn query-fn-by-name
  "Storage schemas vary on whether `fn.name` is stored as text or as
   an enum (the package-loader codec roundtrip is sometimes one,
   sometimes the other). Try both shapes; swallow the
   validation-error/type-mismatch from the side that doesn't fit.

   `swallow-all?` — when true, swallow ANY ExceptionInfo (used by
   seeder paths that just want to try a name and skip on any
   failure). Defaults to swallowing only `:validation-error/type-
   mismatch` — re-raise other errors so genuine storage failures
   don't get silently dropped."
  ([storage fn-name] (query-fn-by-name storage fn-name false))
  ([storage fn-name swallow-all?]
   (letfn [(try-one
             [v]
             (try (first (sp/query-entities storage :fn {:name v}))
                  (catch clojure.lang.ExceptionInfo e
                    (when-not (or swallow-all?
                                  (= :validation-error/type-mismatch
                                     (:type (ex-data e))))
                      (throw e))
                    nil)))]
     (or (try-one fn-name)
         (try-one (keyword fn-name))))))


(defn query-fn-id-by-name
  "Like `query-fn-by-name`, but returns just the fn `:id` (a UUID) or
   nil. Used by seeder / branch-router paths that only need the id."
  ([storage fn-name] (query-fn-id-by-name storage fn-name false))
  ([storage fn-name swallow-all?]
   (some-> (query-fn-by-name storage fn-name swallow-all?) :id)))


(defn resolve-fn-id
  "Translate `{:fn-id … OR :fn-name …}` request shape into a fn-id.
   Returns the UUID or nil."
  [storage parsed]
  (cond
    (:fn-id parsed)   (:fn-id parsed)
    (:fn-name parsed) (some-> (query-fn-by-name storage (:fn-name parsed)) :id)
    :else nil))


(defn resolve-fn
  "Like `resolve-fn-id` but returns the full :fn row in a single
   storage round-trip. Use this when the caller needs both `:id` AND
   `:name` (or other columns) — saves the extra `read-entity` you'd
   otherwise chain after `resolve-fn-id`. Returns nil if neither
   identifier resolves."
  [storage parsed]
  (cond
    (:fn-id parsed)   (sp/read-entity storage :fn (:fn-id parsed))
    (:fn-name parsed) (query-fn-by-name storage (:fn-name parsed))
    :else nil))


(defn- collect-reachable-graph
  "The reachable closure of `root-fn-id` — parent-ids + ref-fn-id edges —
   in the shape `free-args-via` walks:
   `{:fns-by-id :slots-by-id :all-bindings :all-list-items :all-fn-slots}`.

   Loaded through the storage's OWN graph resolver
   (`sp/resolve-execution-graph` — the recursive-CTE / batch path the
   executor compiles from), i.e. a constant handful of round trips.
   This used to be a hand-rolled BFS issuing four `query-entities` per
   level: fine for a leaf (1–3 levels), but the app root's closure is
   dozens of levels deep, and the Run form for `web-server` took ~30–50 s
   (2026-09-02 — the demo's Runs pane looked hung).

   The resolver follows a SUPERSET of the BFS's edges (it also pulls
   type / base / element fn rows); the extra rows are inert —
   `free-args-via` keys everything by the inheritance chain and its
   binding ids. Slot type-fn rows (the `[:fn {ARGS} RET]` constraint
   `hof-slot-call-site-names` reads) are topped up explicitly, so the
   output does not depend on which resolver variant the storage runs.
   A root that does not resolve yields the empty closure, as before."
  [storage root-fn-id]
  (let [g (try
            (sp/resolve-execution-graph storage root-fn-id)
            (catch clojure.lang.ExceptionInfo e
              (when-not (= :not-found (:type (ex-data e))) (throw e))))
        fns-by-id (or (:fns g) {})
        fn-slots (vec (:fn-slots g))
        slot-ids (into #{} (map :slot-id) fn-slots)
        slots-by-id (into {}
                          (comp (filter #(slot-ids (:id %)))
                                (map (juxt :id identity)))
                          (:slots g))
        type-fn-ids (into #{}
                          (comp (keep :type-fn-id) (remove fns-by-id))
                          (vals slots-by-id))
        type-fn-rows (if (empty? type-fn-ids)
                       {}
                       (sp/read-entities storage :fn (vec type-fn-ids)))
        bindings (vec (:bindings g))
        list-items (vec (:list-items g))]
    {:fns-by-id (merge fns-by-id type-fn-rows)
     :slots-by-id slots-by-id
     :all-bindings bindings
     :all-list-items list-items
     :all-fn-slots fn-slots
     ;; Per-fn / per-binding indexes + a per-computation memo — see
     ;; `free-args-via`: the closure of the app root is ~5k rows, and
     ;; the walk visits thousands of fns.
     :fn-slots-by-fn (group-by :fn-id fn-slots)
     :bindings-by-fn (group-by :fn-id bindings)
     :items-by-binding (group-by :binding-id list-items)
     :memo (atom {})}))


(defn- inheritance-chain-in-memory
  "Transitive parents of `fn-id` walked from the pre-loaded
   `fns-by-id` map. Identical semantics to the old storage-backed
   `inheritance-chain` but with zero round-trips."
  [fn-id fns-by-id]
  (loop [acc [fn-id] frontier #{fn-id}]
    (if (empty? frontier)
      acc
      (let [visited (set acc)
            next-frontier (->> frontier
                               (mapcat #(:parent-ids (get fns-by-id %)))
                               (remove visited)
                               set)]
        (recur (into acc next-frontier) next-frontier)))))


(defn- root-source-slot-id
  "Walk the `:source-slot-id` chain to the ROOT slot — the deepest
   slot in the chain (the original one being renamed). Returns
   `slot-id` itself when it has no `:source-slot-id`.

   Rename equivalence: two slots are equivalent free-arg surfaces iff
   they share a root. Captured-arg discovery walks refs and surfaces
   slots at WHICHEVER point in the chain the ref-walk hits — that may
   be the original (`:call-noargs.:func`) or an upstream rename
   (`:_fire-target.:fn`, `:cron-next-after.:cron`). A binding on any
   of them must close all of them. Comparing by root makes the check
   commutative (#51)."
  [slot-id slots-by-id]
  (loop [sid slot-id seen #{}]
    (cond
      (nil? sid) nil
      (seen sid) sid
      :else
      (if-let [src (:source-slot-id (get slots-by-id sid))]
        (recur src (conj seen sid))
        sid))))


(defn- slot-type
  "The type expression `slot-id` declares — its type-fn's `:constraint`
   (e.g. `[:fn {:item :any} :any]`, `:jsonb`), or nil. This is the SAME
   representation the type-checker resolves, so the `graphden.types.core`
   predicates apply to it directly."
  [slot-id slots-by-id fns-by-id]
  (some-> (get slots-by-id slot-id) :type-fn-id fns-by-id :constraint))


(defn- hof-slot-call-site-names
  "Call-site arg names of a slot whose type is a structural HOF type
   `[:fn {ARGS} RET …]` — the keys of ARGS, supplied per invocation by the
   parent's impl (not captured). `#{}` for a bare `:fn` primitive (no
   structural shape → every free arg is captured) or a non-fn slot.
   Shares `types/fn-args` with the type-checker's `hof-call-site-arg-names`."
  [slot-id slots-by-id fns-by-id]
  (set (keys (types/fn-args (slot-type slot-id slots-by-id fns-by-id)))))


(defn- fn-typed-slot?
  "True when `slot-id`'s type is a CALLABLE — the bare `:fn` primitive or a
   structural `[:fn {ARGS} RET]`. A ref bound to such a slot is a callback:
   the parent's impl INVOKES it (per request for an `:http-server` handler,
   per tick for a `:schedule` body), so the callback's free args are supplied
   at invocation, not by the outer fn's caller at start. Used by the
   service-ability projection to drop those subtrees. Shares
   `types/callable-type?` with the type-checker's `hof-slot?`."
  [slot-id slots-by-id fns-by-id]
  (boolean (types/callable-type? (slot-type slot-id slots-by-id fns-by-id))))


(defn- free-args-via
  "Internal: `{arg-name → slot-id}` for `fn-id`'s free args, walking
   ref-fn-id bindings transitively. `visited` guards against cycles
   (GraphConstraints forbid them already, defence-in-depth).

   This is a DELIBERATE sibling of the type-checker's `collect-free-args`
   / `ref-free-args` (`types/check`), NOT accidental duplication: that one
   walks the rich-types REGISTRY at type-check time and returns `{name →
   type}` (with type-var freshening); this walks the live DB GRAPH for
   /api/execute + CRUD-guard freshness and returns `{name → slot-id}`.
   Different data source, different return — the only shared logic (the
   HOF call-site rule) lives in `types/callable-type?` + `types/fn-args`,
   which both call.

   `db` is the pre-loaded reachable-closure from
   `collect-reachable-graph` — every storage round-trip happened
   upfront, so the recursive resolution is pure in-memory.

   `drop-hof-refs?` (service-ability projection, top-level only): when
   true, refs bound to THIS fn's fn-typed (callback) slots are dropped
   ENTIRELY — those free args are the callback's per-invocation concern
   (supplied by the deferred invoker, e.g. `:http-server` per request),
   not needed to START the fn. The flag is NOT propagated into the
   recursion, so it only strips the service fn's OWN top-level callback
   bindings (a nested `:map` deep inside a one-shot computation keeps its
   captured args, which genuinely block). Default false = the exact
   full free-arg surface `/api/execute` needs for its arg form."
  ([fn-id visited db] (free-args-via fn-id visited db false))
  ([fn-id visited db drop-hof-refs?]
   (if (contains? visited fn-id)
     {}
     (let [{:keys [fns-by-id slots-by-id all-bindings all-list-items
                   all-fn-slots]} db
           ;; Indexes: `collect-reachable-graph` supplies them; a caller
           ;; that hands in the bare five-key shape gets them built once.
           fn-slots-by-fn (or (:fn-slots-by-fn db) (group-by :fn-id all-fn-slots))
           bindings-by-fn (or (:bindings-by-fn db) (group-by :fn-id all-bindings))
           items-by-binding (or (:items-by-binding db)
                                (group-by :binding-id all-list-items))
           visited' (conj visited fn-id)
           chain-vec (inheritance-chain-in-memory fn-id fns-by-id)
           ;; Level-order position: 0 = the fn itself, growing towards the
           ;; roots. Used to pick WHICH exposure of a slot identity names
           ;; the free arg (closest wins — rename-view contract).
           chain-pos (into {} (map-indexed (fn [i fid] [fid i])) chain-vec)
           chain-fn-slots (mapcat #(get fn-slots-by-fn %) chain-vec)
           chain-bindings (mapcat #(get bindings-by-fn %) chain-vec)
           chain-list-items (mapcat #(get items-by-binding (:id %)) chain-bindings)
           ;; The lifted set of a referenced sub-graph does not depend on
           ;; the path that reached it (the graph is a DAG — cycles are
           ;; rejected at write time; `visited` is defence-in-depth), so
           ;; one computation memoises it per fn-id. Without this the app
           ;; root re-walked every shared sub-graph once per path:
           ;; 26 s in-memory for `web-server` on a 5k-fn closure
           ;; (2026-09-02), the Run pane's "Loading runs…" hang.
           sub-frees (fn [rfid]
                       (if-let [memo (:memo db)]
                         (if (contains? @memo rfid)
                           (get @memo rfid)
                           (let [v (free-args-via rfid visited' db)]
                             (swap! memo assoc rfid v)
                             v))
                         (free-args-via rfid visited' db)))
           ;; Bound bindings are tracked by ROOT slot-id (see
           ;; `root-source-slot-id`). All rename siblings collapse to
           ;; the same root, so the bound check is rename-insensitive.
           root-of (fn [sid] (root-source-slot-id sid slots-by-id))
           bound-roots (->> chain-bindings
                            ;; `:value-present` flag — `{:default nil}`
                            ;; in fns.edn is a real binding (slot is
                            ;; pinned to nil), so the slot is NOT free
                            ;; at execute-by-name time.
                            (filter #(or (true? (:value-present %))
                                         (some? (:ref-fn-id %))
                                         (true? (:list-append %))))
                            (map (comp root-of :slot-id))
                            set)
           ;; ONE exposure per ROOT slot identity. A rename-view slot and
           ;; its source share a root but carry different NAMES — surfacing
           ;; both made the Run form show `data` AND `payload` after a
           ;; rename (and leaked every `{:as …}` capture-rename's source
           ;; name: `coll`+`item` next to `current`+`value`). The public
           ;; name is the one on the CLOSEST chain fn, rename slot first on
           ;; a tie; the shadowed source name is not a separate free arg.
           direct (->> chain-fn-slots
                       (keep (fn [{fs-fn-id :fn-id slot-id :slot-id}]
                               (when-not (bound-roots (root-of slot-id))
                                 (when-let [s (get slots-by-id slot-id)]
                                   {:name (keyword (:name s))
                                    :slot-id slot-id
                                    :root (root-of slot-id)
                                    :pos (get chain-pos fs-fn-id Long/MAX_VALUE)
                                    :rename? (some? (:source-slot-id s))}))))
                       (group-by :root)
                       vals
                       (map (fn [entries]
                              (first (sort-by (juxt :pos (comp not :rename?) :name)
                                              entries))))
                       (map (juxt :name :slot-id))
                       (into {}))
           ;; Recurse into every ref-fn-id reachable from chain bindings
           ;; — slot-bound refs + list-item refs. Each is a captured
           ;; sub-graph whose still-unbound free-args propagate up as
           ;; free-args of the outer fn-def.
           ;;
           ;; A ref bound to a HOF slot is the exception: its lifted set
           ;; is MINUS the slot's structural call-site arg names — those
           ;; are supplied per invocation by the parent impl, not by the
           ;; caller. Mirrors the type-checker's `ref-free-args`; without
           ;; it a HOF-composed fn's callback leaks e.g. `:item` as a
           ;; phantom free arg (→ spurious service-create rejection).
           ;; List-item refs are list elements, not callbacks — no
           ;; call-site notion, so they recurse unmodified.
           binding-ref-frees
           (reduce (fn [acc {:keys [ref-fn-id slot-id] :as b}]
                     ;; Skip a ref with no target, an IDENTITY edge (a ref
                     ;; into a `:fn-ref` slot — the target is named, never
                     ;; evaluated, so none of its frees are this fn's: a
                     ;; consumer naming `:web-server` must not inherit
                     ;; every free of the app), AND — under the service-
                     ;; ability projection (top level only) — a ref in a
                     ;; callback slot: it's invoked by the deferred invoker, so
                     ;; its whole free-arg subtree is per-invocation, not
                     ;; start-blocking.
                     (if (or (nil? ref-fn-id)
                             (ids/identity-edge? b (get slots-by-id slot-id))
                             (and drop-hof-refs?
                                  (fn-typed-slot? slot-id slots-by-id fns-by-id)))
                       acc
                       (let [lifted (sub-frees ref-fn-id)
                             call-site (hof-slot-call-site-names
                                         slot-id slots-by-id fns-by-id)]
                         (merge acc (if (seq call-site)
                                      (into {} (remove (comp call-site key)) lifted)
                                      lifted)))))
                   {}
                   chain-bindings)
           list-item-ref-frees
           (reduce (fn [acc rfid]
                     (merge acc (sub-frees rfid)))
                   {}
                   (distinct (keep :ref-fn-id chain-list-items)))
           transitive (merge list-item-ref-frees binding-ref-frees)
           ;; A slot bound at THIS level removes that slot from the
           ;; combined free-arg map — both direct and transitive. Lets
           ;; a derived fn-def bind a captured arg (e.g.
           ;; `:my-cron :args {:cron …}`) and have it disappear. Compare
           ;; by ROOT slot-id so a binding on a rename slot closes its
           ;; siblings everywhere in the chain (#51).
           combined (merge transitive direct)
           unbound (into {}
                         (remove (fn [[_ sid]] (bound-roots (root-of sid))))
                         combined)
           ;; Cross-LEVEL rename collapse (#51 class): the ref walk lifts a
           ;; captured slot under its SOURCE name (`:func`) while this
           ;; chain exposes the same identity under a rename (`:fn`). The
           ;; caller-facing name is the chain's rename — drop the lifted
           ;; exposure whose root the direct chain already names
           ;; differently, so one identity never shows up twice.
           direct-name-by-root (into {}
                                     (map (fn [[n sid]] [(root-of sid) n]))
                                     direct)]
       (into {}
             (remove (fn [[n sid]]
                       (when-let [dn (get direct-name-by-root (root-of sid))]
                         (not= dn n))))
             unbound)))))


(defn free-arg-slot-map
  "Return `{arg-name → slot-id}` for `fn-id`'s free args.

   Includes:
   1. Slots in the inheritance chain that have no value/ref/list
      binding at any chain level (direct free args, existing semantics).
   2. Transitive free args of fn-graphs reachable via ref-fn-id
      bindings — both slot-bound refs and binding-list-item refs. The
      referenced fn-graph's still-unbound free-args surface as
      captured-args of the outer fn-def. NEW (closure-capture
      commit 2/6).

   A slot whose id appears in any chain-level binding is removed from
   the combined map — that's how `:my-cron :args {:cron …}` makes
   `:cron` disappear from `:my-cron`'s free-arg map despite `:cron`
   being a captured arg inherited from `:schedule`.

   See `docs/CLOSURE_CAPTURE.md` § Implementation Contract for the
   semantics. Subsequent commits layer wrap-time capture in
   `hof-callable` (3) and type-checker propagation (4) on top.

   This function is PURE — it re-reads the graph every call (a handful
   of round trips through the storage's graph resolver — see
   `collect-reachable-graph`). Callers on the hot `/api/execute` path
   use `free-arg-slot-map-cached` instead; direct callers (tests, the
   `:free-arg-slot-map` base-fn) keep the exact, always-fresh
   behaviour."
  [ctx fn-id]
  (let [storage (request/require-storage ctx)]
    (free-args-via fn-id #{} (collect-reachable-graph storage fn-id))))


(defn service-blocking-free-args
  "Like `free-arg-slot-map`, but the SERVICE-ABILITY projection: `{arg-name
   → slot-id}` for only the free args that would prevent the fn from being
   STARTED as a service (the reconciler runs it with empty args).

   Drops the fn's own top-level CALLBACK-slot subtrees — an `:http-server`
   handler or a `:schedule` body is invoked by the deferred invoker (per
   request / per tick), so its free args are per-invocation and irrelevant
   to starting the listener/loop. Keeps direct free args and args lifted
   through DATA slots (a genuinely unstartable fn — `increment` with no
   operand, a cron missing its `:cron` schedule, a helper with a required
   data input — still surfaces here).

   This is the check the `:service` create-guard wants; `/api/execute`
   keeps `free-arg-slot-map` (its arg form needs the FULL surface)."
  [ctx fn-id]
  (let [storage (request/require-storage ctx)]
    (free-args-via fn-id #{} (collect-reachable-graph storage fn-id) true)))


(defn free-arg-slot-map-cached
  "Memoized `free-arg-slot-map` for the `/api/execute` hot path, where
   this call was measured at ~1.3–1.9 s and runs once per request.

   The result is a pure function of the graph state for a given
   `[storage branch fn]`, so it's cached in `free-arg-cache` and dropped
   wholesale by `context/invalidate-graph-cache!` on every mutation —
   the choke point every CRUD write already goes through. Keyed on the
   BASE storage's identity (via `vs/unwrap`) as well as branch + fn, so
   two distinct graphs never collide even without an intervening clear
   (e.g. across tests that share the process-wide cache). Org needn't be
   in the key: a fn's reachable closure is confined to own-org∪public
   (`reject-cross-org-refs!`), so the free-arg map is invariant across
   requesters who can see the fn.

   ONLY safe where every graph mutation before the call routes through
   `invalidate-graph-cache!`. That holds for `/api/execute` (it runs
   after CRUD writes, never during one). Callers that mutate storage
   directly without invalidating must use the pure `free-arg-slot-map`."
  [ctx fn-id]
  (let [storage (request/require-storage ctx)
        base (if (vs/versioned-storage? storage) (vs/unwrap storage) storage)
        branch-id (when (vs/versioned-storage? storage)
                    (vs/current-branch-id storage))
        k [(System/identityHashCode base) branch-id fn-id]]
    (fac/get-or-compute
      k
      (fn []
        (free-args-via fn-id #{} (collect-reachable-graph storage fn-id))))))

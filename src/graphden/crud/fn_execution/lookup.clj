(ns graphden.crud.fn-execution.lookup
  "Read-only helpers for the /api/execute pipeline — resolve fn-id /
   fn-version-id from the request shape, look up the free-arg slot
   map for a fn. No DB writes happen here."
  (:require
    [graphden.crud.fn-execution.free-arg-cache :as fac]
    [graphden.crud.request :as request]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.compile.surface :as surface]
    [graphden.storage.protocol.core :as sp]
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


(defn- executor-lookups
  "The executor's compile lookups over `root-fn-id`'s reachable closure —
   loaded through the storage's OWN graph resolver
   (`sp/resolve-execution-graph`: the recursive-CTE / batch path the
   executor compiles from, a constant handful of round trips; the app
   root's closure is ~5k rows). Slot type-fn rows (the `[:fn {ARGS}
   RET]` constraint `hof-lambda-params` reads) are topped up explicitly —
   the resolver does not chase `slot.type-fn-id`. The free-arg surface
   is then the SAME walk the compiler runs (`surface/public-free-entries`),
   so the Run form, the service create-guard and the layout's deep-free
   placeholders agree by construction. A root that does not resolve
   yields empty lookups."
  [storage root-fn-id]
  (let [g (try
            (sp/resolve-execution-graph storage root-fn-id)
            (catch clojure.lang.ExceptionInfo e
              (when-not (= :not-found (:type (ex-data e))) (throw e))))
        fns-by-id (or (:fns g) {})
        type-fn-ids (into #{}
                          (comp (keep :type-fn-id) (remove fns-by-id))
                          (:slots g))
        type-fn-rows (if (empty? type-fn-ids)
                       {}
                       (sp/read-entities storage :fn (vec type-fn-ids)))]
    (l/build-lookups {:fns (vec (vals (merge fns-by-id type-fn-rows)))
                      :slots (vec (:slots g))
                      :fn-slots (vec (:fn-slots g))
                      :bindings (vec (:bindings g))
                      :list-items (vec (:list-items g))})))


(defn- entries->slot-map
  "`{arg-name → slot-id}` from public entries."
  [entries]
  (into {} (map (juxt :ext-name :slot-id)) entries))


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
   `executor-lookups`) and reads the executor's own public surface
   (`surface/public-free-entries`: the name walker's membership and names,
   plus HOF closure captures minus what any enclosing scope supplies —
   lambda params by alpha-equivalence, env-bindings). Callers on the hot
   `/api/execute` path
   use `free-arg-slot-map-cached` instead; direct callers (tests, the
   `:free-arg-slot-map` base-fn) keep the exact, always-fresh
   behaviour."
  [ctx fn-id]
  (let [storage (request/require-storage ctx)]
    (entries->slot-map
      (surface/public-free-entries fn-id (executor-lookups storage fn-id)))))


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
    (entries->slot-map
      (surface/public-free-entries fn-id (executor-lookups storage fn-id)
                                   {:skip-root-hofs? true}))))


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
      (fn [] (free-arg-slot-map ctx fn-id)))))

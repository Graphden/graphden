(ns graphden.executor.compile-eager
  "Eager compile pipeline — the executor's compile half.

   Each fn-def compiles to an ordinary Clojure closure
   `(fn [free-args ctx])`. On invocation the closure performs ONLY:

     1. Read free-arg values by their final external names (renames
        applied at compile time — there is no runtime rename pass).
     2. Invoke pre-captured child callables (captured at compile time
        by topological sort of the ref-DAG; no per-call registry
        lookup).
     3. Call the impl with a `defbase`-shaped args map.

   Lazy semantics come from Clojure-native form evaluation — every
   `:ref` arg is a `delay`, `resolve-arg` `@`-derefs it, so
   `(if test then else)` only forces the picked branch. No
   `:lazy-args` markers or other flags: lazy is built in.

   Secret bindings are ordinary `:resolved-value` args since the
   `:override-kind` retirement — `:vault-get`'s own impl owns the
   vault-client resolution (ctx `:vault` + JVM-wide fallback)."
  (:require
    [graphden.executor.compile.bindings :as b]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.compile.renames :as r]
    [graphden.executor.runtime :as rt]
    [graphden.util.counters :as counters]
    [graphden.util.json-size :as json-size]))


;; =============================================================================
;; Per-execute DRY memo
;; =============================================================================
;;
;; One top-level closure invocation = one HashMap stored under
;; `::call-cache` in `ctx`. Every sibling `:ref` invocation in the
;; sub-tree hits the cache on `[ref-id fa]`, so a fn-def that pulls a
;; ref TWICE — once for validation, once for the success branch — only
;; fires the child ONCE. Without this, side-effecting impls like
;; `:create-entity` insert twice → unique-violation; pure impls just
;; waste work but still compute the right value.
;;
;; `always-fresh-fn-ids` carries impls whose `:effects` include `:time`
;; or `:random` — these must fire fresh on every read even within one
;; top-level call (two adjacent clock reads must see different values).
;; `:env` / `:io` / `:db` / `:network` ARE cacheable within one
;; top-level call (env values don't change mid-request, the txn sees a
;; consistent DB snapshot, etc.).

;; ^:dynamic so the parallel kaocha plugin can shadow per-NS-thread.
;; Production hands prod a single shared atom via the root binding; tests
;; running on isolated NS-threads see fresh atoms via
;; `kaocha.plugin.parallel/isolation-vars`. Without this, two sibling
;; NSes both calling `compile-runtime/rebuild!` race on the set —
;; whoever lands last wins, and the loser's `:time`/`:random` fn-ids
;; drop out of always-fresh, masking timing-sensitive tests.
(def ^:dynamic *always-fresh-fn-ids* (atom #{}))


(def ^:dynamic *request-call-cache*
  "Per-EXECUTION-SCOPE call-cache override, or nil. When bound to a
   `java.util.HashMap`, `run` installs IT as the ctx's `::call-cache`
   for the current thread, overriding any cache captured in the ctx.

   Why it exists — the CME + cross-request leak it closes. A long-lived
   handler (`:http-server`'s `:handler` `:fn`, a `:future` body) is
   `hof-wrap`ped at BUILD time and captures the ctx of the execute that
   built it — including that execute's `::call-cache` HashMap. Without an
   override, EVERY later invocation of that handler reuses the ONE
   build-time HashMap: concurrent HTTP requests then read/evict/put the
   same non-thread-safe map, and once it fills past `call-cache-max-size`
   the eviction walk races a concurrent put into a
   `ConcurrentModificationException` (observed under e2e load, /health
   500s). It is ALSO a correctness leak — a no-free-arg subtree memoises
   on request 1 and serves that frozen result to every later caller (the
   `/api/my-tokens` cross-principal class the route-collection
   `defer-handler-call-cache` closes for its routes only).

   The fix: each NEW execution scope entering on a fresh thread binds a
   fresh HashMap here — the `:http-server` adapter per REQUEST, `:future`
   per WORKER. `run` then installs the per-scope map, so each request/
   worker memoises in isolation (within-scope memo preserved, no shared
   mutation, no CME). DELIBERATELY not conveyed to `:future` threads
   (`capture-conveyed-bindings`) — like `*path-trace*`, it is per-scope
   state, and `future-fn` binds its own fresh one anyway."
  nil)


(defn set-always-fresh-fn-ids!
  "Merge `ids` into the always-fresh (cache-bypass) set — anything
   whose registered `:effects` intersects `#{:time :random}`. Called
   by `compile_runtime`'s `rebuild!` / `delta-recompile!` after a
   compile pass, since the set drives every `:ref` invocation.

   UNION, not reset: primes run per-ctx (per branch / per shard), each
   from its OWN graph view — a reset from branch A's view dropped
   branch B's `:time` fns until B's next rebuild, and an optimistic
   rebuild whose swap lost the `unchanged?` race still clobbered the
   set from its stale snapshot with no rollback. Union is monotone
   and sound: a stale member (deleted fn) is an id that is never
   invoked; the worst case of over-membership is a skipped cache hit
   on a fn that stopped being time/random mid-process — correct
   results, marginally less caching. The set resets only with the
   process (or a test's isolation binding)."
  [ids]
  ;; Coerce a non-set current value to #{} first — the parallel
  ;; plugin's DEFAULT isolation seed is `{}` (a map), which the old
  ;; reset! semantics happened to mask.
  (swap! *always-fresh-fn-ids*
         (fn [cur] (into (if (set? cur) cur #{}) ids))))


(defn always-fresh-isolation-seed
  "Seeder for the parallel plugin's isolation atom — the set must
   start as a SET (the plugin's default seed is `{}`)."
  []
  #{})


(def trace-all
  "Sentinel value for `*traced-fn-ids*` (Debug P2). An execution-scoped
   `(binding [*traced-fn-ids* (atom trace-all)] …)` means every `:ref`
   frame of THAT execution records — no per-fn set test. Bound by
   `crud.fn-execution.persist/run-future` alongside `*path-trace*` when
   the submission opted in via `trace?`: running a specific fn with the
   trace box checked IS the \"small subtree they explicitly select\"
   from PHILOSOPHY § Debugging constraint 1 — the sentinel admits only
   frames actually reached inside that one execution, a strict subset
   of the fn's ref-closure, at zero closure-computation cost."
  ::trace-all)


;; ^:dynamic for the same parallel-kaocha isolation reason as
;; `*always-fresh-fn-ids*` above (see `kaocha.plugin.parallel/isolation-vars`).
(def ^:dynamic *traced-fn-ids*
  "Debug/observability P1 — the per-fn half of the execution-path
   capture opt-in: only `:ref` invocations whose target fn-id is in
   this set record entries into `compile-runtime/*path-trace*` (or,
   when the atom holds the `trace-all` sentinel, every `:ref` frame of
   the execution that bound it — the Debug-P2 editor path).

   RUNTIME-ONLY state, deliberately NOT a stored fn field:
   PHILOSOPHY § \"Per-fn debug/trace toggles are not a stored field\"
   rejects `fn.trace_enabled` — trace is a runtime decoration applied
   by the executor when asked, not a property of the fn entity. The
   set lives in executor memory and resets on restart, exactly like
   `always-fresh-fn-ids`."
  (atom #{}))


(defn set-traced-fn-ids!
  "Replace the set of path-traced fn-ids (Debug P1 per-fn opt-in).
   Mirrors `set-always-fresh-fn-ids!` — call with the ids a user
   explicitly selected for tracing; capture additionally requires the
   per-execution `trace?` flag (which binds
   `compile-runtime/*path-trace*`) OR an ambient-sampling win (Debug
   P3 — see `ambient-sample?`). Editor-submitted `trace?` runs bypass
   this set via the `trace-all` execution-scoped binding (see
   `trace-all`); the root-level set remains the selective hook for
   programmatic captures and ambient sampling."
  [ids]
  (reset! *traced-fn-ids* (set ids)))


;; =============================================================================
;; Ambient session sampling (Debug P3 — PHILOSOPHY § Debugging constraint 2)
;; =============================================================================

;; ^:dynamic for the same parallel-kaocha isolation reason as the other
;; trace vars (see `kaocha.plugin.parallel/isolation-vars`). RUNTIME-ONLY
;; state by constraint 2's wording ("tunable per session, never persisted
;; as 100%"): an atom in executor memory, resets to the 1% default on
;; every restart — there is deliberately NO config key, env var, or
;; stored field that could persist a full-capture rate.
(def ^:dynamic *trace-sample-rate*
  "Session-scoped ambient sample rate (0.0–1.0, default 0.01) for fns in
   the `*traced-fn-ids*` selective set. A top-level execution of such an
   fn WITHOUT an explicit `trace?` submit gets its path captured with
   this probability — decided ONCE at `run-future` binding time
   (`ambient-sample?`), never per-node. Tune via `set-trace-sampling!`."
  (atom 0.01))


(defn trace-sample-rate-isolation-seed
  "Seed value for `kaocha.plugin.parallel`'s per-NS-thread isolation
   binding of `*trace-sample-rate*` — the 0.01 session default (the
   plugin's generic `{}` seed would break the numeric read)."
  []
  0.01)


(defn set-trace-sampling!
  "Set the ambient trace sample rate (Debug P3, constraint 2). `rate`
   must be in [0.0, 1.0]. A FULL rate (>= 1.0) additionally requires
   `{:confirm-full true}` — the programmatic mirror of the UI's
   \"confirm before full capture\" doctrine (constraint 3): sampling
   is the default, 100% is an explicit \"I really want this\".

   Runtime atom only — never persisted; restarts reset to 0.01."
  ([rate] (set-trace-sampling! rate nil))
  ([rate {:keys [confirm-full]}]
   (when-not (and (number? rate) (<= 0.0 (double rate) 1.0))
     (throw (ex-info "trace sample rate must be a number in [0.0, 1.0]"
                     {:type :trace/invalid-sample-rate :rate rate})))
   (when (and (>= (double rate) 1.0) (not (true? confirm-full)))
     (throw (ex-info "full (100%) ambient sampling requires {:confirm-full true}"
                     {:type :trace/full-sampling-requires-confirm :rate rate})))
   (reset! *trace-sample-rate* (double rate))))


(defn ambient-sample?
  "The Debug-P3 ambient-sampling decision, made ONCE per top-level
   execution at `run-future` binding time (never per-node): true iff
   `fn-id` is in the SELECTIVE `*traced-fn-ids*` set (not the
   `trace-all` sentinel — that's the explicit-`trace?` path) AND a
   single `rand` draw wins at `*trace-sample-rate*`. Rate 0 never
   samples and 1.0 always does, both WITHOUT consulting `rand` — so
   tests at the extremes are deterministic."
  [fn-id]
  (let [ids @*traced-fn-ids*]
    (and (not (identical? trace-all ids))
         (contains? ids fn-id)
         (let [r (double @*trace-sample-rate*)]
           (cond
             (<= r 0.0) false
             (>= r 1.0) true
             :else (< (rand) r))))))


(defn- fa-key-for-cache
  "Project `fa` to the subset the ref-target actually reads —
   `ref-frees` from `r/cache-projection-frees`. The walker is a
   strict superset of `deep-free-ext-names`: it walks INTO HOF
   bodies (`:is-fn :ref` bindings) and subtracts each boundary's
   `hof-lambda-params`, so closure-captured names a HOF body reads
   from caller's `fa` at wrap time DO land in the cache key.
   Without this, two invocations of the same outer fn-graph with
   different closure-captured values (e.g. each secret's `:fn-row`
   in `_shape-secret-bindings`) collapse to one cache slot and
   every caller sees the FIRST result — `GET /api/secrets` returned
   every row with the first secret's `:path` until this wiring
   landed.

   `nil` → unknown set, full `fa` as the key (defensive fallback)."
  [ref-frees fa]
  (if (nil? ref-frees)
    fa
    (if (empty? ref-frees)
      {}
      (select-keys fa ref-frees))))


(def skip-cache-priming-key
  "Ctx flag consumed by `run`. Set it on the ctx of an execute whose
   PURPOSE is to build long-lived Ring handler callables that will each
   serve independent later requests — a route-collection router-install
   (`:tenancy/router-install`, accounts `/auth/*`). See `run`'s cache
   logic + `defer-handler-call-cache` for why."
  ::skip-cache-priming)


(defn defer-handler-call-cache
  "Mark `ctx` so an execute that BUILDS route handlers doesn't leave a
   shared `::call-cache` for those handlers to capture.

   A route-collection router is built once (at boot) by executing its
   router fn-def; the `:fn`-typed route handlers are `hof-wrap`ped
   during that execute and CAPTURE its ctx. Normally `run` primes a
   `::call-cache` HashMap into the ctx — so every later request through
   such a handler reuses that ONE build-time cache. A no-free-arg
   subtree then has a constant cache key `[fn-id {}]`, is memoised on
   the first request, and its frozen result is served to every caller
   forever (e.g. `/api/my-tokens/list` leaking one account's tokens to
   all — a cross-principal data leak). The main app router escapes this
   because branch-router re-dispatches each request through `execute`
   with a fresh, cache-less branch ctx.

   With this flag, `run` primes no shared cache and PROPAGATES the flag
   through every nested run down to where the `:fn` route handlers are
   `hof-wrap`ped — so those handlers capture a cache-less, flag-carrying
   ctx. Each later request through such a handler then runs cache-less
   too (every `:ref` a fresh call): correct + per-principal, trading the
   within-request memo away for these low-traffic admin routes. HOF
   callbacks elsewhere are unaffected — they capture the enclosing
   REQUEST execute's ctx (no flag), so within-execute memoisation and
   shared per-execute `:atom`/state still work."
  [ctx]
  (assoc ctx skip-cache-priming-key true))


(def ^:private call-cache-max-size
  "Cap on the per-execute call-cache. Average entry size observed
   ~180 KB (large maps, JSON strings, etc.) on /api/graph/entities-
   sized requests. Bound by SIZE × N empirically:

   - 10,000 entries (initial guess): hit 1.8 GB heap (OOM verified)
   - 1,000 entries (overcorrection): no OOM but 4 tests degraded
     because the cache cleared too aggressively during legitimate
     working-set repeats (loadSecrets went 5.3 s > 5 s budget)
   - 5,000 entries (chosen): ~900 MB worst-case, fits under 2.25 GB
     heap with room for the rest of the live working set; keeps
     enough hit-rate that no test trips its perf budget.

   When the cap is hit we drop the PURE entries but keep the single-
   fire effectful ones (`evict-preserving-effectful!`) — a plain
   `.clear` could drop an already-computed side-effecting entry and
   let its effect re-fire on the next pull within the same execute
   (see that fn's doc). Single-threaded, no LRU machinery; the next
   miss repopulates the pure working set lazily."
  5000)


(def ^:private single-fire-effect-cats
  "Side-effecting effect categories whose within-execute single-fire
   RELIES on the call-cache. A fn-def that pulls a side-effecting ref
   twice (validation + success branch) fires it once ONLY because the
   second pull hits the cache (docstring at the top of this ns cites
   `:create-entity`'s double-insert → unique-violation). Unlike
   `:time`/`:random` — which bypass the cache entirely via
   `*always-fresh-fn-ids*` and re-fire by design — these ARE cached
   within one execute, so an eviction that drops such an entry re-fires
   the effect. `:env` is excluded: it is an idempotent read, not a
   mutation, so a re-fire is harmless. The set can grow if a new
   externally-observable effect category is added."
  #{:db :network :io :process :state :raw-sql})


(def ^:private rich-type-of-id-fn
  "Var handle to `registry.core/rich-type-of-id` — resolved lazily to
   break the same load cycle the other delays in this ns dodge. Read
   only on the rare cap-eviction path, so its cost is off the hot
   path. Honours `*rich-types-override*` (parallel-test isolation) via
   the registry's own view."
  (delay (requiring-resolve 'graphden.executor.registry.core/rich-type-of-id)))


(defn- effectful-ref?
  "True iff `ref-id`'s registry rich-type declares a single-fire
   side-effect (`single-fire-effect-cats`) — the entries cap-eviction
   must preserve. Keyed by IDENTITY (id-only), matching
   `compile-runtime/prime-always-fresh!`'s effect scan; a stale
   identity id with no registry entry reads as non-effectful (the same
   narrow edge the stale-name rescue closes on the trace path — it
   only bites a side-effecting fn re-pulled AFTER >cap distinct keys in
   one execute, which no real graph reaches)."
  [ref-id]
  (boolean (some single-fire-effect-cats (:effects (@rich-type-of-id-fn ref-id)))))


(defn- evict-preserving-effectful!
  "Cap-eviction that DROPS pure entries but KEEPS single-fire effectful
   ones (`effectful?` — a `ref-id → bool` predicate). A plain `.clear`
   would drop an already-computed side-effecting entry and let its
   effect re-fire on the next pull within the SAME execute; the
   per-execute cache exists precisely to make such a ref single-fire.
   Walks the key set once, removing each key (a `[ref-id projected-fa]`
   vector) whose `ref-id` head is not effectful. Effectful refs per
   execute are few, so what survives stays bounded in practice; in the
   pathological all-effectful case the cache exceeds the cap rather
   than sacrifice single-fire (correctness over the size bound)."
  [^java.util.HashMap cache effectful?]
  (let [it (java.util.Set/.iterator (java.util.HashMap/.keySet cache))]
    (while (java.util.Iterator/.hasNext it)
      (when-not (effectful? (nth (java.util.Iterator/.next it) 0))
        (java.util.Iterator/.remove it)))))


;; =============================================================================
;; Execution-path capture (Debug/observability P1) — the seam.
;;
;; `call-with-cache` is the single choke point every `:ref` invocation
;; passes through, so it is where the path trace records. Both vars it
;; consults live elsewhere: `*path-trace*` in `compile-runtime` (per-
;; execution opt-in, bound by `crud.fn-execution.persist/run-future`)
;; and `*traced-fn-ids*` above (per-fn opt-in). The `requiring-resolve`
;; delays below break the load cycle the direct `:require`s would
;; create (compile-eager ← compile-runtime ← interface ← registry.core)
;; — same precedent as `rich-type-of-id-or-stale-name-fn` /
;; `make-single-arg-callable-fn` further down this file.
;; =============================================================================

(def ^:private path-trace-var
  "Var-object handle to `compile-runtime/*path-trace*`. Deref the delay
   for the Var, deref the Var for its thread-bound value — reading
   through the Var object honours `binding` frames, so the hot path
   pays one delay-field read + one Var read, nothing else, when
   tracing is off."
  (delay (requiring-resolve 'graphden.executor.compile-runtime/*path-trace*)))


(def ^:private trace-capture-class-fn
  "Var handle to `registry.core/trace-capture-class` — the capture-time
   frame classification (`:plain` / `:secret-output` / `:secret-input`
   / `:unknown`) that decides value capture, `{:hidden …}` marking, and
   ancestor poisoning. FAIL-CLOSED: a frame with no registry entry
   classifies `:unknown` and is treated like a secret, not captured.
   Called only on the already-opted-in slow path, never when tracing
   is off."
  (delay (requiring-resolve 'graphden.executor.registry.core/trace-capture-class)))


(def max-path-trace-entries
  "Hard capture-time cap on entries in one execution's path trace —
   keeps a traced fn inside a hot loop from growing the atom without
   bound (the persist side additionally byte-caps what lands on the
   row). Oldest entries are KEPT (recording stops at the cap); the
   persist-side byte cap is the oldest-first-drop half."
  10000)


(def max-captured-value-bytes
  "Debug P3 — per-entry cap on a captured intermediate VALUE, measured
   as UTF-8 JSON bytes through the same streaming counter the result
   persistence caps use. A value over the cap (or unserializable —
   callables, atoms) is NOT captured; the entry carries
   `:value-truncated? true` instead."
  4096)


(def ^:dynamic *max-captured-value-total-bytes*
  "Debug P3 — total in-memory budget for captured values across one
   execution's trace buffer (PHILOSOPHY § Debugging constraint 5's
   per-session size limit, 16 MB per its example). Enforced AT CAPTURE
   TIME: past the budget, the OLDEST entries drop first and the
   snapshot carries `:values-dropped? true` so the user is told.
   Dynamic so tests can trip it without allocating 16 MB."
  (* 16 1024 1024))


(def value-bytes-key
  "Internal per-entry accounting key (`::value-bytes`) — the measured
   JSON byte size of the entry's captured `:value`, kept so the
   drop-oldest budget enforcement can subtract a dropped entry without
   re-serializing it. Namespaced so the persist-side snapshot can
   strip it from the wire shape."
  ::value-bytes)


(defn new-path-trace
  "Fresh per-execution path-trace state for `compile-runtime/*path-trace*`:
   `{:entries []}` plus, in value-capture mode (Debug P3),
   `:capture-values? true` and the running `:value-bytes` total. One
   atom per execution, bound by `crud.fn-execution.persist/run-future`,
   released for GC when the completion reaper snapshots it — the
   in-memory buffer's lifetime IS the execution's (constraint 5's TTL
   half for buffers; persisted rows ride `:fn-execution`'s sweeper)."
  ([] (new-path-trace nil))
  ([{:keys [capture-values?]}]
   (atom (cond-> {:entries []
                  ;; Frame bookkeeping for the call TREE: `:next-seq`
                  ;; numbers frames in ENTRY order; `:stack` holds the
                  ;; currently-open frames so a completing frame knows
                  ;; its parent (entries land in completion order, so
                  ;; without the stack the flat vector cannot be
                  ;; reassembled into a tree). Single-writer by
                  ;; construction: the trace vars are deliberately not
                  ;; conveyed to worker threads.
                  :next-seq 0
                  :stack []}
           capture-values? (assoc :capture-values? true :value-bytes 0)))))


(defn render-captured-value
  "Debug P3 — render one fresh call's return into capture-entry fields,
   through the SAME safety machinery as `:result` persistence (the
   streaming JSON byte counter): within `max-captured-value-bytes` →
   `{:value v, value-bytes-key n}`; oversize or unserializable →
   `{:value-truncated? true}` (no value — nothing partial leaks).
   NEVER called for secret-touching fns — their branch in
   `path-traced-fresh-call` records `{:hidden :secret}` without
   reading the value at all (constraint 4)."
  [v]
  (if-some [n (json-size/json-bytes-up-to v max-captured-value-bytes)]
    {:value v value-bytes-key n}
    {:value-truncated? true}))


(defn- record-path-entry!
  "Append `entry` to the trace state unless the entry-count cap is hit.
   Entries carrying a captured value (`value-bytes-key` present)
   additionally enforce the total value-bytes budget: when the running
   total would exceed `*max-captured-value-total-bytes*`, the OLDEST
   entries drop first (a contiguous suffix survives — same direction as
   the persist-side byte cap) and `:values-dropped?` marks the state."
  [trace entry]
  (swap! trace
         (fn [{:keys [entries value-bytes] :as st}]
           (if (>= (count entries) max-path-trace-entries)
             st
             (let [b (long (or (get entry value-bytes-key) 0))]
               (if (zero? b)
                 (update st :entries conj entry)
                 (loop [es entries
                        total (long (or value-bytes 0))
                        dropped? false]
                   (if (and (seq es) (> (+ total b) *max-captured-value-total-bytes*))
                     (recur (subvec es 1)
                            (- total (long (or (get (nth es 0) value-bytes-key) 0)))
                            true)
                     (cond-> (assoc st
                                    :entries (conj es entry)
                                    :value-bytes (+ total b))
                       dropped? (assoc :values-dropped? true))))))))))


(defn- enter-frame!
  "Open a call frame: draw the next entry-order seq number and push it
   onto the frame stack. Returns `[seq parent-seq]` — `parent-seq` is
   the frame that was on top (nil for a root frame). The entry itself
   is recorded at COMPLETION (with these numbers), so children recorded
   earlier can already reference this frame as their parent."
  [trace]
  (let [{:keys [stack]}
        (swap! trace (fn [{:keys [next-seq stack] :as st}]
                       (assoc st
                              :next-seq (inc (long (or next-seq 0)))
                              :stack (conj (or stack []) {:seq (long (or next-seq 0))}))))
        n (count stack)]
    [(:seq (peek stack))
     (when (> n 1) (:seq (nth stack (- n 2))))]))


(defn- exit-frame!
  "Close the top call frame and return it (carrying `:poisoned?` when a
   hidden descendant's output flowed through it). Single-writer, so the
   read-then-pop pair is race-free."
  [trace]
  (let [frame (peek (:stack @trace))]
    (when frame (swap! trace update :stack pop))
    frame))


(defn- leaf-frame!
  "Number a LEAF entry (cache hit / hidden node recorded up-front)
   without pushing a stack frame — nothing records under it before it
   completes. Returns `[seq parent-seq]` against the current stack top."
  [trace]
  (let [{:keys [next-seq stack]}
        (swap! trace (fn [st] (update st :next-seq (fnil inc 0))))]
    [(dec (long next-seq)) (:seq (peek stack))]))


(defn- poison-stack!
  "Mark every currently-open frame `:poisoned?` — a hidden node's
   output was just produced INSIDE each of them (the frame that forced
   it, and its ancestors), so their eventual return values may derive
   from the secret. Poisoned frames record `:value-hidden
   :secret-derived` instead of a captured value — the dynamic,
   trace-local complement to the static taint rules (it covers the
   statically-invisible flows: `:any` slots, cells reset to secrets,
   unregistered fns)."
  [trace]
  (swap! trace update :stack
         (fn [stack] (mapv #(assoc % :poisoned? true) stack))))


(defn- hidden-entry-kind
  "The `:hidden` wire tag for a non-`:plain` capture class."
  [cls]
  (if (= :unknown cls) :unknown-type :secret))


(defn- poisons-ancestors?
  "Does a hidden frame's OUTPUT taint its consumers? True for
   `:secret-output` (return declaredly secret) and `:unknown`
   (fail-closed — no type information at all); false for
   `:secret-input` (a trusted sink whose plain return the checker
   verified)."
  [cls]
  (or (= :secret-output cls) (= :unknown cls)))


(defn- active-path-trace
  "The bound `*path-trace*` atom when path capture applies to `ref-id`,
   else nil. First check is the nil-check on the var — the entire cost
   of the feature for untraced executions; the per-fn test only runs
   once a trace atom is bound (same cheap `contains?` shape as the
   always-fresh check). The `trace-all` sentinel (execution-scoped,
   bound by `run-future` for `trace?` submissions — Debug P2) short-
   circuits the membership test: every frame of that execution records."
  [ref-id]
  (when-some [trace (deref ^clojure.lang.Var @path-trace-var)]
    (let [ids @*traced-fn-ids*]
      (when (or (identical? trace-all ids) (contains? ids ref-id))
        trace))))


(defn- record-path-hit!
  "Record a cache-hit entry: `{:seq … :parent-seq … :fn-id …
   :cache-hit? true}` — NO `:duration-ms` (none was spent; absence,
   not 0, so a reader can distinguish 'free' from 'sub-millisecond').
   Non-`:plain` frames record `{:hidden …}` with no cache info at all,
   and a hit whose class poisons ancestors STILL poisons them — the
   memoised secret value flows to the consuming frames exactly like a
   fresh one. `ref-name` (the ref's authored row name) is the stale-id
   rescue for the classification."
  [trace ref-id ref-name]
  (let [cls (@trace-capture-class-fn ref-id ref-name)
        [n parent] (leaf-frame! trace)
        base (cond-> {:seq n :fn-id ref-id}
               (some? parent) (assoc :parent-seq parent))]
    (if (= :plain cls)
      (record-path-entry! trace (assoc base :cache-hit? true))
      (do (when (poisons-ancestors? cls) (poison-stack! trace))
          (record-path-entry! trace (assoc base :hidden (hidden-entry-kind cls)))))))


(defn- path-traced-fresh-call
  "Invoke `(child fa ctx)` recording a fresh-call entry into `trace`:
   `{:fn-id … :cache-hit? false :duration-ms <wall ms>}`. A THROWING
   frame still lands in the trace (the failing call is the one being
   debugged) — recorded on the `finally` path, without a value.
   `:duration-ms` measures the immediate invocation only — lazily-
   forced children account to whichever frame forces them.

   Value capture (Debug P3, constraint 3): when the trace state was
   built with `:capture-values?` (the `capture-values?` submit flag
   behind the UI's explicit confirm), a SUCCESSFUL return additionally
   records `render-captured-value`'s fields — `:value` within the
   4 KB per-entry cap, `:value-truncated? true` past it. Cache hits
   record no value (the fresh entry for the same `[fn-id fa]` already
   carries it).

   Tree linkage: every entry carries `:seq` (entry-order frame number)
   and `:parent-seq` (the frame that forced it; absent for roots), so
   the completion-ordered flat vector reassembles into the call tree.

   Secret skip (capture-time, per PHILOSOPHY § Debugging constraint
   4, now via `trace-capture-class`): a non-`:plain` frame records
   `{:fn-id … :hidden :secret|:unknown-type}` — no duration, no cache
   info, and the return value is NEVER read into the capture buffer
   (the renderer is not invoked on this branch, in either mode).
   `:unknown-type` is the FAIL-CLOSED arm: no registry entry means \"no
   type information\", which must hide, not capture. `:secret-output`
   and `:unknown` classes additionally POISON every open ancestor
   frame — those frames record `:value-hidden :secret-derived` instead
   of a value, the dynamic complement to the static taint rules.
   `ref-name` (the ref's authored row name) lets the classification
   rescue a stale/abandoned identity id — without it a historical id
   whose secret rich-type lives under its current name reads as
   non-secret and its return would be captured (a narrow trace leak)."
  [trace ref-id ref-name child fa ctx]
  (let [cls (@trace-capture-class-fn ref-id ref-name)]
    (if (not= :plain cls)
      ;; Hidden frame (secret-touching, or fail-closed unknown): the
      ;; entry records UP-FRONT — the return value is never read into
      ;; the buffer, in either capture mode. The frame is still pushed
      ;; so children invoked inside it nest under it in the tree, and
      ;; so poisoning (when the class taints consumers) reaches the
      ;; ancestors that will force this frame's value.
      (let [[n parent] (enter-frame! trace)]
        (when (poisons-ancestors? cls) (poison-stack! trace))
        (record-path-entry! trace
                            (cond-> {:seq n :fn-id ref-id
                                     :hidden (hidden-entry-kind cls)}
                              (some? parent) (assoc :parent-seq parent)))
        (try
          (child fa ctx)
          (finally (exit-frame! trace))))
      (let [capture? (:capture-values? @trace)
            [n parent] (enter-frame! trace)
            t0 (System/nanoTime)
            recorded? (volatile! false)
            record! (fn [value-fields]
                      (record-path-entry!
                        trace
                        (merge (cond-> {:seq n
                                        :fn-id ref-id
                                        :cache-hit? false
                                        :duration-ms (quot (- (System/nanoTime) t0)
                                                           1000000)}
                                 (some? parent) (assoc :parent-seq parent))
                               value-fields)))]
        (try
          (let [v (child fa ctx)]
            (vreset! recorded? true)
            (let [frame (exit-frame! trace)]
              (record! (cond
                         ;; A hidden descendant's output flowed through
                         ;; this frame — its value derives from it. The
                         ;; marker records in BOTH capture modes (the
                         ;; tree reader should see the derivation), the
                         ;; value in neither.
                         (:poisoned? frame) {:value-hidden :secret-derived}
                         capture? (render-captured-value v))))
            v)
          (finally
            (when-not @recorded?
              (exit-frame! trace)
              (record! nil))))))))


(defn- fresh-call
  "One fresh `(child fa ctx)` invocation — through the path-trace seam
   when capture applies to `ref-id`, bare otherwise. `ref-name` (the
   ref's authored row name) is threaded to the seam for the stale-id
   secret rescue."
  [ref-id ref-name child fa ctx]
  (if-some [trace (active-path-trace ref-id)]
    (path-traced-fresh-call trace ref-id ref-name child fa ctx)
    (child fa ctx)))


(defn- call-with-cache
  "Invoke `(child fa ctx)` through the per-execute memo. Cache key is
   `[ref-id projected-fa]` where projected-fa is `fa` restricted to
   the ref-target's declared free args. Cache miss / absent cache /
   always-fresh fn-id all fall through to a fresh call. `::nil`
   sentinel distinguishes a cached `nil` from miss.

   On reaching `call-cache-max-size` the cache evicts its PURE entries
   but keeps single-fire effectful ones (`evict-preserving-effectful!`)
   — bounding pathological per-request growth without re-firing a
   side-effecting ref pulled twice in one execute (see the size
   constant's doc).

   `ref-name` (the ref's authored row name, a compile-time constant)
   is threaded to the path-trace seam so its stale-id secret rescue
   works. The 5-arity form (no name) delegates with `nil` — used by
   focused tests and any caller without a name in hand.

   Debug P1: every arm passes the path-trace seam (`fresh-call` /
   `record-path-hit!`) — zero work beyond one nil-check unless the
   execution bound `*path-trace*` AND `ref-id` is in `*traced-fn-ids*`.
   Entries land in COMPLETION order (a callee's entry precedes its
   caller's)."
  ([ref-id ref-frees child fa ctx]
   (call-with-cache ref-id ref-frees nil child fa ctx))
  ([ref-id ref-frees ref-name child fa ctx]
   (let [^java.util.HashMap cache (::call-cache ctx)]
     (if (or (nil? cache) (contains? @*always-fresh-fn-ids* ref-id))
       (fresh-call ref-id ref-name child fa ctx)
       (let [k [ref-id (fa-key-for-cache ref-frees fa)]
             cached (java.util.HashMap/.get cache k)]
         (if (some? cached)
           (do (when-some [trace (active-path-trace ref-id)]
                 (record-path-hit! trace ref-id ref-name))
               (when-not (identical? cached ::nil) cached))
           (let [v (fresh-call ref-id ref-name child fa ctx)]
             (when (>= (java.util.HashMap/.size cache) call-cache-max-size)
               (evict-preserving-effectful! cache effectful-ref?))
             (java.util.HashMap/.put cache k (if (nil? v) ::nil v))
             v)))))))


(defn- has-impl?
  "Root-fn carries a registered Clojure impl. Type-rows return false
   and never enter the compile pipeline."
  [fn-id {:keys [fn-map base-fns] :as lookups}]
  (boolean (some-> (l/root-fn fn-id fn-map lookups)
                   :name keyword
                   base-fns)))


(defn- supported-shapes?
  "True iff every binding shape `fn-id` carries is supported by the
   current compile-eager stage. With Stage 4 every classify-slot
   kind (`:value` / `:free` / `:ref` / `:seq` /
   `:resolved-value`)
   has a builder, so every fn whose root has an impl is now
   compilable — this check stays here as a guard against future
   `classify-slot` additions until they get their builder."
  [fn-id lookups]
  (every? (fn [bnd]
            (case (:kind bnd)
              (:value :free :seq :ref :resolved-value) true
              false))
          (b/collect-bindings fn-id lookups)))


(defn- ref-deps
  "Set of fn-ids that compile of `fn-id` depends on at runtime.

   Looks at `b/collect-bindings` AND `b/collect-env-bindings`
   (the SAME sources of truth `compile-fn` uses), so inherited
   bindings and env-bindings (context-propagation slots like
   `:base-handler`) are included. Anything ref'd inside a `:seq`
   binding is included via the binding-list-item rows that
   `collect-bindings` materialises under `:items`."
  [fn-id lookups]
  (let [from-bnd (fn [acc bnd]
                   (case (:kind bnd)
                     :ref (conj acc (:ref-id bnd))
                     :seq (into acc (keep :ref-fn-id (:items bnd)))
                     acc))]
    (reduce from-bnd
            (reduce from-bnd #{} (b/collect-bindings fn-id lookups))
            (b/collect-env-bindings fn-id lookups))))


(defn- resolve-impl
  [fn-id {:keys [fn-map base-fns] :as lookups}]
  (let [root-name (some-> (l/root-fn fn-id fn-map lookups) :name keyword)]
    (or (get base-fns root-name)
        (throw (ex-info (str "No impl for base-fn " (pr-str root-name))
                        {:type :compile/missing-impl
                         :fn-id fn-id :base-fn-name root-name})))))


(defn- force-value
  "Force a deferred value read out of `free-args`. Refs propagate
   through `free-args` as `rt/thunk`s or `delay`s so the impl's
   `resolve-arg` can short-circuit — anything BUT `resolve-arg`
   that reads a free-arg value (seq item positional renames being
   the only path) must force here so the consumer sees the
   underlying value."
  [v]
  (cond
    (rt/thunk? v) (v)
    (instance? clojure.lang.IDeref v) @v
    :else v))


(defn- seq-item-builder
  "Compile one `binding-list-item` row into a `(fn [fa ctx])`
   producing the item's runtime value. Four shapes:
     - `{:value {:as :name} :literal nil}` — positional free-arg
       substitution (`:route :args :items [{:as :path} ...]`);
       reads via `force-value` so a delay in `free-args` gets
       forced before the consumer sees it.
     - `:value` present — literal value.
     - `:ref-fn-id` — invoke pre-compiled child callable.
     - everything nil — literal `nil`.

   `owner-fn-id` is the fn-id whose seq binding contains this item.
   The positional `{:as :name}` resolves to that owner's OWN rename
   slot (parser creates one per rename); the reader indexes `fa`
   by that rename slot's id — pure slot-id, no name fallback."
  [item child-callables lookups owner-fn-id]
  (cond
    (and (map? (:value item))
         (:as (:value item))
         (not (:literal item)))
    (let [k (keyword (:as (:value item)))
          ;; Phase 4 — rename-aware slot-id from owner's own rename
          ;; slot for this `:as` name. Falls back to name fallback
          ;; below when the rename slot doesn't exist (some seq
          ;; binding shapes don't produce slot rows via parser).
          sid (some-> (get (:slot-by-fn-name lookups) [owner-fn-id k])
                      :id)]
      (if sid
        (fn [fa _ctx]
          (let [v (get fa sid ::miss)]
            (force-value (if (identical? v ::miss) (get fa k) v))))
        (fn [fa _ctx] (force-value (get fa k)))))

    (some? (:value item))
    (constantly (:value item))

    (:ref-fn-id item)
    (let [ref-id (:ref-fn-id item)
          child (or (get child-callables ref-id)
                    (throw (ex-info "compile-eager: seq-item ref-target not compiled"
                                    {:type :compile/missing-child :item item})))
          ref-frees (set (r/cache-projection-frees ref-id lookups))
          ref-name (get-in lookups [:fn-map ref-id :name])]
      (fn [fa ctx] (call-with-cache ref-id ref-frees ref-name child fa ctx)))

    :else (constantly nil)))


(defn make-shape-callable
  "Build the Clojure callable a HOF expects, given a 0/1/many
   `lambda-params` shape (see `r/hof-lambda-params` +
   CLOSURE_CAPTURE.md):

   - 0 → variadic-ignore: `:future :body` / `:loop-until-interrupted`.
   - 1 → single-arg: caller passes one value, target sees it under
         the lambda-param's name. `:map`'s `:func`, `:filter`'s `:pred`.
   - 2+ → map-callable: caller passes `{lambda-name → value}`.

   `invoke-with` is the bridge: it gets a NAME-keyed map of the
   per-call lambda values (`nil` for the 0-arg variant) and returns
   the callable's return value. All three call sites in the executor
   — root-binding `hof-wrap`, env-binding HOF case, and the public
   `make-single-arg-callable` entry — feed different env sources
   through this same shape decision."
  [lambda-params invoke-with]
  (case (count lambda-params)
    0 (fn [& _] (invoke-with nil))
    1 (let [k (first lambda-params)]
        (fn [item] (invoke-with {k item})))
    invoke-with))


(defn- apply-hof-translation
  "Apply HOF wrap-time slot-id translation to fa.

   `translation` is `{R-slot-id F-source-key}` — for each R-slot-id
   the callee R reads, the source key in the captured F-side fa.
   Phase 5 conservative scope: sources are ext-name keywords
   (caller-supplied free args whose name lives in fa). A future
   extension may also map R-slot-ids from F-slot-ids for cross-fn
   rename cascades.

   Empty translation short-circuits — fa passes through.

   PRESENCE — not truthiness — is the copy gate. A caller passing
   `:body nil` or `:flag false` deserves to land under R's slot-id
   just like any other value.

   THUNKS are skipped at copy: an env-binding write puts a deferred
   `(rt/thunk …)` under `fa[name]` whose body calls `call-with-cache`
   on a ref. Copying that thunk under R's slot-id would let an inner
   reader find it via slot-id AND name fallback — and the inner ref
   target may itself trigger the same env-binding chain, causing
   `call-with-cache` to miss (mid-computation) and recurse to
   StackOverflow. Caller-supplied free args are plain values; the
   thunk-skip preserves their copy while keeping env-binding writes
   on their existing name-fallback path."
  [fa translation]
  (if (empty? translation)
    fa
    (reduce-kv (fn [acc r-sid src]
                 (cond
                   (contains? acc r-sid) acc
                   (contains? acc src)
                   (let [v (get acc src)]
                     (if (rt/thunk? v)
                       acc
                       (assoc acc r-sid v)))
                   :else acc))
               fa translation)))


(defn- hof-wrap
  "Root-binding HOF: returns a `(fn [fa ctx])` whose call yields the
   callable. The callable closes over `fa` (the wrap-time snapshot of
   the caller's env). lambda-args are merged name-keyed; the slot-id
   readers in R fall back to name lookups when no slot-id key is
   present, so the dynamic lambda values flow correctly.

   `translation` (Phase 5) propagates F-side slot-id keys (and
   ext-name keys for caller args that didn't reach F's walker surface)
   into R's slot-id namespace — required for cross-fn rename cascades
   (e.g. `:method-map :handler` rename slot → `:assoc-handler :handler`
   rename slot have different ids) to survive the wrap."
  [child lambda-params translation]
  (if (empty? translation)
    (fn [fa ctx]
      (make-shape-callable lambda-params
                           (fn [lambda-args]
                             (child (if lambda-args (merge fa lambda-args) fa)
                                    ctx))))
    (fn [fa ctx]
      (let [fa* (apply-hof-translation fa translation)]
        (make-shape-callable lambda-params
                             (fn [lambda-args]
                               (child (if lambda-args (merge fa* lambda-args) fa*)
                                      ctx)))))))


(def ^:private rich-type-of-id-or-stale-name-fn
  (delay (requiring-resolve
           'graphden.executor.registry.core/rich-type-of-id-or-stale-name)))


(defn- compile-time-value-root?
  "True iff `fn-id`'s root base-fn is registered `:compile-time-value?`
   — the marker (from the impls.clj registry, threaded through
   `record-rich-types!`) that says: evaluate this fn ONCE at compile
   time and bake `(constantly result)`. Backs `:cell`'s registry-
   persistent atom."
  [fn-id {:keys [fn-map] :as lookups}]
  (let [root (l/root-fn fn-id fn-map lookups)]
    (boolean (some-> (@rich-type-of-id-or-stale-name-fn (:id root)
                                                        (:name root))
                     :compile-time-value?))))


(defn- compile-time-value-closure
  "Decide how to compile a fn whose root base-fn is
   `:compile-time-value?` (e.g. anything parenting `:cell`), given its
   classified `enriched` bindings and its assembled runtime closure
   `run`:

   - EVERY binding a literal (`:value`) → the value is a compile-time
     constant: evaluate `run` ONCE now and bake `(fn [_ _] result)` so
     every invocation, across every `execute` this compiled registry
     serves, hands back the SAME instance (that's `:cell`'s persistent
     atom). Empty `fa` + bare `ctx`: `:value` builders are
     `(constantly v)` and the impl is effect-free by contract.
   - Otherwise (a `:ref` / `:seq` / `:free` binding — a runtime or
     unbound value) → there is no compile-time constant to bake, so
     compile NORMALLY; the fn then behaves like a per-call `:atom`
     (a fresh instance each `execute`). Persistence requires a pinned
     literal — degrade gracefully rather than throw, since a single
     non-literal `:cell` must not fail the whole-registry compile-all."
  [_fn-id enriched run]
  (if (and (seq enriched) (every? #(= :value (:kind %)) enriched))
    (let [baked (run {} {})]
      (fn [_fa _ctx] baked))
    run))


(def ^:private make-single-arg-callable-fn
  (delay (requiring-resolve
           'graphden.executor.compile-runtime/make-single-arg-callable)))


(defn- lazy-seq-of-values
  "Lazy-seq that materialises each item by calling its builder only
   when the consumer pulls the cons-cell — matches Clojure-native
   lazy seqs. This is what makes `:and` / `:or` short-circuit
   through their `:items` seq slot: `every?` / `some` walk the seq
   and stop at the first decisive element, so later builders never
   fire."
  [item-builders fa ctx]
  (letfn [(walk
            [i]
            (lazy-seq
              (when (< i (count item-builders))
                (cons ((nth item-builders i) fa ctx)
                      (walk (inc i))))))]
    (walk 0)))


(defn- arg-builder
  "Return `(fn [free-args ctx])` producing the value for one
   classified binding.

   Lazy semantics are built into the model the way Clojure does
   them — `:ref` bindings ALWAYS produce a `delay`, and the impl
   reads the arg through `rt/resolve-arg` which auto-derefs
   `IDeref`. Inside the impl, `(if test then else)` short-circuits
   because Clojure's native `if` only evaluates the picked form,
   so only its `resolve-arg` call runs, and only its delay forces.
   The un-taken branch's `delay` stays unforced — its side-effects
   never fire. No `:lazy?` flag, no `:lazy-args` registration:
   ordinary Clojure evaluation does it.

   `:seq` materialises as an unchunked lazy-seq of values
   (Clojure-native short-circuit through `every?` / `some`).
   `lazy-seq?` slots wrap each item in `delay` for consumers like
   `cond-fn` that step past unforced items via `nnext`."
  [fn-id
   {:keys [kind ext-name value ref-id is-fn produces-callable? ref-renames
           items lazy-seq? slot-id binder-fn-id resolver-id stored]
    :as bnd}
   child-callables
   lookups]
  (case kind
    :value (constantly value)
    ;; Phase 4 — slot-id reader with name fallback.
    ;;
    ;; The rename-aware reader id (`l/effective-reader-slot-id`) is
    ;; the PRIMARY key: two inline-anons of the same base-fn with
    ;; their own `{:as :X}` renames have the SAME chain-leaf but
    ;; DIFFERENT rename-slot ids, so each anon's reader finds its
    ;; own caller value via slot-id without name collision.
    ;;
    ;; Name fallback covers paths that still write `fa` by name:
    ;;   - env-builder writes `fa[env-name]` (per-fn synthetic
    ;;     shared computations like `:_request-parsed`)
    ;;   - hof-wrap's `make-shape-callable` merges `lambda-args`
    ;;     under lambda-param names (per-call values)
    ;;   - `build-ref-renames` slow path copies caller→callee names
    ;; These name keys flow through the same `fa` and the reader's
    ;; fallback finds them when there's no slot-id key. The fa is
    ;; thus hybrid: slot-id keys distinguish structural ambiguity at
    ;; the boundary translator, name keys cover dynamic flows. This
    ;; is the architecture, not a transitional kludge — the two key
    ;; spaces serve different needs.
    :free  (let [sid (l/effective-reader-slot-id fn-id slot-id lookups)
                 k ext-name]
             (fn [fa _ctx]
               (let [v (get fa sid ::miss)]
                 (if (identical? v ::miss) (get fa k) v))))
    ;; Generic resolver: evaluate the resolver graph fn with the stored
    ;; value as its single argument, lazily at first read. Reuses the
    ;; 1-arg callable machinery — a resolver is just a fn usable as a
    ;; single-arg callable (`:str-upper`, `:vault-get`, anything).
    :resolved-value
    (let [rid resolver-id sv stored]
      (fn [_fa ctx]
        (rt/thunk
          (fn []
            ((@make-single-arg-callable-fn ctx rid) sv)))))

    :ref
    (let [child (or (get child-callables ref-id)
                    (throw (ex-info "compile-eager: ref-target not yet compiled"
                                    {:type :compile/missing-child
                                     :binding bnd :ref-id ref-id
                                     :fn-id fn-id})))
          ref-frees (set (r/cache-projection-frees ref-id lookups))
          ref-name (get-in lookups [:fn-map ref-id :name])]
      (cond
        ;; HOF binding where the slot's structural shape is
        ;; `[:fn {…} …]` and the target is NOT itself a callable-
        ;; producer: build the Clojure closure the consumer will
        ;; call positionally. Value-shape, not delay-shape — the
        ;; impl invokes it directly.
        (and is-fn (not produces-callable?))
        (let [lambda-params (r/hof-lambda-params ref-id slot-id bnd fn-id lookups)
              translation (r/build-hof-translation ref-id lambda-params lookups)]
          (hof-wrap child lambda-params translation))

        ;; Two collapse into one — both want "invoke child with the
        ;; caller's env, wrap in a thunk for short-circuit":
        ;; - `:produces-callable?`: target's fn-graph evaluates to a
        ;;   Clojure callable (`:_router` → ring-handler). Wrapping
        ;;   means the router builds only when the impl actually
        ;;   reads the arg.
        ;; - non-renamed plain ref: the common case — no caller→
        ;;   callee free-arg translation needed.
        ;;
        ;; `rt/thunk` (a fn with `::thunk` meta) rather than `delay`
        ;; here: `resolve-arg` auto-calls it for impls that read args
        ;; via the `defbase` macro AND impls that read raw
        ;; (`((:body args))` — the closure-capture acceptance test)
        ;; can still invoke the value as a 0-arg fn.
        (or produces-callable? (empty? ref-renames))
        (fn [fa ctx]
          (rt/thunk (fn [] (call-with-cache ref-id ref-frees ref-name child fa ctx))))

        :else
        (fn [fa ctx]
          (rt/thunk (fn []
                      (call-with-cache
                        ref-id ref-frees ref-name child
                        (reduce-kv (fn [acc callee-name caller-name]
                                     (assoc acc callee-name (get fa caller-name)))
                                   fa ref-renames)
                        ctx))))))
    :seq
    (let [item-builders (mapv #(seq-item-builder % child-callables lookups
                                                 (or binder-fn-id fn-id))
                              items)]
      (if lazy-seq?
        (fn [fa ctx]
          (map (fn [b] (delay (b fa ctx))) item-builders))
        (fn [fa ctx]
          (lazy-seq-of-values item-builders fa ctx))))
    (throw (ex-info (str "compile-eager: unsupported binding kind " kind)
                    {:type :compile/unsupported-kind :binding bnd}))))


(defn- env-arg-builder
  "Build the value that lands under one env-binding's env-name in
   `fa'`. Different shape from `arg-builder` because env-bindings
   need to participate in a SHARED env (sibling env-bindings can
   reference each other in any order).

   Returns `(fn [fa-ref ctx])` — a thunk that reads from the
   volatile `fa-ref` at FORCE time, so the env map it sees is
   the final one (all env-bindings populated), not the partial
   snapshot at construction time. For `:value` bindings we just
   return the literal — no closure needed."
  [fn-id env-bnd child-callables lookups]
  (case (:kind env-bnd)
    :value (let [v (:value env-bnd)] (fn [_fa-ref _ctx] v))

    ;; List binding on a deep (renamed) sequence slot — same item
    ;; builders as the root `:seq` kind, reading the shared env at
    ;; force time. Lazy when the owning root declared the slot so
    ;; (`:do`'s `:steps`): the consumer forces the delays in order.
    :seq
    (let [{:keys [items lazy-seq? binder-fn-id]} env-bnd
          item-builders (mapv #(seq-item-builder % child-callables lookups
                                                 (or binder-fn-id fn-id))
                              items)]
      (if lazy-seq?
        (fn [fa-ref ctx]
          (map (fn [b] (delay (b @fa-ref ctx))) item-builders))
        (fn [fa-ref ctx]
          (lazy-seq-of-values item-builders @fa-ref ctx))))

    :ref
    (let [{:keys [ref-id is-fn produces-callable? slot-id]} env-bnd
          child (or (get child-callables ref-id)
                    (throw (ex-info "compile-eager: env-binding ref not yet compiled"
                                    {:type :compile/missing-child
                                     :env-binding env-bnd :fn-id fn-id})))
          ref-frees (set (r/cache-projection-frees ref-id lookups))
          ref-name (get-in lookups [:fn-map ref-id :name])]
      (cond
        ;; HOF env-binding whose target ISN'T itself a callable-
        ;; producer: build the closure-captured Clojure callable.
        ;; Reads `fa-ref` at FORCE time (sibling env-bindings may
        ;; not have populated yet at construction).
        (and is-fn (not produces-callable?))
        (let [lambda-params (r/hof-lambda-params ref-id slot-id env-bnd fn-id lookups)
              translation (r/build-hof-translation ref-id lambda-params lookups)]
          (fn [fa-ref ctx]
            (make-shape-callable
              lambda-params
              (fn [lambda-args]
                (let [fa* (apply-hof-translation @fa-ref translation)]
                  (child (if lambda-args (merge fa* lambda-args) fa*)
                         ctx))))))

        ;; Target evaluates to a callable (`:_router` → reitit
        ;; ring-handler). Same as the regular `arg-builder` :ref
        ;; path: don't hof-wrap a positional callable.
        produces-callable?
        (fn [fa-ref ctx]
          (rt/thunk (fn [] (call-with-cache ref-id ref-frees ref-name child @fa-ref ctx))))

        :else
        (let [renames (r/build-ref-renames ref-id fn-id lookups)]
          (if (empty? renames)
            (fn [fa-ref ctx]
              (rt/thunk (fn [] (call-with-cache ref-id ref-frees ref-name child @fa-ref ctx))))
            (fn [fa-ref ctx]
              (rt/thunk (fn []
                          (call-with-cache
                            ref-id ref-frees ref-name child
                            (reduce-kv
                              (fn [acc cn cln] (assoc acc cn (get @fa-ref cln)))
                              @fa-ref
                              renames)
                            ctx))))))))))


(defn compile-fn
  "Return `(fn [free-args ctx])` for `fn-id`. `child-callables` is
   `{fn-id → callable}` for ref-targets, populated in topological
   order by `compile-all`.

   Env-bindings (bindings on slots that AREN'T root slots — used to
   propagate values like `:base-handler` through the ref-tree to
   inner consumers) participate in a shared `fa-ref` volatile.
   They evaluate to `delay`s whose closures read the volatile at
   FORCE time, so an env-binding `A` that needs another env-binding
   `B`'s value (forwards-reference, the order they're declared in
   doesn't constrain dependencies) sees the final `fa'` map — same
   semantics as the legacy compile's `augment-env`. Without this,
   `:types-compatible`'s `:_rejected?` closure (which needs
   `:validation` from the same env layer) sees an empty `:validation`
   slot and reports every well-formed request as rejected, even
   though the API path is correct."
  ([fn-id lookups]
   (compile-fn fn-id lookups {}))
  ([fn-id lookups child-callables]
   (let [impl (resolve-impl fn-id lookups)
         enriched (mapv (fn [bnd]
                          (if (and (= :ref (:kind bnd))
                                   (not (:is-fn bnd)))
                            (assoc bnd :ref-renames
                                   (r/build-ref-renames (:ref-id bnd)
                                                        fn-id
                                                        lookups))
                            bnd))
                        (b/collect-bindings fn-id lookups))
         builders (mapv #(arg-builder fn-id % child-callables lookups) enriched)
         keys-vec (mapv :base-name enriched)
         n (count builders)
         env-bnds (b/collect-env-bindings fn-id lookups)
         env-builders (mapv #(env-arg-builder fn-id % child-callables lookups)
                            env-bnds)
         env-names (mapv :env-name env-bnds)
         env-n (count env-bnds)
         ;; Compile-time-derived runtime aliasing for this fn's own
         ;; rename slots. When a rename like `{:as :item}` surfaces
         ;; a deep slot (`:branch-row` from the ref-tree) under a
         ;; renamed outer name, downstream refs still read by the
         ;; deep name — `apply-rename-aliases` copies the
         ;; caller-supplied rename value back to the deep name so
         ;; the lookup succeeds. Empty aliases (fns without own
         ;; rename slots — the common case) short-circuit at apply.
         rename-aliases (r/compute-rename-aliases fn-id lookups)
         ;; Top-level entry: install a fresh per-execute call-cache in
         ;; ctx if none is in scope yet. Nested closure calls inherit
         ;; the outer cache through ctx, so all siblings memoise on
         ;; `(ref-id × fa)`. `HashMap` (not `clojure.lang.PersistentMap`)
         ;; — one-cache-per-call, single-threaded read/write inside one
         ;; top-level closure.
         run
         (fn [fa ctx]
           (let [;; Prime a fresh per-call cache for sibling `:ref`
                 ;; memoisation, UNLESS:
                 ;;  - one is already in scope (inherited from an enclosing
                 ;;    execute) — reuse it; or
                 ;;  - this is a router-BUILD execute flagged by
                 ;;    `defer-handler-call-cache` — prime NO shared cache and
                 ;;    KEEP the flag, so every nested run down to where the
                 ;;    `:fn` route handlers are `hof-wrap`ped stays cache-less
                 ;;    too (consuming the flag here would let those nested runs
                 ;;    re-prime a cache the handlers then capture). The flag
                 ;;    rides along on the captured ctx, so each later REQUEST
                 ;;    through such a handler also runs cache-less (every `:ref`
                 ;;    a fresh call) — correct + per-principal, trading away
                 ;;    within-request memo for these low-traffic admin routes.
                 ctx (let [rc *request-call-cache*]
                       (cond
                         ;; Per-scope override active (an `:http-server`
                         ;; request or `:future` worker bound a fresh cache):
                         ;; install ITS map, overriding any build-time cache
                         ;; this ctx captured — that captured map is shared by
                         ;; every invocation of a build-time handler and races
                         ;; a CME under concurrency. Idempotent for nested runs
                         ;; (ctx already carries `rc` → the next clause returns
                         ;; it unchanged).
                         (and rc (not (identical? (::call-cache ctx) rc)))
                         (assoc ctx ::call-cache rc)

                         (or (::call-cache ctx) (skip-cache-priming-key ctx))
                         ctx

                         :else
                         (assoc ctx ::call-cache (java.util.HashMap.))))
                 fa (r/apply-rename-aliases fa rename-aliases)
                 ;; The `fa-ref` volatile ONLY exists so env-binding delays can
                 ;; read the post-merge map at force time (forward references
                 ;; between env-bindings). Fns with no env-bindings — the common
                 ;; case — never read it, so skip the per-call allocation entirely.
                 fa' (if (zero? env-n)
                       fa
                       (let [fa-ref (volatile! fa)
                             merged (loop [m fa, i 0]
                                      (if (< i env-n)
                                        (recur (assoc m (nth env-names i)
                                                      ((nth env-builders i) fa-ref ctx))
                                               (inc i))
                                        m))]
                         (vreset! fa-ref merged)
                         merged))]
             (impl (persistent!
                     (loop [acc (transient {}), i 0]
                       (if (< i n)
                         (recur (assoc! acc
                                        (nth keys-vec i)
                                        ((nth builders i) fa' ctx))
                                (inc i))
                         acc)))
                   ctx)))]
     ;; `:cell` (and any `:compile-time-value?` base-fn): evaluate once
     ;; here and bake `(constantly result)`, so the atom persists across
     ;; every `execute` this compiled registry serves.
     (if (compile-time-value-root? fn-id lookups)
       (compile-time-value-closure fn-id enriched run)
       run))))


;; =============================================================================
;; compile-all — topological pass over the whole graph
;; =============================================================================

(defn- topo-sort
  "Kahn's algorithm over `{fn-id → #{dep-fn-id}}` — returns a vector
   of fn-ids in compile order (deps first). Throws on cycles (which
   storage-level constraints should already rule out — second line
   of defence)."
  [deps]
  (let [in-deg (into {} (map (fn [[fid ds]] [fid (count ds)])) deps)
        dependents-of (reduce-kv (fn [acc fid ds]
                                   (reduce #(update %1 %2 (fnil conj []) fid) acc ds))
                                 {}
                                 deps)]
    (loop [sorted (transient [])
           in-deg in-deg
           ready (into #{} (keep (fn [[k v]] (when (zero? v) k))) in-deg)]
      (if (empty? ready)
        (if (= (count sorted) (count deps))
          (persistent! sorted)
          (throw (ex-info "compile-eager: cycle in ref-DAG"
                          {:type :compile/cycle
                           :remaining (vec (remove (set (persistent! sorted)) (keys deps)))})))
        (let [fid (first ready)
              [in-deg' ready']
              (reduce (fn [[id rd] d]
                        (let [n (dec (get id d))]
                          [(assoc id d n) (cond-> rd (zero? n) (conj d))]))
                      [in-deg (disj ready fid)]
                      (get dependents-of fid))]
          (recur (conj! sorted fid) in-deg' ready'))))))


(defn- prune-unresolvable
  "Fixed point over `candidates`: keep a fn only while every one of
   its ref-deps resolves to either an `available` id (something
   compiled OUTSIDE this set — e.g. a pinned `existing-registry`
   entry) or a surviving candidate. Drops — transitively — any fn
   that refs an id resolvable through neither. This is property (c)
   of `reachable-targets`, factored out so `compile-subset` shares
   the exact same reachability discipline as the full compile:
   a delta must never try to compile a fn the full path would
   refuse (its `:ref` arm would throw `:compile/missing-child`)."
  [candidates available lookups]
  (loop [surviving (set candidates)]
    (let [next-surviving
          (into #{}
                (filter (fn [fid]
                          (every? (fn [dep]
                                    (or (contains? available dep)
                                        (contains? surviving dep)))
                                  (ref-deps fid lookups))))
                surviving)]
      (if (= next-surviving surviving)
        surviving
        (recur next-surviving)))))


(defn- reachable-targets
  "Fixed-point: fn-id is `target` iff (a) its root carries an impl,
   (b) every binding shape it uses is supported by this stage, and
   (c) every fn-id it refs is also `target`. (c) makes the
   exclusion of fns transitively dependent on an unsupported one
   explicit; the seed covers (a) + (b)."
  [lookups]
  (let [seed (into #{}
                   (comp (map :id)
                         (filter #(and (has-impl? % lookups)
                                       (supported-shapes? % lookups))))
                   (vals (:fn-map lookups)))]
    ;; No pinned externals in a full compile — a ref must resolve to
    ;; another target or it's dropped.
    (prune-unresolvable seed #{} lookups)))


(defn- compile-all*
  [lookups]
  (let [targets (reachable-targets lookups)
        deps (into {}
                   (map (fn [fid] [fid (ref-deps fid lookups)]))
                   targets)
        order (topo-sort deps)]
    (reduce (fn [acc fid]
              (assoc acc fid (compile-fn fid lookups acc)))
            {}
            order)))


;; ============================================================================
;; Process-wide cache for `compile-all` output
;; ============================================================================
;;
;; compile-eager closures are ctx-INDEPENDENT (ctx arrives at execute time,
;; not compile time), so two storages that present the same graph + the same
;; base-fn registry compile to the SAME `{fn-id → closure}` map. Cache it
;; per (graph-content × base-fn-name-set) — first JVM-wide call pays the
;; full ~8 s compile pass; sister contexts (test ns's that bootstrap the
;; same package set, sibling branches with identical graph views) hit warm
;; in < 1 ms.
;;
;; Bounded LRU — 4 entries comfortably cover {dev system + a couple of
;; branches + a test bootstrap} without holding stale registries forever.

;; 2 (was 4): each cached registry holds ~3000 closure references,
;; and each closure captures references to its parent lookups
;; (fn-map / slot-map / 4 index maps / 4 lazy atom caches). 4
;; generations was ~10MB of accumulating heap that mostly never got
;; queried — the cache hits are dominated by repeat calls within the
;; SAME compilation window (sister branches share a graph snapshot)
;; rather than across windows. Dropping to 2 cuts heap pressure
;; without losing the dominant hit case (current branch + base
;; branch).
;;
;; 2 was re-tested against 4 on the unit suite once the hit/miss counters
;; existed, because a 7% hit rate looked like a cache that had been sized into
;; uselessness. It hadn't:
;;
;;              misses   hits   suite fixture   the 3 golden NSes
;;   size 2       101     10        239 s        78.8 / 77.3 s
;;   size 4       100     11        214 s        69.3 / 68.9 s
;;
;; One fewer miss, and both totals inside the run-to-run band (237/245/258/231
;; across four baseline runs). Size is not what those namespaces are waiting on:
;; a ~2600-fn compile takes ~55-60 s, `compile-all`'s delay already coalesces
;; the three of them onto ONE of those, and the other two simply BLOCK on it —
;; which is why each still reads ~70 s (14 s golden bootstrap + ~60 s compile)
;; while only one compile is actually running. A bigger cache cannot help a
;; queue for work that has to happen once. Making the compile itself cheaper
;; could; nothing here does.
(def ^:private compile-all-cache-max-size 2)


(def ^:private compile-all-cache
  "Bounded FIFO `[[key compiled] ...]` — oldest first, newest last.

   FIFO, not LRU: a hit reads the entry and leaves it where it is, so two
   compiles of unrelated graphs evict a third that is being hit constantly.
   This said \"LRU\" for its whole life and never promoted anything.

   Left as a FIFO deliberately. Proper LRU is a few lines, but the measurement
   above says eviction is not costing this suite anything — so it would be a
   behaviour change with no evidence behind it, which is how the last three
   plausible fixes in this area went. The name is what was wrong; the name is
   what is fixed."
  (atom []))


(def ^:private effective-rich-types-fn
  ;; requiring-resolve — same cycle-avoidance as
  ;; `rich-type-of-id-or-stale-name-fn` above.
  (delay (requiring-resolve
           'graphden.executor.registry.core/effective-rich-types)))


(defn- compile-all-cache-key
  "Hash of (graph shape × base-fn name set × ambient rich-types).
   Same key ⇒ same compile output. Picks the same per-entity field set
   the registry already relies on for identity (mutable timestamps /
   generated UUIDs that don't affect compile output stay out).

   Rich-types are IN the key because compile output depends on them:
   `produces-callable?` (fed by the swept `:return-type` entries)
   decides whether a HOF wrap captures the produced callable or the
   builder closure. Keyed without them, a caller compiling under
   swept types could be served a compile made under unswept types by
   a sibling with the identical graph — the served closures then
   classcast at execute time (`AFunction cannot be cast to
   Associative`), which is exactly how the fixture-diet landing
   failed. Value-hash (not identity) so equal snapshots still share."
  [{:keys [fn-map slot-map fn-slots-by-fn bindings-by-fn items-by-binding
           base-fns]}]
  (hash [(set (vals fn-map))
         (set (vals slot-map))
         (set (mapcat val fn-slots-by-fn))
         (set (mapcat val bindings-by-fn))
         (set (mapcat val items-by-binding))
         (set (keys base-fns))
         (@effective-rich-types-fn)]))


(defn compile-all
  "Compile every fn-row whose ref-DAG bottoms out at base-fn impls.
   Returns `{fn-id → (fn [free-args ctx])}`. Cycle in ref-DAG →
   throws (second line of defence over the storage constraint).

   `lookups` MUST already carry `:base-fns` (the impl registry).

   Cached on a process-wide bounded LRU keyed by graph-shape +
   base-fn name set — sister callers (test ns's that bootstrap the
   same package set, sibling branches with identical graph views)
   skip the compile pass entirely and just retrieve the
   ctx-independent closure map.

   The cache holds DELAYS, not values, and that is what makes the
   cache work for CONCURRENT sister callers rather than only
   sequential ones. It used to read the atom, miss, compile, then
   write — check-then-act. Three namespaces released together from
   `ensure-golden!`'s lock therefore all missed the same cold key and
   all compiled the same ~2600-fn graph at once. Measured fingerprint:
   79.1 s / 77.7 s / 75.7 s of fixture, three different workloads
   agreeing to within 4% because they were not different workloads at
   all — they were one compile, run three times, contending.

   With a delay, the `swap!` decides the winner: a loser's swap
   function re-runs against the winner's value, finds the key present,
   and returns the vector untouched, so its own unrun delay is
   discarded. Everyone then derefs the SAME delay — one compile, the
   rest blocked on it. A global lock would also fix the dogpile but
   would serialise compiles of genuinely DIFFERENT graphs, which is
   the case this cache exists to make fast."
  [lookups]
  (let [k (compile-all-cache-key lookups)
        installed? (volatile! false)
        cache (swap! compile-all-cache
                     (fn [v]
                       (if (some (fn [[ck _]] (= ck k)) v)
                         (do (vreset! installed? false) v)
                         (do (vreset! installed? true)
                             (conj (vec (take-last (dec compile-all-cache-max-size) v))
                                   [k (delay (compile-all* lookups))])))))
        d (some (fn [[ck cv]] (when (= ck k) cv)) cache)]
    ;; Counted here, not at `rebuild!`, because `rebuild!` is the ASK and this is
    ;; the WORK. Three namespaces each calling `rebuild!` is three asks and — if
    ;; this cache is doing its job — one compile. A counter on the ask cannot
    ;; tell those apart, which is exactly how the dogpile went unseen: the
    ;; suite's 107 rebuilds looked identical before and after it was fixed.
    ;;
    ;; The `volatile!` reads oddly next to a `swap!` whose function must be pure.
    ;; It is: `swap!` may retry and re-run the fn, and each run overwrites the
    ;; flag, so the LAST run — the one whose value was actually installed — is
    ;; the one that decides. That is precisely the answer we want.
    (counters/count! (if @installed? :compile/all-miss :compile/all-hit))
    (try
      @d
      (catch Exception t
        ;; A delay memoises its exception, so a transient failure would be
        ;; served to every later caller of this key forever. Evict, and let the
        ;; next one compile again — the old code could not cache a failure at
        ;; all (it wrote only after a successful compile), and that property is
        ;; worth keeping. `Exception`, not `Throwable`: an Error here means the
        ;; JVM is already going down, and a poisoned cache entry is not the
        ;; problem worth solving on the way.
        (swap! compile-all-cache (fn [v] (filterv (fn [[ck _]] (not= ck k)) v)))
        (throw t)))))


(defn reset-compile-all-cache!
  "Test hook — drop every cached entry. Useful for tests that
   intentionally mutate the same graph mid-deftest to verify
   compile-time re-classification, since the cache would otherwise
   short-circuit a second compile pass."
  []
  (reset! compile-all-cache []))


(defn compile-subset
  "Recompile a SUBSET of fn-ids on top of `existing-registry`. Used
   by `delta-recompile!`: only the blast radius needs new closures,
   but those closures may reference each other AND existing entries
   from outside the blast.

   Topologically sorts the subset by inter-blast deps so a fn
   compiled later in the subset sees freshly-built children, not
   the pre-mutation copies. Subset entries dependent on each other
   compile in dependency order; entries whose deps live outside
   the subset pick those up from `existing-registry`.

   Skips fn-ids whose root has no registered impl (type-rows,
   anonymous incomplete rows) AND — same as the full-compile
   `reachable-targets` — fn-ids whose ref-closure can't be fully
   satisfied. A subset fn G that refs a non-compilable-but-existing
   fn H (no impl / unsupported shape) is DROPPED rather than compiled:
   H never entered `existing-registry`, so compiling G would hit the
   `:ref` arm's `(get child-callables H)` → nil → `:compile/missing-
   child` throw, aborting the whole delta and leaving the registry
   stale. `reachable-targets` already excludes such a G from a full
   compile; the delta path must degrade the same way, not throw."
  [lookups existing-registry subset-fn-ids]
  (let [incoming (set subset-fn-ids)
        candidates (into #{}
                         (filter #(and (has-impl? % lookups)
                                       (supported-shapes? % lookups)))
                         incoming)
        ;; Ref-deps resolvable from OUTSIDE this recompile: pinned
        ;; `existing-registry` entries that are NOT themselves being
        ;; recompiled here (a blast member's stale closure must not
        ;; count as available — only its fresh survival does).
        available (into #{} (remove incoming) (keys existing-registry))
        subset (prune-unresolvable candidates available lookups)
        ;; Restrict deps to subset members for topo-sort — deps
        ;; outside the subset are pinned through `existing-registry`
        ;; and don't constrain order.
        deps (into {}
                   (map (fn [fid]
                          [fid (into #{}
                                     (filter subset)
                                     (ref-deps fid lookups))]))
                   subset)
        order (topo-sort deps)]
    (reduce (fn [acc fid]
              (assoc acc fid (compile-fn fid lookups acc)))
            existing-registry
            order)))

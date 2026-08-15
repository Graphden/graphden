(ns graphden.executor.compile-runtime
  "Public entry points for the compiled executor.

   Bridges between the compile-time registry (produced by
   `graphden.executor.compile-eager/compile-all`) and the executor's
   public API (`execute`, `make-*-arg-callable`).

   This namespace IS the executor — `exec/` public API delegates here.
   The registry is rebuilt on demand when missing (test paths that create
   contexts directly without going through the system-level
   `:exec/compiled-registry` init-key)."
  (:require
    [clojure.set :as set]
    [clojure.tools.logging :as log]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.executor.compile-eager :as ce]
    [graphden.executor.compile.deps :as deps]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.compile.renames :as r]
    [graphden.packages.records.types :as record-types]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.core :as types]
    [graphden.util.counters :as counters]))


;; =============================================================================
;; Registry lifecycle
;; =============================================================================

(defn org-in-shard?
  "Is `org` part of the slice of the graph THIS executor is responsible
   for? The single membership question, asked in two places: the compile
   filter below (per row) and the request router (per request), which
   must agree or a pod would serve requests for fns it never compiled.

   `orgs` nil ⇒ this executor serves everything (single-tenant /
   self-hosted). Otherwise it is a membership predicate over org-ids: a
   SET is the usual value and is already a predicate; a hash-sharded
   fleet passes a fn instead, so it never has to enumerate its tenants.

   A nil `org` means an un-owned row — the shared platform graph (core /
   web / app packages) that every executor needs. The public org, if the
   deployment has one, is just another member: core deliberately doesn't
   know its name (`tenancy.context/public-org`), so whoever builds the
   ctx makes sure the predicate admits it."
  [orgs org]
  (or (nil? orgs) (nil? org) (boolean (orgs org))))


;; =============================================================================
;; Parallel-test seam
;; =============================================================================

(def ^:dynamic *impl-override*
  "Parallel-test seam: a map of `{op-keyword → fn}` shadowing the real
   implementation of the correspondingly-named registry-lifecycle
   operation (`:rebuild!` `:rebuild-optimistic!` `:read-graph`
   `:load-cell!` `:evict-cell!` — same args as the fns). nil
   (production) = every call runs the real body. Tests `binding` this
   instead of `with-redefs`-ing the root vars — a root rebind is
   process-global and forced `^:serial` pins on the graph-epoch,
   compile-runtime and fleet-command suites (serial-reduction batch 4).
   Cost on the real path: one nil-map lookup per rebuild / epoch-heal /
   fleet cell-command — these are cold per-rebuild / per-heal /
   per-command paths, never the per-execute hot path.

   A stub that wants to DELEGATE to the real implementation must
   re-bind this var to nil around the delegate call — the root fn
   re-checks the override on entry, so a plain call to the captured
   root value would recurse straight back into the stub."
  nil)


(defn- impl
  [op]
  (get *impl-override* op))


(defn- read-graph
  "Read the slot/fn-slot/binding model entities from storage. Bundled
   so `rebuild!` and the on-demand free-arg resolver share the same
   query shape.

   `orgs` (nil ⇒ no filtering, the single-tenant / self-hosted default)
   restricts the graph to one executor's shard, so a pod compiles only
   the orgs it serves instead of every tenant's fns. Filtering happens
   after the read rather than in the where-clause because
   `query-entities` only expresses equality while membership is a set or
   a predicate — and the read is transient anyway; the compiled registry
   is the thing we keep and the thing that was growing with tenant count.

   Sharding is sound because a fn's ref closure never leaves its own
   org ∪ the un-owned platform rows: a tenant can only bind refs to fns
   its org-scoped storage let it see. `crud.entities/reject-cross-org-refs!`
   turns that from an emergent property into an enforced one."
  ([storage] (read-graph storage nil))
  ([storage orgs]
   (if-let [f (impl :read-graph)]
     (f storage orgs)
     (let [q (fn [entity]
               (let [rows (sp/query-entities storage entity {})]
                 (if orgs
                   (filterv #(org-in-shard? orgs (:org-id %)) rows)
                   rows)))]
       {:fns        (q :fn)
        :slots      (q :slot)
        :fn-slots   (q :fn-slot)
        :bindings   (q :binding)
        :list-items (q :binding-list-item)}))))


(defonce ^:private per-org-aliases
  ;; §4 Risk-2 fix: `{org-id → {alias-name → body}}`, the per-org SLICE of the
  ;; flat global registry. Rebuilt in lockstep with the global by
  ;; `register-type-aliases-from-db!`. The global stays org-agnostic (bootstrap /
  ;; public / platform type-checks read it); a TENANT type-check binds
  ;; `types/*type-aliases-override*` to `org-alias-snapshot` so it sees only
  ;; {public + own-org} aliases, never another org's same-named type.
  (atom {}))


(def ^:dynamic *per-org-aliases-override*
  "Thread-local override of `per-org-aliases` for parallel-test isolation —
   mirrors `types.core/*type-aliases-override*` (and seeded the same way via
   `per-org-snapshot-for-isolation`). nil = the process-global."
  nil)


(defn- per-org-atom
  []
  (or *per-org-aliases-override* per-org-aliases))


(defn per-org-snapshot-for-isolation
  "Snapshot the current per-org index — the isolation-var seeder (so a bound
   override starts from the global state instead of empty, matching rich-types)."
  []
  @(per-org-atom))


(defn org-alias-snapshot
  "The `{name → body}` alias view a tenant in `org` should type-check against:
   the public slice (under `public-org`, plus any untenanted NULL-org rows) as
   the base, overlaid by `org`'s own. `public-org` is supplied by the caller
   (the tenancy layer) so this core fn needs no tenancy dependency."
  [public-org org]
  (let [m @(per-org-atom)]
    (merge (get m nil) (get m public-org) (get m org))))


(defn register-type-aliases-from-db!
  "Walk type-rows in the just-loaded graph and register them as type-
   aliases — the runtime equivalent of system/core's
   `register-type-aliases!` which only sees package data. Called from
   `rebuild!` AND from CRUD impls so types CREATED VIA API become
   resolvable to the type-checker without a server restart.

   Mirrors the EDN-side body shapes:
     record-type    → `{slot-name slot-type-name …}`
     refinement     → `[:refine base-name constraint]`
     list           → `[:list element-name]`

   Phase 7 — registration goes through `types/register-type-aliases-
   batch`, which extends the validation view with every pending name
   before checking bodies. Mutual / self-recursive types (`{:children
   [:list :tree]}`, `Person↔Address`) register in a single pass
   without the legacy fixed-point loop. Genuinely malformed entries
   (dangling refs to names that aren't in the batch and aren't
   registered yet) are collected per-row and logged."
  [{:keys [fns slots fn-slots]}]
  (let [fn-by-id   (into {} (map (juxt :id identity)) fns)
        slot-by-id (into {} (map (juxt :id identity)) slots)
        slots-by-fn (group-by :fn-id fn-slots)
        name-by-id (fn [id] (some-> (get fn-by-id id) :name keyword))
        candidates
        (keep
          (fn [f]
            (when-let [nm (some-> (:name f) keyword)]
              (let [own-slots (->> (get slots-by-fn (:id f) [])
                                   (sort-by :position)
                                   (keep #(get slot-by-id (:slot-id %))))
                    role (record-types/type-row-role f (seq own-slots))
                    body (case role
                           :record
                           (into {}
                                 (keep (fn [s]
                                         (when-let [tn (name-by-id (:type-fn-id s))]
                                           [(keyword (:name s)) tn])))
                                 own-slots)

                           :refinement
                           (when-let [base (name-by-id (:base-fn-id f))]
                             [:refine base (or (:constraint f) [:any])])

                           :list
                           (when-let [elem (name-by-id (:element-fn-id f))]
                             [:list elem])

                           :union
                           ;; Constraint already shaped as [:union T1 T2 …]
                           ;; by parse-union; re-register verbatim so
                           ;; resolve-alias sees the same form the EDN
                           ;; path produces.
                           (:constraint f)

                           :variant
                           ;; Desugar to the union-of-pinned-records
                           ;; form so the alias-registry stores the
                           ;; same structural type the EDN path
                           ;; produces (see types/desugar-variant).
                           ;; The constraint payload is
                           ;; `[:variant tag1 T1 tag2 T2 …]`; we strip
                           ;; the leading `:variant` and let the
                           ;; helper rebuild the union. Names embedded
                           ;; in `Tᵢ` resolve through the batch's
                           ;; pre-extended `*alias-view*`, so mutual
                           ;; refs across newly-saved type-rows work
                           ;; in a single pass — same as records.
                           (types/desugar-variant (:constraint f))

                           ;; marker declaration — register the TAG in
                           ;; the marker registry (not an alias) and emit
                           ;; no alias body.
                           :marker
                           (do (types/register-marker!
                                 nm (second (:constraint f)))
                               nil)

                           :fn-type
                           ;; `[:fn args ret]` — already in canonical
                           ;; structural form, register verbatim. The
                           ;; row has no name when it came from an
                           ;; inline reference; alias entry only
                           ;; lands when the `:name` keyword exists
                           ;; (the `keep` over `(:name f)` above
                           ;; filters anonymous rows out anyway).
                           (:constraint f)

                           nil)]
                (when body {:nm nm :body body :org (:org-id f)
                            ;; Owner id feeds the alias-collision
                            ;; diagnostic — per-ns names may repeat,
                            ;; a silent alias overwrite must not.
                            :owner (:id f)}))))
          fns)
        {:keys [failed]} (types/register-type-aliases-batch
                           (map (juxt :nm :body :owner) candidates))
        failed-names (set (map :nm failed))]
    ;; Rebuild the per-org slice from the SUCCESSFULLY-registered candidates
    ;; (reuse the already-validated bodies; no re-check). Lockstep with the
    ;; global write above → same freshness guarantee.
    (reset! (per-org-atom)
            (reduce (fn [m {:keys [nm body org]}]
                      (if (contains? failed-names nm) m (assoc-in m [org nm] body)))
                    {} candidates))
    (doseq [{:keys [nm reason]} failed]
      (log/warn (str "register-type-aliases-from-db!: skipped " (pr-str nm)
                     " — " reason)))))


(defn- storage-root
  "Walk a storage's decorator chain to the handle underneath.

   Used to ask one question: does `:compile-storage` read the same rows as
   `(:storage ctx)`? Object identity cannot answer it — both are distinct
   `VersionedStorage` instances even in single-tenant — but the chain can.

   `VersionedStorage` exposes its inner handle as `:base-storage`, so the walk
   goes through it. `OrgScopedStorage` (tenancy) names its inner handle `:base`,
   so the walk STOPS there — which is precisely the answer we want: a ctx whose
   reads are org-scoped does not see the graph the registry is compiled from, and
   its cache must not be used to compile one."
  [s]
  (loop [x s]
    (if-let [b (:base-storage x)] (recur b) x)))


(defn- compile-storage
  "Storage for STRUCTURAL (compile-time) reads of the fn-graph — the privileged
   org-agnostic storage when wired (§4 Design B: the registry holds every org's
   fns), else the runtime storage. Runtime DATA reads stay on `(:storage ctx)`
   so org isolation holds at execute time (org-scoped reads + the `resolve-fn` /
   execute-guard gates). In single-tenant the two are equal → a no-op.

   CAVEAT (§4 Risk 2): `register-type-aliases-from-db!` (called from rebuild!)
   now registers EVERY org's type-rows into the process-global alias registry,
   so two orgs' same-named types collide (last-write-wins — a low-severity
   cross-org info leak limited to a validation message; the `:fn` rows
   themselves stay org-scoped via identity-filtering). Per-org type registries
   is the follow-up. This merely WIDENS the pre-existing global-type registry."
  [ctx]
  (or (:compile-storage ctx) (:storage ctx)))


(defn refresh-type-registries-from-storage!
  "Light-weight equivalent of `rebuild!` that ONLY refreshes type
   registries (aliases + rich-types snapshot of current DB type-rows)
   — does NOT recompile fn closures.

   CRUD impls call this AFTER mutation so editor reads (`/api/types`,
   pickers) see the new/modified types immediately. The compiled
   closures are also stale at that point but they're rebuilt
   on-demand by the next `execute` (via `registry`'s lazy fallback)."
  [ctx]
  (let [storage (compile-storage ctx)
        graph (read-graph storage (:executor-orgs ctx))]
    (register-type-aliases-from-db! graph)
    graph))


(defn- prime-graph-cache!
  "Mirror the just-loaded raw entities into `:graph-cache` so reads
   from layout / `/api/graph/entities` / `/api/types` go through the
   same snapshot the compiler used."
  [ctx graph]
  (when-let [graph-cache (:graph-cache ctx)]
    (reset! graph-cache (select-keys graph [:fns :slots :fn-slots
                                            :bindings :list-items]))))


(defn- prime-compile-deps!
  "Capture the dependency index on `ctx`. Holds
   `{:forward-deps {fn-id → #{deps}} :reverse-deps {fn-id → #{dependers}}}`
   so the next CRUD can compute its delta via
   `deps/incremental-update` instead of a from-scratch sweep.

   Two arities:
   - `[ctx graph]` — full rebuild (cold start, mass migrations).
   - `[ctx graph changed-fn-ids]` — delta: re-derive forward-deps
     only for the changed fns, patch reverse-deps edges. ~ms vs the
     full sweep's ~65 ms on a 3000-fn graph.

   Delta path falls through to a full rebuild when no prior state
   exists (cold start) — the caller doesn't have to special-case
   that path."
  ([ctx graph] (prime-compile-deps! ctx graph nil))
  ([ctx graph changed-fn-ids]
   (when-let [holder (:compile-deps ctx)]
     (let [current @holder]
       (if (and (seq changed-fn-ids)
                (map? current)
                (contains? current :forward-deps))
         (reset! holder (deps/incremental-update current graph changed-fn-ids))
         (reset! holder (deps/build-deps-state graph)))))))


(defn- prime-always-fresh!
  "Refresh the global always-fresh fn-id set from `:effects` in the
   rich-types registry. Pulled out so `delta-recompile!` can call it
   too — the set drives every `:ref` invocation's cache lookup, so it
   must reflect the current graph after a partial recompile."
  [fns]
  ;; `registry.core` requires `executor.interface`, which requires
  ;; `executor.context`, which requires this ns — so eagerly
  ;; requiring it here would cycle. Deferred-resolved + asserted
  ;; non-nil so a future rename fails loudly instead of silently
  ;; degrading.
  (let [type-of-id (or (requiring-resolve
                         'graphden.executor.registry.core/rich-type-of-id)
                       (throw (ex-info
                                "rich-type-of-id missing — namespace rename?"
                                {:type :compile/missing-symbol
                                 :symbol 'graphden.executor.registry.core/rich-type-of-id})))
        fresh-cats #{:time :random}
        fresh-ids
        (into #{}
              (keep (fn [f]
                      ;; Registry entries key on the fn's IDENTITY — the
                      ;; row id in hand — so same-named fns in different
                      ;; namespaces each get their own freshness verdict.
                      (let [eff (:effects (type-of-id (:id f)))]
                        (when (and eff (some fresh-cats eff))
                          (:id f)))))
              fns)]
    (ce/set-always-fresh-fn-ids! fresh-ids)))


(defn call-with-invalidation-lock
  "Run thunk `f` holding the ctx's `:invalidation-lock`, or plain when
   no lock is wired (stripped test ctx). The lock is a
   `java.util.concurrent.locks.ReentrantLock`, NOT `locking` /
   `synchronized`, on purpose: the body under this lock runs a full or
   delta recompile that can take multiple seconds, and a virtual thread
   that blocks on a `synchronized` monitor PINS its carrier for the
   whole wait (JDK 21). Under concurrent invalidations (CRUD writes +
   the PG NOTIFY listener) that pins enough ForkJoinPool carriers to
   starve the request pool — unrelated reads (`/api/types`, graph
   entities) then stall for the recompile's duration. A ReentrantLock
   lets a blocked waiter UNMOUNT from its carrier instead. Reentrant,
   so `invalidate-graph-cache!` → `rebuild!` re-entry is fine."
  [ctx f]
  (if-let [lock (:invalidation-lock ctx)]
    (do (java.util.concurrent.locks.ReentrantLock/.lock lock)
        (try (f) (finally (java.util.concurrent.locks.ReentrantLock/.unlock lock))))
    (f)))


(defonce ^:private full-compile-semaphore
  ;; Process-wide bound on CONCURRENT full compiles. A full read-graph +
  ;; compile-all holds the whole graph, its lookups AND the new registry
  ;; live at once (measured 49.8 s at 4137 fns) — and nothing used to stop
  ;; several of them running together: the per-branch build monitor dedupes
  ;; one branch only, so two cold branches, or a cold-branch build racing
  ;; the epoch heal's `heal-stale-ctxs!`, each ran their own compile-all.
  ;; Two such working sets exhausted the prod heap on 2026-08-05
  ;; (ExitOnOutOfMemoryError, ~20 min outage). One permit serializes them;
  ;; queued compiles just wait — correctness is unaffected.
  ;; `GRAPHDEN_MAX_CONCURRENT_COMPILES` widens it for hosts with heap to
  ;; spare (mirrors the `GRAPHDEN_MAX_CACHED_BRANCHES` knob pattern).
  ;; j.u.c.Semaphore, not `locking`: a blocked virtual-thread waiter
  ;; UNMOUNTS from its carrier (same JDK-21 pinning rationale as
  ;; `call-with-invalidation-lock` above).
  (java.util.concurrent.Semaphore.
    (max 1 (or (some-> (System/getenv "GRAPHDEN_MAX_CONCURRENT_COMPILES")
                       parse-long)
               1))))


(def ^:dynamic *compile-permit-override*
  "Parallel-test seam. nil (production) = the global
   `full-compile-semaphore`. The kaocha parallel plugin binds
   `(atom :bypass)` — the suite runs ~a hundred tiny per-namespace
   rebuilds, and funnelling those micro-graphs through ONE global permit
   convoys unrelated namespaces (166 s waits; time-window execution
   tests missed their polling deadlines — 17 failures, gate
   20260805-212257). The production bound targets whole-platform
   compiles, which the suite never runs concurrently anyway. A test of
   the permit mechanism itself binds a fresh `Semaphore` here to get a
   deterministic bound regardless of the env knob. IDeref values are
   deref'd (the plugin wraps every seed in an atom)."
  nil)


(defn compile-permit-isolation-seed
  "Seeder for the parallel plugin's isolation binding — see
   `*compile-permit-override*`."
  []
  :bypass)


(defn call-with-compile-permit
  "Run thunk `f` holding a full-compile permit. DEADLOCK-FREE BY
   CONSTRUCTION: callers hold the permit only across the pure
   read-graph → compile-all section, which acquires no locks — so a
   permit holder never waits on a lock, and a lock holder waiting for a
   permit (the `rebuild!` path, entered under the ctx's
   invalidation-lock) can always be satisfied. Keep it that way: never
   take `call-with-invalidation-lock` (or any other lock) inside `f`.
   Counts `:registry/compile-queued` when the permit isn't immediately
   available — the observable signal that compiles are stacking up."
  [f]
  (let [ov *compile-permit-override*
        ov (if (instance? clojure.lang.IDeref ov) @ov ov)
        sem (cond
              (nil? ov) full-compile-semaphore
              (instance? java.util.concurrent.Semaphore ov) ov
              :else nil)]
    (if (nil? sem)
      (f)
      (do (when-not (java.util.concurrent.Semaphore/.tryAcquire sem)
            (counters/count! :registry/compile-queued)
            (java.util.concurrent.Semaphore/.acquire sem))
          (try (f) (finally (java.util.concurrent.Semaphore/.release sem)))))))


(defn- prep-compile-inputs
  "The shared read-side prep of every (re)compile path, applied to a
   graph already in hand: register DB-declared type-aliases, refresh
   the always-fresh set, and build lookups with the ctx's base-fns
   attached. Returns `{:graph g :fns-map m :lookups l}` with `:fns`
   normalised to an id-keyed map regardless of the seq/map shape the
   source (read-graph vs :graph-cache) delivered.

   Four call sites (`rebuild!`, `rebuild-optimistic!`,
   `delta-recompile!`, `load-cell!`) previously copy-pasted this
   block with small drifts — the audit's :resolved-value walker bug
   showed what per-site drift costs; keep the sequence HERE only."
  [ctx graph]
  (register-type-aliases-from-db! graph)
  (let [fns-map (if (map? (:fns graph))
                  (:fns graph)
                  (into {} (map (juxt :id identity)) (:fns graph)))]
    (prime-always-fresh! (vals fns-map))
    {:graph graph
     :fns-map fns-map
     :lookups (assoc (l/cached-build-lookups graph)
                     :base-fns (:base-fns ctx))}))


(defn rebuild-optimistic!
  "Stale-while-revalidate rebuild for the epoch heal: read + compile
   OUTSIDE the invalidation lock (concurrent write requests' delta
   invalidations are NOT blocked behind a ~30s compile — blocking them
   made clients abort and cascade further heals), then take the lock
   only for the swap, and swap ONLY IF `unchanged?` still holds — a
   write that landed mid-compile has already delta-patched the LIVE
   registry, and clobbering it with our older snapshot would regress
   read-your-writes. Returns true when the swap happened.

   Alias re-registration runs outside the lock too — it is idempotent
   and per-name, so a transiently-ahead alias view is harmless.

   The read + compile runs under the full-compile permit (released
   BEFORE the swap takes the lock — see `call-with-compile-permit`'s
   ordering contract)."
  [ctx unchanged?]
  (if-let [f (impl :rebuild-optimistic!)]
    (f ctx unchanged?)
    (let [_ (counters/count! :registry/rebuild)
          {:keys [graph compiled]}
          (call-with-compile-permit
            (fn []
              (let [{:keys [graph lookups]}
                    (prep-compile-inputs
                      ctx (read-graph (compile-storage ctx)
                                      (:executor-orgs ctx)))]
                {:graph graph :compiled (ce/compile-all lookups)})))]
      (call-with-invalidation-lock
        ctx
        (fn []
          (if (unchanged?)
            (do (reset! (:compiled-registry ctx) compiled)
                (prime-graph-cache! ctx graph)
                (prime-compile-deps! ctx graph)
                true)
            false))))))


(defn rebuild!
  "Rebuild the compiled registry in `ctx` from whatever the slot/
   binding tables currently hold. Also primes `:graph-cache` and
   `:compile-deps` so read-heavy consumers stay in sync. Call at
   startup and on full invalidation.

   Runs under the context's `:invalidation-lock` so the
   read-graph → compute → prime-multi-atom sequence stays atomic
   relative to concurrent `invalidate-graph-cache!` callers."
  [ctx]
  (if-let [f (impl :rebuild!)]
    (f ctx)
    (do
      ;; The expensive outcome, counted where it actually happens rather than at
      ;; the call site — a caller can ask for a delta and still land here (see
      ;; `delta-recompile!`'s fallback). Measured at 4137 fns: 49.8 s.
      (counters/count! :registry/rebuild)
      (call-with-invalidation-lock
        ctx
        (fn []
          ;; Permit INSIDE the ctx lock: safe because permit holders never
          ;; wait on locks (`call-with-compile-permit`'s contract), so a
          ;; lock-holding waiter here can't deadlock — and the reentrant
          ;; `invalidate-graph-cache! → rebuild!` path keeps working.
          (let [{:keys [graph compiled]}
                (call-with-compile-permit
                  (fn []
                    (let [{:keys [graph lookups]}
                          (prep-compile-inputs
                            ctx (read-graph (compile-storage ctx)
                                            (:executor-orgs ctx)))]
                      {:graph graph :compiled (ce/compile-all lookups)})))]
            (reset! (:compiled-registry ctx) compiled)
            (prime-graph-cache! ctx graph)
            (prime-compile-deps! ctx graph)
            compiled))))))


(def ^:dynamic *stale-revalidate-sync?*
  "Test seam. When true, `maybe-schedule-revalidate!` runs the background
   rebuild INLINE (deterministic) instead of on a daemon thread — mirrors
   `branch-router/*epoch-heal-sync?*`."
  false)


(defn maybe-schedule-revalidate!
  "Kick a single background stale-while-revalidate for `ctx` when its
   `:registry-stale?` flag is set and none is already running. This is the
   availability contract for the request-path full clear: the gate keeps
   returning the STALE registry (never blocks behind a ~50s cold compile),
   and this refreshes it off-thread. Coalesced via the ctx's
   `:registry-rebuild-inflight` CAS guard, so N concurrent requests that
   observe the flag still spawn only one rebuild.

   Shape mirrors the epoch heal (`branch-router/heal-stale-ctxs!`): two
   optimistic attempts (compile outside the invalidation lock, swap only if
   `:invalidation-count` didn't move mid-compile — a moved count means a
   newer write already patched the live registry and our snapshot would
   regress read-your-writes), then a blocking `rebuild!` correctness
   fallback. The stale flag is cleared only if no NEW full clear landed
   while we ran (`:full-clear-count` unmoved) — a delta bumps
   `:invalidation-count` but not `:full-clear-count`, so ordinary edits
   don't wastefully keep re-triggering; a genuine full clear during our
   compile leaves the flag set for the next request to re-kick."
  [ctx]
  (let [inflight (:registry-rebuild-inflight ctx)
        stale? (:registry-stale? ctx)
        ic (:invalidation-count ctx)
        fc (:full-clear-count ctx)]
    (when (and inflight stale? ic fc @stale?
               (compare-and-set! inflight false true))
      (let [run (fn []
                  (try
                    (let [fc0 @fc
                          c0 @ic]
                      (loop [attempt 1]
                        (when-not (rebuild-optimistic! ctx (fn [] (= c0 @ic)))
                          (if (< attempt 2)
                            (recur (inc attempt))
                            (rebuild! ctx))))
                      ;; Cleared only if no full clear re-flagged us mid-run;
                      ;; otherwise the next request re-kicks with a fresh token.
                      (when (= fc0 @fc) (reset! stale? false)))
                    (catch Exception e
                      (log/warn e "registry stale-revalidate failed"))
                    (finally (reset! inflight false))))]
        (if *stale-revalidate-sync?*
          (run)
          (let [t (Thread. ^Runnable run "registry-stale-revalidate")]
            (Thread/.setDaemon t true)
            (Thread/.start t)))))))


(defn instantiate-from-templates!
  "Hydrate `dst-ctx`'s `:compiled-registry` from `src-ctx`'s. Used by
   the branch-router's lazy-compile fast path: when a non-default
   branch is graph-identical to its base (no own version rows + no
   merge edges landing on it), the base's compiled closures are the
   same closures the branch would compile from scratch.

   compile-eager closures are ctx-INDEPENDENT — `ctx` arrives at
   `execute` time, not compile time — so sister branches share the
   same `{fn-id → closure}` map directly. `:graph-cache` and
   `:compile-deps` are copied across for warm reads.

   Returns the registry copied across, or nil when `src-ctx` has
   nothing to share."
  [src-ctx dst-ctx]
  (when-let [src-registry (some-> (:compiled-registry src-ctx) deref)]
    (reset! (:compiled-registry dst-ctx) src-registry)
    (when-let [src-graph (some-> (:graph-cache src-ctx) deref)]
      (prime-graph-cache! dst-ctx src-graph))
    (when-let [src-deps (some-> (:compile-deps src-ctx) deref)]
      (when-let [holder (:compile-deps dst-ctx)]
        (reset! holder src-deps)))
    src-registry))


(defn delta-recompile!
  "Recompile only the fns whose closures depend on `changed-fn-ids` —
   the inverse-closure under the reverse-deps index built by
   `rebuild!`. Bound to one `read-graph` and N `compile-fn` calls
   where N is the blast radius (typically a handful, not the whole
   registry).

   Falls back to a full `rebuild!` when the reverse-deps index is
   missing (cold start) or when `changed-fn-ids` is empty / nil — the
   caller asked for invalidation but didn't say what changed, so the
   safe move is to drop everything.

   Type-aliases are always re-registered (cheap, global), and
   entries for deleted fn-ids are dissoc'd from the registry so a
   stale closure can't outlive its row."
  [ctx changed-fn-ids]
  (let [holder (:compiled-registry ctx)
        deps-state (some-> (:compile-deps ctx) deref)
        reverse-deps (:reverse-deps deps-state)]
    (cond
      (or (nil? holder) (nil? @holder) (nil? reverse-deps) (empty? changed-fn-ids))
      ;; Counted apart from `:registry/rebuild` because this is a delta that
      ;; WASN'T one. The caller named its changed fns and still paid for the
      ;; whole graph; nothing in the return value says so, and no timing can
      ;; distinguish it from a cold cache. Without this counter, a budget on
      ;; "deltas stay deltas" would be satisfied by the fallback silently.
      (do
        (counters/count! :registry/delta-fell-back-to-rebuild)
        (rebuild! ctx))

      :else
      (let [_ (counters/count! :registry/delta-recompile)
            storage (compile-storage ctx)
            ;; The graph we need is, in the common case, already in hand:
            ;; `invalidate-graph-cache!` splices `:graph-cache` immediately before
            ;; calling us, and that cache holds exactly this shape
            ;; (`{:fns :slots :fn-slots :bindings :list-items}`). Re-reading all of
            ;; it out of Postgres to recompile a handful of fns is the single most
            ;; expensive thing on the write path: measured at 4137 fns, a `:fn`
            ;; create spent 477 ms of its 918 ms right here, and its blast radius
            ;; was one fn.
            ;;
            ;; Only when the two graphs are the SAME graph, though. `read-graph`
            ;; shards by `:executor-orgs`, and `compile-storage` is the privileged
            ;; org-agnostic handle when tenancy is wired — while the cache is
            ;; filled from the request's (org-scoped) storage. Taking the cache
            ;; there would compile one tenant's fns and drop every other's. In
            ;; single-tenant the two handles are the same object and no shard
            ;; filter exists, which is exactly what these two checks say.
            cached (when (and (nil? (:executor-orgs ctx))
                              (identical? (storage-root storage)
                                          (storage-root (:storage ctx))))
                     (some-> (:graph-cache ctx) deref))
            {:keys [graph fns-map lookups]}
            (prep-compile-inputs
              ctx (or cached (read-graph storage (:executor-orgs ctx))))
            blast (deps/transitive-blast reverse-deps changed-fn-ids)]
        ;; CRUD impls invoke `invalidate-graph-cache!` directly on
        ;; the http-kit worker thread (see `crud/entities.clj`), so
        ;; two concurrent client requests can land here against the
        ;; same `holder` atom. Read-modify-write through `swap!`
        ;; CAS-retries so a sibling's recompile isn't silently
        ;; dropped.
        (swap! holder
               (fn [current]
                 (let [pruned (into {}
                                    (filter (fn [[k _]] (contains? fns-map k)))
                                    current)]
                   (ce/compile-subset lookups pruned blast))))
        (prime-graph-cache! ctx graph)
        ;; Pass changed-fn-ids so prime-compile-deps takes the
        ;; incremental delta path instead of rebuilding the full
        ;; index — sub-ms vs ~65 ms on the production graph.
        (prime-compile-deps! ctx graph changed-fn-ids)
        @holder))))


(defn- ctx-forward-deps
  "The `:forward-deps` index for cell load/evict — from the primed
   `:compile-deps` if present, else built from the graph. `graph-fn` (a thunk) is
   called only on the cold path, so a caller that already read the graph can pass
   it back without a second read."
  [ctx graph-fn]
  (or (:forward-deps (some-> (:compile-deps ctx) deref))
      (:forward-deps (deps/build-deps-state (graph-fn)))))


(defn load-cell!
  "Compile a CELL — a root fn + its transitive forward ref-closure
   (docs/FLEET_RFC.md §3) — INTO the ctx's compiled registry, ON TOP of
   whatever is already loaded. This is the unit a fleet executor adds at
   runtime WITHOUT a full rebuild: only the cell's closure is compiled, not
   the whole graph.

   Reuses the delta machinery: `forward-closure` over the `:forward-deps`
   index (primed, or built from the graph on a fresh executor), then
   `compile-subset` on top of the current registry, swapped in under CAS so a
   concurrent load / delta-recompile isn't dropped. The graph read is
   transient — only the compiled closure is kept, which is the whole point.

   Returns the set of fn-ids the cell contributed (empty if `root-fn-id` isn't
   in this executor's shard). Cell fns absent from the graph (a stale forward
   edge to a deleted fn) are filtered out rather than crashing the compile."
  [ctx root-fn-id]
  (if-let [f (impl :load-cell!)]
    (f ctx root-fn-id)
    (let [holder (:compiled-registry ctx)
          storage (compile-storage ctx)
          {:keys [graph fns-map lookups]}
          (prep-compile-inputs
            ctx (read-graph storage (:executor-orgs ctx)))
          forward-deps (ctx-forward-deps ctx (constantly graph))
          cell (into #{}
                     (filter #(contains? fns-map %))
                     (deps/forward-closure forward-deps [root-fn-id]))]
      (when (seq cell)
        (swap! holder (fn [current] (ce/compile-subset lookups (or current {}) cell)))
        (prime-graph-cache! ctx graph)
        (prime-compile-deps! ctx graph (vec cell))
        ;; Record the root so `evict-cell!` can reference-count shared fns.
        (when-let [roots (:loaded-roots ctx)]
          (swap! roots conj root-fn-id)))
      cell)))


(defn evict-cell!
  "Drop a cell loaded by `load-cell!`: remove `root-fn-id` from the ctx's
   `:loaded-roots` and evict from the registry the fns in its forward-closure
   that NO OTHER loaded root still needs — reference counting via the union of
   the remaining roots' closures, so a fn shared with another live cell stays.
   The inverse of `load-cell!` (docs/FLEET_RFC.md §6.2).

   Reads the `:forward-deps` index — from the primed `:compile-deps` if present
   (no graph read needed), else built from a transient graph read. Returns the
   set of fn-ids evicted (empty if the root wasn't loaded)."
  [ctx root-fn-id]
  (if-let [f (impl :evict-cell!)]
    (f ctx root-fn-id)
    (let [holder (:compiled-registry ctx)
          roots-atom (:loaded-roots ctx)]
      (if (or (nil? holder) (nil? roots-atom) (not (contains? @roots-atom root-fn-id)))
        #{}
        (let [forward-deps (ctx-forward-deps
                             ctx #(read-graph (compile-storage ctx) (:executor-orgs ctx)))
              remaining (disj @roots-atom root-fn-id)
              ;; Union of every OTHER loaded root's closure — the fns that must
              ;; survive. `forward-closure` over a set of roots is their union.
              still-needed (deps/forward-closure forward-deps remaining)
              evictable (into #{}
                              (remove still-needed)
                              (deps/forward-closure forward-deps [root-fn-id]))]
          (swap! roots-atom disj root-fn-id)
          (when (seq evictable)
            (swap! holder (fn [current] (apply dissoc current evictable))))
          evictable)))))


(defn cell-held?
  "Is `fn-id` currently servable on THIS executor? Runtime membership at CELL
   granularity (docs/FLEET_RFC.md §6.2): a fn is held iff it's in the compiled
   registry — `load-cell!` puts a cell's whole closure there, `evict-cell!`
   removes what nothing else needs. The fleet generalisation of `org-in-shard?`
   (which answers the same question at whole-org granularity for the static
   shard): a non-fleet executor compiles its whole shard, so `cell-held?` is
   true for every shard fn; a fleet executor holds only the cells it loaded.

   The predicate is LIVE — it reads the registry atom that load/evict mutate,
   so membership changes at runtime with no ctx rebuild. nil / empty registry
   (before the first load) ⇒ false.

   RESERVED, not yet wired into routing. Today the app-router forward-hop gates
   on the STATIC `org-in-shard?` (docs/FLEET_RFC.md T2.4 — the smaller step), and
   in a sharded fleet that is sufficient (verified on the cluster). `cell-held?`
   is the primitive for the deferred LOAD-ON-DEMAND mode: pods start with only
   `public` compiled and load a cell on demand / on placement, at which point
   routing consults `cell-held?` (serve here vs forward to the holder / lazy-
   load). Adopting that mode is a coupled change (empty-start boot + the
   controller becoming shard-aware), so it stays a separate scoped effort — hence
   this predicate is built + tested (`load_cell_test`) but intentionally
   unreferenced by the request path for now."
  [ctx fn-id]
  (boolean (some-> (:compiled-registry ctx) deref (contains? fn-id))))


(defn registry
  "Return the current compiled registry from `ctx`, rebuilding on-demand
   when missing. Tests that skip the `:exec/compiled-registry` init-key
   still get a working executor via this fallback — the cost is a single
   compile pass on first execution.

   Double-checked under the invalidation lock: a full `rebuild!` takes
   seconds, and without coalescing every concurrent reader that hit an
   empty holder fired its OWN rebuild (observed: 20+ full recompiles
   stacked on one editor mutation). Re-checking `@holder` after taking
   the lock means the first arrival rebuilds and everyone else reuses
   its result. Reentrant lock, so the inner `rebuild!` re-acquires
   cheaply."
  [ctx]
  (when-let [holder (:compiled-registry ctx)]
    (let [cur @holder]
      (if (some? cur)
        (do
          ;; Serve the current registry immediately. If a full clear flagged
          ;; it stale, revalidate in the BACKGROUND — a reader never blocks
          ;; behind the ~50s cold compile. Coalesced (a no-op once one is in
          ;; flight), so this is cheap on the hot path.
          (when (some-> (:registry-stale? ctx) deref)
            (maybe-schedule-revalidate! ctx))
          cur)
        ;; Cold: never compiled (boot, or a divergent cold branch's first
        ;; access) — nothing to serve stale, so compile once under the lock,
        ;; double-checked so concurrent readers coalesce onto the first
        ;; arrival's result. This blocks only THIS ctx's readers until its
        ;; first compile; the pod-wide hang came from full-clearing a WARM
        ;; ctx to nil, which no longer happens (see `context/invalidate-graph-
        ;; cache!` stale-while-revalidate).
        (call-with-invalidation-lock ctx (fn [] (or @holder (rebuild! ctx))))))))


;; =============================================================================
;; Arg-name resolution
;; =============================================================================

(defn- lookups-for-ctx
  "Build lookups from ctx — prefers `:graph-cache` (populated by
   `rebuild!`) so repeated calls reuse the same lookups identity via
   `cached-build-lookups`. Falls back to a fresh `read-graph` only on
   cold start. Shared by `free-arg-ext-names` and the slot-id-keyed
   public-API translator."
  [ctx]
  ;; Cold fallback reads via `compile-storage` — the SAME handle every
  ;; compile path uses (and the one `prime-graph-cache!` fills the warm
  ;; cache from). Reading `(:storage ctx)` here instead would let a
  ;; cold multi-tenant ctx build the public-boundary translator from
  ;; the org-scoped view while the registry compiled from the
  ;; privileged one — a view divergence with no upside.
  (let [graph (or (some-> (:graph-cache ctx) deref)
                  (read-graph (compile-storage ctx) (:executor-orgs ctx)))]
    (assoc (l/cached-build-lookups graph)
           :base-fns (:base-fns ctx))))


(defn free-arg-ext-names
  "Ordered vector of external names for fn-id's free args reachable
   through its ref-chain — propagates names through non-HOF refs and
   env-bindings so deep frees (`:request` of `:_request-body` →
   `:_create-parsed` → `:process-create-entity`) surface at the outer
   fn-def's interface.

   Includes optional frees (e.g. `:default` of `:get`) — callers can
   override them; HOF dispatch is shielded by the structural-name
   fast-path in `hof-lambda-params`."
  [ctx fn-id]
  (vec (r/deep-free-ext-names fn-id (lookups-for-ctx ctx))))


;; =============================================================================
;; Public-API translator (Phase 2 of the slot-id-keyed runtime refactor)
;;
;; At every public boundary (`execute`, `make-single-arg-callable`'s
;; per-call closure), translate caller's `{ext-name → value}` into
;; `{slot-id → value, ext-name → value}` via the walker's surface
;; entries. Phase 4 `:free` readers prefer the slot-id half (rename-
;; aware via `effective-reader-slot-id`); the ext-name half covers
;; env-binding writes, HOF lambda-args, and `apply-rename-aliases`
;; cross-fn cascades that still flow under name keys today. Both
;; halves are load-bearing in the shipped hybrid architecture.
;;
;; Phase 5 HOF wrap-time translation (`build-hof-translation` +
;; `apply-hof-translation`) extends the slot-id route across HOF
;; boundaries — the walker stops at HOF, so caller args past HOF
;; only have the ext-name route until `apply-hof-translation` writes
;; them under R's slot-id at wrap.
;; =============================================================================


(defn translate-named-args
  "Translate caller's `{ext-name → value}` to the dual-key
   `{ext-name → value, slot-id → value, …}` shape the slot-id-keyed
   runtime expects at its public boundary.

   - Looks up `fn-id`'s surface free args via
     `r/deep-free-ext-entries`. That walker emits one entry per
     distinct chain-leaf slot the runtime will read from `fa`. Most
     production fn-graphs have MANY chain-leaf slots reading the same
     caller-supplied name — every `(:get :coll :the-name)` inside a
     ref-tree contributes its own entry. They aren't a collision; the
     caller's single value is meant to reach all of them.
   - 0 walker entries → key passes through unchanged. Keeps
     `execute`'s lenient contract for tests bypassing
     `execute-with-named-args`'s upstream `unknown-arg-name` check.
   - ≥1 entries → write the value under the ext-name AND under EVERY
     matching slot-id. Phase 4 readers prefer the slot-id half; the
     ext-name half covers env-binding writes, HOF lambda-args, and
     `apply-rename-aliases` cross-fn cascades that still flow through
     name keys. The dual-key is load-bearing in the shipped hybrid
     architecture (`docs/ARCHITECTURE.md` § Runtime fa).

   Does NOT throw on multi-slot ext-names. The #104 collision is
   structural — two slots sharing a name that are semantically
   DIFFERENT, not just shared deep readers — and the walker can't
   tell those apart without runtime context. Phase 5 HOF translation
   bridges past HOF boundaries (`r/build-hof-translation` +
   `apply-hof-translation`); the parser disambiguates fn-def-level
   bindings via `resolve-slot-owner`'s type pass. Public boundary
   here writes everything; downstream Phase 5 + parser decide which
   slot wins per call site.

   `nil` / `{}` args short-circuit."
  [fn-id args lookups]
  (if (or (nil? args) (empty? args))
    args
    (let [entries (r/deep-free-ext-entries fn-id lookups)
          by-name (group-by :ext-name entries)]
      (reduce-kv
        (fn [acc arg-name v]
          (let [matches (get by-name arg-name)]
            (if (empty? matches)
              (assoc acc arg-name v)
              (reduce (fn [m e] (assoc m (:slot-id e) v))
                      (assoc acc arg-name v)
                      matches))))
        {}
        args))))


;; =============================================================================
;; Cancellation
;; =============================================================================

(def ^:dynamic *cancel-check*
  "Hook called at each caller→callee transition inside `execute`.
   Bound by `crud.fn-execution/run-future` to a closure that reads
   the per-execution cancel-flag atom; `nil` (default) means no
   cancellation context — every call is a no-op.

   Best-effort: blocking JDBC / IO inside a base-fn impl won't react
   to an interrupt without explicit `Statement.cancel()` / equivalent;
   the throw fires at the next executor invocation boundary."
  nil)


(defn check-cancel!
  "Public helper for impls that want to participate in cooperative
   cancellation between long internal loops. Currently nothing in
   stdlib calls this — wired only by `execute` itself."
  []
  (when *cancel-check* (*cancel-check*)))


;; =============================================================================
;; Runtime effect tracing
;; =============================================================================

(def ^:dynamic *effect-trace*
  "Atom holding a set of observed effect-category keywords (e.g.
   `#{:env :io}`), bound by the fn-execution future wrapper. `nil` (the
   default) means no tracing context — `record-effect!` is a no-op.

   Effectful base-fn impls call `(record-effect! :env)` etc. to declare
   the side effect they're about to perform. Comparison against the
   row's `:declared-effects` after run lets the editor surface drift
   between the static effect-type declaration and what the impl
   actually did."
  nil)


(def ^:dynamic *path-trace*
  "Atom holding per-execution path-trace state — `{:entries [...]}`
   plus, in value-capture mode, `:capture-values?` and the byte
   accounting (see `compile-eager/new-path-trace`; entry shapes:
   `{:fn-id … :cache-hit? … :duration-ms … (:value …)}` or `{:fn-id …
   :hidden :secret}`). Bound by the fn-execution future wrapper WHEN
   the submitted execution opted in via the request's `trace?` /
   `capture-values?` flags, or when an ambient-sampling draw won
   (Debug P3). `nil` (the default) means no tracing context — the
   seam in `call-with-cache` does a single nil-check and measures
   nothing.

   Debug/observability P1–P3 (PHILOSOPHY § Debugging and
   Observability): capture is DOUBLY opt-in — this var (per-execution)
   AND the fn appearing in `compile-eager`'s `traced-fn-ids` set (per
   fn; the `trace-all` sentinel covers explicit-`trace?` runs).
   Values are recorded ONLY in `:capture-values?` mode (behind the
   UI's explicit confirm), under 4 KB-per-entry / 16 MB-total budgets;
   plain traces record fn-ids, cache-hit flags and durations only."
  nil)


(def ^:dynamic *allowed-effects*
  "Set of effect categories the current execution is permitted to
   perform, or `nil` (the default) for UNRESTRICTED. Bound by
   `execute` from the context's `:allowed-effects` — the cloud sandbox
   (docs/TENANCY_SEAM.md § Effect gate). When a non-nil set is in
   effect, `record-effect!`
   throws `:execution/forbidden-effect` for any category outside it —
   the runtime half of the effect gate (the build-time registry filter
   is the belt-and-suspenders second layer)."
  nil)


;; --- Background-thread binding conveyance (task #6) ---------------------------
;;
;; `:future` (and the service workers built on it) run their body in a FRESH
;; thread, which does NOT inherit the caller's dynamic bindings. Without this,
;; a tenant service escapes the effect gate the moment it spawns its worker —
;; `*allowed-effects*` reverts to nil (unrestricted) in the new thread. Any
;; layer registers the vars that MUST cross the thread boundary; `future-fn`
;; captures + re-establishes them. Kept HERE (not in tenancy) so
;; core/concurrency stays tenancy-free — tenancy adds `*current-org*` at load.
;; `*effect-trace*` / `*path-trace*` / `*cancel-check*` are DELIBERATELY not
;; conveyed: they are per-top-level-request state, not a persistent worker's.

(defonce ^:private conveyed-dynamic-vars (atom #{#'*allowed-effects*}))


(defn register-conveyed-var!
  "Register dynamic Var `v` for conveyance into background (`:future`) threads.
   Idempotent."
  [v]
  (swap! conveyed-dynamic-vars conj v))


(defn capture-conveyed-bindings
  "Snapshot every conveyed var's CURRENT value → `{var value}`, for
   re-establishing in a spawned thread via `with-bindings`. Call at spawn time,
   on the parent thread."
  []
  (persistent!
    (reduce (fn [acc v] (assoc! acc v (deref v)))
            (transient {})
            @conveyed-dynamic-vars)))


(def ^:dynamic *execute-authorized*
  "True once the ctx's `:execute-guard` (the tenancy addon's per-namespace
   execute check, docs/TENANCY_SEAM.md § Execute guard) has run for THIS
   top-level execute, so
   the recursive sub-fn `execute` calls don't re-run it. Default false →
   the next top-level `execute` consults the guard."
  false)


(def cloud-forbidden-effects
  "The security-sensitive effect categories a cloud sandbox must forbid
   (docs/TENANCY_SEAM.md § Effect gate). A cloud org context should set its
   `:allowed-effects` to the full effect vocabulary MINUS this set.

   Verified by the effect-gate coverage audit: every base-fn that
   performs one of these calls `record-effect!`, so excluding them from
   `:allowed-effects` actually blocks the operation (no silent bypass).

   - `:env`     — environment variables / system properties
   - `:io`      — file / classpath reads
   - `:network` — outbound HTTP / sockets
   - `:process` — thread / server / execution lifecycle control
   - `:raw-sql` — arbitrary SQL / HoneySQL against a datasource,
                  BYPASSING the org-scoped + RLS-checked storage
                  protocol. The org-scoped path (`:query-entities` and
                  friends) records only `:db` and stays allowed; the
                  raw escape hatches (`:pg-query` / `:pg-execute` /
                  `:pg-tx` on the platform pool, `:sql-query` /
                  `:sql-exec` on an arbitrary datasource) additionally
                  record `:raw-sql` so a cloud/tenant graph can't read
                  or mutate the platform DB (incl. RLS-less token /
                  user / grant tables) or an out-of-band datasource.
   - `:cross-org` — running a fn under ANOTHER org's `*current-org*`
                  scope (the cloud domain-router's `:execute-in-org`
                  primitive). The trusted platform router runs
                  unrestricted (nil `*allowed-effects*`) so it passes;
                  forbidding it here keeps it out of every tenant set,
                  so a tenant graph referencing `:execute-in-org` is
                  403'd before it can cross into a foreign org. Exactly
                  the `:raw-sql`/`:pg-query` gate, one level up (org
                  boundary instead of storage boundary).

   The safe remainder — `:db`, `:time`, `:state`, `:random` — stays
   allowed (internal infrastructure depends on it). New base-fns must
   keep this contract: any security-sensitive primitive MUST
   `record-effect!`, or it becomes a sandbox hole."
  #{:env :io :network :process :raw-sql :cross-org})


(def known-effects
  "Alias of `types.core/known-effect-categories` — the ONE effect
   vocabulary (recording AND declaration; see its docstring). The
   registry of safe-vs-sensitive lives in `cloud-forbidden-effects`;
   this is the full set so the safe complement can be derived. A new
   SAFE category goes in the types.core set; a new SENSITIVE one goes
   BOTH there AND in `cloud-forbidden-effects`."
  types/known-effect-categories)


(def default-cloud-allowed-effects
  "The `:allowed-effects` value a restricted (cloud / user-graph)
   ExecutionContext carries — the full vocabulary minus the
   security-sensitive set, i.e. `#{:db :state :time :random}`.

   The gate is ctx-based, so it's applied per-tenant: a USER-graph
   execution runs in an org-ctx carrying this set (the tenancy addon
   binds it — `tenancy.addon` / `tenancy.app-router`), while the PLATFORM
   ctx stays unrestricted (the platform's own web-server / vault / config
   NEED :network / :env / :io / :process, so the default ctx can't be
   globally restricted)."
  (set/difference known-effects cloud-forbidden-effects))


;; Seam: a `(fn [org] -> allowed-effects-set)` the tenancy addon installs to
;; resolve a tenant's effect allow-list from its PLAN/tier (task #4) — e.g. a
;; free org stays on `default-cloud-allowed-effects`, a paid org gets
;; `:network` too. nil (no addon / single-tenant) or an unknown org → the
;; locked default, so behaviour is unchanged until a plan widens it. (`defonce`
;; takes no docstring; `defonce` so a namespace reload keeps the installed fn.)
(defonce cloud-allowed-effects-resolver (atom nil))


(defn cloud-allowed-effects-for
  "The effect allow-list a tenant `org`'s submitted graph runs under —
   resolved from its plan via the installed `cloud-allowed-effects-resolver`,
   falling back to the locked `default-cloud-allowed-effects` when no resolver
   is installed or it returns nil (free tier / no addon)."
  [org]
  (or (when-let [f @cloud-allowed-effects-resolver]
        ;; Fail-SAFE + fail-SECURE: a resolver that throws (misconfigured, or a
        ;; stale one left installed against a closed storage) must not crash
        ;; every tenant execute, and the safe fallback is the LOCKED default —
        ;; never accidentally widen a tenant's effects because plan lookup
        ;; broke. In production the resolver is installed once against a live
        ;; storage and never throws; this only guards against a bad install.
        (try (f org) (catch Exception _ nil)))
      default-cloud-allowed-effects))


(def cloud-request-allowed-effects
  "The `*allowed-effects*` a TENANT HTTP REQUEST runs under at the handler
   level (bound by `tenancy.addon`). Broader than
   `default-cloud-allowed-effects` by exactly `:raw-sql`: the trusted platform
   handler reads storage through `:pg-query` (a `:raw-sql`-recording base-fn)
   ON THE TENANT'S BEHALF, so gating `:raw-sql` here would 403 essentially
   every tenant request. It STILL blocks the external-world effects
   `#{:env :io :network :process}`, so a tenant can't drive those through a
   platform endpoint (defense in depth).

   The tenant's OWN submitted graph is gated more strictly — WITHOUT
   `:raw-sql` — at the execute boundary, where `crud.fn-execution/apply-execute`
   puts `default-cloud-allowed-effects` on the exec ctx. So a tenant can't run
   the raw-SQL escape hatch in their own fn, but the handler serving them can
   read storage."
  (conj default-cloud-allowed-effects :raw-sql))


;; Seam: a `(fn [svc thunk] -> result)` the tenancy addon installs so the
;; service reconciler runs each service INSIDE its org's sandbox — the addon's
;; fn binds `*current-org*` to the service's `:org-id` and `*allowed-effects*`
;; to that org's plan effects (`cloud-allowed-effects-for`), so a persistent
;; tenant service is gated exactly like a request-path execute. Combined with
;; the future conveyance (`conveyed-dynamic-vars`), the gate reaches the worker
;; thread the service spawns. nil (no addon / single-tenant) or a PLATFORM
;; service (nil / public org-id — the addon's fn decides) → the thunk runs
;; UNRESTRICTED, so the platform's own services (web-server, vault, cron) keep
;; their full effects. Kept in core (not tenancy) so the reconciler — which is
;; core — has no tenancy dependency; mirrors `cloud-allowed-effects-resolver`.
(defonce service-execution-scope (atom nil))


(defn run-service-scoped
  "Run `thunk` — a 0-arg fn that starts ONE service via `execute` — under the
   installed `service-execution-scope`, or directly when none is installed.
   The seam decides, by the service's `:org-id`, whether to sandbox: a tenant
   service is wrapped in its org's effect gate + org context; a platform
   service (and every service in single-tenant mode) runs unrestricted. A seam
   that throws propagates (a genuinely misconfigured install must not silently
   run a service unsandboxed — fail closed, unlike the effects RESOLVER which
   fails safe to the locked default)."
  [svc thunk]
  (if-let [scope @service-execution-scope]
    (scope svc thunk)
    (thunk)))


(def ^:dynamic *scrub-internal-errors?*
  "When true (bound by `tenancy.addon` for org≠public requests, alongside
   `*allowed-effects*`), failed-execution outcomes surfaced to the client —
   AND persisted into the org-scoped `:fn-execution` row the history panel
   reads — are scrubbed by `persist/scrub-outcome`: only whitelisted
   user-level error types pass verbatim; everything else (raw JDBC/IO
   messages can carry SQL text, paths, class names) is replaced by
   an opaque `Internal error, ref: <uuid>` with the full detail logged server-side
   under that ref. Default false: single-tenant / platform keeps full
   errors. Conveyed into the async record-completion future by the
   binding (futures carry dynamic bindings)."
  false)


(defn run-with-timeout
  "Run `thunk` bounded by `timeout-ms`. Returns its value, or `::timeout`
   (cancelling the run) when it overruns, `::error` when it throws, or
   `::rejected` when a bounded `executor` is SATURATED. Generic — the caller
   arranges cooperative cancellation (bind `*cancel-check*` inside the thunk so
   an interrupted execute aborts). Lives here, next to `execute`, so both the
   cloud app-router and a BYO executor bound a handler through the same helper
   without either depending on the other.

   `executor` (optional `java.util.concurrent.ExecutorService`): when given,
   the run is SUBMITTED to it — pass the shared bounded execution pool
   (`persist/current-execution-pool`) so the FaaS app-router / BYO handler
   paths QUEUE under load and shed with `::rejected` → 503 instead of piling
   unbounded soloExecutor threads (P1.3). When nil (the default), the legacy
   unbounded `future` (Clojure's soloExecutor) runs it — back-compatible for
   existing callers, which never see `::rejected`.

   CALLER CONTRACT: the sentinels are keywords in THIS namespace —
   `:graphden.executor.compile-runtime/{timeout,error,rejected}`. Callers MUST
   match them QUALIFIED (`::cr/timeout` via an alias, or the fully-qualified
   form), never bare `::timeout`/`::error` — a bare `::error` resolves to the
   caller's OWN namespace, compiles fine, and silently never matches, so an
   errored/timed-out handler leaks the raw sentinel instead of a 5xx."
  ([timeout-ms thunk] (run-with-timeout timeout-ms thunk nil))
  ([timeout-ms thunk executor]
   (if executor
     (if-let [fut (try (java.util.concurrent.ExecutorService/.submit
                         ^java.util.concurrent.ExecutorService executor
                         ^Callable thunk)
                       (catch java.util.concurrent.RejectedExecutionException _ nil))]
       (let [result (try (java.util.concurrent.Future/.get
                           fut (long timeout-ms) java.util.concurrent.TimeUnit/MILLISECONDS)
                         (catch java.util.concurrent.TimeoutException _ ::timeout)
                         (catch Exception e
                           ;; The sentinel is what callers dispatch on, but
                           ;; the CAUSE must not vanish — an undifferentiable
                           ;; ::error made failing handlers undebuggable.
                           (log/warn (or (ex-cause e) e)
                                     "run-with-timeout: handler threw")
                           ::error))]
         (when (identical? result ::timeout)
           (java.util.concurrent.Future/.cancel fut true))
         result)
       ::rejected)
     (let [fut (future (thunk))
           result (try (deref fut timeout-ms ::timeout)
                       (catch Exception e
                         (log/warn (or (ex-cause e) e)
                                   "run-with-timeout: handler threw")
                         ::error))]
       (when (identical? result ::timeout) (future-cancel fut))
       result))))


(defn record-effect!
  "Record that the calling impl is about to perform an effect of
   `category`. The vocabulary in use across the package layer is
   `:db`, `:env`, `:io`, `:network`, `:process`, `:state`, `:time`,
   `:random`, `:raw-sql`
   (same vocabulary as rich-type-of `:effects`); `cloud-forbidden-effects`
   marks the security-sensitive subset.

   GATE: when `*allowed-effects*` is a non-nil set and `category` is not
   in it, throws `:execution/forbidden-effect` BEFORE the impl performs
   the side effect (record-effect! is called first by convention). No-op
   gate when unrestricted. Tracing into `*effect-trace*` is a no-op
   outside an execution trace context."
  [category]
  (when (and *allowed-effects* (not (contains? *allowed-effects* category)))
    (throw (ex-info (str "Forbidden effect: " category
                         " (allowed: " *allowed-effects* ")")
                    {:type :execution/forbidden-effect
                     :effect category
                     :allowed *allowed-effects*})))
  (when *effect-trace*
    (swap! *effect-trace* conj category)))


(defn- throw-fn-not-found!
  "Canonical `:execution-error/fn-not-found` for a missing compiled-
   registry entry. The same shape was inlined at two call sites; lifted
   here so the next caller doesn't have to copy-paste."
  [fn-id]
  (throw (ex-info (str "Function not found: " fn-id)
                  {:type :execution-error/fn-not-found
                   :fn-id fn-id})))


;; =============================================================================
;; Execute
;; =============================================================================

(defn execute
  "Invoke `fn-id` via the compiled registry. `named-args` is a `{arg-name
   value}` map using the outermost external arg names (rename-aware).

   HOF impls that deref a `:fn`-type arg end up with a callable (from
   `rt/hof-callable`) rather than a UUID and hand it back in through
   this same entry point. For single-entry args the value is unwrapped
   from the map; for empty or multi-entry args the whole map is passed
   through.

   Cancellation: when `*cancel-check*` is bound (by the fn-execution
   harness), it's called at the top of each invocation — if the
   per-execution flag flipped, an `InterruptedException` propagates
   up through the call stack and is caught by the fn-execution
   reaper which writes `:status :cancelled`."
  [ctx fn-id named-args]
  (check-cancel!)
  ;; Per-namespace execute gate (docs/TENANCY_SEAM.md § Execute guard):
  ;; consult the ctx's `:execute-guard`
  ;; ONCE per top-level execute (the recursion flag keeps it off the hot
  ;; sub-fn path), then re-enter. The guard throws `:authz/forbidden` on a
  ;; denied tenant execute; absent (core / system / admin) → no-op.
  (if (and (not *execute-authorized*) (:execute-guard ctx))
    (binding [*execute-authorized* true]
      ((:execute-guard ctx) ctx fn-id)
      (execute ctx fn-id named-args))
    ;; Effect sandbox: when the ctx restricts effects, bind
    ;; `*allowed-effects*` so `record-effect!` can gate. `identical?`
    ;; skips re-binding on recursive `execute` calls within the same ctx;
    ;; an unrestricted ctx (the common case) pays zero binding overhead.
    (let [allowed (:allowed-effects ctx)]
      (if (and allowed (not (identical? allowed *allowed-effects*)))
        (binding [*allowed-effects* allowed]
          (execute ctx fn-id named-args))
        (if (fn? fn-id)
          (let [args (or named-args {})]
            (if (= 1 (count args))
              (fn-id (first (vals args)))
              (fn-id args)))
          (let [reg (registry ctx)
                closure (or (get reg fn-id) (throw-fn-not-found! fn-id))
                ;; Translate caller's name-keyed args into the dual-key
                ;; shape (slot-id + name). Phase 4 readers prefer slot-id;
                ;; the name half covers env-binding / lambda-arg / rename-
                ;; alias paths that still flow under name keys today.
                translated (translate-named-args fn-id (or named-args {})
                                                 (lookups-for-ctx ctx))]
            ;; compile-eager closure signature: `(fn [free-args ctx])`.
            ;; Child callables are captured at compile time — `reg`
            ;; is no longer needed by the runtime.
            (closure translated ctx)))))))


(defn execute-by-name
  [ctx fn-name named-args]
  (let [match (lookup/query-fn-by-name (:storage ctx) fn-name)]
    (when-not match
      (throw (ex-info (str "Function '" fn-name "' not found")
                      {:type :execution-error/fn-not-found
                       :fn-name fn-name})))
    (execute ctx (:id match) named-args)))


(def defer-handler-call-cache
  "Re-export of `compile-eager/defer-handler-call-cache`. Wrap the ctx of
   a router-BUILD execute (`execute-by-name` producing a route-collection
   Ring router) so the graph-executed handlers it builds don't capture a
   shared build-time call-cache — each later request primes its own. See
   the target's docstring for the cross-principal-leak it prevents."
  ce/defer-handler-call-cache)


;; =============================================================================
;; HOF callable helpers
;; =============================================================================

(defn make-single-arg-callable
  "Build a top-level Clojure callable over `fn-id`. Dispatches on
   the free-arg count of the target:
   - 0 free args → variadic-ignore callable
   - 1 free arg → single-arg callable (item bound to that name)
   - 2+         → map-callable, caller passes `{name → value}`

   If `fn-id` is already a callable, returns it as-is — convenience
   for HOF impls that may receive either a fn-id or an already-built
   callable.

   Translates the caller's name-keyed map through
   `translate-named-args` before invoking the closure, mirroring
   `execute`'s public boundary — slot-id keys get written for every
   walker entry matching the caller's name, the original name key
   stays so name-keyed paths (env-binding, lambda-args,
   `apply-rename-aliases`) still find values."
  [ctx fn-id]
  (if (fn? fn-id)
    fn-id
    (let [reg (registry ctx)
          closure (or (get reg fn-id) (throw-fn-not-found! fn-id))
          lookups (lookups-for-ctx ctx)
          free-names (vec (r/deep-free-ext-names fn-id lookups))]
      (ce/make-shape-callable free-names
                              (fn [args]
                                (closure (translate-named-args
                                           fn-id (or args {}) lookups)
                                         ctx))))))

(ns graphden.system.branch-router
  "Per-branch ExecutionContext registry + Ring dispatcher for the
   versioning UI.

   The compiled executor closes over a specific ExecutionContext at
   compile-time (see `executor.compile/build-closure` line ~471 —
   `(impl args ctx)`). That ctx carries a VersionedStorage bound to
   one branch, plus its own compiled-registry / graph-cache atoms.
   So serving requests on a non-main branch needs ITS OWN ctx with
   ITS OWN compiled registry — same fn-graph, but bound to a
   storage wrapper pointing at the requested branch.

   This namespace holds the atom of per-branch ctx + Ring callable,
   and the dispatcher that the `web.ring-adapter/branch-routing-wrap`
   base-fn delegates to. Lazy build on first request; invalidation
   piggybacks on the existing `invalidate-graph-cache!` (the branch
   ctx's compiled-registry atom is cleared, and our cached Ring
   callable re-reads the registry on every call so the next request
   picks up a fresh rebuild).

   Split by concern into sub-namespaces this one orchestrates:
   `.request` (what a Ring request asks for — branch ref, page load,
   `/livez`), `.cache` (dropping cached entries + the pinned-branches
   seam), `.epoch` (graph-epoch validation and the self-heal) and
   `.recheck` (re-recording types into a ctx's rich-types slice)."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.crud.debug-capture :as debug-capture]
    [graphden.crud.fn-execution.lookup :as fn-lookup]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as ctx]
    [graphden.executor.registry.core :as registry-core]
    [graphden.storage.postgres.graph-epoch :as epoch]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router.cache :as cache]
    [graphden.system.branch-router.epoch :as router-epoch]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.system.branch-router.request :as br-req]
    [graphden.system.route-collection :as rc]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.merge :as vmerge]
    [graphden.versioning.storage.resolution :as vres]))


;; =============================================================================
;; Per-branch ctx + Ring callable cache
;; =============================================================================

(def ^:private default-max-cached-branches
  "Soft cap on the number of per-branch ctx entries kept warm. The
   LRU evicts the least-recently-used non-default entry when adding
   would exceed this. 16 covers a dev workflow with a handful of
   active feature branches comfortably; production multi-tenant
   tunes this via the `GRAPHDEN_MAX_CACHED_BRANCHES` env var (read
   by the `:exec/branch-router` init-key into `create-router`'s
   `:max-size` option)."
  16)


(defrecord BranchRouter
  [base-ctx default-branch-id handlers handler-fn-id])


;; `handlers` is an atom of `{branch-id → {:ctx ExecutionContext :handler ring-fn :built-at Instant :last-used long-ms}}`.
;; `default-branch-id` is the main-branch id — requests that don't pick a branch land here.
;; `handler-fn-id` is the compiled fn-id of the top-level Ring handler
;; (`_app-ring-response`) — resolved once at construction so per-request
;; dispatch is just a map lookup.
;; `:max-size` (assoc'd onto the record, see `create-router`) caps the
;; cache; LRU evicts the oldest non-default entry on overflow.


(defn- build-branch-ctx
  "Create a fresh ExecutionContext bound to `branch-id`. Each branch
   gets its own atoms (compiled-registry / graph-cache / compile-deps)
   so writes invalidate the right slice — `invalidate-graph-cache!`
   takes a ctx and only touches that ctx's caches."
  [base-ctx branch-id]
  (let [base-storage (vs/unwrap (:storage base-ctx))
        branch-storage (vs/->VersionedStorage base-storage branch-id)]
    (cond-> (ctx/create-context {:storage branch-storage
                                 :base-fns (:base-fns base-ctx)
                                 :clock (:clock base-ctx)
                                 :allowed-effects (:allowed-effects base-ctx)
                                 ;; Inherit the executor's org shard — a branch
                                 ;; ctx must compile the same slice of the graph
                                 ;; as its base, or the pod would pull every
                                 ;; tenant's fns back in through the side door.
                                 :executor-orgs (:executor-orgs base-ctx)
                                 ;; Inherit the pod role so per-branch requests
                                 ;; apply the same hosted/BYO refusal.
                                 :byo-executor? (:byo-executor? base-ctx)})
      ;; Privileged structural-read storage for THIS branch (§4 Design B): the
      ;; raw PG re-wrapped at branch-id, so `rebuild!` compiles every org's fns
      ;; in this executor's shard (isolation stays on the org-scoped `:storage`
      ;; above at runtime). Absent in single-tenant → compile reads fall back to
      ;; :storage.
      (:pg-storage base-ctx)
      (assoc :pg-storage (:pg-storage base-ctx)
             :compile-storage (vs/->VersionedStorage (:pg-storage base-ctx) branch-id))
      ;; Inherit the cross-pod NOTIFY emitter. Without it a CRUD write
      ;; served on a non-default branch tells sibling pods nothing —
      ;; `crud.entities/notify-after-write!` reads it off the ctx, and
      ;; only the base ctx used to carry it, so every branch edit was
      ;; silently pod-local.
      (:notify-emitter base-ctx) (assoc :notify-emitter (:notify-emitter base-ctx))
      ;; Inherit the LISTEN side too — an event-driven SSE stream served
      ;; on a branch registers its wake callback on the same listener.
      (:notify-listener base-ctx) (assoc :notify-listener (:notify-listener base-ctx))
      ;; Inherit the off-record auth seam so per-branch execution
      ;; authenticates through the same provider as the base context.
      (:auth-provider base-ctx) (assoc :auth-provider (:auth-provider base-ctx))
      ;; Inherit the per-namespace execute guard (§4.2) — branch execution
      ;; must enforce the same grants as the base context.
      (:execute-guard base-ctx) (assoc :execute-guard (:execute-guard base-ctx))
      ;; Inherit the self-serve DNS-verify seam (§3.4 #2) — the
      ;; `:invoke-verify-domain` base-fn runs in the per-branch handler ctx.
      (:verify-domain base-ctx) (assoc :verify-domain (:verify-domain base-ctx))
      ;; Inherit the user-model seam — `:invoke-login` / `:invoke-create-user`
      ;; run in the per-branch handler ctx.
      (:user-ops base-ctx) (assoc :user-ops (:user-ops base-ctx))
      ;; Inherit the self-serve API-token seam — the
      ;; `:invoke-{mint,list,revoke}-my-token` base-fns run in the per-branch
      ;; handler ctx.
      (:my-tokens base-ctx) (assoc :my-tokens (:my-tokens base-ctx)))))


(defn- ring-callable-for-ctx
  "Returns a `(fn [request])` callable that delegates to the compiled
   closure for `handler-fn-id` in `branch-ctx`. The registry is
   re-read on every invocation so the latest delta-recompile result
   is visible without rebuilding the callable.

   Debug «catch next request» (crud.debug-capture): when a trap is
   armed for the request's org + this branch and the request matches,
   the invocation runs through `run-captured!` — path-trace bound,
   outcome persisted as a `:fn-execution` row — and returns the same
   response. Unarmed cost: one atom deref + `empty?`."
  [branch-ctx handler-fn-id]
  (let [branch-id (vs/current-branch-id (:storage branch-ctx))]
    (fn [request]
      (let [reg (cr/registry branch-ctx)
            closure (get reg handler-fn-id)]
        (when-not closure
          (throw (ex-info "Branch handler closure missing"
                          {:type :execution-error/fn-not-found
                           :fn-id handler-fn-id
                           :branch-id branch-id})))
        ;; compile-eager closure signature: `(fn [free-args ctx])`.
        (if-some [trap (when (debug-capture/any-traps?)
                         (debug-capture/consume-trap! branch-id request))]
          (debug-capture/run-captured! trap branch-id branch-ctx handler-fn-id
                                       request
                                       #(closure {:request request} branch-ctx))
          (closure {:request request} branch-ctx))))))


(defn- optional-ring-callable-for-ctx
  "Like `ring-callable-for-ctx` but TOLERANT: returns nil when the closure
   is absent (an OPTIONAL package's handler this branch doesn't have),
   instead of throwing. Used for the `registry` / `mcp` per-branch handlers
   — each is a `:router-or-nil`-backed handler that returns nil on no-match,
   so a nil result (missing closure OR no route matched) means the caller
   falls through to the next optional handler / the main handler. Re-reads
   the registry each call, so a delta-recompile (e.g. after a package
   publish/install write) is visible without rebuilding the callable — this
   is exactly the freshness the boot-frozen route-collection seam lacked."
  [branch-ctx handler-fn-id]
  (fn [request]
    (when-let [closure (get (cr/registry branch-ctx) handler-fn-id)]
      (closure {:request request} branch-ctx))))


(defn- compose-branch-handler
  "The per-branch Ring callable. Consults each OPTIONAL handler (registry /
   mcp — present only when their package is loaded) in turn; the first
   non-nil response wins. When none match (all nil → their `:router-or-nil`
   found no route, or the package isn't loaded), falls through to the MAIN
   `_app-ring-response` handler. This serves the optional packages' routes
   through the SAME per-branch, invalidation-fresh, request-threaded
   machinery as the main router — the requirement the seam could not meet."
  [branch-ctx handler-fn-id optional-handler-fn-ids]
  (let [main (ring-callable-for-ctx branch-ctx handler-fn-id)
        opts (mapv #(optional-ring-callable-for-ctx branch-ctx %)
                   optional-handler-fn-ids)]
    (if (seq opts)
      (fn [request]
        (or (some (fn [h] (h request)) opts)
            (main request)))
      main)))


(defn- now-ms
  []
  (System/currentTimeMillis))


(defn- touch!
  "Update the cache entry's `:last-used` so subsequent eviction
   decisions see the recent access. Idempotent — if the entry is
   gone (raced with a concurrent invalidate / eviction) the swap is
   a no-op."
  [handlers branch-id]
  (swap! handlers
         (fn [m]
           (if-let [entry (get m branch-id)]
             (assoc m branch-id (assoc entry :last-used (now-ms)))
             m))))


(defn- evict-lru-if-full
  "If the cache is at `max-size` AND inserting `new-id` would push
   it past, drop the oldest non-default, non-pinned entry. The
   default-branch entry is pinned (it's seeded eagerly and represents
   the hottest path); so is every branch in `pinned` — a running
   service holds that ctx by reference, and the cap used to be the
   one eviction path that ignored pins (heal + idle sweep honour
   them). `new-id` is also excluded from consideration — if the
   caller is replacing an existing entry, no eviction is needed. When
   everything else is pinned the cache simply grows past the cap."
  [m max-size default-branch-id new-id pinned]
  (let [evictable (apply dissoc m default-branch-id new-id pinned)]
    (if (and (>= (count m) max-size)
             (not (contains? m new-id))
             (seq evictable))
      (let [oldest-id (->> evictable
                           (sort-by (fn [[_ v]] (or (:last-used v) 0)))
                           ffirst)]
        (dissoc m oldest-id))
      m)))


(defn- build-holder
  "Per-branch build-coordination holder: `{:lock ReentrantLock :gen
   AtomicLong}`, stored on the router as a ConcurrentHashMap so distinct
   branch-ids never contend — only same-branch contenders serialize.

   `:lock` dedupes concurrent `build-and-cache!` callers for the same
   cold branch: the first arrival builds under the lock, the rest acquire
   it, double-check, and find the entry the first arrival wrote.
   A `ReentrantLock`, NOT `locking`/`synchronized`: the guarded body runs
   `cr/rebuild!` (a full compile, seconds) on a cold-branch miss, and a
   virtual thread blocked on a monitor pins its carrier (JDK 21) — same
   reason as `compile-runtime/call-with-invalidation-lock`.

   `:gen` is the branch's build generation. `invalidate!` bumps it before
   dropping the holder; a build that captured the generation BEFORE its
   (multi-second) compile compares it at install time and DISCARDS its
   result on a mismatch — a concurrent delete during the build advanced
   the generation, so installing would resurrect a ctx for a branch that
   no longer exists (the epoch-heal backstop can't catch it: the delete's
   epoch bump advances this pod's watermark past the delete WHILE the
   build runs). See `build-and-cache!` / `invalidate!`."
  [router branch-id]
  (when-let [monitors (:build-monitors router)]
    (java.util.concurrent.ConcurrentHashMap/.computeIfAbsent
      monitors
      branch-id
      (reify java.util.function.Function
        (apply
          [_ _]
          {:lock (java.util.concurrent.locks.ReentrantLock.)
           :gen (java.util.concurrent.atomic.AtomicLong. 0)})))))


(defn- branch-is-merge-target?
  "True iff `branch-id` is the target of at least one branch-merge — the half of
   the old `branch-has-own-content?` a delta compile can't cheaply seed (the
   merged fns own their version rows on the SOURCE branch), so a merge-target
   branch takes the full-rebuild path."
  [base-storage branch-id]
  (boolean (seq (sp/query-entities base-storage :branch-merge
                                   {:target-branch-id branch-id} {:limit 1}))))


(defn- chain-divergent-fn-ids
  "The `:fn` ids whose resolved definition on `branch-id` differs from the
   router's base (`default-branch-id` — the root/default branch, main): the
   UNION of `merge-affected-fn-ids` over EVERY branch in `branch-id`'s
   ancestor chain EXCEPT the default itself.

   Why the whole chain, not just `branch-id`'s own rows: a branch C forked
   off a NON-root branch B inherits B's edits through resolution (C's
   VersionedStorage resolves along C→B→…→main). `merge-affected-fn-ids`
   queries version rows for ONE branch, so `(… base C)` on a C with no own
   edits is empty — which would send C down the graph-identical fast path
   and make it execute MAIN's pre-B closures verbatim (wrong result from the
   first request). Unioning across the chain makes the divergence set
   relative to main: empty ⇒ genuinely identical to main (fast path);
   non-empty ⇒ delta-recompile that FULL set (C's own overrides AND every
   ancestor edit C inherits). The default branch's chain is just `[default]`,
   so it yields the empty set and never deltas against itself."
  [base-storage default-branch-id branch-id]
  (let [chain (vres/collect-branch-chain base-storage branch-id)
        ;; Fns merged INTO an ancestor own their version rows on the merge
        ;; SOURCE branch, not on the ancestor, and the source is NOT in
        ;; `branch-id`'s ancestor chain — so `merge-affected-fn-ids` over
        ;; the chain alone MISSES them. A fork of a merge-target then
        ;; inherits those merged edits through resolution
        ;; (`branch-visibility-ids` walks chain + merge-sources) yet is
        ;; told nothing diverged → it runs main's pre-merge closures (the
        ;; A1.1 tail of the W1 divergence-set fix — same wrong-result
        ;; class, one merge-hop deeper). Add the source branch of every
        ;; merge whose target lands on some chain member; over-inclusion
        ;; only costs an unnecessary delta-recompile, never a stale run.
        merge-sources (when (seq chain)
                        (->> (sp/query-entities base-storage :branch-merge
                                                {:target-branch-id (vec chain)})
                             (keep :source-branch-id)))]
    (into #{}
          (comp (remove #(= % default-branch-id))
                (mapcat #(vmerge/merge-affected-fn-ids base-storage %)))
          (into (set chain) merge-sources))))


(defn- build-actual-entry!
  "Lazy-compile per-branch ctx + Ring callable. Fast path: when the
   branch is graph-identical to its base (no own version rows, no
   merge edges) we copy the base ctx's compiled registry directly
   into the branch's ctx — compile-eager closures are ctx-
   independent, so the same `{fn-id → closure}` map serves both.
   Slow path: full `rebuild!` for the branch.

   No atom writes; caller installs the result."
  [{:keys [base-ctx default-branch-id handler-fn-id optional-handler-fn-ids]} branch-id]
  (let [branch-ctx (assoc (build-branch-ctx base-ctx branch-id)
                          ;; Private rich-types slice, forked from the base's
                          ;; view: the compile below (and every later request
                          ;; dispatched to this branch) records type/effect
                          ;; entries HERE, so branch compiles stop clobbering
                          ;; the base registry (`/api/types` cross-branch
                          ;; union — VERSIONING § Known gaps).
                          :rich-types-atom
                          (registry-core/fork-rich-types-atom
                            (or (:rich-types-atom base-ctx)
                                (registry-core/active-rich-types-atom)))
                          ;; …and its per-org cousin: tenant name-precedence
                          ;; entries recorded under this branch stay private
                          ;; to it (same fork-at-build contract).
                          :per-org-rich-atom
                          (registry-core/fork-per-org-rich-atom
                            (or (:per-org-rich-atom base-ctx)
                                (registry-core/active-per-org-rich-atom))))
        base-storage (vs/unwrap (:storage base-ctx))
        merge-target? (branch-is-merge-target? base-storage branch-id)
        ;; Divergence RELATIVE TO MAIN across the whole ancestor chain, not
        ;; just this branch's own rows — otherwise a branch forked off a
        ;; non-root branch (which inherits that branch's edits through
        ;; resolution) would take the graph-identical fast path and execute
        ;; main's pre-fork closures. See `chain-divergent-fn-ids`.
        own-fn-ids (when-not merge-target?
                     (chain-divergent-fn-ids base-storage default-branch-id branch-id))
        base-registry (some-> (:compiled-registry base-ctx) deref)]
    (recheck/call-with-ctx-slices
      branch-ctx
      (fn []
        (cond
          ;; 1. Identical to base → reuse the base registry directly.
          (and base-registry (not merge-target?) (empty? own-fn-ids))
          (cr/instantiate-from-templates! base-ctx branch-ctx)

          ;; 2. Divergent from main by own OR inherited version rows → delta-compile
          ;;    on top of base.
          ;;    A branch differs from main by a handful of fns; a full rebuild of
          ;;    the whole ~3700-fn graph for that was measured at ~57s and BLOCKS the
          ;;    executor (a divergent branch whose ctx was evicted from the LRU pays it
          ;;    on next access — the `compile-all` cache is keyed by graph shape, so a
          ;;    branch edit changes the shape and misses). Reuse the base's closures
          ;;    for every unchanged fn; recompile only the fns this branch overrides
          ;;    (+ their reverse-dep closure) against the branch's view.
          (and base-registry (not merge-target?) (seq own-fn-ids))
          (do
            ;; Seed the branch registry + reverse-dep index from the base, but NOT the
            ;; base graph-cache — leave it empty so `delta-recompile!` reads the
            ;; BRANCH's resolved graph and compiles the overrides against it.
            (reset! (:compiled-registry branch-ctx) base-registry)
            (when-let [src-deps (some-> (:compile-deps base-ctx) deref)]
              (when-let [holder (:compile-deps branch-ctx)]
                (reset! holder src-deps)))
            (cr/delta-recompile! branch-ctx (set own-fn-ids))
            ;; The slice forked from the base knows nothing about THIS
            ;; branch's own fns: record them now, before the entry is served.
            (recheck/record-own-fn-types! branch-ctx branch-id own-fn-ids))

          ;; 3. Merge target (merged fns own their rows on the source, not cheaply
          ;;    seedable here), or cold start with no base registry → full compile.
          :else
          (cr/rebuild! branch-ctx))
        ;; Error-tolerance: repopulate the branch's derived diagnostics for
        ;; editor-authored fns (async; see § Ctx-build diagnostics recompute).
        ;; Inside the binding: `future` conveys it, so the recheck records
        ;; into THIS branch's slice.
        (recheck/schedule-user-fn-recheck! branch-ctx branch-id)))
    {:ctx branch-ctx
     :handler (compose-branch-handler branch-ctx handler-fn-id optional-handler-fn-ids)
     :built-at (java.time.Instant/now)
     :last-used (now-ms)}))


(defn- install-built-entry!
  "Commit a freshly-built `entry` for `branch-id` into `handlers`,
   applying the LRU. Two hardening steps beyond the bare `assoc`:

   1. Generation guard (L1): install ONLY if `gen-holder`'s generation
      still equals `gen0` (captured before the build). A concurrent
      `invalidate!` (branch delete) bumps the generation mid-build, so a
      mismatch means the branch is gone — drop the result rather than
      resurrect its ctx. The check rides inside the `handlers` swap so it
      is atomic w.r.t. the install.
   2. Evicted-holder reap (L4): whatever branch the LRU dropped also loses
      its `build-monitors` holder here. `evict-lru-if-full` only touches
      `handlers`, so without this the per-branch lock+gen holder would leak,
      growing unbounded over long churn of many branches. Mirrors
      `invalidate!`'s removal; a holder a concurrent build still holds is at
      worst re-created by `computeIfAbsent` (dedup lost for that one build,
      never a correctness issue) — the same tiny race `invalidate!` accepts.

   Returns `entry` regardless (the request in flight is still served from
   it even when the cache install is discarded)."
  [{:keys [handlers build-monitors]} branch-id entry max-size default-branch-id gen-holder gen0]
  (let [pinned (cache/pinned-branches)
        [old new] (swap-vals!
                    handlers
                    (fn [m]
                      (if (and gen-holder
                               (not= gen0 (java.util.concurrent.atomic.AtomicLong/.get gen-holder)))
                        m
                        (-> m
                            (evict-lru-if-full max-size default-branch-id branch-id pinned)
                            (assoc branch-id entry)))))]
    (when build-monitors
      (doseq [gone (keys old)
              :when (and (not= gone branch-id) (not (contains? new gone)))]
        (java.util.concurrent.ConcurrentHashMap/.remove build-monitors gone)))
    entry))


(def ^:dynamic *build-entry-override*
  "Test seam: when bound to `(fn [router branch-id] entry)`,
   `build-and-cache!` builds through it instead of `build-actual-entry!`
   — the build-generation / LRU tests script a build (a delete landing
   mid-build) around the real install path. A dynamic var for the
   reason `*resolve-uncached-override*` gives: `with-redefs` of the
   builder rebinds it for every parallel test that cold-builds a real
   branch. nil (the default) means production behaviour."
  nil)


(defn- build-entry!
  [router branch-id]
  (if-let [f *build-entry-override*]
    (f router branch-id)
    (build-actual-entry! router branch-id)))


(defn- build-and-cache!
  "Build the per-branch ctx + Ring callable and cache it. Cold-branch
   thundering-herd safe — concurrent callers for the same branch-id
   serialize on the `build-holder` lock, double-check inside it, and
   share the single rebuild!. LRU evicts the oldest non-default
   entry if the cache is at `:max-size`.

   The build generation is captured BEFORE the (multi-second) build and
   re-checked at install (`install-built-entry!`) so a branch deleted
   mid-build is not resurrected — see `build-holder`."
  [{:keys [handlers default-branch-id] :as router} branch-id]
  (let [max-size (or (:max-size router) default-max-cached-branches)]
    (if-let [holder (build-holder router branch-id)]
      (let [lock (:lock holder)
            gen-holder (:gen holder)]
        (java.util.concurrent.locks.ReentrantLock/.lock lock)
        (try
          (or (get @handlers branch-id)
              (let [gen0 (java.util.concurrent.atomic.AtomicLong/.get gen-holder)
                    entry (build-entry! router branch-id)]
                (install-built-entry! router branch-id entry max-size
                                      default-branch-id gen-holder gen0)))
          (finally (java.util.concurrent.locks.ReentrantLock/.unlock lock))))
      ;; No monitor map → test path with a hand-constructed router.
      ;; Best-effort: just swap, accepting the rare duplicate build.
      (let [entry (build-entry! router branch-id)]
        (install-built-entry! router branch-id entry max-size
                              default-branch-id nil 0)))))


(def ^:dynamic *ctx-idle-ttl-ms*
  "How long a cached non-default branch ctx may go unused before it is
   dropped. The count cap (`default-max-cached-branches`, 16) bounds the
   worst case; this bounds the STEADY state — every cached ctx is a
   compiled registry (tens of MB), and a workspace keeps its merged
   source branches forever (the target resolves through them), so a
   day's branches would otherwise sit warm until the cap. A dropped
   ctx rebuilds on its next request (a branch without own changes copies
   the base by value)."
  (* 15 60 1000))


(def ^:dynamic *ctx-idle-sweep-period-ms*
  "How often `evict-idle-ctxs!` scans the cache — on the request path,
   so keep it rare; the scan itself is a map walk."
  60000)


(defn- evict-idle-ctxs!
  "Drop every non-default, non-pinned cached ctx whose `:last-used` is
   older than `*ctx-idle-ttl-ms*` (a branch with a running service is
   pinned — its idle ctx is the service's ctx). At most once per `*ctx-idle-sweep-period-ms*`
   (a CAS on the router's `:idle-sweep` stamp keeps concurrent requests
   from repeating the walk); routers built without the stamp (test
   stubs) never sweep."
  [{:keys [handlers default-branch-id idle-sweep] :as router}]
  (when idle-sweep
    (let [now (now-ms)
          prev @idle-sweep]
      (when (and (> (- now prev) *ctx-idle-sweep-period-ms*)
                 (compare-and-set! idle-sweep prev now))
        (let [pinned (cache/pinned-branches)]
          (doseq [[bid entry] @handlers]
            (when (and (not= bid default-branch-id)
                       (not (contains? pinned bid))
                       (> (- now (or (:last-used entry) now)) *ctx-idle-ttl-ms*))
              (cache/invalidate! router bid))))))))


(defn entry-for
  "The cached `{:ctx :handler}` entry for `branch-id`, building lazily
   on miss. Falls back to the default-branch entry when `branch-id` is
   nil or matches the default. Records the access via `touch!` on
   cache hits so the LRU eviction sees the freshest order, and sweeps
   idle entries (`evict-idle-ctxs!`) on the way."
  [{:keys [default-branch-id handlers] :as router} branch-id]
  (router-epoch/validate-graph-epoch! router)
  (evict-idle-ctxs! router)
  (let [effective (or branch-id default-branch-id)
        cached (get @handlers effective)]
    (when (and cached (not= effective default-branch-id))
      (touch! handlers effective))
    (or cached (build-and-cache! router effective))))


(defn handler-for
  "Ring callable for `branch-id` — see `entry-for`."
  [router branch-id]
  (:handler (entry-for router branch-id)))


(def ^:dynamic *ctx-for-override*
  "Test seam: when bound to `(fn [router branch-id] ctx)`, `ctx-for`
   delegates to it instead of the real cache lookup. Exists for the
   reconciler suite's stub (it hands the reconciler a minimal fake
   \"router\" and needs `ctx-for` to return a fixed base ctx).

   WHY a dynamic var and not `with-redefs`: a root rebind is
   process-global, so any test using it races every parallel test that
   goes through the real `ctx-for` — forcing `^:serial` pins.
   `binding` is per-thread; nil (the default) means production
   behaviour, at the cost of one Var deref on a cold path."
  nil)


(defn ctx-for
  "Return the per-branch ExecutionContext for `branch-id`, building
   lazily on miss. Useful for CRUD impls that need to call
   `invalidate-graph-cache!` after a write.

   Checks `*ctx-for-override*` first (test seam — see its docstring)."
  [router branch-id]
  (if-let [f *ctx-for-override*]
    (f router branch-id)
    (:ctx (entry-for router branch-id))))


(defn- current-scope
  "The tenant scope for branch resolution — the addon's `*current-org*` when the
   tenancy ns is loaded, so a tenant's branch ref resolves + caches PER ORG and
   never returns another org's same-named branch from the cache. nil in
   single-tenant / core. `resolve` (not requiring-resolve) so core never loads
   the addon. The resolution read is already org-scoped via OrgScoped; this only
   keys the cache to match."
  []
  (some-> (resolve 'graphden.tenancy.context/*current-org*) deref))


(defn invalidate-cached-branch!
  "Delta-invalidate ONE branch's ctx, but only if this pod has already
   built it. Returns true when something was invalidated.

   Used by the cross-pod NOTIFY path: the writing pod invalidated its own
   ctx inline, the receiving pod has to be told. Deliberately does NOT
   build the ctx — a pod that never served the branch has no stale state,
   and compiling it here would make it pay for a branch nobody asked it
   about."
  [{:keys [handlers]} branch-id seeds]
  (boolean
    (when-let [branch-ctx (some-> handlers deref (get branch-id) :ctx)]
      (if (seq seeds)
        (ctx/invalidate-graph-cache! branch-ctx seeds)
        (ctx/invalidate-graph-cache! branch-ctx))
      true)))


(defn invalidate-affected-ctxs!
  "Delta-invalidate every CACHED branch ctx whose resolved view includes
   a write made on `written-branch-id`, skipping that branch itself
   (its own ctx is invalidated by the caller).

   Which branches are affected? Exactly those that inherit from the
   written branch — `written-branch-id ∈ (collect-branch-chain … C)`.
   Version rows are branch-scoped, so a write on `dev` is invisible from
   `main`, while a write on `main` is visible from every branch that
   doesn't override that fn. This is the same reachability question the
   resolver answers on read, so it uses the resolver's own (memoised)
   chain walk.

   Never BUILDS a ctx. A branch this pod has never served has no cached
   registry to go stale, and compiling one here would make an unrelated
   pod pay for a branch nobody asked it about.

   Without this, a cached branch with no own version rows keeps serving
   the closures it copied from `main` at build time — its storage
   resolves the new rows, but its compiled registry never recompiles.
   Delta seeds keep the cost proportional to the edit: each affected ctx
   recompiles only the blast radius of `seeds`, not its whole graph.

   `seeds` nil ⇒ full clear on each affected ctx (the cross-cutting
   `:slot` / unknown-shape case)."
  [{:keys [handlers base-ctx]} written-branch-id seeds]
  (when-let [cached (and handlers written-branch-id base-ctx (seq @handlers))]
    (let [base-storage (vs/unwrap (:storage base-ctx))]
      (doseq [[branch-id entry] cached
              :when (not= branch-id written-branch-id)
              :let [branch-ctx (:ctx entry)]
              :when branch-ctx
              :when (some #(= written-branch-id %)
                          (vres/collect-branch-chain base-storage branch-id))]
        (try
          ;; Same three-way answer as `crud.entities/affected-fn-ids`: a non-empty
          ;; set delta-recompiles; `nil` means "unknown shape" and full-clears;
          ;; `#{}` means the write reached no compiled closure at all, so a sibling
          ;; has nothing to recompile either. `#{}` used to fall into the full
          ;; clear here, costing every cached sibling branch a whole-graph rebuild
          ;; on its next request.
          (cond
            (seq seeds) (do (ctx/invalidate-graph-cache! branch-ctx seeds)
                            ;; The child's rich-types slice learned nothing
                            ;; from a write it INHERITS — re-record the blast
                            ;; radius into it (async, bounded).
                            (recheck/recheck-ctx-types! branch-ctx branch-id seeds))
            (nil? seeds) (do (ctx/invalidate-graph-cache! branch-ctx)
                             (recheck/recheck-ctx-types! branch-ctx branch-id nil))
            :else nil)
          (catch Exception e
            ;; Best-effort: a stale sibling ctx is worse than a slow one,
            ;; but a throw here would fail the user's CRUD write. Drop the
            ;; cached entry so the next request rebuilds it from scratch.
            (log/warn e "sibling branch ctx invalidation failed — dropping entry"
                      {:branch-id branch-id})
            (swap! handlers dissoc branch-id)))))))


;; =============================================================================
;; Construction + dispatch
;; =============================================================================

(defn- resolve-handler-fn-id
  "Look up the top-level Ring handler fn by name in base storage."
  [base-storage handler-fn-name]
  (fn-lookup/query-fn-id-by-name (vs/unwrap base-storage)
                                 handler-fn-name
                                 true))


(defn create-router
  "Construct a BranchRouter. `handler-fn-name` is the string name of
   the top-level Ring handler fn-def (typically
   `\"_app-ring-response\"`). The default-branch entry is seeded
   eagerly so the very first request doesn't pay a compile pass.

   Options:
     :max-size  — soft cap on the cached per-branch ctx count, default
                  `default-max-cached-branches` (16). LRU evicts the
                  oldest non-default entry on overflow."
  ([base-ctx handler-fn-name]
   (create-router base-ctx handler-fn-name nil))
  ([base-ctx handler-fn-name {:keys [max-size optional-handler-fn-names]}]
   (let [;; Tag the base ctx with the rich-types atom it was built under
         ;; (the global in production, the thread's isolation override in
         ;; tests). The default branch keeps READING AND WRITING this very
         ;; atom — boot's package sync populated it — while every OTHER
         ;; branch forks a private copy in `build-actual-entry!`.
         base-ctx (cond-> base-ctx
                    (nil? (:rich-types-atom base-ctx))
                    (assoc :rich-types-atom (registry-core/active-rich-types-atom))
                    (nil? (:per-org-rich-atom base-ctx))
                    (assoc :per-org-rich-atom (registry-core/active-per-org-rich-atom)))
         default-branch-id (vs/current-branch-id (:storage base-ctx))
         handler-fn-id (resolve-handler-fn-id (:storage base-ctx) handler-fn-name)
         ;; OPTIONAL handlers (`_registry-ring-response` / `_mcp-ring-response`)
         ;; — resolved TOLERANTLY: keep only those whose fn-def exists (i.e.
         ;; the package is loaded). A dropped package simply contributes no id,
         ;; so its routes are never dispatched and `app` still boots.
         optional-handler-fn-ids (into []
                                       (keep #(resolve-handler-fn-id (:storage base-ctx) %))
                                       optional-handler-fn-names)]
     (when-not handler-fn-id
       (throw (ex-info (str "Handler fn-def not found: " handler-fn-name)
                       {:type :branch-router/handler-not-found
                        :name handler-fn-name})))
     (let [router (cond-> (->BranchRouter base-ctx default-branch-id
                                          (atom {}) handler-fn-id)
                    true (assoc :ref-cache (atom {})
                                :optional-handler-fn-ids optional-handler-fn-ids
                                :build-monitors (java.util.concurrent.ConcurrentHashMap.)
                                :idle-sweep (atom (now-ms)))
                    max-size (assoc :max-size max-size))]
       ;; Eager seed for the default branch: reuse the base-ctx (which
       ;; already has its compiled-registry primed by
       ;; `:exec/compiled-registry`) rather than building a fresh ctx
       ;; and re-compiling.
       (swap! (:handlers router)
              assoc default-branch-id
              {:ctx base-ctx
               :handler (compose-branch-handler base-ctx handler-fn-id
                                                optional-handler-fn-ids)
               :built-at (java.time.Instant/now)
               :last-used (now-ms)})
       ;; The default ctx was just built from the CURRENT graph — seed
       ;; the epoch watermark so the first request doesn't spuriously
       ;; heal over boot-sync bumps the build already absorbed.
       (router-epoch/seed-watermark! (vs/unwrap (:storage base-ctx)))
       ;; The default branch's ctx never goes through build-actual-entry!,
       ;; so schedule its diagnostics recompute here — this is the hook
       ;; that closes the ROADMAP restart caveat for the main branch.
       (recheck/schedule-user-fn-recheck! base-ctx default-branch-id)
       (log/info "Branch router ready" {:default-branch-id default-branch-id
                                        :handler-fn-name handler-fn-name
                                        :max-size (or max-size
                                                      default-max-cached-branches)})
       router))))


(def ^:dynamic *resolve-uncached-override*
  "Test seam: when bound to `(fn [router branch-ref] id-or-nil)`,
   `resolve-branch-id-uncached` delegates to it instead of the real
   base-storage reads. Exists for the ref-cache / TOCTOU suite in
   `branch-router-test`, which drives the UNCACHED internals with
   scripted read sequences (call counting, delete-between-reads)
   while keeping `resolve-branch-id`'s caching layer real.

   WHY a dynamic var and not `with-redefs` (same rationale as
   `*ctx-for-override*` above): a root rebind is process-global, so
   any test using it races every parallel test resolving a real
   branch ref — forcing `^:serial` pins. `binding` is per-thread;
   nil (the default) means production behaviour."
  nil)


(defn- ref-uuid
  "`branch-ref` parsed as a UUID, or nil. `UUID/fromString` is lenient —
   any case mix, and short groups (`1-1-1-1-1`) — so many distinct
   strings name the same id."
  [branch-ref]
  (try (java.util.UUID/fromString branch-ref)
       (catch IllegalArgumentException _ nil)))


(defn- resolve-branch-id-uncached
  [{:keys [base-ctx] :as router} branch-ref]
  (if-let [f *resolve-uncached-override*]
    (f router branch-ref)
    (let [base (vs/unwrap (:storage base-ctx))]
      (or (some->> (ref-uuid branch-ref) (sp/read-entity base :branch) :id)
          (:id (first (sp/query-entities base :branch {:name branch-ref})))))))


(defn- ref-cache-key
  "Where a resolution of `branch-ref` to `id` is cached. A ref that
   resolved BY ID is keyed by the canonical id (`[scope :id \"<uuid>\"]`),
   so every spelling of one branch id shares a slot — keyed by the raw
   ref, each case variant of a visible id was its own never-evicted
   entry (two DB reads apiece), a map grown by request input alone. A
   ref resolved BY NAME is keyed by the name itself, which only an
   existing branch can fill. `id` nil → the key a lookup tries."
  [scope branch-ref id]
  (if (and id (= id (ref-uuid branch-ref)))
    [scope :id (str id)]
    [scope branch-ref]))


(defn- cached-ref
  "The cached id for `branch-ref`, or nil. The by-id slot is tried first,
   mirroring `resolve-branch-id-uncached`'s id-before-name order."
  [cache scope branch-ref]
  (or (some->> (ref-uuid branch-ref) (ref-cache-key scope branch-ref) (get cache))
      (get cache (ref-cache-key scope branch-ref nil))))


(def ^:dynamic *resolve-branch-id-override*
  "Test seam: when bound to `(fn [router branch-ref] id-or-nil)`,
   `resolve-branch-id` delegates to it WHOLESALE — no default-branch
   short-circuit, no ref-cache. Exists for the dispatch suite's
   scripted `{ref → id}` resolution stubs. Same `with-redefs`-vs-
   `binding` rationale as `*ctx-for-override*` /
   `*resolve-uncached-override*` above."
  nil)


(defn resolve-branch-id
  "Translate a user-supplied branch ref (UUID string or branch name)
   to the branch-id in base storage. Returns the row's `:id`, or nil
   when the ref doesn't resolve. Nil ref returns the default-branch-id
   (handler-for then short-circuits to the seeded entry).

   Result is cached on the router's `:ref-cache` atom keyed by
   `[scope ref]` — or `[scope :id canonical-id]` for a ref that resolved
   by id, see `ref-cache-key` — (§4) — `:branch` is org-scoped, so org-A and org-B's
   same-named branches resolve to different ids and must not share a
   cache slot (else one would run in the other's branch ctx). A
   non-default branch name resolves once per process lifetime per
   (org, name). Invalidated by `invalidate!` / `invalidate-all!`.
   Misses (unresolved refs) are NOT cached so a typo never sticks.

   TOCTOU guard: `delete-branch!`'s `forget-ref-cache-for-branch!`
   sweep matches entries by value, so a sweep that runs between the
   uncached DB read and the `assoc` below sees no entry — the assoc
   would then cache a dead id forever. Re-reading AFTER the assoc
   closes the window: a delete that lands before the re-read is seen
   here (entry dropped, nil/new id returned); a delete that lands
   after it sees the now-present entry and sweeps it itself. Costs one
   extra read per cache MISS only (once per (org, ref) per process).

   Checks `*resolve-branch-id-override*` first (test seam — see its
   docstring)."
  [{:keys [default-branch-id ref-cache] :as router} branch-ref]
  (if-let [f *resolve-branch-id-override*]
    (f router branch-ref)
    (if (or (nil? branch-ref) (str/blank? branch-ref))
      default-branch-id
      (let [scope (current-scope)]
        (or (some-> ref-cache deref (cached-ref scope branch-ref))
            (when-let [id (resolve-branch-id-uncached router branch-ref)]
              (if-not ref-cache
                id
                (let [k (ref-cache-key scope branch-ref id)]
                  (swap! ref-cache assoc k id)
                  (let [id' (resolve-branch-id-uncached router branch-ref)]
                    (if (= id' id)
                      id
                      (do (swap! ref-cache dissoc k)
                          id')))))))))))


(defn extract-branch-ref
  "Delegates to `graphden.system.branch-router.request/extract-branch-ref`. Kept here for the private
   graphden-tenancy addon (`graphden.tenancy.preview` reads the branch ref
   through `br/extract-branch-ref`; listed in tools/open-core-seam.edn) —
   in-repo callers use the request namespace directly."
  [request]
  (br-req/extract-branch-ref request))


(declare dispatch*)


(defn dispatch
  "Top-level Ring middleware. Reads `extract-branch-ref` off the
   request, resolves the branch, and delegates to the per-branch
   handler. Unknown branch refs surface a 400 rather than silently
   misrouting. `/livez` short-circuits FIRST as a registry-independent
   liveness probe (see `br-req/liveness-path`).

   Binds `epoch/*request-bump-log*` for the request when the caller
   has not (the `:http-server` adapter does; a test or an embedded
   caller driving `dispatch` directly did not). Without the log a
   write's epoch bumps are never NOTED — `note-graph-epoch-validated!`
   drains an unbound log into nothing — so every such write aged into
   an \"aborted\" epoch 45 s later and a heal dropped every cached
   branch ctx mid-suite: the background rebuilds behind two flakes of
   (`branch-invalidation-test`,
   `rich-types-registry-branch-scope-test`)."
  [router request]
  (cond
    (= br-req/liveness-path (:uri request)) br-req/liveness-response
    epoch/*request-bump-log* (dispatch* router request)
    :else (binding [epoch/*request-bump-log* (atom [])]
            (dispatch* router request))))


(defn- dispatch*
  [router request]
  (let [base-ctx (:base-ctx router)
        ;; Realize a streaming body up front, ONCE for every consumer below —
        ;; the fleet seam, the FaaS app-router, the tenancy control-plane
        ;; router AND the editor/API chain all see a String body. The app
        ;; package's own `:realize-request-body` step stays (idempotent: a
        ;; String passes through), but it only covered the app chain — a
        ;; form-POST to a tenancy route (signup / login / org provisioning)
        ;; reached `parse-form-body` as an unread InputStream and silently
        ;; parsed to `{}` (live cloud hit this: `POST /api/orgs name=…`
        ;; created an org named "").
        request (let [b (:body request)]
                  (if (instance? java.io.InputStream b)
                    (assoc request :body (slurp (java.io.InputStreamReader. b "UTF-8")))
                    request))
        ;; Fleet control-plane seam (docs/FLEET_RFC.md §6.3): the internal
        ;; cell load/evict command (`POST /internal/fleet/cell/...`). Checked
        ;; FIRST — it's infra, org-agnostic, internal-token-gated, and never a
        ;; tenant path. Returns a response for a matching command, nil for
        ;; anything else (→ falls through). Absent unless this pod is a fleet
        ;; member (`GRAPHDEN_EXECUTOR_ID` set).
        fleet-command (:fleet-command base-ctx)
        fleet-resp (when fleet-command (fleet-command base-ctx request))
        ;; App-router seam (§3.4 FaaS): a request to a TENANT's subdomain is
        ;; the tenant's APP — served by that org's handler fn (org-scoped +
        ;; effect-gated), NOT the editor/API. The app-router returns a
        ;; response for such requests, or nil for an apex/platform request,
        ;; which then falls through to the editor/API flow below. Absent
        ;; (core / single-tenant) → straight to editor/API.
        app-router (:app-router base-ctx)
        app-resp (or fleet-resp
                     (when app-router (app-router base-ctx request)))]
    (or app-resp
        ;; Branch resolution runs INSIDE the request-scope (§4): `:branch` is
        ;; org-scoped, so `*current-org*` must be bound when resolve-branch-id
        ;; reads it — otherwise a tenant's own branch is invisible at resolution
        ;; (→ a spurious 400) and the org cache key would be wrong. So the whole
        ;; resolve → handler chain is the request-scope's thunk. The branch ctx
        ;; itself stays org-agnostic (Design B) — keyed by branch-id alone.
        (let [request-scope (:request-scope base-ctx)
              run (fn []
                    ;; Arm merge-only branch protection for the whole request
                    ;; write path (editor CRUD + bundle import both flow
                    ;; through here). Off outside a request — boot's package
                    ;; sync writes to main through VersionedStorage but never
                    ;; through dispatch, so it is never gated; merge writes
                    ;; go straight to base-storage version tables, so they are
                    ;; exempt structurally. See `vs/*enforce-require-merge?*`.
                    (binding [vs/*enforce-require-merge?* true]
                      ;; Route-collection seam (docs/TENANCY_SEAM.md
                      ;; § Route-collection seam): consult
                      ;; every installed fall-through router FIRST, INSIDE the
                      ;; request-scope so `*current-org*` is bound — the tenancy
                      ;; org-admin panels (grants/users/…) AND the optional
                      ;; registry (`/api/packages/installed` reads org/branch-
                      ;; scoped pins) need the same org binding branch resolution
                      ;; does. A matched path returns a response; no match (or an
                      ;; empty collection → no optional package installed) falls
                      ;; through to the branch-resolution chain. Branch-agnostic
                      ;; by design: the branch ref is irrelevant to these paths.
                      (or (rc/dispatch-first request)
                          (let [branch-ref (br-req/extract-branch-ref request)
                                branch-id (resolve-branch-id router branch-ref)]
                            (cond
                              (or (nil? branch-ref) (some? branch-id))
                              ;; Bind the target branch's rich-types slice for
                              ;; the WHOLE request: type-checks on writes,
                              ;; /api/types reads and effect-gate lookups all
                              ;; land on the branch's own view. Falls back to
                              ;; the ambient override (test isolation) for
                              ;; ctxs built before this tagging existed.
                              ;; ONE entry lookup — handler and ctx must come
                              ;; from the same generation (an invalidation
                              ;; between two lookups could pair an old handler
                              ;; with a new ctx), and validate-graph-epoch!
                              ;; need not run twice per request.
                              (let [entry (entry-for router branch-id)]
                                (recheck/call-with-ctx-slices (:ctx entry)
                                                              #((:handler entry) request)))

                              ;; A PAGE load naming a branch that is gone (merged
                              ;; and deleted elsewhere, or by this user's own tour
                              ;; cleanup in another tab) used to answer 400 — and
                              ;; since the 400 replaced the HTML, the editor never
                              ;; booted: no scripts, no explanation, nothing to
                              ;; click. Send the navigation to the same URL without
                              ;; the stale `?branch=` instead; the editor loads on
                              ;; the default branch and the user is back in
                              ;; business. API/XHR callers still get the 400 below,
                              ;; which is what they can act on.
                              (br-req/document-navigation? request)
                              {:status 302
                               :headers {"Location" (br-req/uri-without-branch request)
                                         "Cache-Control" "no-store"}
                               :body ""}

                              :else
                              {:status 400
                               :headers {"Content-Type" "application/json"}
                               ;; JSON-encode — `branch-ref` is user-controlled
                               ;; (X-Graphden-Branch header / ?branch=), so a raw
                               ;; string-concat let a `"` inject arbitrary keys
                               ;; into the response envelope.
                               :body (json/generate-string
                                       {:ok false
                                        :error (str "Unknown branch: " branch-ref)})})))))]
          ;; One merge-record memo per request: every resolved read in the
          ;; request shares the chain's `branch-merge` rows (`vres/*merges-memo*`).
          (vres/call-with-merges-memo
            (fn []
              (if request-scope
                (request-scope base-ctx request run)
                (run))))))))


;; =============================================================================
;; Process-wide singleton — the only knob `web.ring-adapter/branch-routing-wrap`
;; base-fn impl needs to reach the router from inside a compiled fn-graph.
;; Mirrors the pattern used by `graphden.services.reconciler` for its
;; `running` atom — system-level state that doesn't fit cleanly inside
;; ctx (because the ctx is closed in at compile time, before the
;; router exists).
;; =============================================================================

(defonce ^{:doc "Active BranchRouter for this JVM — the process-global.
                 Set by the `:exec/branch-router` init-key on startup,
                 cleared on halt. `nil` outside a running system — base-fn
                 impls short-circuit to single-branch behaviour so tests
                 don't have to set this up.

                 Reached only through `active-router-atom` so the kaocha
                 parallel plugin can isolate it per NS-thread via
                 `*active-router-override*`."}
  active-router-global
  (atom nil))


(def ^:dynamic *active-router-override*
  "Per-NS-thread override atom for parallel-test isolation. `nil` = use
   the process-global `active-router-global`. Bound to a fresh `(atom nil)`
   per NS-thread by `kaocha.plugin.parallel`: integration tests
   (`smoke-pass`, ...) `set-active-router!` during their run, and without
   this isolation a sibling NS-thread's merge handler would read the wrong
   router off the shared global and invalidate the wrong ExecutionContext
   — an intermittent flake (e.g. `branches-lifecycle-test`)."
  nil)


(defn- active-router-atom
  []
  (or *active-router-override* active-router-global))


(defn active-router-isolation-seed
  "Parallel-plugin seeder: each isolated NS-thread starts with NO active
   router — the bound atom holds `nil`, not the plugin's default `{}`."
  []
  nil)


(defn set-active-router!
  [router]
  (reset! (active-router-atom) router))


(defn clear-active-router!
  []
  (reset! (active-router-atom) nil))


(defn current-router
  []
  @(active-router-atom))

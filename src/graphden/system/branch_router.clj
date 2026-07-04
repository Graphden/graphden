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
   picks up a fresh rebuild)."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.crud.fn-execution.lookup :as fn-lookup]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as ctx]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.tenancy-router :as tr]
    [graphden.versioning.storage.core :as vs]))


;; =============================================================================
;; Header / query extraction
;; =============================================================================

(def header-name
  "Lowercased Ring-style header key the dispatcher reads."
  "x-graphden-branch")


(def query-param
  "URL query-string key the dispatcher reads when no header is set."
  "branch")


(defn- parse-branch-from-query
  [query-string]
  (when (and query-string (not (str/blank? query-string)))
    (some (fn [pair]
            (let [[k v] (str/split pair #"=" 2)]
              (when (= query-param (java.net.URLDecoder/decode k "UTF-8"))
                (some-> v (java.net.URLDecoder/decode "UTF-8")))))
          (str/split query-string #"&"))))


(defn extract-branch-ref
  "Returns the branch ref the request asks for, or nil for default.
   Header wins over query param — explicit programmatic API beats
   shareable-URL convenience. Empty / blank values count as nil."
  [request]
  (let [hdr (get-in request [:headers header-name])
        qs (parse-branch-from-query (:query-string request))
        chosen (or (some-> hdr (str/trim) (#(when-not (str/blank? %) %)))
                   (some-> qs (str/trim) (#(when-not (str/blank? %) %))))]
    chosen))


;; =============================================================================
;; Per-branch ctx + Ring callable cache
;; =============================================================================

(def ^:private default-max-cached-branches
  "Soft cap on the number of per-branch ctx entries kept warm. The
   LRU evicts the least-recently-used non-default entry when adding
   would exceed this. 16 covers a dev workflow with a handful of
   active feature branches comfortably; production multi-tenant
   would tune this through the `:max-size` arg to `create-router`."
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
                                 :allowed-effects (:allowed-effects base-ctx)})
      ;; Privileged structural-read storage for THIS branch (§4 Design B): the
      ;; raw PG re-wrapped at branch-id, so `rebuild!` compiles every org's fns
      ;; org-agnostically (isolation stays on the org-scoped `:storage` above at
      ;; runtime). Absent in single-tenant → compile reads fall back to :storage.
      (:pg-storage base-ctx)
      (assoc :pg-storage (:pg-storage base-ctx)
             :compile-storage (vs/->VersionedStorage (:pg-storage base-ctx) branch-id))
      ;; Inherit the off-record auth seam so per-branch execution
      ;; authenticates through the same provider as the base context.
      (:auth-provider base-ctx) (assoc :auth-provider (:auth-provider base-ctx))
      ;; Inherit the per-namespace execute guard (§4.2) — branch execution
      ;; must enforce the same grants as the base context.
      (:execute-guard base-ctx) (assoc :execute-guard (:execute-guard base-ctx))
      ;; Inherit the self-serve deploy seam (§3.4 4b) — the
      ;; `:invoke-set-org-handler` base-fn runs in the per-branch handler ctx.
      (:set-org-handler base-ctx) (assoc :set-org-handler (:set-org-handler base-ctx))
      ;; Inherit the self-serve DNS-verify seam (§3.4 #2) — the
      ;; `:invoke-verify-domain` base-fn runs in the per-branch handler ctx.
      (:verify-domain base-ctx) (assoc :verify-domain (:verify-domain base-ctx))
      ;; Inherit the user-model seam (§4.1) — `:invoke-login` / `:invoke-create-user`
      ;; run in the per-branch handler ctx.
      (:user-ops base-ctx) (assoc :user-ops (:user-ops base-ctx)))))


(defn- ring-callable-for-ctx
  "Returns a `(fn [request])` callable that delegates to the compiled
   closure for `handler-fn-id` in `branch-ctx`. The registry is
   re-read on every invocation so the latest delta-recompile result
   is visible without rebuilding the callable."
  [branch-ctx handler-fn-id]
  (fn [request]
    (let [reg (cr/registry branch-ctx)
          closure (get reg handler-fn-id)]
      (when-not closure
        (throw (ex-info "Branch handler closure missing"
                        {:type :execution-error/fn-not-found
                         :fn-id handler-fn-id
                         :branch-id (vs/current-branch-id
                                      (:storage branch-ctx))})))
      ;; compile-eager closure signature: `(fn [free-args ctx])`.
      (closure {:request request} branch-ctx))))


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
   it past, drop the oldest non-default entry. The default-branch
   entry is pinned (it's seeded eagerly and represents the hottest
   path). `new-id` is also excluded from consideration — if the
   caller is replacing an existing entry, no eviction is needed."
  [m max-size default-branch-id new-id]
  (let [evictable (-> m (dissoc default-branch-id new-id))]
    (if (and (>= (count m) max-size)
             (not (contains? m new-id))
             (seq evictable))
      (let [oldest-id (->> evictable
                           (sort-by (fn [[_ v]] (or (:last-used v) 0)))
                           ffirst)]
        (dissoc m oldest-id))
      m)))


(defn- branch-monitor
  "Per-branch reentrant lock used to dedupe concurrent build-and-
   cache! callers for the same cold branch. Stored on the router as a
   ConcurrentHashMap so distinct branch-ids never block each other —
   only same-branch contenders serialize. The first arrival builds,
   the rest acquire the lock, double-check, and find the entry the
   first arrival wrote.

   A `ReentrantLock`, NOT `locking`/`synchronized`: the guarded body
   runs `cr/rebuild!` (a full compile, seconds) on a cold-branch miss,
   and a virtual thread blocked on a monitor pins its carrier (JDK 21)
   — same reason as `compile-runtime/call-with-invalidation-lock`."
  [router branch-id]
  (when-let [monitors (:build-monitors router)]
    (java.util.concurrent.ConcurrentHashMap/.computeIfAbsent
      monitors
      branch-id
      (reify java.util.function.Function
        (apply [_ _] (java.util.concurrent.locks.ReentrantLock.))))))


(defn- branch-has-own-content?
  "True iff `branch-id` has at least one own version row OR is the
   target of at least one branch-merge. When neither is the case the
   branch's resolved view is identical to its base, so we can skip
   the full compile and reuse the base ctx's templates.

   Each probe runs with `:limit 1` so the version tables don't
   marshal thousands of rows back to Clojure just to check
   existence — `or` short-circuits on the first hit anyway, but
   without the limit a single hit on `:fn-version` for a branch
   with 10k own version rows would return all 10k."
  [base-storage branch-id]
  (let [any-row? (fn [entity where]
                   (boolean (seq (sp/query-entities base-storage entity where {:limit 1}))))]
    (or (any-row? :fn-version {:branch-id branch-id})
        (any-row? :fn-slot-version {:branch-id branch-id})
        (any-row? :binding-version {:branch-id branch-id})
        (any-row? :binding-list-item-version {:branch-id branch-id})
        (any-row? :branch-merge {:target-branch-id branch-id}))))


(defn- build-actual-entry!
  "Lazy-compile per-branch ctx + Ring callable. Fast path: when the
   branch is graph-identical to its base (no own version rows, no
   merge edges) we copy the base ctx's compiled registry directly
   into the branch's ctx — compile-eager closures are ctx-
   independent, so the same `{fn-id → closure}` map serves both.
   Slow path: full `rebuild!` for the branch.

   No atom writes; caller installs the result."
  [{:keys [base-ctx handler-fn-id]} branch-id]
  (let [branch-ctx (build-branch-ctx base-ctx branch-id)
        base-storage (vs/unwrap (:storage base-ctx))
        own-content? (branch-has-own-content? base-storage branch-id)
        base-registry (some-> (:compiled-registry base-ctx) deref)]
    (if (and (not own-content?) base-registry)
      ;; Fast path: graph identical → reuse base registry.
      (cr/instantiate-from-templates! base-ctx branch-ctx)
      ;; Slow path: branch differs from base → full compile.
      (cr/rebuild! branch-ctx))
    {:ctx branch-ctx
     :handler (ring-callable-for-ctx branch-ctx handler-fn-id)
     :built-at (java.time.Instant/now)
     :last-used (now-ms)}))


(defn- build-and-cache!
  "Build the per-branch ctx + Ring callable and cache it. Cold-branch
   thundering-herd safe — concurrent callers for the same branch-id
   serialize on `branch-monitor`, double-check inside the lock, and
   share the single rebuild!. LRU evicts the oldest non-default
   entry if the cache is at `:max-size`."
  [{:keys [handlers default-branch-id] :as router} branch-id]
  (let [max-size (or (:max-size router) default-max-cached-branches)]
    (if-let [monitor (branch-monitor router branch-id)]
      (do
        (java.util.concurrent.locks.ReentrantLock/.lock monitor)
        (try
          (or (get @handlers branch-id)
              (let [entry (build-actual-entry! router branch-id)]
                (swap! handlers
                       (fn [m]
                         (-> m
                             (evict-lru-if-full max-size default-branch-id branch-id)
                             (assoc branch-id entry))))
                entry))
          (finally (java.util.concurrent.locks.ReentrantLock/.unlock monitor))))
      ;; No monitor map → test path with a hand-constructed router.
      ;; Best-effort: just swap, accepting the rare duplicate build.
      (let [entry (build-actual-entry! router branch-id)]
        (swap! handlers
               (fn [m]
                 (-> m
                     (evict-lru-if-full max-size default-branch-id branch-id)
                     (assoc branch-id entry))))
        entry))))


(defn handler-for
  "Return the Ring callable for `branch-id`, building lazily on miss.
   Falls back to the cached default-branch entry when `branch-id` is
   nil or matches the default. Records the access via `touch!` on
   cache hits so the LRU eviction sees the freshest order."
  [{:keys [default-branch-id handlers] :as router} branch-id]
  (let [effective (or branch-id default-branch-id)
        cached (get @handlers effective)]
    (when (and cached (not= effective default-branch-id))
      (touch! handlers effective))
    (:handler (or cached (build-and-cache! router effective)))))


(defn ctx-for
  "Return the per-branch ExecutionContext for `branch-id`, building
   lazily on miss. Useful for CRUD impls that need to call
   `invalidate-graph-cache!` after a write."
  [{:keys [default-branch-id handlers] :as router} branch-id]
  (let [effective (or branch-id default-branch-id)
        cached (get @handlers effective)]
    (when (and cached (not= effective default-branch-id))
      (touch! handlers effective))
    (:ctx (or cached (build-and-cache! router effective)))))


(defn- current-scope
  "The tenant scope for branch resolution — the addon's `*current-org*` when the
   tenancy ns is loaded, so a tenant's branch ref resolves + caches PER ORG and
   never returns another org's same-named branch from the cache. nil in
   single-tenant / core. `resolve` (not requiring-resolve) so core never loads
   the addon. The resolution read is already org-scoped via OrgScoped; this only
   keys the cache to match."
  []
  (some-> (resolve 'graphden.tenancy.context/*current-org*) deref))


(defn- forget-ref-cache-for-branch!
  "Drop every `ref → id` entry that points at `branch-id`. Called from
   `invalidate!` so a delete-branch! followed by a re-create with the
   same name doesn't surface a stale id. Keys are `[scope ref]`; matching is
   by value (branch-id) so it sweeps every org's entry for the branch."
  [router branch-id]
  (when-let [ref-cache (:ref-cache router)]
    (swap! ref-cache
           (fn [m]
             (reduce-kv (fn [acc k v]
                          (if (= v branch-id) acc (assoc acc k v)))
                        {}
                        m)))))


(defn invalidate!
  "Drop the cached entry for one branch + every ref → id mapping that
   points at it. Called after a write — the next request rebuilds.
   Mainly used after `delete-branch!` so the ctx doesn't outlive its
   branch row."
  [{:keys [handlers build-monitors] :as router} branch-id]
  (swap! handlers dissoc branch-id)
  (forget-ref-cache-for-branch! router branch-id)
  (when build-monitors
    (java.util.concurrent.ConcurrentHashMap/.remove build-monitors branch-id)))


(defn invalidate-all!
  "Drop every cached per-branch entry + the entire ref-cache. Used by
   schema-migration paths that change the executor's shape under all
   branches."
  [{:keys [handlers ref-cache build-monitors]}]
  (reset! handlers {})
  (when ref-cache (reset! ref-cache {}))
  (when build-monitors
    (java.util.concurrent.ConcurrentHashMap/.clear build-monitors)))


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
  ([base-ctx handler-fn-name {:keys [max-size]}]
   (let [default-branch-id (vs/current-branch-id (:storage base-ctx))
         handler-fn-id (resolve-handler-fn-id (:storage base-ctx) handler-fn-name)]
     (when-not handler-fn-id
       (throw (ex-info (str "Handler fn-def not found: " handler-fn-name)
                       {:type :branch-router/handler-not-found
                        :name handler-fn-name})))
     (let [router (cond-> (->BranchRouter base-ctx default-branch-id
                                          (atom {}) handler-fn-id)
                    true (assoc :ref-cache (atom {})
                                :build-monitors (java.util.concurrent.ConcurrentHashMap.))
                    max-size (assoc :max-size max-size))]
       ;; Eager seed for the default branch: reuse the base-ctx (which
       ;; already has its compiled-registry primed by
       ;; `:exec/compiled-registry`) rather than building a fresh ctx
       ;; and re-compiling.
       (swap! (:handlers router)
              assoc default-branch-id
              {:ctx base-ctx
               :handler (ring-callable-for-ctx base-ctx handler-fn-id)
               :built-at (java.time.Instant/now)
               :last-used (now-ms)})
       (log/info "Branch router ready" {:default-branch-id default-branch-id
                                        :handler-fn-name handler-fn-name
                                        :max-size (or max-size
                                                      default-max-cached-branches)})
       router))))


(defn- resolve-branch-id-uncached
  [{:keys [base-ctx]} branch-ref]
  (let [base (vs/unwrap (:storage base-ctx))]
    (or (try (some->> branch-ref java.util.UUID/fromString
                      (sp/read-entity base :branch)
                      :id)
             (catch IllegalArgumentException _ nil))
        (:id (first (sp/query-entities base :branch {:name branch-ref}))))))


(defn resolve-branch-id
  "Translate a user-supplied branch ref (UUID string or branch name)
   to the branch-id in base storage. Returns the row's `:id`, or nil
   when the ref doesn't resolve. Nil ref returns the default-branch-id
   (handler-for then short-circuits to the seeded entry).

   Result is cached on the router's `:ref-cache` atom keyed by
   `[scope ref]` (§4) — `:branch` is org-scoped, so org-A and org-B's
   same-named branches resolve to different ids and must not share a
   cache slot (else one would run in the other's branch ctx). A
   non-default branch name resolves once per process lifetime per
   (org, name). Invalidated by `invalidate!` / `invalidate-all!`.
   Misses (unresolved refs) are NOT cached so a typo never sticks."
  [{:keys [default-branch-id ref-cache] :as router} branch-ref]
  (if (or (nil? branch-ref) (str/blank? branch-ref))
    default-branch-id
    (let [k [(current-scope) branch-ref]]
      (if (and ref-cache (contains? @ref-cache k))
        (get @ref-cache k)
        (when-let [id (resolve-branch-id-uncached router branch-ref)]
          (when ref-cache (swap! ref-cache assoc k id))
          id)))))


(defn dispatch
  "Top-level Ring middleware. Reads `extract-branch-ref` off the
   request, resolves the branch, and delegates to the per-branch
   handler. Unknown branch refs surface a 400 rather than silently
   misrouting."
  [router request]
  (let [base-ctx (:base-ctx router)
        ;; App-router seam (§3.4 FaaS): a request to a TENANT's subdomain is
        ;; the tenant's APP — served by that org's handler fn (org-scoped +
        ;; effect-gated), NOT the editor/API. The app-router returns a
        ;; response for such requests, or nil for an apex/platform request,
        ;; which then falls through to the editor/API flow below. Absent
        ;; (core / single-tenant) → straight to editor/API.
        app-router (:app-router base-ctx)
        app-resp (when app-router (app-router base-ctx request))]
    (or app-resp
        ;; Branch resolution runs INSIDE the request-scope (§4): `:branch` is
        ;; org-scoped, so `*current-org*` must be bound when resolve-branch-id
        ;; reads it — otherwise a tenant's own branch is invisible at resolution
        ;; (→ a spurious 400) and the org cache key would be wrong. So the whole
        ;; resolve → handler chain is the request-scope's thunk. The branch ctx
        ;; itself stays org-agnostic (Design B) — keyed by branch-id alone.
        (let [request-scope (:request-scope base-ctx)
              run (fn []
                    ;; Route-collection seam (PLATFORM_PLAN §2.1 / §6): consult
                    ;; the tenancy control-plane router FIRST, INSIDE the
                    ;; request-scope so `*current-org*` is bound — the org-admin
                    ;; panels (grants/users/…) read org-scoped entities, so they
                    ;; need the same org binding branch resolution does. A
                    ;; matched control-plane path returns a response; no match
                    ;; (or no addon installed → nil router) falls through to the
                    ;; branch-resolution chain. Branch-agnostic by design: the
                    ;; branch ref is irrelevant to org administration.
                    (or (tr/dispatch (tr/current-router) request)
                        (let [branch-ref (extract-branch-ref request)
                              branch-id (resolve-branch-id router branch-ref)]
                          (if (and (some? branch-ref) (nil? branch-id))
                            {:status 400
                             :headers {"Content-Type" "application/json"}
                             :body (str "{\"ok\":false,\"error\":\"Unknown branch: " branch-ref "\"}")}
                            ((handler-for router branch-id) request)))))]
          (if request-scope
            (request-scope base-ctx request run)
            (run))))))


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

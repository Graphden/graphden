(ns graphden.executor.compile-runtime
  "Public entry points for the compiled executor.

   Bridges between the compile-time registry (produced by
   `graphden.executor.compile-eager/compile-all`) and the executor's
   public API (`execute`, `make-*-arg-callable`).

   Since the legacy queue was retired, this namespace IS the executor —
   `exec/` public API delegates here. The registry is rebuilt on demand
   when missing (test paths that create contexts directly without going
   through the system-level `:exec/compiled-registry` init-key)."
  (:require
    [clojure.set :as set]
    [clojure.tools.logging :as log]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.executor.compile-eager :as ce]
    [graphden.executor.compile.deps :as deps]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.compile.renames :as r]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.core :as types]))


;; =============================================================================
;; Registry lifecycle
;; =============================================================================

(defn- read-graph
  "Read the slot/fn-slot/binding model entities from storage. Bundled
   so `rebuild!` and the on-demand free-arg resolver share the same
   query shape."
  [storage]
  {:fns        (sp/query-entities storage :fn {})
   :slots      (sp/query-entities storage :slot {})
   :fn-slots   (sp/query-entities storage :fn-slot {})
   :bindings   (sp/query-entities storage :binding {})
   :list-items (sp/query-entities storage :binding-list-item {})})


(defn- type-row-role
  "Classify a fn-row into one of the type roles (per the schema role
   table) — `:record`, `:refinement`, `:list`, `:union`, `:variant`,
   `:fn-type`, `:base-fn`, `:composed`, or `:primitive`. Used by
   `register-type-aliases-from-db!` to pick the right alias body shape.

   Unions, variants, AND structural fn-types share the storage shape
   — all three stash their payload in `:constraint`
   (`[:union T1 T2 …]` / `[:variant tag1 T1 tag2 T2 …]` /
   `[:fn args ret]`). The leading keyword discriminates.

   `:return-type-fn-id` is the base-fn signal; a real type-row has
   none. A base-fn with slots (e.g. `:parse-fn-from-form`,
   `:create-entity`) is thus never misclassified as a `:record`
   type-row."
  [fn-row has-slots?]
  (cond
    (seq (:parent-ids fn-row))     :composed
    (some? (:return-type-fn-id fn-row)) :base-fn
    (some? (:base-fn-id fn-row))   :refinement
    (some? (:element-fn-id fn-row)) :list
    (and (vector? (:constraint fn-row))
         (= :union (first (:constraint fn-row)))) :union
    (and (vector? (:constraint fn-row))
         (= :variant (first (:constraint fn-row)))) :variant
    (and (vector? (:constraint fn-row))
         (= :fn (first (:constraint fn-row)))) :fn-type
    has-slots?                     :record
    :else                          :primitive))


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
                    role (type-row-role f (seq own-slots))
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
                (when body {:nm nm :body body :org (:org-id f)}))))
          fns)
        {:keys [failed]} (types/register-type-aliases-batch (map (juxt :nm :body) candidates))
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
        graph (read-graph storage)]
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
  (let [snap (or (requiring-resolve
                   'graphden.executor.registry.core/rich-types-snapshot)
                 (throw (ex-info
                          "rich-types-snapshot missing — namespace rename?"
                          {:type :compile/missing-symbol
                           :symbol 'graphden.executor.registry.core/rich-types-snapshot})))
        rich (snap)
        fresh-cats #{:time :random}
        fresh-ids
        (into #{}
              (keep (fn [f]
                      (when-let [nm (:name f)]
                        (let [eff (:effects (get rich (keyword nm)))]
                          (when (and eff (some fresh-cats eff))
                            (:id f))))))
              fns)]
    (ce/set-always-fresh-fn-ids! fresh-ids)))


(defn rebuild!
  "Rebuild the compiled registry in `ctx` from whatever the slot/
   binding tables currently hold. Also primes `:graph-cache` and
   `:compile-deps` so read-heavy consumers stay in sync. Call at
   startup and on full invalidation.

   Runs under the context's `:invalidation-lock` so the
   read-graph → compute → prime-multi-atom sequence stays atomic
   relative to concurrent `invalidate-graph-cache!` callers."
  [ctx]
  (let [body (fn []
               (let [storage (compile-storage ctx)
                     graph (read-graph storage)
                     _ (register-type-aliases-from-db! graph)
                     base-fns (:base-fns ctx)
                     lookups (assoc (l/cached-build-lookups graph)
                                    :base-fns base-fns)
                     _ (prime-always-fresh! (:fns graph))
                     compiled (ce/compile-all lookups)]
                 (reset! (:compiled-registry ctx) compiled)
                 (prime-graph-cache! ctx graph)
                 (prime-compile-deps! ctx graph)
                 compiled))]
    (if-let [lock (:invalidation-lock ctx)]
      (locking lock (body))
      (body))))


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
      (rebuild! ctx)

      :else
      (let [storage (compile-storage ctx)
            graph (read-graph storage)
            _ (register-type-aliases-from-db! graph)
            base-fns (:base-fns ctx)
            fns-map (if (map? (:fns graph))
                      (:fns graph)
                      (into {} (map (juxt :id identity)) (:fns graph)))
            _ (prime-always-fresh! (vals fns-map))
            blast (deps/transitive-blast reverse-deps changed-fn-ids)
            lookups (assoc (l/cached-build-lookups graph) :base-fns base-fns)]
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


(defn registry
  "Return the current compiled registry from `ctx`, rebuilding on-demand
   when missing. Tests that skip the `:exec/compiled-registry` init-key
   still get a working executor via this fallback — the cost is a single
   compile pass on first execution."
  [ctx]
  (when-let [holder (:compiled-registry ctx)]
    (or @holder (rebuild! ctx))))


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
  (let [graph (or (some-> (:graph-cache ctx) deref)
                  (read-graph (:storage ctx)))]
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


(def ^:dynamic *allowed-effects*
  "Set of effect categories the current execution is permitted to
   perform, or `nil` (the default) for UNRESTRICTED. Bound by
   `execute` from the context's `:allowed-effects` (PLATFORM_PLAN §5
   cloud sandbox). When a non-nil set is in effect, `record-effect!`
   throws `:execution/forbidden-effect` for any category outside it —
   the runtime half of the effect gate (the build-time registry filter
   is the belt-and-suspenders second layer)."
  nil)


(def ^:dynamic *execute-authorized*
  "True once the ctx's `:execute-guard` (the tenancy addon's per-namespace
   execute check, PLATFORM_PLAN §4.2) has run for THIS top-level execute, so
   the recursive sub-fn `execute` calls don't re-run it. Default false →
   the next top-level `execute` consults the guard."
  false)


(def cloud-forbidden-effects
  "The security-sensitive effect categories a cloud sandbox must forbid
   (PLATFORM_PLAN §5). A cloud org context should set its
   `:allowed-effects` to the full effect vocabulary MINUS this set.

   Verified by the effect-gate coverage audit: every base-fn that
   performs one of these calls `record-effect!`, so excluding them from
   `:allowed-effects` actually blocks the operation (no silent bypass).

   - `:env`     — environment variables / system properties
   - `:io`      — file / classpath reads
   - `:network` — outbound HTTP / sockets
   - `:process` — thread / server / execution lifecycle control

   The safe remainder — `:db`, `:time`, `:state`, `:random` — stays
   allowed (internal infrastructure depends on it). New base-fns must
   keep this contract: any security-sensitive primitive MUST
   `record-effect!`, or it becomes a sandbox hole."
  #{:env :io :network :process})


(def known-effects
  "Every effect category the package layer currently records (the
   `record-effect!` vocabulary). The registry of safe-vs-sensitive lives
   in `cloud-forbidden-effects`; this is the full set so the safe
   complement can be derived. Register a new SAFE category here; a new
   SENSITIVE one goes in BOTH this set AND `cloud-forbidden-effects`."
  #{:db :env :io :network :process :state :time :random})


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


(defn record-effect!
  "Record that the calling impl is about to perform an effect of
   `category`. The vocabulary in use across the package layer is
   `:db`, `:env`, `:io`, `:network`, `:process`, `:state`, `:time`
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
  ;; Per-namespace execute gate (§4.2): consult the ctx's `:execute-guard`
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

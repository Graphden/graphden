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

   `:return-type-fn-id` is the ground-truth signal for `:base-fn` —
   `:impl-hash` is sometimes NULL in the DB for defbase-declared base
   fns (sync-side bug, separate effort), so leaning on `impl-hash`
   alone misclassifies a base-fn with slots (e.g. `:parse-fn-from-
   form`, `:create-entity`) as a `:record` type-row and the alias
   loader trips a `body not well-formed` warning for each. A real
   type-row has no `:return-type-fn-id`."
  [fn-row has-slots?]
  (cond
    (seq (:parent-ids fn-row))     :composed
    (or (some? (:impl-hash fn-row))
        (some? (:return-type-fn-id fn-row))) :base-fn
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
                (when body [nm body]))))
          fns)
        {:keys [failed]} (types/register-type-aliases-batch candidates)]
    (doseq [{:keys [nm reason]} failed]
      (log/warn (str "register-type-aliases-from-db!: skipped " (pr-str nm)
                     " — " reason)))))


(defn refresh-type-registries-from-storage!
  "Light-weight equivalent of `rebuild!` that ONLY refreshes type
   registries (aliases + rich-types snapshot of current DB type-rows)
   — does NOT recompile fn closures.

   CRUD impls call this AFTER mutation so editor reads (`/api/types`,
   pickers) see the new/modified types immediately. The compiled
   closures are also stale at that point but they're rebuilt
   on-demand by the next `execute` (via `registry`'s lazy fallback)."
  [ctx]
  (let [storage (:storage ctx)
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
  "Capture the freshly-built reverse-dependency index on `ctx`. Used
   by `delta-recompile!` to compute the blast radius of subsequent
   mutations."
  [ctx graph]
  (when-let [holder (:compile-deps ctx)]
    (reset! holder (deps/build-reverse-deps graph))))


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
               (let [storage (:storage ctx)
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
        reverse-deps (some-> (:compile-deps ctx) deref)]
    (cond
      (or (nil? holder) (nil? @holder) (nil? reverse-deps) (empty? changed-fn-ids))
      (rebuild! ctx)

      :else
      (let [storage (:storage ctx)
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
        (prime-compile-deps! ctx graph)
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
  (let [storage (:storage ctx)
        graph (read-graph storage)
        lookups (assoc (l/cached-build-lookups graph)
                       :base-fns (:base-fns ctx))]
    (vec (r/deep-free-ext-names fn-id lookups))))


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


(defn record-effect!
  "Record that the calling impl is about to perform an effect of
   `category` (one of :env, :db, :io, :network, :time, :random — same
   vocabulary as rich-type-of `:effects`). No-op outside an execution
   trace context."
  [category]
  (when *effect-trace*
    (swap! *effect-trace* conj category)))


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
  (if (fn? fn-id)
    (let [args (or named-args {})]
      (if (= 1 (count args))
        (fn-id (first (vals args)))
        (fn-id args)))
    (let [reg (registry ctx)
          closure (get reg fn-id)]
      (when-not closure
        (throw (ex-info (str "Function not found: " fn-id)
                        {:type :execution-error/fn-not-found
                         :fn-id fn-id})))
      ;; compile-eager closure signature: `(fn [free-args ctx])`.
      ;; Child callables are captured at compile time — `reg`
      ;; is no longer needed by the runtime.
      (closure (or named-args {}) ctx))))


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
   callable."
  [ctx fn-id]
  (if (fn? fn-id)
    fn-id
    (let [reg (registry ctx)
          closure (get reg fn-id)
          free-names (free-arg-ext-names ctx fn-id)]
      (when-not closure
        (throw (ex-info (str "Function not found: " fn-id)
                        {:type :execution-error/fn-not-found
                         :fn-id fn-id})))
      (ce/make-shape-callable free-names
                              (fn [args] (closure (or args {}) ctx))))))

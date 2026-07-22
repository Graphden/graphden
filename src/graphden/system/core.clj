(ns graphden.system.core
  "Integrant init-key implementations for all system components.

   Component dependency graph:
   :db/schema        → (pure function, no deps)
   :db/postgres      → [:db/schema]
   :db/versioned     → [:db/postgres]
   :app/packages     → (pure, loads package definitions)
   :exec/base-fns    → [:db/versioned, :app/packages]
   :exec/fn-entities → [:db/versioned, :exec/base-fns, :app/packages]
   :exec/context     → [:db/versioned]
   :exec/compiled-registry  → [:exec/context, :exec/fn-entities]
   :exec/service-reconciler → [:exec/context, :app/packages, :exec/compiled-registry]
   :exec/cleanup-scheduler  → [:exec/context]"
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.auth.provider :as auth]
    [graphden.clients.vault :as vault]
    [graphden.crud.fn-execution.lookup :as fn-lookup]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry-core]
    [graphden.executor.registry.interface :as registry]
    [graphden.fleet.command :as fleet-command]
    [graphden.fleet.control-loop :as fleet-loop]
    [graphden.fleet.discovery :as fleet-discovery]
    [graphden.fleet.router :as fleet-router]
    [graphden.packages.loader :as pkg]
    [graphden.packages.manifest :as manifest]
    [graphden.packages.records :as records]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as records-parse]
    [graphden.schema.executions.schema :as es]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.packages.schema :as pkgs]
    [graphden.schema.placement.schema :as placement]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.services.schema :as svcs]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.services.port-check :as port-check]
    [graphden.services.reconciler :as recon]
    [graphden.storage.postgres.advisory-lock :as pg-lock]
    [graphden.storage.postgres.core :as postgres]
    [graphden.storage.postgres.notify :as pg-notify]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.api-routes-js :as api-routes-js]
    [graphden.system.api-url-drift :as api-url-drift]
    [graphden.system.branch-router :as br]
    [graphden.system.demo-branches :as demo]
    [graphden.system.sse :as sse]
    [graphden.types.check :as types-check]
    [graphden.types.check.narrowing :as types-narrowing]
    [graphden.types.core :as types]
    [graphden.versioning.storage.core :as vs]
    [integrant.core :as ig]))


;; =============================================================================
;; Schema (pure, no lifecycle)
;; =============================================================================

(defmethod ig/init-key :db/schema [_ {:keys [extensions]}]
  (log/info "Building schema...")
  (let [base (-> (mds/create-builder)
                 (gds/extend-builder)
                 (vts/extend-builder)
                 (vds/extend-builder)
                 ;; Executions ref :fn-version (vds-registered above). Non-
                 ;; versioned (event-shaped, immutable), so they don't appear
                 ;; in versioning's entity-config.
                 (es/extend-builder)
                 ;; Services ref :fn (logical, not version). Also non-versioned —
                 ;; admin desired-state mutates in place; per-version trail is
                 ;; carried by the :fn-execution rows services SPAWN.
                 (svcs/extend-builder)
                 ;; Registry artifacts — immutable published package snapshots.
                 ;; Non-versioned (immutable by contract), refs nothing graph-side.
                 (pkgs/extend-builder)
                 ;; Fleet placement map `(org, entry-fn-id) → executor-id`
                 ;; (docs/FLEET_RFC.md §6.1). Refs :fn (logical). Non-versioned —
                 ;; control-plane routing state that mutates in place.
                 (placement/extend-builder))]
    ;; Addon schema-extension seam (PLATFORM_PLAN §3.0): each `extensions`
    ;; entry is a `(builder → builder)` fn — the tenancy addon adds its
    ;; `:grant` entity here without editing core. Absent → core schema.
    (ds/build (reduce (fn [b extend] (extend b)) base (or extensions [])))))


;; =============================================================================
;; Storage (unified initialization)
;; =============================================================================

(defn- init-storage!
  "Unified storage initialization.
   Creates storage using create-fn, initializes with schema, seeds traits."
  [storage-name create-fn {:keys [jdbc-url username password pool-size schema]}]
  (log/info (str "Connecting to " storage-name ":") jdbc-url)
  (let [storage (-> (create-fn {:jdbc-url jdbc-url
                                :username username
                                :password password
                                :pool-size pool-size})
                    (sp/initialize-with-cleanup! schema))]
    (vts/seed-traits! storage)
    (log/info (str storage-name " initialized"))
    storage))


(defn- halt-storage!
  "Unified storage shutdown."
  [storage-name storage]
  (log/info (str "Closing " storage-name "..."))
  (sp/close storage))


(defmethod ig/init-key :db/postgres [_ {:keys [datasource-wrap] :as opts}]
  ;; `:datasource-wrap` (a fn DataSource→DataSource) is the tenancy addon's
  ;; RLS seam (§3.0 B5 ops wiring) — it wraps the pool so every connection
  ;; carries `graphden.current_org`. Absent in core → plain pool.
  (let [storage (init-storage! "PostgreSQL" postgres/create-storage
                               (dissoc opts :datasource-wrap))]
    (cond-> storage
      datasource-wrap (assoc :pool (datasource-wrap (:pool storage))))))


(defmethod ig/halt-key! :db/postgres [_ storage]
  (halt-storage! "PostgreSQL storage" storage))


;; =============================================================================
;; Cross-process LISTEN/NOTIFY transport
;; =============================================================================
;;
;; Background thread + dedicated Postgres connection. Receives events
;; (`:service` writes today; fn-def invalidations in Block 7 sub-block
;; B) and dispatches to registered callbacks. The reconciler registers
;; its callback during its own init-key; the connection's session
;; lasts the lifetime of the pod.

(defmethod ig/init-key :db/notify-listener [_ {:keys [pg-opts]}]
  (log/info "Starting LISTEN listener for graphden_events...")
  (pg-notify/create-listener pg-opts))


(defmethod ig/halt-key! :db/notify-listener [_ listener]
  (log/info "Stopping LISTEN listener...")
  (pg-notify/close-listener! listener))


;; =============================================================================
;; SSE invalidation relay — forwards `graphden_events` to remote / BYO
;; executors that can't LISTEN on Postgres (docs/SCALING.md § SSE).
;;
;; Opt-in: only starts when `GRAPHDEN_SSE_PORT` is set. On its own httpkit
;; server / port, parallel to the app server + the LISTEN connection —
;; invalidation is infra below the graph-composed router, not an app route.
;; =============================================================================

(defmethod ig/init-key :sse/relay [_ {:keys [port notify-listener auth-provider]}]
  (let [p (if (string? port) (parse-long port) port)]
    (if (and p (pos? p))
      (do (log/info "Wiring SSE invalidation relay" {:port p})
          (sse/start-relay! {:port p
                             :notify-listener notify-listener
                             :auth-provider auth-provider}))
      (do (log/info "SSE relay disabled (no GRAPHDEN_SSE_PORT)") nil))))


(defmethod ig/halt-key! :sse/relay [_ relay]
  (when relay (sse/stop-relay! relay)))


;; =============================================================================
;; Per-service advisory-lock connection
;; =============================================================================
;;
;; A dedicated Postgres connection that holds this pod's service
;; ownership locks. `pg_try_advisory_lock(<service-key>)` succeeds
;; for whichever pod gets to it first; siblings see false and skip
;; starting the service. On pod halt the connection closes →
;; Postgres releases every lock → sibling pods can take over on
;; their next reconcile pass.

(defmethod ig/init-key :db/service-locks [_ {:keys [pg-opts]}]
  (log/info "Opening service-locks connection...")
  ;; A reconnecting HOLDER, not a bare Connection: a dropped lock
  ;; connection releases every advisory lock this pod held, and
  ;; `advisory-lock/ensure-live!` (called from the reconciler) reopens it
  ;; + re-asserts ownership. The holder IS the integrant value.
  (pg-lock/create-lock-holder pg-opts))


(defmethod ig/halt-key! :db/service-locks [_ holder]
  (log/info "Releasing service-locks + closing connection...")
  ;; `close-holder!` releases all locks (best-effort, logged so
  ;; shutdown-time PG drift is visible) then closes the underlying
  ;; connection.
  (pg-lock/close-holder! holder))


;; =============================================================================
;; Versioned Storage Decorator
;; =============================================================================

;; Tenancy storage seam (PLATFORM_PLAN §3.0). Core wires an IDENTITY
;; passthrough of the base storage; the tenancy addon overrides this key
;; with an `OrgScopedStorage` decorator that injects the per-request
;; `org-id` filter. Placement is deliberate: it sits BENEATH versioning
;; (`Versioned(OrgScoped(Postgres))`), so the branch-router's `vs/unwrap`
;; (which strips the VersionedStorage to rebuild a per-branch view) lands
;; on the OrgScoped layer and the tenant filter survives — closing the
;; ADR §3.0 nuance-1 `vs/unwrap` leak. (RLS is still the belt-and-
;; suspenders second layer.)
(defmethod ig/init-key :app/storage [_ {:keys [base]}]
  base)


(defmethod ig/init-key :db/versioned [_ {:keys [base-storage]}]
  (log/info "Enabling versioning...")
  (let [versioned (vs/wrap-with-versioning base-storage)]
    (log/info "Branch:" (vs/current-branch-id versioned))
    versioned))


;; No halt needed - base storage handles cleanup


;; =============================================================================
;; Package Loading
;; =============================================================================

(defmethod ig/init-key :app/packages [_ {:keys [package-names extra-package-names]}]
  ;; `:extra-package-names` is the addon fns-channel seam (PLATFORM_PLAN
  ;; §2.1 / §3.0): the tenancy addon appends its own fns-package(s) — e.g.
  ;; the org-admin UI — via the manifest WITHOUT restating the core list,
  ;; so they load only when the addon is active.
  (let [names (vec (concat package-names extra-package-names
                           (manifest/package-names (manifest/read-manifest))))]
    (log/info "Loading packages:" names)
    (let [packages (pkg/load-packages names)]
      (log/info "Packages loaded:" (count (:packages packages)) "packages,"
                (count (:base-fn-defs packages)) "base-fns,"
                (count (:fn-defs packages)) "fn-defs")
      packages)))


;; =============================================================================
;; Base Functions Registry
;; =============================================================================

(defn compute-all-fn-name-ids
  "Pre-compute deterministic fn-ids for every named def across the
   loaded packages — base-fns + composed fn-defs (incl. `:fn-type`
   declarations) combined. Threaded into both syncs so cross-module
   references (e.g. a base-fn's `:return-type` pointing at a type-row
   in another module) resolve.

   `:fn-type` declarations get the standard `(fn-id ns name)`
   deterministic UUID — they now produce real fn-rows whose
   `:constraint` carries the structural `[:fn args ret]` shape
   (mirrors how unions / variants stash their payload). Pre-fix this
   path aliased them to `primitive-fn-id :fn`, leaving every
   `:return-type :http-server-handle`-style reference pointing at the
   bare-`:fn` row and erasing the structural shape from storage."
  [packages]
  (let [base-pairs (keep (fn [[fn-name fn-def]]
                           (when fn-name
                             [fn-name (records/fn-id (:namespace fn-def) fn-name)]))
                         (:base-fn-defs packages))
        fn-def-pairs (keep (fn [fd]
                             (when-let [n (:name fd)]
                               [n (records/fn-id (:namespace fd) n)]))
                           (:fn-defs packages))]
    (into {} (concat base-pairs fn-def-pairs))))


(defn register-type-aliases!
  "Walk every fn-def that declares a structural type (refinement,
   record, list, union, fn-type) and register it as a type-alias so
   the type-checker's `resolve-alias` can expand the keyword when it
   appears as a `:type` reference in another fn-def. Without this,
   `:http-server :args {:port :port}` would store the bare keyword
   and a downstream literal like `{:port 8080}` would trigger a
   bogus `:int ⊆ :port` primitive subtype check.

   Two passes — the second resolves now that all top-level names
   are known. This lets `:ring-handler` reference `:ring-request`
   regardless of declaration order in fns.edn.

   Registration tries each alias even if some fail validation
   (e.g. references an unknown type) — the second pass usually
   resolves those. Genuine errors surface later through the
   type-checker on first use."
  [fn-defs]
  (let [alias-body
        (fn [fd]
          (cond
            (:refine fd)
            (let [{:keys [base constraint]} (:refine fd)]
              (when base [:refine base (or constraint [:any])]))

            (and (:type fd) (map? (:type fd)))
            (:type fd)

            (:list fd)
            [:list (:list fd)]

            (:union fd)
            (into [:union] (:union fd))

            ;; Homogeneous map alias — `:map {:key K :value V}` is sugar
            ;; for the structural `[:map K V]`. Without this branch the
            ;; alias-body fn ignored the declaration and downstream slot
            ;; references (`:list-entities :where :_storage-where-map`)
            ;; saw a bare keyword the alias registry didn't know about,
            ;; so the type-checker treated it as opaque and a literal
            ;; `{:value {}}` failed against it. See
            ;; `docs/TYPE_CHECK_BACKLOG.md` § "Re-audit (2026-06-07)".
            (and (:map fd) (map? (:map fd)))
            (let [{:keys [key value]} (:map fd)]
              (when (and key value) [:map key value]))

            ;; `:variant [:tag1 T1 :tag2 T2 …]` desugars to a union of
            ;; tag-pinned records (see types/desugar-variant). Without
            ;; this branch the EDN-declared `:result-text`,
            ;; `:result-int`, `:validation` aliases never reached
            ;; `register-type-alias!` and the type-checker treated
            ;; them as unknown keywords — defeating the whole point
            ;; of a variant declaration.
            (:variant fd)
            (types/desugar-variant (:variant fd))

            (:fn-type fd)
            (let [[args ret] (:fn-type fd)]
              [:fn (or args {}) ret])))
        candidates (for [fd fn-defs
                         :when (:name fd)
                         :let [body (alias-body fd)]
                         :when body]
                     ;; Owner id = the type-row's deterministic sync id —
                     ;; feeds the cross-owner collision diagnostic
                     ;; (per-ns names may legally repeat; a silent alias
                     ;; overwrite must not).
                     [(:name fd) body (records/fn-id (:namespace fd) (:name fd))])
        try-once
        (fn [pending]
          ;; Returns the subset of [name body] pairs whose validation
          ;; still fails — caller iterates until fixed point.
          (reduce
            (fn [still-pending [nm body owner]]
              (try (types/register-type-alias! nm body owner)
                   still-pending
                   (catch Exception _
                     (conj still-pending [nm body owner]))))
            []
            pending))]
    ;; Iterate to fixed point — each pass widens `aliases-snapshot`,
    ;; which `well-formed?` consults for inner-keyword refs. Bound
    ;; the loop count so a true cycle (or a body referencing an
    ;; unknown type) terminates instead of spinning.
    (loop [pending candidates, iter 0]
      (let [next-pending (try-once pending)]
        (cond
          (empty? next-pending) nil
          (or (= (count next-pending) (count pending))
              (>= iter 8))
          (doseq [[nm _] next-pending]
            (log/warn "register-type-alias! failed for" nm
                      "— body references an unknown type"))
          :else (recur next-pending (inc iter)))))))


(defn- validate-no-name-collisions!
  "Every named fn must own a globally-unique name across BOTH base-fns AND
   composed fn-defs. Reference resolution keys on bare names
   (`compute-all-fn-name-ids`), and a base-fn + fn-def sharing a name in the
   same namespace collapse to one `(records/fn-id namespace name)` — so the
   fn-def row SILENTLY upserts over the base-fn row at sync (parent-ids
   replacing the base-fn's return-type marker while the impl registry still
   holds the impl), with NO error. The composed-only `validate-all-defs!`
   never sees the base-fns, so this is the sole cross-set guard. Anonymous
   defs (name = nil) are content-hash-deduped and excluded."
  [packages]
  (let [base-names (keep first (:base-fn-defs packages))
        def-names  (keep :name (:fn-defs packages))
        dups (->> (concat base-names def-names)
                  frequencies
                  (keep (fn [[n c]] (when (> c 1) n)))
                  vec)]
    (when (seq dups)
      (throw (ex-info (str "Colliding fn names across base-fns + fn-defs: "
                           (pr-str dups)
                           " — the same name is defined more than once "
                           "(a base-fn ↔ fn-def clobber silently overwrites a row at sync).")
                      {:type :packages/fn-name-collision
                       :colliding-names dups})))))


(defn register-base-fns-from-packages!
  "Pure side-effects: sync namespaces, register type-aliases, register
   base-fn impls in the global registry, sync base-fn rows to storage.
   `extra-base-fns` is an optional map of `{fn-name → impl}` merged on
   top of the package impls (test overrides). Returns
   `{:ns-id-map :all-name->id :base-fns}` so callers can thread the
   resolved name→id map into a subsequent
   `sync-fn-entities-from-packages!` call.

   Shared by the production `:exec/base-fns` integrant init-key and the
   out-of-band `bootstrap-from-packages!` test helper."
  ([storage packages]
   (register-base-fns-from-packages! storage packages nil))
  ([storage packages extra-base-fns]
   ;; Fail loud BEFORE any DB write if a base-fn and a fn-def share a name.
   (validate-no-name-collisions! packages)
   (let [base-fn-defs (:base-fn-defs packages)
         ;; Sync namespace entities first (creates ns hierarchy in DB)
         ns-id-map (pkg/sync-namespaces! storage (:namespaces packages)
                                         (:ns-descriptions packages))
         ;; Full name→id map covering base-fns + composed fn-defs so
         ;; either sync can resolve a reference into the other set.
         all-name->id (compute-all-fn-name-ids packages)
         ;; Map of {fn-name → impl} threaded through to `:exec/context`
         ;; via integrant — sidesteps the process-global registry so
         ;; concurrent test-scope start! calls can't race on the atom.
         ;; `extra-base-fns` is merged on top of package impls. Same
         ;; map is also pushed into the global registry for back-compat
         ;; with direct `exec/get-base-fn` / REPL callers.
         base-fns-map (merge (registry/compute-base-fns-map base-fn-defs)
                             extra-base-fns)]
     (registry/sync-primitives! storage)
     ;; Register refinement type-aliases BEFORE base-fn rich-type
     ;; recording so `:http-server :args {:port :port}` stores the
     ;; structural `[:refine :int …]` form, not the bare keyword.
     (register-type-aliases! (:fn-defs packages))
     (doseq [[fn-name impl] base-fns-map]
       (exec/register-base-fn! fn-name impl))
     (registry/sync-defs-to-storage! storage base-fn-defs ns-id-map all-name->id)
     {:ns-id-map ns-id-map
      :all-name->id all-name->id
      :base-fns base-fns-map})))


(defn- run-type-check-sweep!
  "Topological-order type-check sweep across `expanded-fn-defs`.

   - **Pass 1** isolates each fn-def's per-fn check; failures populate
     the registry with whatever rich-type the partial run could
     produce and get DEBUG-logged.
   - **Pass 2/3** rebuilds caller-narrowings (Phase α') + ref-return
     overrides (Phase #170) over the topologically-sorted list and
     re-runs each fn-def with both bound — that fixpoint replaces
     pass 1's isolation view; the FINAL failure set is what the
     allowlist gates against.
   - Sweep summary WARN runs when any fn-def failed (DEBUG-logged
     per-fn).
   - **Allowlist gate** — `types-check/allowed-type-check-failures`
     enumerates the known-failing fn-defs (closed over time as the
     type system gains expressiveness). Any failure NOT in the
     allowlist is a regression; any allowlisted name that's NO LONGER
     failing must be removed from the allowlist. Throws at sync time
     so CI catches both. `skip-allowlist-gate?` opts a test bootstrap
     out — useful when loading a SUBSET of production packages."
  [expanded-fn-defs skip-allowlist-gate?]
  (let [sorted (deps/topological-sort expanded-fn-defs)
        failed-names (atom #{})]
    (doseq [fd sorted]
      (try (types-check/check-fn-def! fd)
           (catch Exception e
             (swap! failed-names conj (:name fd))
             (log/debug "Type-check failed for fn-def" (:name fd) "—"
                        (ex-message e)))))
    (let [narrowings (types-narrowing/build-caller-narrowings sorted)
          overrides  (types-narrowing/build-ref-return-overrides sorted)]
      (reset! failed-names #{})
      (doseq [fd sorted]
        (try (types-narrowing/check-fn-def-with-narrowings! fd narrowings overrides)
             (catch Exception e
               (swap! failed-names conj (:name fd))
               (log/debug "Type-check failed for fn-def" (:name fd) "—"
                          (ex-message e))))))
    (when (pos? (count @failed-names))
      (log/warn "Type-check sweep: " (count @failed-names)
                "fn-defs failed (DEBUG-logged) — runtime unaffected,"
                " editor effect/return strips may be missing for those names —"
                " docs/TYPE_CHECK_BACKLOG.md"))
    (when-not skip-allowlist-gate?
      (types-check/assert-sweep-failures-match-allowlist! @failed-names))))


(defn sync-fn-entities-from-packages!
  "Pure side-effects: sync composed fn-defs to storage, snapshot their
   rich-types, run a topological-order type-check sweep. Returns the
   created fn-rows. `base-fns-info` is the result of
   `register-base-fns-from-packages!` — its `:ns-id-map` and
   `:all-name->id` are forwarded to the compose layer so cross-set
   references (base-fn `:return-type` naming a fn-def-declared type-row)
   resolve.

   Shared by the production `:exec/fn-entities` integrant init-key and
   the out-of-band `bootstrap-from-packages!` test helper.

   `:skip-type-check?` — when truthy, runs only the storage-sync +
   seed-rich-types passes; skips the heavy topological type-check
   sweep at the end. Tests that don't exercise the type-API
   endpoints can opt in to save ~15 s of bootstrap per ns. The
   editor type strips / `/api/types/*` endpoints depend on the
   sweep, so production NEVER skips."
  ([storage packages base-fns-info]
   (sync-fn-entities-from-packages! storage packages base-fns-info nil))
  ([storage packages base-fns-info
    {:keys [skip-type-check? skip-allowlist-gate?]}]
   (let [fn-defs (:fn-defs packages)
         ns-id-map (or (:ns-id-map base-fns-info) {})
         extra-name->id (or (:all-name->id base-fns-info) {})
         ;; Hand the base-fn defs into the composed-fn sync so the slot
         ;; resolver sees their `:args` declarations — without these,
         ;; bindings on slots owned by base-fns wouldn't resolve.
         extra-defs (into {}
                          (keep (fn [[fn-name fn-def]]
                                  (when fn-name
                                    [fn-name (assoc fn-def :name fn-name)])))
                          (:base-fn-defs packages))
         fns (fn-composition/sync-fns-to-storage! storage fn-defs ns-id-map
                                                  extra-name->id extra-defs)]
     ;; Snapshot composed fn-defs into the in-memory rich-type registry
     ;; so the editor's `:effects` strip and arg-type hints can resolve
     ;; their declared shape. Two passes:
     ;;
     ;; 1. Seed each fn-def's declared shape (return + args + declared
     ;;    `:effects`). Without this, `check-all-defs!` would fail to
     ;;    resolve refs to peer fn-defs whose entries don't exist yet.
     ;; 2. Run the full type-checker so `:effects` propagate transitively
     ;;    through every parent + ref edge — that's what powers the
     ;;    editor's effects-strip showing the union of every category
     ;;    a fn-def TRANSITIVELY pulls in. Wrap in try/catch: a single
     ;;    fn-def's type-mismatch shouldn't block server startup.
     ;; Refinement aliases are registered earlier in `:exec/base-fns` so
     ;; base-fn arg types (`:port`, `:user-port`, …) resolve to their
     ;; structural form during the base-fn rich-type pass.
     ;; record-rich-types! validates arg `:type` declarations — those
     ;; only exist on base-fn-style fn-defs (rare here; composed fn-defs
     ;; use `:args` for parent BINDINGS, not declarations). Try-each so a
     ;; few mis-shaped entries don't kill the seed pass; check-all-defs!
     ;; below recovers the proper computed types via type-inference anyway.
     (doseq [fd fn-defs]
       (when-let [fn-name (:name fd)]
         (try (registry-core/record-rich-types! fn-name fd)
              (catch Exception e
                ;; Mis-shaped entries are recoverable by the type-check
                ;; sweep below — don't block startup, but DEBUG-log so
                ;; the per-fn cause is available when chasing a
                ;; downstream sweep failure.
                (log/debug e "Seed-pass record-rich-types! failed for"
                           fn-name)))))
     ;; Type-check in dependency (topological) order: every fn-def is
     ;; checked AFTER the parents and refs it reads, so a SINGLE sweep
     ;; reaches the fixpoint — `check-fn-def!` always sees its
     ;; dependencies' final rich-types, never a stale seed. This is
     ;; what eliminates the order-dependent under-convergence a fixed
     ;; pass count over arbitrary order suffered (a deep chain it
     ;; couldn't propagate, leaving composed fn-defs absent or
     ;; mis-typed). A fn-def that throws here is genuinely absent from
     ;; the rich-type registry — the editor would miss its effect strip
     ;; / computed return — so the per-failure detail is logged at
     ;; DEBUG and a single summary WARN runs at the end. Per-fn-def
     ;; WARNs were too noisy in prod startup logs (~22 baseline
     ;; entries from `:router-result` / `:merge-in` / friends whose
     ;; producer-of-callable shape the typchecker can't unify yet —
     ;; runtime behaviour is correct, the editor just doesn't get a
     ;; computed return-type for those slots). DEBUG keeps the signal
     ;; available; the summary count makes regressions visible.
     ;; Inline `{:parent :X :args …}` anon fn-defs appear in arg-binding
     ;; position throughout `branches/`, `secrets/`, and `execution/`
     ;; packages. The parser's storage-sync pass lifts each into a
     ;; synthetic named `_anon-<hash>` fn-def — so storage / runtime
     ;; see the expanded form — but the type-check sweep reads the
     ;; ORIGINAL EDN, which still carries the literal map. Without
     ;; pre-expansion the sweep classifies each inline anon as a
     ;; record-type literal and fails 20+ bindings against slots that
     ;; expect the parent's structural return type (eg. `:ref` against
     ;; `[:union :null :text]`, `:string` against predicates).
     ;;
     ;; Run the same `expand-inline-anons-in-module` pass on the
     ;; type-check input so the sweep sees the synthetic refs.
     (let [expanded-fn-defs (records-parse/expand-inline-anons-in-module fn-defs)]
       ;; Re-seed rich-types so synthetic anons get a registry entry too.
       (doseq [fd expanded-fn-defs]
         (when-let [fn-name (:name fd)]
           (try (registry-core/record-rich-types! fn-name fd)
                (catch Exception e
                  (log/debug e "Re-seed record-rich-types! failed for" fn-name)))))
       (when-not skip-type-check?
         (run-type-check-sweep! expanded-fn-defs skip-allowlist-gate?)
         ;; Port-collision scan — runs against the expanded fn-def
         ;; set so synthetic anons that bind `:port` get inspected
         ;; too. Logs a WARN per colliding port; doesn't fail
         ;; bootstrap because the OS still tells the truth at
         ;; reconcile time. Catches admin-misconfig (two web-server
         ;; fn-defs both bound to :port 8080) BEFORE any service is
         ;; even created — earlier than `:start-failed-at`.
         (port-check/warn-on-collisions! expanded-fn-defs)))
     fns)))


(defmethod ig/init-key :exec/base-fns
  [_ {:keys [storage packages extra-base-fns]}]
  (log/info "Registering base functions...")
  (let [result (register-base-fns-from-packages! storage packages extra-base-fns)
        base-fn-defs (:base-fn-defs packages)]
    (log/info "Base functions registered:"
              (count base-fn-defs)
              (when (seq extra-base-fns)
                (str "(+ " (count extra-base-fns) " extras)")))
    (assoc result :status :registered)))


;; No halt needed - registry is global state


;; =============================================================================
;; Fn Entities
;; =============================================================================

(defmethod ig/init-key :exec/fn-entities
  [_ {:keys [storage packages base-fns skip-allowlist-gate?]}]
  (log/info "Creating fn entities...")
  (let [fns (sync-fn-entities-from-packages!
              storage packages base-fns
              {:skip-allowlist-gate? skip-allowlist-gate?})]
    (log/info "Fn entities created:" (count fns))
    fns))


;; =============================================================================
;; Test-friendly bootstrap (out-of-band)
;; =============================================================================
;;
;; Replicates the `:exec/base-fns` + `:exec/fn-entities` init-key chain
;; without integrant. Tests that exercise graph-level handlers
;; (`/api/entities/*` via `:process-create-entity` &c.) call this once
;; from a `:once` fixture to populate storage + registry + type-aliases.
;;
;; Returns `{:ns-id-map :all-name->id :base-fns :fn-rows}` so callers
;; can resolve fn-ids by name without re-querying storage.

(defn bootstrap-from-packages!
  "Bootstrap `storage` from the named `package-names` (default
   [\"core\" \"web\"]). Calls the same `register-base-fns-from-packages!`
   + `sync-fn-entities-from-packages!` helpers the production
   `:exec/base-fns` + `:exec/fn-entities` init-keys use, so any drift
   between bootstrap and production stays localized to those two
   helpers. Safe to call once per test JVM lifetime against a clean
   storage. Idempotent against the global type-alias / base-fn-impl
   registries (re-registers them)."
  ([storage]
   (bootstrap-from-packages! storage ["core" "web"] nil))
  ([storage package-names]
   (bootstrap-from-packages! storage package-names nil))
  ([storage package-names opts]
   (let [packages (pkg/load-packages package-names)
         base-fns-info (register-base-fns-from-packages! storage packages)
         fns (sync-fn-entities-from-packages! storage packages base-fns-info opts)]
     (assoc base-fns-info :fn-rows fns))))


;; =============================================================================
;; Vault client (OpenBao / Vault KV v2)
;; =============================================================================
;;
;; Infrastructure-level secrets handle. The executor pulls this off
;; the context to auto-deref `:override-kind :secret-path` bindings
;; on `:secret-leaf`-parented fn-defs. Address + token live in
;; `system-*.edn` (and ultimately env), NEVER exposed to the user
;; fn-graph — that's the whole point of routing user secrets through
;; `:secret-leaf` instead of `:env`.
;;
;; Optional: when address is blank, the key returns nil and the
;; secret-leaf auto-deref raises a clear "vault not configured"
;; error on first use. Lets tests skip the openbao container.

(defmethod ig/init-key :vault/client [_ {:keys [address token]}]
  (let [client (when (and (string? address) (not (str/blank? address)))
                 {:address address :token token})]
    (if client
      (log/info "Vault client configured for" address)
      (log/info "Vault client disabled (no :address) — secret-leaf auto-deref will throw on use"))
    ;; Also stash in the JVM-wide atom so consumers that don't get the
    ;; client through their request ctx (admin handlers running in a
    ;; per-branch ctx whose build doesn't carry :vault forward) can
    ;; still find it. See `graphden.clients.vault/active-client` for
    ;; the rationale.
    (reset! vault/active-client client)
    client))


(defmethod ig/halt-key! :vault/client [_ _]
  (reset! vault/active-client nil))


;; =============================================================================
;; Executor Context
;; =============================================================================

;; Authentication seam (PLATFORM_PLAN §3.0 / §4.1). Core wires the default
;; single-token provider; the tenancy addon overrides this key with a
;; session/JWT provider. Everything downstream (the `:authenticate-request`
;; base-fn → `:request-authenticated?` → auth middleware) is provider-
;; agnostic.
(defmethod ig/init-key :auth/provider [_ {:keys [token]}]
  (log/info "Wiring auth provider {:provider :single-token}")
  (auth/single-token-provider token))


(defn- parse-executor-orgs
  "`\"public,acme,beta\"` → `#{\"public\" \"acme\" \"beta\"}`; blank / nil → nil
   (compile the whole graph — the self-hosted default).

   The operator must list the PUBLIC org explicitly if the deployment has
   one: the platform packages live there once they are written through the
   tenancy decorator, and a pod without them compiles nothing. Rows with a
   NULL `:org-id` are un-owned and always in every shard, so a
   non-tenancy deployment that sets this by accident still works."
  [s]
  (when (string? s)
    (let [orgs (into #{} (comp (map str/trim) (remove str/blank?))
                     (str/split s #","))]
      (when (seq orgs) orgs))))


(defmethod ig/init-key :exec/context
  [_ {:keys [storage vault-client pg-storage base-fns auth-provider request-scope
             execute-guard app-router set-org-handler verify-domain user-ops
             executor-orgs byo-executor? executor-id]}]
  (log/info "Creating executor context...")
  ;; `assoc` (not the constructor's named opts) — the ExecutionContext
  ;; record stays narrow; vault rides on the extra-key surface
  ;; alongside `:compiled-templates`. Impls grab it via `(:vault ctx)`.
  ;;
  ;; `:notify-emitter` is built from the raw PG pool — short-lived
  ;; `SELECT pg_notify(...)` runs through the main pool just like
  ;; any other one-shot query. CRUD writers call
  ;; `((:notify-emitter ctx) event)` after a successful `:service`
  ;; row mutation. Falls back to a no-op when pg-storage is absent
  ;; (tests that don't wire PG).
  ;;
  ;; `:base-fns` comes via integrant ref from `:exec/base-fns` —
  ;; ctx-scoped (no global registry read). When the ref isn't wired
  ;; (older config files or tests that skip `:exec/base-fns`),
  ;; `create-context` falls back to the global registry snapshot.
  (let [emitter (if pg-storage
                  (pg-notify/make-emitter (:pool pg-storage))
                  pg-notify/noop-emitter)
        ;; Fleet forward-hop seam (T2.6): only wired when this pod has a fleet
        ;; identity (`GRAPHDEN_EXECUTOR_ID`). Forwards a misdirected request to
        ;; the executor holding the org's cell (per `:placement`) instead of
        ;; 421. All executors listen on the same port (`GRAPHDEN_PORT`).
        fleet-forward (when (seq executor-id)
                        (let [port (fleet-command/fleet-port)]
                          (fn [request org entry-fn-id]
                            (fleet-router/forward-or-nil storage executor-id port
                                                         org entry-fn-id request))))
        ;; Fleet control-plane command seam (docs/FLEET_RFC.md §6.3): the
        ;; internal cell load/evict endpoint a move (or an ops call) drives.
        ;; Wired on the same condition as the forward-hop (this pod is a fleet
        ;; member). Gated by the shared internal token, NOT the tenant auth-
        ;; provider — a cell command is cross-org platform authority.
        fleet-cmd (when (seq executor-id)
                    (fleet-command/make-command-handler (fleet-command/internal-token)))
        ctx-opts (cond-> {:storage storage}
                   (and base-fns (:base-fns base-fns))
                   (assoc :base-fns (:base-fns base-fns))
                   ;; The auth seam — read by `:authenticate-request` via
                   ;; `(:auth-provider ctx)`. Branch contexts inherit it
                   ;; (build-branch-ctx).
                   auth-provider (assoc :auth-provider auth-provider)
                   ;; Request-scope seam (§3.0 B4) — wrapped around each
                   ;; handler by the branch-router. Only the tenancy addon
                   ;; wires it; absent in core (single-tenant).
                   request-scope (assoc :request-scope request-scope)
                   ;; Per-namespace execute guard (§4.2). Addon-only.
                   execute-guard (assoc :execute-guard execute-guard)
                   ;; App-router seam (§3.4 FaaS) — serves a tenant's subdomain
                   ;; via that org's handler fn. Addon-only.
                   app-router (assoc :app-router app-router)
                   ;; Self-serve deploy seam (§3.4 4b) — addon-only.
                   set-org-handler (assoc :set-org-handler set-org-handler)
                   ;; Self-serve DNS-verify seam (§3.4 #2) — addon-only.
                   verify-domain (assoc :verify-domain verify-domain)
                   ;; User-model seam (§4.1) — create-user / login. Addon-only.
                   user-ops (assoc :user-ops user-ops)
                   ;; Executor shard — the orgs whose fns THIS pod compiles.
                   ;; Absent ⇒ the whole graph (self-hosted / single-tenant).
                   ;; A collection or predicate from an addon passes through;
                   ;; a comma-separated env string is parsed here.
                   executor-orgs
                   (assoc :executor-orgs (if (string? executor-orgs)
                                           (parse-executor-orgs executor-orgs)
                                           executor-orgs))
                   ;; Pod role — a BYO executor serves the `:byo` orgs in its
                   ;; shard; a hosted pod 421s them. From `GRAPHDEN_BYO_EXECUTOR`
                   ;; (parsed to bool) or an addon override.
                   byo-executor?
                   (assoc :byo-executor? (if (string? byo-executor?)
                                           (contains? #{"true" "1" "yes"} byo-executor?)
                                           (boolean byo-executor?)))
                   ;; Fleet forward-hop seam — only present when this pod has a
                   ;; fleet identity (see the let above).
                   fleet-forward (assoc :fleet-forward fleet-forward)
                   ;; Fleet control-plane command seam — same condition.
                   fleet-cmd (assoc :fleet-command fleet-cmd))]
    (cond-> (-> (exec/create-context ctx-opts)
                (assoc :notify-emitter emitter))
      vault-client (assoc :vault vault-client)
      ;; Privileged structural-read storage (§4 org-agnostic compile): the raw
      ;; PG beneath OrgScoped, re-wrapped for this branch. `rebuild!` reads the
      ;; fn-graph STRUCTURE through it so the compiled registry contains every
      ;; org's fns (isolation stays at runtime: org-scoped data reads via
      ;; `:storage` + the `resolve-fn` / execute-guard gates). `:pg-storage`
      ;; rides too so `build-branch-ctx` can build a per-branch compile storage.
      ;; In single-tenant this equals `:storage` (no OrgScoped) → a no-op.
      pg-storage (assoc :pg-storage pg-storage
                        :compile-storage (vs/->VersionedStorage
                                           pg-storage (vs/current-branch-id storage))))))


;; =============================================================================
;; Compiled Registry (compile-at-startup executor)
;; =============================================================================
;;
;; Walks every fn entity in storage and compiles each into a Clojure
;; closure of shape `(fn [all-fns free-args] result)`. Stored in the
;; context's `:compiled-registry` atom for the hot path (HTTP handlers).

(defmethod ig/init-key :exec/compiled-registry [_ {:keys [context]}]
  (log/info "Building compiled registry...")
  (let [registry (cr/rebuild! context)]
    (log/info "Compiled registry built:" (count registry) "fns")
    registry))


;; =============================================================================
;; Branch router — per-branch ExecutionContext + Ring dispatch
;; =============================================================================
;;
;; Each non-default branch needs its OWN compiled registry — the
;; executor closes over ctx at compile-time and a branch's bindings
;; can diverge from main. Lazy build on first request; cached
;; afterwards. Mutations call `invalidate-graph-cache!` on the per-
;; branch ctx via the standard executor invalidation path, so a
;; write on branch X clears X's registry without touching main's.
;;
;; The router lives in a process-wide atom
;; (`branch-router/active-router`) because the wrap base-fn impl
;; runs inside compiled closures that already closed over a fixed
;; ctx.

(defmethod ig/init-key :exec/branch-router [_ {:keys [context]}]
  (log/info "Initialising branch router...")
  (let [router (br/create-router context "_app-ring-response")]
    (br/set-active-router! router)
    router))


(defmethod ig/halt-key! :exec/branch-router [_ _router]
  (br/clear-active-router!))


;; =============================================================================
;; Backend↔frontend URL drift check
;; =============================================================================
;;
;; Runs once after `:_router` is compiled. Enumerates the live
;; router's `/api/*` paths, scans every editor JS file for `/api/*`
;; string-literals, and throws if any literal doesn't match a known
;; path or prefix. Catches "renamed a route fn-def, forgot to
;; update the JS" silently-broken-deploys at boot time.
;;
;; See `graphden.system.api-url-drift` for the algorithm and
;; `graphden.system.api-url-drift-test` for the per-helper unit
;; tests. The check is enabled by default; `skip?` (env-backed) is
;; a circuit breaker for test bootstraps that load a subset of
;; packages.

(defn env-truthy?
  "Parse a wire-friendly truthy flag. Accepts the EDN literal `true`,
   or any of `\"1\" \"true\" \"yes\" \"on\"` (case-insensitive) when
   the value came through an env var. Anything else (including the
   empty string from an unset env in `system-prod.edn`) is OFF.

   Used wherever an integrant arg can come from Aero `#env` (which
   collapses unset vars to `\"\"`, a truthy value in Clojure)."
  [raw]
  (cond
    (true? raw)                  true
    (or (false? raw) (nil? raw)) false
    (string? raw)                (contains? #{"1" "true" "yes" "on"}
                                            (str/lower-case raw))
    :else                        (boolean raw)))


(defmethod ig/init-key :exec/api-url-drift-check
  [_ {:keys [context skip?]}]
  ;; `skip?` reuses `env-truthy?` — unset env collapses to `""`,
  ;; which must NOT count as truthy. Empty string / false / nil →
  ;; run the check; "1" / "true" / "yes" / "on" → skip.
  (if (env-truthy? skip?)
    (do (log/info "API URL drift check skipped"
                  "— set GRAPHDEN_SKIP_URL_DRIFT_CHECK= to re-enable")
        :skipped)
    (do (log/info "Checking editor JS for /api/* URL drift...")
        (let [router (exec/execute-by-name context "_router" {})]
          (api-url-drift/check-router! router)
          (log/info "API URL drift check passed")
          :ok))))


;; =============================================================================
;; API routes JS cache
;; =============================================================================
;;
;; Builds the `window.API = {…}` JS module once at boot from the
;; live `:_router`. Stored in a process-global atom; read by the
;; `cached-api-routes-js` defbase (declared `:effects #{}` so the
;; bundle pipeline doesn't inherit handler effects through this
;; chain). The editor JS bundle's `:_editor-api-routes-script-tag`
;; renders the cached value into an inline `<script>` BEFORE the
;; main editor.js loads, exposing `window.API.<key>` to every
;; editor module.

(defmethod ig/init-key :exec/api-routes-js-cache
  [_ {:keys [context]}]
  (log/info "Building cached api-routes JS module...")
  (let [router (exec/execute-by-name context "_router" {})]
    (api-routes-js/install-from-router! router)
    (log/info "api-routes JS cache:" (count (api-routes-js/read-cache)) "bytes")
    :ok))


(defmethod ig/halt-key! :exec/api-routes-js-cache [_ _]
  (api-routes-js/clear-cache!))


;; =============================================================================
;; Demo branches (dev only — no-op in prod when `:branches` is absent/empty)
;; =============================================================================
;;
;; Pre-bakes a couple of versioning-UI demo branches after package sync
;; finishes. Idempotent: existing branches with the same name are left
;; alone, so a JVM restart doesn't double-write. `bb deploy` wipes the
;; DB, sync re-runs, and the demo branches reappear.
;;
;; See `graphden.system.demo-branches` for the declaration shape.

(defmethod ig/init-key :exec/demo-branches [_ {:keys [context enabled? branches]}]
  (let [on? (env-truthy? enabled?)]
    (cond
      (not on?)
      (log/info "[demo-branches] disabled"
                "— set GRAPHDEN_DEMO_BRANCHES_ENABLED=1 to seed demo branches")

      (seq branches)
      (demo/seed! (:storage context) branches)

      :else
      (log/info "[demo-branches] enabled but :branches is empty — nothing to seed"))
    ;; Returning state keeps it visible in the system map for any
    ;; future REPL-driven re-seed.
    {:enabled? on?
     :branches (vec (or branches []))}))


;; =============================================================================
;; Service reconciler (replaces :http/server)
;;
;; On start: seed package-declared services into the :service table
;; (idempotent — deterministic ids), then read enabled rows and
;; reconcile.
;;
;; On halt: stop every running service.
;;
;; The in-process atom is modified from CRUD endpoints (which call
;; `recon/reconcile-once!` after writing), from NOTIFY events, and from
;; a periodic tick.
;;
;; Level-triggered convergence: a `ScheduledExecutorService` re-runs the
;; reconcile every `reconcile-period-ms` (default 15s). This is what makes
;; the reconciler robust rather than purely edge-triggered:
;;   - a `:singleton`'s pod CRASHES → its advisory lock auto-releases, but
;;     the crash emits no NOTIFY; a sibling that recorded `::not-our-lock`
;;     re-attempts the lock on the next tick and takes over (HA cron);
;;   - an out-of-band DB edit (psql, other tools) is picked up within a tick;
;;   - a transient start failure (port not yet freed) reconverges on a tick
;;     instead of sleeping under `reconcile-monitor` inline.
;; =============================================================================

(defn- resolve-fn-id-by-name
  "Best-effort fn-name → fn-id lookup. Seeder path swallows any
   ExceptionInfo since this runs during early startup where
   storage state may be incomplete."
  [storage fn-name]
  (fn-lookup/query-fn-id-by-name storage fn-name true))


(defn- seed-package-services!
  "Idempotently materialise each entry from `(:seeded-services
   packages)` as a `:service` row.

   Service id is deterministic on `(package-name, service-name)` via
   `ids/seeded-service-id` so re-running the seeder lands on the same
   row — letting an admin's `:enabled?` toggle survive restart.

   If the `:fn-name` doesn't resolve at boot (e.g. package didn't load
   the fn for some reason), the entry is logged and skipped — the
   admin can re-create the fn and the next boot will pick it up.

   `:cardinality` is BACKFILLED onto an existing row when that row's
   value is nil — i.e. it was written before the field existed. Without
   this, upgrading a live deployment would leave the seeded
   `:web-server` row at nil ≡ `:singleton`, and only one pod would ever
   bind a port. Backfill only touches nil, so an admin who deliberately
   set a cardinality keeps it, same as `:enabled?`.

   Returns a vector of `{:id :seeded? :fn-name :package-name}` maps
   for logging."
  [storage packages]
  (vec
    (keep
      (fn [{:keys [package-name name fn-name enabled? restart-policy cardinality]}]
        (let [svc-id (ids/seeded-service-id package-name name)
              fn-id (resolve-fn-id-by-name storage (clojure.core/name fn-name))
              existing (first (sp/query-entities storage :service {:id svc-id}))]
          (cond
            (nil? fn-id)
            (do (log/warn "seeded service skipped — fn-name didn't resolve"
                          {:package package-name :service name :fn-name fn-name})
                nil)

            existing
            ;; Idempotent: existing row may have a different
            ;; :enabled? (admin toggled it) — don't overwrite.
            (do
              (when (and cardinality (nil? (:cardinality existing)))
                (log/info "backfilling :cardinality on pre-existing service row"
                          {:service-id svc-id :cardinality cardinality})
                (sp/update-entity storage :service svc-id {:cardinality cardinality}))
              {:id svc-id :seeded? false :fn-name fn-name :package-name package-name})

            :else
            (try
              (sp/create-entity storage :service
                                {:id svc-id
                                 :fn-id fn-id
                                 :enabled? (if (false? enabled?) false true)
                                 :restart-policy (or restart-policy :always)
                                 :cardinality (or cardinality :singleton)})
              {:id svc-id :seeded? true :fn-name fn-name :package-name package-name}
              (catch Exception e
                ;; Race window: two pods may seed concurrently with
                ;; the same deterministic id. One wins the unique-
                ;; constraint, the other gets PG's `duplicate key`
                ;; — treat as success (the row exists, that's what
                ;; we wanted).
                (if (re-find #"duplicate key|unique constraint" (or (ex-message e) ""))
                  {:id svc-id :seeded? false :fn-name fn-name :package-name package-name}
                  (throw e)))))))
      (pkg/get-seeded-services packages))))


(defn- invalidate-from-notify!
  "Apply a sibling pod's `fn:invalidate` event to THIS pod's caches.

   Mirrors the local write path in `crud.entities/invalidate!`: the
   branch the write landed on, plus every cached branch that inherits
   from it. When the payload carries no branch-id (an older build's
   emitter) fall back to the base ctx, which is what this callback did
   before branch-ids rode along.

   Empty `id` ≡ full clear; a populated id is one delta seed."
  [ctx id branch-id]
  (let [seeds (when-not (str/blank? id) [(java.util.UUID/fromString id)])
        router (br/current-router)
        branch-uuid (when-not (str/blank? branch-id)
                      (java.util.UUID/fromString branch-id))]
    (if (and router branch-uuid)
      (do (br/invalidate-cached-branch! router branch-uuid seeds)
          (br/invalidate-affected-ctxs! router branch-uuid seeds))
      (if seeds
        (exec-ctx/invalidate-graph-cache! ctx seeds)
        (exec-ctx/invalidate-graph-cache! ctx)))))


(defn- on-notify
  "Multi-purpose listener callback:

   - `:service` events → trigger a reconcile pass (managed-service
     ownership re-evaluation).
   - `:fn :invalidate` events → invalidate the affected fn-id on every
     cached ctx that can see the write (see `invalidate-from-notify!`).
     Pod A writes 5 binding rows under one request → emits 5 events →
     sibling pod B fires 5 invalidates. Each is cheap (delta path on the
     reverse-deps index).
   - `:execution :cancel` events → cancel the execution if THIS pod is
     the one running it. Every pod gets the event; at most one owns the
     future, the rest no-op."
  [ctx]
  (fn [{:keys [kind op id branch-id] :as event}]
    (try
      (case kind
        ;; Retry-free: a start failure isn't retried inline (which would sleep
        ;; under `reconcile-monitor` and block the listener thread + every
        ;; other reconcile trigger) — the periodic tick reconverges instead.
        :service   (recon/reconcile-once! ctx recon/running {:max-retries 0 :backoff-ms 0})
        :fn        (when (= op :invalidate)
                     (invalidate-from-notify! ctx id branch-id))
        :execution (when (and (= op :cancel) (not (str/blank? id)))
                     (persist/cancel-local! (java.util.UUID/fromString id)))
        nil)
      (catch Exception e
        (log/error e "NOTIFY dispatch threw" {:event event})))))


(defn- start-reconcile-ticker!
  "Spawn a scheduled tick that re-runs `reconcile-once!` every `period-ms`,
   retry-free (a failed start reconverges on the next tick rather than
   sleeping under `reconcile-monitor`). Returns the scheduler for halt."
  [ctx period-ms]
  (let [scheduler (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
    (log/info "Starting service reconcile ticker — period" period-ms "ms")
    (java.util.concurrent.ScheduledExecutorService/.scheduleAtFixedRate
      scheduler
      ^Runnable (fn []
                  (try (recon/reconcile-once! ctx recon/running {:max-retries 0 :backoff-ms 0})
                       (catch Exception e
                         (log/warn e "periodic reconcile failed"))))
      period-ms period-ms
      java.util.concurrent.TimeUnit/MILLISECONDS)
    scheduler))


(defmethod ig/init-key :exec/service-reconciler
  [_ {:keys [context packages notify-listener service-locks reconcile-period-ms]}]
  (log/info "Starting service reconciler...")
  ;; Production singleton — clear any stale state from a previous run
  ;; (e.g. test fixture or REPL reset) before reconciling.
  (reset! recon/running {})
  (let [storage (:storage context)
        ;; Thread the lock HOLDER through ctx so reconcile-once! can use it
        ;; without changing its arglist contract. The holder (not a bare
        ;; connection) is what lets a pass reconnect a dropped lock conn +
        ;; re-assert ownership.
        ctx (cond-> context
              service-locks (assoc :service-locks-holder service-locks))
        seeded (seed-package-services! storage packages)
        new-seeds (filterv :seeded? seeded)
        enabled-services (sp/query-entities storage :service {:enabled? true})]
    (when (seq new-seeds)
      (log/info "Seeded" (count new-seeds) "package-declared :service rows"
                {:rows (mapv (fn [s] (select-keys s [:fn-name :package-name])) new-seeds)}))
    (when (seq enabled-services)
      (log/info "Reconciling" (count enabled-services) "enabled :service rows")
      (recon/reconcile-once! ctx recon/running))
    ;; Hook into the NOTIFY transport — reconcile when a sibling pod
    ;; mutates `:service`. Callback closes over the lock-augmented
    ;; ctx so per-NOTIFY reconciles use the same advisory-lock path
    ;; as boot.
    (let [callback (when notify-listener
                     (pg-notify/register! notify-listener (on-notify ctx)))
          ticker (start-reconcile-ticker! ctx (or reconcile-period-ms 15000))]
      {:running recon/running
       :context ctx
       :notify-listener notify-listener
       :notify-callback callback
       :ticker ticker})))


(defmethod ig/halt-key! :exec/service-reconciler
  [_ {:keys [running notify-listener notify-callback ticker]}]
  (log/info "Stopping service reconciler...")
  (when ticker
    (java.util.concurrent.ExecutorService/.shutdown ^java.util.concurrent.ExecutorService ticker)
    (try (java.util.concurrent.ExecutorService/.awaitTermination
           ^java.util.concurrent.ExecutorService ticker 5 java.util.concurrent.TimeUnit/SECONDS)
         (catch InterruptedException _ nil)))
  (when (and notify-listener notify-callback)
    (pg-notify/unregister! notify-listener notify-callback))
  (when running (recon/stop-all! running))
  (log/info "Service reconciler stopped"))


(defmethod ig/suspend-key! :exec/service-reconciler [_ {:keys [running]}]
  ;; Same as halt — services don't have a suspend state distinct from stop.
  (when running (recon/stop-all! running)))


;; =============================================================================
;; Fleet placement controller (docs/FLEET_RFC.md §6.3, Phase 2)
;;
;; A leader-locked periodic tick that reads live cell weights + the executor set
;; and applies the pure `control-loop/plan-tick` decision — placing new cells and
;; rebalancing sustained imbalance via the directed cell-command transport. Every
;; fleet pod runs the component; a single advisory lock (its own dedicated
;; connection, distinct from the reconciler's) elects ONE controller, so two
;; pods can't fight (§6.3 Safety). Inert unless this pod is a fleet member
;; (`GRAPHDEN_EXECUTOR_ID` set) — single-tenant / self-hosted never starts it.
;; =============================================================================

(def ^:private fleet-controller-lock-id
  "Fixed advisory-lock key that elects the single fleet controller — a constant
   so every pod contends for the SAME lock."
  #uuid "f1ee7c07-0000-0000-0000-000000000001")


(defn- fleet-controller-opts
  "Controller knobs from env (read directly, like the other fleet vars):
   `:sustain-ticks` (imbalance must persist this many ticks before a move),
   `:min-improvement` (magnitude floor), `:max-moves` (per-tick cap),
   `:w-overlap` (overlap-accounting weight — > 0 co-locates code-sharing cells;
   default 0 keeps pure load-balancing, so overlap is strictly opt-in)."
  []
  {:sustain-ticks (or (some-> (System/getenv "GRAPHDEN_FLEET_SUSTAIN_TICKS") parse-long) 3)
   :min-improvement (or (some-> (System/getenv "GRAPHDEN_FLEET_MIN_IMPROVEMENT") parse-double) 0.0)
   :max-moves (or (some-> (System/getenv "GRAPHDEN_FLEET_MAX_MOVES") parse-long) Integer/MAX_VALUE)
   :w-overlap (or (some-> (System/getenv "GRAPHDEN_FLEET_OVERLAP_WEIGHT") parse-double) 0.0)})


(defn- fleet-controller-tick!
  "One control pass, leader-gated. Re-asserts the advisory lock (re-acquiring
   after a lock-conn reconnect, or failing if a sibling took over); only the
   holder ticks. A non-leader resets its streak so a failover starts clean."
  [ctx holder state-atom opts]
  (try
    (pg-lock/ensure-live! holder)
    (if (pg-lock/try-lock! (pg-lock/holder-conn holder) fleet-controller-lock-id)
      (let [env {:storage (:storage ctx)
                 :forward-deps (:forward-deps (some-> (:compile-deps ctx) deref))
                 :executors (fleet-discovery/fleet-executors)
                 :move-fn (fn [cmd] (fleet-command/execute-move! ctx cmd))}
            decision (fleet-loop/run-tick! env @state-atom opts)]
        (reset! state-atom (:state decision))
        (when (or (seq (:moves decision)) (seq (:initial-placements decision)))
          (log/info "Fleet controller applied placement"
                    {:initial (count (:initial-placements decision))
                     :moves (count (:moves decision))
                     :imbalance (:current-imbalance decision)})))
      (reset! state-atom {}))
    (catch Exception e
      (log/warn e "Fleet controller tick failed — will retry next tick"))))


(defmethod ig/init-key :exec/fleet-controller
  [_ {:keys [context pg-opts enabled? period-ms]}]
  ;; `enabled?` is the fleet identity (`GRAPHDEN_EXECUTOR_ID`) — a non-blank
  ;; string on a fleet member, nil/false otherwise. Guard against a literal
  ;; `false` (whose `(str false)` = "false" is non-blank) reading as enabled.
  (if-not (and enabled? (not (str/blank? (str enabled?))))
    (do (log/info "Fleet controller disabled (not a fleet member)") nil)
    (let [holder (pg-lock/create-lock-holder pg-opts)
          state-atom (atom {})
          opts (fleet-controller-opts)
          period (or period-ms 30000)
          scheduler (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
      (log/info "Starting fleet controller — period" period "ms," opts)
      (java.util.concurrent.ScheduledExecutorService/.scheduleAtFixedRate
        scheduler
        ^Runnable (fn [] (fleet-controller-tick! context holder state-atom opts))
        period period java.util.concurrent.TimeUnit/MILLISECONDS)
      {:scheduler scheduler :holder holder :state state-atom})))


(defmethod ig/halt-key! :exec/fleet-controller [_ component]
  (when component
    (let [{:keys [scheduler holder]} component]
      (when scheduler
        (java.util.concurrent.ExecutorService/.shutdown
          ^java.util.concurrent.ExecutorService scheduler)
        (try (java.util.concurrent.ExecutorService/.awaitTermination
               ^java.util.concurrent.ExecutorService scheduler 5 java.util.concurrent.TimeUnit/SECONDS)
             (catch InterruptedException _ nil)))
      (when holder (pg-lock/close-holder! holder))
      (log/info "Fleet controller stopped"))))


;; =============================================================================
;; Execution cleanup scheduler
;;
;; Runs hourly. Sweeps `:fn-execution` rows whose status + age exceeds
;; the per-status TTL (see `crud.fn-execution` ns-docstring). Single
;; scheduled-executor thread; halt-key shuts it down + awaits in-
;; flight work.
;; =============================================================================

(defn- one-hour
  []
  (* 60 60 1000))


(defn- as-instant
  "Storage codec returns timestamptz columns in different shapes
   depending on backend / driver / clj-reader (jdbc.next default is
   `java.time.Instant`; the pg driver returns `java.sql.Timestamp` for
   some configs; the EDN reader walks `#inst` literals as
   `java.util.Date`; serialised forms come back as ISO-8601 or
   SQL-style strings). Normalise to `java.time.Instant`."
  [x]
  (cond
    (nil? x) nil
    (instance? java.time.Instant x) x
    ;; Both java.sql.Timestamp and java.util.Date expose toInstant().
    (instance? java.util.Date x) (java.util.Date/.toInstant x)
    :else
    (let [s (str x)]
      (try (java.time.Instant/parse s)
           (catch java.time.format.DateTimeParseException _
             ;; SQL-style `2026-05-21 12:00:00.0` — rewrite to ISO.
             (let [iso (-> s
                           (str/replace #" " "T")
                           ;; drop trailing fractional-second zero pad
                           ;; that may not parse without a Z
                           (str/replace #"\.0+$" "")
                           (str "Z"))]
               (java.time.Instant/parse iso)))))))


(defn sweep-executions!
  "Delete `:fn-execution` rows past TTL; mark zombie `:pending` rows
   older than 1h as `:cancelled` so the row stops blocking the
   polling client.

   `now` is an injectable `Instant` for deterministic tests; defaults
   to wall-clock when omitted. Public (no `-` suffix) so tests can
   exercise it without `#'`-style var lookups."
  ([storage]
   (sweep-executions! storage (java.time.Instant/now)))
  ([storage now]
   (let [now-ms (java.time.Instant/.toEpochMilli now)
         ;; Per-status TTLs (in ms).
         ttl-ms {"succeeded" (* 7  24 60 60 1000)
                 "failed"    (* 30 24 60 60 1000)
                 "cancelled" (* 7  24 60 60 1000)}
         zombie-ms (one-hour)
         all (sp/query-entities storage :fn-execution {})
         age-of (fn [row stamp-key]
                  (when-let [t (as-instant (get row stamp-key))]
                    (- now-ms (java.time.Instant/.toEpochMilli t))))
         ;; storage may return :status as the enum keyword `:succeeded`
         ;; OR the bare string "succeeded" depending on codec; `name`
         ;; normalises to the bare token both ways.
         status-str (fn [row]
                      (let [s (:status row)]
                        (cond
                          (keyword? s) (name s)
                          (string? s) s
                          :else (str s))))]
     (doseq [row all]
       (let [status (status-str row)]
         (cond
           ;; Zombie sweep: pending > 1h gets force-cancelled so the
           ;; polling client stops waiting forever for a future that
           ;; died with the JVM.
           (and (= "pending" status)
                (when-let [a (age-of row :started-at)] (> a zombie-ms)))
           (sp/update-entity storage :fn-execution (:id row)
                             {:status :cancelled
                              :finished-at now
                              :error "zombie: pending > 1h, swept"})

           ;; TTL sweep: delete rows past per-status retention.
           (when-let [limit (get ttl-ms status)]
             (when-let [a (age-of row :finished-at)] (> a limit)))
           (sp/delete-entity storage :fn-execution (:id row))))))))


(defmethod ig/init-key :exec/cleanup-scheduler
  [_ {:keys [context period-ms]}]
  (let [storage (:storage context)
        period (or period-ms (one-hour))
        scheduler (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
    (log/info "Starting execution cleanup scheduler — period" period "ms")
    (java.util.concurrent.ScheduledExecutorService/.scheduleAtFixedRate
      scheduler
      ^Runnable (fn []
                  ;; Catch Exception (not Throwable) — Errors should
                  ;; propagate, the scheduler swallowing them is fine
                  ;; for OOM / StackOverflow cases.
                  (try (sweep-executions! storage)
                       (catch Exception e
                         (log/warn e "execution-cleanup sweep failed"))))
      period period
      java.util.concurrent.TimeUnit/MILLISECONDS)
    scheduler))


(defmethod ig/halt-key! :exec/cleanup-scheduler
  [_ ^java.util.concurrent.ScheduledExecutorService scheduler]
  (when scheduler
    (log/info "Stopping execution cleanup scheduler...")
    (java.util.concurrent.ExecutorService/.shutdown scheduler)
    (try (java.util.concurrent.ExecutorService/.awaitTermination
           scheduler 5 java.util.concurrent.TimeUnit/SECONDS)
         (catch InterruptedException _ nil))))

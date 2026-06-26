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
    [graphden.clients.vault :as vault]
    [graphden.crud.fn-execution.lookup :as fn-lookup]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry-core]
    [graphden.executor.registry.interface :as registry]
    [graphden.packages.loader :as pkg]
    [graphden.packages.records :as records]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as records-parse]
    [graphden.schema.executions.schema :as es]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.packages.schema :as pkgs]
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
    [graphden.types.check :as types-check]
    [graphden.types.check.narrowing :as types-narrowing]
    [graphden.types.core :as types]
    [graphden.versioning.storage.core :as vs]
    [integrant.core :as ig]))


;; =============================================================================
;; Schema (pure, no lifecycle)
;; =============================================================================

(defmethod ig/init-key :db/schema [_ _]
  (log/info "Building schema...")
  (-> (mds/create-builder)
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
      (ds/build)))


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


(defmethod ig/init-key :db/postgres [_ opts]
  (init-storage! "PostgreSQL" postgres/create-storage opts))


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
  {:connection (pg-lock/create-lock-conn pg-opts)})


(defmethod ig/halt-key! :db/service-locks [_ {:keys [connection]}]
  (log/info "Releasing service-locks + closing connection...")
  ;; Release-all is best-effort during shutdown — we still need to
  ;; close the connection even if a release fails. But silencing
  ;; the failure entirely would hide DB-side state-leak (advisory
  ;; locks persisting on the connection until the session truly
  ;; dies). Log so dashboards see shutdown-time PG drift.
  (try (pg-lock/release-all! connection)
       (catch Exception e
         (log/warn e "service-locks release-all failed during halt — continuing close")))
  (pg-lock/close-lock-conn! connection))


;; =============================================================================
;; Versioned Storage Decorator
;; =============================================================================

(defmethod ig/init-key :db/versioned [_ {:keys [base-storage]}]
  (log/info "Enabling versioning...")
  (let [versioned (vs/wrap-with-versioning base-storage)]
    (log/info "Branch:" (vs/current-branch-id versioned))
    versioned))


;; No halt needed - base storage handles cleanup


;; =============================================================================
;; Package Loading
;; =============================================================================

(defmethod ig/init-key :app/packages [_ {:keys [package-names]}]
  (log/info "Loading packages:" package-names)
  (let [packages (pkg/load-packages package-names)]
    (log/info "Packages loaded:" (count (:packages packages)) "packages,"
              (count (:base-fn-defs packages)) "base-fns,"
              (count (:fn-defs packages)) "fn-defs")
    packages))


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
                     [(:name fd) body])
        try-once
        (fn [pending]
          ;; Returns the subset of [name body] pairs whose validation
          ;; still fails — caller iterates until fixed point.
          (reduce
            (fn [still-pending [nm body]]
              (try (types/register-type-alias! nm body)
                   still-pending
                   (catch Exception _
                     (conj still-pending [nm body]))))
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

(defmethod ig/init-key :exec/context
  [_ {:keys [storage vault-client pg-storage base-fns]}]
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
        ctx-opts (cond-> {:storage storage}
                   (and base-fns (:base-fns base-fns))
                   (assoc :base-fns (:base-fns base-fns)))]
    (cond-> (-> (exec/create-context ctx-opts)
                (assoc :notify-emitter emitter))
      vault-client (assoc :vault vault-client))))


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
;; `recon/reconcile-once!` after writing) and from this init-key.
;; Out-of-band DB edits (psql, other tools) won't be picked up until
;; restart — acceptable for the admin workflow today. Periodic poll
;; is on the Phase-2 roadmap (next sub-feature).
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

   Returns a vector of `{:id :seeded? :fn-name :package-name}` maps
   for logging."
  [storage packages]
  (vec
    (keep
      (fn [{:keys [package-name name fn-name enabled? restart-policy]}]
        (let [svc-id (ids/seeded-service-id package-name name)
              fn-id (resolve-fn-id-by-name storage (clojure.core/name fn-name))]
          (cond
            (nil? fn-id)
            (do (log/warn "seeded service skipped — fn-name didn't resolve"
                          {:package package-name :service name :fn-name fn-name})
                nil)

            (seq (sp/query-entities storage :service {:id svc-id}))
            ;; Idempotent: existing row may have a different
            ;; :enabled? (admin toggled it) — don't overwrite.
            {:id svc-id :seeded? false :fn-name fn-name :package-name package-name}

            :else
            (try
              (sp/create-entity storage :service
                                {:id svc-id
                                 :fn-id fn-id
                                 :enabled? (if (false? enabled?) false true)
                                 :restart-policy (or restart-policy :always)})
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


(defn- on-notify
  "Multi-purpose listener callback:

   - `:service` events → trigger a reconcile pass (managed-service
     ownership re-evaluation).
   - `:fn :invalidate` events → drop the affected fn-id from the
     compiled cache; empty id ≡ full clear (mirrors the local
     `(invalidate-graph-cache! ctx)` no-seed path). A populated id
     is one delta seed; pod A writes 5 binding rows under one
     request → emits 5 events → sibling pod B fires 5 invalidates.
     Each is cheap (delta path on the reverse-deps index)."
  [ctx]
  (fn [{:keys [kind op id] :as event}]
    (try
      (case kind
        :service (recon/reconcile-once! ctx recon/running)
        :fn      (when (= op :invalidate)
                   (if (or (nil? id) (= "" id))
                     (exec-ctx/invalidate-graph-cache! ctx)
                     (exec-ctx/invalidate-graph-cache!
                       ctx [(java.util.UUID/fromString id)])))
        nil)
      (catch Exception e
        (log/error e "NOTIFY dispatch threw" {:event event})))))


(defmethod ig/init-key :exec/service-reconciler
  [_ {:keys [context packages notify-listener service-locks]}]
  (log/info "Starting service reconciler...")
  ;; Production singleton — clear any stale state from a previous run
  ;; (e.g. test fixture or REPL reset) before reconciling.
  (reset! recon/running {})
  (let [storage (:storage context)
        ;; Thread the lock connection through ctx so reconcile-once!
        ;; can use it without changing its arglist contract.
        ctx (cond-> context
              service-locks (assoc :service-locks-connection (:connection service-locks)))
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
                     (pg-notify/register! notify-listener (on-notify ctx)))]
      {:running recon/running
       :context ctx
       :notify-listener notify-listener
       :notify-callback callback})))


(defmethod ig/halt-key! :exec/service-reconciler
  [_ {:keys [running notify-listener notify-callback]}]
  (log/info "Stopping service reconciler...")
  (when (and notify-listener notify-callback)
    (pg-notify/unregister! notify-listener notify-callback))
  (when running (recon/stop-all! running))
  (log/info "Service reconciler stopped"))


(defmethod ig/suspend-key! :exec/service-reconciler [_ {:keys [running]}]
  ;; Same as halt — services don't have a suspend state distinct from stop.
  (when running (recon/stop-all! running)))


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

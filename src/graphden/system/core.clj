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
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry-core]
    [graphden.executor.registry.interface :as registry]
    [graphden.packages.loader :as pkg]
    [graphden.packages.records :as records]
    [graphden.schema.executions.schema :as es]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.services.schema :as svcs]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.services.reconciler :as recon]
    [graphden.storage.postgres.core :as postgres]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.system.demo-branches :as demo]
    [graphden.types.check :as types-check]
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

(defn- compute-all-fn-name-ids
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


(defn- register-type-aliases!
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


(defmethod ig/init-key :exec/base-fns [_ {:keys [storage packages]}]
  (log/info "Registering base functions...")
  (let [base-fn-defs (:base-fn-defs packages)
        ;; Sync namespace entities first (creates ns hierarchy in DB)
        ns-id-map (pkg/sync-namespaces! storage (:namespaces packages)
                                        (:ns-descriptions packages))
        ;; Full name→id map covering base-fns + composed fn-defs so
        ;; either sync can resolve a reference into the other set.
        all-name->id (compute-all-fn-name-ids packages)]
    (registry/sync-primitives! storage)
    ;; Register refinement type-aliases BEFORE base-fn rich-type
    ;; recording so `:http-server :args {:port :port}` stores the
    ;; structural `[:refine :int …]` form, not the bare keyword.
    (register-type-aliases! (:fn-defs packages))
    (registry/register-base-fns! base-fn-defs)
    (registry/sync-defs-to-storage! storage base-fn-defs ns-id-map all-name->id)
    (log/info "Base functions registered:" (count base-fn-defs))
    {:status :registered
     :ns-id-map ns-id-map
     :all-name->id all-name->id}))


;; No halt needed - registry is global state


;; =============================================================================
;; Fn Entities
;; =============================================================================

(defmethod ig/init-key :exec/fn-entities [_ {:keys [storage packages base-fns]}]
  (log/info "Creating fn entities...")
  (let [fn-defs (:fn-defs packages)
        ns-id-map (or (:ns-id-map base-fns) {})
        extra-name->id (or (:all-name->id base-fns) {})
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
             (catch Exception _))))
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
    (let [failures (atom 0)]
      (doseq [fd (deps/topological-sort fn-defs)]
        (try (types-check/check-fn-def! fd)
             (catch Exception e
               (swap! failures inc)
               (log/debug "Type-check failed for fn-def" (:name fd) "—"
                          (ex-message e)))))
      (when (pos? @failures)
        (log/warn "Type-check sweep: " @failures
                  "fn-defs failed (DEBUG-logged) — runtime unaffected,"
                  " editor effect/return strips may be missing for those names —"
                  " docs/TYPE_CHECK_BACKLOG.md")))
    (log/info "Fn entities created:" (count fns))
    fns))


;; =============================================================================
;; Vault client (OpenBao / Vault KV v2)
;; =============================================================================
;;
;; Infrastructure-level secrets handle. The :vault-get base-fn pulls
;; this off the executor context to talk to KV v2. Address + token
;; live in `system-*.edn` (and ultimately env), NEVER exposed to the
;; user fn-graph — that's the whole point of routing user secrets
;; through `:vault-get` instead of `:env`.
;;
;; Optional: when address is blank, the key returns nil and
;; `:vault-get` raises a clear "vault not configured" error on first
;; use. Lets tests skip the openbao container.

(defmethod ig/init-key :vault/client [_ {:keys [address token]}]
  (if (and (string? address) (not (str/blank? address)))
    (do (log/info "Vault client configured for" address)
        {:address address :token token})
    (do (log/info "Vault client disabled (no :address) — :vault-get will throw on use")
        nil)))


;; =============================================================================
;; Executor Context
;; =============================================================================

(defmethod ig/init-key :exec/context [_ {:keys [storage vault-client]}]
  (log/info "Creating executor context...")
  ;; `assoc` (not the constructor's named opts) — the ExecutionContext
  ;; record stays narrow; vault rides on the extra-key surface
  ;; alongside `:compiled-templates`. Impls grab it via `(:vault ctx)`.
  (cond-> (exec/create-context {:storage storage})
    vault-client (assoc :vault vault-client)))


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
;; ctx — same pattern as `services.reconciler/legacy-handle`.

(defmethod ig/init-key :exec/branch-router [_ {:keys [context]}]
  (log/info "Initialising branch router...")
  (let [router (br/create-router context "_app-ring-response")]
    (br/set-active-router! router)
    router))


(defmethod ig/halt-key! :exec/branch-router [_ _router]
  (br/clear-active-router!))


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

(defn- demo-branches-enabled?
  "Parse the `:enabled?` flag from the system config. Accepts a few
   wire-friendly shapes: the EDN literal `true`, or any of
   `\"1\" \"true\" \"yes\" \"on\"` (case-insensitive) when the value
   came through an env var. Anything else (including the empty
   string from an unset env in `system-prod.edn`) means OFF."
  [raw]
  (cond
    (true? raw)                  true
    (or (false? raw) (nil? raw)) false
    (string? raw)                (contains? #{"1" "true" "yes" "on"}
                                            (str/lower-case raw))
    :else                        (boolean raw)))


(defmethod ig/init-key :exec/demo-branches [_ {:keys [context enabled? branches]}]
  (let [on? (demo-branches-enabled? enabled?)]
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
;; On start: read enabled :service rows; if none, fall back to the
;; package-declared :startup-fn (single-shot, no supervision). When
;; service rows exist they take priority — the legacy :startup-fn is
;; ignored so an admin who switched to declarative services doesn't
;; get a duplicate web-server bound to the same port.
;;
;; On halt: stop every running service (including the legacy fallback).
;;
;; Phase 1 is NOT periodic-poll: the in-process atom is only modified
;; from CRUD endpoints (which call `recon/reconcile-once!` after
;; writing) and from this init-key. Out-of-band DB edits (psql, other
;; tools) won't be picked up until restart — acceptable for the admin
;; workflow on Phase 1. Periodic poll arrives with multi-pod (Phase 3).
;; =============================================================================

(defn- resolve-fn-id-by-name
  "Best-effort fn-name → fn-id lookup. Storage codec ambiguity (text
   vs enum-tagged) is handled by trying both forms — same pattern as
   `fn-execution.lookup/query-fn-by-name` but inlined here to avoid
   coupling the legacy-fallback path to crud machinery."
  [storage fn-name]
  (letfn [(try-one
            [v]
            (try (some-> (first (sp/query-entities storage :fn {:name v})) :id)
                 (catch clojure.lang.ExceptionInfo _ nil)))]
    (or (try-one fn-name)
        (try-one (keyword fn-name)))))


(defn- start-legacy-fallback!
  "When no :service rows exist, honour the package-declared
   `:startup-fn` (single-shot, no supervisor). Returns the fn's
   return value (a stopper for web-server-shape fns).

   Also stashes `{:fn-id :stopper}` under `recon/legacy-handle` so:
   - `validate-execute`'s `already-running-as-service?` can reject
     ad-hoc Run on the same fn (clicking ▶ on the legacy web-server
     would try to re-bind its port)
   - `reconcile-once!`'s displacement step can stop the fallback
     before starting a matching managed service"
  [context packages]
  (when-let [startup-fn-name (:startup-fn packages)]
    (log/warn "no :service rows in DB — falling back to package :startup-fn"
              startup-fn-name
              "(declare a :service row to enable supervision; this code path goes away in Phase 2)")
    (let [fn-id (resolve-fn-id-by-name (:storage context) (name startup-fn-name))
          stopper (cr/execute-by-name context (name startup-fn-name) nil)]
      (reset! recon/legacy-handle {:fn-id fn-id :stopper stopper})
      stopper)))


(defmethod ig/init-key :exec/service-reconciler [_ {:keys [context packages]}]
  (log/info "Starting service reconciler...")
  ;; Production singletons — clear any stale state from a previous run
  ;; (e.g. test fixture or REPL reset) before reconciling.
  (reset! recon/running {})
  (reset! recon/legacy-handle nil)
  (let [storage (:storage context)
        enabled-services (sp/query-entities storage :service {:enabled? true})
        ;; Legacy fallback lives OUTSIDE the reconciler's atom — if it
        ;; was in there, every `/api/services/reconcile` would diff
        ;; against the empty DB and stop the web-server. Stored in the
        ;; component map; halt-key! drains it alongside `stop-all!`.
        ;; Once an admin declares a real :service for :web-server, the
        ;; bind will conflict (legacy still on the port). Phase 2's
        ;; packages-based registration will retire this path entirely.
        legacy-stopper (when (empty? enabled-services)
                         (start-legacy-fallback! context packages))]
    (when (seq enabled-services)
      (log/info "reconciling" (count enabled-services) "enabled :service rows")
      (recon/reconcile-once! context recon/running))
    {:running recon/running
     :context context
     :legacy-stopper legacy-stopper}))


(defn- stop-legacy!
  "Defensive: legacy-stopper is whatever the startup-fn returned —
   usually a thunk, but we don't enforce it. Log unfamiliar shapes
   instead of throwing, mirroring `reconciler/stop-service!`."
  [legacy-stopper]
  (try
    (cond
      (fn? legacy-stopper)  (do (log/info "stopping legacy fallback")
                                (legacy-stopper))
      (nil? legacy-stopper) nil
      :else                 (log/warn "legacy fallback stopper is non-callable"
                                      (type legacy-stopper)))
    (catch Exception e
      (log/error e "legacy fallback stopper threw"))))


(defmethod ig/halt-key! :exec/service-reconciler [_ {:keys [running legacy-stopper]}]
  (log/info "Stopping service reconciler...")
  (when running (recon/stop-all! running))
  ;; Two possible stoppers — `:legacy-stopper` in the component map
  ;; (set by init-key) and the `:stopper` in `recon/legacy-handle`
  ;; (set by start-legacy-fallback!). They're the same function but
  ;; the handle may have been cleared by `maybe-displace-legacy!`
  ;; already — calling the component-map one is the authoritative path.
  (stop-legacy! legacy-stopper)
  (reset! recon/legacy-handle nil)
  (log/info "Service reconciler stopped"))


(defmethod ig/suspend-key! :exec/service-reconciler [_ component]
  ;; Same as halt — services don't have a suspend state distinct from
  ;; stop in Phase 1.
  (when-let [running (:running component)] (recon/stop-all! running))
  (stop-legacy! (:legacy-stopper component)))


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

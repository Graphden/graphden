(ns graphden.system.init.storage
  "Integrant init-keys for the storage stack: schema build, Postgres
   pool, LISTEN/NOTIFY transport, SSE relay, service-lock connection,
   and the VersionedStorage decorator."
  (:require
    [clojure.tools.logging :as log]
    [graphden.schema.executions.schema :as es]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.packages.schema :as pkgs]
    [graphden.schema.placement.schema :as placement]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.services.schema :as svcs]
    [graphden.schema.stats.schema :as stats]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.advisory-lock :as pg-lock]
    [graphden.storage.postgres.core :as postgres]
    [graphden.storage.postgres.notify :as pg-notify]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.sse :as sse]
    [graphden.versioning.storage.core :as vs]
    [integrant.core :as ig]))


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
                 ;; Usage rollups — pre-aggregated per-(hour, org, fn, status)
                 ;; execution counters (Phase C1 observability). Non-versioned;
                 ;; mutated only by the upsert-increment in
                 ;; crud.fn-execution.stats.
                 (stats/extend-builder)
                 ;; Fleet placement map `(org, entry-fn-id) → executor-id`
                 ;; (docs/FLEET_RFC.md §6.1). Refs :fn (logical). Non-versioned —
                 ;; control-plane routing state that mutates in place.
                 (placement/extend-builder))]
    ;; Addon schema-extension seam (docs/TENANCY_SEAM.md § Storage & schema
    ;; seams): each `extensions`
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

;; Tenancy storage seam (docs/TENANCY_SEAM.md § Storage & schema seams).
;; Core wires an IDENTITY
;; passthrough of the base storage; the tenancy addon overrides this key
;; with an `OrgScopedStorage` decorator that injects the per-request
;; `org-id` filter. Placement is deliberate: it sits BENEATH versioning
;; (`Versioned(OrgScoped(Postgres))`), so the branch-router's `vs/unwrap`
;; (which strips the VersionedStorage to rebuild a per-branch view) lands
;; on the OrgScoped layer and the tenant filter survives — closing the
;; `vs/unwrap` leak. (RLS is still the belt-and-
;; suspenders second layer.)
(defmethod ig/init-key :app/storage [_ {:keys [base]}]
  base)


(defmethod ig/init-key :db/versioned [_ {:keys [base-storage]}]
  (log/info "Enabling versioning...")
  (let [versioned (vs/wrap-with-versioning base-storage)]
    (log/info "Branch:" (vs/current-branch-id versioned))
    versioned))


;; No halt needed - base storage handles cleanup

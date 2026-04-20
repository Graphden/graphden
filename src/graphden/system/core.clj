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
   :http/server      → [:exec/context, :exec/fn-entities, :app/packages]"
  (:require
    [clojure.tools.logging :as log]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.interface :as registry]
    [graphden.packages.loader :as pkg]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as postgres]
    [graphden.storage.protocol.core :as sp]
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

(defmethod ig/init-key :exec/base-fns [_ {:keys [storage packages]}]
  (log/info "Registering base functions...")
  (let [base-fn-defs (:base-fn-defs packages)
        ;; Sync namespace entities first (creates ns hierarchy in DB)
        ns-id-map (pkg/sync-namespaces! storage (:namespaces packages))]
    (registry/register-base-fns! base-fn-defs)
    (registry/sync-defs-to-storage! storage base-fn-defs ns-id-map)
    (log/info "Base functions registered:" (count base-fn-defs))
    {:status :registered :ns-id-map ns-id-map}))


;; No halt needed - registry is global state


;; =============================================================================
;; Fn Entities
;; =============================================================================

(defmethod ig/init-key :exec/fn-entities [_ {:keys [storage packages base-fns]}]
  (log/info "Creating fn entities...")
  (let [fn-defs (:fn-defs packages)
        ns-id-map (or (:ns-id-map base-fns) {})
        fns (fn-composition/sync-fns-to-storage! storage fn-defs ns-id-map)]
    (log/info "Fn entities created:" (count fns))
    fns))


;; =============================================================================
;; Executor Context
;; =============================================================================

(defmethod ig/init-key :exec/context [_ {:keys [storage max-depth timeout-ms]}]
  (log/info "Creating executor context...")
  (exec/create-context {:storage storage
                        :max-depth (or max-depth 1000)
                        :timeout-ms (or timeout-ms 30000)}))


;; =============================================================================
;; Compiled Registry (compile-at-startup executor)
;; =============================================================================
;;
;; Walks every fn/arg entity in storage and compiles each into a Clojure
;; closure of shape `(fn [all-fns free-args] result)`. Stored in the
;; context's `:compiled-registry` atom for the hot path (HTTP handlers)
;; to bypass the legacy queue entirely.

(defmethod ig/init-key :exec/compiled-registry [_ {:keys [context]}]
  (log/info "Building compiled registry...")
  (let [registry (cr/rebuild! context)]
    (log/info "Compiled registry built:" (count registry) "fns")
    registry))


;; =============================================================================
;; HTTP Server (executed via compile-at-startup registry)
;; =============================================================================

(defmethod ig/init-key :http/server [_ {:keys [context packages port]}]
  (let [startup-fn-name (:startup-fn packages)
        ;; EXECUTOR env var picks which path starts the server:
        ;;   "compiled" (default) → compile-at-startup registry
        ;;   "legacy"             → trampolined queue (for A/B debugging)
        executor-kind (keyword (or (System/getenv "EXECUTOR") "compiled"))]
    (log/info "Starting HTTP server via" startup-fn-name
              "on port" port
              "(executor:" executor-kind ")...")
    (let [server (case executor-kind
                   :legacy   (exec/execute-by-name context (name startup-fn-name) nil)
                   :compiled (cr/execute-by-name context (name startup-fn-name) nil))]
      (log/info "HTTP server started on port" port)
      server)))


(defmethod ig/halt-key! :http/server [_ server]
  (log/info "Stopping HTTP server...")
  (when server
    ;; http-kit server is a function - calling it stops the server
    (server))
  (log/info "HTTP server stopped"))


(defmethod ig/suspend-key! :http/server [_ server]
  ;; Same as halt for HTTP server
  (when server (server)))


(defmethod ig/resume-key :http/server [k opts _ _]
  ;; Restart server with new context
  (ig/init-key k opts))

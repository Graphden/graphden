(ns graphden.system.core
  "Integrant init-key implementations for all system components.

   Component dependency graph:
   :db/schema        → (pure function, no deps)
   :db/age           → [:db/schema]
   :db/versioned     → [:db/age]
   :exec/base-fns    → [:db/versioned]
   :exec/fn-entities → [:db/versioned, :exec/base-fns]
   :exec/context     → [:db/versioned]
   :http/server      → [:exec/context, :exec/fn-entities]"
  (:require
    [clojure.tools.logging :as log]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.interface :as registry]
    [graphden.library.base-fns.core :as bf]
    [graphden.library.base-fns.web.crud :as crud-fns]
    [graphden.library.base-fns.web.graph :as graph-fns]
    [graphden.library.base-fns.web.html :as html-fns]
    [graphden.library.base-fns.web.http-kit :as http-kit-fns]
    [graphden.library.base-fns.web.reitit :as reitit-fns]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.age.core :as age]
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
;; AGE Storage
;; =============================================================================

(defmethod ig/init-key :db/age [_ {:keys [jdbc-url username password pool-size schema]}]
  (log/info "Connecting to Apache AGE:" jdbc-url)
  (let [storage (-> (age/create-storage {:jdbc-url jdbc-url
                                         :username username
                                         :password password
                                         :pool-size pool-size})
                    (sp/initialize-with-cleanup! schema))]
    (vts/seed-traits! storage)
    (log/info "AGE storage initialized")
    storage))


(defmethod ig/halt-key! :db/age [_ storage]
  (log/info "Closing AGE storage...")
  (sp/close storage))


;; =============================================================================
;; PostgreSQL Storage (plain, no AGE extension)
;; =============================================================================

(defmethod ig/init-key :db/postgres [_ {:keys [jdbc-url username password pool-size schema]}]
  (log/info "Connecting to PostgreSQL:" jdbc-url)
  (let [storage (-> (postgres/create-storage {:jdbc-url jdbc-url
                                               :username username
                                               :password password
                                               :pool-size pool-size})
                    (sp/initialize-with-cleanup! schema))]
    (vts/seed-traits! storage)
    (log/info "PostgreSQL storage initialized")
    storage))


(defmethod ig/halt-key! :db/postgres [_ storage]
  (log/info "Closing PostgreSQL storage...")
  (sp/close storage))


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
;; Base Functions Registry
;; =============================================================================

(defmethod ig/init-key :exec/base-fns [_ {:keys [storage]}]
  (log/info "Registering base functions...")
  (registry/initialize-all! storage
                            [bf/all-defs
                             http-kit-fns/all-defs
                             reitit-fns/all-defs
                             html-fns/all-defs
                             graph-fns/all-defs
                             crud-fns/all-defs])
  (log/info "Base functions registered")
  :registered)


;; No halt needed - registry is global state


;; =============================================================================
;; Fn Entities
;; =============================================================================

(defmethod ig/init-key :exec/fn-entities [_ {:keys [storage fn-defs]}]
  (log/info "Creating fn entities...")
  (let [fns (fn-composition/sync-fns-to-storage! storage fn-defs)]
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
;; HTTP Server (executed via graph)
;; =============================================================================

(defmethod ig/init-key :http/server [_ {:keys [context startup-fn-name port]}]
  (log/info "Starting HTTP server via" startup-fn-name "on port" port "...")
  (let [server (exec/execute-by-name context (name startup-fn-name) nil)]
    (log/info "HTTP server started on port" port)
    server))


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


;; =============================================================================
;; Fn Definitions (loaded from var)
;; =============================================================================

(defmethod ig/init-key :app/fn-defs [_ fn-defs]
  ;; fn-defs is already resolved from #var by Aero
  fn-defs)

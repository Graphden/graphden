(ns graphden.datomic-storage.util
  "Utility functions for Datomic storage.

   Contains:
   - Type mapping (DataSchema types to Datomic types)
   - Configuration and timeout handling
   - Attribute naming conventions
   - Connection validation
   - Client configuration validation"
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.storage-protocol.interface :as sp]))


;; === Type mapping ===

(def type->datomic
  "Maps our field types to Datomic value types."
  {:uuid        :db.type/uuid
   :text        :db.type/string
   :int         :db.type/long
   :bool        :db.type/boolean
   :numeric     :db.type/bigdec
   :timestamptz :db.type/instant
   :jsonb       :db.type/string  ; Stored as EDN string
   :bytes       :db.type/bytes})


;; NOTE: Type information (:enum vs :ref, etc.) is preserved through the metadata system,
;; not through Datomic's native schema introspection. Both :enum and :ref map to
;; :db.type/ref in Datomic, but the original type is stored in graphden.metadata entities.


;; === Configuration ===

(def ^:dynamic *query-timeout-ms*
  "Timeout for Datomic queries in milliseconds. Can be rebound per-thread.
   Default is 30000 ms (30 seconds). Use `with-query-timeout` to temporarily change."
  sp/default-query-timeout-ms)


(defn with-query-timeout
  "Executes f with a custom query timeout (in milliseconds).

   Example:
   (with-query-timeout 60000
     #(sp/query-entities storage :user {}))"
  [timeout-ms f]
  (binding [*query-timeout-ms* timeout-ms]
    (f)))


;; === Attribute naming ===

(defn entity-attr
  "Creates a Datomic attribute ident for an entity field.
   E.g., :user/name -> user entity, name field"
  [entity-name field-name]
  (keyword (name entity-name) (name field-name)))


(defn metadata-attr
  "Creates a metadata attribute ident."
  [attr-name]
  (keyword "graphden.metadata" (name attr-name)))


(defn enum-value-ident
  "Creates a Datomic ident for an enum value.
   E.g., :status.value/active"
  [enum-name value-kw]
  (keyword (str (name enum-name) ".value") (name value-kw)))


;; === Connection validation ===

(defn ensure-connection!
  "Ensures connection is available for CRUD operations.
   Throws :storage-not-initialized if conn-atom is nil.
   Returns the connection if valid."
  [conn-atom operation-name]
  (if-let [conn @conn-atom]
    conn
    (do
      (log/error "CRUD operation failed: storage not initialized" {:operation operation-name})
      (throw (ex-info "Cannot perform operation: storage not initialized"
                      {:type :storage-not-initialized
                       :operation operation-name})))))


;; === Default configurations ===

(def default-local-config
  "Default configuration for Datomic Local with in-memory storage."
  {:server-type :datomic-local
   :storage-dir :mem
   :system "graphden-dev"})


(def ^:private valid-server-types
  "Valid Datomic server types."
  #{:datomic-local :peer-server :ion :cloud})


(defn validate-db-name!
  "Validates database name. Must be non-empty string without special characters."
  [db-name]
  (when-not (string? db-name)
    (throw (ex-info "db-name must be a string"
                    {:type :config-error/invalid-db-name
                     :db-name db-name
                     :db-name-type (type db-name)})))
  (when (str/blank? db-name)
    (throw (ex-info "db-name cannot be blank"
                    {:type :config-error/invalid-db-name
                     :db-name db-name})))
  ;; Datomic database names should be alphanumeric with hyphens
  (when-not (re-matches #"[a-zA-Z][a-zA-Z0-9-]*" db-name)
    (throw (ex-info "db-name must start with a letter and contain only alphanumeric characters and hyphens"
                    {:type :config-error/invalid-db-name
                     :db-name db-name}))))


(defn validate-client-config!
  "Validates Datomic client configuration.

   Validates:
   - server-type is one of known types
   - Required keys are present for each server type
   - No obviously invalid values"
  [config]
  (when-not (map? config)
    (throw (ex-info "client-config must be a map"
                    {:type :config-error/invalid-client-config
                     :config-type (type config)})))
  (let [server-type (:server-type config)]
    (when-not server-type
      (throw (ex-info "client-config must include :server-type"
                      {:type :config-error/missing-server-type
                       :available-types valid-server-types})))
    (when-not (contains? valid-server-types server-type)
      (throw (ex-info (str "Unknown server-type: " server-type)
                      {:type :config-error/invalid-server-type
                       :server-type server-type
                       :valid-types valid-server-types})))
    ;; Server-type specific validation
    ;; Redact config once for all error messages (security: prevent credential leakage)
    (let [safe-config (sp/redact-sensitive-deep config)]
      (case server-type
        :datomic-local
        (do
          (when-not (:system config)
            (throw (ex-info "datomic-local requires :system in client-config"
                            {:type :config-error/missing-system
                             :config safe-config})))
          (when-not (:storage-dir config)
            (throw (ex-info "datomic-local requires :storage-dir in client-config"
                            {:type :config-error/missing-storage-dir
                             :config safe-config}))))

        :peer-server
        (do
          (when-not (:endpoint config)
            (throw (ex-info "peer-server requires :endpoint in client-config"
                            {:type :config-error/missing-endpoint})))
          (when-not (:access-key config)
            (throw (ex-info "peer-server requires :access-key in client-config"
                            {:type :config-error/missing-access-key})))
          (when-not (:secret config)
            (throw (ex-info "peer-server requires :secret in client-config"
                            {:type :config-error/missing-secret}))))

        ;; :ion and :cloud have complex configs, just warn if empty
        (:ion :cloud)
        (when (< (count config) 2)
          (log/warn "Datomic ion/cloud config seems minimal, may fail to connect"
                    {:server-type server-type
                     :config-keys (keys config)}))))))

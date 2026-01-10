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
;; Query timeout - local var that delegates to storage-protocol for actual value.
;; We maintain a local var for binding compatibility in tests.

(def ^:dynamic *query-timeout-ms*
  "Query timeout in milliseconds for API consistency with postgres-storage.
   Default is 30000 ms (30 seconds)."
  sp/default-query-timeout-ms)


(defn with-query-timeout
  "Executes f with a custom query timeout binding.
   Delegates to sp/with-query-timeout which properly binds config/*query-timeout-ms*.
   Also binds local *query-timeout-ms* for API compatibility."
  [timeout-ms f]
  (sp/with-query-timeout timeout-ms
                         (fn []
                           (binding [*query-timeout-ms* timeout-ms]
                             (f)))))


(def execute-with-timeout!
  "Executes a query function with timeout enforcement.
   Delegates to storage-protocol implementation.
   Uses sp/*query-timeout-ms* which is bound by with-query-timeout."
  sp/execute-with-timeout!)


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
   - Credentials (access-key, secret) are at least 8 characters (security)
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
        (let [min-credential-length 8]  ; Security: prevent trivially weak credentials
          (when-not (:endpoint config)
            (throw (ex-info "peer-server requires :endpoint in client-config"
                            {:type :config-error/missing-endpoint})))
          (when-not (:access-key config)
            (throw (ex-info "peer-server requires :access-key in client-config"
                            {:type :config-error/missing-access-key})))
          (when (and (:access-key config) (< (count (:access-key config)) min-credential-length))
            (throw (ex-info (str "access-key must be at least " min-credential-length " characters")
                            {:type :config-error/credential-too-short
                             :field :access-key
                             :min-length min-credential-length
                             :actual-length (count (:access-key config))})))
          (when-not (:secret config)
            (throw (ex-info "peer-server requires :secret in client-config"
                            {:type :config-error/missing-secret})))
          (when (and (:secret config) (< (count (:secret config)) min-credential-length))
            (throw (ex-info (str "secret must be at least " min-credential-length " characters")
                            {:type :config-error/credential-too-short
                             :field :secret
                             :min-length min-credential-length
                             :actual-length (count (:secret config))}))))

        ;; :ion and :cloud have complex configs, just warn if empty
        (:ion :cloud)
        (when (< (count config) 2)
          (log/warn "Datomic ion/cloud config seems minimal, may fail to connect"
                    {:server-type server-type
                     :config-keys (keys config)}))))))


;; === Error handling ===

(defn classify-datomic-error
  "Classifies a Datomic exception into a canonical error type.
   Returns a keyword registered in storage-protocol error registry.

   Classification order:
   1. ExceptionInfo with our :type - pass through (already classified)
   2. ExceptionInfo with :db/error - Datomic-specific errors
   3. ExceptionInfo with cognitect anomaly - Datomic Client API errors
   4. Java exception types - connection/IO errors
   5. Fallback to :datomic-error"
  [e]
  (cond
    ;; ExceptionInfo with our own :type key - already classified
    (and (instance? clojure.lang.ExceptionInfo e)
         (:type (ex-data e)))
    (:type (ex-data e))

    ;; ExceptionInfo with :db/error key (Datomic-specific errors)
    (instance? clojure.lang.ExceptionInfo e)
    (let [data (ex-data e)
          db-error (:db/error data)
          cognitect-anomaly (:cognitect.anomalies/category data)]
      (cond
        ;; Not-found errors (Datomic and Cognitect anomalies)
        (or (= db-error :db.error/not-an-entity)
            (= db-error :db.error/invalid-entity-id)
            (= db-error :db.error/not-found)
            (= cognitect-anomaly :cognitect.anomalies/not-found))
        :not-found

        ;; Unique constraint violations
        (or (= db-error :db.error/unique-conflict)
            (= db-error :db.error/datoms-conflict)
            (= cognitect-anomaly :cognitect.anomalies/conflict))
        :constraint-violation/unique

        ;; CAS conflict (optimistic locking)
        (= db-error :db.error/cas-failed) :concurrent-modification

        ;; Transient errors (Cognitect anomalies)
        (= cognitect-anomaly :cognitect.anomalies/busy) :transient-error/busy
        (= cognitect-anomaly :cognitect.anomalies/unavailable) :transient-error/unavailable
        (= cognitect-anomaly :cognitect.anomalies/interrupted) :transient-error/interrupted

        ;; Fall back to generic Datomic error
        :else :datomic-error))

    ;; Connection/runtime errors
    (instance? java.util.concurrent.ExecutionException e)
    :transient-error/execution

    (instance? java.net.ConnectException e)
    :connection-error

    (instance? java.io.IOException e)
    :io-error

    :else :datomic-error))


(defn wrap-datomic-error
  "Wraps a Datomic exception with application context.
   Returns an ex-info with :type, :operation, and context.

   SECURITY: Context is redacted before logging to prevent sensitive data leakage."
  [e log-prefix operation context]
  (let [error-type (classify-datomic-error e)
        message (ex-message e)
        ;; Redact sensitive data from context before logging
        safe-context (sp/redact-sensitive-deep context)
        error-data (merge {:type error-type
                           :operation operation
                           :message message}
                          safe-context)]
    ;; Log without exposing full exception details
    (log/warn log-prefix error-data)
    (ex-info (str log-prefix " during " (name operation) ": " message)
             ;; Keep original context in exception for debugging
             (merge {:type error-type
                     :operation operation
                     :message message}
                    context)
             e)))


(defmacro with-datomic-error-handling
  "Wraps body with Datomic error handling.
   Catches exceptions and rethrows with application context.

   Parameters:
   - log-prefix: String prefix for log message (e.g., \"Database error\")
   - operation: Keyword describing the operation (e.g., :create-entity)
   - context: Map of additional context
   - body: Forms to execute

   Usage:
   (with-datomic-error-handling \"Database error\" :create-entity {:entity-name name}
     (d/transact conn tx-data))"
  [log-prefix operation context & body]
  `(try
     (do ~@body)
     (catch Exception e#
       (throw (wrap-datomic-error e# ~log-prefix ~operation ~context)))))


;; === StorageErrorClassifier implementation ===

(defrecord DatomicErrorClassifier
  []

  sp/StorageErrorClassifier

  (classify-error
    [_this exception]
    (classify-datomic-error exception))


  (wrap-error
    [_this exception operation context]
    (wrap-datomic-error exception "Datomic error" operation context)))


(def ^:private error-classifier-singleton
  "Cached singleton instance of DatomicErrorClassifier.
   Since the classifier is stateless, we only need one instance."
  (->DatomicErrorClassifier))


(defn create-error-classifier
  "Returns the Datomic error classifier singleton.
   Since the classifier is stateless, this always returns the same instance."
  []
  error-classifier-singleton)

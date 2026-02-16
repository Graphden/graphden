(ns graphden.postgres-storage.errors
  "SQL error handling and classification for PostgreSQL storage.

   PostgreSQL error codes: https://www.postgresql.org/docs/current/errcodes-appendix.html
   Table-driven error classification for maintainability."
  (:require
    [clojure.string :as str]
    [graphden.storage-protocol.interface :as sp]))


;; === Error Classification Tables ===

(def ^:private sql-state->error-type
  "Maps PostgreSQL SQLSTATE codes to application error types.
   See: https://www.postgresql.org/docs/current/errcodes-appendix.html"
  {"23505" :unique-violation           ; unique_violation
   "23503" :foreign-key-violation      ; foreign_key_violation
   "23502" :not-null-violation         ; not_null_violation
   "23514" :check-constraint-violation ; check_violation
   "42P01" :table-not-found            ; undefined_table
   "57014" :query-timeout              ; query_canceled
   "40001" :serialization-failure      ; serialization_failure (transaction conflict)
   "40P01" :deadlock-detected          ; deadlock_detected
   "25006" :read-only-transaction})    ; read_only_sql_transaction


(def ^:private sql-state-prefix->error-type
  "Maps PostgreSQL SQLSTATE class prefixes to application error types.
   Used for error classes where specific codes aren't important."
  {"08" :connection-error})          ; connection_exception class


;; === Error Classification ===

(defn- get-sql-state
  "Safely gets SQL state from SQLException, returning nil if not available."
  ^String [^java.sql.SQLException e]
  (java.sql.SQLException/.getSQLState e))


(defn classify-sql-error
  "Classifies SQLException into application error type using table-driven lookup.
   Returns a keyword like :unique-violation, :foreign-key-violation, etc.
   Returns :unknown-sql-error for unrecognized errors."
  [^java.sql.SQLException e]
  (if-let [state (get-sql-state e)]
    (or
      ;; Exact match first
      (get sql-state->error-type state)
      ;; Then prefix match (for error classes)
      (some (fn [[prefix error-type]]
              (when (str/starts-with? state prefix) error-type))
            sql-state-prefix->error-type)
      :unknown-sql-error)
    :unknown-sql-error))


;; === Error Predicates ===

(defn- make-error-predicate
  "Factory function that creates an error type predicate.
   Returns a function that checks if SQLException matches the given error type."
  [error-type]
  (fn [^java.sql.SQLException e]
    (= error-type (classify-sql-error e))))


(def table-not-found?
  "Returns true if the SQLException indicates a missing table (PostgreSQL 42P01)."
  (make-error-predicate :table-not-found))


(def unique-violation?
  "Returns true if the SQLException indicates a unique constraint violation (PostgreSQL 23505)."
  (make-error-predicate :unique-violation))


(def foreign-key-violation?
  "Returns true if the SQLException indicates a foreign key violation (PostgreSQL 23503)."
  (make-error-predicate :foreign-key-violation))


(def not-null-violation?
  "Returns true if the SQLException indicates a NOT NULL violation (PostgreSQL 23502)."
  (make-error-predicate :not-null-violation))


(def check-constraint-violation?
  "Returns true if the SQLException indicates a CHECK constraint violation (PostgreSQL 23514)."
  (make-error-predicate :check-constraint-violation))


(def connection-error?
  "Returns true if the SQLException indicates a connection failure (PostgreSQL class 08)."
  (make-error-predicate :connection-error))


(def query-canceled?
  "Returns true if the SQLException indicates a query was canceled (timeout) (PostgreSQL 57014)."
  (make-error-predicate :query-timeout))


(def serialization-failure?
  "Returns true if the SQLException indicates a serialization failure due to
   concurrent transaction conflict (PostgreSQL 40001).
   This typically occurs with SERIALIZABLE isolation level."
  (make-error-predicate :serialization-failure))


(def deadlock-detected?
  "Returns true if the SQLException indicates a deadlock was detected (PostgreSQL 40P01).
   The transaction was automatically aborted by PostgreSQL."
  (make-error-predicate :deadlock-detected))


(def read-only-transaction?
  "Returns true if the SQLException indicates an attempt to write in a read-only transaction
   (PostgreSQL 25006). This can occur with read replicas or explicit READ ONLY transactions."
  (make-error-predicate :read-only-transaction))


;; === Error Wrapping ===

(defn wrap-sql-error
  "Wraps a SQLException with application-level context.
   Translates PostgreSQL error codes to meaningful error types.
   Delegates to shared wrap-storage-error with sql-state in context.

   SECURITY: Context is redacted before logging to prevent sensitive data leakage.

   Parameters:
   - e: SQLException to wrap
   - log-prefix: String prefix for log message (e.g., \"Database error\", \"DDL error\")
   - operation: Keyword describing the operation (e.g., :create-entity, :add-column)
   - context: Map of additional context (e.g., {:entity-name :user})"
  [^java.sql.SQLException e log-prefix operation context]
  (let [error-type (classify-sql-error e)
        sql-state (get-sql-state e)]
    (sp/wrap-storage-error error-type e log-prefix operation
                           (assoc context :sql-state sql-state))))


;; === StorageErrorClassifier implementation ===

(defrecord PostgresErrorClassifier
  []

  sp/StorageErrorClassifier

  (classify-error
    [_this exception]
    (if (instance? java.sql.SQLException exception)
      (classify-sql-error exception)
      :unknown-sql-error))


  (wrap-error
    [_this exception operation context]
    (if (instance? java.sql.SQLException exception)
      (wrap-sql-error exception "Database error" operation context)
      (sp/wrap-storage-error :unknown-sql-error
                             exception "Database error" operation context))))


(def ^:private error-classifier-singleton
  "Cached singleton instance of PostgresErrorClassifier.
   Since the classifier is stateless, we only need one instance."
  (->PostgresErrorClassifier))


(defn create-error-classifier
  "Returns the PostgreSQL error classifier singleton.
   Since the classifier is stateless, this always returns the same instance."
  []
  error-classifier-singleton)


;; === Error Handling Macro ===

(defmacro with-sql-error-handling
  "Wraps body with SQLException handling.
   Catches SQLException and rethrows with application context.

   Parameters:
   - log-prefix: String prefix for log message (e.g., \"Database error\", \"DDL error\")
   - operation: Keyword describing the operation (e.g., :create-entity, :add-column)
   - context: Map of additional context
   - body: Forms to execute

   Usage:
   (with-sql-error-handling \"Database error\" :create-entity {:entity-name name}
     (jdbc/execute! ...))"
  [log-prefix operation context & body]
  `(try
     (do ~@body)
     (catch java.sql.SQLException e#
       (throw (wrap-sql-error e# ~log-prefix ~operation ~context)))))

(ns graphden.postgres-storage.util
  "Shared utilities for PostgreSQL storage.
   Type mapping, naming conversions, SQL helpers, and configuration.

   SQL Injection Prevention Strategy:
   ---------------------------------
   This module uses a defense-in-depth approach to prevent SQL injection:

   1. HoneySQL (honey.sql) - All DML queries (SELECT, INSERT, UPDATE, DELETE) are
      built using HoneySQL which automatically parameterizes values. Values are
      never interpolated directly into SQL strings.

   2. Identifier validation - For DDL operations (CREATE TABLE, ALTER, etc.) where
      parameterization isn't possible, all identifiers (table names, column names,
      enum values) are validated against a strict alphanumeric pattern via
      `validate-sql-identifier!`. This prevents injection through identifier names.

   3. Type-safe conversions - The `ident->sql` and related functions ensure that
      only valid, validated identifiers are used in SQL statements."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.storage-protocol.interface :as sp]
    [next.jdbc.result-set :as rs]))


;; === Configuration ===

(def ^:dynamic *query-timeout-ms*
  "Timeout for SQL queries in milliseconds. Can be rebound per-thread.
   Default is 30000 ms (30 seconds). Use `with-query-timeout` to temporarily change.
   Note: Internally converted to seconds for JDBC calls."
  sp/default-query-timeout-ms)


(def min-query-timeout-ms
  "Minimum allowed query timeout in milliseconds.
   1000ms (1 second) minimum because JDBC setQueryTimeout uses seconds,
   and sub-second values would round to 0 (no timeout)."
  1000)


(defn with-query-timeout
  "Executes f with a custom query timeout (in milliseconds).
   Timeout must be a positive integer. Minimum is 1000ms (1 second).

   Why 1000ms minimum?
   - JDBC setQueryTimeout uses seconds (integer), values <1000ms become 0
   - SQL queries need time for network roundtrip and query parsing
   - This is different from executor timeout (50ms min) which covers overall execution

   Example:
   (with-query-timeout 60000
     #(sp/query-entities storage :user {}))"
  [timeout-ms f]
  (when-not (pos-int? timeout-ms)
    (throw (ex-info "Query timeout must be a positive integer (ms)"
                    {:type :config-error/invalid-timeout
                     :timeout-ms timeout-ms})))
  (when (< timeout-ms min-query-timeout-ms)
    (throw (ex-info (str "Query timeout must be at least " min-query-timeout-ms "ms (1 second)")
                    {:type :config-error/invalid-timeout
                     :timeout-ms timeout-ms
                     :min-timeout-ms min-query-timeout-ms})))
  (binding [*query-timeout-ms* timeout-ms]
    (f)))


(defn get-query-timeout-seconds
  "Returns the current query timeout in seconds for JDBC calls.
   Reads the dynamic var *query-timeout-ms* and converts to seconds.
   The timeout is stored in milliseconds but JDBC setQueryTimeout uses seconds.

   Safety: Asserts that timeout is at least min-query-timeout-ms to prevent
   silent timeout disabling. This catches improper direct binding of
   *query-timeout-ms* (use with-query-timeout instead)."
  []
  (assert (>= *query-timeout-ms* min-query-timeout-ms)
          (str "Query timeout must be at least " min-query-timeout-ms "ms. "
               "Use with-query-timeout for safe rebinding."))
  (quot *query-timeout-ms* 1000))


;; === Error handling ===

;; PostgreSQL error codes: https://www.postgresql.org/docs/current/errcodes-appendix.html
;; Table-driven error classification for maintainability

(def ^:private sql-state->error-type
  "Maps PostgreSQL SQLSTATE codes to application error types.
   See: https://www.postgresql.org/docs/current/errcodes-appendix.html"
  {"23505" :unique-violation         ; unique_violation
   "23503" :foreign-key-violation    ; foreign_key_violation
   "23502" :not-null-violation       ; not_null_violation
   "23514" :check-constraint-violation ; check_violation
   "42P01" :table-not-found          ; undefined_table
   "57014" :query-timeout})          ; query_canceled


(def ^:private sql-state-prefix->error-type
  "Maps PostgreSQL SQLSTATE class prefixes to application error types.
   Used for error classes where specific codes aren't important."
  {"08" :connection-error})          ; connection_exception class


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


;; Convenience predicates for backward compatibility and specific checks

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


(defn wrap-sql-error
  "Wraps a SQLException with application-level context.
   Translates PostgreSQL error codes to meaningful error types.
   Logs the error with context for debugging.
   Returns an ex-info with :type, :sql-state, and operation context.

   SECURITY: Context is redacted before logging to prevent sensitive data leakage.
   The raw exception is NOT logged to avoid exposing SQL details.

   Parameters:
   - e: SQLException to wrap
   - log-prefix: String prefix for log message (e.g., \"Database error\", \"DDL error\")
   - operation: Keyword describing the operation (e.g., :create-entity, :add-column)
   - context: Map of additional context (e.g., {:entity-name :user})"
  [^java.sql.SQLException e log-prefix operation context]
  (let [error-type (classify-sql-error e)
        sql-state (java.sql.SQLException/.getSQLState e)
        message (java.sql.SQLException/.getMessage e)
        ;; Redact sensitive data from context before logging
        safe-context (sp/redact-sensitive-deep context)
        error-data (merge {:type error-type
                           :operation operation
                           :sql-state sql-state
                           :message message}
                          safe-context)]
    ;; Log without raw exception to avoid exposing SQL internals
    (log/warn log-prefix error-data)
    (ex-info (str log-prefix " during " (name operation) ": " message)
             ;; Keep original context in exception for debugging (not logged)
             (merge {:type error-type
                     :operation operation
                     :sql-state sql-state
                     :message message}
                    context)
             e)))


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
      (let [error-data (merge {:type :unknown-sql-error
                               :operation operation
                               :message (str exception)}
                              context)]
        (log/warn exception "Unknown error" error-data)
        (ex-info (str "Error during " (name operation) ": " exception)
                 error-data
                 exception)))))


(defn create-error-classifier
  "Creates a PostgreSQL error classifier instance."
  []
  (->PostgresErrorClassifier))


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


;; === SQL validation ===
;;
;; NOTE: These are defined early because they're used by type mapping functions below.

(def ^:private sql-identifier-pattern
  "Pattern for valid SQL identifiers (snake_case, alphanumeric + underscore)."
  #"^[a-z][a-z0-9_]*$")


(def ^:private max-sql-identifier-length
  "Maximum length for PostgreSQL identifiers.
   PostgreSQL truncates identifiers longer than 63 bytes (NAMEDATALEN - 1).
   We enforce this limit to avoid silent truncation issues."
  63)


(defn validate-sql-identifier!
  "Validates that a string is a safe SQL identifier.
   Prevents SQL injection in DDL statements where parameterization isn't possible.
   Identifiers must be lowercase because PostgreSQL folds unquoted identifiers to lowercase,
   and using uppercase would create case-sensitivity issues.
   Also enforces PostgreSQL's 63-character identifier length limit."
  [s context]
  (when (> (count s) max-sql-identifier-length)
    (throw (ex-info (str "SQL identifier too long: '" s "' (" (count s) " chars). "
                         "PostgreSQL limits identifiers to " max-sql-identifier-length " characters.")
                    {:type :validation-error/identifier-too-long
                     :value s
                     :length (count s)
                     :max-length max-sql-identifier-length
                     :context context})))
  (when-not (re-matches sql-identifier-pattern s)
    (throw (ex-info (str "Invalid SQL identifier: '" s "'. "
                         "Must start with lowercase letter and contain only "
                         "lowercase letters, digits, and underscores. "
                         "Uppercase is not allowed because PostgreSQL folds "
                         "unquoted identifiers to lowercase.")
                    {:type :validation-error/invalid-identifier
                     :value s
                     :context context
                     :pattern (str sql-identifier-pattern)}))))


;; === Type mapping ===

(def type->pg
  "Maps our field types to PostgreSQL types."
  {:uuid        "UUID"
   :text        "TEXT"
   :int         "BIGINT"
   :bool        "BOOLEAN"
   :numeric     "NUMERIC"
   :timestamptz "TIMESTAMPTZ"
   :jsonb       "JSONB"
   :bytes       "BYTEA"})


(defn kw->snake-case
  "Converts a keyword to snake_case string (unquoted)."
  [k]
  (str/replace (name k) "-" "_"))


(defn snake->kw
  "Converts a snake_case string to kebab-case keyword."
  [s]
  (keyword (str/replace s "_" "-")))


(defn check-snake-case-collisions!
  "Checks that converting keywords to snake_case doesn't create collisions.
   E.g., :foo-bar and :foo_bar would both become 'foo_bar'.
   Throws if collisions detected. Runs in O(n) time where n = number of keywords."
  [context keywords]
  (let [;; Group keywords by their snake_case form in single pass - O(n)
        snake->originals (reduce (fn [acc kw]
                                   (update acc (kw->snake-case kw) (fnil conj []) kw))
                                 {}
                                 keywords)
        ;; Find groups with more than one original - O(n)
        collisions (into []
                         (comp (filter #(> (count (val %)) 1))
                               (map (fn [[snake originals]]
                                      {:snake-case snake :originals originals})))
                         snake->originals)]
    (when (seq collisions)
      (throw (ex-info "Snake_case naming collision detected"
                      (merge context {:type :validation-error/naming-collision
                                      :collisions collisions}))))))


(defn ident->sql
  "Converts a keyword identifier to quoted SQL-safe name (snake_case).
   Validates the result to prevent SQL injection in DDL operations.
   Uses double quotes to handle reserved words like 'order', 'user', etc.

   Throws if the keyword would produce an invalid SQL identifier."
  [k]
  (let [sql-name (kw->snake-case k)]
    (validate-sql-identifier! sql-name {:type :identifier :keyword k})
    (str "\"" sql-name "\"")))


(defn field-type->pg
  "Converts a field type to PostgreSQL type string.
   Handles basic types, refs (as UUID), enums, and unions (as JSONB)."
  [field-spec]
  (let [t (:type field-spec)]
    (case t
      :ref "UUID"
      :enum (ident->sql (:enum-name field-spec))
      :union "JSONB"
      (get type->pg t "TEXT"))))


(defn enum-value->sql
  "Converts an enum value keyword to SQL string (snake_case, no quotes wrapper).
   Validates the result to prevent SQL injection.

   Note: Uppercase letters in keyword will cause validation to fail since
   PostgreSQL enum values must be lowercase (they are case-sensitive and
   we use unquoted identifiers which PostgreSQL folds to lowercase)."
  [k]
  (let [kw-name (name k)
        sql-val (str/replace kw-name "-" "_")]
    ;; Log warning if keyword contains uppercase (will fail validation anyway)
    (when (not= kw-name (str/lower-case kw-name))
      (log/warn "Enum value keyword contains uppercase letters which are not allowed"
                {:keyword k :would-become sql-val}))
    (validate-sql-identifier! sql-val {:type :enum-value :keyword k})
    sql-val))


(defn sql->enum-value
  "Converts SQL enum value back to keyword (reverses enum-value->sql)."
  [s]
  (keyword (str/replace s "_" "-")))


(def ^:private valid-pg-types
  "Set of valid PostgreSQL types for DDL."
  #{"UUID" "TEXT" "BIGINT" "BOOLEAN" "NUMERIC" "TIMESTAMPTZ" "JSONB" "BYTEA"})


(defn validate-pg-type!
  "Validates that a PostgreSQL type string is safe to use in DDL.
   Allows base types and quoted identifiers (for enums)."
  [type-str context]
  (when-not (or (contains? valid-pg-types type-str)
                ;; Quoted identifier pattern: \"snake_case_name\"
                (re-matches #"^\"[a-z][a-z0-9_]*\"$" type-str))
    (throw (ex-info "Invalid PostgreSQL type specification"
                    {:type :validation-error/invalid-pg-type
                     :pg-type type-str
                     :context context}))))


;; === Query execution helpers ===
;; Common patterns for executing SQL queries with consistent options.
;; These reduce boilerplate in CRUD, DDL, and other modules.

(def ^:private default-query-opts
  "Default options for query execution.
   - :builder-fn: Convert row keys to lowercase unqualified keywords
   - :timeout: Comes from dynamic var *query-timeout-ms*"
  {:builder-fn rs/as-unqualified-lower-maps})


(defn query-opts
  "Returns query options with current timeout.
   Can optionally merge additional options."
  ([]
   (assoc default-query-opts :timeout (get-query-timeout-seconds)))
  ([extra-opts]
   (merge (query-opts) extra-opts)))

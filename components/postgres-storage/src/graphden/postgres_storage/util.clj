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
    [clojure.tools.logging :as log]))


;; === Configuration helpers ===

(def ^:private timeout-fallback-logged? (atom false))


;; Cache the resolved var to avoid repeated reflection.
;; The var reference is resolved once on first access; the var's VALUE
;; is still read on each call (since *query-timeout-ms* is dynamic).
;; Uses atom with ::not-cached sentinel to allow reset in tests.
(def ^:private timeout-var-cache (atom ::not-cached))


(defn- resolve-timeout-var
  "Resolves and caches the timeout var. Returns the var or nil if not found.
   If resolution fails (returns nil), does NOT cache the result, so subsequent
   calls will retry. This handles the case where util.clj is loaded before
   core.clj - the first call returns nil but later calls succeed."
  []
  (let [cached @timeout-var-cache]
    (if (= cached ::not-cached)
      ;; First call - resolve and cache only if successful
      (let [resolved (resolve 'graphden.postgres-storage.core/*query-timeout-ms*)]
        (when resolved
          (reset! timeout-var-cache resolved))
        resolved)
      ;; Already cached (and must be non-nil since we only cache successful resolutions)
      cached)))


(defn get-query-timeout-seconds
  "Returns the current query timeout in seconds for JDBC calls.
   Resolves the dynamic var *query-timeout-ms* from core.clj and converts to seconds.
   Returns 30 seconds if resolution fails (e.g., during test isolation).

   Architecture note:
   Uses `resolve` (reflection) to avoid circular dependency:
   - util.clj is required by core.clj, crud.clj, ddl.clj
   - *query-timeout-ms* is defined in core.clj
   - If util.clj required core.clj, we'd have a circular dependency

   This is a deliberate trade-off: runtime var resolution vs compile-time
   circular dependency. The fallback value ensures the system works even
   if resolution fails (e.g., in test isolation or REPL experiments).

   The var reference is cached (via atom) to avoid repeated reflection;
   the var's value is still read on each call since it's dynamic.

   The timeout is stored in milliseconds but JDBC setQueryTimeout uses seconds."
  []
  (if-let [timeout-var (resolve-timeout-var)]
    (quot (deref timeout-var) 1000)
    (do
      ;; Log warning once to avoid log spam, but alert on potential misconfiguration
      (when (compare-and-set! timeout-fallback-logged? false true)
        (log/warn "Could not resolve *query-timeout-ms* from core.clj, using fallback of 30 seconds"))
      30)))


;; === Error handling ===

;; PostgreSQL error codes: https://www.postgresql.org/docs/current/errcodes-appendix.html

(defn table-not-found?
  "Returns true if the SQLException indicates a missing table (PostgreSQL 42P01)."
  [^java.sql.SQLException e]
  (= "42P01" (java.sql.SQLException/.getSQLState e)))


(defn unique-violation?
  "Returns true if the SQLException indicates a unique constraint violation (PostgreSQL 23505)."
  [^java.sql.SQLException e]
  (= "23505" (java.sql.SQLException/.getSQLState e)))


(defn foreign-key-violation?
  "Returns true if the SQLException indicates a foreign key violation (PostgreSQL 23503)."
  [^java.sql.SQLException e]
  (= "23503" (java.sql.SQLException/.getSQLState e)))


(defn not-null-violation?
  "Returns true if the SQLException indicates a NOT NULL violation (PostgreSQL 23502)."
  [^java.sql.SQLException e]
  (= "23502" (java.sql.SQLException/.getSQLState e)))


(defn check-constraint-violation?
  "Returns true if the SQLException indicates a CHECK constraint violation (PostgreSQL 23514)."
  [^java.sql.SQLException e]
  (= "23514" (java.sql.SQLException/.getSQLState e)))


(defn connection-error?
  "Returns true if the SQLException indicates a connection failure (PostgreSQL class 08)."
  [^java.sql.SQLException e]
  (when-let [state (java.sql.SQLException/.getSQLState e)]
    (str/starts-with? state "08")))


(defn query-canceled?
  "Returns true if the SQLException indicates a query was canceled (timeout) (PostgreSQL 57014)."
  [^java.sql.SQLException e]
  (= "57014" (java.sql.SQLException/.getSQLState e)))


(defn classify-sql-error
  "Classifies SQLException into application error type.
   Returns a keyword like :unique-violation, :foreign-key-violation, etc.
   Returns :unknown-sql-error for unrecognized errors."
  [^java.sql.SQLException e]
  (cond
    (unique-violation? e)           :unique-violation
    (foreign-key-violation? e)      :foreign-key-violation
    (not-null-violation? e)         :not-null-violation
    (check-constraint-violation? e) :check-constraint-violation
    (table-not-found? e)            :table-not-found
    (connection-error? e)           :connection-error
    (query-canceled? e)             :query-timeout
    :else                           :unknown-sql-error))


(defn wrap-sql-error
  "Wraps a SQLException with application-level context.
   Translates PostgreSQL error codes to meaningful error types.
   Logs the error with context for debugging.
   Returns an ex-info with :type, :sql-state, and operation context.

   Parameters:
   - e: SQLException to wrap
   - log-prefix: String prefix for log message (e.g., \"Database error\", \"DDL error\")
   - operation: Keyword describing the operation (e.g., :create-entity, :add-column)
   - context: Map of additional context (e.g., {:entity-name :user})"
  [^java.sql.SQLException e log-prefix operation context]
  (let [error-type (classify-sql-error e)
        sql-state (java.sql.SQLException/.getSQLState e)
        message (java.sql.SQLException/.getMessage e)
        error-data (merge {:type error-type
                           :operation operation
                           :sql-state sql-state
                           :message message}
                          context)]
    (log/warn e log-prefix error-data)
    (ex-info (str log-prefix " during " (name operation) ": " message)
             error-data
             e)))


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
                      (merge context {:collisions collisions}))))))


(defn ident->sql
  "Converts a keyword identifier to quoted SQL-safe name (snake_case).
   Uses double quotes to handle reserved words like 'order', 'user', etc."
  [k]
  (str "\"" (kw->snake-case k) "\""))


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


;; === SQL validation ===

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
                    {:value s
                     :length (count s)
                     :max-length max-sql-identifier-length
                     :context context})))
  (when-not (re-matches sql-identifier-pattern s)
    (throw (ex-info (str "Invalid SQL identifier: '" s "'. "
                         "Must start with lowercase letter and contain only "
                         "lowercase letters, digits, and underscores. "
                         "Uppercase is not allowed because PostgreSQL folds "
                         "unquoted identifiers to lowercase.")
                    {:value s
                     :context context
                     :pattern (str sql-identifier-pattern)}))))


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
                    {:type type-str :context context}))))

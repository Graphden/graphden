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


(defn get-query-timeout-seconds
  "Returns the current query timeout in seconds for JDBC calls.
   Resolves the dynamic var *query-timeout-ms* from core.clj and converts to seconds.
   Returns 30 seconds if resolution fails (e.g., during test isolation).

   Note: Uses reflection to avoid circular dependency with core.clj.
   The timeout is stored in milliseconds but JDBC setQueryTimeout uses seconds."
  []
  (if-let [timeout-var (resolve 'graphden.postgres-storage.core/*query-timeout-ms*)]
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


(defn validate-sql-identifier!
  "Validates that a string is a safe SQL identifier.
   Prevents SQL injection in DDL statements where parameterization isn't possible."
  [s context]
  (when-not (re-matches sql-identifier-pattern s)
    (throw (ex-info "Invalid SQL identifier"
                    {:value s
                     :context context
                     :pattern (str sql-identifier-pattern)}))))


(defn enum-value->sql
  "Converts an enum value keyword to SQL string (snake_case, no quotes wrapper).
   Validates the result to prevent SQL injection."
  [k]
  (let [sql-val (str/replace (name k) "-" "_")]
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

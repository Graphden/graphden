(ns graphden.storage.postgres.util
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
    [graphden.storage.postgres.errors :as errors]
    [graphden.storage.protocol.core :as sp]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))


;; === Configuration ===
;; Query timeout is centralized in storage-protocol.config; this
;; namespace just aliases the public helpers so postgres call sites
;; can `(:require [.util :as util])` once and reach both timeout and
;; error helpers under one prefix.

(def with-query-timeout
  "Executes f with a custom query timeout (in milliseconds).
   Delegates to storage-protocol for centralized configuration."
  sp/with-query-timeout)


(defn get-query-timeout-seconds
  "Returns the current query timeout in seconds for JDBC calls. Thin
   re-export over `protocol.config/get-query-timeout-seconds` —
   centralises the timeout-shape conversion + safety validation
   in one place. The pre-fix duplicate inlined `validate-query-timeout!`
   + `quot` here; if the validator ever needed to add a new safety
   check (max-timeout cap, NaN guard, etc.) the two copies would
   silently drift."
  []
  (sp/get-query-timeout-seconds))


;; === Error handling (re-exports from errors.clj) ===

(def classify-sql-error errors/classify-sql-error)
(def table-not-found? errors/table-not-found?)
(def unique-violation? errors/unique-violation?)
(def foreign-key-violation? errors/foreign-key-violation?)
(def not-null-violation? errors/not-null-violation?)
(def check-constraint-violation? errors/check-constraint-violation?)
(def connection-error? errors/connection-error?)
(def query-canceled? errors/query-canceled?)
(def serialization-failure? errors/serialization-failure?)
(def deadlock-detected? errors/deadlock-detected?)
(def read-only-transaction? errors/read-only-transaction?)
(def wrap-sql-error errors/wrap-sql-error)
(def create-error-classifier errors/create-error-classifier)


(defmacro with-sql-error-handling
  [log-prefix operation context & body]
  `(errors/with-sql-error-handling ~log-prefix ~operation ~context ~@body))


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
;;
;; Type mappings are extensible for custom types.
;; Use register-type-mapping! to add new canonical type -> PostgreSQL type mappings.

(def ^:private type-mappings
  "Atom holding canonical type -> PostgreSQL type mappings.
   Extensible via register-type-mapping! for custom types."
  (atom {:uuid        "UUID"
         :text        "TEXT"
         :int         "BIGINT"
         :bool        "BOOLEAN"
         :numeric     "NUMERIC"
         :timestamptz "TIMESTAMPTZ"
         :jsonb       "JSONB"
         :bytes       "BYTEA"}))


(defn type->pg
  "Maps a canonical field type to PostgreSQL type string.
   Returns nil for unknown types.

   This function looks up the type in the current type mappings.
   Use register-type-mapping! to add custom type mappings."
  [field-type]
  (get @type-mappings field-type))


(defn register-type-mapping!
  "Registers a new canonical type -> PostgreSQL type mapping.
   Use this to add support for custom field types.

   Arguments:
   - canonical-type: keyword like :my-custom-type
   - pg-type: PostgreSQL type string like \"MYTYPE\"

   Example:
   (register-type-mapping! :money \"MONEY\")
   (register-type-mapping! :point \"POINT\")"
  [canonical-type pg-type]
  (swap! type-mappings assoc canonical-type pg-type))


(defn reset-type-mappings!
  "Resets type mappings to defaults. Mainly for testing."
  []
  (reset! type-mappings
          {:uuid        "UUID"
           :text        "TEXT"
           :int         "BIGINT"
           :bool        "BOOLEAN"
           :numeric     "NUMERIC"
           :timestamptz "TIMESTAMPTZ"
           :jsonb       "JSONB"
           :bytes       "BYTEA"}))


;; Delegate to shared naming utilities
(def kw->snake-case
  "Converts a keyword to snake_case string (unquoted)."
  sp/kw->snake-case)


(def snake->kw
  "Converts a snake_case string to kebab-case keyword."
  sp/snake->kw)


(def check-snake-case-collisions!
  "Checks that converting keywords to snake_case doesn't create collisions."
  sp/check-snake-case-collisions!)


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
   Handles basic types, refs (as UUID), enums, and unions (as JSONB).
   Falls back to TEXT for unknown types (allows custom types via type mappings)."
  [field-spec]
  (let [t (:type field-spec)]
    (case t
      :ref "UUID"
      :enum (ident->sql (:enum-name field-spec))
      :union "JSONB"
      (or (type->pg t) "TEXT"))))


(defn enum-value->sql
  "Converts an enum value keyword to SQL string (snake_case, lowercase).
   Validates the result to prevent SQL injection.

   Uppercase letters are automatically converted to lowercase since
   PostgreSQL enum values are case-sensitive and we use unquoted
   identifiers which PostgreSQL folds to lowercase."
  [k]
  (let [kw-name (name k)
        ;; Explicitly convert to lowercase for safety
        sql-val (-> kw-name
                    str/lower-case
                    (str/replace "-" "_"))]
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


;; === JDBC execution seam ===
;;
;; Every direct SQL statement in the postgres storage layer goes
;; through `exec!` / `exec-one!` instead of calling next.jdbc root
;; Vars — one choke point, one test seam.

(def ^:dynamic *jdbc-override*
  "Parallel-test seam: a map `{:execute! f :execute-one! f}` shadowing
   `next.jdbc/execute!` / `next.jdbc/execute-one!` inside `exec!` /
   `exec-one!`. Each override fn receives `[ds sql-params opts]`
   (`opts` is the fully-resolved options map, `{}` when the call site
   asked for raw jdbc defaults). nil (production) = the real jdbc
   call. Tests `binding` this instead of `with-redefs`-ing the
   next.jdbc root Vars — a root rebind is process-global and forced
   `^:serial` pins on the sql-errors / crud / edge-cases postgres
   suites (serial-reduction cluster A). Mirrors
   `advisory-lock/*impl-override*`.

   Perf note: an extra fn call + nil check in front of a NETWORK
   round trip is noise by construction — this repo's
   reverted-optimization lessons concern per-node registry paths,
   not per-SQL-call ones."
  nil)


(defn exec!
  "`next.jdbc/execute!` behind the `*jdbc-override*` seam.

   The 2-arity threads `(query-opts)` — the standard DML options
   (unqualified-lowercase builder + the protocol query timeout),
   DRYing the former `(jdbc/execute! ds q (query-opts))` boilerplate.
   Pass `{}` explicitly for raw jdbc defaults — DDL / migration /
   session statements (advisory locks, NOTIFY) that must NOT inherit
   the DML query timeout."
  ([ds sql-params] (exec! ds sql-params (query-opts)))
  ([ds sql-params opts]
   (if-let [f (:execute! *jdbc-override*)]
     (f ds sql-params opts)
     (jdbc/execute! ds sql-params opts))))


(defn exec-one!
  "`next.jdbc/execute-one!` behind the `*jdbc-override*` seam.
   Arity/option semantics identical to `exec!`."
  ([ds sql-params] (exec-one! ds sql-params (query-opts)))
  ([ds sql-params opts]
   (if-let [f (:execute-one! *jdbc-override*)]
     (f ds sql-params opts)
     (jdbc/execute-one! ds sql-params opts))))

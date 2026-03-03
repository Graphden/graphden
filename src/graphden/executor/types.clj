(ns graphden.executor.types
  "Type hints and validation for the executor.

   Contains:
   - Human-readable type hints for error messages
   - Type validation functions
   - Type mismatch error handling"
  (:require
    [clojure.tools.logging :as log]
    [graphden.executor.context :as ctx]
    [graphden.schema.fields.types :as ft]
    [graphden.storage.protocol.core :as sp]))


;; === Value Truncation ===

(defn truncate-value
  "Truncates large values for error messages to avoid huge exception data.
   Returns a shortened representation for display purposes.
   Redacts sensitive data (passwords, tokens, etc.) before truncation.

   Uses pr-str for consistent Clojure-readable output. The max-len parameter
   controls truncation; callers typically use 100 chars for error context."
  [value max-len]
  (let [redacted (sp/redact-sensitive-deep value)
        s (pr-str redacted)]
    (if (> (count s) max-len)
      (str (subs s 0 max-len) "...")
      s)))


;; === Type Hints ===

(def ^:private default-type-hints
  "Human-readable hints for expected Clojure types (built-in types)."
  {:fn "UUID (function reference)"
   :ref "UUID (entity reference)"
   :int "integer (e.g., 42, -1)"
   :bool "boolean (true or false)"
   :text "string (e.g., \"hello\")"
   :numeric "number (int, float, bigdec, ratio)"
   :jsonb "map or vector"
   :bytes "byte array (byte-array)"
   :timestamptz "java.time.Instant, java.time.LocalDateTime, or java.util.Date"
   :enum "keyword (e.g., :active, :pending)"
   :uuid "UUID"})


(def custom-type-hints
  "Atom containing custom type hints that extend the built-in hints.
   Use `register-type-hint!` to add hints for custom types.
   Hints registered here take precedence over default hints."
  (atom {}))


(defn register-type-hint!
  "Registers a human-readable hint for a custom type.
   The hint is shown in type mismatch error messages to help users understand
   what value format is expected.

   Example:
   (register-type-hint! :email \"string in email format (e.g., user@example.com)\")
   (register-type-hint! :phone \"string with international format (e.g., +1-555-123-4567)\")"
  [type-keyword hint-string]
  (when-not (keyword? type-keyword)
    (throw (ex-info "type-keyword must be a keyword"
                    {:type :invalid-argument
                     :type-keyword type-keyword})))
  (when-not (string? hint-string)
    (throw (ex-info "hint-string must be a string"
                    {:type :invalid-argument
                     :hint-string hint-string})))
  (swap! custom-type-hints assoc type-keyword hint-string))


(defn get-type-hint
  "Gets human-readable hint for a type, checking custom hints first."
  [arg-type]
  (or (get @custom-type-hints arg-type)
      (get default-type-hints arg-type)
      (name arg-type)))


;; === Type Mismatch Errors ===

(defn throw-type-mismatch!
  "Throws a type mismatch error with detailed context."
  [arg-schema provided-value]
  (let [arg-type (:type arg-schema)
        arg-name (:name arg-schema)
        arg-schema-id (:id arg-schema)
        hint (get-type-hint arg-type)]
    (throw (ex-info (str "Type mismatch for argument '" arg-name "': "
                         "expected " (name arg-type) " (" hint "), "
                         "got " (-> provided-value class .getSimpleName))
                    {:type :execution-error/type-mismatch
                     :arg-name arg-name
                     :arg-schema-id arg-schema-id
                     :expected-type arg-type
                     :expected-hint hint
                     :provided-value (truncate-value provided-value ctx/error-value-truncation-length)
                     :provided-type (type provided-value)}))))


;; === Type Validation ===

(defn- check-unknown-type-circuit-breaker!
  "Checks if unknown type count exceeds threshold and throws if so.
   Acts as circuit breaker to prevent silent schema mismatch issues.

   Uses swap-vals! for atomic check-and-increment to avoid race conditions
   where multiple threads could slip past the threshold check simultaneously.
   The check uses >= to ensure we throw exactly when hitting the limit."
  [^clojure.lang.Atom unknown-type-counter max-unknown-types ^clojure.lang.Keyword arg-type]
  (let [[_old-count new-count] (swap-vals! unknown-type-counter inc)]
    ;; Use >= to throw when reaching the limit (not after exceeding it)
    ;; This prevents off-by-one errors in concurrent scenarios
    (when (>= new-count max-unknown-types)
      (throw (ex-info "Too many unknown types in forward compatibility mode - possible schema mismatch"
                      {:type :execution-error/unknown-type-limit-exceeded
                       :unknown-type-count new-count
                       :max-allowed max-unknown-types
                       :last-unknown-type arg-type
                       :hint "Check schema version compatibility or disable forward compatibility mode"})))))


(defn type-mismatch?
  "Returns true if provided-value doesn't match the expected arg-type.

   Type validation rules:
   - Known types: strict validation based on Clojure predicates (from field-types)
   - :union type: accepts any value (validation happens at schema level,
     where union variants are checked against allowed types)
   - Unknown types: behavior depends on strict? flag:
     - strict?=true (default): throws exception to catch schema mismatches early
     - strict?=false: returns false (permissive) for forward compatibility
       with circuit breaker at max-unknown-types

   This is a runtime check for user-provided arguments only. Values from
   the execution graph (arg-values) are assumed to be already validated."
  [^clojure.lang.Keyword arg-type provided-value strict? max-unknown-types ^clojure.lang.Atom unknown-type-counter]
  (if (contains? ft/type-validators arg-type)
    (not (ft/valid-type? arg-type provided-value))
    ;; Unknown type - behavior depends on strict mode
    (if strict?
      (throw (ex-info "Unknown argument type encountered"
                      {:type :execution-error/unknown-arg-type
                       :arg-type arg-type
                       :value-type (type provided-value)
                       :hint "Set :strict-type-validation? false in context for forward compatibility"}))
      ;; Forward compatibility mode: accept unknown types with warning + circuit breaker
      ;; This allows newer schema versions to introduce new types without
      ;; breaking existing deployments. Circuit breaker prevents silent failures.
      (do
        (check-unknown-type-circuit-breaker! unknown-type-counter max-unknown-types arg-type)
        ;; SECURITY: Intentionally NOT logging the actual value here.
        ;; Values may contain secrets (passwords, tokens, API keys) that should
        ;; never appear in logs. Only type information is logged for debugging.
        (log/warn "Unknown argument type in forward compatibility mode"
                  {:arg-type arg-type
                   :value-type (type provided-value)
                   :action :accepting-without-validation})
        false))))


(defn validate-provided-arg-type!
  "Validates that a provided argument matches the expected arg-schema type.
   Throws ExceptionInfo with detailed context if type mismatch detected.

   Called from build-thunk when a user provides an argument that overrides
   a stored arg-value. This ensures user-provided values match the expected
   type before creating a LiteralThunk.

   Note: Stored arg-values from the execution graph are not validated here;
   they are assumed to be valid from schema-level checks during creation."
  [provided-value ^clojure.lang.IPersistentMap arg-schema strict? max-unknown-types ^clojure.lang.Atom unknown-type-counter]
  (when-not (and arg-schema (:type arg-schema))
    (throw (ex-info "Invalid arg-schema: missing type"
                    {:type :execution-error/invalid-arg-schema
                     :arg-schema arg-schema})))
  (when (type-mismatch? (:type arg-schema) provided-value strict? max-unknown-types unknown-type-counter)
    (throw-type-mismatch! arg-schema provided-value)))

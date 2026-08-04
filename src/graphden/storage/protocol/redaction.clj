(ns graphden.storage.protocol.redaction
  "Sensitive-data redaction utilities used by error wrapping, log
   formatting, and any code that logs user-controlled data.

   Holds an extensible registry of sensitive field names / regex
   patterns / predicates. `redact-sensitive-map` and
   `redact-sensitive-deep` walk data structures and replace values under
   matching keys with `\"[REDACTED]\"`.

   Applications register custom fields via `register-sensitive-field-*`
   at startup (PII / HIPAA / GDPR scenarios) and can call
   `validate-sensitive-field-coverage!` to assert the critical set is
   wired in."
  (:require
    [clojure.string :as str]))


;; === Sensitive Data Redaction ===
;;
;; Shared utilities for redacting sensitive data in logs and exceptions.
;; All storage implementations should use these to ensure consistent security.
;;
;; IMPORTANT: Any new sensitive field patterns should be added here.
;; All storage backends and the executor use these patterns for consistent
;; security across the entire Graphden system.

;; === Extensible Sensitive Field Registry ===
;;
;; Applications can register custom sensitive field names and patterns
;; for domain-specific requirements (PII, HIPAA, GDPR, etc.).

(def ^:private default-sensitive-field-names
  "Default field names that should always be redacted."
  #{:password :secret :token :api-key :auth-token
    :access-key :private-key :jdbc-url :connection-string
    :credentials :passphrase :pin :ssn :credit-card})


(def ^:private default-sensitive-field-patterns
  "Default regex patterns for identifying sensitive field names."
  [#"(?i)pass(word|wd)?"
   #"(?i)secret"
   #"(?i)token"
   #"(?i)api[_-]?key"
   #"(?i)auth"
   #"(?i)credential"
   #"(?i)private[_-]?key"
   #"(?i)access[_-]?key"
   #"(?i)connection[_-]?string"
   #"(?i)jdbc[_-]?url"
   #"(?i)db[_-]?(password|user|pass)"
   #"(?i)database[_-]?(password|user|pass)"])


(def ^:private sensitive-field-registry
  "Extensible registry for sensitive field detection.
   Contains:
   - :names - set of explicit field name keywords
   - :patterns - vector of regex patterns
   - :predicates - vector of custom predicate functions"
  (atom {:names default-sensitive-field-names
         :patterns default-sensitive-field-patterns
         :predicates []}))


(def ^:dynamic *sensitive-fields-override*
  "Per-NS-thread override atom for parallel-test isolation. nil = use
   the process-global registry. The kaocha parallel plugin binds a
   fresh atom (seeded via `sensitive-fields-isolation-seed`) per
   NS-thread, so tests that register/reset sensitive fields mutate
   their own copy — `errors_test` registering `:employee-ssn` can no
   longer race a sibling NS's redaction mid-run."
  nil)


(defn- registry-atom
  []
  (or *sensitive-fields-override* sensitive-field-registry))


(defn sensitive-fields-isolation-seed
  "Seed for the plugin's per-thread override — the current GLOBAL
   registry state (explicitly not the override, which the plugin is
   about to bind), so the defaults and any boot-time registrations
   stay visible inside the isolated copy."
  []
  @sensitive-field-registry)


(defn register-sensitive-field-name!
  "Registers an explicit field name as sensitive.
   The name will be matched exactly (case-insensitive for keywords).

   Arguments:
   - field-name: keyword to treat as sensitive (e.g., :social-security-number)

   Example:
     (register-sensitive-field-name! :employee-id)
     (register-sensitive-field-name! :medical-record-number)"
  [field-name]
  (when-not (keyword? field-name)
    (throw (ex-info "field-name must be a keyword"
                    {:type :invalid-argument
                     :field-name field-name})))
  (swap! (registry-atom) update :names conj field-name)
  field-name)


(defn register-sensitive-field-pattern!
  "Registers a regex pattern for matching sensitive field names.
   Pattern will be tested against field name strings.

   Arguments:
   - pattern: compiled regex pattern (java.util.regex.Pattern)

   Example:
     (register-sensitive-field-pattern! #\"(?i)patient[_-]?id\")
     (register-sensitive-field-pattern! #\"(?i)hipaa\")
     (register-sensitive-field-pattern! #\"(?i)gdpr[_-]?data\")"
  [pattern]
  (when-not (instance? java.util.regex.Pattern pattern)
    (throw (ex-info "pattern must be a compiled regex"
                    {:type :invalid-argument
                     :pattern pattern
                     :pattern-type (type pattern)})))
  (swap! (registry-atom) update :patterns conj pattern)
  pattern)


(defn register-sensitive-field-predicate!
  "Registers a custom predicate function for sensitive field detection.
   The predicate receives a field name keyword and returns truthy if sensitive.

   Use this for complex matching logic that can't be expressed as regex.

   Arguments:
   - pred-fn: (fn [field-name-keyword] -> boolean)

   Example:
     ;; Mark all fields in specific namespace as sensitive
     (register-sensitive-field-predicate!
       (fn [k] (= \"pii\" (namespace k))))

     ;; Mark fields with certain metadata
     (register-sensitive-field-predicate!
       (fn [k] (some-> k resolve meta :sensitive)))"
  [pred-fn]
  (when-not (fn? pred-fn)
    (throw (ex-info "pred-fn must be a function"
                    {:type :invalid-argument
                     :pred-fn pred-fn})))
  (swap! (registry-atom) update :predicates conj pred-fn)
  pred-fn)


(defn reset-sensitive-field-registry!
  "Resets the sensitive field registry to defaults.
   Useful for testing or when reconfiguring the application.

   WARNING: This removes all custom registrations."
  []
  (reset! (registry-atom)
          {:names default-sensitive-field-names
           :patterns default-sensitive-field-patterns
           :predicates []})
  nil)


(defn get-sensitive-field-registry
  "Returns the current sensitive field registry state.
   Useful for saving state before modifications in tests."
  []
  @(registry-atom))


(defn set-sensitive-field-registry!
  "Sets the sensitive field registry to a specific state.
   Useful for restoring state after tests.

   Arguments:
   - state: map with :names, :patterns, :predicates keys"
  [state]
  (reset! (registry-atom) state)
  nil)


(defmacro with-sensitive-field-registry
  "Executes body with an isolated sensitive field registry.
   Saves the current registry state before body and restores it after.

   This macro ensures test isolation - any registrations made within
   the body are automatically cleaned up, preventing test pollution.

   Example:
     (with-sensitive-field-registry
       (register-sensitive-field-name! :test-field)
       (is (sensitive-field? :test-field)))
     ;; :test-field is no longer registered here

   For tests that need a completely clean registry:
     (with-sensitive-field-registry
       (reset-sensitive-field-registry!)
       ;; Only default fields are registered
       ...)"
  [& body]
  `(let [saved-state# (get-sensitive-field-registry)]
     (try
       (do ~@body)
       (finally
         (set-sensitive-field-registry! saved-state#)))))


(defn sensitive-field-names
  "Returns the current set of explicitly registered sensitive field names.
   Includes both default and custom registered names."
  []
  (:names @(registry-atom)))


(defn sensitive-field-patterns
  "Returns the current vector of sensitive field regex patterns.
   Includes both default and custom registered patterns."
  []
  (:patterns @(registry-atom)))


(defn sensitive-field?
  "Returns true if field name matches known sensitive patterns.
   Checks in order:
   1. Explicit field names (fast exact match)
   2. Regex patterns
   3. Custom predicate functions

   Handles keywords, strings, and nil gracefully.

   This function uses the extensible sensitive field registry.
   Use register-sensitive-field-name!, register-sensitive-field-pattern!,
   or register-sensitive-field-predicate! to add custom detection rules."
  [field-name]
  (when field-name
    (let [kw (if (keyword? field-name) field-name (keyword field-name))
          name-str (name kw)
          {:keys [names patterns predicates]} @(registry-atom)]
      (when (seq name-str)
        (or
          ;; Check explicit names first (fast exact match)
          (contains? names kw)
          ;; Then check patterns (regex matching)
          (some #(re-find % name-str) patterns)
          ;; Finally check custom predicates
          (some #(% kw) predicates))))))


;; === Critical sensitive field patterns ===
;; These are the minimum patterns that MUST be covered for security.

(def critical-sensitive-patterns
  "Critical patterns that must be matched for security.
   Used by validate-sensitive-field-coverage! to ensure minimum protection."
  #{:password :secret :token :api-key :credentials :auth-token :private-key})


(defn validate-sensitive-field-coverage!
  "Validates that all critical sensitive patterns are properly matched.
   Throws if any critical pattern would not be detected as sensitive.

   Use this at application startup to verify security configuration.

   Returns nil on success, throws on failure."
  []
  (let [unmatched (remove sensitive-field? critical-sensitive-patterns)]
    (when (seq unmatched)
      (throw (ex-info "Critical sensitive patterns not covered by registry"
                      {:type :security-error/incomplete-sensitive-field-coverage
                       :unmatched-patterns (set unmatched)
                       :hint "Ensure default sensitive field patterns are registered"})))))


(defn warn-on-suspicious-field
  "Logs a warning if a field name looks sensitive but isn't registered.
   Call this when logging/displaying data to catch potential misses.

   Returns true if field looks suspicious but not registered."
  [field-name]
  (when field-name
    (let [name-str (name (if (keyword? field-name) field-name (keyword field-name)))
          ;; Check for common sensitive-looking substrings not in registry
          suspicious-substrings ["key" "pwd" "pass" "cred" "secret" "token" "auth"]
          looks-suspicious? (some #(str/includes? (str/lower-case name-str) %)
                                  suspicious-substrings)]
      (when (and looks-suspicious? (not (sensitive-field? field-name)))
        ;; Log warning (lazy require to avoid circular deps)
        (require 'clojure.tools.logging)
        ((resolve 'clojure.tools.logging/warn)
         "Potentially sensitive field not in registry:" field-name)
        true))))


(defn redact-sensitive-map
  "Redacts values for sensitive keys in a map.
   Non-recursive - only checks top-level keys.
   Use redact-sensitive-deep for nested structures."
  [m]
  (when (map? m)
    (reduce-kv (fn [acc k v]
                 (assoc acc k (if (sensitive-field? k) "[REDACTED]" v)))
               {}
               m)))


(defn redact-sensitive-deep
  "Recursively redacts values for keys matching sensitive patterns.
   Preserves structure but replaces sensitive values with [REDACTED].
   Handles maps, vectors, lists, and sets. Other values pass through unchanged.

   Use this for logging/error messages that may contain nested sensitive data.
   For simple flat maps, redact-sensitive-map is more efficient."
  [data]
  (cond
    (map? data)
    (into {}
          (map (fn [[k v]]
                 [k (if (sensitive-field? k)
                      "[REDACTED]"
                      (redact-sensitive-deep v))])
               data))

    (vector? data)
    (mapv redact-sensitive-deep data)

    (set? data)
    (set (map redact-sensitive-deep data))

    (seq? data)
    (map redact-sensitive-deep data)

    :else data))

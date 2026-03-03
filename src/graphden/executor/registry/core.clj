(ns graphden.executor.registry.core
  "Core infrastructure for base function registration and storage sync.

   Provides:
   - Base function registration to executor
   - Storage synchronization for fn-schema and arg-schema
   - Deterministic UUID generation for idempotent sync

   Each base function is defined with metadata:
   - :args - map of {arg-name -> type} (or {:type :T :required false} for optional)
   - :return-type - the type returned by the function
   - :impl - the implementation function (receives delays, uses @ to deref)
   - :lazy - (optional) set of arg names that should NOT be auto-deref'd

   Use the defbase macro to define functions with automatic argument handling.

   ## Security Considerations (UUID v5)

   This namespace uses UUID v5 (RFC 4122 name-based SHA-1) for deterministic
   UUID generation. Key security properties:

   ### Why UUID v5 is Safe Here

   1. **Threat model**: We need deterministic, collision-resistant IDs for
      base function schemas. We do NOT need:
      - Unpredictability (IDs are not secrets)
      - Privacy (function names are public)
      - Cryptographic binding (IDs are just identifiers)

   2. **SHA-1 weakness irrelevant**: SHA-1's collision vulnerability requires
      attacker-controlled input and ~2^63 operations. Our inputs are:
      - Fixed namespace UUID (not attacker-controlled)
      - Function names from trusted code (not user input)

   3. **Collision resistance sufficient**: UUID v5 uses 122 bits of SHA-1 hash.
      For our use case (< 10,000 base functions), collision probability is
      negligible (~10^-33).

   ### Namespace UUID Confidentiality

   The namespace UUID (`base-fn-namespace-uuid`) is NOT a secret:
   - It's compiled into the application and visible in bytecode
   - Knowing it doesn't enable any attack (inputs are trusted)
   - Changing it would break all existing fn-schema references

   DO NOT treat this as a cryptographic key. It's a namespace identifier.

   ### DoS Protection (max-name-length)

   The 256-byte name limit prevents:
   - Memory exhaustion from very long strings
   - CPU exhaustion during SHA-1 hashing
   - Hash collision attacks (shorter inputs = fewer collision opportunities)

   Benchmark: SHA-1 processes ~500MB/s on modern CPUs.
   256 bytes takes ~0.5μs - negligible even for 10,000 functions."
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
    [graphden.executor.interface :as exec]
    [graphden.schema.fields.interface :as ft]
    [graphden.storage.protocol.interface :as sp])
  (:import
    (java.nio.charset
      StandardCharsets)
    (java.security
      MessageDigest)
    (java.util
      UUID)))


;; === Implementation Hash ===
;;
;; The impl-hash is a SHA-256 hash of the canonical form of a base function's
;; implementation. It enables detecting when a base function's behavior changes,
;; which is critical for version control of base functions.
;;
;; The hash is computed from:
;; - :args (map of argument specs, sorted by key)
;; - :return-type
;; - :impl-source (the original body forms before macro transformation)
;;
;; This means:
;; - Formatting changes (whitespace, newlines) don't affect the hash
;; - Comments don't affect the hash (they're not in the read form)
;; - Reordering args in the map doesn't affect the hash (sorted)
;; - Any semantic change (body, types, args) DOES affect the hash

(defn- sort-maps-recursively
  "Recursively sorts all maps by keys for stable hash computation."
  [form]
  (walk/postwalk
    (fn [x]
      (if (map? x)
        (into (sorted-map) x)
        x))
    form))


(defn compute-impl-hash
  "Computes SHA-256 hash of a base function's implementation.

   The hash is computed from a canonical form that includes:
   - :args - argument specifications (sorted by key)
   - :return-type - the return type
   - :impl-source - the original body forms (if provided)

   Returns a 64-character lowercase hex string (full SHA-256).

   This hash changes when:
   - Function body changes
   - Argument types change
   - Arguments are added/removed
   - Return type changes

   This hash does NOT change when:
   - Whitespace/formatting changes
   - Comments change
   - Order of args in map changes"
  [{:keys [args return-type impl-source]}]
  (let [canonical {:args (sort-maps-recursively args)
                   :return-type return-type
                   :impl-source (when impl-source
                                  (mapv sort-maps-recursively impl-source))}
        s (pr-str canonical)
        md (MessageDigest/getInstance "SHA-256")
        hash-bytes (MessageDigest/.digest md (String/.getBytes s StandardCharsets/UTF_8))]
    (str/join (map #(format "%02x" %) hash-bytes))))


(defn register-base-fns!
  "Registers base functions from a definitions map.

   Each definition should have:
   - :args - map of argument names to types
   - :return-type - the return type
   - :impl - function that takes [{:keys [arg1 arg2 ...]} ctx]

   The :impl function receives arguments as delays. Use defbase macro
   to automatically handle dereferencing."
  [defs]
  (doseq [[fn-name fn-def] defs]
    (exec/register-base-fn! fn-name (:impl fn-def))))


;; === Storage Sync ===
;;
;; Base functions are synced to storage with deterministic UUIDs generated
;; using UUID v5 (RFC 4122 name-based SHA-1). This ensures:
;;
;; 1. IDEMPOTENT SYNC: Running sync multiple times produces the same UUIDs,
;;    so existing records are updated rather than duplicated.
;;
;; 2. CROSS-ENVIRONMENT CONSISTENCY: The same base function has the same UUID
;;    in development, staging, and production environments.
;;
;; 3. STABLE REFERENCES: Code can reference base function UUIDs as constants
;;    knowing they won't change between deployments.
;;
;; IMPORTANT: The namespace UUID below is a fixed constant. Changing it would
;; generate different UUIDs for all base functions, breaking existing data.
;; If you need to change it (migration scenario), you must:
;; 1. Export all existing fn-schema/arg-schema mappings
;; 2. Update the namespace UUID
;; 3. Create a migration to update all references to the new UUIDs

(def ^:private base-fn-namespace-uuid
  "Namespace UUID for generating deterministic UUIDs for base functions.
   This is a fixed constant used as the namespace for UUID v5 generation.
   DO NOT CHANGE this value without a migration plan - it would break
   all existing base function references in storage."
  #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d")


(def ^:private max-name-length
  "Maximum allowed length for name strings in UUID v5 generation.
   Prevents DoS via extremely long strings that would consume
   excessive memory and CPU during SHA-1 hashing."
  256)


(defn- uuid-v5
  "Generates a UUID v5 (name-based SHA-1) from namespace UUID and name string.
   Returns a deterministic UUID for the same namespace+name combination.

   Security:
   - Validates namespace-uuid is a UUID
   - Validates name-str is a non-empty string
   - Limits name-str length to max-name-length (256) to prevent DoS
   - Rejects null bytes which could cause injection issues

   Optimized: creates byte array directly instead of using intermediate ByteBuffer
   for namespace UUID, reducing object allocations."
  [namespace-uuid name-str]
  (when-not (instance? UUID namespace-uuid)
    (throw (ex-info "namespace-uuid must be a UUID"
                    {:type :invalid-argument
                     :namespace-uuid namespace-uuid})))
  (when-not (string? name-str)
    (throw (ex-info "name-str must be a string"
                    {:type :invalid-argument
                     :name-str name-str})))
  (when (str/blank? name-str)
    (throw (ex-info "name-str must not be blank"
                    {:type :invalid-argument
                     :name-str name-str})))
  (when (> (count name-str) max-name-length)
    (throw (ex-info "name-str exceeds maximum length"
                    {:type :invalid-argument
                     :max-length max-name-length
                     :actual-length (count name-str)})))
  (when (str/includes? name-str "\u0000")
    (throw (ex-info "name-str contains null bytes"
                    {:type :invalid-argument})))
  (let [;; Convert namespace UUID to bytes directly (avoids intermediate ByteBuffer)
        ns-bytes (let [arr (byte-array 16)
                       buf (java.nio.ByteBuffer/wrap arr)]
                   (java.nio.ByteBuffer/.putLong buf (UUID/.getMostSignificantBits namespace-uuid))
                   (java.nio.ByteBuffer/.putLong buf (UUID/.getLeastSignificantBits namespace-uuid))
                   arr)
        name-bytes (String/.getBytes name-str StandardCharsets/UTF_8)
        digest (doto (MessageDigest/getInstance "SHA-1")
                 (MessageDigest/.update ns-bytes)
                 (MessageDigest/.update name-bytes))
        hash-bytes (MessageDigest/.digest digest)]
    ;; Set version to 5 (name-based SHA-1)
    (aset hash-bytes 6 (unchecked-byte (bit-or (bit-and (aget hash-bytes 6) 0x0f) 0x50)))
    ;; Set variant to RFC 4122
    (aset hash-bytes 8 (unchecked-byte (bit-or (bit-and (aget hash-bytes 8) 0x3f) 0x80)))
    ;; Build UUID from first 16 bytes
    (let [result-buf (java.nio.ByteBuffer/wrap hash-bytes 0 16)]
      (UUID. (java.nio.ByteBuffer/.getLong result-buf) (java.nio.ByteBuffer/.getLong result-buf)))))


(def ^:private memoized-uuid-v5
  "Memoized UUID v5 generation to avoid redundant SHA-1 calculations.
   Cache key is the name string; namespace UUID is always base-fn-namespace-uuid."
  (memoize (fn [name-str]
             (uuid-v5 base-fn-namespace-uuid name-str))))


(def ^:private identifier-pattern
  "Pattern for valid function/argument names.
   Must start with letter or underscore, contain only alphanumeric, underscore, hyphen.
   May end with ? for predicate functions (Clojure convention)."
  #"^[a-zA-Z_][a-zA-Z0-9_-]*\??$")


(defn- validate-identifier!
  "Validates that a name is a valid identifier for function or argument.
   Throws if invalid. Empty strings, special characters, etc. are rejected."
  [name-type name-value]
  (let [s (if (keyword? name-value) (name name-value) (str name-value))]
    (when (or (nil? s) (empty? s))
      (throw (ex-info (str name-type " cannot be empty")
                      {:type :invalid-identifier
                       :name-type name-type
                       :name-value name-value})))
    (when (> (count s) 128)
      (throw (ex-info (str name-type " exceeds maximum length of 128 characters")
                      {:type :invalid-identifier
                       :name-type name-type
                       :name-value s
                       :length (count s)})))
    (when-not (re-matches identifier-pattern s)
      (throw (ex-info (str name-type " contains invalid characters: must start with letter/underscore, contain only alphanumeric, underscore, hyphen")
                      {:type :invalid-identifier
                       :name-type name-type
                       :name-value s})))))


(defn fn-schema-uuid
  "Generates deterministic UUID for a base function's fn-schema.
   Validates that fn-name is a valid identifier.
   Results are memoized to avoid redundant SHA-1 calculations."
  [fn-name]
  (validate-identifier! "fn-name" fn-name)
  (memoized-uuid-v5 (str "fn-schema:" (name fn-name))))


(defn arg-schema-uuid
  "Generates deterministic UUID for a base function's arg-schema.
   Validates that both fn-name and arg-name are valid identifiers.
   Results are memoized to avoid redundant SHA-1 calculations."
  [fn-name arg-name]
  (validate-identifier! "fn-name" fn-name)
  (validate-identifier! "arg-name" arg-name)
  (memoized-uuid-v5 (str "arg-schema:" (name fn-name) ":" (name arg-name))))


(def ^:private valid-arg-types
  "Valid types for base function arguments.
   Includes all field types plus executor-specific types."
  (into ft/supported-types #{:any :fn}))


(defn- validate-arg-type!
  "Validates that arg-type is a known type. Throws if invalid."
  [arg-name arg-type]
  (when-not (contains? valid-arg-types arg-type)
    (throw (ex-info (str "Unknown arg type: " arg-type)
                    {:type :invalid-arg-type
                     :arg-name arg-name
                     :arg-type arg-type
                     :valid-types valid-arg-types}))))


(defn- parse-arg-spec
  "Parses an arg spec which can be either a keyword (type) or a map with :type and :required.
   Validates that the type is known. Throws if arg-spec is invalid."
  [arg-name arg-spec]
  (cond
    (keyword? arg-spec)
    (do
      (validate-arg-type! arg-name arg-spec)
      {:arg-type arg-spec :required true})

    (map? arg-spec)
    (if-let [arg-type (:type arg-spec)]
      (let [required-val (get arg-spec :required true)]
        (when-not (boolean? required-val)
          (throw (ex-info ":required must be a boolean"
                          {:type :invalid-arg-spec
                           :arg-name arg-name
                           :arg-spec arg-spec
                           :required-value required-val})))
        (validate-arg-type! arg-name arg-type)
        {:arg-type arg-type :required required-val})
      (throw (ex-info "arg-spec map must contain :type key"
                      {:type :invalid-arg-spec
                       :arg-name arg-name
                       :arg-spec arg-spec})))

    :else
    (throw (ex-info "arg-spec must be a keyword or map with :type"
                    {:type :invalid-arg-spec
                     :arg-name arg-name
                     :arg-spec arg-spec}))))


(defn validate-fn-def!
  "Validates a function definition before syncing to storage.
   Validates all arg specs and return type upfront to fail fast.
   Throws ExceptionInfo if validation fails.

   This allows validation to be performed separately from sync,
   enabling better error messages with full context."
  [fn-name {:keys [args return-type]}]
  (when-not (keyword? fn-name)
    (throw (ex-info "fn-name must be a keyword"
                    {:type :invalid-fn-def
                     :fn-name fn-name
                     :fn-name-type (type fn-name)})))
  (when-not return-type
    (throw (ex-info "Function definition must include :return-type"
                    {:type :invalid-fn-def
                     :fn-name fn-name})))
  (when-not (contains? valid-arg-types return-type)
    (throw (ex-info (str "Unknown return type: " return-type)
                    {:type :invalid-return-type
                     :fn-name fn-name
                     :return-type return-type
                     :valid-types valid-arg-types})))
  ;; Validate all args upfront
  (doseq [[arg-name arg-spec] args]
    (parse-arg-spec arg-name arg-spec)))


(defn- sync-fn-schema!
  "Syncs a single fn-schema to storage. Creates or updates.
   Computes and stores impl-hash for version tracking."
  [storage fn-name fn-def]
  (let [{:keys [return-type]} fn-def
        id (fn-schema-uuid fn-name)
        impl-hash (compute-impl-hash fn-def)
        existing (sp/read-entity storage :fn-schema id)]
    (if existing
      ;; Update if changed
      (let [new-data {:name (name fn-name)
                      :returned-type return-type
                      :base-fn-name (name fn-name)
                      :impl-hash impl-hash}]
        (when (or (not= (:name existing) (:name new-data))
                  (not= (:returned-type existing) (:returned-type new-data))
                  (not= (:base-fn-name existing) (:base-fn-name new-data))
                  (not= (:impl-hash existing) (:impl-hash new-data)))
          (sp/update-entity storage :fn-schema id new-data)))
      ;; Create new
      (sp/create-entity storage :fn-schema
                        {:id id
                         :name (name fn-name)
                         :returned-type return-type
                         :base-fn-name (name fn-name)
                         :impl-hash impl-hash}))
    id))


(defn- sync-arg-schemas!
  "Syncs arg-schemas for a function to storage.
   Sets first-class=true for :fn type args (HOF), false for others."
  [storage fn-name fn-schema-id args]
  (doseq [[arg-name arg-spec] args]
    (let [{:keys [arg-type required]} (parse-arg-spec arg-name arg-spec)
          ;; :fn type args are first-class (HOF - pass fn-id, don't execute)
          first-class? (= :fn arg-type)
          id (arg-schema-uuid fn-name arg-name)
          existing (sp/read-entity storage :arg-schema id)]
      (if existing
        ;; Update if changed
        (let [new-data {:fn-schema-id fn-schema-id
                        :name (name arg-name)
                        :type arg-type
                        :required required
                        :first-class first-class?}]
          (when (or (not= (:fn-schema-id existing) fn-schema-id)
                    (not= (:name existing) (:name new-data))
                    (not= (:type existing) (:type new-data))
                    (not= (:required existing) required)
                    (not= (:first-class existing) first-class?))
            (sp/update-entity storage :arg-schema id new-data)))
        ;; Create new
        (sp/create-entity storage :arg-schema
                          {:id id
                           :fn-schema-id fn-schema-id
                           :name (name arg-name)
                           :type arg-type
                           :required required
                           :first-class first-class?})))))


(defn validate-all-defs!
  "Validates all function definitions before syncing.
   Fails fast on first invalid definition.
   Call this before sync-defs-to-storage! for better error reporting.

   Throws ExceptionInfo with :type :invalid-fn-def, :invalid-arg-spec,
   :invalid-arg-type, or :invalid-return-type if validation fails."
  [defs]
  (doseq [[fn-name fn-def] defs]
    (validate-fn-def! fn-name fn-def)))


;; Use centralized limit from storage-protocol config
(def ^:private max-sync-batch-size
  "Maximum number of function definitions to sync in a single call.
   Uses centralized value from sp/max-sync-batch-size."
  sp/max-sync-batch-size)


(defn sync-defs-to-storage!
  "Syncs function definitions to storage.
   Creates fn-schema and arg-schema entries for each function.
   Uses deterministic UUIDs so syncing is idempotent.

   IMPORTANT: Validates all definitions before syncing.
   This ensures atomic behavior - either all definitions are valid and synced,
   or none are synced if any validation fails.

   Arguments:
   - storage: a storage instance that implements StorageCRUD
   - defs: map of {fn-name -> fn-def} where fn-def has :args, :return-type

   Returns a map with counts:
   {:fn-schemas {:created n :updated m}
    :arg-schemas {:created n :updated m}}

   Throws:
   - :batch-error/batch-too-large if defs count exceeds max-sync-batch-size (500)"
  [storage defs]
  ;; Validate batch size to prevent DoS
  (when (> (count defs) max-sync-batch-size)
    (throw (ex-info (str "Too many function definitions to sync: " (count defs)
                         " (max " max-sync-batch-size ")")
                    {:type :batch-error/batch-too-large
                     :batch-size (count defs)
                     :max-batch-size max-sync-batch-size
                     :operation :sync-defs-to-storage})))
  ;; Validate all definitions upfront for fail-fast behavior
  (validate-all-defs! defs)
  (let [fn-schema-stats (atom {:created 0 :updated 0})
        arg-schema-stats (atom {:created 0 :updated 0})]
    (doseq [[fn-name fn-def] defs]
      (let [fn-schema-id (fn-schema-uuid fn-name)
            existed? (some? (sp/read-entity storage :fn-schema fn-schema-id))]
        ;; Sync fn-schema
        (sync-fn-schema! storage fn-name fn-def)
        (if existed?
          (swap! fn-schema-stats update :updated inc)
          (swap! fn-schema-stats update :created inc))
        ;; Sync arg-schemas
        (doseq [[arg-name _] (:args fn-def)]
          (let [arg-id (arg-schema-uuid fn-name arg-name)
                arg-existed? (some? (sp/read-entity storage :arg-schema arg-id))]
            (sync-arg-schemas! storage fn-name fn-schema-id {arg-name (get-in fn-def [:args arg-name])})
            (if arg-existed?
              (swap! arg-schema-stats update :updated inc)
              (swap! arg-schema-stats update :created inc))))))
    {:fn-schemas @fn-schema-stats
     :arg-schemas @arg-schema-stats}))

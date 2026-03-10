(ns graphden.executor.registry.core
  "Core infrastructure for base function registration and storage sync.

   ## 2-Entity Schema

   Base functions are synced as:
   - fn entity with parent-id=nil (marks it as base-fn)
   - arg entities with source-id=nil (marks them as primary arguments)

   Deterministic UUID generation ensures idempotent sync.

   ## Security Considerations (UUID v5)

   Uses UUID v5 (RFC 4122 name-based SHA-1) for deterministic UUID generation.
   This is safe because:
   1. IDs are not secrets - they're just identifiers
   2. Inputs are from trusted code (not user input)
   3. Collision resistance is sufficient for our use case"
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
    [graphden.executor.interface :as exec]
    [graphden.schema.fields.types :as ft]
    [graphden.storage.protocol.core :as sp])
  (:import
    (java.nio.charset
      StandardCharsets)
    (java.security
      MessageDigest)
    (java.util
      UUID)))


;; === Implementation Hash ===

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
  "Computes SHA-256 hash of a base function's implementation."
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
  "Registers base functions from a definitions map."
  [defs]
  (doseq [[fn-name fn-def] defs]
    (exec/register-base-fn! fn-name (:impl fn-def))))


;; === Storage Sync ===

(def ^:private base-fn-namespace-uuid
  "Namespace UUID for generating deterministic UUIDs."
  #uuid "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d")


(def ^:private max-name-length 256)


(defn- uuid-v5
  "Generates a UUID v5 (name-based SHA-1) from namespace UUID and name string."
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
  (let [ns-bytes (let [arr (byte-array 16)
                       buf (java.nio.ByteBuffer/wrap arr)]
                   (java.nio.ByteBuffer/.putLong buf (UUID/.getMostSignificantBits namespace-uuid))
                   (java.nio.ByteBuffer/.putLong buf (UUID/.getLeastSignificantBits namespace-uuid))
                   arr)
        name-bytes (String/.getBytes name-str StandardCharsets/UTF_8)
        digest (doto (MessageDigest/getInstance "SHA-1")
                 (MessageDigest/.update ns-bytes)
                 (MessageDigest/.update name-bytes))
        hash-bytes (MessageDigest/.digest digest)]
    (aset hash-bytes 6 (unchecked-byte (bit-or (bit-and (aget hash-bytes 6) 0x0f) 0x50)))
    (aset hash-bytes 8 (unchecked-byte (bit-or (bit-and (aget hash-bytes 8) 0x3f) 0x80)))
    (let [result-buf (java.nio.ByteBuffer/wrap hash-bytes 0 16)]
      (UUID. (java.nio.ByteBuffer/.getLong result-buf) (java.nio.ByteBuffer/.getLong result-buf)))))


(def ^:private memoized-uuid-v5
  "Memoized UUID v5 generation."
  (memoize (fn [name-str]
             (uuid-v5 base-fn-namespace-uuid name-str))))


(def ^:private identifier-pattern
  #"^[a-zA-Z_][a-zA-Z0-9_-]*\??$")


(defn- validate-identifier!
  "Validates that a name is a valid identifier."
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
      (throw (ex-info (str name-type " contains invalid characters")
                      {:type :invalid-identifier
                       :name-type name-type
                       :name-value s})))))


(defn fn-uuid
  "Generates deterministic UUID for a base function."
  [fn-name]
  (validate-identifier! "fn-name" fn-name)
  (memoized-uuid-v5 (str "fn:" (name fn-name))))


(defn arg-uuid
  "Generates deterministic UUID for a base function's arg."
  [fn-name arg-name]
  (validate-identifier! "fn-name" fn-name)
  (validate-identifier! "arg-name" arg-name)
  (memoized-uuid-v5 (str "arg:" (name fn-name) ":" (name arg-name))))


(def ^:private valid-arg-types
  "Valid types for base function arguments."
  (into ft/supported-types #{:any :fn}))


(defn- validate-arg-type!
  "Validates that arg-type is a known type."
  [arg-name arg-type]
  (when-not (contains? valid-arg-types arg-type)
    (throw (ex-info (str "Unknown arg type: " arg-type)
                    {:type :invalid-arg-type
                     :arg-name arg-name
                     :arg-type arg-type
                     :valid-types valid-arg-types}))))


(defn- parse-arg-spec
  "Parses an arg spec which can be either a keyword (type) or a map."
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
  "Validates a function definition before syncing to storage."
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
  (doseq [[arg-name arg-spec] args]
    (parse-arg-spec arg-name arg-spec)))


(defn- prepare-fn-record
  "Prepares a fn record for batch upsert.
   Base fn = fn entity with parent-id=nil."
  [fn-name fn-def]
  (let [{:keys [return-type]} fn-def
        id (fn-uuid fn-name)
        impl-hash (compute-impl-hash fn-def)]
    {:id id
     :name (name fn-name)
     :return-type return-type
     :impl-hash impl-hash}))


(defn- prepare-arg-records
  "Prepares arg records for a base function.
   Primary args = arg entities with source-id=nil.
   Sets is-fn=true for :fn type args (HOF)."
  [fn-name fn-id args]
  (mapv (fn [[arg-name arg-spec]]
          (let [{:keys [arg-type required]} (parse-arg-spec arg-name arg-spec)
                is-fn? (= :fn arg-type)
                id (arg-uuid fn-name arg-name)]
            {:id id
             :fn-id fn-id
             :name (name arg-name)
             :type arg-type
             :required required
             :is-fn is-fn?}))
        args))


(defn validate-all-defs!
  "Validates all function definitions before syncing."
  [defs]
  (doseq [[fn-name fn-def] defs]
    (validate-fn-def! fn-name fn-def)))


(def ^:private max-sync-batch-size sp/max-sync-batch-size)


(defn- count-existing-ids
  "Counts how many ids already exist in storage."
  [storage entity-name ids]
  (if (empty? ids)
    0
    (count (sp/read-entities storage entity-name ids))))


(defn sync-defs-to-storage!
  "Syncs function definitions to storage using batch upsert.
   Creates fn and arg entities for each base function.
   Uses deterministic UUIDs so syncing is idempotent.

   Optimized: Uses batch INSERT ... ON CONFLICT DO UPDATE
   instead of individual queries (N+1 → 4 queries).

   Returns counts:
   {:fns {:created n :updated m} :args {:created n :updated m}}"
  [storage defs]
  (when (> (count defs) max-sync-batch-size)
    (throw (ex-info (str "Too many function definitions to sync: " (count defs)
                         " (max " max-sync-batch-size ")")
                    {:type :batch-error/batch-too-large
                     :batch-size (count defs)
                     :max-batch-size max-sync-batch-size
                     :operation :sync-defs-to-storage})))
  (validate-all-defs! defs)
  ;; Prepare all fn records
  (let [fn-records (mapv (fn [[fn-name fn-def]]
                           (prepare-fn-record fn-name fn-def))
                         defs)
        ;; Prepare all arg records
        arg-records (vec (mapcat (fn [[fn-name fn-def]]
                                   (let [fn-id (fn-uuid fn-name)]
                                     (prepare-arg-records fn-name fn-id (:args fn-def))))
                                 defs))
        ;; Count existing records (for created/updated stats)
        fn-ids (mapv :id fn-records)
        arg-ids (mapv :id arg-records)
        existing-fn-count (count-existing-ids storage :fn fn-ids)
        existing-arg-count (count-existing-ids storage :arg arg-ids)]
    ;; Batch upsert fns (single SQL statement)
    (when (seq fn-records)
      (sp/upsert-entities storage :fn fn-records))
    ;; Batch upsert args (single SQL statement)
    (when (seq arg-records)
      (sp/upsert-entities storage :arg arg-records))
    {:fns {:created (- (count fn-records) existing-fn-count)
           :updated existing-fn-count}
     :args {:created (- (count arg-records) existing-arg-count)
            :updated existing-arg-count}}))

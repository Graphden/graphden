(ns graphden.fn-registry.core
  "Core infrastructure for base function registration and storage sync.

   Provides:
   - Function definition wrapping with automatic arg forcing
   - Storage synchronization for fn-schema and arg-schema
   - Deterministic UUID generation for idempotent sync

   Each base function is defined with metadata:
   - :args - map of {arg-name -> type} (or {:type :T :required false} for optional)
   - :return-type - the type returned by the function
   - :impl - the implementation function
   - :lazy-args - (optional) set of arg names that receive thunks

   The wrapper automatically forces args based on their type:
   - :fn type args return fn-id (via LazyFnThunk.force-value)
   - :lazy-args receive thunks for manual forcing
   - All other args are forced to their values"
  (:require
    [graphden.executor.interface :as exec]
    [graphden.field-types.interface :as ft]
    [graphden.storage-protocol.interface :as sp])
  (:import
    (java.nio.charset
      StandardCharsets)
    (java.security
      MessageDigest)
    (java.util
      UUID)))


;; === Function Wrapper ===

(defn wrap-base-fn
  "Wraps a base function implementation to handle argument forcing.

   - Regular args: pre-forced, impl receives plain values
   - :fn type args: force-value returns fn-id, impl receives fn-id
   - :lazy-args: impl receives thunk, must call force-value manually"
  [{:keys [args impl lazy-args] :or {lazy-args #{}}}]
  ;; Validate that all lazy-args exist in args
  (when-let [unknown-lazy-args (seq (remove #(contains? args %) lazy-args))]
    (throw (ex-info "lazy-args contains unknown argument names"
                    {:type :invalid-lazy-args
                     :unknown-args (set unknown-lazy-args)
                     :valid-args (set (keys args))})))
  (fn [thunks ctx]
    (let [processed-args
          (reduce-kv
            (fn [acc arg-name _arg-type]
              (let [thunk (get thunks arg-name)]
                (cond
                  ;; Lazy arg - pass thunk as-is for manual forcing
                  (contains? lazy-args arg-name)
                  (assoc acc arg-name thunk)

                  ;; No thunk provided - skip
                  (nil? thunk)
                  acc

                  ;; Normal arg - force value
                  ;; Note: for :fn type, LazyFnThunk.force-value returns fn-id
                  :else
                  (assoc acc arg-name (exec/force-value thunk ctx)))))
            {}
            args)]
      (impl processed-args ctx))))


(defn register-base-fns!
  "Registers base functions from a definitions map.
   Each definition should have :args, :return-type, :impl, and optionally :lazy-args."
  [defs]
  (doseq [[fn-name fn-def] defs]
    (exec/register-base-fn! fn-name (wrap-base-fn fn-def))))


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


(defn- uuid-v5
  "Generates a UUID v5 (name-based SHA-1) from namespace UUID and name string.
   Returns a deterministic UUID for the same namespace+name combination."
  [namespace-uuid name-str]
  (when-not (instance? UUID namespace-uuid)
    (throw (ex-info "namespace-uuid must be a UUID"
                    {:type :invalid-argument
                     :namespace-uuid namespace-uuid})))
  (when-not (string? name-str)
    (throw (ex-info "name-str must be a string"
                    {:type :invalid-argument
                     :name-str name-str})))
  (let [ns-buf (doto (java.nio.ByteBuffer/allocate 16)
                 (java.nio.ByteBuffer/.putLong (UUID/.getMostSignificantBits namespace-uuid))
                 (java.nio.ByteBuffer/.putLong (UUID/.getLeastSignificantBits namespace-uuid)))
        name-bytes (String/.getBytes name-str StandardCharsets/UTF_8)
        digest (doto (MessageDigest/getInstance "SHA-1")
                 (MessageDigest/.update (java.nio.ByteBuffer/.array ns-buf))
                 (MessageDigest/.update name-bytes))
        hash-bytes (MessageDigest/.digest digest)]
    ;; Set version to 5 (name-based SHA-1)
    (aset hash-bytes 6 (unchecked-byte (bit-or (bit-and (aget hash-bytes 6) 0x0f) 0x50)))
    ;; Set variant to RFC 4122
    (aset hash-bytes 8 (unchecked-byte (bit-or (bit-and (aget hash-bytes 8) 0x3f) 0x80)))
    ;; Build UUID from first 16 bytes
    (let [result-buf (java.nio.ByteBuffer/wrap hash-bytes 0 16)]
      (UUID. (java.nio.ByteBuffer/.getLong result-buf) (java.nio.ByteBuffer/.getLong result-buf)))))


(defn fn-schema-uuid
  "Generates deterministic UUID for a base function's fn-schema."
  [fn-name]
  (uuid-v5 base-fn-namespace-uuid (str "fn-schema:" (name fn-name))))


(defn arg-schema-uuid
  "Generates deterministic UUID for a base function's arg-schema."
  [fn-name arg-name]
  (uuid-v5 base-fn-namespace-uuid (str "arg-schema:" (name fn-name) ":" (name arg-name))))


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


(defn- sync-fn-schema!
  "Syncs a single fn-schema to storage. Creates or updates."
  [storage fn-name {:keys [return-type]}]
  (let [id (fn-schema-uuid fn-name)
        existing (sp/read-entity storage :fn-schema id)]
    (if existing
      ;; Update if changed
      (let [new-data {:name (name fn-name)
                      :returned-type return-type
                      :base-fn-name (name fn-name)}]
        (when (or (not= (:name existing) (:name new-data))
                  (not= (:returned-type existing) (:returned-type new-data))
                  (not= (:base-fn-name existing) (:base-fn-name new-data)))
          (sp/update-entity storage :fn-schema id new-data)))
      ;; Create new
      (sp/create-entity storage :fn-schema
                        {:id id
                         :name (name fn-name)
                         :returned-type return-type
                         :base-fn-name (name fn-name)}))
    id))


(defn- sync-arg-schemas!
  "Syncs arg-schemas for a function to storage."
  [storage fn-name fn-schema-id args]
  (doseq [[arg-name arg-spec] args]
    (let [{:keys [arg-type required]} (parse-arg-spec arg-name arg-spec)
          id (arg-schema-uuid fn-name arg-name)
          existing (sp/read-entity storage :arg-schema id)]
      (if existing
        ;; Update if changed
        (let [new-data {:fn-schema-id fn-schema-id
                        :name (name arg-name)
                        :type arg-type
                        :required required}]
          (when (or (not= (:fn-schema-id existing) fn-schema-id)
                    (not= (:name existing) (:name new-data))
                    (not= (:type existing) (:type new-data))
                    (not= (:required existing) required))
            (sp/update-entity storage :arg-schema id new-data)))
        ;; Create new
        (sp/create-entity storage :arg-schema
                          {:id id
                           :fn-schema-id fn-schema-id
                           :name (name arg-name)
                           :type arg-type
                           :required required})))))


(defn sync-defs-to-storage!
  "Syncs function definitions to storage.
   Creates fn-schema and arg-schema entries for each function.
   Uses deterministic UUIDs so syncing is idempotent.

   Arguments:
   - storage: a storage instance that implements StorageCRUD
   - defs: map of {fn-name -> fn-def} where fn-def has :args, :return-type

   Returns a map with counts:
   {:fn-schemas {:created n :updated m}
    :arg-schemas {:created n :updated m}}"
  [storage defs]
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

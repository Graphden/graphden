(ns graphden.storage.protocol.core
  "Protocol for storage implementations.

   Storage brings itself into sync with a DataSchema through the
   initialize function. It uses UUIDs to detect renames vs add/delete.

   Key concepts:
   - Storage reads the current state via StorageIntrospection methods
   - UUID mappings stored in _schema_metadata table enable rename detection
   - Destructive changes (removing entities/fields/enums) throw errors
   - Safe type widening (int→numeric, text→jsonb) is allowed

   The typical flow:
   1. Create storage instance (implementation-specific)
   2. Call (initialize storage schema) to sync with DataSchema
   3. Use storage for CRUD operations
   4. Call (close storage) when done

   ## Facade Pattern (Re-exports)

   This namespace serves as a unified facade for the storage-protocol component,
   re-exporting functions from internal modules (errors, config, validation, etc.).
   This is an intentional component pattern that provides:

   - **Single import point** - Users only need `[graphden.storage.protocol.core :as sp]`
   - **Stable API** - Internal refactoring doesn't break user code
   - **Discoverability** - All public functions visible in one place

   Internal modules (not for direct import by users):
   - `errors.clj` - Error types, classification, sensitive data redaction
   - `config.clj` - Dynamic vars, timeouts, limits
   - `validation.clj` - Input validation helpers
   - `constraints.clj` - Graph constraint implementations
   - `graph.clj` - BFS traversal, execution graph resolution
   - `metadata.clj` - Schema migration helpers
   - `naming.clj` - Keyword/snake_case conversions
   - `locks.clj` - Read-write lock utilities
   - `codec.clj` - Value encoding/decoding utilities

   ## Naming Conventions

   Functions follow these naming patterns:
   - `get-*` - Returns value or nil if not found (optional lookup)
   - `read-*` - Returns value or throws if not found (required lookup)
   - `create-*` / `update-*` / `delete-*` - Mutating operations
   - `query-*` - Returns collection (possibly empty)
   - `validate-*!` - Validates input, throws on failure (public API)
   - `check-*!` - Checks conditions, throws on failure (migration/internal)
   - `*-impl` - Internal implementation (not for direct use)

   ## Security

   Sensitive data is automatically redacted in logs and error messages.
   Use `register-sensitive-field-name!` to add application-specific fields.
   Default patterns cover: password, secret, token, api-key, credentials.

   ## Query Timeout

   All storage backends read the query timeout from
   `storage.protocol.config/*query-timeout-ms*` (default: 30s);
   `get-query-timeout-seconds` converts it for JDBC."
  (:require
    [graphden.schema.fields.types :as ft]
    [graphden.storage.protocol.codec :as codec]
    [graphden.storage.protocol.config :as config]
    [graphden.storage.protocol.constraints :as constraints]
    [graphden.storage.protocol.errors :as errors]
    [graphden.storage.protocol.graph :as graph]
    [graphden.storage.protocol.metadata :as metadata]
    [graphden.storage.protocol.naming :as naming]
    [graphden.storage.protocol.redaction :as redaction]
    [graphden.storage.protocol.validation :as validation]))


;; ============================================================================
;; PROTOCOL DEFINITIONS
;; ============================================================================

(defprotocol Storage
  "Protocol for data storage backends."

  (initialize
    [this schema]
    "Initializes/migrates storage to match the given DataSchema.")

  (close
    [this]
    "Releases storage resources."))


(defprotocol StorageIntrospection
  "Protocol for reading storage state."

  (current-entities
    [this]
    "Returns set of entity names currently in storage.")

  (current-fields
    [this entity-name]
    "Returns map of field definitions for entity.")

  (current-enums
    [this]
    "Returns set of enum type names in storage.")

  (current-enum-values
    [this enum-name]
    "Returns set of keyword values for enum.")

  (schema-metadata
    [this]
    "Returns stored UUID→name mappings."))


(defprotocol StorageCRUD
  "Protocol for CRUD operations on stored entities."

  (create-entity
    [this entity-name data]
    "Creates a new entity record.")

  (read-entity
    [this entity-name id]
    "Reads an entity record by ID.")

  (update-entity
    [this entity-name id data]
    "Updates an existing entity record.")

  (delete-entity
    [this entity-name id]
    "Deletes an entity record by ID.")

  (query-entities
    [this entity-name where]
    [this entity-name where opts]
    "Queries entities matching the given criteria.

     Three-arg form is the original predicate-only query.

     Four-arg form additionally accepts `opts`:
       {:order-by [[:column :asc|:desc] ...]   ; nil → unordered
        :limit    n                            ; nil → unbounded
        :offset   n}                           ; nil → 0
     `opts` may be nil or empty. Backends that wrap a base storage
     (e.g. VersionedStorage) MAY refuse `opts` on entities whose
     semantics require post-query resolution (currently versioned
     entities) by throwing `:storage-error/unsupported-opts`.")

  (query-latest-per-group
    [this entity-name where group-cols]
    "Returns ONE row per distinct `group-cols` tuple — the row with
     the latest `:created-at`. Same `where`-semantics as
     `query-entities`. `group-cols` is a non-empty vector of keyword
     column names (e.g. `[:fn-id :branch-id]`).

     Why this lives at the protocol level: versioned reads only ever
     need the latest version per (entity-id, branch-id). Without a
     dedup-at-source path, `query-entities` returns ALL historical
     versions and the resolution layer drops most of them on the
     floor — fine in tests, OOM in long-running executors.

     Backends that can't push the dedup to the storage engine (e.g.
     a hypothetical pure-in-memory backend) MAY implement this as
     `query-entities` + in-memory grouping. The contract is the
     result, not the mechanism."))


(defprotocol StorageBatchCRUD
  "Protocol for batch CRUD operations."

  (create-entities
    [this entity-name data-seq]
    "Creates multiple entity records.")

  (read-entities
    [this entity-name ids]
    "Reads multiple entity records by IDs. Returns `{id → record}` for the
     found rows (empty map when none match) — callers deref via `(vals …)`,
     so implementations MUST return a map, not a seq of rows.")

  (update-entities
    [this entity-name data-seq]
    "Updates multiple entity records. Each record must have :id.")

  (upsert-entities
    [this entity-name data-seq]
    "Inserts or updates multiple entity records (INSERT ... ON CONFLICT DO UPDATE).
     Each record must have :id. Returns seq of upserted records.")

  (delete-entities
    [this entity-name ids]
    "Deletes multiple entity records.")

  (query-ref-many-owners
    [this entity-name field-name target-id]
    "For a `:ref-many` `field-name` on `entity-name`, return the
     vector of OWNER ids that have `target-id` in that field's
     junction table. Backed by the reverse junction-table index
     (`idx_<jt>_target`) so it's O(log n) on the target column instead
     of an O(N) entity-table scan.

     Used by reverse-dependency checks (the delete-guard
     `find-fn-usages` chain) and anywhere else that needs to walk a
     ref-many edge upstream."))


(defprotocol GraphConstraints
  "Constraints for graph integrity."

  (validate-no-dependency-cycle!
    [this owner-fn-id ref-fn-id]
    "Validates that referencing ref-fn does not create dependency cycle."))


(defprotocol ConstraintHelpers
  "Helper protocol for constraint validation."

  (collect-dependency-chain
    [this fn-id]
    "Returns a set of all fn-ids that fn-id depends on (transitive)."))


(defprotocol StorageValueCodec
  "Protocol for encoding/decoding values between Clojure and storage backend."

  (encode-value
    [this value field-spec]
    "Encodes a Clojure value for storage.")

  (decode-value
    [this value field-spec]
    "Decodes a storage value to Clojure.")

  (encode-row
    [this row field-specs]
    "Encodes all values in a row map for storage.")

  (decode-row
    [this row field-specs]
    "Decodes all values in a storage row to Clojure."))


(defprotocol StorageErrorClassifier
  "Protocol for classifying storage-specific errors."

  (classify-error
    [this exception]
    "Classifies a storage exception into canonical error type.")

  (wrap-error
    [this exception operation context]
    "Wraps a storage exception with application context."))


(defprotocol ExecutionGraph
  "Protocol for retrieving complete execution graph for a function."

  (resolve-execution-graph
    [this fn-id]
    "Resolves the complete execution graph for a function."))


;; ============================================================================
;; CONSTRAINT HELPER IMPLEMENTATIONS
;; ============================================================================

(defn validate-no-dependency-cycle-impl
  "Shared implementation of no-dependency-cycle validation."
  [helpers owner-fn-id ref-fn-id]
  (constraints/validate-no-dependency-cycle-impl
    collect-dependency-chain helpers owner-fn-id ref-fn-id))


;; ============================================================================
;; RE-EXPORTS FROM HELPER MODULES
;; ============================================================================

;; === Error re-exports ===
(def redact-sensitive-map redaction/redact-sensitive-map)
(def redact-sensitive-deep redaction/redact-sensitive-deep)


(def wrap-storage-error
  "Wraps an exception with application context, redacts before logging.
   See errors/wrap-storage-error for details."
  errors/wrap-storage-error)


;; === Error Registry ===
;;
;; Extensible error type registry for custom application errors.
;; Pre-registered types cover common storage scenarios.

(def register-error-type!
  "Registers a custom error type with metadata.
   See errors/register-error-type! for details."
  errors/register-error-type!)


(def get-error-metadata
  "Returns metadata for an error type, or nil if not registered."
  errors/get-error-metadata)


(def error-retryable?
  "Returns true if the error type is marked as retryable."
  errors/error-retryable?)


(def error-category
  "Returns the category of an error type."
  errors/error-category)


(def registered-error-types
  "Returns a set of all registered error type keywords."
  errors/registered-error-types)


;; === Sensitive Field Registry ===

(def register-sensitive-field-name!
  "Registers an explicit field name as sensitive for redaction.
   See redaction/register-sensitive-field-name! for details."
  redaction/register-sensitive-field-name!)


(def register-sensitive-field-pattern!
  "Registers a regex pattern for matching sensitive field names.
   See redaction/register-sensitive-field-pattern! for details."
  redaction/register-sensitive-field-pattern!)


(def register-sensitive-field-predicate!
  "Registers a custom predicate for sensitive field detection.
   See redaction/register-sensitive-field-predicate! for details."
  redaction/register-sensitive-field-predicate!)


(def reset-sensitive-field-registry!
  "Resets sensitive field registry to defaults. Use with caution."
  redaction/reset-sensitive-field-registry!)


(def sensitive-field?
  "Returns true if field name matches sensitive patterns.
   Checks explicit names, regex patterns, and custom predicates."
  redaction/sensitive-field?)


;; === Metadata re-exports ===
;;
;; Public protocol surface for schema-type compatibility checks.
;; Implementations live in `storage.protocol.metadata`; the storage
;; protocol re-exports them here so callers (`postgres/migration`,
;; protocol tests) don't reach into the metadata helper namespace.

(defn types-equivalent?
  [t1 t2]
  (ft/types-equivalent? t1 t2))


(defn safe-type-change?
  [old-type new-type]
  (metadata/safe-type-change? old-type new-type))


(defn safe-nullable-change?
  [old-nullable? new-nullable?]
  (metadata/safe-nullable-change? old-nullable? new-nullable?))


(def check-removed! metadata/check-removed!)
(def warn-removed! metadata/warn-removed!)
(def check-type-change! metadata/check-type-change!)
(def check-nullable-change! metadata/check-nullable-change!)
(def build-metadata-from-schema metadata/build-metadata-from-schema)
(def build-first-init-changes metadata/build-first-init-changes)
(def check-all-removals! metadata/check-all-removals!)
(def compute-entity-changes metadata/compute-entity-changes)
(def compute-field-changes metadata/compute-field-changes)
(def compute-enum-changes metadata/compute-enum-changes)
(def compute-enum-value-changes metadata/compute-enum-value-changes)


;; === Validation re-exports ===
(def validate-required-fields! validation/validate-required-fields!)
(def validate-no-duplicate-ids! validation/validate-no-duplicate-ids!)
(def validate-data-is-map! validation/validate-data-is-map!)
(def validate-where-clause! validation/validate-where-clause!)
(def validate-where-clause-fields! validation/validate-where-clause-fields!)
(def validate-where-clause-types! validation/validate-where-clause-types!)
(def validate-entity-name! validation/validate-entity-name!)
(def validate-credential-length! validation/validate-credential-length!)
(def validate-no-control-chars! validation/validate-no-control-chars!)
(def validate-credentials! validation/validate-credentials!)
(def validate-jdbc-url! validation/validate-jdbc-url!)
(def canonical-field-types validation/canonical-field-types)


(defn canonical-type?
  [type-kw]
  (validation/canonical-type? type-kw))


(def type-category validation/type-category)


(defn reference-type?
  [type-kw]
  (validation/reference-type? type-kw))


(defn complex-type?
  [type-kw]
  (validation/complex-type? type-kw))


;; === Graph re-exports ===
;; `default-query-timeout-ms` is the only constant external callers
;; (tests, in particular) reach via this namespace.
(def default-query-timeout-ms config/default-query-timeout-ms)


;; Re-export dynamic var
(def ^:dynamic *max-graph-iterations*
  "Maximum iterations for graph resolution. Binds to graph/*max-graph-iterations*."
  10000)


(defn with-max-graph-iterations
  "Binds *max-graph-iterations* in both this namespace and graph namespace."
  [limit f]
  (binding [*max-graph-iterations* limit
            graph/*max-graph-iterations* limit]
    (f)))


;; === Naming re-exports ===
(def kw->snake-case naming/kw->snake-case)
(def snake->kw naming/snake->kw)
(def check-snake-case-collisions! naming/check-snake-case-collisions!)


;; === Query timeout re-export ===

(def get-query-timeout-seconds config/get-query-timeout-seconds)


;; === Collection generation limits re-exports ===
(def ^:dynamic *max-range-size*
  "Maximum elements in range to prevent memory exhaustion. Default: 1000000."
  config/*max-range-size*)


(def ^:dynamic *max-repeat-size*
  "Maximum elements in repeat to prevent memory exhaustion. Default: 1000000."
  config/*max-repeat-size*)


;; === Batch size validation re-exports ===
(def ^:dynamic *max-batch-size*
  "Maximum entities in a single batch operation. Default: 10000
   (see `config/*max-batch-size*`, the value this re-exports)."
  config/*max-batch-size*)


(def validate-batch-size!
  "Validates batch size is within allowed limits. Throws if exceeded."
  config/validate-batch-size!)


;; ============================================================================
;; ADDITIONAL HELPERS
;; ============================================================================

(defn standard-crud-normalize-data
  "Apply entity-specific normalization rules to a CRUD write payload
   before validation + storage. Returns the (possibly-augmented) data
   map.

   Currently one rule:
     `:binding` rows with `:value` written but no `:value-present`
     flag get `:value-present true`. Mirrors the parser's contract
     (`packages/records/parse.clj`) — the writer who passes `:value`,
     even nil, intends a value-binding. Without this, callers (tests,
     CRUD handlers, any direct sp/create-entity user) would silently
     produce rows the executor's classifier treats as `:free`,
     leaking the caller's `fa[<slot-name>]` (Ring request, etc.) into
     the slot. Centralised here so every storage backend picks it up
     without each call-site having to remember."
  [entity-name data]
  (cond-> data
    (and (= entity-name :binding)
         (contains? data :value)
         (not (contains? data :value-present)))
    (assoc :value-present true)))


(defn standard-crud-validations!
  "Performs standard validations for CRUD operations."
  [entity-name data fields]
  (validate-data-is-map! entity-name data)
  (when fields
    (validate-required-fields! entity-name fields data)))


(defn standard-query-validations!
  "Performs standard validations for query operations.
   Validates the where clause structure, field names, and value types."
  [entity-name fields where]
  (validate-where-clause! where)
  (when fields
    (validate-where-clause-fields! entity-name fields where)
    (validate-where-clause-types! entity-name fields where)))


(defn- valid-opts-key?
  [k]
  (#{:order-by :limit :offset} k))


(defn validate-query-opts!
  "Validate the optional 4-th arg `opts` map of `query-entities`.
   Allowed keys: `:order-by` `:limit` `:offset`. Throws an
   `ExceptionInfo` with `:type :storage-error/invalid-opts` on a bad
   shape. nil/empty opts pass."
  [entity-name opts]
  (when (some? opts)
    (when-not (map? opts)
      (throw (ex-info ":opts must be a map (or nil)"
                      {:type :storage-error/invalid-opts
                       :entity-name entity-name :opts opts})))
    (doseq [k (keys opts)]
      (when-not (valid-opts-key? k)
        (throw (ex-info (str "Unknown :opts key " k)
                        {:type :storage-error/invalid-opts
                         :entity-name entity-name :opts opts :bad-key k}))))
    (when-let [ob (:order-by opts)]
      (when-not (and (sequential? ob)
                     (every? (fn [pair]
                               (and (sequential? pair) (= 2 (count pair))
                                    (keyword? (first pair))
                                    (#{:asc :desc} (second pair))))
                             ob))
        (throw (ex-info ":order-by must be a seq of [column :asc/:desc] pairs"
                        {:type :storage-error/invalid-opts
                         :entity-name entity-name :order-by ob}))))
    (doseq [k [:limit :offset]]
      (when-let [v (get opts k)]
        (when-not (and (integer? v) (not (neg? v)))
          (throw (ex-info (str k " must be a non-negative integer")
                          {:type :storage-error/invalid-opts
                           :entity-name entity-name k v})))))))


(defn wrap-batch-error
  "Wraps an exception with batch context information."
  ([exception index batch-size]
   (wrap-batch-error exception index batch-size nil))
  ([exception index batch-size failed-id]
   (let [base-data (if (instance? clojure.lang.ExceptionInfo exception)
                     (ex-data exception)
                     {:type :batch-error/partial-failure})
         batch-data (cond-> {:batch-index index
                             :batch-size batch-size}
                      failed-id (assoc :failed-id failed-id))]
     (ex-info (ex-message exception)
              (merge base-data batch-data)
              exception))))


(defn initialize-with-cleanup!
  "Initializes storage with schema, cleaning up on failure."
  [storage schema]
  (try
    (initialize storage schema)
    storage
    (catch Exception e
      (close storage)
      (throw e))))


;; === Codec utilities re-exports ===

(def generic-encode-row
  "Generic row encoding that applies encode-value to each field.
   See codec/generic-encode-row for details."
  codec/generic-encode-row)


(def generic-decode-row
  "Generic row decoding that applies decode-value to each field.
   See codec/generic-decode-row for details."
  codec/generic-decode-row)

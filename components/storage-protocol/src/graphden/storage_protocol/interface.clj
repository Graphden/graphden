(ns graphden.storage-protocol.interface
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
   3. Use storage for CRUD operations (future protocol methods)
   4. Call (close storage) when done

   ## Canonical Error Types

   All storage implementations MUST use these error types for consistency.
   Error types use namespaced keywords for categorization.

   ### CRUD Errors
   - :not-found                  - Entity/record not found by ID
   - :entity-not-in-schema       - Entity name not defined in schema
   - :invalid-data               - Data is not a map or has wrong shape
   - :invalid-where-clause       - Where clause is not a map or nil
   - :batch-insert-mismatch      - Batch insert returned wrong count

   ### Constraint Violations (prefix :constraint-violation/)
   - :constraint-violation/unique              - Unique constraint violated
   - :constraint-violation/parent-schema-mismatch - Parent fn has different schema
   - :constraint-violation/arg-already-defined - Arg already defined in parent chain
   - :constraint-violation/arg-schema-mismatch - Arg schema doesn't belong to fn
   - :constraint-violation/inheritance-cycle   - Cycle in parent-fn-id chain
   - :constraint-violation/dependency-cycle    - Cycle in arg-value references

   ### Validation Errors (prefix :validation-error/)
   - :validation-error/required-field-missing   - Required field not provided
   - :validation-error/duplicate-ids            - Duplicate IDs in batch
   - :validation-error/constraint-check-failed  - Constraint validation query failed
   - :validation-error/naming-collision         - Multiple names map to same SQL identifier
   - :validation-error/identifier-too-long      - SQL identifier exceeds length limit
   - :validation-error/invalid-identifier       - Invalid SQL identifier format
   - :validation-error/invalid-pg-type          - Invalid PostgreSQL type specification

   ### Configuration Errors (prefix :config-error/)
   - :config-error/invalid-timeout  - Invalid timeout value
   - :config-error/missing-jdbc-url - JDBC URL not provided
   - :config-error/invalid-pool-*   - Pool configuration issues

   ### Migration Errors
   - :destructive-change              - Attempt to remove entity/field/enum

   ### Metadata Errors (prefix :metadata-error/)
   - :metadata-error/inconsistency    - Metadata doesn't match DB state
   - :metadata-error/corrupted        - Metadata is corrupted
   - :metadata-error/rollback-failed  - Migration rollback failed

   ### Execution Errors (prefix :execution-error/)
   - :execution-error/graph-too-large - Graph resolution exceeded max iterations

   ### Storage State Errors
   - :storage-not-initialized         - CRUD attempted before initialize

   ## Logging Level Policy

   All storage implementations SHOULD follow this logging policy:

   - ERROR: Critical failures affecting system stability
     - Failed to close resources (connection pool, etc.)
     - Storage not initialized when operation attempted
     - Metadata rollback failures

   - WARN: Recoverable issues or fallback behavior
     - Using fallback values (e.g., default timeout)
     - Constraint violations (before throwing)
     - Approaching limits (iteration count, etc.)

   - INFO: Major lifecycle events
     - Storage initialization/closing
     - Migration completed
     - Schema changes applied

   - DEBUG: Internal details for troubleshooting
     - Pool created/closed successfully
     - Metadata operations
     - Query details

   ## Naming Conventions

   ### The `-impl` Suffix

   Functions with the `-impl` suffix provide shared implementation logic that
   storage backends can delegate to. There are two categories:

   1. **Public shared implementations** (in this namespace):
      Functions like `validate-parent-same-schema-impl`, `validate-no-arg-override-impl`
      provide reusable validation logic that uses the ConstraintHelpers protocol.
      Storage backends call these from their own validation functions.

   2. **Private protocol implementations** (in storage backends):
      Functions like `resolve-execution-graph-impl`, `create-entity-impl` contain
      the core logic for protocol methods. The actual protocol method (in defrecord)
      typically just delegates to the `-impl` function, possibly with error handling
      or logging wrappers.

   This pattern separates the pure implementation logic from protocol boilerplate,
   making the code easier to test and maintain."
  (:require
    [clojure.set :as set]
    [clojure.tools.logging :as log]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.field-types.interface :as ft])
  (:import
    (java.util.concurrent.locks
      Lock
      ReentrantReadWriteLock)))


(defprotocol Storage
  "Protocol for data storage backends."

  (initialize
    [this schema]
    "Initializes/migrates storage to match the given DataSchema.
     Uses UUID-based tracking to detect renames.

     Process:
     1. Read current storage state via StorageIntrospection methods
     2. Read stored metadata (_schema_metadata)
     3. Compute diff between schema and current state using UUIDs
     4. Apply changes (create tables, add columns, rename, create indexes)
     5. Update _schema_metadata

     Returns a map describing changes made:
     {:entities {:created [:name ...] :renamed {old-name new-name ...}}
      :fields {:created [{:entity e :field f} ...] :renamed [...]}
      :enums {:created [:name ...] :renamed {...}}
      :enum-values {:created [{:enum e :value v} ...]}
      :constraints {:created [{:entity e :constraint c} ...]}}

     Throws ExceptionInfo on destructive changes:
     - Removed entity (UUID in metadata but not in schema)
     - Removed field
     - Removed enum
     - Removed enum value
     - Incompatible type change (e.g., text→int)")

  (close
    [this]
    "Releases storage resources (connections, handles).
     Should be called when storage is no longer needed.
     Implementations should be idempotent (safe to call multiple times)."))


(defprotocol StorageIntrospection
  "Protocol for reading storage state.
   Used by initialize to compute diff with target schema.

   Return value semantics:
   Collection queries (list all items):
   - current-entities: empty set if none exist, never nil
   - current-enums: empty set if none exist, never nil

   Lookup queries (get specific item):
   - current-fields: nil if entity not found, empty map if entity has no fields
   - current-enum-values: nil if enum not found, empty set if enum has no values

   State query:
   - schema-metadata: nil if storage not yet initialized

   This distinction allows callers to differentiate 'not found' from 'empty'."

  (current-entities
    [this]
    "Returns set of entity names currently in storage.
     Does not include internal tables (like _schema_metadata).
     Returns empty set if storage is empty, never nil.")

  (current-fields
    [this entity-name]
    "Returns map of field definitions for entity.
     Shape: {field-name {:type :text :nullable? false} ...}
     The implicit :id field is not included.
     Returns nil if entity doesn't exist in storage.")

  (current-enums
    [this]
    "Returns set of enum type names in storage.
     Returns empty set if no enums defined, never nil.")

  (current-enum-values
    [this enum-name]
    "Returns set of keyword values for enum.
     Returns nil if enum doesn't exist, empty set if enum has no values.")

  (schema-metadata
    [this]
    "Returns stored UUID→name mappings.
     Shape: {:entities {uuid entity-name ...}
             :fields {uuid {:entity entity-name :field field-name
                            :type field-type :nullable? bool} ...}
             :enums {uuid enum-name ...}
             :enum-values {uuid {:enum enum-name :value value-keyword} ...}}
     Returns nil if storage has not been initialized yet."))


(defprotocol StorageCRUD
  "Protocol for CRUD operations on stored entities.
   All operations work with entity data as maps.
   The :id field is always a UUID and is the primary key."

  (create-entity
    [this entity-name data]
    "Creates a new entity record.
     If :id is not provided, a random UUID is generated.

     Arguments:
     - entity-name: keyword name of the entity (e.g., :fn-schema, :fn)
     - data: map of field values (without :id, or with :id to use specific UUID)

     Returns the created record with :id.
     Throws ExceptionInfo if validation fails or constraints are violated.")

  (read-entity
    [this entity-name id]
    "Reads an entity record by ID.

     Arguments:
     - entity-name: keyword name of the entity
     - id: UUID of the record

     Returns the record as a map, or nil if not found.")

  (update-entity
    [this entity-name id data]
    "Updates an existing entity record.

     Arguments:
     - entity-name: keyword name of the entity
     - id: UUID of the record to update
     - data: map of fields to update (partial update supported)

     Returns the updated record.
     Throws ExceptionInfo if record not found or constraints are violated.")

  (delete-entity
    [this entity-name id]
    "Deletes an entity record by ID.

     Arguments:
     - entity-name: keyword name of the entity
     - id: UUID of the record to delete

     Returns true if deleted, false if not found.
     Throws ExceptionInfo if referential integrity would be violated.")

  (query-entities
    [this entity-name where]
    "Queries entities matching the given criteria.

     Arguments:
     - entity-name: keyword name of the entity
     - where: map of field->value for exact match, or nil for all records

     Returns a sequence of matching records (may be empty).
     Example: (query-entities storage :fn {:fn-schema-id some-uuid})"))


(defprotocol StorageBatchCRUD
  "Protocol for batch CRUD operations on stored entities.
   Companion to StorageCRUD (single-entity operations).
   Provides more efficient operations when working with multiple records.
   Implementations should optimize for bulk operations (batch inserts, IN clauses, etc.).

   ATOMICITY GUARANTEES:
   - create-entities: All-or-nothing. If any record fails validation or
     constraint check, NO records are created.
   - read-entities: Isolation depends on storage backend (see below).
   - delete-entities: All-or-nothing. If any deletion would violate referential
     integrity, NO records are deleted.

   ISOLATION GUARANTEES BY BACKEND:

   Memory storage:
   - Uses ReentrantReadWriteLock with read lock for queries
   - Reads see a consistent snapshot (no dirty reads, no phantom reads)
   - Multiple concurrent reads allowed
   - Writes are exclusive and atomic

   PostgreSQL storage:
   - Uses PostgreSQL's default READ COMMITTED isolation level
   - Each statement sees only rows committed before it began
   - Dirty reads are prevented
   - Phantom reads are possible between statements in a batch
   - For stronger isolation, use explicit transactions at application level

   Datomic storage:
   - Uses Datomic's immutable database values
   - Reads always see a consistent point-in-time snapshot
   - No dirty reads, no phantom reads within a query
   - Strongest isolation guarantees of all backends

   ORDERING:
   - create-entities: Returns records in same order as input data-seq.
   - read-entities: Returns a map (unordered). Use (map result ids) if order matters.
   - delete-entities: Order of deletion is not guaranteed."

  (create-entities
    [this entity-name data-seq]
    "Creates multiple entity records in a single operation.
     If :id is not provided for a record, a random UUID is generated.

     Arguments:
     - entity-name: keyword name of the entity (e.g., :fn-schema, :fn)
     - data-seq: sequence of maps, each representing a record to create

     Returns a sequence of created records with :id fields, in input order.
     Throws ExceptionInfo if validation fails or constraints are violated.
     On failure, no records are created (atomic operation).")

  (read-entities
    [this entity-name ids]
    "Reads multiple entity records by IDs in a single operation.

     Arguments:
     - entity-name: keyword name of the entity
     - ids: sequence of UUIDs

     Returns a map of {id -> record} for found records.
     Records not found are simply not included in the result.
     Note: To preserve order, use (map result ids) after calling.")

  (delete-entities
    [this entity-name ids]
    "Deletes multiple entity records by IDs in a single operation.

     Arguments:
     - entity-name: keyword name of the entity
     - ids: sequence of UUIDs to delete

     Returns the count of actually deleted records.
     Throws ExceptionInfo if referential integrity would be violated.
     On failure, no records are deleted (atomic operation)."))


(defprotocol GraphConstraints
  "Constraints for graph integrity in the function composition graph.
   Each storage MUST implement this protocol.
   Violation of any constraint throws ExceptionInfo.

   These methods are called during CRUD operations to validate
   data integrity before persisting changes."

  (validate-parent-same-schema!
    [this fn-id parent-fn-id]
    "Validates that parent-fn has the same fn-schema-id as fn.
     Called when creating/updating fn with parent-fn-id.

     Arguments:
     - fn-id: UUID of the fn being created/updated
     - parent-fn-id: UUID of the proposed parent fn

     Throws ExceptionInfo with :type :constraint-violation/parent-schema-mismatch
     if parent has a different fn-schema-id.")

  (validate-no-arg-override!
    [this fn-id arg-schema-id]
    "Validates that arg-schema-id is not already defined in the parent chain.
     Called when creating arg-value.

     Arguments:
     - fn-id: UUID of the fn that owns this arg-value
     - arg-schema-id: UUID of the arg-schema being set

     Throws ExceptionInfo with :type :constraint-violation/arg-already-defined
     if any ancestor fn already has an arg-value for this arg-schema-id.")

  (validate-arg-schema-belongs-to-fn!
    [this fn-id arg-schema-id]
    "Validates that arg-schema belongs to the fn-schema of this fn.
     Called when creating arg-value.

     Arguments:
     - fn-id: UUID of the fn that owns this arg-value
     - arg-schema-id: UUID of the arg-schema

     Throws ExceptionInfo with :type :constraint-violation/arg-schema-mismatch
     if arg-schema's fn-schema-id differs from fn's fn-schema-id.")

  (validate-no-inheritance-cycle!
    [this fn-id parent-fn-id]
    "Validates that setting parent-fn-id does not create an inheritance cycle.
     Called when creating/updating fn with parent-fn-id.

     Arguments:
     - fn-id: UUID of the fn being created/updated
     - parent-fn-id: UUID of the proposed parent fn

     Throws ExceptionInfo with :type :constraint-violation/inheritance-cycle
     if setting this parent would create a cycle (parent chain leads back to fn-id).")

  (validate-no-dependency-cycle!
    [this owner-fn-id value-fn-id]
    "Validates that referencing value-fn does not create a dependency cycle.
     Called when creating arg-value with value = ref<fn>.

     Arguments:
     - owner-fn-id: UUID of the fn that owns this arg-value
     - value-fn-id: UUID of the fn being referenced as value

     Throws ExceptionInfo with :type :constraint-violation/dependency-cycle
     if this reference would create a cycle (value-fn depends on owner-fn)."))


(defprotocol ConstraintHelpers
  "Helper protocol for constraint validation.
   Provides low-level operations that vary by storage implementation.
   Used by the shared constraint validation functions below.

   ## NULL Contract

   All getter methods (get-fn-schema-id-for-fn, get-fn-schema-id-for-arg-schema,
   get-parent-fn-id) MUST return nil when the entity is not found. They MUST NOT
   throw exceptions for missing entities.

   Callers handle nil values appropriately:
   - validate-parent-same-schema-impl: skips validation if fn not found
   - validate-no-arg-override-impl: allows override if parent chain is empty
   - validate-no-inheritance-cycle-impl: stops traversal at nil

   This contract enables consistent behavior across all storage backends
   (postgres, datomic, memory) and simplifies error handling."

  (get-fn-schema-id-for-fn
    [this fn-id]
    "Returns the fn-schema-id for the given fn-id.
     Returns nil if fn-id is nil or fn not found in storage.")

  (get-fn-schema-id-for-arg-schema
    [this arg-schema-id]
    "Returns the fn-schema-id for the given arg-schema-id.
     Returns nil if arg-schema-id is nil or not found in storage.")

  (get-parent-fn-id
    [this fn-id]
    "Returns the parent-fn-id for the given fn-id.
     Returns nil if fn-id is nil, fn not found, or fn has no parent.")

  (collect-parent-chain
    [this fn-id]
    "Returns a set of all ancestor fn-ids (not including fn-id itself).
     Traverses the parent-fn-id chain up to the root.
     Returns empty set if fn has no parents.")

  (collect-arg-schema-ids-in-chain
    [this fn-id]
    "Returns a set of arg-schema-ids defined in the parent chain.
     Does NOT include arg-values owned by fn-id itself.
     Returns empty set if fn has no parents.")

  (collect-dependency-chain
    [this fn-id]
    "Returns a set of all fn-ids that fn-id depends on through arg-values.
     Includes fn-id itself. Used for cycle detection.
     Returns #{fn-id} if fn has no dependencies."))


;; === Shared constraint helper implementations ===
;;
;; Default implementations for ConstraintHelpers methods.
;; Storage implementations can use these or provide their own optimized versions.
;;
;; ## When to use shared impls:
;;
;; - collect-parent-chain-impl: Use when your storage doesn't have a native way
;;   to traverse hierarchies (e.g., no recursive CTEs). This does N queries for
;;   N levels of nesting. Memory storage uses this.
;;
;; - validate-*-impl functions: Always use these - they provide the constraint
;;   validation logic that should be consistent across all backends. Each backend
;;   just provides ConstraintHelpers that knows how to fetch data.
;;
;; ## When to override:
;;
;; - PostgreSQL: Uses recursive CTEs (WITH RECURSIVE) to fetch entire parent chain
;;   in a single query. More efficient for deep hierarchies.
;;
;; - Datomic: Fetches all parent mappings and traverses in-memory. Better for
;;   small graphs where in-memory traversal is faster than multiple queries.
;;
;; If your backend has efficient graph traversal primitives, implement
;; collect-parent-chain and collect-dependency-chain directly in your
;; ConstraintHelpers record instead of delegating to these impls.

(defn collect-parent-chain-impl
  "Default implementation of collect-parent-chain.
   Uses get-parent-fn-id to traverse the chain.
   Returns a set of all ancestor fn-ids (not including fn-id itself).

   Performance: O(N) queries where N is the chain depth.
   For deep hierarchies, consider implementing a custom version using
   recursive CTEs or bulk fetching."
  [helpers fn-id]
  (loop [current-id (get-parent-fn-id helpers fn-id)
         ancestor-ids #{}]
    (if (or (nil? current-id) (contains? ancestor-ids current-id))
      ancestor-ids
      (recur (get-parent-fn-id helpers current-id)
             (conj ancestor-ids current-id)))))


;; === Shared constraint validation functions ===
;; These use ConstraintHelpers protocol and can be called from any storage impl.

(defn validate-parent-same-schema-impl
  "Shared implementation of parent-same-schema validation.
   Uses ConstraintHelpers protocol methods."
  [helpers fn-id parent-fn-id]
  (when parent-fn-id
    (let [fn-schema-id (get-fn-schema-id-for-fn helpers fn-id)
          parent-schema-id (get-fn-schema-id-for-fn helpers parent-fn-id)]
      (when (and fn-schema-id parent-schema-id
                 (not= fn-schema-id parent-schema-id))
        (throw (ex-info "Parent fn has different fn-schema-id"
                        {:type :constraint-violation/parent-schema-mismatch
                         :fn-id fn-id
                         :parent-fn-id parent-fn-id
                         :fn-schema-id fn-schema-id
                         :parent-schema-id parent-schema-id}))))))


(defn validate-no-arg-override-impl
  "Shared implementation of no-arg-override validation.
   Uses ConstraintHelpers protocol methods."
  [helpers fn-id arg-schema-id]
  (let [parent-arg-schema-ids (collect-arg-schema-ids-in-chain helpers fn-id)]
    (when (contains? parent-arg-schema-ids arg-schema-id)
      (throw (ex-info "Argument already defined in parent chain"
                      {:type :constraint-violation/arg-already-defined
                       :fn-id fn-id
                       :arg-schema-id arg-schema-id})))))


(defn validate-arg-schema-belongs-to-fn-impl
  "Shared implementation of arg-schema-belongs-to-fn validation.
   Uses ConstraintHelpers protocol methods."
  [helpers fn-id arg-schema-id]
  (let [fn-schema-id (get-fn-schema-id-for-fn helpers fn-id)
        arg-fn-schema-id (get-fn-schema-id-for-arg-schema helpers arg-schema-id)]
    (when (and fn-schema-id arg-fn-schema-id
               (not= fn-schema-id arg-fn-schema-id))
      (throw (ex-info "Arg-schema does not belong to fn's schema"
                      {:type :constraint-violation/arg-schema-mismatch
                       :fn-id fn-id
                       :arg-schema-id arg-schema-id
                       :fn-schema-id fn-schema-id
                       :arg-fn-schema-id arg-fn-schema-id})))))


(defn validate-no-inheritance-cycle-impl
  "Shared implementation of no-inheritance-cycle validation.
   Uses ConstraintHelpers protocol methods."
  [helpers fn-id parent-fn-id]
  (when parent-fn-id
    ;; Check self-reference
    (when (= fn-id parent-fn-id)
      (throw (ex-info "Cannot set self as parent"
                      {:type :constraint-violation/inheritance-cycle
                       :fn-id fn-id
                       :parent-fn-id parent-fn-id})))
    ;; Check if fn-id appears in parent's ancestor chain
    (let [parent-ancestors (collect-parent-chain helpers parent-fn-id)]
      (when (contains? parent-ancestors fn-id)
        (throw (ex-info "Setting parent would create inheritance cycle"
                        {:type :constraint-violation/inheritance-cycle
                         :fn-id fn-id
                         :parent-fn-id parent-fn-id
                         :cycle-through (conj parent-ancestors parent-fn-id)}))))))


(defn validate-no-dependency-cycle-impl
  "Shared implementation of no-dependency-cycle validation.
   Uses ConstraintHelpers protocol methods."
  [helpers owner-fn-id value-fn-id]
  (when value-fn-id
    ;; Early check for self-reference (avoids expensive dependency chain query)
    (when (= owner-fn-id value-fn-id)
      (throw (ex-info "Reference would create dependency cycle"
                      {:type :constraint-violation/dependency-cycle
                       :owner-fn-id owner-fn-id
                       :value-fn-id value-fn-id})))
    ;; Check if owner-fn-id is in the dependency chain of value-fn-id
    (let [value-deps (collect-dependency-chain helpers value-fn-id)]
      (when (contains? value-deps owner-fn-id)
        (throw (ex-info "Reference would create dependency cycle"
                        {:type :constraint-violation/dependency-cycle
                         :owner-fn-id owner-fn-id
                         :value-fn-id value-fn-id}))))))


;; === Value Codec Protocol ===

(defprotocol StorageValueCodec
  "Protocol for encoding/decoding values between Clojure and storage backend.
   Each storage implementation has different serialization requirements:
   - PostgreSQL: JSONB for complex types, PGobject for enums
   - Datomic: EDN strings for union types, refs for enums
   - Memory: No transformation needed

   Implementations should handle:
   - :jsonb/:union types -> backend-specific format
   - :enum types -> backend-specific enum representation
   - :ref types -> UUID handling
   - nil values -> NULL representation"

  (encode-value
    [this value field-spec]
    "Encodes a Clojure value for storage.
     field-spec contains {:type :jsonb/:enum/:ref/... :enum-name :foo (optional)}
     Returns backend-specific value (e.g., PGobject for PostgreSQL).")

  (decode-value
    [this value field-spec]
    "Decodes a storage value to Clojure.
     Returns Clojure data structure.")

  (encode-row
    [this row field-specs]
    "Encodes all values in a row map for storage.
     field-specs is {field-name {:type ... :enum-name ...}}
     Returns row with encoded values.")

  (decode-row
    [this row field-specs]
    "Decodes all values in a storage row to Clojure.
     Returns row with decoded values and kebab-case keys."))


;; === Default codec helpers ===

(defn needs-special-encoding?
  "Returns true if field type requires special encoding (not passthrough)."
  [field-type]
  (contains? #{:jsonb :union :enum} field-type))


;; === Storage Error Classification ===

(def storage-error-types
  "Canonical storage error types across all backends.
   Each backend maps its native errors to these types.

   Note on entity/table errors:
   - :entity-not-in-schema - Entity name not defined in DataSchema (application-level check)
   - :table-not-found - Database table doesn't exist (database-level error, e.g., SQL state 42P01)

   Memory storage uses :entity-not-in-schema (checks schema before operation).
   PostgreSQL may return :table-not-found if table is missing (database error).
   Both indicate the entity is not available, but at different levels."
  #{:unique-violation
    :foreign-key-violation
    :not-null-violation
    :check-constraint-violation
    :entity-not-in-schema
    :table-not-found
    :connection-error
    :query-timeout
    :parse-error
    :unknown-sql-error})


(defprotocol StorageErrorClassifier
  "Protocol for classifying storage-specific errors.
   Each backend implements this to translate native exceptions
   to canonical error types.

   ## Primary Method: classify-error
   The main method is `classify-error`, which translates native exceptions
   to canonical error keywords. Most internal code uses macros like
   `with-crud-error-handling` that call the underlying error handling
   utilities directly.

   ## Convenience Method: wrap-error
   The `wrap-error` method is a convenience wrapper that combines
   classification with ex-info creation. It's primarily useful for
   external code that wants a simple error handling API. Internal
   implementations typically use direct error handling for performance.

   Example usage:
   (try
     (jdbc/execute! ...)
     (catch SQLException e
       (let [error-type (classify-error classifier e)]
         (throw (ex-info \"DB error\" {:type error-type})))))"

  (classify-error
    [this exception]
    "Classifies a storage exception into canonical error type.
     Returns keyword from storage-error-types or :unknown-sql-error.
     Exception can be SQLException, ExceptionInfo, etc.")

  (wrap-error
    [this exception operation context]
    "Wraps a storage exception with application context.
     Returns ex-info with :type, :operation, and merged context.
     - operation: keyword like :create-entity, :update-entity
     - context: map with additional info like {:entity-name :user}

     Note: Internal code often uses direct error handling macros instead
     of this method for performance. This method is provided as a
     convenience for external consumers of the protocol."))


(defprotocol ExecutionGraph
  "Protocol for retrieving complete execution graph for a function.
   Implementations should optimize for efficient retrieval - using JOINs,
   recursive CTEs, denormalized data, or caching as appropriate.

   The returned graph contains everything needed to execute a function:
   - All fn records (target + all referenced functions recursively)
   - All fn-schema records
   - All arg-schema records
   - All resolved arg-values (merged from parent chains)
   - All fn-result-values referenced by arg-values

   Each storage implementation can optimize this differently:
   - memory: simple recursive traversal (fast for in-memory data)
   - postgres: recursive CTE or denormalized tables with triggers
   - datomic: pull patterns or recursive queries"

  (resolve-execution-graph
    [this fn-id]
    "Resolves the complete execution graph for a function.

     Returns a map with all data needed for execution:
     {:fns {fn-id -> fn-record ...}
      :fn-schemas {fn-schema-id -> fn-schema-record ...}
      :arg-schemas {arg-schema-id -> arg-schema-record ...}
      :resolved-args {fn-id -> {arg-schema-id -> arg-value-record} ...}
      :fn-result-values {fn-result-value-id -> fn-result-value-record ...}}

     Where:
     - :fns contains the target fn and all transitively referenced fns
     - :fn-schemas contains all fn-schemas for the fns
     - :arg-schemas contains all arg-schemas for those fn-schemas
     - :resolved-args contains merged arg-values (child overrides parent)
       for each fn, keyed by arg-schema-id
     - :fn-result-values contains all fn-result-value records referenced
       by arg-values (for cached computation results)

     The executor can then:
     1. Look up fn by id in :fns
     2. Look up its fn-schema in :fn-schemas
     3. Look up arg-schemas in :arg-schemas
     4. Get resolved arg-values from :resolved-args
     5. For fn-result-value references, look up in :fn-result-values

     Throws ExceptionInfo with :type :not-found if fn-id doesn't exist."))


;; === ExecutionGraphResult record ===

(defrecord ExecutionGraphResult
  [fns fn-schemas arg-schemas resolved-args fn-result-values]
  ;; defrecord already implements ILookup with keyword field access,
  ;; so (:fns graph) works automatically.
  ;; This record provides:
  ;; - Type safety: clear field names and structure
  ;; - Documentation: named fields vs opaque map
  ;; - Efficiency: direct field access vs map lookup
  )


(defn ->execution-graph
  "Creates an ExecutionGraphResult record from a map.
   Validates that all required keys are present and non-empty.

   Expected shape:
   {:fns {fn-id -> fn-record ...}
    :fn-schemas {fn-schema-id -> fn-schema-record ...}
    :arg-schemas {arg-schema-id -> arg-schema-record ...}
    :resolved-args {fn-id -> {arg-schema-id -> arg-value-record} ...}
    :fn-result-values {fn-result-value-id -> fn-result-value-record ...}}

   Throws if:
   - Required keys are missing
   - :fns is empty (must contain at least the target function)
   - :fn-schemas is empty (target fn must have a schema)"
  [{:keys [fns fn-schemas arg-schemas resolved-args fn-result-values]
    :or {fn-result-values {}}}]
  (when-not (map? fns)
    (throw (ex-info "ExecutionGraphResult requires :fns map"
                    {:type :invalid-data :received (type fns)})))
  (when (empty? fns)
    (throw (ex-info "ExecutionGraphResult :fns must contain at least target function"
                    {:type :invalid-data :hint "Check that fn-id exists in storage"})))
  (when-not (map? fn-schemas)
    (throw (ex-info "ExecutionGraphResult requires :fn-schemas map"
                    {:type :invalid-data :received (type fn-schemas)})))
  (when (empty? fn-schemas)
    (throw (ex-info "ExecutionGraphResult :fn-schemas must contain at least one schema"
                    {:type :invalid-data :hint "Check that fn has valid fn-schema-id"})))
  (when-not (map? arg-schemas)
    (throw (ex-info "ExecutionGraphResult requires :arg-schemas map"
                    {:type :invalid-data :received (type arg-schemas)})))
  (when-not (map? resolved-args)
    (throw (ex-info "ExecutionGraphResult requires :resolved-args map"
                    {:type :invalid-data :received (type resolved-args)})))
  (->ExecutionGraphResult fns fn-schemas arg-schemas resolved-args fn-result-values))


(defn execution-graph?
  "Returns true if x is an ExecutionGraphResult record."
  [x]
  (instance? ExecutionGraphResult x))


;; === Shared constants ===
;;
;; ## Timeout Architecture
;;
;; There are TWO different timeout concepts in graphden:
;;
;; ### Storage Query Timeout (default-query-timeout-ms)
;; - Purpose: Limits individual database query duration
;; - Scope: Single SQL query or Datomic query
;; - Mechanism:
;;   - PostgreSQL: JDBC setQueryTimeout (seconds, min 1s due to JDBC API)
;;   - Datomic: :timeout query option (milliseconds)
;; - Default: 30000ms (30 seconds)
;; - Use case: Prevent runaway database queries from blocking connections
;;
;; ### Executor Timeout (executor/create-context :timeout-ms)
;; - Purpose: Limits total execution time for a function graph
;; - Scope: Entire execution including all function calls and queries
;; - Mechanism: Checked at start of each function call (best-effort)
;; - Default: 30000ms (same as storage for consistency)
;; - Minimum: 50ms (allows for fast unit tests)
;; - Use case: Prevent infinite loops or very long computations
;;
;; Note: Executor timeout is NOT a hard guarantee - a long-running base
;; function will complete fully even if it exceeds the timeout.
;; The check happens at function call boundaries, not during execution.

(def default-query-timeout-ms
  "Default timeout for storage queries in milliseconds.
   Used by PostgreSQL (via JDBC setQueryTimeout) and Datomic backends.
   Value: 30000ms (30 seconds) - reasonable default for most queries.

   Note: This is a shared constant for consistency across backends.
   Each backend may provide its own dynamic var for runtime overrides.

   See also: executor/create-context :timeout-ms for execution timeout."
  30000)


;; === Execution graph limits ===

(def default-max-depth
  "Default maximum recursion depth for function execution.
   Used by executor as default and by storage for parent chain limits.
   Value: 1000 - reasonable default for most use cases."
  1000)


(def ^:dynamic *max-graph-iterations*
  "Maximum number of iterations when resolving execution graph.
   Prevents infinite loops in case of data inconsistencies.
   Default: 10000 (enough for complex graphs, catches runaway loops).

   ## Formula for Sizing

   For a graph with N functions:
   - Minimum iterations: N (one per function, best case no references)
   - Typical iterations: N + R where R = number of fn-result-value refs
   - Worst case: N * max-depth (deeply nested chains)

   Recommendations:
   - Small graphs (<100 fns): default 10000 is more than sufficient
   - Medium graphs (100-1000 fns): default should work
   - Large graphs (>1000 fns): consider increasing proportionally

   Can be overridden using with-max-graph-iterations."
  10000)


(defn with-max-graph-iterations
  "Executes f with a custom max-graph-iterations limit.
   Useful for testing or for graphs that are known to be large.

   Example:
   (with-max-graph-iterations 50000 #(resolve-execution-graph storage fn-id))"
  [limit f]
  (binding [*max-graph-iterations* limit]
    (f)))


(defn check-graph-iteration-limit!
  "Checks if iteration count exceeds the limit.
   Logs warning at 80% of limit to help identify potential runaway graphs.
   Throws ExceptionInfo if limit is exceeded."
  [iteration-count fn-id]
  (let [warning-threshold (long (* 0.8 *max-graph-iterations*))]
    (when (and (> iteration-count warning-threshold)
               (< iteration-count *max-graph-iterations*))
      (log/warn "Graph resolution approaching iteration limit"
                {:fn-id fn-id
                 :iteration-count iteration-count
                 :max-iterations *max-graph-iterations*
                 :percent-used (int (* 100 (/ iteration-count *max-graph-iterations*)))})))
  (when (> iteration-count *max-graph-iterations*)
    (throw (ex-info "Execution graph resolution exceeded maximum iterations"
                    {:type :execution-error/graph-too-large
                     :fn-id fn-id
                     :max-iterations *max-graph-iterations*
                     :iteration-count iteration-count}))))


;; === UUID parsing ===

(defn try-parse-uuid
  "Attempts to parse value as UUID. Returns UUID or nil.
   Handles UUIDs, UUID strings, and returns nil for non-UUID values.

   Optional context parameter is used for logging/debugging when parsing fails.
   Context is ignored in normal operation but helps trace issues."
  ([v] (try-parse-uuid v nil))
  ([v _context]
   (cond
     (uuid? v) v
     (string? v) (try
                   (java.util.UUID/fromString v)
                   (catch IllegalArgumentException _ nil))
     :else nil)))


;; === Sensitive Data Redaction ===
;;
;; Shared utilities for redacting sensitive data in logs and exceptions.
;; All storage implementations should use these to ensure consistent security.

(def sensitive-field-patterns
  "Regex patterns for identifying sensitive field names that should be redacted.
   Used across all storage backends and executor for consistent security."
  [#"(?i)password"
   #"(?i)secret"
   #"(?i)token"
   #"(?i)api[_-]?key"
   #"(?i)auth"
   #"(?i)credential"
   #"(?i)private[_-]?key"])


(defn sensitive-field?
  "Returns true if field name matches known sensitive patterns.
   Handles keywords, strings, and nil gracefully."
  [field-name]
  (when field-name
    (let [name-str (if (keyword? field-name) (name field-name) (str field-name))]
      (when (seq name-str)
        (some #(re-find % name-str) sensitive-field-patterns)))))


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


;; === Canonical Error Types ===
;;
;; All storage implementations should use consistent error types in ex-info exceptions.
;; This enables reliable error handling across different storage backends.
;;
;; ## CRUD Errors
;;
;; | Error Type               | Meaning                                          |
;; |--------------------------|--------------------------------------------------|
;; | :not-found               | Entity with given ID does not exist              |
;; | :unique-violation        | Unique constraint violated during insert/update  |
;; | :duplicate-ids           | Batch operation contains duplicate IDs           |
;; | :constraint-violation/*  | Specific constraint violations (see below)       |
;;
;; ## Constraint Violation Errors
;;
;; | Error Type                               | Meaning                              |
;; |------------------------------------------|--------------------------------------|
;; | :constraint-violation/unique             | Unique constraint violated           |
;; | :constraint-violation/parent-schema-mismatch | Parent fn has different schema   |
;; | :constraint-violation/arg-already-defined | Arg already defined in parent chain |
;; | :constraint-violation/arg-schema-mismatch | Arg schema doesn't belong to fn    |
;; | :constraint-violation/inheritance-cycle  | Circular inheritance detected        |
;; | :constraint-violation/dependency-cycle   | Circular dependency detected         |
;; | :constraint-violation/required-field     | Required field is null/missing       |
;;
;; ## Schema/Migration Errors
;;
;; | Error Type                  | Meaning                                       |
;; |-----------------------------|-----------------------------------------------|
;; | :destructive-change         | Attempted to remove entity/field/enum/value  |
;; | :metadata-error/corrupted   | Stored metadata is invalid/corrupted          |
;; | :metadata-error/rollback-failed | Migration rollback failed                  |
;;
;; ## Configuration Errors
;;
;; | Error Type                      | Meaning                                 |
;; |---------------------------------|-----------------------------------------|
;; | :config-error/missing-jdbc-url  | JDBC URL not provided                   |
;; | :config-error/missing-username  | Database username not provided          |
;; | :config-error/missing-password  | Database password not provided          |
;; | :config-error/invalid-pool-size | Invalid connection pool size            |
;; | :config-error/invalid-pool-config | Pool configuration inconsistent       |
;; | :config-error/invalid-timeout   | Invalid timeout value                   |
;;
;; ## Execution Errors
;;
;; | Error Type                       | Meaning                                 |
;; |----------------------------------|-----------------------------------------|
;; | :execution-error/fn-not-found    | Function not found in execution graph   |
;; | :execution-error/fn-schema-not-found | Function schema not found            |
;; | :execution-error/base-fn-not-found | Base function not in registry         |
;; | :execution-error/max-depth-exceeded | Maximum recursion depth reached       |
;; | :execution-error/timeout         | Execution timeout exceeded              |
;; | :execution-error/type-mismatch   | Argument type doesn't match schema      |
;; | :execution-error/missing-required-arg | Required argument not provided       |
;;
;; ## Parse Errors
;;
;; | Error Type          | Meaning                                          |
;; |---------------------|--------------------------------------------------|
;; | :parse-error/jsonb  | Failed to parse JSONB value from storage         |


;; === Type System Documentation ===
;;
;; Graphden uses an abstract type system that gets mapped to concrete storage types.
;; This section documents how types are represented across different storage backends.
;;
;; ## Base Types (from field-types component)
;;
;; | Type          | Description                      |
;; |---------------|----------------------------------|
;; | :uuid         | UUID identifier                  |
;; | :text         | Text/string value                |
;; | :int          | Integer number                   |
;; | :bool         | Boolean true/false               |
;; | :numeric      | Numeric value (int or double)    |
;; | :timestamptz  | Timestamp with timezone          |
;; | :jsonb        | JSON/EDN data                    |
;; | :bytes        | Binary data                      |
;;
;; ## Special Types (schema-specific)
;;
;; | Type   | Description                              | Storage        |
;; |--------|------------------------------------------|----------------|
;; | :ref   | Reference to another entity              | Same as :uuid  |
;; | :enum  | Enumeration value                        | Backend-specific |
;; | :union | Union type (multiple possible types)     | Same as :jsonb |
;;
;; ## PostgreSQL Type Mapping (postgres-storage/util.clj)
;;
;; | Abstract  | PostgreSQL    | Notes                           |
;; |-----------|---------------|---------------------------------|
;; | :uuid     | UUID          |                                 |
;; | :text     | TEXT          |                                 |
;; | :int      | BIGINT        | 64-bit signed integer           |
;; | :bool     | BOOLEAN       |                                 |
;; | :numeric  | NUMERIC       | Arbitrary precision             |
;; | :timestamptz | TIMESTAMPTZ | With timezone                 |
;; | :jsonb    | JSONB         | Binary JSON, supports indexing  |
;; | :bytes    | BYTEA         |                                 |
;; | :ref      | UUID          | Foreign key reference           |
;; | :enum     | Custom TYPE   | PostgreSQL ENUM type            |
;; | :union    | JSONB         | Tagged union as JSON            |
;;
;; ## Datomic Type Mapping (datomic-storage/core.clj)
;;
;; | Abstract  | Datomic           | Notes                        |
;; |-----------|-------------------|------------------------------|
;; | :uuid     | :db.type/uuid     |                              |
;; | :text     | :db.type/string   |                              |
;; | :int      | :db.type/long     | 64-bit signed integer        |
;; | :bool     | :db.type/boolean  |                              |
;; | :numeric  | :db.type/bigdec   | Java BigDecimal              |
;; | :timestamptz | :db.type/instant | java.util.Date            |
;; | :jsonb    | :db.type/string   | Stored as EDN string         |
;; | :bytes    | :db.type/bytes    |                              |
;; | :ref      | :db.type/ref      | Datomic entity reference     |
;; | :enum     | :db.type/ref      | Reference to ident entity    |
;; | :union    | :db.type/string   | Stored as EDN string         |
;;
;; ## Memory Storage
;;
;; No type conversion - values stored as native Clojure data structures.
;; Validation happens at schema level, not storage level.
;;
;; ## Type Equivalence
;;
;; Some types are stored identically and can be treated as equivalent:
;; - :ref ≡ :uuid (both stored as UUID)
;; - :union ≡ :jsonb (both stored as JSONB/EDN)
;;
;; ## Type Widening
;;
;; Safe type changes that preserve data:
;; - :int → :numeric, :text, :jsonb
;; - :bool → :text, :jsonb
;; - :numeric → :text, :jsonb
;; - :text → :jsonb
;; - :uuid → :text
;; - :timestamptz → :text


;; === Type compatibility ===
;; Re-export from field-types for backwards compatibility

(def type-mappings
  "Complete type mapping reference for all storage backends.
   See graphden.field-types.interface/type-mappings for details."
  ft/type-mappings)


(def type-widening
  "Map of type→set of types it can safely widen to.
   See graphden.field-types.interface/type-widening for details."
  ft/type-widening)


(def type-equivalents
  "Types that are equivalent (stored the same way in storage).
   See graphden.field-types.interface/type-equivalents for details."
  ft/type-equivalents)


(defn types-equivalent?
  "Returns true if two types are equivalent (stored the same way).
   Delegates to graphden.field-types.interface/types-equivalent?."
  [t1 t2]
  (ft/types-equivalent? t1 t2))


(defn safe-type-change?
  "Returns true if changing from old-type to new-type is safe.
   Safe changes are: same type, equivalent types, or widening to a more general type.

   ## Limitations

   This function checks SCHEMA compatibility only, not DATA compatibility.
   It does NOT validate that existing data in the database would fit in the
   new type. For example:

   - int → numeric: Schema-safe, data-safe (widening)
   - numeric → int: Schema-unsafe (not in type-widening map)
   - text → text: Always safe

   Before narrowing type changes (e.g., text with 1000 chars → varchar(100)),
   manually verify that existing data fits. This is a known limitation
   requiring DBA review for production migrations."
  [old-type new-type]
  (or (= old-type new-type)
      (types-equivalent? old-type new-type)
      (contains? (get type-widening old-type #{}) new-type)))


(defn safe-nullable-change?
  "Returns true if changing nullable is safe.
   Safe changes:
   - Same value (no change)
   - false→true (allowing nulls is safe)
   Unsafe changes:
   - true→false (existing nulls would become invalid)"
  [old-nullable? new-nullable?]
  (or (= old-nullable? new-nullable?)
      (and (false? old-nullable?) (true? new-nullable?))))


;; === Destructive change detection utilities ===

(defn check-removed!
  "Checks for items removed from schema and throws if any found.

   Arguments:
   - item-type: string describing the type (e.g., \"entities\", \"fields\")
   - old-uuids: set of UUIDs from existing metadata
   - new-uuids: set of UUIDs from new schema
   - get-name-fn: function that takes UUID and returns human-readable name/info

   Throws ExceptionInfo with :type :destructive-change if items were removed."
  [item-type old-uuids new-uuids get-name-fn]
  (let [removed (set/difference old-uuids new-uuids)]
    (when (seq removed)
      (throw (ex-info (str "Destructive change: " item-type " removed")
                      {:type :destructive-change
                       :removed (vec (map get-name-fn removed))})))))


(defn check-type-change!
  "Checks that a field type change is safe and throws if not.

   Arguments:
   - entity-name: keyword name of the entity
   - field-name: keyword name of the field
   - old-type: the current type in storage
   - new-type: the new type in schema

   Throws ExceptionInfo with :type :destructive-change if type change is unsafe."
  [entity-name field-name old-type new-type]
  (when (and old-type
             (not (safe-type-change? old-type new-type)))
    (throw (ex-info "Destructive change: incompatible type change"
                    {:type :destructive-change
                     :entity entity-name
                     :field field-name
                     :old-type old-type
                     :new-type new-type}))))


(defn check-nullable-change!
  "Checks that a nullable change is safe and throws if not.

   Arguments:
   - entity-name: keyword name of the entity
   - field-name: keyword name of the field
   - old-nullable?: the current nullable value in storage (must be boolean or nil)
   - new-nullable?: the new nullable value in schema

   Throws ExceptionInfo with :type :destructive-change if nullable change is unsafe
   (i.e., changing from nullable to non-nullable).
   Throws ExceptionInfo with :type :metadata-error if old-nullable? is not a boolean."
  [entity-name field-name old-nullable? new-nullable?]
  ;; If old-nullable? is present but not a boolean, metadata is corrupted
  (when (and (some? old-nullable?) (not (boolean? old-nullable?)))
    (throw (ex-info "Corrupted metadata: nullable value is not a boolean"
                    {:type :metadata-error/corrupted
                     :entity entity-name
                     :field field-name
                     :old-nullable? old-nullable?
                     :expected-type :boolean
                     :actual-type (type old-nullable?)})))
  (when (and (some? old-nullable?)
             (not (safe-nullable-change? old-nullable? new-nullable?)))
    (throw (ex-info "Destructive change: field changed from nullable to non-nullable"
                    {:type :destructive-change
                     :entity entity-name
                     :field field-name
                     :old-nullable? old-nullable?
                     :new-nullable? new-nullable?}))))


;; === Schema diff utilities ===
;; These functions compute changes between old metadata and new schema.
;; They are shared across all storage implementations.


(defn- collect-fields-meta
  "Collects field metadata for all entities."
  [schema]
  (into {}
        (mapcat (fn [entity-name]
                  (map (fn [[field-name field-spec]]
                         [(:uuid field-spec)
                          {:entity entity-name :field field-name}])
                       (ds/entity-fields schema entity-name)))
                (ds/entities schema))))


(defn- collect-enum-values-meta
  "Collects enum value metadata for all enums."
  [enums-data]
  (into {}
        (mapcat (fn [[enum-name {:keys [values]}]]
                  (map (fn [[value-kw value-uuid]]
                         [value-uuid {:enum enum-name :value value-kw}])
                       values))
                enums-data)))


(defn collect-created-fields
  "Collects created fields info for changes report.
   Returns [{:entity e :field f} ...]"
  [schema]
  (vec (mapcat (fn [e]
                 (map (fn [[f _]] {:entity e :field f})
                      (ds/entity-fields schema e)))
               (ds/entities schema))))


(defn collect-created-enum-values
  "Collects created enum values info for changes report.
   Returns [{:enum enum-name :value v} ...]"
  [schema]
  (vec (mapcat (fn [[enum-name {:keys [values]}]]
                 (map (fn [[v _]] {:enum enum-name :value v})
                      values))
               (ds/enums schema))))


(defn collect-field-uuids
  "Collects all field UUIDs from schema.
   Returns set of UUIDs."
  [schema]
  (set (mapcat (fn [e]
                 (map (fn [[_ spec]] (:uuid spec))
                      (ds/entity-fields schema e)))
               (ds/entities schema))))


(defn collect-enum-value-uuids
  "Collects all enum value UUIDs from schema.
   Returns set of UUIDs."
  [schema]
  (set (mapcat (fn [[_ {:keys [values]}]]
                 (map second values))
               (ds/enums schema))))


(defn build-metadata-from-schema
  "Builds metadata structure from DataSchema for first-time initialization.
   Returns: {:entities {uuid->name}
             :fields {uuid->{:entity name :field name}}
             :enums {uuid->name}
             :enum-values {uuid->{:enum name :value kw}}}"
  [schema]
  (let [entities-meta (into {}
                            (map (fn [entity-name]
                                   [(ds/entity-uuid schema entity-name) entity-name])
                                 (ds/entities schema)))
        fields-meta (collect-fields-meta schema)
        enums-data (ds/enums schema)
        enums-meta (into {}
                         (map (fn [[enum-name {:keys [uuid]}]]
                                [uuid enum-name])
                              enums-data))
        enum-values-meta (collect-enum-values-meta enums-data)]
    {:entities entities-meta
     :fields fields-meta
     :enums enums-meta
     :enum-values enum-values-meta}))


(defn build-first-init-changes
  "Builds the changes map for first-time initialization.
   All entities, fields, enums, and enum-values are marked as created."
  [schema]
  {:entities {:created (vec (ds/entities schema)) :renamed {}}
   :fields {:created (collect-created-fields schema) :renamed []}
   :enums {:created (vec (keys (ds/enums schema))) :renamed {}}
   :enum-values {:created (collect-created-enum-values schema)}})


(defn check-all-removals!
  "Checks for any destructive removals (entities, fields, enums, enum-values).
   Throws on first removal found."
  [old-metadata schema]
  ;; Check entities
  (let [old-entity-uuids (set (keys (:entities old-metadata)))
        new-entity-uuids (set (map #(ds/entity-uuid schema %) (ds/entities schema)))]
    (check-removed! "entities" old-entity-uuids new-entity-uuids
                    #(get (:entities old-metadata) %)))
  ;; Check fields
  (let [old-field-uuids (set (keys (:fields old-metadata)))
        new-field-uuids (collect-field-uuids schema)]
    (check-removed! "fields" old-field-uuids new-field-uuids
                    #(get (:fields old-metadata) %)))
  ;; Check enums
  (let [old-enum-uuids (set (keys (:enums old-metadata)))
        new-enum-uuids (set (map (fn [[_ {:keys [uuid]}]] uuid) (ds/enums schema)))]
    (check-removed! "enums" old-enum-uuids new-enum-uuids
                    #(get (:enums old-metadata) %)))
  ;; Check enum values
  (let [old-value-uuids (set (keys (:enum-values old-metadata)))
        new-value-uuids (collect-enum-value-uuids schema)]
    (check-removed! "enum values" old-value-uuids new-value-uuids
                    #(get (:enum-values old-metadata) %))))


(defn compute-entity-changes
  "Computes created and renamed entities.
   Returns {:created [entity-names...] :renamed {old-name new-name ...}}"
  [old-metadata schema]
  (let [old-uuid->name (:entities old-metadata)
        created (vec (for [entity-name (ds/entities schema)
                           :let [uuid (ds/entity-uuid schema entity-name)]
                           :when (not (contains? old-uuid->name uuid))]
                       entity-name))
        renamed (into {}
                      (for [entity-name (ds/entities schema)
                            :let [uuid (ds/entity-uuid schema entity-name)
                                  old-name (get old-uuid->name uuid)]
                            :when (and old-name (not= old-name entity-name))]
                        [old-name entity-name]))]
    {:created created :renamed renamed}))


(defn compute-field-changes
  "Computes created and renamed fields.
   Returns {:created [{:entity e :field f}...] :renamed [{:entity e :old-field o :new-field n}...]}"
  [old-metadata schema]
  (let [old-uuid->info (:fields old-metadata)
        created (vec (for [entity-name (ds/entities schema)
                           [field-name field-spec] (ds/entity-fields schema entity-name)
                           :let [uuid (:uuid field-spec)]
                           :when (not (contains? old-uuid->info uuid))]
                       {:entity entity-name :field field-name}))
        renamed (vec (for [entity-name (ds/entities schema)
                           [field-name field-spec] (ds/entity-fields schema entity-name)
                           :let [uuid (:uuid field-spec)
                                 old-info (get old-uuid->info uuid)]
                           :when (and old-info (not= (:field old-info) field-name))]
                       {:entity entity-name
                        :old-field (:field old-info)
                        :new-field field-name}))]
    {:created created :renamed renamed}))


(defn compute-enum-changes
  "Computes created and renamed enums.
   Returns {:created [enum-names...] :renamed {old-name new-name ...}}"
  [old-metadata schema]
  (let [old-uuid->name (:enums old-metadata)
        enums-data (ds/enums schema)
        created (vec (for [[enum-name {:keys [uuid]}] enums-data
                           :when (not (contains? old-uuid->name uuid))]
                       enum-name))
        renamed (into {}
                      (for [[enum-name {:keys [uuid]}] enums-data
                            :let [old-name (get old-uuid->name uuid)]
                            :when (and old-name (not= old-name enum-name))]
                        [old-name enum-name]))]
    {:created created :renamed renamed}))


(defn compute-enum-value-changes
  "Computes created enum values.
   Returns {:created [{:enum e :value v}...]}"
  [old-metadata schema]
  (let [old-uuid->info (:enum-values old-metadata)
        enums-data (ds/enums schema)
        created (vec (for [[enum-name {:keys [values]}] enums-data
                           [value-kw value-uuid] values
                           :when (not (contains? old-uuid->info value-uuid))]
                       {:enum enum-name :value value-kw}))]
    {:created created}))


;; === CRUD validation utilities ===


(defn validate-required-fields!
  "Validates that all required (non-nullable) fields are present and not nil.
   Throws ExceptionInfo if validation fails.

   This is SHAPE-ONLY validation - it checks presence/nil but NOT types.
   Type validation happens at the storage backend level during actual
   insert/update operations. This separation allows for:
   - Fast presence checks before hitting the database
   - Backend-specific type coercion (e.g., string->UUID in PostgreSQL)

   Arguments:
   - entity-name: keyword name of the entity
   - fields: map of {field-name {:type ... :nullable? ...}}
   - data: the data map being validated

   Throws ExceptionInfo with :type :validation-error/required-field-missing
   if a required field is missing or nil."
  [entity-name fields data]
  (doseq [[field-name field-spec] fields]
    (when (and (not= field-name :id)  ; :id is auto-generated
               (not (:nullable? field-spec))
               (or (not (contains? data field-name))
                   (nil? (get data field-name))))
      (throw (ex-info (str "Required field '" (name field-name) "' is missing or nil")
                      {:type :validation-error/required-field-missing
                       :entity entity-name
                       :field field-name})))))


(defn validate-no-duplicate-ids!
  "Validates that there are no duplicate IDs in a batch of records.
   Throws ExceptionInfo if duplicate IDs are found.

   Arguments:
   - entity-name: keyword name of the entity
   - data-seq: sequence of data maps, each may have an :id field

   Throws ExceptionInfo with :type :validation-error/duplicate-ids
   if duplicate IDs are found in the batch."
  [entity-name data-seq]
  (let [explicit-ids (->> data-seq
                          (map :id)
                          (filter some?))
        id-counts (frequencies explicit-ids)
        duplicates (->> id-counts
                        (filter (fn [[_ cnt]] (> cnt 1)))
                        (map first))]
    (when (seq duplicates)
      (throw (ex-info (str "Duplicate IDs found in batch: " (pr-str duplicates))
                      {:type :validation-error/duplicate-ids
                       :entity entity-name
                       :duplicate-ids (vec duplicates)})))))


(defn validate-data-is-map!
  "Validates that data is a map for CRUD operations.
   Throws ExceptionInfo if data is not a map.

   Arguments:
   - entity-name: keyword name of the entity
   - data: the data to validate

   Throws ExceptionInfo with :type :invalid-data if data is not a map."
  [entity-name data]
  (when-not (map? data)
    (throw (ex-info "data must be a map"
                    {:type :invalid-data
                     :entity-name entity-name
                     :data data
                     :data-type (type data)}))))


(defn validate-where-clause!
  "Validates that where clause is nil or a map for query operations.
   Throws ExceptionInfo if where is not nil or a map.

   Arguments:
   - where: the where clause to validate

   Throws ExceptionInfo with :type :invalid-where-clause if invalid."
  [where]
  (when (and (some? where) (not (map? where)))
    (throw (ex-info "where clause must be nil or a map"
                    {:type :invalid-where-clause
                     :where where
                     :where-type (type where)}))))


;; === Execution Graph Utilities ===
;;
;; Shared utilities for execution graph resolution across storage implementations.

(defn merge-arg-values-for-chain
  "Merges arg-values from a parent chain where child overrides parent.
   Given a chain [child grandparent great-grandparent ...] and all arg-values,
   returns {arg-schema-id -> arg-value-record} with closest-to-child values winning.

   Arguments:
   - all-arg-values: sequence of arg-value records with :owner-fn-id and :arg-schema-id
   - chain: vector of fn-ids ordered from child to root [child parent grandparent ...]

   Returns map of {arg-schema-id -> arg-value-record} or nil if chain is empty.

   Example:
   If grandparent defines :x=1 and child defines :x=2,
   the result will have :x=2 (child wins)."
  [all-arg-values chain]
  (when (seq chain)
    (let [chain-set (set chain)
          chain-pos (zipmap chain (range))
          ;; Filter arg-values belonging to this chain
          chain-arg-values (filter #(chain-set (:owner-fn-id %)) all-arg-values)]
      ;; Group by arg-schema-id, pick the one with lowest chain position (closest to target fn)
      (->> chain-arg-values
           (group-by :arg-schema-id)
           (map (fn [[arg-schema-id avs]]
                  [arg-schema-id (apply min-key #(get chain-pos (:owner-fn-id %) Long/MAX_VALUE) avs)]))
           (into {})))))


(defn extract-uuid-refs-from-arg-values
  "Extracts UUIDs referenced in arg-values.
   Returns set of UUIDs that could be fn or fn-result-value references.

   Arguments:
   - arg-values-map: map of {arg-schema-id -> arg-value-record} with :value field

   Returns set of UUIDs found in :value fields."
  [arg-values-map]
  (->> (vals arg-values-map)
       (map :value)
       (keep try-parse-uuid)
       (set)))


;; === Read-Write Lock Utilities ===
;;
;; Shared lock utilities for storage implementations that need thread-safe
;; concurrent access. Uses ReentrantReadWriteLock for better concurrency:
;; - Multiple readers can run concurrently
;; - Writers have exclusive access


(defn with-read-lock
  "Executes f with read lock held. Multiple readers can run concurrently.

   Example:
   (with-read-lock rw-lock #(read-data state))"
  [^ReentrantReadWriteLock rw-lock f]
  (let [lock ^Lock (ReentrantReadWriteLock/.readLock rw-lock)]
    (Lock/.lock lock)
    (try
      (f)
      (finally
        (Lock/.unlock lock)))))


(defn with-write-lock
  "Executes f with write lock held. Exclusive access - no readers or writers
   can proceed while this lock is held.

   Example:
   (with-write-lock rw-lock #(swap! state update-data))"
  [^ReentrantReadWriteLock rw-lock f]
  (let [lock ^Lock (ReentrantReadWriteLock/.writeLock rw-lock)]
    (Lock/.lock lock)
    (try
      (f)
      (finally
        (Lock/.unlock lock)))))


;; === Storage Implementation Helpers ===
;;
;; Helper functions for implementing new storage backends.
;; These provide common patterns and reduce boilerplate.

(defn create-rw-lock
  "Creates a new ReentrantReadWriteLock for thread-safe storage access.
   Use with-read-lock and with-write-lock for locking operations."
  []
  (ReentrantReadWriteLock.))


(defn standard-crud-validations!
  "Performs standard validations for CRUD operations.
   Call this at the beginning of create/update operations.

   Arguments:
   - entity-name: keyword identifying the entity type
   - data: map of entity data
   - fields: field specifications from schema (or nil)

   Throws ExceptionInfo if validation fails."
  [entity-name data fields]
  (validate-data-is-map! entity-name data)
  (when fields
    (validate-required-fields! entity-name fields data)))


(defn standard-batch-validations!
  "Performs standard validations for batch CRUD operations.
   Call this at the beginning of batch create operations.

   Arguments:
   - entity-name: keyword identifying the entity type
   - data-seq: sequence of entity data maps

   Throws ExceptionInfo if validation fails."
  [entity-name data-seq]
  (validate-no-duplicate-ids! entity-name data-seq))


(defn initialize-with-cleanup!
  "Initializes storage with schema, cleaning up on failure.

   This is a common pattern for storage factory functions:
   1. Creates storage (caller's responsibility)
   2. Initializes with schema
   3. On success: returns storage
   4. On failure: closes storage and re-throws exception

   Arguments:
   - storage: an uninitialized storage instance
   - schema: DataSchema to initialize with

   Returns initialized storage on success.
   On error, closes storage and re-throws the exception.

   Example:
     (defn create-storage [opts]
       (-> (backend/create-storage opts)
           (sp/initialize-with-cleanup! schema)))"
  [storage schema]
  (try
    (initialize storage schema)
    storage
    (catch Exception e
      (close storage)
      (throw e))))


(def storage-checklist
  "Checklist of protocols and functions to implement for a new storage backend.

   Required protocols:
   - Storage (initialize, close)
   - StorageIntrospection (current-entities, current-fields, current-enums,
                           current-enum-values, schema-metadata)
   - StorageCRUD (create-entity, read-entity, update-entity, delete-entity, query-entities)

   Optional protocols:
   - StorageBatchCRUD (create-entities, read-entities, delete-entities)
   - GraphConstraints (for graph storage with referential integrity)
   - ExecutionGraph (for graph execution support)
   - ConstraintHelpers (data-fetching for shared constraint validation)

   Recommended utilities from this namespace:
   - create-rw-lock / with-read-lock / with-write-lock (for thread safety)
   - standard-crud-validations! / standard-batch-validations! (for input validation)
   - validate-required-fields! / validate-data-is-map! / validate-where-clause!
   - build-metadata-from-schema / build-first-init-changes (for migration)
   - check-all-removals! / safe-type-change? / check-type-change! (for schema evolution)
   - default-query-timeout-ms / default-max-depth (for configuration)

   Protocols to implement from StorageErrorClassifier (recommended):
   - classify-error (for error classification)
   - wrap-error (for error wrapping with context)

   Protocols to implement from StorageValueCodec (if needed):
   - encode-value / decode-value / encode-row / decode-row"
  {:required-protocols [:Storage :StorageIntrospection :StorageCRUD]
   :optional-protocols [:StorageBatchCRUD :GraphConstraints :ExecutionGraph :ConstraintHelpers]
   :recommended-protocols [:StorageErrorClassifier :StorageValueCodec]})

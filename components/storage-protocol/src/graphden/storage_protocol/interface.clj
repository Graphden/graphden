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
   4. Call (close storage) when done"
  (:require
    [clojure.set :as set]
    [graphden.data-schema-protocol.interface :as ds])
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

   Nil return semantics:
   - current-entities: returns empty set if storage is empty, never nil
   - current-fields: returns nil if entity doesn't exist, empty map if no fields
   - current-enums: returns empty set if no enums, never nil
   - current-enum-values: returns nil if enum doesn't exist
   - schema-metadata: returns nil if storage not yet initialized"

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
     Returns nil if enum doesn't exist in storage.")

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
   Provides more efficient operations when working with multiple records.
   Implementations should optimize for bulk operations (batch inserts, IN clauses, etc.).

   ATOMICITY GUARANTEES:
   - create-entities: All-or-nothing. If any record fails validation or
     constraint check, NO records are created.
   - read-entities: Isolation depends on storage backend. Results represent
     a consistent snapshot at some point during the read.
   - delete-entities: All-or-nothing. If any deletion would violate referential
     integrity, NO records are deleted.

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
   Used by the shared constraint validation functions below."

  (get-fn-schema-id-for-fn
    [this fn-id]
    "Returns the fn-schema-id for the given fn-id, or nil if fn not found.")

  (get-fn-schema-id-for-arg-schema
    [this arg-schema-id]
    "Returns the fn-schema-id for the given arg-schema-id, or nil if not found.")

  (get-parent-fn-id
    [this fn-id]
    "Returns the parent-fn-id for the given fn-id, or nil if no parent.")

  (collect-parent-chain
    [this fn-id]
    "Returns a set of all ancestor fn-ids (not including fn-id itself).
     Traverses the parent-fn-id chain up to the root.")

  (collect-arg-schema-ids-in-chain
    [this fn-id]
    "Returns a set of arg-schema-ids defined in the parent chain.
     Does NOT include arg-values owned by fn-id itself.")

  (collect-dependency-chain
    [this fn-id]
    "Returns a set of all fn-ids that fn-id depends on through arg-values.
     Includes fn-id itself. Used for cycle detection."))


;; === Shared constraint helper implementations ===
;; Default implementations for ConstraintHelpers methods.
;; Storage implementations can use these if they only provide get-parent-fn-id.

(defn collect-parent-chain-impl
  "Default implementation of collect-parent-chain.
   Uses get-parent-fn-id to traverse the chain.
   Returns a set of all ancestor fn-ids (not including fn-id itself)."
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


(defprotocol ExecutionGraph
  "Protocol for retrieving complete execution graph for a function.
   Implementations should optimize for efficient retrieval - using JOINs,
   recursive CTEs, denormalized data, or caching as appropriate.

   The returned graph contains everything needed to execute a function:
   - All fn records (target + all referenced functions recursively)
   - All fn-schema records
   - All arg-schema records
   - All resolved arg-values (merged from parent chains)

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
      :resolved-args {fn-id -> {arg-schema-id -> arg-value-record} ...}}

     Where:
     - :fns contains the target fn and all transitively referenced fns
     - :fn-schemas contains all fn-schemas for the fns
     - :arg-schemas contains all arg-schemas for those fn-schemas
     - :resolved-args contains merged arg-values (child overrides parent)
       for each fn, keyed by arg-schema-id

     The executor can then:
     1. Look up fn by id in :fns
     2. Look up its fn-schema in :fn-schemas
     3. Look up arg-schemas in :arg-schemas
     4. Get resolved arg-values from :resolved-args

     Throws ExceptionInfo with :type :not-found if fn-id doesn't exist."))


;; === Execution graph limits ===

(def ^:dynamic *max-graph-iterations*
  "Maximum number of iterations when resolving execution graph.
   Prevents infinite loops in case of data inconsistencies.
   Default: 10000 (enough for complex graphs, catches runaway loops).

   Can be overridden using with-max-graph-iterations."
  10000)


;; Keep old name as alias for backward compatibility
(def max-graph-iterations
  "Deprecated: Use *max-graph-iterations* instead.
   Kept for backward compatibility."
  *max-graph-iterations*)


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
   Throws ExceptionInfo if limit is exceeded."
  [iteration-count fn-id]
  (when (> iteration-count *max-graph-iterations*)
    (throw (ex-info "Execution graph resolution exceeded maximum iterations"
                    {:type :execution-error/graph-too-large
                     :fn-id fn-id
                     :max-iterations *max-graph-iterations*
                     :iteration-count iteration-count}))))


;; === UUID parsing ===

(defn try-parse-uuid
  "Attempts to parse value as UUID. Returns UUID or nil.
   Handles UUIDs, UUID strings, and returns nil for non-UUID values."
  [v]
  (cond
    (uuid? v) v
    (string? v) (try
                  (java.util.UUID/fromString v)
                  (catch IllegalArgumentException _ nil))
    :else nil))


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

(def type-mappings
  "Complete type mapping reference for all storage backends.
   Keys are abstract types, values are maps of backend -> concrete type.

   Usage:
     (get-in type-mappings [:uuid :postgres]) ;=> \"UUID\"
     (get-in type-mappings [:int :datomic])   ;=> :db.type/long"
  {:uuid        {:postgres "UUID"        :datomic :db.type/uuid    :memory :any}
   :text        {:postgres "TEXT"        :datomic :db.type/string  :memory :any}
   :int         {:postgres "BIGINT"      :datomic :db.type/long    :memory :any}
   :bool        {:postgres "BOOLEAN"     :datomic :db.type/boolean :memory :any}
   :numeric     {:postgres "NUMERIC"     :datomic :db.type/bigdec  :memory :any}
   :timestamptz {:postgres "TIMESTAMPTZ" :datomic :db.type/instant :memory :any}
   :jsonb       {:postgres "JSONB"       :datomic :db.type/string  :memory :any}
   :bytes       {:postgres "BYTEA"       :datomic :db.type/bytes   :memory :any}
   :ref         {:postgres "UUID"        :datomic :db.type/ref     :memory :any}
   :enum        {:postgres :custom       :datomic :db.type/ref     :memory :any}
   :union       {:postgres "JSONB"       :datomic :db.type/string  :memory :any}})


(def type-widening
  "Map of type→set of types it can safely widen to.
   Widening means no data loss is possible.
   Types not in this map cannot be widened (only same-type allowed)."
  {:int #{:numeric :text :jsonb}
   :bool #{:text :jsonb}
   :numeric #{:text :jsonb}
   :text #{:jsonb}
   :uuid #{:text}
   :timestamptz #{:text}})


(def type-equivalents
  "Types that are equivalent (stored the same way in storage).
   Used for comparison to avoid false 'incompatible type' errors."
  #{#{:uuid :ref}    ; :ref is stored as UUID
    #{:jsonb :union}})  ; :union is stored as JSONB


(defn types-equivalent?
  "Returns true if two types are equivalent (stored the same way).
   For example, :ref is stored as :uuid, and :union is stored as :jsonb.
   This is used to avoid false 'incompatible type' errors during migration."
  [t1 t2]
  (some #(and (contains? % t1) (contains? % t2)) type-equivalents))


(defn safe-type-change?
  "Returns true if changing from old-type to new-type is safe.
   Safe changes are: same type, equivalent types, or widening to a more general type."
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
   - old-nullable?: the current nullable value in storage
   - new-nullable?: the new nullable value in schema

   Throws ExceptionInfo with :type :destructive-change if nullable change is unsafe
   (i.e., changing from nullable to non-nullable)."
  [entity-name field-name old-nullable? new-nullable?]
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

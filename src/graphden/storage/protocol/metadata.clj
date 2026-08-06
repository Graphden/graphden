(ns graphden.storage.protocol.metadata
  "Schema diff and metadata utilities.

   Contains:
   - Schema diff computation (entity, field, enum changes)
   - Metadata building from DataSchema
   - Type compatibility and change detection"
  (:require
    [clojure.set :as set]
    [clojure.tools.logging :as log]
    [graphden.schema.fields.types :as ft]
    [graphden.schema.protocol.protocol :as ds]))


;; === Type compatibility ===

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
      (ft/types-equivalent? old-type new-type)
      (contains? (get ft/type-widening old-type #{}) new-type)))


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


(defn warn-removed!
  "Rollback-tolerant sibling of `check-removed!`: an item the DB knows
   that the current schema does NOT declare is LOGGED and LEFT in place,
   never thrown on.

   Rationale (2026-08-06 outage class): the diff `old-uuids - new-uuids`
   cannot tell an intentional forward removal apart from an OLD image
   booting against a DB a NEWER image already migrated (e.g. a rolled-back
   deploy where the newer code had added `token.label`). Throwing crashed
   boot on the rollback and forced a `DROP SCHEMA` recovery — total data
   loss. Leaving the unknown column/entity/enum-value is HARMLESS: the
   additive migration (`generic_migration.clj`) never drops it (only
   explicit `retire-field` DROPs a column), and code that doesn't declare
   it simply never reads it. Re-adding the field later finds the data
   intact.

   Returns the set of removed UUIDs (for callers/tests)."
  [item-type old-uuids new-uuids get-name-fn]
  (let [removed (set/difference old-uuids new-uuids)]
    (when (seq removed)
      (log/warn (str "Schema no longer declares " (count removed) " " item-type
                     " the DB retains — leaving them in place (forward-compat / "
                     "rollback-tolerant). Use `retire-field` to intentionally DROP. "
                     "Retained: " (pr-str (vec (map get-name-fn removed))))))
    removed))


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
  (into [] (mapcat (fn [e]
                     (map (fn [[f _]] {:entity e :field f})
                          (ds/entity-fields schema e))))
        (ds/entities schema)))


(defn collect-created-enum-values
  "Collects created enum values info for changes report.
   Returns [{:enum enum-name :value v} ...]"
  [schema]
  (into [] (mapcat (fn [[enum-name {:keys [values]}]]
                     (map (fn [[v _]] {:enum enum-name :value v})
                          values)))
        (ds/enums schema)))


(defn collect-field-uuids
  "Collects all field UUIDs from schema.
   Returns set of UUIDs."
  [schema]
  (into #{} (mapcat (fn [e]
                      (map (fn [[_ spec]] (:uuid spec))
                           (ds/entity-fields schema e))))
        (ds/entities schema)))


(defn collect-enum-value-uuids
  "Collects all enum value UUIDs from schema.
   Returns set of UUIDs."
  [schema]
  (into #{} (mapcat (fn [[_ {:keys [values]}]]
                      (map second values)))
        (ds/enums schema)))


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


(defn collect-retired-field-uuids
  "Set of UUIDs the schema marked as intentionally removed via
   `retire-field`. The migration framework excludes these from the
   destructive-change rejection set and runs `:on-delete-field!`
   for each instead. Empty set when the schema has nothing retired."
  [schema]
  (into #{}
        (mapcat (fn [[_ field-map]] (vals field-map)))
        (ds/retired-fields schema)))


(defn check-all-removals!
  "Reconciles items the DB knows but the current schema no longer declares
   (entities, fields, enums, enum-values) in a ROLLBACK-TOLERANT way: each
   such item is LOGGED and LEFT in place (`warn-removed!`), never thrown on.

   Why not throw: the old-vs-new diff cannot distinguish an intentional
   forward removal from an OLD image booting against a DB a NEWER image
   already migrated. Throwing crashed boot on any rollback and forced a
   `DROP SCHEMA` recovery (the 2026-08-06 outage). Leaving the unknown
   object is harmless — the additive migration never drops it; only an
   explicit `retire-field` issues a DROP COLUMN.

   Field-level retirements declared via `retire-field` are filtered out of
   the field diff (they are handled by the DROP-COLUMN path, not warned).

   Returns a map of {item-type → removed-uuid-set} for observability/tests."
  [old-metadata schema]
  {:entities
   (let [old-entity-uuids (set (keys (:entities old-metadata)))
         new-entity-uuids (into #{} (map #(ds/entity-uuid schema %)) (ds/entities schema))]
     (warn-removed! "entities" old-entity-uuids new-entity-uuids
                    #(get (:entities old-metadata) %)))
   :fields
   ;; minus declared retirements (those take the explicit DROP-COLUMN path).
   (let [retired (collect-retired-field-uuids schema)
         old-field-uuids (set/difference (set (keys (:fields old-metadata))) retired)
         new-field-uuids (collect-field-uuids schema)]
     (warn-removed! "fields" old-field-uuids new-field-uuids
                    #(get (:fields old-metadata) %)))
   :enums
   (let [old-enum-uuids (set (keys (:enums old-metadata)))
         new-enum-uuids (into #{} (map (fn [[_ {:keys [uuid]}]] uuid)) (ds/enums schema))]
     (warn-removed! "enums" old-enum-uuids new-enum-uuids
                    #(get (:enums old-metadata) %)))
   :enum-values
   (let [old-value-uuids (set (keys (:enum-values old-metadata)))
         new-value-uuids (collect-enum-value-uuids schema)]
     (warn-removed! "enum values" old-value-uuids new-value-uuids
                    #(get (:enum-values old-metadata) %)))})


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

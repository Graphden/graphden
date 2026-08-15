(ns graphden.storage.protocol.generic-migration
  "Generic migration pipeline shared by postgres and datomic backends.

   Extracts the common migration orchestration pattern:
   1. Check for destructive changes (removals)
   2. Validate type compatibility
   3. Process enums (create new, rename existing, add values)
   4. Process entities (create new, rename existing)
   5. Process fields (create new, rename existing)
   6. Save metadata
   7. Return changes map

   Backend-specific operations are provided via a callbacks map.

   ## Callbacks Map

   Required callbacks:
   - :make-field-verifier   (fn [old-metadata old-entity-name] -> verify-fn)
     Returns a function (fn [entity-name field-name field-spec old-field-info] -> nil or throw)
     that verifies a field exists in the database (consistency check).
   - :on-create-enum!       (fn [ctx enum-name values] -> nil)
   - :on-add-enum-value!    (fn [ctx enum-name value-kw] -> nil)
   - :on-create-entity!     (fn [ctx schema entity-name] -> nil)
   - :on-create-field!      (fn [ctx entity-name field-name field-spec] -> nil)
   - :save-metadata!        (fn [schema] -> nil)

   Optional callbacks:
   - :on-rename-enum!       (fn [ctx old-name new-name] -> nil)
   - :on-rename-entity!     (fn [ctx old-name new-name] -> nil)
   - :on-rename-field!      (fn [ctx entity-name old-name new-name] -> nil)
   - :on-existing-field!    (fn [ctx entity-name field-name field-spec old-field-info] -> nil)
     Called after rename check for existing fields (e.g., postgres type widening).
   - :extra-context-keys    map of additional keys for migration context
   - :post-process!         (fn [ctx] -> nil)
     Called after processing, before save-metadata (e.g., datomic transact + validate)."
  (:require
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.protocol.core :as sp]))


;; === Migration context ===

(defn create-migration-context
  "Creates a mutable context for tracking migration changes.
   extra-keys is merged in for backend-specific tracking (e.g., :new-schema atom)."
  ([] (create-migration-context {}))
  ([extra-keys]
   (merge {:created-entities (atom [])
           :renamed-entities (atom {})
           :created-fields (atom [])
           :renamed-fields (atom [])
           :created-enums (atom [])
           :renamed-enums (atom {})
           :created-enum-values (atom [])
           :renamed-enum-values (atom [])}
          extra-keys)))


(defn context->changes
  "Extracts the final changes map from migration context."
  [ctx]
  {:entities {:created @(:created-entities ctx) :renamed @(:renamed-entities ctx)}
   :fields {:created @(:created-fields ctx) :renamed @(:renamed-fields ctx)}
   :enums {:created @(:created-enums ctx) :renamed @(:renamed-enums ctx)}
   :enum-values {:created @(:created-enum-values ctx)
                 :renamed @(:renamed-enum-values ctx)}})


;; === Type compatibility checks ===

(defn check-single-field-type!
  "Checks type and nullable compatibility for a single field.
   verify-field-in-db! is called to confirm the field exists in the database."
  [verify-field-in-db! entity-name old-metadata field-name field-spec]
  (let [field-uuid (:uuid field-spec)
        old-field-info (get (:fields old-metadata) field-uuid)]
    (when old-field-info
      (verify-field-in-db! entity-name field-name field-spec old-field-info)
      (let [old-type (:type old-field-info)
            new-type (:type field-spec)
            old-nullable? (:nullable? old-field-info)
            new-nullable? (get field-spec :nullable? false)]
        (sp/check-type-change! entity-name field-name old-type new-type)
        (sp/check-nullable-change! entity-name field-name old-nullable? new-nullable?)))))


(defn check-all-field-types!
  "Checks type compatibility for all entities and fields.
   make-field-verifier: (fn [old-metadata old-entity-name] -> verify-fn)
   where verify-fn: (fn [entity-name field-name field-spec old-field-info] -> nil or throw)."
  [make-field-verifier old-metadata schema]
  (run! (fn [entity-name]
          (let [entity-uuid (ds/entity-uuid schema entity-name)
                old-entity-name (get (:entities old-metadata) entity-uuid)]
            (when old-entity-name
              (let [verify-fn (make-field-verifier old-metadata old-entity-name)]
                (run! (fn [[field-name field-spec]]
                        (check-single-field-type! verify-fn entity-name old-metadata field-name field-spec))
                      (ds/entity-fields schema entity-name))))))
        (ds/entities schema)))


;; === Enum processing ===

(defn- process-existing-enum-value!
  "Adds a new value to an existing enum if its uuid is absent from old
   metadata; RENAMES the pg enum label when the uuid is present but
   the keyword changed (the uuid is the identity — same contract as
   entity/field renames). Without the rename arm the keyword change
   was a silent no-op: pg kept the old label while save-metadata!
   recorded the new one, the drift became invisible to every later
   uuid-keyed diff, and the first write with the new label failed at
   pg ('invalid input value for enum'). A backend without the
   callback fails loudly instead of silently diverging."
  [callbacks old-metadata enum-name value-kw value-uuid ctx]
  (if-let [old-entry (get (:enum-values old-metadata) value-uuid)]
    (when (not= (:value old-entry) value-kw)
      (if-let [rename! (:on-rename-enum-value! callbacks)]
        (do (rename! ctx enum-name (:value old-entry) value-kw)
            (swap! (:renamed-enum-values ctx) conj
                   {:enum enum-name :old (:value old-entry) :new value-kw}))
        (throw (ex-info (str "enum value renamed (" enum-name ": "
                             (:value old-entry) " -> " value-kw
                             ") but the backend has no "
                             ":on-rename-enum-value! callback — refusing "
                             "to let metadata diverge from storage")
                        {:type :migration/enum-value-rename-unsupported
                         :enum enum-name
                         :old (:value old-entry) :new value-kw}))))
    (do ((:on-add-enum-value! callbacks) ctx enum-name value-kw)
        (swap! (:created-enum-values ctx) conj {:enum enum-name :value value-kw}))))


(defn- process-single-enum!
  "Processes a single enum during migration (rename or create)."
  [callbacks old-metadata enum-name {:keys [uuid values]} ctx]
  (if-let [old-enum-name (get (:enums old-metadata) uuid)]
    (do
      ;; Existing enum - check for rename
      (when (not= old-enum-name enum-name)
        (when-let [rename-fn (:on-rename-enum! callbacks)]
          (rename-fn ctx old-enum-name enum-name))
        (swap! (:renamed-enums ctx) assoc old-enum-name enum-name))
      ;; Add new values
      (run! (fn [[value-kw value-uuid]]
              (process-existing-enum-value! callbacks old-metadata enum-name value-kw value-uuid ctx))
            values))
    ;; New enum
    (do
      ((:on-create-enum! callbacks) ctx enum-name values)
      (swap! (:created-enums ctx) conj enum-name)
      (run! (fn [[v _]] (swap! (:created-enum-values ctx) conj {:enum enum-name :value v}))
            values))))


;; === Field processing ===

(defn- process-existing-field!
  "Processes an existing field during migration (rename, type widening)."
  [callbacks entity-name field-name field-spec old-field-info ctx]
  ;; Check for rename
  (when (not= (:field old-field-info) field-name)
    (when-let [rename-fn (:on-rename-field! callbacks)]
      (rename-fn ctx entity-name (:field old-field-info) field-name))
    (swap! (:renamed-fields ctx) conj {:entity entity-name
                                       :old-field (:field old-field-info)
                                       :new-field field-name}))
  ;; Optional post-processing (e.g., postgres type widening)
  (when-let [existing-fn (:on-existing-field! callbacks)]
    (existing-fn ctx entity-name field-name field-spec old-field-info)))


(defn- process-single-field!
  "Processes a single field during migration."
  [callbacks old-metadata entity-name field-name field-spec ctx]
  (let [field-uuid (:uuid field-spec)
        old-field-info (get (:fields old-metadata) field-uuid)]
    (if old-field-info
      (process-existing-field! callbacks entity-name field-name field-spec old-field-info ctx)
      ;; New field
      (do
        ((:on-create-field! callbacks) ctx entity-name field-name field-spec)
        (swap! (:created-fields ctx) conj {:entity entity-name :field field-name})))))


;; === Entity processing ===

(defn- process-existing-entity!
  "Processes an existing entity during migration."
  [callbacks schema old-metadata entity-name old-entity-name ctx]
  ;; Check for rename
  (when (not= old-entity-name entity-name)
    (when-let [rename-fn (:on-rename-entity! callbacks)]
      (rename-fn ctx old-entity-name entity-name))
    (swap! (:renamed-entities ctx) assoc old-entity-name entity-name))
  ;; Process fields
  (run! (fn [[field-name field-spec]]
          (process-single-field! callbacks old-metadata entity-name field-name field-spec ctx))
        (ds/entity-fields schema entity-name)))


(defn- process-single-entity!
  "Processes a single entity during migration (existing or new)."
  [callbacks schema old-metadata entity-name ctx]
  (let [entity-uuid (ds/entity-uuid schema entity-name)
        old-entity-name (get (:entities old-metadata) entity-uuid)]
    (if old-entity-name
      (process-existing-entity! callbacks schema old-metadata entity-name old-entity-name ctx)
      ;; New entity
      (do
        ((:on-create-entity! callbacks) ctx schema entity-name)
        (swap! (:created-entities ctx) conj entity-name)
        (run! (fn [[f _]] (swap! (:created-fields ctx) conj {:entity entity-name :field f}))
              (ds/entity-fields schema entity-name))))))


;; === Main orchestration ===

(defn- process-retired-fields!
  "Run `:on-delete-field!` for every field marked as retired on the
   schema. Backends that don't support drops can omit the callback —
   `do-migration!` then logs a no-op (the field stays in storage).
   Idempotent: rerunning the migration with the same retired set
   should be safe (`DROP COLUMN IF EXISTS` semantics on the backend
   side). The framework only knows about the call; correctness lives
   in the callback."
  [callbacks old-metadata schema ctx]
  (when-let [on-delete (:on-delete-field! callbacks)]
    (doseq [[entity-name field-map] (ds/retired-fields schema)
            [field-name uuid] field-map]
      ;; Drop by the CURRENT column name resolved from old metadata's
      ;; uuid index, falling back to the declared tombstone name. The
      ;; name-only drop was a fully silent no-op when a deployment
      ;; skipped an intermediate rename release (uuid exempted from
      ;; the removal warn, IF EXISTS swallowed the miss, the column
      ;; lingered).
      (let [current-name (or (get-in old-metadata [:fields uuid :field])
                             field-name)]
        (on-delete ctx entity-name current-name)))))


(defn do-migration!
  "Performs schema migration using the provided callbacks.
   Returns changes map with created/renamed entities/fields/enums."
  [callbacks old-metadata schema]
  ;; Check for destructive changes (retired fields are exempt — they
  ;; were declared explicitly and get processed below).
  (sp/check-all-removals! old-metadata schema)

  ;; Check type compatibility
  (check-all-field-types! (:make-field-verifier callbacks) old-metadata schema)

  ;; Create migration context
  (let [ctx (create-migration-context (or (:extra-context-keys callbacks) {}))]
    ;; Process enums
    (run! (fn [[enum-name enum-def]]
            (process-single-enum! callbacks old-metadata enum-name enum-def ctx))
          (ds/enums schema))

    ;; Process entities and fields
    (run! #(process-single-entity! callbacks schema old-metadata % ctx)
          (ds/entities schema))

    ;; Process retired fields — DROP COLUMN equivalents.
    (process-retired-fields! callbacks old-metadata schema ctx)

    ;; Optional post-processing (e.g., datomic transact + validate)
    (when-let [post-fn (:post-process! callbacks)]
      (post-fn ctx))

    ;; Save metadata
    ((:save-metadata! callbacks) schema)

    ;; Return changes
    (context->changes ctx)))

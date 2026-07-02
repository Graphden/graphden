(ns graphden.storage.postgres.test-setup
  "Shared test setup and helpers for postgres-storage tests.

   This namespace provides:
   - Testcontainer fixture functions
   - Dynamic var for container binding
   - Helper function for creating test storage
   - Production-shape graph schema (`make-graph-schema` returns the
     fn / slot / fn-slot / binding / binding-list-item schema)
   - CRUD helpers (`create-base-fn!`, `create-composed-fn!`,
     `create-slot!`, `create-fn-slot!`, `create-binding!`,
     `create-list-item!`)

   Usage in test namespaces:
   ```clojure
   (use-fixtures :once (setup/container-fixture))
   (use-fixtures :each (setup/clean-db-fixture))

   ;; In tests:
   (let [storage (setup/create-test-storage)]
     ...)
   ```"
  (:require
    [graphden.packages.records :as records]
    [graphden.schema.graph.schema :as graph-schema]
    [graphden.schema.malli.core :as mds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]))


(def primitive-fn-ids
  "Map keyword → uuid for the 14 primitive type-rows. Helpers accept
   primitive keywords as type-fn-id refs and look them up here."
  (records/primitive-fn-ids))


(defn seed-primitives!
  "Inserts the 14 primitive fn-rows so slot.type-fn-id refs resolve.
   Idempotent — uses upsert."
  [storage]
  (sp/upsert-entities storage :fn
                      (mapv #(dissoc % :kind) (records/boot-primitive-records)))
  storage)


(def ^:dynamic *container*
  "Dynamic var holding the testcontainer for PostgreSQL.
   Bound by container-fixture."
  nil)


(defn container-fixture
  "Creates a fixture that starts PostgreSQL testcontainer once per test namespace.
   Returns a function suitable for use with `use-fixtures :once`."
  []
  (pth/create-container-fixture #'*container*))


(defn clean-db-fixture
  "Creates a fixture that cleans the database before each test.
   Returns a function suitable for use with `use-fixtures :each`."
  []
  (pth/create-clean-db-fixture #'*container*))


(defn create-test-storage
  "Creates a test storage with a clean database.
   Cleans DB to ensure isolation when multiple storages created in one test."
  []
  (pth/clean-database-fast! *container*)
  (pg/create-storage (pth/get-container-config *container*)))


(defn get-container-config
  "Returns the connection config for the current testcontainer."
  []
  (pth/get-container-config *container*))


(defn make-graph-schema
  "Builds the production graph schema (fn / slot / fn-slot / binding /
   binding-list-item) plus the namespace entity. Suitable for any
   integration test that needs the live storage shape."
  []
  (graph-schema/build-schema (mds/create-builder)))


(defn create-base-fn!
  "Creates a base fn (no parents, `return-type-fn-id` set — THE base-fn
   marker, defaulting to the `:any` primitive). Returns the row."
  [storage entity-name]
  (sp/create-entity storage :fn
                    {:name entity-name
                     :parent-ids []
                     :return-type-fn-id (records/primitive-fn-id :any)}))


(defn create-composed-fn!
  "Creates a composed fn (has parent-ids). Returns the row.

   Either pass a single parent uuid via the 3-arity or a vector via
   the 3-arity (vector form covers the MI case)."
  [storage entity-name parent-spec]
  (sp/create-entity storage :fn
                    {:name entity-name
                     :parent-ids (cond
                                   (sequential? parent-spec) (vec parent-spec)
                                   (some? parent-spec) [parent-spec]
                                   :else [])}))


(defn- resolve-type-ref
  "Coerces a type-ref (UUID or primitive keyword) into a fn-id UUID."
  [type-ref]
  (cond
    (uuid? type-ref) type-ref
    (keyword? type-ref) (or (get primitive-fn-ids type-ref)
                            (throw (ex-info (str "Unknown primitive type: " type-ref)
                                            {:type-ref type-ref
                                             :known (keys primitive-fn-ids)})))
    :else type-ref))


(defn create-slot!
  "Creates a slot row with the given name and type-fn-id. `type-ref`
   may be a UUID or a primitive keyword (`:int`, `:text`, …)."
  [storage slot-name type-ref & {:keys [required description]
                                 :or {required true}}]
  (sp/create-entity storage :slot
                    (cond-> {:name slot-name
                             :type-fn-id (resolve-type-ref type-ref)
                             :required required}
                      description (assoc :description description))))


(defn create-fn-slot!
  "Wires `slot-id` onto `fn-id` at `position` (0-based)."
  [storage fn-id slot-id position]
  (sp/create-entity storage :fn-slot
                    {:fn-id fn-id
                     :slot-id slot-id
                     :position position}))


(defn create-binding!
  "Creates a binding row at `(fn-id, slot-id)`. Pass any combination of
   `:value`, `:ref-fn-id`, `:rename-to`, `:type-override-fn-id`,
   `:list-append`, `:list-closed`, `:description`."
  [storage fn-id slot-id & {:as fields}]
  (sp/create-entity storage :binding
                    (merge {:fn-id fn-id :slot-id slot-id} fields)))


(defn create-list-item!
  "Creates a binding-list-item row under `binding-id` at `position`."
  [storage binding-id position & {:keys [value ref-fn-id literal]}]
  (sp/create-entity storage :binding-list-item
                    (cond-> {:binding-id binding-id :position position}
                      (some? value) (assoc :value value)
                      ref-fn-id (assoc :ref-fn-id ref-fn-id)
                      (some? literal) (assoc :literal literal))))


(defn create-arg!
  "Compatibility helper that bridges legacy `arg`-table call sites to
   the slot/binding model. Two flavours:

   1. **Primary form** (no `:source-id`): creates a slot owned by
      `fn-id` and attaches it via fn-slot. Returns the slot record.

   2. **Inherited form** (`:source-id` set, slot record/id passed as
      the source): emits a binding row at `(fn-id, source-slot-id)`
      with `:value` or `:ref-id` as supplied.

   New tests should call `create-slot!` / `create-fn-slot!` /
   `create-binding!` directly; this shim only exists so existing
   integration tests can link against the new helpers without
   wholesale rewrite."
  ([storage fn-id opts]
   (create-arg! storage fn-id opts 0))
  ([storage fn-id
    {arg-name :name arg-type :type
     :keys [source-id value ref-id]} position]
   (cond
     source-id
     (let [;; `:source-id` carries the slot record / slot-id directly
           ;; in the new model — primary args (form 1 above) return the
           ;; slot row, so callers thread `(:id slot)` here.
           slot-id source-id]
       (cond
         (some? value) (create-binding! storage fn-id slot-id :value value)
         (some? ref-id) (create-binding! storage fn-id slot-id :ref-fn-id ref-id)
         :else nil))

     :else
     (let [slot (create-slot! storage arg-name (or arg-type :any))]
       (create-fn-slot! storage fn-id (:id slot) position)
       slot))))

(ns graphden.executor.test-setup
  "Shared test setup for executor tests.

   Provides helper functions for creating test storage and setting up
   common test fixtures using PostgreSQL testcontainers.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [graphden.executor.interface :as exec]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]))


;; ============================================================================
;; Container Management
;; ============================================================================

(def ^:dynamic *container*
  "Dynamic var holding the PostgreSQL container for tests."
  nil)


(defn create-container-fixture
  "Creates a :once fixture that starts/stops a PostgreSQL container."
  []
  (pth/create-container-fixture #'*container*))


(defn create-clean-db-fixture
  "Creates an :each fixture that cleans the database before each test."
  []
  (pth/create-clean-db-fixture #'*container*))


;; ============================================================================
;; Storage Creation
;; ============================================================================

(defn create-test-storage
  "Creates a PostgreSQL storage from the current test container.
   Cleans the database and initializes schema before creating storage.
   Must be called within a test that has the container fixture active."
  []
  (pth/clean-database-fast! *container*)
  (let [storage (pg/create-storage (pth/get-container-config *container*))
        schema (gds/build-schema (mds/create-builder))]
    (sp/initialize storage schema)
    storage))


;; ============================================================================
;; Test Helpers - 2-Entity Schema
;; ============================================================================

(defn create-arg!
  "Creates an arg entity with the given properties.

   For base-fn args (no inheritance): source-id should be nil
   For composed fn args: source-id points to parent's arg

   Options:
   - :value - literal JSONB value
   - :ref-id - FK to fn (execute and use result)
   - :is-fn - pass fn-id directly (for HOF)"
  [storage fn-id {:keys [required is-fn source-id value ref-id]
                  arg-name :name
                  arg-type :type
                  :or {required true is-fn false}}]
  (sp/create-entity storage :arg
                    (cond-> {:fn-id fn-id
                             :name arg-name
                             :type arg-type
                             :required required
                             :is-fn is-fn}
                      source-id (assoc :source-id source-id)
                      (some? value) (assoc :value value)
                      ref-id (assoc :ref-id ref-id))))


(defn create-composed-fn!
  "Creates a composed fn with parent-id set.
   Returns the fn entity."
  [storage entity-name parent-id]
  (sp/create-entity storage :fn {:name entity-name :parent-id parent-id}))


(defn create-base-fn!
  "Creates a base fn (parent-id=nil).
   The name field is used for registry lookup.
   Returns the fn entity."
  [storage entity-name return-type]
  (sp/create-entity storage :fn
                    {:name entity-name
                     :return-type return-type}))


(defn setup-add-function!
  "Sets up an 'add' function that adds two numbers.
   Returns {:fn base-fn :arg-a arg-a :arg-b arg-b :composed-fn composed-fn}

   In 2-entity schema:
   - Creates base fn with parent-id=nil
   - Creates args owned by base fn
   - Creates composed fn with parent-id=base-fn-id"
  [storage]
  ;; Register the base function (args are delays, use @ to deref)
  (exec/register-base-fn!
    :add
    (fn [{:keys [a b]} _ctx]
      (+ @a @b)))

  ;; Create base fn - keep name "add" to match registry keyword
  ;; Composed fn gets unique name to avoid conflicts
  (let [unique-suffix (str (random-uuid))
        base-fn (create-base-fn! storage "add" :int)
        ;; Create args for base fn
        arg-a (create-arg! storage (:id base-fn)
                           {:name "a" :type :int :required true :is-fn false})
        arg-b (create-arg! storage (:id base-fn)
                           {:name "b" :type :int :required true :is-fn false})
        ;; Create composed fn instance with unique name
        composed-fn (create-composed-fn! storage (str "my-add-" unique-suffix) (:id base-fn))]
    {:base-fn base-fn
     :arg-a arg-a
     :arg-b arg-b
     :composed-fn composed-fn}))

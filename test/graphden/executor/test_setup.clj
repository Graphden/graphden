(ns graphden.executor.test-setup
  "Shared test setup for executor tests.

   Provides helper functions for creating test storage and setting up
   common test fixtures using PostgreSQL testcontainers.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)

   NOTE: This module has entity helpers that work with the PRODUCTION schema
   (gds/build-schema). storage.postgres.test-setup has similar helpers but
   they work with a simplified test schema (make-graph-schema) with text types
   instead of enums. Cannot unify without schema alignment."
  (:require
    [graphden.executor.interface :as exec]
    [graphden.executor.runtime :as rt]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]))


;; ============================================================================
;; Impl helper — inline `defbase`-style for test registration
;; ============================================================================

(defmacro fn-impl
  "Build an anonymous base-fn impl whose body references args by name,
   mirroring `defbase` but inline. Equivalent to:

     (fn [args ctx]
       (let [a (rt/resolve-arg args :a)
             b (rt/resolve-arg args :b)]
         body))

   The symbols `args` and `ctx` are bound by the generated fn and
   accessible from the body — useful for HOF impls that need ctx
   (`(exec/make-single-arg-callable ctx some-fn)`) or lazy impls that
   want to skip the eager resolve for specific keys
   (`(rt/resolve-arg args :then-branch)` inside an `if`).

   Use in tests instead of `(fn [{:keys [a b]} _] (+ @a @b))` — the
   latter relies on the legacy-deref adapter which production no longer
   pays for on the hot path."
  [arg-syms & body]
  (let [let-bindings (mapcat (fn [s] [s `(rt/resolve-arg ~'args ~(keyword s))]) arg-syms)]
    `(fn [~'args ~'ctx]
       (let [~'ctx ~'ctx               ; keep kondo quiet for bodies that ignore ctx
             ~'args ~'args             ; and same for args if the body only names resolved syms
             ~@let-bindings]
         ~@body))))


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
;; Test Helpers - 2-Entity Schema (PRODUCTION schema with enum types)
;; ============================================================================

(defn create-base-fn!
  "Creates a base fn entity. Returns the fn record.
   Base fns have parent-id=nil. Uses PRODUCTION schema with enum types."
  [storage entity-name return-type]
  (sp/create-entity storage :fn
                    {:name entity-name
                     :parent-ids nil
                     :return-type return-type}))


(defn create-composed-fn!
  "Creates a composed fn entity. Returns the fn record.
   Composed fns have parent-id set to their base fn."
  [storage entity-name parent-id]
  (sp/create-entity storage :fn
                    {:name entity-name
                     :parent-ids [parent-id]}))


(defn create-arg!
  "Creates an arg entity. Returns the arg record.

   Required:
   - fn-id: the fn this arg belongs to
   - opts map with :name, :type, :required, :is-fn

   Optional in opts:
   - :source-id - parent arg this inherits from
   - :value - literal value (mutually exclusive with ref-id)
   - :ref-id - reference to another fn (mutually exclusive with value)"
  [storage fn-id {:keys [required is-fn source-id value ref-id]
                  arg-name :name
                  arg-type :type}]
  (sp/create-entity storage :arg
                    {:fn-id fn-id
                     :name arg-name
                     :type arg-type
                     :required required
                     :is-fn is-fn
                     :source-id source-id
                     :value value
                     :ref-id ref-id}))


(defn setup-add-function!
  "Sets up an 'add' function that adds two numbers.
   Returns {:fn base-fn :arg-a arg-a :arg-b arg-b :composed-fn composed-fn}

   In 2-entity schema:
   - Creates base fn with parent-id=nil
   - Creates args owned by base fn
   - Creates composed fn with parent-id=base-fn-id"
  [storage]
  ;; Register the base function. Args arrive via compile's build-args-map —
  ;; plain values for literal bindings, `rt/thunk` for refs, or raw values
  ;; in free-args. `rt/resolve-arg` handles all three.
  (exec/register-base-fn!
    :add
    (fn [args _ctx]
      (+ (rt/resolve-arg args :a) (rt/resolve-arg args :b))))

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

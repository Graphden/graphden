(ns graphden.executor.test-setup
  "Shared test setup for executor tests.

   Provides helper functions for creating test storage and setting up
   common test fixtures using AGE testcontainers."
  (:require
    [graphden.executor.interface :as exec]
    [graphden.storage.age.test-setup :as th]
    [graphden.storage.protocol.core :as sp]))


;; ============================================================================
;; Container Management
;; ============================================================================

(def ^:dynamic *container*
  "Dynamic var holding the PostgreSQL container for tests."
  nil)


(defn create-container-fixture
  "Creates a :once fixture that starts/stops a PostgreSQL container."
  []
  (th/create-container-fixture #'*container*))


(defn create-clean-db-fixture
  "Creates an :each fixture that cleans the database before each test."
  []
  (th/create-clean-db-fixture #'*container*))


;; ============================================================================
;; Storage Creation
;; ============================================================================

(defn create-test-storage
  "Creates an AGE storage from the current test container.
   Cleans the database and initializes schema before creating storage.
   Must be called within a test that has the container fixture active."
  []
  (th/create-test-storage *container*))


;; ============================================================================
;; Test Helpers
;; ============================================================================

(defn create-fn-usage!
  "Creates a fn-usage entity pointing to a fn.
   Returns the fn-usage id (for use as arg-value :value).

   Use this when you want the referenced fn to be EXECUTED and its
   result used as the argument value. If you want to pass the fn
   itself (e.g., for HOF), use the fn-id directly.

   Optional second arg is the result-name for deduplication (defaults to random UUID string)."
  ([storage fn-id]
   (create-fn-usage! storage fn-id (str (random-uuid))))
  ([storage fn-id result-name]
   (:id (sp/create-entity storage :fn-usage {:fn-id fn-id :name result-name}))))


(defn create-arg-value-with-binding!
  "Creates arg-value and fn-arg binding. Returns the arg-value.

   With normalized schema:
   - arg-value is a pure value (no owner-fn-id)
   - fn-arg binds fn to arg-value"
  [storage fn-id arg-schema-id value]
  (let [av (sp/create-entity storage :arg-value
                             {:arg-schema-id arg-schema-id
                              :value value})]
    (sp/create-entity storage :fn-arg
                      {:fn-id fn-id
                       :arg-schema-id arg-schema-id
                       :arg-value-id (:id av)})
    av))


(defn setup-add-function!
  "Sets up an 'add' function that adds two numbers.
   Returns {:fn-schema fn-schema :arg-a arg-schema-a :arg-b arg-schema-b :fn fn-rec}"
  [storage]
  ;; Register the base function (args are delays, use @ to deref)
  (exec/register-base-fn!
    :add
    (fn [{:keys [a b]} _ctx]
      (+ @a @b)))

  ;; Create fn-schema
  (let [fn-schema (sp/create-entity storage :fn-schema
                                    {:name "add"
                                     :returned-type :int})
        ;; Create arg-schemas
        arg-a (sp/create-entity storage :arg-schema
                                {:fn-schema-id (:id fn-schema)
                                 :name "a"
                                 :type :int
                                 :required true :first-class false})
        arg-b (sp/create-entity storage :arg-schema
                                {:fn-schema-id (:id fn-schema)
                                 :name "b"
                                 :type :int
                                 :required true :first-class false})
        ;; Create fn instance
        fn-rec (sp/create-entity storage :fn
                                 {:name "my-add"
                                  :fn-schema-id (:id fn-schema)})]
    {:fn-schema fn-schema
     :arg-a arg-a
     :arg-b arg-b
     :fn-rec fn-rec}))

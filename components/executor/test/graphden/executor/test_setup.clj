(ns graphden.executor.test-setup
  "Shared test setup for executor tests.

   Provides helper functions for creating test storage and setting up
   common test fixtures."
  (:require
    [graphden.executor.interface :as exec]
    [graphden.graph-storage-memory.interface :as gsm]
    [graphden.storage-protocol.interface :as sp]))


(defn create-test-storage
  "Creates a storage with a simple fn-schema and registers the base function."
  []
  (gsm/create-storage))


(defn create-fn-result-value!
  "Creates a fn-result-value entity pointing to a fn.
   Returns the fn-result-value id (for use as arg-value :value).

   Use this when you want the referenced fn to be EXECUTED and its
   result used as the argument value. If you want to pass the fn
   itself (e.g., for HOF), use the fn-id directly.

   Optional second arg is the result-name for deduplication (defaults to random UUID string)."
  ([storage fn-id]
   (create-fn-result-value! storage fn-id (str (random-uuid))))
  ([storage fn-id result-name]
   (:id (sp/create-entity storage :fn-result-value {:fn-id fn-id :name result-name}))))


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
                                 :required true})
        arg-b (sp/create-entity storage :arg-schema
                                {:fn-schema-id (:id fn-schema)
                                 :name "b"
                                 :type :int
                                 :required true})
        ;; Create fn instance
        fn-rec (sp/create-entity storage :fn
                                 {:name "my-add"
                                  :fn-schema-id (:id fn-schema)})]
    {:fn-schema fn-schema
     :arg-a arg-a
     :arg-b arg-b
     :fn-rec fn-rec}))

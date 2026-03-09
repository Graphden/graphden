(ns graphden.storage.postgres.execution-graph-test
  "Tests for PostgreSQL storage ExecutionGraph protocol.

   Covers:
   - resolve-execution-graph with simple functions
   - resolve-execution-graph with function references
   - resolve-execution-graph edge cases (not found, non-uuid values, etc.)
   - Mock-based coverage tests

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.graph :as graph]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


;; === Helper ===

(defn- find-arg-by-name
  "Finds an arg by name from an execution graph for a specific fn-id."
  [execution-graph fn-id arg-name]
  (let [args (graph/get-graph-args-for-fn execution-graph fn-id)]
    (first (filter #(= arg-name (:name %)) args))))


;; === ExecutionGraph tests ===


(deftest resolve-execution-graph-simple-test
  (testing "resolves simple function with literal arg values"
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [;; Create base fn
              base-fn (setup/create-base-fn! storage "add" :int)
              base-arg-a (setup/create-arg! storage (:id base-fn)
                                            {:name "a" :type :int :required true :is-fn false})
              base-arg-b (setup/create-arg! storage (:id base-fn)
                                            {:name "b" :type :int :required true :is-fn false})
              ;; Create composed fn with values
              composed-fn (setup/create-composed-fn! storage "add-1-2" (:id base-fn))
              _ (setup/create-arg! storage (:id composed-fn)
                                   {:name "a" :type :int :required true :is-fn false
                                    :source-id (:id base-arg-a) :value 1})
              _ (setup/create-arg! storage (:id composed-fn)
                                   {:name "b" :type :int :required true :is-fn false
                                    :source-id (:id base-arg-b) :value 2})
              result (sp/resolve-execution-graph storage (:id composed-fn))]
          (is (contains? (:fns result) (:id composed-fn)))
          (is (contains? (:fns result) (:id base-fn)))
          (let [arg-a (find-arg-by-name result (:id composed-fn) "a")
                arg-b (find-arg-by-name result (:id composed-fn) "b")]
            (is (= 1 (:value arg-a)))
            (is (= 2 (:value arg-b)))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-with-refs-test
  (testing "resolves function with references to other functions"
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [;; Create const base fn
              const-base (setup/create-base-fn! storage "const" :int)
              const-arg (setup/create-arg! storage (:id const-base)
                                           {:name "value" :type :int :required true :is-fn false})
              ;; Create add base fn
              add-base (setup/create-base-fn! storage "add" :int)
              add-arg-a (setup/create-arg! storage (:id add-base)
                                           {:name "a" :type :int :required true :is-fn false})
              add-arg-b (setup/create-arg! storage (:id add-base)
                                           {:name "b" :type :int :required true :is-fn false})
              ;; Create const-3 composed fn
              const-3 (setup/create-composed-fn! storage "const-3" (:id const-base))
              _ (setup/create-arg! storage (:id const-3)
                                   {:name "value" :type :int :required true :is-fn false
                                    :source-id (:id const-arg) :value 3})
              ;; Create const-5 composed fn
              const-5 (setup/create-composed-fn! storage "const-5" (:id const-base))
              _ (setup/create-arg! storage (:id const-5)
                                   {:name "value" :type :int :required true :is-fn false
                                    :source-id (:id const-arg) :value 5})
              ;; Create add-3-5 that references const-3 and const-5 via ref-id
              add-3-5 (setup/create-composed-fn! storage "add-3-5" (:id add-base))
              _ (setup/create-arg! storage (:id add-3-5)
                                   {:name "a" :type :int :required true :is-fn false
                                    :source-id (:id add-arg-a) :ref-id (:id const-3)})
              _ (setup/create-arg! storage (:id add-3-5)
                                   {:name "b" :type :int :required true :is-fn false
                                    :source-id (:id add-arg-b) :ref-id (:id const-5)})
              graph (sp/resolve-execution-graph storage (:id add-3-5))]
          ;; Should have all 5 fns: add-3-5, add-base, const-3, const-5, const-base
          (is (= 5 (count (:fns graph))))
          (is (contains? (:fns graph) (:id add-3-5)))
          (is (contains? (:fns graph) (:id const-3)))
          (is (contains? (:fns graph) (:id const-5))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-not-found-test
  (testing "throws when function not found"
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Function not found"
              (sp/resolve-execution-graph storage (random-uuid))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-with-non-uuid-values-test
  (testing "handles non-UUID literal values correctly (not treated as fn refs)"
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [;; Create base fn with various arg types
              base-fn (setup/create-base-fn! storage "process" :text)
              arg-text (setup/create-arg! storage (:id base-fn)
                                          {:name "text-arg" :type :text :required true :is-fn false})
              arg-int (setup/create-arg! storage (:id base-fn)
                                         {:name "int-arg" :type :int :required true :is-fn false})
              ;; Create composed fn with literal values
              composed-fn (setup/create-composed-fn! storage "my-process" (:id base-fn))
              _ (setup/create-arg! storage (:id composed-fn)
                                   {:name "text-arg" :type :text :required true :is-fn false
                                    :source-id (:id arg-text) :value "hello world"})
              _ (setup/create-arg! storage (:id composed-fn)
                                   {:name "int-arg" :type :int :required true :is-fn false
                                    :source-id (:id arg-int) :value 42})
              result (sp/resolve-execution-graph storage (:id composed-fn))]
          ;; Should have 2 fns (composed + base)
          (is (= 2 (count (:fns result))))
          (is (contains? (:fns result) (:id composed-fn)))
          ;; Check resolved args have literal values
          (let [arg-text (find-arg-by-name result (:id composed-fn) "text-arg")
                arg-int (find-arg-by-name result (:id composed-fn) "int-arg")]
            (is (= "hello world" (:value arg-text)))
            (is (= 42 (:value arg-int)))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-shared-reference-test
  (testing "handles shared fn reference (same fn referenced by multiple args)"
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [;; const base fn
              const-base (setup/create-base-fn! storage "const" :int)
              const-arg (setup/create-arg! storage (:id const-base)
                                           {:name "value" :type :int :required true :is-fn false})
              ;; add base fn
              add-base (setup/create-base-fn! storage "add" :int)
              add-arg-a (setup/create-arg! storage (:id add-base)
                                           {:name "a" :type :int :required true :is-fn false})
              add-arg-b (setup/create-arg! storage (:id add-base)
                                           {:name "b" :type :int :required true :is-fn false})
              ;; const-5 fn - will be referenced TWICE
              const-5 (setup/create-composed-fn! storage "const-5-shared" (:id const-base))
              _ (setup/create-arg! storage (:id const-5)
                                   {:name "value" :type :int :required true :is-fn false
                                    :source-id (:id const-arg) :value 5})
              ;; add-5-5 fn referencing const-5 for BOTH args via ref-id
              add-5-5 (setup/create-composed-fn! storage "add-5-5-shared" (:id add-base))
              _ (setup/create-arg! storage (:id add-5-5)
                                   {:name "a" :type :int :required true :is-fn false
                                    :source-id (:id add-arg-a) :ref-id (:id const-5)})
              _ (setup/create-arg! storage (:id add-5-5)
                                   {:name "b" :type :int :required true :is-fn false
                                    :source-id (:id add-arg-b) :ref-id (:id const-5)})
              result (sp/resolve-execution-graph storage (:id add-5-5))]
          ;; const-5 should only appear once despite being referenced twice
          ;; 4 fns: add-5-5, add-base, const-5, const-base
          (is (= 4 (count (:fns result))))
          (is (contains? (:fns result) (:id add-5-5)))
          (is (contains? (:fns result) (:id const-5)))
          ;; Both args should have ref-id references
          (let [arg-a (find-arg-by-name result (:id add-5-5) "a")
                arg-b (find-arg-by-name result (:id add-5-5) "b")]
            (is (= (:id const-5) (:ref-id arg-a)))
            (is (= (:id const-5) (:ref-id arg-b)))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-self-reference-test
  (testing "handles fn with self-reference in arg (HOF pattern)"
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [;; recursive base fn with two args
              rec-base (setup/create-base-fn! storage "recursive" :int)
              ;; 'self' arg for HOF - is-fn=true means pass fn-id, not execute
              arg-self (setup/create-arg! storage (:id rec-base)
                                          {:name "self" :type :fn :required true :is-fn true})
              arg-n (setup/create-arg! storage (:id rec-base)
                                       {:name "n" :type :int :required true :is-fn false})
              ;; Create composed fn that references itself
              rec-fn (setup/create-composed-fn! storage "factorial" (:id rec-base))
              ;; Self-reference: self arg references rec-fn via ref-id
              _ (setup/create-arg! storage (:id rec-fn)
                                   {:name "self" :type :fn :required true :is-fn true
                                    :source-id (:id arg-self) :ref-id (:id rec-fn)})
              _ (setup/create-arg! storage (:id rec-fn)
                                   {:name "n" :type :int :required true :is-fn false
                                    :source-id (:id arg-n) :value 5})
              result (sp/resolve-execution-graph storage (:id rec-fn))]
          ;; Should have 2 fns (rec-fn + rec-base) - self-reference doesn't create duplicate
          (is (= 2 (count (:fns result))))
          (is (contains? (:fns result) (:id rec-fn)))
          ;; Self arg should have ref-id reference; n arg has literal value
          (let [arg-self (find-arg-by-name result (:id rec-fn) "self")
                arg-n (find-arg-by-name result (:id rec-fn) "n")]
            (is (= (:id rec-fn) (:ref-id arg-self)))
            (is (= 5 (:value arg-n)))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-uuid-literal-in-value-test
  (testing "UUID literals in :value field are not treated as fn references"
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [;; Create base fn with any type arg
              base-fn (setup/create-base-fn! storage "test-fn" :int)
              arg-any (setup/create-arg! storage (:id base-fn)
                                         {:name "any-arg" :type :any :required false :is-fn false})
              ;; Create composed fn with UUID in value (literal, not reference)
              some-uuid #uuid "99999999-9999-9999-9999-999999999999"
              composed-fn (setup/create-composed-fn! storage "fn-with-uuid-literal" (:id base-fn))
              _ (setup/create-arg! storage (:id composed-fn)
                                   {:name "any-arg" :type :any :required false :is-fn false
                                    :source-id (:id arg-any) :value (str some-uuid)})
              result (sp/resolve-execution-graph storage (:id composed-fn))]
          ;; Should have 2 fns (composed + base) - UUID in :value is literal, not followed
          (is (= 2 (count (:fns result))))
          (is (contains? (:fns result) (:id composed-fn)))
          ;; The arg should have the UUID as literal value
          (let [arg-any (find-arg-by-name result (:id composed-fn) "any-arg")]
            (is (= (str some-uuid) (:value arg-any)))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-hof-reference-test
  (testing "handles HOF fn references correctly (is-fn=true) via ref-id"
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [;; Create base fn with HOF arg
              base-fn (setup/create-base-fn! storage "test-fn" :int)
              arg-ref (setup/create-arg! storage (:id base-fn)
                                         {:name "ref-arg" :type :fn :required false :is-fn true})
              ;; Create main composed fn
              main-fn (setup/create-composed-fn! storage "main-fn" (:id base-fn))
              ;; Create referenced fn
              ref-fn (setup/create-composed-fn! storage "ref-fn" (:id base-fn))
              ;; main-fn's ref-arg -> ref-fn via ref-id
              _ (setup/create-arg! storage (:id main-fn)
                                   {:name "ref-arg" :type :fn :required false :is-fn true
                                    :source-id (:id arg-ref) :ref-id (:id ref-fn)})
              graph (sp/resolve-execution-graph storage (:id main-fn))]
          ;; 3 fns: main-fn, ref-fn, base-fn
          (is (= 3 (count (:fns graph))))
          (is (contains? (:fns graph) (:id main-fn)))
          (is (contains? (:fns graph) (:id ref-fn))))
        (finally
          (sp/close storage))))))

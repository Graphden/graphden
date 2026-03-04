(ns graphden.storage.postgres.execution-graph-test
  "Tests for PostgreSQL storage ExecutionGraph protocol.

   Covers:
   - resolve-execution-graph with simple functions
   - resolve-execution-graph with function references
   - resolve-execution-graph edge cases (not found, non-uuid values, etc.)
   - Mock-based coverage tests"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.postgres.crud :as crud]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


;; === ExecutionGraph tests ===


(deftest resolve-execution-graph-simple-test
  (testing "resolves simple function with no dependencies"
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "add" :returned-type "int"})
              arg-a (sp/create-entity storage :arg-schema
                                      {:fn-schema-id (:id fn-schema)
                                       :name "a" :type "int" :required true
                                       :first-class false})
              arg-b (sp/create-entity storage :arg-schema
                                      {:fn-schema-id (:id fn-schema)
                                       :name "b" :type "int" :required true
                                       :first-class false})
              fn-add (sp/create-entity storage :fn
                                       {:name "add-1-2"
                                        :fn-schema-id (:id fn-schema)})
              _ (setup/create-arg-value-with-binding! storage (:id fn-add) (:id arg-a) 1)
              _ (setup/create-arg-value-with-binding! storage (:id fn-add) (:id arg-b) 2)
              graph (sp/resolve-execution-graph storage (:id fn-add))]
          (is (contains? (:fns graph) (:id fn-add)))
          (is (contains? (:fn-schemas graph) (:id fn-schema)))
          (is (contains? (:arg-schemas graph) (:id arg-a)))
          (is (contains? (:arg-schemas graph) (:id arg-b)))
          (is (contains? (:resolved-args graph) (:id fn-add)))
          (let [args (get (:resolved-args graph) (:id fn-add))]
            (is (= 1 (:value (get args (:id arg-a)))))
            (is (= 2 (:value (get args (:id arg-b)))))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-with-fn-refs-test
  (testing "resolves function with references to other functions"
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [const-schema (sp/create-entity storage :fn-schema
                                             {:name "const-int" :returned-type "int"})
              const-arg (sp/create-entity storage :arg-schema
                                          {:fn-schema-id (:id const-schema)
                                           :name "value" :type "int" :required true
                                           :first-class false})
              add-schema (sp/create-entity storage :fn-schema
                                           {:name "add" :returned-type "int"})
              add-arg-a (sp/create-entity storage :arg-schema
                                          {:fn-schema-id (:id add-schema)
                                           :name "a" :type "int" :required true
                                           :first-class false})
              add-arg-b (sp/create-entity storage :arg-schema
                                          {:fn-schema-id (:id add-schema)
                                           :name "b" :type "int" :required true
                                           :first-class false})
              const-3 (sp/create-entity storage :fn
                                        {:name "const-3"
                                         :fn-schema-id (:id const-schema)})
              _ (setup/create-arg-value-with-binding! storage (:id const-3) (:id const-arg) 3)
              const-5 (sp/create-entity storage :fn
                                        {:name "const-5"
                                         :fn-schema-id (:id const-schema)})
              _ (setup/create-arg-value-with-binding! storage (:id const-5) (:id const-arg) 5)
              add-3-5 (sp/create-entity storage :fn
                                        {:name "add-3-5"
                                         :fn-schema-id (:id add-schema)})
              ;; Use fn-usages to reference const-3 and const-5 (execute them)
              cs-3 (setup/create-fn-usage! storage (:id const-3))
              cs-5 (setup/create-fn-usage! storage (:id const-5))
              _ (setup/create-arg-value-with-fn-usage-binding! storage (:id add-3-5) (:id add-arg-a) cs-3)
              _ (setup/create-arg-value-with-fn-usage-binding! storage (:id add-3-5) (:id add-arg-b) cs-5)
              graph (sp/resolve-execution-graph storage (:id add-3-5))]
          (is (= 3 (count (:fns graph))))
          (is (contains? (:fns graph) (:id add-3-5)))
          (is (contains? (:fns graph) (:id const-3)))
          (is (contains? (:fns graph) (:id const-5)))
          (is (= 2 (count (:fn-schemas graph))))
          (is (= 3 (count (:arg-schemas graph)))))
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
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "process" :returned-type "text"})
              ;; Various arg types with literal values
              arg-text (sp/create-entity storage :arg-schema
                                         {:fn-schema-id (:id fn-schema)
                                          :name "text-arg" :type "text" :required true
                                          :first-class false})
              arg-int (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id fn-schema)
                                         :name "int-arg" :type "int" :required true
                                         :first-class false})
              fn-rec (sp/create-entity storage :fn
                                       {:name "my-process"
                                        :fn-schema-id (:id fn-schema)})
              ;; Arg values with literals (not fn references)
              _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-text) "hello world")  ; String literal
              _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-int) 42)  ; Integer literal
              graph (sp/resolve-execution-graph storage (:id fn-rec))]
          ;; Should only have 1 fn (no references resolved)
          (is (= 1 (count (:fns graph))))
          (is (contains? (:fns graph) (:id fn-rec)))
          ;; Check resolved args have literal values
          (let [args (get (:resolved-args graph) (:id fn-rec))]
            (is (= "hello world" (:value (get args (:id arg-text)))))
            (is (= 42 (:value (get args (:id arg-int)))))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-with-invalid-uuid-string-test
  (testing "handles invalid UUID strings gracefully (not treated as fn refs)"
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [fn-schema (sp/create-entity storage :fn-schema
                                          {:name "echo" :returned-type "text"})
              arg-val (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id fn-schema)
                                         :name "value" :type "text" :required true
                                         :first-class false})
              fn-rec (sp/create-entity storage :fn
                                       {:name "my-echo"
                                        :fn-schema-id (:id fn-schema)})
              ;; Arg value with string that looks like UUID but isn't valid
              _ (setup/create-arg-value-with-binding! storage (:id fn-rec) (:id arg-val) "not-a-valid-uuid-at-all")
              graph (sp/resolve-execution-graph storage (:id fn-rec))]
          ;; Should only have 1 fn (invalid UUID string not treated as ref)
          (is (= 1 (count (:fns graph))))
          (let [args (get (:resolved-args graph) (:id fn-rec))]
            (is (= "not-a-valid-uuid-at-all" (:value (get args (:id arg-val)))))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-shared-reference-test
  (testing "handles shared fn reference (same fn referenced by multiple args)"
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [;; const-int schema
              const-schema (sp/create-entity storage :fn-schema
                                             {:name "const-shared" :returned-type "int"})
              const-arg (sp/create-entity storage :arg-schema
                                          {:fn-schema-id (:id const-schema)
                                           :name "value" :type "int" :required true
                                           :first-class false})
              ;; add schema - both args reference fns
              add-schema (sp/create-entity storage :fn-schema
                                           {:name "add-shared" :returned-type "int"})
              add-arg-a (sp/create-entity storage :arg-schema
                                          {:fn-schema-id (:id add-schema)
                                           :name "a" :type "int" :required true
                                           :first-class false})
              add-arg-b (sp/create-entity storage :arg-schema
                                          {:fn-schema-id (:id add-schema)
                                           :name "b" :type "int" :required true
                                           :first-class false})
              ;; const-5 fn - will be referenced TWICE
              const-5 (sp/create-entity storage :fn
                                        {:name "const-5-shared"
                                         :fn-schema-id (:id const-schema)})
              _ (setup/create-arg-value-with-binding! storage (:id const-5) (:id const-arg) 5)
              ;; add-5-5 fn referencing const-5 for BOTH args via fn-usages
              ;; This creates a shared reference that triggers the "already visited" branch
              add-5-5 (sp/create-entity storage :fn
                                        {:name "add-5-5-shared"
                                         :fn-schema-id (:id add-schema)})
              ;; Create two separate fn-usages pointing to same fn
              cs-5-a (setup/create-fn-usage! storage (:id const-5) "cs-5-a")
              cs-5-b (setup/create-fn-usage! storage (:id const-5) "cs-5-b")
              _ (setup/create-arg-value-with-fn-usage-binding! storage (:id add-5-5) (:id add-arg-a) cs-5-a)
              _ (setup/create-arg-value-with-fn-usage-binding! storage (:id add-5-5) (:id add-arg-b) cs-5-b)
              graph (sp/resolve-execution-graph storage (:id add-5-5))]
          ;; const-5 should only appear once in the graph despite being referenced twice
          (is (= 2 (count (:fns graph))))
          (is (contains? (:fns graph) (:id add-5-5)))
          (is (contains? (:fns graph) (:id const-5)))
          ;; Both args should have fn-usage-id references
          (let [args (get (:resolved-args graph) (:id add-5-5))]
            (is (= cs-5-a (:fn-usage-id (get args (:id add-arg-a)))))
            (is (= cs-5-b (:fn-usage-id (get args (:id add-arg-b)))))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-self-reference-test
  (testing "handles fn with self-reference in arg-value (triggers 'already visited' branch)"
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [;; recursive fn-schema with two args
              rec-schema (sp/create-entity storage :fn-schema
                                           {:name "recursive" :returned-type "int"})
              ;; 'self' arg will reference the fn itself (for recursion)
              arg-self (sp/create-entity storage :arg-schema
                                         {:fn-schema-id (:id rec-schema)
                                          :name "self" :type "fn" :required true
                                          :first-class true}) ; :fn type, first-class=true for HOF
              arg-n (sp/create-entity storage :arg-schema
                                      {:fn-schema-id (:id rec-schema)
                                       :name "n" :type "int" :required true
                                       :first-class false})
              ;; Create fn instance that references itself
              rec-fn (sp/create-entity storage :fn
                                       {:name "factorial"
                                        :fn-schema-id (:id rec-schema)})
              ;; Self-reference: arg-value references fn via fn-usage (HOF pattern)
              self-usage-id (setup/create-fn-usage! storage (:id rec-fn) "self-ref")
              _ (setup/create-arg-value-with-fn-usage-binding! storage (:id rec-fn) (:id arg-self) self-usage-id) ; Self-reference!
              _ (setup/create-arg-value-with-binding! storage (:id rec-fn) (:id arg-n) 5)
              graph (sp/resolve-execution-graph storage (:id rec-fn))]
          ;; Should only have 1 fn (self-reference doesn't create duplicate)
          (is (= 1 (count (:fns graph))))
          (is (contains? (:fns graph) (:id rec-fn)))
          ;; Self arg should have fn-usage-id reference; n arg has literal value
          (let [args (get (:resolved-args graph) (:id rec-fn))]
            (is (= self-usage-id (:fn-usage-id (get args (:id arg-self)))))
            (is (= 5 (:value (get args (:id arg-n)))))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-uuid-literal-in-value-test
  (testing "UUID literals in :value field are not treated as fn references"
    ;; With the new FK-based schema, UUIDs in :value are literal values,
    ;; not references. Only fn-usage-id triggers graph traversal.
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [pool (:pool storage)
              ;; Create a fn-schema
              fn-schema (crud/create-entity pool :fn-schema
                                            {:name "test-fn"
                                             :returned-type "int"}
                                            nil)
              ;; Create an arg-schema for any type argument
              arg-any (crud/create-entity pool :arg-schema
                                          {:fn-schema-id (:id fn-schema)
                                           :name "any-arg"
                                           :type "any"
                                           :required false
                                           :first-class false}
                                          nil)
              ;; Create a fn
              fn-rec (crud/create-entity pool :fn
                                         {:name "fn-with-uuid-literal"
                                          :fn-schema-id (:id fn-schema)}
                                         nil)
              ;; Create an arg-value with a UUID in :value (literal, not reference)
              some-uuid #uuid "99999999-9999-9999-9999-999999999999"
              arg-value (crud/create-entity pool :arg-value
                                            {:arg-schema-id (:id arg-any)
                                             :value some-uuid}
                                            nil)
              _ (crud/create-entity pool :fn-arg
                                    {:fn-id (:id fn-rec)
                                     :arg-schema-id (:id arg-any)
                                     :arg-value-id (:id arg-value)}
                                    nil)
              graph (sp/resolve-execution-graph storage (:id fn-rec))]
          ;; Should only have 1 fn (UUID in :value is literal, not followed)
          (is (= 1 (count (:fns graph))))
          (is (contains? (:fns graph) (:id fn-rec)))
          ;; The arg-value should have the UUID as literal value
          (let [args (get (:resolved-args graph) (:id fn-rec))]
            (is (= (str some-uuid) (str (:value (get args (:id arg-any))))))))
        (finally
          (sp/close storage))))))


;; === Mock-based coverage tests ===

(deftest resolve-execution-graph-simple-refs-test
  (testing "handles fn references correctly in graph traversal via fn-usage-id"
    ;; This tests that fn references via fn-usage-id are resolved
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [pool (:pool storage)
              ;; Create fn-schema
              fn-schema (crud/create-entity pool :fn-schema
                                            {:name "test-fn"
                                             :returned-type "int"}
                                            nil)
              arg-ref (crud/create-entity pool :arg-schema
                                          {:fn-schema-id (:id fn-schema)
                                           :name "ref-arg"
                                           :type "fn"
                                           :required false
                                           :first-class true}  ; first-class=true for HOF
                                          nil)
              ;; Create main fn
              main-fn (crud/create-entity pool :fn
                                          {:name "main-fn"
                                           :fn-schema-id (:id fn-schema)}
                                          nil)
              ;; Create referenced fn
              ref-fn (crud/create-entity pool :fn
                                         {:name "ref-fn"
                                          :fn-schema-id (:id fn-schema)}
                                         nil)
              ;; Create fn-usage for ref-fn
              fn-usage (crud/create-entity pool :fn-usage
                                           {:fn-id (:id ref-fn)
                                            :name "ref-fn-usage"}
                                           nil)
              ;; Create arg-value with fn-usage-id pointing to fn-usage
              arg-value (crud/create-entity pool :arg-value
                                            {:arg-schema-id (:id arg-ref)
                                             :fn-usage-id (:id fn-usage)}
                                            nil)
              _ (crud/create-entity pool :fn-arg
                                    {:fn-id (:id main-fn)
                                     :arg-schema-id (:id arg-ref)
                                     :arg-value-id (:id arg-value)}
                                    nil)
              graph (sp/resolve-execution-graph storage (:id main-fn))]
          ;; Both fns should be in graph
          (is (= 2 (count (:fns graph))))
          (is (contains? (:fns graph) (:id main-fn)))
          (is (contains? (:fns graph) (:id ref-fn))))
        (finally
          (sp/close storage))))))

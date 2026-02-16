(ns graphden.graph-storage-age.execution-graph-test
  "Tests for AGE execution graph resolution.

   These tests verify that resolve-execution-graph correctly builds
   the ExecutionGraphResult for function execution."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.graph-storage-age.test-setup :as setup]
    [graphden.storage-protocol.interface :as sp]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


(deftest resolve-simple-fn-test
  (testing "resolve-execution-graph for simple fn without dependencies"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          ;; Create fn-schema (no base-fn-name, just a definition)
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "add-one"
                                       :returned-type "int"})
          ;; Create arg-schema
          arg-schema (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id fn-schema)
                                        :name "x"
                                        :type "int"
                                        :required true})
          ;; Create fn instance
          fn-entity (sp/create-entity storage :fn
                                      {:name "add-one-instance"
                                       :fn-schema-id (:id fn-schema)})
          ;; Create arg-value with binding
          _ (setup/create-arg-value-with-binding! storage (:id fn-entity) (:id arg-schema) 42)
          ;; Resolve execution graph
          result (sp/resolve-execution-graph storage (:id fn-entity))]
      (try
        ;; Check result structure
        (is (map? result))
        (is (contains? result :fns))
        (is (contains? result :fn-schemas))
        (is (contains? result :arg-schemas))
        (is (contains? result :resolved-args))
        ;; Check fns
        (is (= 1 (count (:fns result))))
        (is (contains? (:fns result) (:id fn-entity)))
        ;; Check fn-schemas
        (is (= 1 (count (:fn-schemas result))))
        (is (contains? (:fn-schemas result) (:id fn-schema)))
        ;; Check arg-schemas
        (is (>= (count (:arg-schemas result)) 1))
        ;; Check resolved-args has the fn with its args
        (is (contains? (:resolved-args result) (:id fn-entity)))
        (finally
          (sp/close storage))))))


(deftest resolve-fn-with-fn-reference-test
  (testing "resolve-execution-graph follows fn references (UUID in arg-value)"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          ;; Create inner fn-schema and fn
          inner-schema (sp/create-entity storage :fn-schema
                                         {:name "inner"
                                          :returned-type "int"})
          inner-fn (sp/create-entity storage :fn
                                     {:name "inner-fn"
                                      :fn-schema-id (:id inner-schema)})
          ;; Create outer fn-schema with arg that refs a fn
          outer-schema (sp/create-entity storage :fn-schema
                                         {:name "outer"
                                          :returned-type "int"})
          ref-arg-schema (sp/create-entity storage :arg-schema
                                           {:fn-schema-id (:id outer-schema)
                                            :name "inner-ref"
                                            :type "ref"
                                            :required true})
          ;; Create outer fn
          outer-fn (sp/create-entity storage :fn
                                     {:name "outer-fn"
                                      :fn-schema-id (:id outer-schema)})
          ;; Bind arg to reference inner-fn (just pass the UUID directly)
          _ (setup/create-arg-value-with-binding!
              storage (:id outer-fn) (:id ref-arg-schema)
              (:id inner-fn))
          ;; Resolve execution graph
          result (sp/resolve-execution-graph storage (:id outer-fn))]
      (try
        ;; Should have both fns
        (is (= 2 (count (:fns result))))
        (is (contains? (:fns result) (:id outer-fn)))
        (is (contains? (:fns result) (:id inner-fn)))
        ;; Should have both schemas
        (is (= 2 (count (:fn-schemas result))))
        (finally
          (sp/close storage))))))


(deftest resolve-fn-with-call-site-reference-test
  (testing "resolve-execution-graph follows call-site references"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          ;; Create target fn
          target-schema (sp/create-entity storage :fn-schema
                                          {:name "target"
                                           :returned-type "int"})
          target-fn (sp/create-entity storage :fn
                                      {:name "target-fn"
                                       :fn-schema-id (:id target-schema)})
          ;; Create call-site pointing to target
          call-site (sp/create-entity storage :call-site
                                      {:fn-id (:id target-fn)})
          ;; Create caller fn
          caller-schema (sp/create-entity storage :fn-schema
                                          {:name "caller"
                                           :returned-type "int"})
          cs-arg-schema (sp/create-entity storage :arg-schema
                                          {:fn-schema-id (:id caller-schema)
                                           :name "cs-ref"
                                           :type "ref"
                                           :required true})
          caller-fn (sp/create-entity storage :fn
                                      {:name "caller-fn"
                                       :fn-schema-id (:id caller-schema)})
          ;; Bind arg to reference call-site (pass UUID directly)
          _ (setup/create-arg-value-with-binding!
              storage (:id caller-fn) (:id cs-arg-schema)
              (:id call-site))
          ;; Resolve execution graph
          result (sp/resolve-execution-graph storage (:id caller-fn))]
      (try
        ;; Should have both fns
        (is (= 2 (count (:fns result))))
        (is (contains? (:fns result) (:id caller-fn)))
        (is (contains? (:fns result) (:id target-fn)))
        ;; Should have call-sites
        (is (contains? (:call-sites result) (:id call-site)))
        (finally
          (sp/close storage))))))


(deftest resolve-fn-not-found-test
  (testing "resolve-execution-graph throws for non-existent fn"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Function not found"
              (sp/resolve-execution-graph storage #uuid "99999999-9999-9999-9999-999999999999")))
        (finally
          (sp/close storage))))))

(ns graphden.storage.age.execution-graph-test
  "Tests for AGE execution graph resolution.

   These tests verify that resolve-execution-graph correctly builds
   the ExecutionGraphResult for function execution."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.malli.interface :as mds]
    [graphden.schema.protocol.interface :as ds]
    [graphden.storage.age.test-setup :as setup]
    [graphden.storage.protocol.interface :as sp]))


(defn- make-simple-graph-schema
  "Creates simplified graph schema for execution graph tests.
   Uses text fields instead of enums for simplicity."
  []
  (-> (mds/create-builder)
      (ds/add-entity :fn-schema #uuid "00000000-0000-0000-0001-000000000001"
                     {:name {:uuid #uuid "00000000-0000-0000-0001-000000000002"
                             :type :text}
                      :returned-type {:uuid #uuid "00000000-0000-0000-0001-000000000003"
                                      :type :text}})
      (ds/add-entity :arg-schema #uuid "00000000-0000-0000-0002-000000000001"
                     {:fn-schema-id {:uuid #uuid "00000000-0000-0000-0002-000000000002"
                                     :type :ref :ref-entity :fn-schema}
                      :name {:uuid #uuid "00000000-0000-0000-0002-000000000003"
                             :type :text}
                      :type {:uuid #uuid "00000000-0000-0000-0002-000000000004"
                             :type :text}
                      :required {:uuid #uuid "00000000-0000-0000-0002-000000000005"
                                 :type :bool}
                      :first-class {:uuid #uuid "00000000-0000-0000-0002-000000000006"
                                    :type :bool}})
      (ds/add-entity :fn #uuid "00000000-0000-0000-0003-000000000001"
                     {:name {:uuid #uuid "00000000-0000-0000-0003-000000000002"
                             :type :text}
                      :fn-schema-id {:uuid #uuid "00000000-0000-0000-0003-000000000003"
                                     :type :ref :ref-entity :fn-schema}
                      :owner-fn-id {:uuid #uuid "00000000-0000-0000-0003-000000000004"
                                    :type :ref :ref-entity :fn
                                    :nullable? true}})
      (ds/add-entity :fn-usage #uuid "00000000-0000-0000-0005-000000000001"
                     {:fn-id {:uuid #uuid "00000000-0000-0000-0005-000000000002"
                              :type :ref :ref-entity :fn}
                      :name {:uuid #uuid "00000000-0000-0000-0005-000000000003"
                             :type :text}
                      :owner-fn-id {:uuid #uuid "00000000-0000-0000-0005-000000000004"
                                    :type :ref :ref-entity :fn
                                    :nullable? true}})
      (ds/add-entity :arg-value #uuid "00000000-0000-0000-0004-000000000001"
                     {:arg-schema-id {:uuid #uuid "00000000-0000-0000-0004-000000000003"
                                      :type :ref :ref-entity :arg-schema}
                      :value {:uuid #uuid "00000000-0000-0000-0004-000000000004"
                              :type :jsonb}})
      (ds/add-entity :fn-arg #uuid "00000000-0000-0000-0006-000000000001"
                     {:fn-id {:uuid #uuid "00000000-0000-0000-0006-000000000002"
                              :type :ref :ref-entity :fn}
                      :arg-schema-id {:uuid #uuid "00000000-0000-0000-0006-000000000003"
                                      :type :ref :ref-entity :arg-schema}
                      :arg-value-id {:uuid #uuid "00000000-0000-0000-0006-000000000004"
                                     :type :ref :ref-entity :arg-value}})
      ds/build))


(defn- create-arg-value-with-binding!
  "Creates arg-value and fn-arg binding. Returns the arg-value."
  [storage fn-id arg-schema-id value]
  (let [av (sp/create-entity storage :arg-value
                             {:arg-schema-id arg-schema-id
                              :value value})]
    (sp/create-entity storage :fn-arg
                      {:fn-id fn-id
                       :arg-schema-id arg-schema-id
                       :arg-value-id (:id av)})
    av))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


(deftest resolve-simple-fn-test
  (testing "resolve-execution-graph for simple fn without dependencies"
    (let [storage (setup/create-raw-storage)
          _ (sp/initialize storage (make-simple-graph-schema))
          ;; Create fn-schema (no base-fn-name, just a definition)
          fn-schema (sp/create-entity storage :fn-schema
                                      {:name "add-one"
                                       :returned-type "int"})
          ;; Create arg-schema
          arg-schema (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id fn-schema)
                                        :name "x"
                                        :type "int"
                                        :required true
                                        :first-class false})
          ;; Create fn instance
          fn-entity (sp/create-entity storage :fn
                                      {:name "add-one-instance"
                                       :fn-schema-id (:id fn-schema)})
          ;; Create arg-value with binding
          _ (create-arg-value-with-binding! storage (:id fn-entity) (:id arg-schema) 42)
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
    (let [storage (setup/create-raw-storage)
          _ (sp/initialize storage (make-simple-graph-schema))
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
                                            :required true
                                            :first-class false})
          ;; Create outer fn
          outer-fn (sp/create-entity storage :fn
                                     {:name "outer-fn"
                                      :fn-schema-id (:id outer-schema)})
          ;; Bind arg to reference inner-fn (just pass the UUID directly)
          _ (create-arg-value-with-binding!
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


(deftest resolve-fn-with-fn-usage-reference-test
  (testing "resolve-execution-graph follows fn-usage references"
    (let [storage (setup/create-raw-storage)
          _ (sp/initialize storage (make-simple-graph-schema))
          ;; Create target fn
          target-schema (sp/create-entity storage :fn-schema
                                          {:name "target"
                                           :returned-type "int"})
          target-fn (sp/create-entity storage :fn
                                      {:name "target-fn"
                                       :fn-schema-id (:id target-schema)})
          ;; Create fn-usage pointing to target
          fn-usage (sp/create-entity storage :fn-usage
                                     {:fn-id (:id target-fn)
                                      :name "target-usage"})
          ;; Create caller fn
          caller-schema (sp/create-entity storage :fn-schema
                                          {:name "caller"
                                           :returned-type "int"})
          cs-arg-schema (sp/create-entity storage :arg-schema
                                          {:fn-schema-id (:id caller-schema)
                                           :name "cs-ref"
                                           :type "ref"
                                           :required true
                                           :first-class false})
          caller-fn (sp/create-entity storage :fn
                                      {:name "caller-fn"
                                       :fn-schema-id (:id caller-schema)})
          ;; Bind arg to reference fn-usage (pass UUID directly)
          _ (create-arg-value-with-binding!
              storage (:id caller-fn) (:id cs-arg-schema)
              (:id fn-usage))
          ;; Resolve execution graph
          result (sp/resolve-execution-graph storage (:id caller-fn))]
      (try
        ;; Should have both fns
        (is (= 2 (count (:fns result))))
        (is (contains? (:fns result) (:id caller-fn)))
        (is (contains? (:fns result) (:id target-fn)))
        ;; Should have fn-usages
        (is (contains? (:fn-usages result) (:id fn-usage)))
        (finally
          (sp/close storage))))))


(deftest resolve-fn-not-found-test
  (testing "resolve-execution-graph throws for non-existent fn"
    (let [storage (setup/create-raw-storage)
          _ (sp/initialize storage (make-simple-graph-schema))]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Function not found"
              (sp/resolve-execution-graph storage #uuid "99999999-9999-9999-9999-999999999999")))
        (finally
          (sp/close storage))))))

(ns graphden.cache-postgres.cache-values-test
  "Integration tests for handling various value types in cache."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.cache-postgres.interface :as cache-pg]
    [graphden.cache-postgres.test-setup :as setup]
    [graphden.cache-protocol.interface :as cache]
    [graphden.storage-protocol.interface :as sp]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


(deftest fn-ref-value-integration-test
  (testing "handles fn-ref values in resolved-args"
    (let [storage (setup/create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (setup/get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :any
                                           :base-fn-name "base"})
              fn-schema-id (:id fn-schema)
              arg-schema (sp/create-entity storage :arg-schema
                                           {:fn-schema-id fn-schema-id
                                            :name "f"
                                            :type :fn
                                            :required true})
              arg-schema-id (:id arg-schema)
              fn-record (sp/create-entity storage :fn
                                          {:name "test-fn"
                                           :fn-schema-id fn-schema-id})
              fn-id (:id fn-record)
              ref-fn (sp/create-entity storage :fn
                                       {:name "ref-fn"
                                        :fn-schema-id fn-schema-id})
              ref-fn-id (:id ref-fn)
              graph {:fns {fn-id {:id fn-id
                                  :name "test-fn"
                                  :fn-schema-id fn-schema-id}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "schema"
                                                :base-fn-name "base"
                                                :returned-type :any}}
                     :arg-schemas {arg-schema-id {:id arg-schema-id
                                                  :fn-schema-id fn-schema-id
                                                  :name "f"
                                                  :type :fn
                                                  :required true}}
                     :resolved-args {fn-id {arg-schema-id {:kind :fn-ref :fn-id ref-fn-id}}}
                     :fn-result-values {}}
              deps {:fn-ids {} :fn-schema-ids {} :arg-schema-ids {}}]
          (cache/save-cache! cache fn-id graph deps)
          (let [cached (cache/get-cached-graph cache fn-id)
                resolved-value (get-in cached [:resolved-args fn-id arg-schema-id])]
            (is (map? resolved-value))
            (is (= :fn-ref (:kind resolved-value)))
            (is (= (str ref-fn-id) (str (:fn-id resolved-value))))))
        (finally
          (sp/close storage))))))


(deftest empty-resolved-args-integration-test
  (testing "handles empty resolved-args"
    (let [storage (setup/create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (setup/get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :text
                                           :base-fn-name "base"})
              fn-schema-id (:id fn-schema)
              arg-schema (sp/create-entity storage :arg-schema
                                           {:fn-schema-id fn-schema-id
                                            :name "opt"
                                            :type :text
                                            :required false})
              arg-schema-id (:id arg-schema)
              fn-record (sp/create-entity storage :fn
                                          {:name "test-fn"
                                           :fn-schema-id fn-schema-id})
              fn-id (:id fn-record)
              graph {:fns {fn-id {:id fn-id
                                  :name "test-fn"
                                  :fn-schema-id fn-schema-id}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "schema"
                                                :base-fn-name "base"
                                                :returned-type :text}}
                     :arg-schemas {arg-schema-id {:id arg-schema-id
                                                  :fn-schema-id fn-schema-id
                                                  :name "opt"
                                                  :type :text
                                                  :required false}}
                     ;; No resolved-args for this fn - simulating optional arg not set
                     :resolved-args {}
                     :fn-result-values {}}
              deps {:fn-ids {} :fn-schema-ids {} :arg-schema-ids {}}]
          (cache/save-cache! cache fn-id graph deps)
          (let [cached (cache/get-cached-graph cache fn-id)]
            ;; Empty resolved-args should be preserved
            (is (empty? (:resolved-args cached)))))
        (finally
          (sp/close storage))))))


(deftest complex-value-types-integration-test
  (testing "handles various value types in resolved-args"
    (let [storage (setup/create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (setup/get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :jsonb
                                           :base-fn-name "base"})
              fn-schema-id (:id fn-schema)
              arg-schema (sp/create-entity storage :arg-schema
                                           {:fn-schema-id fn-schema-id
                                            :name "data"
                                            :type :jsonb
                                            :required true})
              arg-schema-id (:id arg-schema)
              fn-record (sp/create-entity storage :fn
                                          {:name "test-fn"
                                           :fn-schema-id fn-schema-id})
              fn-id (:id fn-record)
              complex-value {:nested {:array [1 2 3]
                                      :string "hello"
                                      :number 42.5
                                      :boolean true}}
              graph {:fns {fn-id {:id fn-id
                                  :name "test-fn"
                                  :fn-schema-id fn-schema-id}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "schema"
                                                :base-fn-name "base"
                                                :returned-type :jsonb}}
                     :arg-schemas {arg-schema-id {:id arg-schema-id
                                                  :fn-schema-id fn-schema-id
                                                  :name "data"
                                                  :type :jsonb
                                                  :required true}}
                     :resolved-args {fn-id {arg-schema-id complex-value}}
                     :fn-result-values {}}
              deps {:fn-ids {} :fn-schema-ids {} :arg-schema-ids {}}]
          (cache/save-cache! cache fn-id graph deps)
          (let [cached (cache/get-cached-graph cache fn-id)
                resolved-value (get-in cached [:resolved-args fn-id arg-schema-id])]
            (is (= [1 2 3] (get-in resolved-value [:nested :array])))
            (is (= "hello" (get-in resolved-value [:nested :string])))
            (is (= 42.5 (get-in resolved-value [:nested :number])))
            (is (true? (get-in resolved-value [:nested :boolean])))))
        (finally
          (sp/close storage))))))


(deftest primitive-values-integration-test
  (testing "handles primitive values in resolved-args"
    (let [storage (setup/create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (setup/get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :any
                                           :base-fn-name "base"})
              fn-schema-id (:id fn-schema)
              arg-schema-int (sp/create-entity storage :arg-schema
                                               {:fn-schema-id fn-schema-id
                                                :name "num"
                                                :type :int
                                                :required true})
              arg-schema-str (sp/create-entity storage :arg-schema
                                               {:fn-schema-id fn-schema-id
                                                :name "str"
                                                :type :text
                                                :required true})
              arg-schema-bool (sp/create-entity storage :arg-schema
                                                {:fn-schema-id fn-schema-id
                                                 :name "flag"
                                                 :type :bool
                                                 :required true})
              fn-record (sp/create-entity storage :fn
                                          {:name "test-fn"
                                           :fn-schema-id fn-schema-id})
              fn-id (:id fn-record)
              graph {:fns {fn-id {:id fn-id
                                  :name "test-fn"
                                  :fn-schema-id fn-schema-id}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "schema"
                                                :base-fn-name "base"
                                                :returned-type :any}}
                     :arg-schemas {(:id arg-schema-int) arg-schema-int
                                   (:id arg-schema-str) arg-schema-str
                                   (:id arg-schema-bool) arg-schema-bool}
                     :resolved-args {fn-id {(:id arg-schema-int) 42
                                            (:id arg-schema-str) "hello"
                                            (:id arg-schema-bool) false}}
                     :fn-result-values {}}
              deps {:fn-ids {} :fn-schema-ids {} :arg-schema-ids {}}]
          (cache/save-cache! cache fn-id graph deps)
          (let [cached (cache/get-cached-graph cache fn-id)]
            (is (= 42 (get-in cached [:resolved-args fn-id (:id arg-schema-int)])))
            (is (= "hello" (get-in cached [:resolved-args fn-id (:id arg-schema-str)])))
            (is (false? (get-in cached [:resolved-args fn-id (:id arg-schema-bool)])))))
        (finally
          (sp/close storage))))))

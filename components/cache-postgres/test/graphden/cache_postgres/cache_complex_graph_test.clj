(ns graphden.cache-postgres.cache-complex-graph-test
  "Integration tests for complex graph structures in cache."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.cache-postgres.interface :as cache-pg]
    [graphden.cache-postgres.test-setup :as setup]
    [graphden.cache-protocol.interface :as cache]
    [graphden.storage-protocol.interface :as sp]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


(deftest multiple-fns-in-graph-integration-test
  (testing "handles graphs with multiple fns"
    (let [storage (setup/create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (setup/get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :int
                                           :base-fn-name "base"})
              fn-schema-id (:id fn-schema)
              parent-fn (sp/create-entity storage :fn
                                          {:name "parent"
                                           :fn-schema-id fn-schema-id})
              parent-fn-id (:id parent-fn)
              child-fn (sp/create-entity storage :fn
                                         {:name "child"
                                          :fn-schema-id fn-schema-id
                                          :parent-fn-id parent-fn-id})
              child-fn-id (:id child-fn)
              grandchild-fn (sp/create-entity storage :fn
                                              {:name "grandchild"
                                               :fn-schema-id fn-schema-id
                                               :parent-fn-id child-fn-id})
              grandchild-fn-id (:id grandchild-fn)
              graph {:fns {parent-fn-id {:id parent-fn-id
                                         :name "parent"
                                         :fn-schema-id fn-schema-id
                                         :parent-fn-id nil}
                           child-fn-id {:id child-fn-id
                                        :name "child"
                                        :fn-schema-id fn-schema-id
                                        :parent-fn-id parent-fn-id}
                           grandchild-fn-id {:id grandchild-fn-id
                                             :name "grandchild"
                                             :fn-schema-id fn-schema-id
                                             :parent-fn-id child-fn-id}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "schema"
                                                :base-fn-name "base"
                                                :returned-type :int}}
                     :arg-schemas {}
                     :resolved-args {}
                     :fn-result-values {}}
              deps {:fn-ids {parent-fn-id 1 child-fn-id 1 grandchild-fn-id 1}
                    :fn-schema-ids {fn-schema-id 1}
                    :arg-schema-ids {}}]
          (cache/save-cache! cache grandchild-fn-id graph deps)
          (let [cached (cache/get-cached-graph cache grandchild-fn-id)]
            (is (= 3 (count (:fns cached))))
            (is (some? (get-in cached [:fns parent-fn-id])))
            (is (some? (get-in cached [:fns child-fn-id])))
            (is (some? (get-in cached [:fns grandchild-fn-id])))))
        (finally
          (sp/close storage))))))


(deftest multiple-arg-schemas-integration-test
  (testing "handles multiple arg-schemas"
    (let [storage (setup/create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (setup/get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :int
                                           :base-fn-name "add"})
              fn-schema-id (:id fn-schema)
              arg1 (sp/create-entity storage :arg-schema
                                     {:fn-schema-id fn-schema-id
                                      :name "a"
                                      :type :int
                                      :required true})
              arg2 (sp/create-entity storage :arg-schema
                                     {:fn-schema-id fn-schema-id
                                      :name "b"
                                      :type :int
                                      :required true})
              arg3 (sp/create-entity storage :arg-schema
                                     {:fn-schema-id fn-schema-id
                                      :name "c"
                                      :type :int
                                      :required false})
              fn-record (sp/create-entity storage :fn
                                          {:name "test"
                                           :fn-schema-id fn-schema-id})
              fn-id (:id fn-record)
              graph {:fns {fn-id {:id fn-id
                                  :name "test"
                                  :fn-schema-id fn-schema-id
                                  :parent-fn-id nil}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "schema"
                                                :base-fn-name "add"
                                                :returned-type :int}}
                     :arg-schemas {(:id arg1) (assoc arg1 :id (:id arg1))
                                   (:id arg2) (assoc arg2 :id (:id arg2))
                                   (:id arg3) (assoc arg3 :id (:id arg3))}
                     :resolved-args {}
                     :fn-result-values {}}
              deps {:fn-ids {}
                    :fn-schema-ids {fn-schema-id 1}
                    :arg-schema-ids {(:id arg1) 1 (:id arg2) 1 (:id arg3) 1}}]
          (cache/save-cache! cache fn-id graph deps)
          (let [cached (cache/get-cached-graph cache fn-id)]
            (is (= 3 (count (:arg-schemas cached))))
            (is (= #{"a" "b" "c"} (set (map :name (vals (:arg-schemas cached))))))))
        (finally
          (sp/close storage))))))

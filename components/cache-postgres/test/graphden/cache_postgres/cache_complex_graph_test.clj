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
                                  :fn-schema-id fn-schema-id}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "schema"
                                                :base-fn-name "add"
                                                :returned-type :int}}
                     :arg-schemas {(:id arg1) (assoc arg1 :id (:id arg1))
                                   (:id arg2) (assoc arg2 :id (:id arg2))
                                   (:id arg3) (assoc arg3 :id (:id arg3))}
                     :resolved-args {}
                     :call-sites {}}
              deps {:fn-ids {}
                    :fn-schema-ids {fn-schema-id 1}
                    :arg-schema-ids {(:id arg1) 1 (:id arg2) 1 (:id arg3) 1}}]
          (cache/save-cache! cache fn-id graph deps)
          (let [cached (cache/get-cached-graph cache fn-id)]
            (is (= 3 (count (:arg-schemas cached))))
            (is (= #{"a" "b" "c"} (set (map :name (vals (:arg-schemas cached))))))))
        (finally
          (sp/close storage))))))

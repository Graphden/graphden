(ns graphden.datomic-storage.constraints-test
  "Tests for datomic-storage GraphConstraints protocol."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.datomic-storage.test-setup :as setup]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.storage-protocol.interface :as sp]))


;; === GraphConstraints tests ===

(defn- make-graph-schema
  "Creates schema with fn-schema, arg-schema, fn, and arg-value entities."
  []
  (-> (mds/create-builder)
      (ds/add-entity :fn-schema #uuid "00000000-0000-0000-0001-000000000001"
                     {:name {:uuid #uuid "00000000-0000-0000-0001-000000000002"
                             :type :text}
                      :returned-type {:uuid #uuid "00000000-0000-0000-0001-000000000003"
                                      :type :text}})
      (ds/add-entity :arg-schema #uuid "00000000-0000-0000-0002-000000000001"
                     {:fn-schema-id {:uuid #uuid "00000000-0000-0000-0002-000000000002"
                                     :type :uuid}
                      :name {:uuid #uuid "00000000-0000-0000-0002-000000000003"
                             :type :text}
                      :type {:uuid #uuid "00000000-0000-0000-0002-000000000004"
                             :type :text}
                      :required {:uuid #uuid "00000000-0000-0000-0002-000000000005"
                                 :type :bool}})
      (ds/add-entity :fn #uuid "00000000-0000-0000-0003-000000000001"
                     {:name {:uuid #uuid "00000000-0000-0000-0003-000000000002"
                             :type :text}
                      :fn-schema-id {:uuid #uuid "00000000-0000-0000-0003-000000000003"
                                     :type :uuid}
                      :parent-fn-id {:uuid #uuid "00000000-0000-0000-0003-000000000004"
                                     :type :uuid
                                     :nullable? true}})
      (ds/add-entity :arg-value #uuid "00000000-0000-0000-0004-000000000001"
                     {:owner-fn-id {:uuid #uuid "00000000-0000-0000-0004-000000000002"
                                    :type :uuid}
                      :arg-schema-id {:uuid #uuid "00000000-0000-0000-0004-000000000003"
                                      :type :uuid}
                      :value {:uuid #uuid "00000000-0000-0000-0004-000000000004"
                              :type :text}})
      ds/build))


(deftest validate-parent-same-schema-test
  (testing "allows nil parent"
    (let [storage (setup/create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn {:id fn-id :name "my-sum" :fn-schema-id fn-schema-id})]
      (try
        (is (nil? (sp/validate-parent-same-schema! storage fn-id nil)))
        (finally
          (sp/close storage)))))

  (testing "allows parent with same schema"
    (let [storage (setup/create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          parent-fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          child-fn-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn {:id parent-fn-id :name "base-sum" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id child-fn-id :name "my-sum" :fn-schema-id fn-schema-id
                                           :parent-fn-id parent-fn-id})]
      (try
        (is (nil? (sp/validate-parent-same-schema! storage child-fn-id parent-fn-id)))
        (finally
          (sp/close storage)))))

  (testing "throws on different schema"
    (let [storage (setup/create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          schema1-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          schema2-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-bbbbbbbbbbbb"
          parent-fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          child-fn-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id schema1-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn-schema {:id schema2-id :name "sub" :returned-type "int"})
          _ (sp/create-entity storage :fn {:id parent-fn-id :name "base-sum" :fn-schema-id schema1-id})
          _ (sp/create-entity storage :fn {:id child-fn-id :name "my-sub" :fn-schema-id schema2-id})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Parent fn has different fn-schema-id"
              (sp/validate-parent-same-schema! storage child-fn-id parent-fn-id)))
        (finally
          (sp/close storage))))))


(deftest validate-no-inheritance-cycle-test
  (testing "allows nil parent"
    (let [storage (setup/create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"]
      (try
        (is (nil? (sp/validate-no-inheritance-cycle! storage fn-id nil)))
        (finally
          (sp/close storage)))))

  (testing "throws on self-reference"
    (let [storage (setup/create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot set self as parent"
              (sp/validate-no-inheritance-cycle! storage fn-id fn-id)))
        (finally
          (sp/close storage)))))

  (testing "throws on cycle A→B→A"
    (let [storage (setup/create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          fn-a #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          fn-b #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn {:id fn-a :name "fn-a" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id fn-b :name "fn-b" :fn-schema-id fn-schema-id
                                           :parent-fn-id fn-a})]
      (try
        ;; Try to make A's parent B (would create A→B→A cycle)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Setting parent would create inheritance cycle"
              (sp/validate-no-inheritance-cycle! storage fn-a fn-b)))
        (finally
          (sp/close storage))))))


(deftest validate-arg-schema-belongs-to-fn-test
  (testing "allows matching schema"
    (let [storage (setup/create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          arg-schema-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id fn-schema-id
                                                   :name "x" :type "int" :required true})
          _ (sp/create-entity storage :fn {:id fn-id :name "my-sum" :fn-schema-id fn-schema-id})]
      (try
        (is (nil? (sp/validate-arg-schema-belongs-to-fn! storage fn-id arg-schema-id)))
        (finally
          (sp/close storage)))))

  (testing "throws on mismatched schema"
    (let [storage (setup/create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          schema1-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          schema2-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-bbbbbbbbbbbb"
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          arg-schema-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id schema1-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn-schema {:id schema2-id :name "sub" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id schema2-id
                                                   :name "x" :type "int" :required true})
          _ (sp/create-entity storage :fn {:id fn-id :name "my-sum" :fn-schema-id schema1-id})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Arg-schema does not belong to fn's schema"
              (sp/validate-arg-schema-belongs-to-fn! storage fn-id arg-schema-id)))
        (finally
          (sp/close storage))))))


(deftest validate-no-arg-override-test
  (testing "allows arg when not in parent chain"
    (let [storage (setup/create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          arg-schema-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id fn-schema-id
                                                   :name "x" :type "int" :required true})
          _ (sp/create-entity storage :fn {:id fn-id :name "my-sum" :fn-schema-id fn-schema-id})]
      (try
        (is (nil? (sp/validate-no-arg-override! storage fn-id arg-schema-id)))
        (finally
          (sp/close storage)))))

  (testing "throws when arg already defined in parent"
    (let [storage (setup/create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          arg-schema-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          parent-fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          child-fn-id #uuid "dddddddd-dddd-dddd-dddd-dddddddddddd"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id fn-schema-id
                                                   :name "x" :type "int" :required true})
          _ (sp/create-entity storage :fn {:id parent-fn-id :name "base-sum" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id child-fn-id :name "my-sum" :fn-schema-id fn-schema-id
                                           :parent-fn-id parent-fn-id})
          _ (sp/create-entity storage :arg-value {:id #uuid "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
                                                  :owner-fn-id parent-fn-id
                                                  :arg-schema-id arg-schema-id
                                                  :value "42"})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Argument already defined in parent chain"
              (sp/validate-no-arg-override! storage child-fn-id arg-schema-id)))
        (finally
          (sp/close storage))))))


(deftest validate-no-dependency-cycle-test
  (testing "allows non-cyclic reference"
    (let [storage (setup/create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          owner-fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          value-fn-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn {:id owner-fn-id :name "owner" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id value-fn-id :name "value" :fn-schema-id fn-schema-id})]
      (try
        (is (nil? (sp/validate-no-dependency-cycle! storage owner-fn-id value-fn-id)))
        (finally
          (sp/close storage)))))

  (testing "allows nil value-fn-id"
    (let [storage (setup/create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          owner-fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"]
      (try
        (is (nil? (sp/validate-no-dependency-cycle! storage owner-fn-id nil)))
        (finally
          (sp/close storage)))))

  (testing "throws when dependency cycle detected"
    (let [storage (setup/create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          fn-a-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          fn-b-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          fn-c-id #uuid "dddddddd-dddd-dddd-dddd-dddddddddddd"
          arg-schema-id #uuid "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "test" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id fn-schema-id
                                                   :name "x" :type "int" :required true})
          _ (sp/create-entity storage :fn {:id fn-a-id :name "fn-a" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id fn-b-id :name "fn-b" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id fn-c-id :name "fn-c" :fn-schema-id fn-schema-id})
          ;; Create b -> c reference (b depends on c)
          _ (sp/create-entity storage :arg-value {:owner-fn-id fn-b-id
                                                  :arg-schema-id arg-schema-id
                                                  :value (str fn-c-id)})
          ;; Create c -> a reference (c depends on a)
          _ (sp/create-entity storage :arg-value {:id #uuid "ffffffff-ffff-ffff-ffff-ffffffffffff"
                                                  :owner-fn-id fn-c-id
                                                  :arg-schema-id arg-schema-id
                                                  :value (str fn-a-id)})]
      (try
        ;; Try to validate a -> b, which would create cycle: a -> b -> c -> a
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dependency cycle"
              (sp/validate-no-dependency-cycle! storage fn-a-id fn-b-id)))
        (finally
          (sp/close storage))))))

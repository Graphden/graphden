(ns graphden.schema.malli.field-type-test
  "Field type validation tests for malli-data-schema."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.malli.test-helpers :refer [uuid]]
    [graphden.schema.protocol.interface :as ds]))


(deftest jsonb-validation-test
  (let [schema (-> (mds/create-builder)
                   (ds/add-entity :doc (uuid) {:data {:uuid (uuid) :type :jsonb}})
                   (ds/build))]

    (testing "jsonb accepts nil"
      (is (nil? (ds/validate-entity schema :doc {:id (random-uuid) :data nil}))))

    (testing "jsonb accepts primitives"
      (is (nil? (ds/validate-entity schema :doc {:id (random-uuid) :data true})))
      (is (nil? (ds/validate-entity schema :doc {:id (random-uuid) :data 42})))
      (is (nil? (ds/validate-entity schema :doc {:id (random-uuid) :data 3.14})))
      (is (nil? (ds/validate-entity schema :doc {:id (random-uuid) :data "text"}))))

    (testing "jsonb accepts arrays"
      (is (nil? (ds/validate-entity schema :doc {:id (random-uuid) :data [1 2 3]})))
      (is (nil? (ds/validate-entity schema :doc {:id (random-uuid) :data ["a" "b"]}))))

    (testing "jsonb accepts objects with string keys"
      (is (nil? (ds/validate-entity schema :doc {:id (random-uuid) :data {"key" "value"}})))
      (is (nil? (ds/validate-entity schema :doc {:id (random-uuid)
                                                 :data {"nested" {"deep" [1 2 3]}}}))))

    (testing "jsonb rejects keyword keys in maps"
      (let [result (ds/validate-entity schema :doc {:id (random-uuid) :data {:key "value"}})]
        (is (some? result))))

    (testing "jsonb rejects functions"
      (let [result (ds/validate-entity schema :doc {:id (random-uuid) :data inc})]
        (is (some? result))))))


(deftest bytes-field-test
  (let [schema (-> (mds/create-builder)
                   (ds/add-entity :binary (uuid) {:data {:uuid (uuid) :type :bytes}})
                   (ds/build))]

    (testing "bytes accepts byte arrays"
      (is (nil? (ds/validate-entity schema :binary
                                    {:id (random-uuid)
                                     :data (byte-array [1 2 3 4])}))))

    (testing "bytes rejects strings"
      (let [result (ds/validate-entity schema :binary
                                       {:id (random-uuid)
                                        :data "not bytes"})]
        (is (some? result))
        (is (contains? (:errors result) :data))))))


(deftest numeric-field-test
  (let [schema (-> (mds/create-builder)
                   (ds/add-entity :measurement (uuid)
                                  {:value {:uuid (uuid) :type :numeric}})
                   (ds/build))]

    (testing "numeric accepts integers"
      (is (nil? (ds/validate-entity schema :measurement
                                    {:id (random-uuid) :value 42}))))

    (testing "numeric accepts doubles"
      (is (nil? (ds/validate-entity schema :measurement
                                    {:id (random-uuid) :value 3.14}))))

    (testing "numeric rejects strings"
      (let [result (ds/validate-entity schema :measurement
                                       {:id (random-uuid) :value "not a number"})]
        (is (some? result))
        (is (contains? (:errors result) :value))))))


(deftest ref-type-extra-attributes-test
  (testing "ref field with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"ref has unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :user (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-entity :item (uuid)
                             {:owner {:uuid (uuid)
                                      :type :ref
                                      :ref-entity :user
                                      :extra-attr "value"}})
              ds/build)))))


(deftest enum-type-extra-attributes-test
  (testing "enum field with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"enum has unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :active}])
              (ds/add-entity :item (uuid)
                             {:status {:uuid (uuid)
                                       :type :enum
                                       :enum-name :status
                                       :extra-attr "value"}})
              ds/build)))))


(deftest base-type-extra-attributes-test
  (testing "base field type with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"text has unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid)
                             {:name {:uuid (uuid)
                                     :type :text
                                     :extra-attr "value"}})
              ds/build)))))


(deftest union-extra-attributes-test
  (testing "union field with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"union has unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid)
                             {:value {:uuid (uuid)
                                      :type :union
                                      :variants [{:type :text}]
                                      :extra-attr "value"}})
              ds/build)))))


(deftest all-field-types-validation-test
  (testing "all field types validate correctly"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :status (uuid) [{:uuid (uuid) :value :active}])
                     (ds/add-entity :target (uuid) {:name {:uuid (uuid) :type :text}})
                     (ds/add-entity :test-entity (uuid)
                                    {:uuid-field {:uuid (uuid) :type :uuid}
                                     :text-field {:uuid (uuid) :type :text}
                                     :int-field {:uuid (uuid) :type :int}
                                     :bool-field {:uuid (uuid) :type :bool}
                                     :numeric-field {:uuid (uuid) :type :numeric}
                                     :timestamp-field {:uuid (uuid) :type :timestamptz}
                                     :jsonb-field {:uuid (uuid) :type :jsonb}
                                     :bytes-field {:uuid (uuid) :type :bytes}
                                     :ref-field {:uuid (uuid) :type :ref :ref-entity :target}
                                     :enum-field {:uuid (uuid) :type :enum :enum-name :status}})
                     ds/build)]
      ;; Valid data
      (is (nil? (ds/validate-entity schema :test-entity
                                    {:id (uuid)
                                     :uuid-field (uuid)
                                     :text-field "hello"
                                     :int-field 42
                                     :bool-field true
                                     :numeric-field 3.14
                                     :timestamp-field (java.util.Date.)
                                     :jsonb-field {"key" "value"}
                                     :bytes-field (byte-array [1 2 3])
                                     :ref-field (uuid)
                                     :enum-field :active})))
      ;; Invalid uuid-field
      (is (some? (:errors (ds/validate-entity schema :test-entity
                                              {:id (uuid)
                                               :uuid-field "not-uuid"
                                               :text-field "hello"
                                               :int-field 42
                                               :bool-field true
                                               :numeric-field 3.14
                                               :timestamp-field (java.util.Date.)
                                               :jsonb-field nil
                                               :bytes-field (byte-array [])
                                               :ref-field (uuid)
                                               :enum-field :active})))))))

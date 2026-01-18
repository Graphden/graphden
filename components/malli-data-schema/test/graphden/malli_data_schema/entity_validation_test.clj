(ns graphden.malli-data-schema.entity-validation-test
  "Entity validation tests for malli-data-schema."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.malli-data-schema.test-helpers :refer [example-schema uuid]]))


(deftest validate-entity-test
  (let [valid-fn-schema-id (random-uuid)
        valid-fn-id (random-uuid)
        valid-arg-schema-id (random-uuid)]

    (testing "valid fn-schema entity"
      (is (nil? (ds/validate-entity example-schema :fn-schema
                                    {:id (random-uuid)
                                     :name "add"
                                     :returned-type :int}))))

    (testing "invalid fn-schema - missing required field"
      (let [result (ds/validate-entity example-schema :fn-schema
                                       {:id (random-uuid)
                                        :returned-type :int})]
        (is (some? result))
        (is (contains? (:errors result) :name))))

    (testing "invalid fn-schema - wrong enum value"
      (let [result (ds/validate-entity example-schema :fn-schema
                                       {:id (random-uuid)
                                        :name "add"
                                        :returned-type :unknown-type})]
        (is (some? result))
        (is (contains? (:errors result) :returned-type))))

    (testing "valid arg-schema with reference"
      (is (nil? (ds/validate-entity example-schema :arg-schema
                                    {:id (random-uuid)
                                     :fn-schema-id valid-fn-schema-id
                                     :name "x"
                                     :type :int}))))

    (testing "valid fn entity"
      (is (nil? (ds/validate-entity example-schema :fn
                                    {:id (random-uuid)
                                     :name "add-two"
                                     :fn-schema-id valid-fn-schema-id}))))

    (testing "valid arg-value with literal int value"
      (is (nil? (ds/validate-entity example-schema :arg-value
                                    {:id (random-uuid)
                                     :owner-fn-id valid-fn-id
                                     :arg-schema-id valid-arg-schema-id
                                     :value 42}))))

    (testing "valid arg-value with literal string value"
      (is (nil? (ds/validate-entity example-schema :arg-value
                                    {:id (random-uuid)
                                     :owner-fn-id valid-fn-id
                                     :arg-schema-id valid-arg-schema-id
                                     :value "hello"}))))

    (testing "valid arg-value with fn reference as value"
      (is (nil? (ds/validate-entity example-schema :arg-value
                                    {:id (random-uuid)
                                     :owner-fn-id valid-fn-id
                                     :arg-schema-id valid-arg-schema-id
                                     :value valid-fn-id}))))

    (testing "invalid arg-value - owner-fn-id is required"
      (let [result (ds/validate-entity example-schema :arg-value
                                       {:id (random-uuid)
                                        :owner-fn-id nil
                                        :arg-schema-id valid-arg-schema-id
                                        :value 42})]
        (is (some? result))
        (is (contains? (:errors result) :owner-fn-id))))

    (testing "unknown entity validation"
      (let [result (ds/validate-entity example-schema :unknown-entity {:id (random-uuid)})]
        (is (some? result))
        (is (contains? (:errors result) :entity))))))


(deftest nullable-fields-test
  (let [schema (-> (mds/create-builder)
                   (ds/add-entity :with-nullable (uuid)
                                  {:required-field {:uuid (uuid) :type :text :nullable? false}
                                   :optional-field {:uuid (uuid) :type :text :nullable? true}})
                   (ds/build))]

    (testing "valid entity with nullable field as nil"
      (is (nil? (ds/validate-entity schema :with-nullable
                                    {:id (random-uuid)
                                     :required-field "value"
                                     :optional-field nil}))))

    (testing "valid entity with nullable field having value"
      (is (nil? (ds/validate-entity schema :with-nullable
                                    {:id (random-uuid)
                                     :required-field "value"
                                     :optional-field "optional"}))))

    (testing "invalid - required field cannot be nil"
      (let [result (ds/validate-entity schema :with-nullable
                                       {:id (random-uuid)
                                        :required-field nil
                                        :optional-field "value"})]
        (is (some? result))
        (is (contains? (:errors result) :required-field))))))

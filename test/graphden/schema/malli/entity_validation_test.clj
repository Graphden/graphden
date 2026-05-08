(ns graphden.schema.malli.entity-validation-test
  "Entity validation tests for malli-data-schema.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.malli.test-helpers :refer [example-schema uuid]]
    [graphden.schema.protocol.protocol :as ds]))


(deftest validate-entity-test
  (let [valid-fn-id (random-uuid)]

    (testing "valid fn entity (base fn with parent-id nil)"
      (is (nil? (ds/validate-entity example-schema :fn
                                    {:id (random-uuid)
                                     :name "add"
                                     :parent-ids nil
                                     :return-type :int}))))

    (testing "valid fn entity (composed fn with parent-id)"
      (is (nil? (ds/validate-entity example-schema :fn
                                    {:id (random-uuid)
                                     :name "my-add"
                                     :parent-ids [valid-fn-id]
                                     :return-type :int}))))

    (testing "invalid fn - missing required field"
      (let [result (ds/validate-entity example-schema :fn
                                       {:id (random-uuid)
                                        :return-type :int})]
        (is (some? result))
        (is (contains? (:errors result) :name))))

    (testing "invalid fn - wrong enum value"
      (let [result (ds/validate-entity example-schema :fn
                                       {:id (random-uuid)
                                        :name "add"
                                        :return-type :unknown-type})]
        (is (some? result))
        (is (contains? (:errors result) :return-type))))

    (testing "valid arg with literal value"
      (is (nil? (ds/validate-entity example-schema :arg
                                    {:id (random-uuid)
                                     :fn-id valid-fn-id
                                     :name "x"
                                     :type :int
                                     :value 42}))))

    (testing "valid arg with ref-id (execute fn and use result)"
      (is (nil? (ds/validate-entity example-schema :arg
                                    {:id (random-uuid)
                                     :fn-id valid-fn-id
                                     :name "x"
                                     :type :int
                                     :ref-id valid-fn-id}))))

    (testing "valid arg with :type :fn (HOF — `is-fn` was retired in #15b)"
      ;; For HOF, fn-id goes in ref-id (not value - value is for literals)
      (is (nil? (ds/validate-entity example-schema :arg
                                    {:id (random-uuid)
                                     :fn-id valid-fn-id
                                     :name "f"
                                     :type :fn
                                     :ref-id valid-fn-id}))))

    (testing "valid arg with all nullable fields nil"
      (is (nil? (ds/validate-entity example-schema :arg
                                    {:id (random-uuid)
                                     :fn-id valid-fn-id
                                     :name "x"
                                     :type :int
                                     :value nil
                                     :ref-id nil
                                     :source-id nil
                                     :required nil}))))

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

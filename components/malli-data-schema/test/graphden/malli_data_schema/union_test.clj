(ns graphden.malli-data-schema.union-test
  "Union type validation tests for malli-data-schema."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.malli-data-schema.test-helpers :refer [uuid]]))


(deftest union-variants-validation-test
  (testing "empty union variants throw"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Union variants cannot be empty"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :union :variants []}})
              (ds/build)))))

  (testing "duplicate ref variants throw"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Union has duplicate variants"
          (-> (mds/create-builder)
              (ds/add-entity :target (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :union
                                                   :variants [{:type :ref :ref-entity :target}
                                                              {:type :ref :ref-entity :target}]}})
              (ds/build)))))

  (testing "duplicate base type variants throw"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Union has duplicate variants"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :union
                                                   :variants [{:type :text}
                                                              {:type :text}]}})
              (ds/build))))))


(deftest union-with-ref-variant-test
  (testing "union with ref variant validates correctly"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :user (uuid) {:name {:uuid (uuid) :type :text}})
                     (ds/add-entity :item (uuid)
                                    {:owner {:uuid (uuid)
                                             :type :union
                                             :variants [{:type :ref :ref-entity :user}
                                                        {:type :text}]}})
                     ds/build)]
      ;; Valid with UUID (ref to user)
      (is (nil? (ds/validate-entity schema :item {:id (uuid) :owner (uuid)})))
      ;; Valid with string (text variant)
      (is (nil? (ds/validate-entity schema :item {:id (uuid) :owner "some text"})))
      ;; Invalid with number
      (let [errors (ds/validate-entity schema :item {:id (uuid) :owner 123})]
        (is (some? (:errors errors)))))))


(deftest union-with-enum-variant-test
  (testing "union with enum variant validates correctly"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :status (uuid)
                                  [{:uuid (uuid) :value :active}
                                   {:uuid (uuid) :value :inactive}])
                     (ds/add-entity :item (uuid)
                                    {:state {:uuid (uuid)
                                             :type :union
                                             :variants [{:type :enum :enum-name :status}
                                                        {:type :int}]}})
                     ds/build)]
      ;; Valid with enum value
      (is (nil? (ds/validate-entity schema :item {:id (uuid) :state :active})))
      ;; Valid with int
      (is (nil? (ds/validate-entity schema :item {:id (uuid) :state 42})))
      ;; Invalid with string
      (let [errors (ds/validate-entity schema :item {:id (uuid) :state "invalid"})]
        (is (some? (:errors errors)))))))


(deftest union-variant-nullable-error-test
  (testing "union variant with :nullable? throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Union variant cannot have :nullable?"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid)
                             {:value {:uuid (uuid)
                                      :type :union
                                      :variants [{:type :text :nullable? true}]}})
              ds/build)))))


(deftest union-with-duplicate-variants-test
  (testing "union with duplicate variants throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Union has duplicate variants"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid)
                             {:value {:uuid (uuid)
                                      :type :union
                                      :variants [{:type :text} {:type :text}]}})
              ds/build)))))


(deftest union-empty-variants-test
  (testing "union with empty variants throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Union variants cannot be empty"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid)
                             {:value {:uuid (uuid) :type :union :variants []}})
              ds/build)))))

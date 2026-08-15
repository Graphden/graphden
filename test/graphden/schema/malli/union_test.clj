(ns graphden.schema.malli.union-test
  "Union type validation tests for malli-data-schema."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.malli.test-helpers :refer [uuid]]
    [graphden.schema.protocol.protocol :as ds]))


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

(ns graphden.schema.malli.build-validation-test
  "Build-time validation tests for malli-data-schema."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.malli.test-helpers :refer [uuid]]
    [graphden.schema.protocol.protocol :as ds]))


(deftest validation-at-build-time-test
  (testing "unknown enum reference in union variant throws at build"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Unknown enum"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid)
                             {:value {:uuid (uuid) :type :union
                                      :variants [{:type :enum :enum-name :undefined}]}})
              (ds/build)))))

  (testing "unknown entity reference in union variant throws at build"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Unknown entity"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid)
                             {:value {:uuid (uuid) :type :union
                                      :variants [{:type :ref :ref-entity :undefined}]}})
              (ds/build)))))

  (testing "self-referencing entity is allowed"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :node (uuid)
                                    {:parent-id {:uuid (uuid) :type :ref
                                                 :ref-entity :node
                                                 :nullable? true}})
                     (ds/build))]
      (is (some? schema))))

  (testing "circular references between entities are allowed"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :a (uuid)
                                    {:b-id {:uuid (uuid) :type :ref :ref-entity :b :nullable? true}})
                     (ds/add-entity :b (uuid)
                                    {:a-id {:uuid (uuid) :type :ref :ref-entity :a :nullable? true}})
                     (ds/build))]
      (is (some? schema)))))


(deftest validate-entity-name-test
  (testing "non-keyword entity name throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Entity name must be a keyword"
          (-> (mds/create-builder)
              (ds/add-entity "string-name" (uuid) {:field {:uuid (uuid) :type :text}})
              (ds/build)))))

  (testing "nil entity name throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Entity name must be a keyword"
          (-> (mds/create-builder)
              (ds/add-entity nil (uuid) {:field {:uuid (uuid) :type :text}})
              (ds/build))))))


(deftest validate-field-names-test
  (testing "non-keyword field name throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field name must be a keyword"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {"string-field" {:uuid (uuid) :type :text}})
              (ds/build)))))

  (testing "reserved :id field name throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field name :id is reserved"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:id {:uuid (uuid) :type :text}})
              (ds/build))))))


(deftest validate-field-spec-test
  (testing "missing :type throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field spec missing :type"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid)}})
              (ds/build)))))

  (testing "unknown field type throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Unknown field type"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :unknown-type}})
              (ds/build)))))

  (testing "non-boolean :nullable? throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field :nullable\? must be a boolean"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :text :nullable? "yes"}})
              (ds/build)))))

  (testing ":ref type missing :ref-entity throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field type :ref requires :ref-entity"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :ref}})
              (ds/build)))))

  (testing ":ref type with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field type :ref has unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :target (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :ref
                                                   :ref-entity :target
                                                   :extra-attr "value"}})
              (ds/build)))))

  (testing ":enum type missing :enum-name throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field type :enum requires :enum-name"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :enum}})
              (ds/build)))))

  (testing ":enum type with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field type :enum has unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :active}])
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :enum
                                                   :enum-name :status
                                                   :extra-attr "value"}})
              (ds/build)))))

  (testing ":union with non-vector :variants throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field type :union requires :variants vector"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :union
                                                   :variants {:type :text}}})
              (ds/build)))))

  (testing ":union variant with :nullable? throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Union variant cannot have :nullable\? attribute"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :union
                                                   :variants [{:type :text :nullable? true}]}})
              (ds/build)))))

  (testing ":union with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field type :union has unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :union
                                                   :variants [{:type :text}]
                                                   :extra-attr "value"}})
              (ds/build)))))

  (testing "base type with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"has unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid (uuid) :type :text
                                                   :extra-attr "value"}})
              (ds/build))))))

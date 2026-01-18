(ns graphden.malli-data-schema.constraint-validation-test
  "Constraint validation tests for malli-data-schema."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.malli-data-schema.test-helpers :refer [uuid]]))


(deftest constraint-validation-test
  (testing "constraint on unknown entity throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"unknown entity"
          (-> (mds/create-builder)
              (ds/add-constraint :unknown-entity {:type :unique :fields [:name]})
              (ds/build)))))

  (testing "constraint on unknown field throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"unknown field"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-constraint :item {:type :unique :fields [:unknown-field]})
              (ds/build)))))

  (testing "constraint with unknown type throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Unknown constraint type"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-constraint :item {:type :unknown-type :fields [:name]})
              (ds/build)))))

  (testing "constraint with empty fields throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Constraint :fields cannot be empty"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-constraint :item {:type :unique :fields []})
              (ds/build)))))

  (testing "constraint with non-vector fields throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Constraint :fields must be a vector"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-constraint :item {:type :unique :fields :name})
              (ds/build))))))


(deftest constraint-error-paths-test
  (testing "constraint missing :type throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Constraint missing :type"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-constraint :item {:fields [:name]})
              (ds/build)))))

  (testing "constraint :fields with non-keywords throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Constraint :fields must contain only keywords"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-constraint :item {:type :unique :fields ["name"]})
              (ds/build)))))

  (testing "constraint with extra attributes throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Constraint has unsupported attributes"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-constraint :item {:type :unique :fields [:name] :extra "value"})
              (ds/build)))))

  (testing "duplicate constraint throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Duplicate constraint"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-constraint :item {:type :unique :fields [:name]})
              (ds/add-constraint :item {:type :unique :fields [:name]})
              (ds/build))))))

(ns graphden.schema.malli.uuid-validation-test
  "UUID validation tests for malli-data-schema."
  (:require
    [cheshire.core :as cheshire]
    [clojure.test :refer [deftest is testing]]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.malli.test-helpers :refer [uuid]]
    [graphden.schema.protocol.protocol :as ds]))


(deftest validate-uuid-test
  (testing "non-UUID entity uuid throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"UUID required"
          (-> (mds/create-builder)
              (ds/add-entity :item "not-a-uuid" {:field {:uuid (uuid) :type :text}})
              (ds/build)))))

  (testing "non-UUID field uuid throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"UUID required"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:field {:uuid "not-a-uuid" :type :text}})
              (ds/build)))))

  (testing "non-UUID enum uuid throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"UUID required"
          (-> (mds/create-builder)
              (ds/add-enum :status "not-a-uuid" [{:uuid (uuid) :value :active}])
              (ds/build)))))

  (testing "non-UUID enum value uuid throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"UUID required"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid "not-a-uuid" :value :active}])
              (ds/build)))))

  (testing "ex-data keeps the canonical error code and stays JSON-encodable"
    ;; `:type` is the ERROR-CODE key the status mapper reads; it used to be
    ;; overwritten with the offending value's `java.lang.Class`, which both
    ;; lost the code and made the ex-data unencodable.
    (try
      (-> (mds/create-builder)
          (ds/add-entity :item "not-a-uuid" {:field {:uuid (uuid) :type :text}})
          (ds/build))
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :validation-error/type-mismatch (:type data)))
          (is (= "java.lang.String" (:value-type data)))
          (is (string? (cheshire/generate-string data))))))))


(deftest uuid-uniqueness-test
  (testing "duplicate entity UUIDs throw"
    (let [same-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-entity :item1 same-uuid {:field {:uuid (uuid) :type :text}})
                (ds/add-entity :item2 same-uuid {:field {:uuid (uuid) :type :text}})
                (ds/build))))))

  (testing "duplicate field UUIDs throw"
    (let [same-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-entity :item (uuid) {:field1 {:uuid same-uuid :type :text}
                                             :field2 {:uuid same-uuid :type :text}})
                (ds/build))))))

  (testing "field UUID same as entity UUID throws"
    (let [same-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-entity :item same-uuid {:field {:uuid same-uuid :type :text}})
                (ds/build))))))

  (testing "enum value UUID same as entity UUID throws"
    (let [same-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-entity :item same-uuid {:field {:uuid (uuid) :type :text}})
                (ds/add-enum :status (uuid) [{:uuid same-uuid :value :active}])
                (ds/build))))))

  (testing "enum value UUID same as field UUID throws"
    (let [same-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-entity :item (uuid) {:field {:uuid same-uuid :type :text}})
                (ds/add-enum :status (uuid) [{:uuid same-uuid :value :active}])
                (ds/build))))))

  (testing "enum UUID same as entity UUID throws"
    (let [same-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-entity :item same-uuid {:field {:uuid (uuid) :type :text}})
                (ds/add-enum :status same-uuid [{:uuid (uuid) :value :active}])
                (ds/build))))))

  (testing "cross-enum value UUID collision throws"
    (let [same-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-enum :status (uuid) [{:uuid same-uuid :value :active}])
                (ds/add-enum :role (uuid) [{:uuid same-uuid :value :admin}])
                (ds/build)))))))


(deftest duplicate-uuid-within-entity-test
  (testing "duplicate field UUIDs within same entity throws specific error"
    (let [same-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID within entity"
            (-> (mds/create-builder)
                (ds/add-entity :item (uuid) {:field1 {:uuid same-uuid :type :text}
                                             :field2 {:uuid same-uuid :type :int}})
                (ds/build)))))))


(deftest field-missing-uuid-test
  (testing "field missing uuid throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Field missing :uuid"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:type :text}})
              (ds/build))))))


(deftest duplicate-entity-name-test
  (testing "duplicate entity names throw"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Duplicate entity name"
          (-> (mds/create-builder)
              (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
              (ds/add-entity :item (uuid) {:other {:uuid (uuid) :type :text}})
              (ds/build))))))


(deftest retired-uuid-is-reserved-test
  (testing "a new field reusing a retired field's uuid throws at build"
    ;; Without the reservation the migration layer would see the old
    ;; column as a rename target while process-retired-fields!
    ;; simultaneously drops it.
    (let [tombstone (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-entity :item (uuid) {:name {:uuid (uuid) :type :text}})
                (ds/retire-field :item :old-col tombstone)
                (ds/add-entity :other (uuid) {:reused {:uuid tombstone :type :text}}))))))
  (testing "a tombstone colliding with an existing field uuid throws too"
    (let [field-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Duplicate UUID"
            (-> (mds/create-builder)
                (ds/add-entity :item (uuid) {:name {:uuid field-uuid :type :text}})
                (ds/retire-field :item :other-col field-uuid)))))))

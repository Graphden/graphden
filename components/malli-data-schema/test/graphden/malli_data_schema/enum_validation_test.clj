(ns graphden.malli-data-schema.enum-validation-test
  "Enum validation tests for malli-data-schema."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.malli-data-schema.test-helpers :refer [uuid]]))


(deftest enum-validation-test
  (testing "non-keyword enum name throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Enum name must be a keyword"
          (-> (mds/create-builder)
              (ds/add-enum "string-name" (uuid) [{:uuid (uuid) :value :active}])
              (ds/build)))))

  (testing "duplicate enum names throw"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Duplicate enum name"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :active}])
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :pending}])
              (ds/build)))))

  (testing "empty enum values throw"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Enum values cannot be empty"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [])
              (ds/build)))))

  (testing "non-keyword enum value throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Enum value :value must be a keyword"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value "string-value"}])
              (ds/build)))))

  (testing "duplicate enum value keywords throw"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Enum has duplicate values"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :active}
                                           {:uuid (uuid) :value :active}])
              (ds/build)))))

  (testing "enum value missing :value throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Enum value missing :value"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid)}])
              (ds/build)))))

  (testing "enum value missing :uuid throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Enum value missing :uuid"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:value :active}])
              (ds/build)))))

  (testing "invalid identifier name in enum value throws"
    ;; Enum value names must be valid SQL identifiers after kebab->snake conversion
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Invalid identifier name"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :123-invalid}])
              (ds/build))))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Invalid identifier name"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :-starts-with-hyphen}])
              (ds/build))))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Invalid identifier name"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :ends-with-hyphen-}])
              (ds/build)))))

  (testing "valid identifier names in enum values work"
    (is (some?
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :active}
                                           {:uuid (uuid) :value :in-progress}
                                           {:uuid (uuid) :value :completed123}])
              (ds/build))))))


(deftest enum-values-format-test
  (testing "enum values not a vector throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Enum values must be a vector"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) {:active {:uuid (uuid)}})
              (ds/build)))))

  (testing "enum value entry not a map throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Each enum value must be a map"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [:active])
              (ds/build)))))

  (testing "duplicate enum value UUIDs throw"
    (let [same-uuid (uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Enum has duplicate value UUIDs"
            (-> (mds/create-builder)
                (ds/add-enum :status (uuid) [{:uuid same-uuid :value :active}
                                             {:uuid same-uuid :value :inactive}])
                (ds/build)))))))


(deftest enum-uuid-test
  (testing "enum-uuid returns correct UUID"
    (let [status-uuid (uuid)
          schema (-> (mds/create-builder)
                     (ds/add-enum :status status-uuid [{:uuid (uuid) :value :active}])
                     (ds/build))]
      (is (= status-uuid (ds/enum-uuid schema :status)))))

  (testing "enum-uuid returns nil for unknown enum"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :status (uuid) [{:uuid (uuid) :value :active}])
                     (ds/build))]
      (is (nil? (ds/enum-uuid schema :unknown))))))


(deftest identifier-validation-test
  (testing "enum value name too long throws"
    (let [long-value (keyword (str/join (repeat 64 "a")))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Identifier name too long"
            (-> (mds/create-builder)
                (ds/add-enum :status (uuid) [{:uuid (uuid) :value long-value}])
                ds/build)))))

  (testing "enum value exactly 63 chars is valid"
    (let [max-value (keyword (str/join (repeat 63 "a")))]
      (is (some? (-> (mds/create-builder)
                     (ds/add-enum :status (uuid) [{:uuid (uuid) :value max-value}])
                     ds/build)))))

  (testing "enum value with invalid pattern throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid identifier name"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value :Invalid}])
              ds/build))))

  (testing "enum value starting with number throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid identifier name"
          (-> (mds/create-builder)
              (ds/add-enum :status (uuid) [{:uuid (uuid) :value (keyword "1invalid")}])
              ds/build)))))

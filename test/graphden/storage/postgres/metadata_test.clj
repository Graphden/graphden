(ns graphden.storage.postgres.metadata-test
  "Unit tests for PostgreSQL metadata parsing functions.
   Tests internal functions that don't require a database connection."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.postgres.metadata :as metadata]))


;; === parse-extra tests ===

(deftest parse-extra-test
  (testing "parses JSON string with keyword values"
    (let [result (metadata/parse-extra "{\"type\":\"text\",\"nullable?\":true}")]
      (is (= :text (:type result)))
      (is (true? (:nullable? result)))))

  (testing "parses JSON string with enum-name"
    (let [result (metadata/parse-extra "{\"type\":\"enum\",\"nullable?\":false,\"enum-name\":\"status\"}")]
      (is (= :enum (:type result)))
      (is (false? (:nullable? result)))
      (is (= :status (:enum-name result)))))

  (testing "returns nil for nil input"
    (is (nil? (metadata/parse-extra nil))))

  (testing "returns nil for empty string"
    (is (nil? (metadata/parse-extra ""))))

  (testing "returns nil for empty JSON object"
    (is (nil? (metadata/parse-extra "{}"))))

  (testing "returns nil for null JSON"
    (is (nil? (metadata/parse-extra "null")))))


;; === extra->json tests ===

(deftest extra->json-test
  (testing "converts map with keyword values"
    (let [result (metadata/extra->json {:type :text :nullable? false})]
      (is (string? result))
      (is (re-find #"\"type\":\"text\"" result))
      (is (re-find #"\"nullable\?\":false" result))))

  (testing "returns nil for nil input"
    (is (nil? (metadata/extra->json nil))))

  (testing "handles nested map values"
    (let [result (metadata/extra->json {:type :jsonb :nullable? true})]
      (is (string? result)))))


;; === parse-metadata-impl tests ===

(deftest parse-metadata-impl-orphaned-field-test
  (testing "strict mode throws on orphaned field"
    (let [rows [{:uuid (random-uuid)
                 :kind "field"
                 :name "orphaned_field"
                 :parent_uuid (random-uuid)  ; non-existent parent
                 :extra nil}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Orphaned field entry"
            (#'metadata/parse-metadata-impl rows true)))))

  (testing "strict mode orphaned field exception contains correct data"
    (let [field-uuid (random-uuid)
          parent-uuid (random-uuid)
          rows [{:uuid field-uuid
                 :kind "field"
                 :name "test_field"
                 :parent_uuid parent-uuid
                 :extra nil}]]
      (try
        (#'metadata/parse-metadata-impl rows true)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :metadata-error/corrupted (:type (ex-data e))))
          (is (= field-uuid (:field-uuid (ex-data e))))
          (is (= :test_field (:field-name (ex-data e))))
          (is (= parent-uuid (:missing-parent-uuid (ex-data e))))))))

  (testing "non-strict mode skips orphaned field"
    (let [rows [{:uuid (random-uuid)
                 :kind "field"
                 :name "orphaned_field"
                 :parent_uuid (random-uuid)
                 :extra nil}]
          result (#'metadata/parse-metadata-impl rows false)]
      (is (empty? (:fields result))))))


(deftest parse-metadata-impl-orphaned-enum-value-test
  (testing "strict mode throws on orphaned enum-value"
    (let [rows [{:uuid (random-uuid)
                 :kind "enum-value"
                 :name "orphaned_value"
                 :parent_uuid (random-uuid)  ; non-existent parent
                 :extra nil}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Orphaned enum-value entry"
            (#'metadata/parse-metadata-impl rows true)))))

  (testing "strict mode orphaned enum-value exception contains correct data"
    (let [value-uuid (random-uuid)
          parent-uuid (random-uuid)
          rows [{:uuid value-uuid
                 :kind "enum-value"
                 :name "active"
                 :parent_uuid parent-uuid
                 :extra nil}]]
      (try
        (#'metadata/parse-metadata-impl rows true)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :metadata-error/corrupted (:type (ex-data e))))
          (is (= value-uuid (:enum-value-uuid (ex-data e))))
          (is (= :active (:value-name (ex-data e))))
          (is (= parent-uuid (:missing-parent-uuid (ex-data e))))))))

  (testing "non-strict mode skips orphaned enum-value"
    (let [rows [{:uuid (random-uuid)
                 :kind "enum-value"
                 :name "orphaned_value"
                 :parent_uuid (random-uuid)
                 :extra nil}]
          result (#'metadata/parse-metadata-impl rows false)]
      (is (empty? (:enum-values result))))))


(deftest parse-metadata-impl-valid-data-test
  (testing "parses valid entity"
    (let [entity-uuid (random-uuid)
          rows [{:uuid entity-uuid
                 :kind "entity"
                 :name "user"
                 :parent_uuid nil
                 :extra nil}]
          result (#'metadata/parse-metadata-impl rows true)]
      (is (= :user (get-in result [:entities entity-uuid])))))

  (testing "parses valid enum"
    (let [enum-uuid (random-uuid)
          rows [{:uuid enum-uuid
                 :kind "enum"
                 :name "status"
                 :parent_uuid nil
                 :extra nil}]
          result (#'metadata/parse-metadata-impl rows true)]
      (is (= :status (get-in result [:enums enum-uuid])))))

  (testing "parses field with parent"
    (let [entity-uuid (random-uuid)
          field-uuid (random-uuid)
          rows [{:uuid entity-uuid
                 :kind "entity"
                 :name "user"
                 :parent_uuid nil
                 :extra nil}
                {:uuid field-uuid
                 :kind "field"
                 :name "email"
                 :parent_uuid entity-uuid
                 :extra "{\"type\":\"text\",\"nullable?\":false}"}]
          result (#'metadata/parse-metadata-impl rows true)]
      (is (= :user (get-in result [:entities entity-uuid])))
      (let [field-info (get-in result [:fields field-uuid])]
        (is (= :user (:entity field-info)))
        (is (= :email (:field field-info)))
        (is (= :text (:type field-info)))
        (is (false? (:nullable? field-info))))))

  (testing "parses enum-value with parent"
    (let [enum-uuid (random-uuid)
          value-uuid (random-uuid)
          rows [{:uuid enum-uuid
                 :kind "enum"
                 :name "status"
                 :parent_uuid nil
                 :extra nil}
                {:uuid value-uuid
                 :kind "enum-value"
                 :name "active"
                 :parent_uuid enum-uuid
                 :extra nil}]
          result (#'metadata/parse-metadata-impl rows true)]
      (is (= :status (get-in result [:enums enum-uuid])))
      (let [value-info (get-in result [:enum-values value-uuid])]
        (is (= :status (:enum value-info)))
        (is (= :active (:value value-info))))))

  (testing "returns nil for empty rows"
    (is (nil? (#'metadata/parse-metadata-impl [] true)))
    (is (nil? (#'metadata/parse-metadata-impl nil true)))))

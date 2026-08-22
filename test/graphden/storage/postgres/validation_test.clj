(ns graphden.storage.postgres.validation-test
  "Tests for PostgreSQL storage validation: configuration, SQL identifiers, snake_case collisions."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


;; === Snake_case collision tests ===

(deftest snake-case-collision-test
  (testing "snake_case naming collision is detected for entities"
    (let [storage (setup/create-test-storage)]
      (try
        ;; :user-name and :user_name both become user_name
        (let [schema (-> (mds/create-builder)
                         (ds/add-entity :user-name #uuid "00000000-0000-0000-0000-000000004001"
                                        {:field1 {:uuid #uuid "00000000-0000-0000-0000-000000004002"
                                                  :type :text}})
                         (ds/add-entity :user_name #uuid "00000000-0000-0000-0000-000000004003"
                                        {:field2 {:uuid #uuid "00000000-0000-0000-0000-000000004004"
                                                  :type :text}})
                         ds/build)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Snake_case naming collision"
                (sp/initialize storage schema))))
        (finally
          (sp/close storage)))))

  (testing "snake_case naming collision is detected for fields"
    (let [storage (setup/create-test-storage)]
      (try
        (let [schema (-> (mds/create-builder)
                         (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000004010"
                                        {:first-name {:uuid #uuid "00000000-0000-0000-0000-000000004011"
                                                      :type :text}
                                         :first_name {:uuid #uuid "00000000-0000-0000-0000-000000004012"
                                                      :type :text}})
                         ds/build)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Snake_case naming collision"
                (sp/initialize storage schema))))
        (finally
          (sp/close storage)))))

  (testing "snake_case naming collision is detected for enums"
    (let [storage (setup/create-test-storage)]
      (try
        (let [schema (-> (mds/create-builder)
                         (ds/add-enum :user-status #uuid "00000000-0000-0000-0000-000000004020"
                                      [{:uuid #uuid "00000000-0000-0000-0000-000000004021"
                                        :value :active}])
                         (ds/add-enum :user_status #uuid "00000000-0000-0000-0000-000000004022"
                                      [{:uuid #uuid "00000000-0000-0000-0000-000000004023"
                                        :value :pending}])
                         (ds/add-entity :item #uuid "00000000-0000-0000-0000-000000004030"
                                        {:name {:uuid #uuid "00000000-0000-0000-0000-000000004031"
                                                :type :text}})
                         ds/build)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Snake_case naming collision"
                (sp/initialize storage schema))))
        (finally
          (sp/close storage))))))


;; === Configuration validation tests ===

(deftest configuration-validation-test
  (testing "creating storage without jdbc-url throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"jdbc-url is required"
          (pg/create-storage {:username "test"
                              :password "test"
                              :pool-size 2}))))

  (testing "creating storage without username throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"username is required"
          (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost/test"
                              :password "test"}))))

  (testing "creating storage with empty username throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"username is required"
          (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost/test"
                              :username "   "
                              :password "test"}))))

  (testing "creating storage without password throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"password is required"
          (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost/test"
                              :username "test"}))))

  (testing "creating storage with empty password throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"password is required"
          (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost/test"
                              :username "test"
                              :password "  "}))))

  (testing "error ex-data contains type instead of sensitive credentials info"
    (try
      (pg/create-storage {:username "testuser" :password "testpass"})
      (is false "expected pg/create-storage to throw")
      (catch clojure.lang.ExceptionInfo e
        ;; Should NOT expose :username or :password in ex-data
        (is (= :config-error/missing-jdbc-url (:type (ex-data e))))
        (is (nil? (:provided-keys (ex-data e)))))))

  (testing "missing username error has correct type"
    (try
      (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost/test"
                          :password "test"})
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :config-error/missing-username (:type (ex-data e)))))))

  (testing "missing password error has correct type"
    (try
      (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost/test"
                          :username "test"})
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :config-error/missing-password (:type (ex-data e))))))))


;; === SQL identifier validation tests ===

(deftest sql-identifier-validation-test
  (testing "validate-sql-identifier! accepts valid identifiers"
    (let [validate-fn #'util/validate-sql-identifier!]
      (is (nil? (validate-fn "valid_name" {})))
      (is (nil? (validate-fn "name123" {})))
      (is (nil? (validate-fn "a" {})))))

  (testing "validate-sql-identifier! rejects invalid identifiers"
    (let [validate-fn #'util/validate-sql-identifier!]
      ;; SQL injection attempts
      (is (thrown? clojure.lang.ExceptionInfo (validate-fn "name'; DROP TABLE users; --" {})))
      (is (thrown? clojure.lang.ExceptionInfo (validate-fn "name\"" {})))
      ;; Invalid characters
      (is (thrown? clojure.lang.ExceptionInfo (validate-fn "name-with-dash" {})))
      (is (thrown? clojure.lang.ExceptionInfo (validate-fn "123starts_with_number" {})))
      (is (thrown? clojure.lang.ExceptionInfo (validate-fn "UPPERCASE" {})))
      (is (thrown? clojure.lang.ExceptionInfo (validate-fn "" {}))))))


;; === PostgreSQL type validation tests ===

(deftest pg-type-validation-test
  (testing "validate-pg-type! accepts valid base types"
    (let [validate-fn #'util/validate-pg-type!]
      (is (nil? (validate-fn "UUID" {})))
      (is (nil? (validate-fn "TEXT" {})))
      (is (nil? (validate-fn "BIGINT" {})))
      (is (nil? (validate-fn "BOOLEAN" {})))
      (is (nil? (validate-fn "NUMERIC" {})))
      (is (nil? (validate-fn "TIMESTAMPTZ" {})))
      (is (nil? (validate-fn "JSONB" {})))
      (is (nil? (validate-fn "BYTEA" {})))))

  (testing "validate-pg-type! accepts valid quoted enum identifiers"
    (let [validate-fn #'util/validate-pg-type!]
      (is (nil? (validate-fn "\"status\"" {})))
      (is (nil? (validate-fn "\"user_role\"" {})))
      (is (nil? (validate-fn "\"my_enum123\"" {})))))

  (testing "validate-pg-type! rejects invalid types"
    (let [validate-fn #'util/validate-pg-type!]
      ;; SQL injection attempts
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid PostgreSQL type"
            (validate-fn "TEXT; DROP TABLE users; --" {})))
      ;; Invalid type names
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid PostgreSQL type"
            (validate-fn "INVALID_TYPE" {})))
      ;; Improperly quoted identifiers
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid PostgreSQL type"
            (validate-fn "\"UPPERCASE\"" {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid PostgreSQL type"
            (validate-fn "\"has-dash\"" {}))))))


;; === Utility function tests ===
(deftest enum-value-conversion-test
  (testing "enum-value->sql converts to snake_case"
    (is (= "active" (util/enum-value->sql :active)))
    (is (= "in_progress" (util/enum-value->sql :in-progress))))

  (testing "sql->enum-value converts back to kebab-case keyword"
    (is (= :active (util/sql->enum-value "active")))
    (is (= :in-progress (util/sql->enum-value "in_progress"))))

  (testing "roundtrip conversion"
    (let [original :in-progress
          sql-val (util/enum-value->sql original)
          back (util/sql->enum-value sql-val)]
      (is (= original back))))

  (testing "enum-value->sql normalizes case and rejects invalid values"
    ;; Uppercase is now normalized to lowercase (security fix)
    (is (= "uppercase" (util/enum-value->sql :UPPERCASE)))
    ;; These still fail because they start with numbers
    (is (thrown? clojure.lang.ExceptionInfo
          (util/enum-value->sql (keyword "123-invalid"))))))


;; === with-query-timeout validation tests ===


;; This namespace boots a real PostgreSQL container. Five pure unit tests
;; used to sit here paying for it and duplicating `util-test` /
;; `protocol.naming-test` / `protocol.config-test` under colliding names;
;; the 2026-08-22 audit moved them out. What stays needs the database.

(ns graphden.executor.types-test
  "Unit tests for the type-hint registry in `graphden.executor.types`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.types :as types]))


;; Reset custom type hints after each test
(use-fixtures :each
  (fn [f]
    (reset! types/custom-type-hints {})
    (f)
    (reset! types/custom-type-hints {})))


(deftest get-type-hint-test
  (testing "returns default hint for built-in types"
    (is (= "integer (e.g., 42, -1)" (types/get-type-hint :int)))
    (is (= "boolean (true or false)" (types/get-type-hint :bool)))
    (is (= "string (e.g., \"hello\")" (types/get-type-hint :text)))
    (is (= "UUID (function reference)" (types/get-type-hint :fn)))
    (is (= "UUID (entity reference)" (types/get-type-hint :ref))))

  (testing "returns type name for unknown types"
    (is (= "unknown-type-xyz" (types/get-type-hint :unknown-type-xyz)))
    (is (= "custom" (types/get-type-hint :custom))))

  (testing "returns custom hint when registered"
    (types/register-type-hint! :email "email address format")
    (is (= "email address format" (types/get-type-hint :email))))

  (testing "custom hints take precedence over defaults"
    (types/register-type-hint! :int "custom int hint")
    (is (= "custom int hint" (types/get-type-hint :int)))))


(deftest register-type-hint-validation-test
  (testing "rejects non-keyword type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"type-keyword must be a keyword"
          (types/register-type-hint! "not-keyword" "hint")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"type-keyword must be a keyword"
          (types/register-type-hint! 123 "hint"))))

  (testing "rejects non-string hint"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"hint-string must be a string"
          (types/register-type-hint! :valid :not-string)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"hint-string must be a string"
          (types/register-type-hint! :valid 42)))))

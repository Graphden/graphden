(ns graphden.fn-registry.validation-test
  "Tests for fn-registry validation functions."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.fn-registry.core :as core]))


(use-fixtures :each exec/with-clean-registry)


;; === parse-arg-spec Tests ===

(deftest parse-arg-spec-test
  (testing "parses keyword arg-spec"
    (let [result (#'core/parse-arg-spec :x :int)]
      (is (= {:arg-type :int :required true} result))))

  (testing "parses map arg-spec with required true"
    (let [result (#'core/parse-arg-spec :x {:type :text :required true})]
      (is (= {:arg-type :text :required true} result))))

  (testing "parses map arg-spec with required false"
    (let [result (#'core/parse-arg-spec :x {:type :bool :required false})]
      (is (= {:arg-type :bool :required false} result))))

  (testing "parses map arg-spec with default required (true)"
    (let [result (#'core/parse-arg-spec :x {:type :numeric})]
      (is (= {:arg-type :numeric :required true} result))))

  (testing "throws on nil arg-spec"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-spec must be a keyword or map"
          (#'core/parse-arg-spec :x nil))))

  (testing "throws on string arg-spec"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-spec must be a keyword or map"
          (#'core/parse-arg-spec :x "string"))))

  (testing "throws on number arg-spec"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-spec must be a keyword or map"
          (#'core/parse-arg-spec :x 42))))

  (testing "throws on vector arg-spec"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-spec must be a keyword or map"
          (#'core/parse-arg-spec :x [:type :int]))))

  (testing "throws on map without :type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arg-spec map must contain :type key"
          (#'core/parse-arg-spec :x {:required false}))))

  (testing "includes arg-name in error data"
    (try
      (#'core/parse-arg-spec :my-arg nil)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :my-arg (:arg-name (ex-data e)))))))

  (testing "validates known arg types - keyword spec"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown arg type"
          (#'core/parse-arg-spec :x :unknown-type))))

  (testing "validates known arg types - map spec"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown arg type"
          (#'core/parse-arg-spec :x {:type :invalid-type :required true}))))

  (testing "accepts all valid storage types"
    (doseq [valid-type [:uuid :text :int :bool :numeric :timestamptz :jsonb :bytes]]
      (is (= {:arg-type valid-type :required true}
             (#'core/parse-arg-spec :x valid-type)))))

  (testing "accepts executor-specific types"
    (is (= {:arg-type :any :required true} (#'core/parse-arg-spec :x :any)))
    (is (= {:arg-type :fn :required true} (#'core/parse-arg-spec :x :fn))))

  (testing "invalid type error includes valid types"
    (try
      (#'core/parse-arg-spec :x :bad-type)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-arg-type (:type (ex-data e))))
        (is (= :x (:arg-name (ex-data e))))
        (is (= :bad-type (:arg-type (ex-data e))))
        (is (set? (:valid-types (ex-data e))))))))


;; === parse-arg-spec required validation Tests ===

(deftest parse-arg-spec-required-validation-test
  (testing "throws when :required is not a boolean"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":required must be a boolean"
          (#'core/parse-arg-spec :x {:type :int :required "true"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":required must be a boolean"
          (#'core/parse-arg-spec :x {:type :int :required 1})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":required must be a boolean"
          (#'core/parse-arg-spec :x {:type :int :required nil}))))

  (testing "accepts valid boolean :required values"
    (is (= {:arg-type :int :required true}
           (#'core/parse-arg-spec :x {:type :int :required true})))
    (is (= {:arg-type :int :required false}
           (#'core/parse-arg-spec :x {:type :int :required false}))))

  (testing ":required defaults to true when not specified"
    (is (= {:arg-type :int :required true}
           (#'core/parse-arg-spec :x {:type :int})))))


;; === parse-arg-spec Error Path Tests ===

(deftest parse-arg-spec-error-test
  (testing "parses keyword type (shorthand)"
    (let [result (#'core/parse-arg-spec :x :int)]
      (is (= :int (:arg-type result)))
      (is (true? (:required result)))))

  (testing "parses map with :type and :required true"
    (let [result (#'core/parse-arg-spec :x {:type :text :required true})]
      (is (= :text (:arg-type result)))
      (is (true? (:required result)))))

  (testing "parses map with :type and :required false"
    (let [result (#'core/parse-arg-spec :x {:type :int :required false})]
      (is (= :int (:arg-type result)))
      (is (false? (:required result)))))

  (testing "defaults :required to true when not specified in map"
    (let [result (#'core/parse-arg-spec :x {:type :bool})]
      (is (= :bool (:arg-type result)))
      (is (true? (:required result)))))

  (testing "rejects map without :type key"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must contain :type key"
          (#'core/parse-arg-spec :x {:required false}))))

  (testing "rejects non-boolean :required"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":required must be a boolean"
          (#'core/parse-arg-spec :x {:type :int :required "yes"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":required must be a boolean"
          (#'core/parse-arg-spec :x {:type :int :required 1}))))

  (testing "rejects invalid arg-spec type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword or map"
          (#'core/parse-arg-spec :x "string")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword or map"
          (#'core/parse-arg-spec :x 123)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword or map"
          (#'core/parse-arg-spec :x [:int]))))

  (testing "error data includes arg info"
    (try
      (#'core/parse-arg-spec :my-arg {:required false})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-arg-spec (:type (ex-data e))))
        (is (= :my-arg (:arg-name (ex-data e))))
        (is (= {:required false} (:arg-spec (ex-data e))))))))


;; === validate-identifier! Tests ===

(deftest validate-identifier-test
  (testing "accepts valid identifiers"
    ;; These should not throw
    (is (nil? (#'core/validate-identifier! "fn-name" :my-fn)))
    (is (nil? (#'core/validate-identifier! "fn-name" :add)))
    (is (nil? (#'core/validate-identifier! "fn-name" :my_func)))
    (is (nil? (#'core/validate-identifier! "fn-name" :_private)))
    (is (nil? (#'core/validate-identifier! "fn-name" :camelCase123)))
    (is (nil? (#'core/validate-identifier! "fn-name" :empty?))))  ; predicates allowed

  (testing "rejects empty identifier"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be empty"
          (#'core/validate-identifier! "fn-name" "")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be empty"
          (#'core/validate-identifier! "arg-name" (keyword "")))))  ; keyword with empty name

  (testing "rejects nil identifier"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be empty"
          (#'core/validate-identifier! "fn-name" nil))))

  (testing "rejects identifier exceeding max length (128 chars)"
    (let [long-name (keyword (str/join (repeat 129 "a")))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds maximum length"
            (#'core/validate-identifier! "fn-name" long-name)))))

  (testing "accepts identifier at exact max length (128 chars)"
    (let [max-name (keyword (str/join (repeat 128 "a")))]
      (is (nil? (#'core/validate-identifier! "fn-name" max-name)))))

  (testing "rejects identifier starting with number"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" :123abc))))

  (testing "rejects identifier with spaces"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" (keyword "my func")))))

  (testing "rejects identifier with special characters"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" (keyword "my@fn"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" (keyword "my!fn"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" (keyword "my.fn")))))

  (testing "error includes name-type and name-value"
    (try
      (#'core/validate-identifier! "arg-name" :123invalid)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-identifier (:type (ex-data e))))
        (is (= "arg-name" (:name-type (ex-data e))))
        (is (= "123invalid" (:name-value (ex-data e))))))))


;; === validate-identifier! Core Path Tests ===

(deftest validate-identifier-core-test
  (testing "rejects nil identifier"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be empty"
          (#'core/validate-identifier! "fn-name" nil))))

  (testing "rejects empty string identifier"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be empty"
          (#'core/validate-identifier! "fn-name" ""))))

  (testing "rejects identifier exceeding max length"
    (let [long-name (str/join (repeat 129 "a"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds maximum length"
            (#'core/validate-identifier! "fn-name" long-name)))))

  (testing "accepts identifier at exactly max length"
    (let [max-name (str/join (repeat 128 "a"))]
      ;; Should not throw
      (#'core/validate-identifier! "fn-name" max-name)
      (is true)))

  (testing "rejects identifier with invalid characters"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" "has space")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" "has.dot")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" "123starts-with-number")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" "-starts-with-hyphen"))))

  (testing "accepts valid identifiers"
    (#'core/validate-identifier! "fn-name" "valid_name")
    (#'core/validate-identifier! "fn-name" "valid-name")
    (#'core/validate-identifier! "fn-name" "ValidName123")
    (#'core/validate-identifier! "fn-name" "_private")
    (#'core/validate-identifier! "fn-name" "predicate?")
    (#'core/validate-identifier! "fn-name" :keyword-name)
    (is true))

  (testing "error data contains context"
    (try
      (#'core/validate-identifier! "arg-name" "bad name")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-identifier (:type (ex-data e))))
        (is (= "arg-name" (:name-type (ex-data e))))
        (is (= "bad name" (:name-value (ex-data e)))))))

  (testing "handles non-keyword non-string values (converts via str)"
    ;; Numbers get converted to strings (starts with digit, invalid)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (#'core/validate-identifier! "fn-name" 123)))
    ;; Symbol gets converted to string (valid)
    (is (nil? (#'core/validate-identifier! "fn-name" 'valid-symbol)))))


;; === validate-fn-def! Tests ===

(deftest validate-fn-def-test
  (testing "accepts valid function definition"
    ;; Should not throw
    (is (nil? (core/validate-fn-def! :my-fn {:args {:x :int} :return-type :int}))))

  (testing "rejects non-keyword fn-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fn-name must be a keyword"
          (core/validate-fn-def! "string-name" {:args {:x :int} :return-type :int})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fn-name must be a keyword"
          (core/validate-fn-def! 123 {:args {:x :int} :return-type :int})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fn-name must be a keyword"
          (core/validate-fn-def! nil {:args {:x :int} :return-type :int}))))

  (testing "fn-name type error includes actual type"
    (try
      (core/validate-fn-def! "not-keyword" {:args {} :return-type :int})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-fn-def (:type (ex-data e))))
        (is (= "not-keyword" (:fn-name (ex-data e))))
        (is (= java.lang.String (:fn-name-type (ex-data e)))))))

  (testing "rejects missing :return-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must include :return-type"
          (core/validate-fn-def! :my-fn {:args {:x :int}}))))

  (testing "rejects nil :return-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must include :return-type"
          (core/validate-fn-def! :my-fn {:args {:x :int} :return-type nil}))))

  (testing "rejects unknown :return-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown return type"
          (core/validate-fn-def! :my-fn {:args {:x :int} :return-type :not-a-type}))))

  (testing "return-type error includes valid types"
    (try
      (core/validate-fn-def! :my-fn {:args {} :return-type :invalid})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-return-type (:type (ex-data e))))
        (is (= :my-fn (:fn-name (ex-data e))))
        (is (= :invalid (:return-type (ex-data e))))
        (is (set? (:valid-types (ex-data e)))))))

  (testing "validates all arg specs"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown arg type"
          (core/validate-fn-def! :my-fn {:args {:x :unknown-type} :return-type :int})))))


;; === validate-fn-def! Core Path Tests ===

(deftest validate-fn-def-core-test
  (testing "rejects non-keyword fn-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fn-name must be a keyword"
          (core/validate-fn-def! "string-name" {:args {} :return-type :int})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fn-name must be a keyword"
          (core/validate-fn-def! 123 {:args {} :return-type :int}))))

  (testing "rejects missing return-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must include :return-type"
          (core/validate-fn-def! :my-fn {:args {}}))))

  (testing "rejects nil return-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must include :return-type"
          (core/validate-fn-def! :my-fn {:args {} :return-type nil}))))

  (testing "error includes fn-name-type for non-keyword"
    (try
      (core/validate-fn-def! "string" {:args {} :return-type :int})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-fn-def (:type (ex-data e))))
        (is (= "string" (:fn-name (ex-data e))))
        (is (= java.lang.String (:fn-name-type (ex-data e)))))))

  (testing "accepts valid function definitions"
    (core/validate-fn-def! :valid-fn {:args {:x :int} :return-type :text})
    (core/validate-fn-def! :no-args {:args {} :return-type :bool})
    (core/validate-fn-def! :any-type {:args {:x :any} :return-type :any})
    (core/validate-fn-def! :fn-type {:args {:f :fn} :return-type :int})
    (is true)))


;; === validate-arg-type! Tests ===

(deftest validate-arg-type-test
  (testing "rejects unknown types"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown arg type"
          (#'core/validate-arg-type! :x :unknown-type)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown arg type"
          (#'core/validate-arg-type! :y :custom))))

  (testing "error includes valid types"
    (try
      (#'core/validate-arg-type! :x :bad-type)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-arg-type (:type (ex-data e))))
        (is (= :x (:arg-name (ex-data e))))
        (is (= :bad-type (:arg-type (ex-data e))))
        (is (set? (:valid-types (ex-data e))))
        (is (contains? (:valid-types (ex-data e)) :int)))))

  (testing "accepts all standard field types"
    (#'core/validate-arg-type! :x :int)
    (#'core/validate-arg-type! :x :numeric)
    (#'core/validate-arg-type! :x :text)
    (#'core/validate-arg-type! :x :bool)
    (#'core/validate-arg-type! :x :uuid)
    (#'core/validate-arg-type! :x :timestamptz)
    (#'core/validate-arg-type! :x :jsonb)
    (#'core/validate-arg-type! :x :bytes)
    (is true))

  (testing "accepts executor-specific types"
    (#'core/validate-arg-type! :x :any)
    (#'core/validate-arg-type! :x :fn)
    (is true)))


;; === validate-all-defs! Tests ===

(deftest validate-all-defs-test
  (testing "validates multiple definitions"
    ;; Should not throw for valid defs
    (core/validate-all-defs! {:fn1 {:args {:x :int} :return-type :int}
                              :fn2 {:args {:y :text} :return-type :text}})
    (is true))

  (testing "fails on first invalid definition"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown return type"
          (core/validate-all-defs! {:good-fn {:args {} :return-type :int}
                                    :bad-fn {:args {} :return-type :invalid}}))))

  (testing "handles empty defs"
    (core/validate-all-defs! {})
    (is true))

  (testing "handles nil defs"
    (core/validate-all-defs! nil)
    (is true)))

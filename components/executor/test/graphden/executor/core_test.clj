(ns graphden.executor.core-test
  "Unit tests for executor core functions.
   Tests internal functions like truncate-value without requiring database."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.core :as core]
    [graphden.storage-protocol.interface :as sp]))


;; === Mock storage for validation tests ===
;; Minimal implementation that satisfies ExecutionGraph protocol
;; Used only for context creation validation, not actual execution

(defrecord MockStorage
  []

  sp/ExecutionGraph

  (resolve-execution-graph
    [_this _fn-id]
    ;; Not used in validation tests
    nil))


;; === truncate-value tests ===

(deftest truncate-value-test
  (testing "returns original string when under limit"
    (is (= "\"hello\"" (#'core/truncate-value "hello" 100)))
    (is (= "42" (#'core/truncate-value 42 100)))
    (is (= ":keyword" (#'core/truncate-value :keyword 100))))

  (testing "truncates long strings with ellipsis"
    (let [long-str "abcdefghij"  ; 10 chars, pr-str adds quotes -> 12 chars
          result (#'core/truncate-value long-str 10)]
      (is (= 13 (count result)))  ; 10 chars + "..."
      (is (str/ends-with? result "..."))))

  (testing "handles exact boundary - string exactly at limit"
    (let [str-5 "abcde"  ; pr-str -> "\"abcde\"" = 7 chars
          result (#'core/truncate-value str-5 7)]
      ;; Exactly at limit, no truncation
      (is (= "\"abcde\"" result))
      (is (not (str/ends-with? result "...")))))

  (testing "handles exact boundary - string one over limit"
    (let [str-6 "abcdef"  ; pr-str -> "\"abcdef\"" = 8 chars
          result (#'core/truncate-value str-6 7)]
      ;; One over limit, should truncate
      (is (= 10 (count result)))  ; 7 + "..."
      (is (str/ends-with? result "..."))))

  (testing "handles empty string"
    (is (= "\"\"" (#'core/truncate-value "" 100))))

  (testing "handles nil"
    (is (= "nil" (#'core/truncate-value nil 100))))

  (testing "handles complex data structures"
    ;; Map
    (let [m {:a 1 :b 2}
          result (#'core/truncate-value m 100)]
      (is (str/includes? result ":a")))

    ;; Vector
    (let [v [1 2 3 4 5]
          result (#'core/truncate-value v 100)]
      (is (= "[1 2 3 4 5]" result)))

    ;; Large nested structure truncates properly
    (let [large {:keys (vec (range 100))}
          result (#'core/truncate-value large 20)]
      (is (= 23 (count result)))  ; 20 + "..."
      (is (str/ends-with? result "..."))))

  (testing "handles special characters in strings"
    (is (= "\"line1\\nline2\"" (#'core/truncate-value "line1\nline2" 100)))
    (is (= "\"tab\\there\"" (#'core/truncate-value "tab\there" 100))))

  (testing "handles Unicode characters"
    ;; Unicode chars: pr-str preserves them
    (let [unicode "привет"  ; Russian "hello"
          result (#'core/truncate-value unicode 100)]
      (is (str/includes? result "привет"))))

  (testing "handles max-len of 0"
    (let [result (#'core/truncate-value "test" 0)]
      ;; Should truncate to 0 chars + "..."
      (is (= "..." result))))

  (testing "handles max-len of 1"
    (let [result (#'core/truncate-value "test" 1)]
      ;; Should truncate to 1 char + "..."
      (is (= "\"..." result))))

  (testing "edge case: truncation mid-escape sequence"
    ;; pr-str of "a\nb" is "\"a\\nb\"" (7 chars)
    ;; If we truncate at 4, we get "\"a\\" + "..." which may look odd but is safe
    (let [result (#'core/truncate-value "a\nb" 4)]
      (is (= 7 (count result)))  ; 4 + "..."
      (is (str/ends-with? result "..."))))

  (testing "redacts sensitive keys in maps"
    (is (str/includes? (#'core/truncate-value {:password "secret123"} 100) "[REDACTED]"))
    (is (str/includes? (#'core/truncate-value {:api-key "abc123"} 100) "[REDACTED]"))
    (is (str/includes? (#'core/truncate-value {:auth-token "xyz"} 100) "[REDACTED]"))
    (is (str/includes? (#'core/truncate-value {:secret "hidden"} 100) "[REDACTED]"))
    (is (str/includes? (#'core/truncate-value {:private-key "key"} 100) "[REDACTED]"))
    (is (str/includes? (#'core/truncate-value {:credential "cred"} 100) "[REDACTED]")))

  (testing "redacts nested sensitive keys"
    (let [nested {:config {:db {:password "secret"}} :name "test"}
          result (#'core/truncate-value nested 200)]
      (is (str/includes? result "[REDACTED]"))
      (is (str/includes? result ":name"))
      (is (str/includes? result "\"test\""))))

  (testing "preserves non-sensitive keys"
    (let [result (#'core/truncate-value {:username "john" :email "john@test.com"} 100)]
      (is (str/includes? result "john"))
      (is (str/includes? result "john@test.com"))))

  (testing "redacts sensitive values in sequences"
    (let [result (#'core/truncate-value [{:password "p1"} {:password "p2"}] 100)]
      (is (str/includes? result "[REDACTED]"))
      (is (not (str/includes? result "p1"))))))


;; === create-context validation tests ===

(deftest create-context-validation-test
  (testing "rejects missing storage"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Storage is required"
          (core/create-context {}))))

  (testing "rejects storage without ExecutionGraph protocol"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"storage must implement ExecutionGraph protocol"
          (core/create-context {:storage :not-a-storage})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"storage must implement ExecutionGraph protocol"
          (core/create-context {:storage {:fake "storage"}}))))

  (testing "rejects timeout below minimum"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"timeout-ms must be at least"
          (core/create-context {:storage (->MockStorage) :timeout-ms 10}))))

  (testing "rejects non-positive max-depth"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"max-depth must be a positive integer"
          (core/create-context {:storage (->MockStorage) :max-depth 0})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"max-depth must be a positive integer"
          (core/create-context {:storage (->MockStorage) :max-depth -1}))))

  (testing "rejects max-depth exceeding limit"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"max-depth exceeds maximum allowed"
          (core/create-context {:storage (->MockStorage) :max-depth 200000}))))

  (testing "rejects non-map path-args"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"path-args must be a map"
          (core/create-context {:storage (->MockStorage) :path-args [1 2 3]}))))

  (testing "rejects invalid path-args keys"
    (let [uuid1 (random-uuid)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"path-args keys must be UUID"
            (core/create-context {:storage (->MockStorage)
                                  :path-args {"string-key" 42}})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"path-args keys must be UUID"
            (core/create-context {:storage (->MockStorage)
                                  :path-args {:keyword-key 42}})))
      ;; Vector with wrong size
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"path-args keys must be UUID"
            (core/create-context {:storage (->MockStorage)
                                  :path-args {[uuid1] 42}})))
      ;; Vector with non-UUID
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"path-args keys must be UUID"
            (core/create-context {:storage (->MockStorage)
                                  :path-args {[uuid1 "not-uuid"] 42}})))))

  (testing "accepts valid path-args keys"
    (let [uuid1 (random-uuid)
          uuid2 (random-uuid)
          ctx (core/create-context {:storage (->MockStorage)
                                    :path-args {uuid1 42
                                                [uuid1 uuid2] "nested"}})]
      (is (some? ctx))
      (is (= 42 (get (:path-args ctx) uuid1)))
      (is (= "nested" (get (:path-args ctx) [uuid1 uuid2]))))))


(deftest create-context-path-args-count-test
  (testing "accepts reasonable number of path-args"
    (let [path-args (into {} (map (fn [_] [(random-uuid) "value"]) (range 100)))]
      (is (some? (core/create-context {:storage (->MockStorage) :path-args path-args})))))

  (testing "rejects excessive path-args count"
    ;; This tests the max-path-args-count limit (10000)
    ;; We use a smaller test to avoid slow test execution
    ;; The actual limit check is tested indirectly via validation
    (let [large-path-args (into {} (map (fn [i] [(random-uuid) i]) (range 10001)))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"path-args count exceeds maximum"
            (core/create-context {:storage (->MockStorage) :path-args large-path-args})))))

  (testing "path-args count error includes details"
    (let [large-path-args (into {} (map (fn [i] [(random-uuid) i]) (range 10001)))]
      (try
        (core/create-context {:storage (->MockStorage) :path-args large-path-args})
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)
                ;; New structure: errors in :validation-errors vector
                err (first (:validation-errors data))]
            (is (= :execution-error/invalid-context (:type data)))
            (is (= 10001 (:path-args-count err)))
            (is (= 10000 (:max-allowed err)))))))))


;; === arg-provided? tests ===

(deftest arg-provided?-test
  (testing "returns true when arg is present"
    (is (true? (core/arg-provided? {:x (delay 1) :y (delay 2)} :x)))
    (is (true? (core/arg-provided? {:x (delay 1) :y (delay 2)} :y))))

  (testing "returns false when arg is absent"
    (is (false? (core/arg-provided? {:x (delay 1)} :y)))
    (is (false? (core/arg-provided? {} :x))))

  (testing "returns true even when value is nil (presence check, not value check)"
    (is (true? (core/arg-provided? {:x nil} :x)))))


;; === register-type-hint! tests ===

(deftest register-type-hint!-test
  (testing "registers custom type hint"
    ;; Clean state
    (reset! core/custom-type-hints {})
    (core/register-type-hint! :email "string in email format")
    (is (= "string in email format" (get @core/custom-type-hints :email))))

  (testing "rejects non-keyword type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"type-keyword must be a keyword"
          (core/register-type-hint! "email" "hint"))))

  (testing "rejects non-string hint"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"hint-string must be a string"
          (core/register-type-hint! :email :not-a-string))))

  (testing "custom hint overrides default"
    (reset! core/custom-type-hints {})
    (core/register-type-hint! :int "custom integer hint")
    (is (= "custom integer hint" (#'core/get-type-hint :int)))
    ;; Cleanup
    (reset! core/custom-type-hints {})))


;; === truncate-value additional tests ===

(deftest truncate-value-edge-cases-test
  (testing "handles deeply nested structures"
    (let [deep {:a {:b {:c {:d {:e "value"}}}}}
          result (#'core/truncate-value deep 50)]
      (is (<= (count result) 53))))  ; 50 + "..."

  (testing "handles very long strings"
    (let [long-str (str/join (repeat 1000 "x"))
          result (#'core/truncate-value long-str 20)]
      (is (= 23 (count result)))))  ; 20 + "..."

  (testing "handles collections with many elements"
    (let [big-vec (vec (range 100))
          result (#'core/truncate-value big-vec 30)]
      (is (<= (count result) 33)))))


;; === check-unknown-type-circuit-breaker! tests ===

(deftest check-unknown-type-circuit-breaker!-test
  (testing "increments counter and allows under limit"
    (let [counter (atom 0)]
      (dotimes [_ 5]
        (#'core/check-unknown-type-circuit-breaker! counter :custom-type))
      (is (= 5 @counter))))

  (testing "throws when exceeding limit"
    (let [counter (atom 10)]  ; Start at limit
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Too many unknown types"
            (#'core/check-unknown-type-circuit-breaker! counter :custom-type)))))

  (testing "exception contains correct data"
    (let [counter (atom 10)]
      (try
        (#'core/check-unknown-type-circuit-breaker! counter :my-custom-type)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :execution-error/unknown-type-limit-exceeded (:type (ex-data e))))
          (is (= 11 (:unknown-type-count (ex-data e))))
          (is (= 10 (:max-allowed (ex-data e))))
          (is (= :my-custom-type (:last-unknown-type (ex-data e)))))))))


;; === Multiple validation errors test ===

(deftest create-context-multiple-errors-test
  (testing "multiple validation errors produces combined message"
    (try
      ;; Trigger multiple errors: bad timeout AND bad max-depth
      (core/create-context {:storage (->MockStorage)
                            :timeout-ms 10    ; too low
                            :max-depth 0})    ; invalid
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :execution-error/invalid-context (:type (ex-data e))))
        (let [errors (:validation-errors (ex-data e))]
          (is (>= (count errors) 2))
          (is (re-find #"Multiple validation errors" (ex-message e))))))))

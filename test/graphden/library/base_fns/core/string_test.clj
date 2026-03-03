(ns graphden.library.base-fns.core.string-test
  "Tests for string base functions."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.library.base-fns.core.test-helpers :as h]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :each exec/with-clean-registry)


(deftest string-operations-test
  (h/register-strings!)

  (testing "str - concatenation"
    (is (= "hello world" (h/call-base-fn :str {:args ["hello" " " "world"]})))
    (is (= "" (h/call-base-fn :str {:args []})))
    (is (= "abc" (h/call-base-fn :str {:args ["a" "b" "c"]}))))

  (testing "str-len"
    (is (= 5 (h/call-base-fn :str-len {:s "hello"})))
    (is (zero? (h/call-base-fn :str-len {:s ""}))))

  (testing "str-upper"
    (is (= "HELLO" (h/call-base-fn :str-upper {:s "hello"})))
    (is (= "HELLO WORLD" (h/call-base-fn :str-upper {:s "Hello World"}))))

  (testing "str-lower"
    (is (= "hello" (h/call-base-fn :str-lower {:s "HELLO"})))
    (is (= "hello world" (h/call-base-fn :str-lower {:s "Hello World"}))))

  (testing "str-trim"
    (is (= "hello" (h/call-base-fn :str-trim {:s "  hello  "})))
    (is (= "hello" (h/call-base-fn :str-trim {:s "\n\thello\n\t"}))))

  (testing "subs"
    (is (= "ell" (h/call-base-fn :subs {:s "hello" :start 1 :end 4})))
    (is (= "llo" (h/call-base-fn :subs {:s "hello" :start 2})))
    (is (= "" (h/call-base-fn :subs {:s "hello" :start 5})))
    (is (= "hello" (h/call-base-fn :subs {:s "hello" :start 0}))))

  (testing "subs - edge cases throw"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"start index cannot be negative"
          (h/call-base-fn :subs {:s "hello" :start -1})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"start index out of bounds"
          (h/call-base-fn :subs {:s "hello" :start 10})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"end index cannot be less than start"
          (h/call-base-fn :subs {:s "hello" :start 3 :end 1})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"end index out of bounds"
          (h/call-base-fn :subs {:s "hello" :start 0 :end 10}))))

  (testing "str-split"
    (is (= ["a" "b" "c"] (h/call-base-fn :str-split {:s "a,b,c" :sep ","}))))

  (testing "str-split - invalid regex throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid regex pattern"
          (h/call-base-fn :str-split {:s "test" :sep "[invalid"}))))

  (testing "str-split - empty separator throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"separator cannot be empty"
          (h/call-base-fn :str-split {:s "test" :sep ""}))))

  (testing "str-join"
    (is (= "a,b,c" (h/call-base-fn :str-join {:coll ["a" "b" "c"] :sep ","})))
    (is (= "abc" (h/call-base-fn :str-join {:coll ["a" "b" "c"]})))))


(deftest string-regex-safety-test
  (h/register-strings!)

  (testing "str-split - regex pattern too long throws"
    (let [long-pattern (str/join (repeat 200 "a"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Regex pattern too long"
            (h/call-base-fn :str-split {:s "test" :sep long-pattern})))))

  (testing "str-split - input string too long throws"
    (let [long-input (str/join (repeat 200000 "a"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Input string too long"
            (h/call-base-fn :str-split {:s long-input :sep ","})))))

  (testing "str-split - normal regex works"
    (is (= ["a" "b" "c"] (h/call-base-fn :str-split {:s "a-b-c" :sep "-"})))
    (is (= ["hello" "world"] (h/call-base-fn :str-split {:s "hello world" :sep " "})))
    (is (= ["one" "two" "three"] (h/call-base-fn :str-split {:s "one::two::three" :sep "::"}))))

  (testing "str-split - regex with special chars works"
    (is (= ["a" "b" "c"] (h/call-base-fn :str-split {:s "a.b.c" :sep "\\."})))
    (is (= ["1" "2" "3"] (h/call-base-fn :str-split {:s "1|2|3" :sep "\\|"}))))

  ;; Note: Regex compilation timeout test is inherently flaky because:
  ;; - 1ms timeout may not trigger on fast machines
  ;; - Finding a pattern that reliably times out is difficult
  ;; The timeout code path is covered implicitly by integration tests
  )


(deftest string-regex-edge-cases-test
  (h/register-strings!)

  (testing "str-split - uses configured limits"
    ;; Verify that with-regex-limits correctly applies custom limits
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Regex pattern too long"
          (sp/with-regex-limits
            {:max-pattern-length 5}
            #(h/call-base-fn :str-split {:s "test" :sep "longer-pattern"})))))

  (testing "str-split - input length limit applies"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Input string too long"
          (sp/with-regex-limits
            {:max-input-length 10}
            #(h/call-base-fn :str-split {:s "this is a longer string" :sep " "}))))))

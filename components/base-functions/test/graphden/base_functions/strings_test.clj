(ns graphden.base-functions.strings-test
  "Tests for base-functions.strings - string manipulation functions."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.base-functions.strings :as strings]
    [graphden.storage-protocol.config :as config]))


;; === str-fn tests ===

(deftest str-fn-test
  (testing "joins args into string"
    (is (= "hello" ((:impl strings/str-fn) {:args (delay ["hello"])} nil)))
    (is (= "helloworld" ((:impl strings/str-fn) {:args (delay ["hello" "world"])} nil)))
    (is (= "123" ((:impl strings/str-fn) {:args (delay [1 2 3])} nil))))

  (testing "handles empty args"
    (is (= "" ((:impl strings/str-fn) {:args (delay [])} nil))))

  (testing "handles nil in args"
    (is (= "ab" ((:impl strings/str-fn) {:args (delay ["a" nil "b"])} nil)))))


;; === subs-fn tests ===

(deftest subs-fn-test
  (testing "extracts substring with start only"
    (is (= "world" ((:impl strings/subs-fn) {:s (delay "hello world") :start (delay 6)} nil))))

  (testing "extracts substring with start and end"
    (is (= "ell" ((:impl strings/subs-fn) {:s (delay "hello") :start (delay 1) :end (delay 4)} nil))))

  (testing "handles zero start"
    (is (= "hel" ((:impl strings/subs-fn) {:s (delay "hello") :start (delay 0) :end (delay 3)} nil))))

  (testing "handles end at string length"
    (is (= "hello" ((:impl strings/subs-fn) {:s (delay "hello") :start (delay 0) :end (delay 5)} nil))))

  (testing "handles start equals end (empty result)"
    (is (= "" ((:impl strings/subs-fn) {:s (delay "hello") :start (delay 2) :end (delay 2)} nil))))

  (testing "handles nil end (substring to end)"
    (is (= "lo" ((:impl strings/subs-fn) {:s (delay "hello") :start (delay 3) :end (delay nil)} nil))))

  (testing "throws for negative start"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"start index cannot be negative"
          ((:impl strings/subs-fn) {:s (delay "hello") :start (delay -1)} nil))))

  (testing "throws for start out of bounds"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"start index out of bounds"
          ((:impl strings/subs-fn) {:s (delay "hello") :start (delay 10)} nil))))

  (testing "throws for end less than start"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"end index cannot be less than start"
          ((:impl strings/subs-fn) {:s (delay "hello") :start (delay 3) :end (delay 1)} nil))))

  (testing "throws for end out of bounds"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"end index out of bounds"
          ((:impl strings/subs-fn) {:s (delay "hello") :start (delay 0) :end (delay 10)} nil)))))


;; === str-len-fn tests ===

(deftest str-len-fn-test
  (testing "returns length of string"
    (is (= 5 ((:impl strings/str-len-fn) {:s (delay "hello")} nil)))
    (is (zero? ((:impl strings/str-len-fn) {:s (delay "")} nil)))
    (is (= 11 ((:impl strings/str-len-fn) {:s (delay "hello world")} nil))))

  (testing "handles unicode"
    (is (= 4 ((:impl strings/str-len-fn) {:s (delay "тест")} nil)))
    ;; Emojis are surrogate pairs in Java, so they count as 2 chars each
    (is (= 4 ((:impl strings/str-len-fn) {:s (delay "🎉🎊")} nil)))))


;; === str-upper-fn tests ===

(deftest str-upper-fn-test
  (testing "converts to uppercase"
    (is (= "HELLO" ((:impl strings/str-upper-fn) {:s (delay "hello")} nil)))
    (is (= "HELLO WORLD" ((:impl strings/str-upper-fn) {:s (delay "Hello World")} nil))))

  (testing "handles already uppercase"
    (is (= "ABC" ((:impl strings/str-upper-fn) {:s (delay "ABC")} nil))))

  (testing "handles empty string"
    (is (= "" ((:impl strings/str-upper-fn) {:s (delay "")} nil)))))


;; === str-lower-fn tests ===

(deftest str-lower-fn-test
  (testing "converts to lowercase"
    (is (= "hello" ((:impl strings/str-lower-fn) {:s (delay "HELLO")} nil)))
    (is (= "hello world" ((:impl strings/str-lower-fn) {:s (delay "Hello World")} nil))))

  (testing "handles already lowercase"
    (is (= "abc" ((:impl strings/str-lower-fn) {:s (delay "abc")} nil))))

  (testing "handles empty string"
    (is (= "" ((:impl strings/str-lower-fn) {:s (delay "")} nil)))))


;; === str-trim-fn tests ===

(deftest str-trim-fn-test
  (testing "trims whitespace"
    (is (= "hello" ((:impl strings/str-trim-fn) {:s (delay "  hello  ")} nil)))
    (is (= "hello world" ((:impl strings/str-trim-fn) {:s (delay "\t hello world \n")} nil))))

  (testing "handles no whitespace"
    (is (= "hello" ((:impl strings/str-trim-fn) {:s (delay "hello")} nil))))

  (testing "handles all whitespace"
    (is (= "" ((:impl strings/str-trim-fn) {:s (delay "   ")} nil))))

  (testing "handles empty string"
    (is (= "" ((:impl strings/str-trim-fn) {:s (delay "")} nil)))))


;; === str-split-fn tests ===

(deftest str-split-fn-test
  (testing "splits string by separator"
    (is (= ["a" "b" "c"] ((:impl strings/str-split-fn) {:s (delay "a,b,c") :sep (delay ",")} nil)))
    (is (= ["hello" "world"] ((:impl strings/str-split-fn) {:s (delay "hello world") :sep (delay " ")} nil))))

  (testing "handles regex separator"
    (is (= ["a" "b" "c"] ((:impl strings/str-split-fn) {:s (delay "a1b2c") :sep (delay "\\d")} nil))))

  (testing "handles no matches"
    (is (= ["hello"] ((:impl strings/str-split-fn) {:s (delay "hello") :sep (delay ",")} nil))))

  (testing "throws for empty separator"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"separator cannot be empty"
          ((:impl strings/str-split-fn) {:s (delay "hello") :sep (delay "")} nil))))

  (testing "throws for input too long"
    (binding [config/*max-regex-input-length* 10]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Input string too long"
            ((:impl strings/str-split-fn) {:s (delay "hello world this is too long") :sep (delay " ")} nil)))))

  (testing "throws for pattern too long"
    (binding [config/*max-regex-length* 5]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Regex pattern too long"
            ((:impl strings/str-split-fn) {:s (delay "hello") :sep (delay "very-long-pattern")} nil)))))

  (testing "throws for invalid regex syntax"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid regex pattern syntax"
          ((:impl strings/str-split-fn) {:s (delay "hello") :sep (delay "[")} nil)))))


;; === str-join-fn tests ===

(deftest str-join-fn-test
  (testing "joins collection with separator"
    (is (= "a,b,c" ((:impl strings/str-join-fn) {:coll (delay ["a" "b" "c"]) :sep (delay ",")} nil)))
    (is (= "hello world" ((:impl strings/str-join-fn) {:coll (delay ["hello" "world"]) :sep (delay " ")} nil))))

  (testing "handles nil separator (defaults to empty)"
    (is (= "abc" ((:impl strings/str-join-fn) {:coll (delay ["a" "b" "c"]) :sep (delay nil)} nil))))

  (testing "handles empty collection"
    (is (= "" ((:impl strings/str-join-fn) {:coll (delay []) :sep (delay ",")} nil))))

  (testing "handles single element"
    (is (= "hello" ((:impl strings/str-join-fn) {:coll (delay ["hello"]) :sep (delay ",")} nil))))

  (testing "handles numbers in collection"
    (is (= "1-2-3" ((:impl strings/str-join-fn) {:coll (delay [1 2 3]) :sep (delay "-")} nil)))))


;; === safe-compile-regex tests ===

(deftest safe-compile-regex-test
  (testing "compiles valid regex"
    (let [pattern (#'strings/safe-compile-regex "\\d+")]
      (is (instance? java.util.regex.Pattern pattern))))

  (testing "throws for pattern too long"
    (binding [config/*max-regex-length* 5]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Regex pattern too long"
            (#'strings/safe-compile-regex "this-is-a-long-pattern")))))

  (testing "throws for invalid regex syntax"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid regex pattern syntax"
          (#'strings/safe-compile-regex "[")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid regex pattern syntax"
          (#'strings/safe-compile-regex "(unclosed"))))

  ;; Note: Testing regex timeout is difficult because compilation is too fast
  ;; The timeout path in safe-compile-regex handles slow pattern compilation
  ;; but we can't reliably trigger it in tests without blocking for too long.
  ;; The timeout code path is covered by code review rather than unit test.
  )


;; === string-defs map tests ===

(deftest string-defs-test
  (testing "contains all expected functions"
    (is (contains? strings/string-defs :str))
    (is (contains? strings/string-defs :subs))
    (is (contains? strings/string-defs :str-len))
    (is (contains? strings/string-defs :str-upper))
    (is (contains? strings/string-defs :str-lower))
    (is (contains? strings/string-defs :str-trim))
    (is (contains? strings/string-defs :str-split))
    (is (contains? strings/string-defs :str-join)))

  (testing "all defs have required keys"
    (doseq [[fn-name def-map] strings/string-defs]
      (is (contains? def-map :args) (str fn-name " should have :args"))
      (is (contains? def-map :return-type) (str fn-name " should have :return-type"))
      (is (contains? def-map :impl) (str fn-name " should have :impl"))
      (is (fn? (:impl def-map)) (str fn-name " :impl should be a function")))))


;; === Edge cases ===

(deftest string-edge-cases-test
  (testing "subs with unicode"
    (is (= "llo" ((:impl strings/subs-fn) {:s (delay "héllo") :start (delay 2) :end (delay 5)} nil))))

  (testing "split with special regex chars"
    (is (= ["a" "b"] ((:impl strings/str-split-fn) {:s (delay "a.b") :sep (delay "\\.")} nil)))
    (is (= ["a" "b"] ((:impl strings/str-split-fn) {:s (delay "a|b") :sep (delay "\\|")} nil))))

  (testing "join with empty separator"
    (is (= "abc" ((:impl strings/str-join-fn) {:coll (delay ["a" "b" "c"]) :sep (delay "")} nil)))))

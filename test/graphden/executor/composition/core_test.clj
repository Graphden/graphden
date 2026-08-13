(ns graphden.executor.composition.core-test
  "Unit tests for composition core — pure/internal functions that
   don't require storage. Covers the slot/fn-slot/binding model's
   pure helpers (deps, validation, parsing)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [clojure.tools.logging]
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.composition.parsing :as parsing]
    [graphden.executor.composition.validation :as validation]))


;; === valid-identifier? ===

(deftest valid-identifier?-test
  (testing "accepts valid identifiers"
    (is (parsing/valid-identifier? "hello"))
    (is (parsing/valid-identifier? "my-fn"))
    (is (parsing/valid-identifier? "_private"))
    (is (parsing/valid-identifier? "fn123"))
    (is (parsing/valid-identifier? "a"))
    (is (parsing/valid-identifier? "A-B-C"))
    (is (parsing/valid-identifier? "my_fn_name")))

  (testing "rejects identifiers starting with digit"
    (is (not (parsing/valid-identifier? "123abc")))
    (is (not (parsing/valid-identifier? "0test"))))

  (testing "rejects identifiers with special chars"
    (is (not (parsing/valid-identifier? "hello world")))
    (is (not (parsing/valid-identifier? "fn@name")))
    (is (parsing/valid-identifier? "a.b"))
    (is (parsing/valid-identifier? "core.arithmetic.add"))
    (is (not (parsing/valid-identifier? "a/b")))
    (is (not (parsing/valid-identifier? ">")))
    (is (not (parsing/valid-identifier? "+"))))

  (testing "rejects empty and nil"
    (is (not (parsing/valid-identifier? "")))
    (is (not (parsing/valid-identifier? nil))))

  (testing "rejects non-string input"
    (is (not (parsing/valid-identifier? 123)))
    (is (not (parsing/valid-identifier? true)))
    (is (not (parsing/valid-identifier? :keyword)))))


;; === parse-fn-ref ===

(deftest parse-fn-ref-test
  (testing "returns keyword for valid identifiers"
    (is (= :my-fn (parsing/parse-fn-ref :my-fn)))
    (is (= :handler (parsing/parse-fn-ref :handler)))
    (is (= :my-fn-123 (parsing/parse-fn-ref :my-fn-123))))

  (testing "returns nil for non-keyword values"
    (is (nil? (parsing/parse-fn-ref "string")))
    (is (nil? (parsing/parse-fn-ref 42)))
    (is (nil? (parsing/parse-fn-ref nil)))
    (is (nil? (parsing/parse-fn-ref [1 2 3])))
    (is (nil? (parsing/parse-fn-ref {:a 1}))))

  (testing "returns nil for keywords with invalid names"
    (is (nil? (parsing/parse-fn-ref :>)))
    (is (nil? (parsing/parse-fn-ref :+)))
    (is (nil? (parsing/parse-fn-ref :123-starts-with-digit)))))


;; === validate-fn-def! ===

(deftest validate-fn-def!-test
  (testing "accepts valid composed fn-def"
    (is (nil? (#'validation/validate-fn-def! {:name :my-fn :parent :base})))
    (is (nil? (#'validation/validate-fn-def! {:name :my-fn :parent :base :args {:a 1}}))))

  (testing "accepts type-row fn-defs"
    (is (nil? (#'validation/validate-fn-def! {:name :rec :type {:k :int}})))
    (is (nil? (#'validation/validate-fn-def! {:name :ref :refine {:base :int :constraint [:> 0]}})))
    (is (nil? (#'validation/validate-fn-def! {:name :lst :list :int}))))

  (testing "accepts base-fn (only :args, no role markers)"
    (is (nil? (#'validation/validate-fn-def! {:name :my-fn :args {:a 1}}))))

  (testing "throws on missing name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must have :name"
          (#'validation/validate-fn-def! {:parent :base}))))

  (testing "throws on non-keyword name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":name must be a keyword"
          (#'validation/validate-fn-def! {:name "string" :parent :base}))))

  (testing "throws on conflicting role markers"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"conflicting role markers"
          (#'validation/validate-fn-def! {:name :bad :type {:k :int} :refine {:base :int}}))))

  (testing "throws on non-map :type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":type must be a map"
          (#'validation/validate-fn-def! {:name :bad :type :int}))))

  (testing "throws on non-map :args"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":args must be a map"
          (#'validation/validate-fn-def! {:name :my-fn :parent :base :args [1 2]})))))


;; === validate-all-defs! ===

(deftest validate-all-defs!-test
  (testing "accepts valid definitions"
    (is (nil? (validation/validate-all-defs!
                [{:name :a :parent :base}
                 {:name :b :parent :base}]))))

  (testing "throws on non-sequential input"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a vector"
          (validation/validate-all-defs! {:name :a :parent :base}))))

  (testing "throws on duplicate names"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate"
          (validation/validate-all-defs!
            [{:name :a :parent :base}
             {:name :a :parent :other}]))))

  (testing "accepts empty vector"
    (is (nil? (validation/validate-all-defs! [])))))


;; === topological-sort ===

(deftest topological-sort-unit-test
  (testing "sorts linear dependency chain"
    (let [fn-defs [{:name :a :parent :base :args {:x :b}}
                   {:name :b :parent :base :args {:x :c}}
                   {:name :c :parent :base}]
          sorted (deps/topological-sort fn-defs)
          names (mapv :name sorted)
          pos (into {} (map-indexed (fn [i n] [n i])) names)]
      (is (< (pos :c) (pos :b)))
      (is (< (pos :b) (pos :a)))))

  (testing "preserves order for independent fns"
    (let [fn-defs [{:name :x :parent :base}
                   {:name :y :parent :base}
                   {:name :z :parent :base}]
          sorted (deps/topological-sort fn-defs)]
      (is (= 3 (count sorted)))
      (is (= #{:x :y :z} (set (map :name sorted))))))

  (testing "handles parent as dependency"
    (let [fn-defs [{:name :child :parent :parent-fn :args {:a 1}}
                   {:name :parent-fn :parent :base}]
          sorted (deps/topological-sort fn-defs)
          names (mapv :name sorted)
          pos (into {} (map-indexed (fn [i n] [n i])) names)]
      (is (< (pos :parent-fn) (pos :child)))))

  (testing "detects two-node cycle"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Circular"
          (deps/topological-sort
            [{:name :a :parent :base :args {:x :b}}
             {:name :b :parent :base :args {:x :a}}]))))

  (testing "single element"
    (let [sorted (deps/topological-sort [{:name :solo :parent :base}])]
      (is (= [:solo] (mapv :name sorted))))))

(ns graphden.packages.records.types-test
  "Tests for `graphden.packages.records.types` — EDN type-reference
   resolution and inline `[:fn args ret]` synthesis. Every fn here is
   pure (no storage), so no fixture is needed."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.types :as types]))


;; ============================================================================
;; type-spec-map?
;; ============================================================================

(deftest type-spec-map?-test
  (testing "the loader's expanded {:type T …} shape is a type-spec map"
    (is (true? (types/type-spec-map? {:type :int})))
    (is (true? (types/type-spec-map? {:type :int :required false}))))

  (testing "a record-type composite (no :type key) and non-maps are not"
    (is (false? (types/type-spec-map? {:foo :int :bar :text})))
    (is (false? (types/type-spec-map? {})))
    (is (false? (types/type-spec-map? :int)))
    (is (false? (types/type-spec-map? nil)))))


;; ============================================================================
;; resolve-type-ref
;; ============================================================================

(deftest resolve-type-ref-primitive-test
  (testing "a primitive keyword resolves to that primitive's fn-id"
    (is (= (ids/primitive-fn-id :int) (types/resolve-type-ref :int {})))
    (is (= (ids/primitive-fn-id :text) (types/resolve-type-ref :text {})))))


(deftest resolve-type-ref-named-test
  (testing "a named keyword resolves through name->id"
    (let [my-id (random-uuid)]
      (is (= my-id (types/resolve-type-ref :my-type {:my-type my-id})))))

  (testing "an unknown keyword throws :records/unknown-type-ref"
    (let [ex (try (types/resolve-type-ref :no-such-type {})
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :records/unknown-type-ref (:type (ex-data ex)))))))


(deftest resolve-type-ref-symbol-test
  (testing "a type-var symbol degrades to the :any primitive"
    (is (= (ids/primitive-fn-id :any) (types/resolve-type-ref 'a {})))))


(deftest resolve-type-ref-map-test
  (testing "an expanded {:type T …} spec recurses onto T, dropping metadata"
    (is (= (ids/primitive-fn-id :int)
           (types/resolve-type-ref {:type :int :required false} {})))
    (let [my-id (random-uuid)]
      (is (= my-id (types/resolve-type-ref {:type :my-type} {:my-type my-id})))))

  (testing "an inline composite map resolves to its shape-hashed anonymous id"
    (is (= (ids/anonymous-fn-id (ids/shape-hash {:k :int}))
           (types/resolve-type-ref {:k :int} {})))))


(deftest resolve-type-ref-structural-test
  (testing "[:fn …] → an anonymous id deterministic in the printed shape"
    (let [shape [:fn {:a :int} :int]]
      (is (= (ids/anonymous-fn-id (ids/digest-hex "SHA-1" (pr-str shape)))
             (types/resolve-type-ref shape {})))))

  (testing "[:list T] degrades to :sequence; [:union …] degrades to :any"
    (is (= (ids/primitive-fn-id :sequence) (types/resolve-type-ref [:list :int] {})))
    (is (= (ids/primitive-fn-id :any) (types/resolve-type-ref [:union :int :text] {}))))

  (testing "[:refine B C] recurses onto the base type"
    (is (= (ids/primitive-fn-id :int)
           (types/resolve-type-ref [:refine :int [:> 0]] {}))))

  (testing "an unknown structural head / unsupported shape throws"
    (doseq [bad [[:bogus :x] 42]]
      (let [ex (try (types/resolve-type-ref bad {})
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :records/unsupported-type-ref (:type (ex-data ex))))))))


;; ============================================================================
;; inline-fn-type-rows-from-form
;; ============================================================================

(deftest inline-fn-type-rows-from-form-test
  (testing "a bare [:fn args ret] produces one anonymous fn-row"
    (let [shape [:fn {:request :jsonb} :int]
          rows  (types/inline-fn-type-rows-from-form shape)
          row   (first rows)]
      (is (= 1 (count rows)))
      (is (= :fn (:kind row)))
      (is (nil? (:name row)))
      (is (= shape (:constraint row)))
      (is (= (ids/anonymous-fn-id (ids/digest-hex "SHA-1" (pr-str shape)))
             (:id row)))
      ;; The id matches what resolve-type-ref hands back for the shape.
      (is (= (:id row) (types/resolve-type-ref shape {})))))

  (testing "a fn-type nested in args / ret yields its own row too"
    (is (= 2 (count (types/inline-fn-type-rows-from-form
                      [:fn {:cb [:fn {} :int]} :int]))))
    (is (= 2 (count (types/inline-fn-type-rows-from-form
                      [:fn {} [:fn {} :text]])))))

  (testing "fn-types buried in compound forms are reached"
    (is (= 1 (count (types/inline-fn-type-rows-from-form [:list [:fn {} :int]]))))
    (is (= 1 (count (types/inline-fn-type-rows-from-form
                      [:refine [:fn {} :int] [:> 0]]))))
    (is (= 1 (count (types/inline-fn-type-rows-from-form
                      [:union [:fn {} :int] :text]))))
    (is (= 1 (count (types/inline-fn-type-rows-from-form
                      {:type [:fn {} :int]}))))
    (is (= 1 (count (types/inline-fn-type-rows-from-form
                      {:field-a [:fn {} :int] :field-b :text})))))

  (testing "a form with no fn-type → nothing"
    (is (empty? (types/inline-fn-type-rows-from-form :int)))
    (is (empty? (types/inline-fn-type-rows-from-form [:list :int])))
    (is (empty? (types/inline-fn-type-rows-from-form {:a :int})))))


;; ============================================================================
;; inline-fn-type-rows-from-fn-def
;; ============================================================================

(deftest inline-fn-type-rows-from-fn-def-test
  (testing "fn-types are collected from :args, :return-type and :fn-type"
    (is (= 1 (count (types/inline-fn-type-rows-from-fn-def
                      {:args {:f [:fn {} :int]}}))))
    (is (= 1 (count (types/inline-fn-type-rows-from-fn-def
                      {:return-type [:fn {:r :jsonb} :int]}))))
    (is (= 1 (count (types/inline-fn-type-rows-from-fn-def
                      {:fn-type [{:x :int} :int]})))))

  (testing "the same shape mentioned twice is deduplicated to one row"
    (is (= 1 (count (types/inline-fn-type-rows-from-fn-def
                      {:args {:f [:fn {} :int]
                              :g [:fn {} :int]}})))))

  (testing "distinct shapes each get their own row"
    (is (= 2 (count (types/inline-fn-type-rows-from-fn-def
                      {:args {:f [:fn {} :int]
                              :g [:fn {} :text]}})))))

  (testing "a fn-def with no inline fn-types → empty"
    (is (empty? (types/inline-fn-type-rows-from-fn-def
                  {:args {:a :int :b :text} :return-type :int})))))

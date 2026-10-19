(ns graphden.packages.records.types-test
  "Tests for `graphden.packages.records.types` — EDN type-reference
   resolution and inline `[:fn args ret]` synthesis. Every fn here is
   pure (no storage), so no fixture is needed."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
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

  (testing "[:map K V] is jsonb-shaped at rest; [:tuple …] is a sequence; :never is :any"
    (is (= (ids/primitive-fn-id :jsonb) (types/resolve-type-ref [:map :keyword :int] {})))
    (is (= (ids/primitive-fn-id :sequence) (types/resolve-type-ref [:tuple :text :int] {})))
    (is (= (ids/primitive-fn-id :any) (types/resolve-type-ref :never {}))))

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


(deftest named-callable-declarations-preserve-effects-and-execution
  (doseq [[declaration expected]
          [[[{:x :int} :int] [:fn {:x :int} :int :any :unknown]]
           [[{} :int #{:db}] [:fn {} :int #{:db} :unknown]]
           [[{} :int #{} #{}] [:fn {} :int #{} #{}]]
           [[{} :int #{} #{:browser-main}] [:fn {} :int #{} #{:browser-main}]]
           [[{} :int :any [:execution-var 'E]] [:fn {} :int :any [:execution-var 'E]]]]]
    (let [definition {:name :callable :namespace "wire" :fn-type declaration}
          row (first (filter #(= (ids/fn-id "wire" :callable) (:id %))
                             (parse/parse-module [definition])))]
      (is (= expected (types/fn-type-declaration declaration)))
      (is (= (if (= 2 (count declaration)) (pop expected) expected) (:constraint row)))
      (is (= (ids/fn-id "wire" :callable) (:id row))))))


(deftest callable-row-and-slot-identities-retain-the-source-shape
  (doseq [shape [[:fn {:x :int} :int]
                 [:fn {:x :int} :int #{:db}]
                 [:fn {:x :int} :int #{} #{:jvm-server :browser-main}]
                 [:fn {:x :int} :int :any [:execution-var 'E]]]]
    (let [id (types/resolve-type-ref shape {})
          source {:name :hof :namespace "wire" :args {:callback {:type shape}}
                  :return-type :int}
          rows (parse/parse-module [source])
          type-row (first (filter #(= id (:id %)) rows))
          slot (first (filter #(and (= :slot (:kind %)) (= "callback" (:name %))) rows))]
      (is (= shape (:constraint type-row)))
      (when (< (count shape) 5)
        (is (= (ids/anonymous-fn-id (ids/digest-hex "SHA-1" (pr-str shape))) id)))
      (is (= id (types/resolve-type-ref shape {})))
      (is (= id (:type-fn-id slot)))
      (is (= (ids/slot-id (ids/fn-id "wire" :hof) :callback) (:id slot))))))


(deftest callable-grammar-errors-fail-before-emitting-rows
  (doseq [bad [[:fn {}] [:fn {} :int #{} #{:worker}]
               [:fn {} :int #{} [:execution-var :E]]
               [:fn {} :int [] :unknown] [:fn :bad :int #{} :unknown]]]
    (is (thrown? clojure.lang.ExceptionInfo (types/resolve-type-ref bad {})))
    (is (thrown? clojure.lang.ExceptionInfo (types/inline-fn-type-rows-from-form bad))))
  (is (thrown? clojure.lang.ExceptionInfo
        (types/fn-type-declaration [{} :int #{} :unknown :extra]))))


(deftest execution-collection-references-retain-their-outer-structure
  (doseq [execution [:unknown #{} #{:browser-main} [:execution-var 'E]]
          shape [[:map :keyword [:fn {} :int #{:db} execution]]
                 [:list [:fn {} :int :any execution]]
                 [:list [:map :keyword [:fn {} :int #{} execution]]]]]
    (let [id (ids/anonymous-fn-id (ids/digest-hex "SHA-1" (pr-str shape)))
          source {:name :contracts :namespace "wire" :args {:handlers {:type shape}}}
          rows (parse/parse-module [source])
          type-row (first (filter #(= id (:id %)) rows))
          slot (first (filter #(= :slot (:kind %)) rows))]
      (is (= id (types/resolve-type-ref shape {})))
      (is (= shape (:constraint type-row)))
      (is (= id (:type-fn-id slot)))
      (is (= (ids/slot-id (ids/fn-id "wire" :contracts) :handlers) (:id slot))))))


(deftest legacy-collection-reimport-retains-historical-slot-identities-and-type-fks
  (doseq [[shape primitive] [[[:list :int] :sequence]
                             [[:map :keyword :int] :jsonb]
                             [[:list [:fn {} :int]] :sequence]
                             [[:map :keyword [:fn {} :int #{:db}]] :jsonb]]]
    (let [source [{:name :legacy :namespace "wire" :args {:handlers {:type shape}}}]
          rows (parse/parse-module source)
          repeated (parse/parse-module source)
          slot (first (filter #(= :slot (:kind %)) rows))]
      (is (= rows repeated))
      (is (= (ids/primitive-fn-id primitive) (:type-fn-id slot)))
      (is (= (ids/slot-id (ids/fn-id "wire" :legacy) :handlers) (:id slot))))))


(deftest canonical5-collection-and-nested-callable-identities-ignore-map-order
  (let [left [:fn (array-map :left :int :right :text)
              {:ok :bool :data [:map :keyword [:fn (array-map :x :int :y :text) :int #{} :unknown]]}
              #{:db :network} #{:browser-main :jvm-server}]
        right [:fn (array-map :right :text :left :int)
               (array-map :data [:map :keyword [:fn (array-map :y :text :x :int) :int #{} :unknown]] :ok :bool)
               (sorted-set :network :db) (sorted-set :jvm-server :browser-main)]
        module (fn [callback]
                 [{:name :stable :namespace "user"
                   :args {:handlers {:type [:list [:map :keyword callback]]}}}])
        rows-left (parse/parse-module (module left))
        rows-right (parse/parse-module (module right))]
    (is (= left right))
    (is (= (types/resolve-type-ref left {}) (types/resolve-type-ref right {})))
    (is (= (set rows-left) (set rows-right)))
    (is (= (set (map :id rows-left)) (set (map :id rows-right))))))


(deftest legacy-callable-identity-retains-the-original-printed-map-order
  (doseq [args [(array-map :z :int :a :text) (array-map :a :text :z :int)]
          suffix [[] [#{:db}]]]
    (let [shape (into [:fn args :int] suffix)]
      (is (= (ids/anonymous-fn-id (ids/digest-hex "SHA-1" (pr-str shape)))
             (types/resolve-type-ref shape {}))))))

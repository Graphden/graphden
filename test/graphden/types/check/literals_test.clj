(ns graphden.types.check.literals-test
  "Unit tests for the pure literal/refinement surface in
   `graphden.types.check.literals` — the shared 'does this value
   satisfy this constraint?' vocabulary used by the type-checker,
   crud validation and the editor's mismatch/provenance popovers.

   `literal-satisfies-refinement?` is exercised heavily from
   `graphden.types.check-test`; this ns covers the rest of the public
   surface: `classify-literal` structural inference,
   `diff-value-against-type` leaf-level mismatch localisation,
   `fn-type-bound-effects` / `closed-enum-of` editor projections, and
   `constraint-compatible-with-base?` sync-time op/base compatibility.
   All pure — no storage, no registry (aliases go through the
   `*type-aliases-override*` test binding)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.types.check.literals :as lit]
    [graphden.types.core :as types]))


;; -----------------------------------------------------------------------------
;; classify-literal — structural inference for literal values

(deftest classify-literal-primitives-test
  (testing "scalar literals classify to their primitive type"
    (is (= :null (lit/classify-literal nil)))
    (is (= :bool (lit/classify-literal true)))
    (is (= :bool (lit/classify-literal false)))
    (is (= :int (lit/classify-literal 42)))
    (is (= :float (lit/classify-literal 3.5)))
    (is (= :text (lit/classify-literal "hello")))
    (is (= :keyword (lit/classify-literal :ok)))
    (is (= :uuid (lit/classify-literal (random-uuid)))))
  (testing "unrecognised shapes return nil (caller falls back to :any)"
    (is (nil? (lit/classify-literal #{1 2})))
    (is (nil? (lit/classify-literal (fn []))))
    (is (nil? (lit/classify-literal '(1 2))) "lists are not literal vectors")))


(deftest classify-literal-maps-test
  (testing "keyword-keyed map classifies into a structural record type"
    (is (= {:a :int :b :text} (lit/classify-literal {:a 1 :b "x"}))))
  (testing "record classification recurses into nested values"
    (is (= {:a :int :b {:c :text}}
           (lit/classify-literal {:a 1 :b {:c "x"}}))))
  (testing "an unclassifiable field value becomes :any, not nil"
    (is (= {:a :any} (lit/classify-literal {:a #{1}}))))
  (testing "string-keyed map with homogeneous values → [:map :text V]"
    (is (= [:map :text :text]
           (lit/classify-literal {"Content-Type" "text/html"
                                  "X-Frame-Options" "DENY"}))))
  (testing "string-keyed map with mixed value types → :jsonb"
    (is (= :jsonb (lit/classify-literal {"a" 1 "b" "x"}))))
  (testing "mixed keyword/string keys → :jsonb (no shape evidence)"
    (is (= :jsonb (lit/classify-literal {:a 1 "b" 2}))))
  (testing "empty map → :empty-map"
    (is (= :empty-map (lit/classify-literal {})))))


(deftest classify-literal-vectors-test
  (testing "homogeneous vector → [:list T]"
    (is (= [:list :int] (lit/classify-literal [1 2 3])))
    (is (= [:list :text] (lit/classify-literal ["a" "b"]))))
  (testing "heterogeneous / empty vectors widen the element to :any"
    (is (= [:list :any] (lit/classify-literal [1 "x"])))
    (is (= [:list :any] (lit/classify-literal []))))
  (testing "vector classification recurses into nested structures"
    (is (= [:list [:list :int]] (lit/classify-literal [[1] [2 3]])))
    (is (= [:list {:a :int}] (lit/classify-literal [{:a 1} {:a 2}])))))


;; -----------------------------------------------------------------------------
;; diff-value-against-type — leaf-level mismatch localisation

(deftest diff-primitive-test
  (testing "satisfied primitive expectation → no leaves"
    (is (= [] (lit/diff-value-against-type 5 :int)))
    (is (= [] (lit/diff-value-against-type "x" :text)))
    (is (= [] (lit/diff-value-against-type 5 :numeric))
        ":int ⊆ :numeric via the numeric ladder"))
  (testing "mismatched primitive → single root-path leaf"
    (is (= [{:path "" :expected :int :actual :text}]
           (lit/diff-value-against-type "x" :int))))
  (testing ":any / :jsonb constrain nothing"
    (is (= [] (lit/diff-value-against-type "anything" :any)))
    (is (= [] (lit/diff-value-against-type {:a [1 "x"]} :jsonb))))
  (testing "an unclassifiable value is accepted conservatively"
    (is (= [] (lit/diff-value-against-type #{1} :int)))))


(deftest diff-record-test
  (testing "matching record literal → no leaves; extra keys are ignored"
    (is (= [] (lit/diff-value-against-type {:a 1 :b "x"} {:a :int :b :text})))
    (is (= [] (lit/diff-value-against-type {:a 1 :extra "ok"} {:a :int}))))
  (testing "missing field → leaf at the field path with actual \"missing\""
    (is (= [{:path ".b" :expected :text :actual "missing"}]
           (lit/diff-value-against-type {:a 1} {:a :int :b :text}))))
  (testing "wrong field type → leaf at the field path"
    (is (= [{:path ".a" :expected :int :actual :text}]
           (lit/diff-value-against-type {:a "s"} {:a :int}))))
  (testing "nested record mismatch carries the full dotted path"
    (is (= [{:path ".outer.inner" :expected :int :actual :text}]
           (lit/diff-value-against-type {:outer {:inner "s"}}
                                        {:outer {:inner :int}}))))
  (testing "non-map value against a record type → root leaf"
    (is (= [{:path "" :expected {:a :int} :actual :int}]
           (lit/diff-value-against-type 5 {:a :int})))))


(deftest diff-list-and-map-test
  (testing "list elements diff with [i] index paths"
    (is (= [] (lit/diff-value-against-type [1 2 3] [:list :int])))
    (is (= [{:path "[1]" :expected :int :actual :text}]
           (lit/diff-value-against-type [1 "x" 2] [:list :int]))))
  (testing "non-sequential value against [:list T] → root leaf"
    (is (= [{:path "" :expected [:list :int] :actual :int}]
           (lit/diff-value-against-type 5 [:list :int]))))
  (testing "[:map K V] checks each value under its .key path"
    (is (= [] (lit/diff-value-against-type {"a" 1 "b" 2} [:map :text :int])))
    (is (= [{:path ".a" :expected :int :actual :text}]
           (lit/diff-value-against-type {"a" "x"} [:map :text :int]))))
  (testing "non-map value against [:map K V] → root leaf"
    (is (= 1 (count (lit/diff-value-against-type [1 2] [:map :text :int]))))))


(deftest diff-tuple-test
  (testing "matching tuple → no leaves"
    (is (= [] (lit/diff-value-against-type [1 "a"] [:tuple :int :text]))))
  (testing "arity mismatch → single leaf naming the actual length"
    (is (= [{:path "" :expected [:tuple :int :text] :actual "tuple of length 1"}]
           (lit/diff-value-against-type [1] [:tuple :int :text]))))
  (testing "element mismatch → leaf at the element index"
    (is (= [{:path "[1]" :expected :text :actual :int}]
           (lit/diff-value-against-type [1 2] [:tuple :int :text]))))
  (testing "non-sequential value → root leaf"
    (is (= [{:path "" :expected [:tuple :int] :actual :int}]
           (lit/diff-value-against-type 7 [:tuple :int])))))


(deftest diff-refine-test
  (testing "value satisfying base + constraint → no leaves"
    (is (= [] (lit/diff-value-against-type 15 [:refine :int [:> 10]]))))
  (testing "base violation reports the BASE leaf, not the refinement"
    (is (= [{:path "" :expected :int :actual :text}]
           (lit/diff-value-against-type "x" [:refine :int [:> 0]]))))
  (testing "constraint violation → leaf with the refined expected type"
    (is (= [{:path "" :expected [:refine :int [:> 10]] :actual :int}]
           (lit/diff-value-against-type 5 [:refine :int [:> 10]]))))
  (testing "an undecidable constraint is accepted (defer to runtime)"
    (is (= [] (lit/diff-value-against-type 5 [:refine :int [:frobnicate 3]])))))


(deftest diff-union-test
  (testing "any branch accepting the value → no leaves"
    (is (= [] (lit/diff-value-against-type 5 [:union :int :text])))
    (is (= [] (lit/diff-value-against-type "x" [:union :int :text]))))
  (testing "all branches failing → the best-near-miss branch's leaves"
    ;; Which branch wins a tie depends on union normalisation order —
    ;; assert the shape, not the winner.
    (let [leaves (lit/diff-value-against-type :kw [:union :int :bool])]
      (is (= 1 (count leaves)))
      (is (= "" (:path (first leaves))))
      (is (= :keyword (:actual (first leaves))))
      (is (contains? #{:int :bool} (:expected (first leaves))))))
  (testing "best-near-miss picks the branch with the FEWEST leaves"
    (let [leaves (lit/diff-value-against-type
                   {:a 1 :b "x"}
                   [:union {:a :text :b :int} {:a :int :b :int}])]
      (is (= [{:path ".b" :expected :int :actual :text}] leaves)
          "the 1-mismatch record branch beats the 2-mismatch one"))))


;; -----------------------------------------------------------------------------
;; fn-type-bound-effects — slot-level effect-bound projection

(deftest fn-type-bound-effects-test
  (testing "a concrete effect SET renders as sorted lower-case strings"
    (is (= ["db" "net"] (lit/fn-type-bound-effects [:fn {} :any #{:net :db}]))))
  (testing "a sequential effect list keeps authored order"
    (is (= ["net" "db"] (lit/fn-type-bound-effects [:fn {} :any [:net :db]]))))
  (testing ":any-shaped effect constraints suppress the section"
    (is (nil? (lit/fn-type-bound-effects [:fn {} :any :any])))
    (is (nil? (lit/fn-type-bound-effects [:fn {} :any "any"]))))
  (testing "3-arity fn types (no effect slot) → nil"
    (is (nil? (lit/fn-type-bound-effects [:fn {} :any]))))
  (testing "non-fn shapes → nil"
    (is (nil? (lit/fn-type-bound-effects :int)))
    (is (nil? (lit/fn-type-bound-effects [:list :int])))
    (is (nil? (lit/fn-type-bound-effects nil))))
  (testing "a scalar (non-collection) effect entry → nil"
    (is (nil? (lit/fn-type-bound-effects [:fn {} :any :db])))))


;; -----------------------------------------------------------------------------
;; closed-enum-of — closed-enum refinement projection

(deftest closed-enum-of-test
  (testing "keyword-based enum: members sorted and colon-prefixed"
    (is (= {:base :keyword
            :members [{:value ":a" :label ":a"}
                      {:value ":b" :label ":b"}]}
           (lit/closed-enum-of [:refine :keyword [:in [:b :a]]]))))
  (testing "text-based enum keeps members verbatim"
    (is (= {:base :text
            :members [{:value "get" :label "get"}
                      {:value "post" :label "post"}]}
           (lit/closed-enum-of [:refine :text [:in ["post" "get"]]]))))
  (testing "non-closed-enum shapes suppress the section"
    (is (nil? (lit/closed-enum-of :int)))
    (is (nil? (lit/closed-enum-of [:refine :int [:> 0]])))
    (is (nil? (lit/closed-enum-of [:list :keyword])))
    (is (nil? (lit/closed-enum-of nil))))
  (testing "a registered alias resolves before the shape check"
    (binding [types/*type-aliases-override*
              (atom {:http-verb [:refine :keyword [:in [:get :post]]]})]
      (is (= {:base :keyword
              :members [{:value ":get" :label ":get"}
                        {:value ":post" :label ":post"}]}
             (lit/closed-enum-of :http-verb))))))


;; -----------------------------------------------------------------------------
;; constraint-compatible-with-base? — sync-time op/base compatibility

(deftest constraint-compatible-numeric-test
  (testing "ordering + membership + regex ops are all legal on numerics"
    (is (true? (lit/constraint-compatible-with-base? :int [:> 0])))
    (is (true? (lit/constraint-compatible-with-base? :numeric [:<= 100])))
    (is (true? (lit/constraint-compatible-with-base? :float [:in #{1.0 2.0}])))
    (is (true? (lit/constraint-compatible-with-base? :int [:matches "\\d+"])))))


(deftest constraint-compatible-text-test
  (testing "text supports equality/membership/regex, but NOT ordering"
    (is (true? (lit/constraint-compatible-with-base? :text [:matches "^a"])))
    (is (true? (lit/constraint-compatible-with-base? :text [:= "x"])))
    (is (true? (lit/constraint-compatible-with-base? :text [:in ["a" "b"]])))
    (is (false? (lit/constraint-compatible-with-base? :text [:>= 0])))
    (is (false? (lit/constraint-compatible-with-base? :text [:< "z"])))))


(deftest constraint-compatible-equality-only-bases-test
  (testing "bool/keyword/uuid/timestamptz permit only equality-shaped ops"
    (is (true? (lit/constraint-compatible-with-base? :bool [:= true])))
    (is (false? (lit/constraint-compatible-with-base? :bool [:< 5])))
    (is (true? (lit/constraint-compatible-with-base? :keyword [:in [:a :b]])))
    (is (false? (lit/constraint-compatible-with-base? :keyword [:matches "a"])))
    (is (true? (lit/constraint-compatible-with-base? :uuid [:not= nil])))
    (is (false? (lit/constraint-compatible-with-base? :timestamptz [:< 5])))))


(deftest constraint-compatible-compound-test
  (testing ":and/:or require EVERY atomic op to fit the base"
    (is (true? (lit/constraint-compatible-with-base?
                 :int [:and [:> 0] [:< 10]])))
    (is (false? (lit/constraint-compatible-with-base?
                  :text [:and [:matches "x"] [:> 0]])))
    (is (false? (lit/constraint-compatible-with-base?
                  :text [:or [:= "a"] [:>= 1]])))
    (is (true? (lit/constraint-compatible-with-base?
                 :int [:and [:or [:> 0] [:in #{1}]] [:matches "x"]]))
        "nested compounds recurse"))
  (testing "an empty :and is vacuously compatible"
    (is (true? (lit/constraint-compatible-with-base? :text [:and])))))


(deftest constraint-compatible-defensive-defaults-test
  (testing "an unmodelled base type permits every op (no false rejection)"
    (is (true? (lit/constraint-compatible-with-base? :custom-type-row [:> 0])))
    (is (true? (lit/constraint-compatible-with-base? :custom-type-row [:matches "x"]))))
  (testing "a nil base rejects every atomic op"
    (is (false? (lit/constraint-compatible-with-base? nil [:> 0])))
    (is (false? (lit/constraint-compatible-with-base? nil [:= 1]))))
  (testing "a non-vector constraint is out of scope here → compatible"
    (is (true? (lit/constraint-compatible-with-base? :int nil)))
    (is (true? (lit/constraint-compatible-with-base? nil "not-a-constraint")))))

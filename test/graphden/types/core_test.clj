(ns graphden.types.core-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [clojure.tools.logging]
    [graphden.types.core :as t]))


;; Built-in refinements are now loaded from the package loader's
;; `aliases.edn` at runtime; unit tests that don't go through the
;; full package boot register their own. Each test gets a clean
;; alias registry, then the built-ins are seeded.
(use-fixtures :each
  (fn [test-fn]
    (t/clear-aliases!)
    (t/register-type-alias! :positive-int     [:refine :int     [:> 0]])
    (t/register-type-alias! :non-negative-int [:refine :int     [:>= 0]])
    (t/register-type-alias! :negative-int     [:refine :int     [:< 0]])
    (t/register-type-alias! :non-empty-text   [:refine :text    [:not= ""]])
    (t/register-type-alias! :positive-numeric [:refine :numeric [:> 0]])
    (test-fn)))


;; -----------------------------------------------------------------------------
;; Predicates / well-formed

(deftest predicates-test
  (is (t/primitive? :int))
  (is (t/primitive? :any))
  (is (not (t/primitive? :nope)))
  (is (t/type-var? 'a))
  (is (not (t/type-var? :a)))
  (is (t/fn-type? [:fn {:x :int} :int]))
  (is (not (t/fn-type? [:fn {:x :int}])))
  (is (t/list-type? [:list :int]))
  (is (t/record-type? {:a :int :b :text}))
  (is (not (t/record-type? [:list :int]))))


(deftest well-formed-test
  (is (t/well-formed? :int))
  (is (t/well-formed? 'a))
  (is (t/well-formed? [:list 'a]))
  (is (t/well-formed? [:fn {:x 'a :y :int} 'a]))
  (is (t/well-formed? {:name :text :age :int}))
  (is (t/well-formed? [:fn {:f [:fn {:x 'a} 'b]
                            :coll [:list 'a]}
                       [:list 'b]]))
  (is (not (t/well-formed? :nope)))
  (is (not (t/well-formed? [:fn :not-a-map :int])))
  (is (not (t/well-formed? [:fn {:x :nope} :int]))))


;; -----------------------------------------------------------------------------
;; Subtyping

(deftest subtype-primitives-test
  (is (t/subtype? :int :int))
  (is (t/subtype? :int :any))
  (is (t/subtype? :int :jsonb))
  (is (not (t/subtype? :any :int)))
  (is (not (t/subtype? :jsonb :int)))
  (is (not (t/subtype? :int :text))))


(deftest primitive-numeric-hierarchy-test
  (testing ":int and :float are subtypes of :numeric"
    (is (t/subtype? :int :numeric))
    (is (t/subtype? :float :numeric))
    (is (t/subtype? :numeric :numeric))
    (is (not (t/subtype? :numeric :int)))
    (is (not (t/subtype? :numeric :float))))
  (testing "no implicit conversion across non-numeric primitives"
    (is (not (t/subtype? :int :text)))
    (is (not (t/subtype? :text :numeric)))
    (is (not (t/subtype? :bool :int)))))


(deftest coarse-lub-test
  (testing "empty input → :any (no information to LUB)"
    (is (= :any (t/coarse-lub []))))
  (testing "single-element collections → that element verbatim"
    (is (= :int  (t/coarse-lub [:int])))
    (is (= :text (t/coarse-lub [:text])))
    (is (= [:list :int] (t/coarse-lub [[:list :int]]))))
  (testing "all-equal collections → that element"
    (is (= :int  (t/coarse-lub [:int :int :int])))
    (is (= :bool (t/coarse-lub [:bool :bool]))))
  (testing "heterogeneous collections → :any (no precise join attempted)"
    ;; Coarse-lub is intentionally conservative — even :int vs :float,
    ;; both numeric, degrades to :any. Callers that need precision
    ;; should use a structural rule (e.g. union or numeric narrowing).
    (is (= :any (t/coarse-lub [:int :text])))
    (is (= :any (t/coarse-lub [:int :float]))
        ":int and :float share :numeric super but coarse-lub is set-equality based")
    (is (= :any (t/coarse-lub [:int :text :bool]))))
  (testing "structural types compared by value equality"
    (is (= [:list :int] (t/coarse-lub [[:list :int] [:list :int]])))
    (is (= :any (t/coarse-lub [[:list :int] [:list :text]])))))


(deftest type-aliases-test
  (testing "built-in refinements registered"
    (is (= [:refine :int [:> 0]]
           (t/resolve-alias :positive-int)))
    (is (= [:refine :int [:>= 0]]
           (t/resolve-alias :non-negative-int)))
    (is (= [:refine :text [:not= ""]]
           (t/resolve-alias :non-empty-text))))
  (testing "primitives pass through unchanged"
    (is (= :int  (t/resolve-alias :int)))
    (is (= :text (t/resolve-alias :text))))
  (testing "unknown keywords pass through (caught later)"
    (is (= :nope (t/resolve-alias :nope))))
  (testing "compound types pass through (no top-level deep recursion)"
    (is (= [:list :int] (t/resolve-alias [:list :int]))))
  (testing "register-type-alias! refuses primitive names"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"shadows a primitive"
          (t/register-type-alias! :int [:refine :int [:> 0]]))))
  (testing "register-type-alias! refuses malformed body"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"not well-formed"
          (t/register-type-alias! :weird [:nope :int])))))


(deftest refinement-constraint-inclusion-test
  ;; Pre-fix, refinement subtype required STRUCTURAL EQUALITY of
  ;; constraints — semantically-included pairs like `:positive-int ⊆
  ;; :non-negative-int` (`[:> 0] ⊆ [:>= 0]`) returned false. Now
  ;; `constraint-implies?` handles the common numeric-comparison
  ;; shapes plus `:and` / `:or` combinators so practical inclusion
  ;; works.
  (testing "tightening lower bound"
    (is (t/subtype? [:refine :int [:> 5]]  [:refine :int [:> 0]]))
    (is (t/subtype? [:refine :int [:> 0]]  [:refine :int [:>= 0]]))
    (is (t/subtype? [:refine :int [:>= 5]] [:refine :int [:>= 0]]))
    (is (t/subtype? [:refine :int [:>= 5]] [:refine :int [:> 0]])
        ":>= 5 ⊆ :> 0 because every x≥5 is also >0"))
  (testing "loosening rejected"
    (is (not (t/subtype? [:refine :int [:> 0]] [:refine :int [:> 5]])))
    (is (not (t/subtype? [:refine :int [:>= 0]] [:refine :int [:> 0]]))
        ":>= 0 ⊄ :> 0 because 0 satisfies LHS but not RHS"))
  (testing "tightening upper bound"
    (is (t/subtype? [:refine :int [:< 5]]  [:refine :int [:<= 100]]))
    (is (t/subtype? [:refine :int [:<= 5]] [:refine :int [:<= 100]]))
    (is (not (t/subtype? [:refine :int [:< 100]] [:refine :int [:< 5]]))))
  (testing ":= LHS"
    (is (t/subtype? [:refine :int [:= 7]] [:refine :int [:> 0]]))
    (is (t/subtype? [:refine :int [:= 7]] [:refine :int [:>= 7]]))
    (is (not (t/subtype? [:refine :int [:= 7]] [:refine :int [:> 7]]))))
  (testing ":and inclusion (intersection narrows)"
    ;; :user-port [:and [:>= 1024] [:<= 65535]] ⊆ :port [:and [:>= 1] [:<= 65535]]
    (is (t/subtype? [:refine :int [:and [:>= 1024] [:<= 65535]]]
                    [:refine :int [:and [:>= 1] [:<= 65535]]]))
    ;; :probability [:and [:>= 0] [:<= 1]] ⊆ :percent [:and [:>= 0] [:<= 100]]
    (is (t/subtype? [:refine :numeric [:and [:>= 0] [:<= 1]]]
                    [:refine :numeric [:and [:>= 0] [:<= 100]]])))
  (testing "set-membership / equality reasoning (non-numeric domains)"
    ;; [:in S1] ⊆ [:in S2]  iff  S1 ⊆ S2
    (is (t/subtype? [:refine :keyword [:in #{:a}]]
                    [:refine :keyword [:in #{:a :b}]]))
    (is (not (t/subtype? [:refine :keyword [:in #{:a :b}]]
                         [:refine :keyword [:in #{:a}]])))
    ;; [:= x] ⊆ [:in S]  iff  x ∈ S
    (is (t/subtype? [:refine :keyword [:= :a]]
                    [:refine :keyword [:in #{:a :b}]]))
    (is (not (t/subtype? [:refine :keyword [:= :z]]
                         [:refine :keyword [:in #{:a :b}]])))
    ;; [:in #{x}] ⊆ [:= x]  (singleton)
    (is (t/subtype? [:refine :keyword [:in #{:a}]]
                    [:refine :keyword [:= :a]]))
    ;; [:= x] ⊆ [:not= y]  iff  x ≠ y
    (is (t/subtype? [:refine :text [:= "a"]] [:refine :text [:not= "b"]]))
    (is (not (t/subtype? [:refine :text [:= "a"]] [:refine :text [:not= "a"]])))
    ;; `:in` operand may be authored as a vector, not a set
    (is (t/subtype? [:refine :keyword [:in [:get]]]
                    [:refine :keyword [:in [:get :post]]])))
  (testing "preserves equality fallback for unknown shapes"
    (is (t/subtype? [:refine :int [:custom-shape 42]]
                    [:refine :int [:custom-shape 42]]))
    (is (not (t/subtype? [:refine :int [:custom-shape 42]]
                         [:refine :int [:custom-shape 100]]))))
  (testing "regex constraint subtype"
    ;; Same pattern — trivial equality.
    (is (t/subtype? [:refine :text [:matches "^https?://"]]
                    [:refine :text [:matches "^https?://"]]))
    ;; Different patterns — NOT subtype-comparable even if one is
    ;; provably stricter. Regex containment in general needs an engine
    ;; theorem prover; we deliberately keep it conservative.
    (is (not (t/subtype? [:refine :text [:matches "^https://"]]
                         [:refine :text [:matches "^https?://"]])))
    ;; The load-bearing case: `:url` (`[:matches "^https?://"]`)
    ;; subtypes `:non-empty-text` (`[:not= ""]`). A `:url`-typed
    ;; argument now passes where `:non-empty-text` is expected.
    (is (t/subtype? [:refine :text [:matches "^https?://"]]
                    [:refine :text [:not= ""]]))
    ;; Empty pattern doesn't imply non-emptiness — degenerate case.
    (is (not (t/subtype? [:refine :text [:matches ""]]
                         [:refine :text [:not= ""]])))
    ;; SOUNDNESS: a NON-empty pattern that STILL matches "" (`.*`, `\s*`,
    ;; `a?`) must NOT be accepted as :non-empty-text. The old heuristic
    ;; only checked the pattern STRING was non-empty (`(seq pattern)`) and
    ;; wrongly accepted these; the fix tests whether the pattern matches "".
    (is (not (t/subtype? [:refine :text [:matches ".*"]]
                         [:refine :text [:not= ""]]))
        "'.*' matches the empty string — NOT a subtype of non-empty-text")
    (is (not (t/subtype? [:refine :text [:matches "\\s*"]]
                         [:refine :text [:not= ""]]))
        "'\\s*' matches the empty string")
    (is (not (t/subtype? [:refine :text [:matches "a?"]]
                         [:refine :text [:not= ""]]))
        "'a?' matches the empty string")
    ;; An anchored pattern that cannot match "" IS accepted.
    (is (t/subtype? [:refine :text [:matches "^a+$"]]
                    [:refine :text [:not= ""]])
        "'^a+$' requires at least one char → subtype of non-empty-text")
    ;; An un-compilable pattern conservatively fails the subtype (no throw).
    (is (not (t/subtype? [:refine :text [:matches "["]]
                         [:refine :text [:not= ""]]))
        "invalid regex fails closed, does not throw")
    ;; Pin the "conservative by design" contract: even when one regex
    ;; obviously implies the other (every match of `^x+` is also a
    ;; match of `^x*`), constraint-implies? doesn't try to prove it.
    ;; Both directions reject — change-detector if someone wires in
    ;; regex containment reasoning later.
    (is (not (t/subtype? [:refine :text [:matches "^x+"]]
                         [:refine :text [:matches "^x*"]]))
        "regex containment is NOT inferred (kept conservative)")
    (is (not (t/subtype? [:refine :text [:matches "^x*"]]
                         [:refine :text [:matches "^x+"]]))
        "regex containment in the other direction also rejected")
    ;; Cross-shape: a `[:matches P]` constraint and a `[:= literal]`
    ;; constraint are NOT subtype-comparable — even when the literal
    ;; matches the pattern, the type system can't know without
    ;; evaluating the regex at every callsite.
    (is (not (t/subtype? [:refine :text [:= "https://x"]]
                         [:refine :text [:matches "^https?://"]]))
        ":= literal vs :matches regex — incomparable across shapes")
    ;; [:matches P] ⊆ [:matches P] holds even when P is a Pattern
    ;; object rather than a string (we keep raw values from parse).
    (is (t/subtype? [:refine :text [:matches "^a"]]
                    [:refine :text [:matches "^a"]])
        "regex equality holds for the same source pattern")))


(deftest type-compare-recursion-guard-test
  (testing "subtype? / unify fail closed on runaway recursion instead of StackOverflow"
    ;; Two DISTINCT mutually-recursive aliases with the same outer
    ;; constructor recurse forever (normalise unfolds one alias level per
    ;; call, `(= sub sup)` never fires because the names differ). The
    ;; depth guard turns that into a clean :types/recursion-limit error.
    (binding [t/*type-compare-depth* (inc @#'t/max-type-compare-depth)]
      (let [ex (try (t/subtype? :int :text)
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :types/recursion-limit (:type (ex-data ex)))
            "subtype? throws a typed recursion-limit error, not a raw StackOverflow"))
      (let [ex (try (t/unify :int :text)
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :types/recursion-limit (:type (ex-data ex)))
            "unify throws a typed recursion-limit error"))))
  (testing "normal (finite-depth) comparisons are unaffected"
    (is (t/subtype? [:list [:list :int]] [:list [:list :int]]))
    (is (t/subtype? {:a :int :b [:list :text]} {:a :int :b [:list :text]}))
    (is (not= ::t/fail (t/unify [:list :int] [:list 'a])))))


;; -----------------------------------------------------------------------------
;; New primitives — :decimal and :input-stream

(deftest decimal-primitive-test
  (testing ":decimal is a primitive"
    (is (t/primitive? :decimal)))
  (testing ":decimal ⊆ :numeric (joins the numeric tower)"
    (is (t/subtype? :decimal :numeric))
    (is (t/subtype? :decimal :jsonb))
    (is (t/subtype? :decimal :any))
    (is (not (t/subtype? :numeric :decimal))
        ":numeric is NOT a subtype of :decimal — supertype direction")
    (is (not (t/subtype? :int :decimal))
        "siblings in the numeric tower don't subtype each other"))
  (testing ":decimal storage-kind degrades to :numeric"
    (is (= :numeric (t/type->storage-kind :decimal))
        "storage value_kind has no :decimal enum entry; degrade to super")))


;; -----------------------------------------------------------------------------
;; occurs? must recurse into every compound shape — otherwise a self-
;; referential unification (`unify 'a [:refine 'a [:> 0]]`) silently
;; builds an infinite type. Test every compound arm directly via
;; behavioural unify so a future regression (dropping an arm) trips
;; here loudly.

(deftest unify-rejects-cyclic-bindings-in-every-compound-shape
  (testing "unify('a, T) where 'a occurs anywhere inside T → ::fail"
    (is (t/fail? (t/unify 'a [:list 'a])))
    (is (t/fail? (t/unify 'a [:map 'a :int])))
    (is (t/fail? (t/unify 'a [:map :keyword 'a])))
    (is (t/fail? (t/unify 'a [:tuple :int 'a])))
    (is (t/fail? (t/unify 'a [:fn {:item 'a} :bool])))
    (is (t/fail? (t/unify 'a [:fn {:item :int} 'a])))
    (is (t/fail? (t/unify 'a [:refine 'a [:> 0]])))
    ;; `[:union :null 'a]` is NOT a cycle — `'a` recursively
    ;; appearing as a union branch is a vacuous "or itself"
    ;; tautology, not an infinite shape. `bind-var` now strips
    ;; the recursive branch and unifies against the remainder
    ;; (`:null` here). Same shape as let-poly "returns either
    ;; this fresh value or the input unchanged".
    (is (= {'a :null} (t/unify 'a [:union :null 'a])))
    (is (t/fail? (t/unify 'a {:f 'a})))
    (is (t/fail? (t/unify 'a {:f [:list 'a]}))
        "nested compounds — 'a inside list inside record"))
  (testing "no cycle → unify binds normally"
    (is (= {'a [:refine :int [:> 0]]}
           (t/unify 'a [:refine :int [:> 0]] {}))
        "no occurrence of 'a inside the refinement → bind 'a to the whole shape")
    (is (= {'a :int} (t/unify 'a :int {})))))


(deftest input-stream-primitive-test
  (testing ":input-stream is a primitive"
    (is (t/primitive? :input-stream)))
  (testing ":input-stream is a leaf — relates to :any (the top)"
    (is (t/subtype? :input-stream :any))
    (is (not (t/subtype? :jsonb :input-stream)))
    (is (not (t/subtype? :input-stream :bytes))
        ":input-stream is NOT bytes — it's a stream wrapper, not a buffer"))
  (testing ":input-stream storage-kind degrades to :any"
    (is (= :any (t/type->storage-kind :input-stream))
        "transient runtime object — never stored as data")))


(deftest map-type-test
  (testing "map-type? recognises [:map K V] only"
    (is (t/map-type? [:map :keyword :int]))
    (is (not (t/map-type? [:list :int])))
    (is (not (t/map-type? {:a :int})))
    (is (not (t/map-type? [:map :int]))))
  (testing "homogeneous-map subtyping — covariant in key AND value"
    (is (t/subtype? [:map :keyword :int] [:map :keyword :int]))
    (is (t/subtype? [:map :keyword :int] [:map :keyword :numeric]))
    (is (not (t/subtype? [:map :keyword :numeric] [:map :keyword :int])))
    (is (t/subtype? [:map :keyword :int] :jsonb))
    (is (t/subtype? [:map :keyword :int] :any)))
  (testing "a keyword-keyed record is a valid [:map :keyword V] value"
    (is (t/subtype? {:a :int :b :int} [:map :keyword :int]))
    (is (not (t/subtype? {:a :text} [:map :keyword :int])))
    ;; a [:map …] is NOT a record — no guaranteed named fields
    (is (not (t/subtype? [:map :keyword :int] {:a :int}))))
  (testing "a record subtypes a parametric [:map a b] — typevars accept anything"
    ;; `:merge`'s `:maps` slot is `[:list [:map a :any]]`; per-element
    ;; check matches a concrete record like `{:data-binding-id :text}`
    ;; against `[:map a :any]`. Without typevar tolerance, the
    ;; subtype-check fails on `(subtype? :keyword a)`. Closes the
    ;; `:_value-form-root-attrs` type-check sweep failure documented
    ;; in TYPE_CHECK_BACKLOG.md.
    (is (t/subtype? {:data-binding-id :text} [:map 'a :any]))
    (is (t/subtype? {:a :int :b :text} [:map 'a 'b]))
    (is (t/subtype? {} [:map 'a 'b]) "empty record fits any parametric map"))
  (testing "well-formed? + storage-kind"
    (is (t/well-formed? [:map :keyword :int]))
    (is (not (t/well-formed? [:map :int])))
    (is (= :jsonb (t/type->storage-kind [:map :keyword :int])))))


(deftest tuple-type-test
  (testing "tuple-type? recognises [:tuple …]"
    (is (t/tuple-type? [:tuple :text :int]))
    (is (t/tuple-type? [:tuple :int]))
    (is (not (t/tuple-type? [:list :int])))
    (is (not (t/tuple-type? {:a :int}))))
  (testing "tuple subtyping — equal length, covariant per position"
    (is (t/subtype? [:tuple :int :int] [:tuple :int :int]))
    (is (t/subtype? [:tuple :int :int] [:tuple :numeric :numeric]))
    (is (not (t/subtype? [:tuple :numeric :int] [:tuple :int :int])))
    (is (not (t/subtype? [:tuple :int] [:tuple :int :int]))
        "length mismatch — not a subtype")
    (is (t/subtype? [:tuple :text :int] :jsonb))
    (is (t/subtype? [:tuple :text :int] :any)))
  (testing "well-formed? + storage-kind"
    (is (t/well-formed? [:tuple :text :int]))
    (is (= :sequence (t/type->storage-kind [:tuple :text :int])))))


(deftest subtype-resolves-aliases-test
  ;; Pre-fix, `subtype?` operated on raw keywords — `:nullable-int`,
  ;; `:positive-int`, `:result-text` etc. all looked like opaque
  ;; tokens and the comparison fell through to the `:else false`
  ;; branch. Most internal callers (type-checker, runtime narrowing
  ;; in `web.crud/impls.clj`) compensated by calling `resolve-alias`
  ;; first, but `/api/types/compatible` and `/api/types/candidates`
  ;; did NOT — their answers were systematically wrong for any
  ;; alias-typed slot. Folding alias resolution into `normalise`
  ;; makes the property universal: every `subtype?` / `unify` site
  ;; is alias-aware now.
  (testing "primitive ⊆ union alias (was broken)"
    (t/register-type-alias! :nullable-int [:union :null :int])
    (is (t/subtype? :int :nullable-int))
    (is (t/subtype? :null :nullable-int))
    (is (not (t/subtype? :text :nullable-int))))

  (testing "refinement alias ⊆ its base (was broken)"
    ;; `:positive-int` is registered by the fixture above as
    ;; `[:refine :int [:> 0]]`.
    (is (t/subtype? :positive-int :int)
        ":positive-int is, by definition of refinement, a subtype of :int"))

  (testing "tag-pinned record ⊆ variant alias (Phase 7 desugar)"
    ;; Mirrors the structural form `desugar-variant` produces for
    ;; `:result-text`. Registering it manually here keeps the test
    ;; isolated from the package loader.
    (let [result-text-body (t/desugar-variant [:ok :text :err :text])]
      (t/register-type-alias! :result-text result-text-body))
    (is (t/subtype? {:tag [:refine :keyword [:= :ok]] :value :text}
                    :result-text)
        ":ok-tagged record matches the :ok branch of the variant union")
    (is (t/subtype? {:tag [:refine :keyword [:= :err]] :value :text}
                    :result-text)
        ":err-tagged record matches the :err branch")
    (is (not (t/subtype? :text :result-text))
        "bare text isn't tag-shaped — fails the union")))


(deftest sequence-normalises-to-list-any-test
  (testing ":sequence (storage primitive) ≡ [:list :any] (typed view)"
    (is (t/subtype? :sequence [:list :any]))
    (is (t/subtype? [:list :any] :sequence))
    (is (t/subtype? [:list :int] :sequence))
    (is (t/unified? (t/unify :sequence [:list :int])))
    (is (t/unified? (t/unify [:list :int] :sequence)))))


(deftest subtype-record-test
  (testing "more fields = subtype (open records)"
    (is (t/subtype? {:a :int :b :text} {:a :int}))
    (is (not (t/subtype? {:a :int} {:a :int :b :text}))))
  (testing "field types covariant"
    (is (t/subtype? {:a :int} {:a :any}))
    (is (not (t/subtype? {:a :any} {:a :int}))))
  (testing "record ⊆ jsonb / any"
    (is (t/subtype? {:a :int} :jsonb))
    (is (t/subtype? {:a :int} :any))
    (is (not (t/subtype? :jsonb {:a :int})))))


(deftest subtype-list-test
  (testing "covariant element"
    (is (t/subtype? [:list :int] [:list :int]))
    (is (t/subtype? [:list :int] [:list :any]))
    (is (not (t/subtype? [:list :any] [:list :int])))))


(deftest subtype-fn-test
  (testing "contravariant args, covariant return"
    (is (t/subtype? [:fn {:x :int} :int] [:fn {:x :int} :int]))
    (is (t/subtype? [:fn {:x :int} :int] [:fn {:x :int} :any]))
    (is (t/subtype? [:fn {:x :any} :int] [:fn {:x :int} :int]))
    (is (not (t/subtype? [:fn {:x :int} :int] [:fn {:x :any} :int])))
    (is (not (t/subtype? [:fn {:x :int} :any] [:fn {:x :int} :int]))))
  (testing "1-arg slot — positional, parameter names alpha-equivalent"
    ;; A 1-arg HOF slot is invoked positionally (`hof-wrap` 1 →
    ;; `(f v)`); the callee's parameter NAME is a local bound
    ;; variable. So `:some?` (param `:value`) satisfies `:filter`'s
    ;; `:pred` slot (param `:item`) — same shape, different name.
    (is (t/subtype? [:fn {:value :int} :bool] [:fn {:item :int} :bool]))
    (is (t/subtype? [:fn {:value :any} :bool] [:fn {:item :int} :bool]))
    ;; contravariance still bites regardless of the name.
    (is (not (t/subtype? [:fn {:value :int} :bool] [:fn {:item :any} :bool]))))
  (testing "callee may declare extra free args (hof-wrap captures them)"
    ;; A handler fn-graph carries extra compile-time free args (an
    ;; optional slot, an env-captured name). Against a 1-arg slot the
    ;; lone param is matched to the callee arg of that name; the rest
    ;; are captured from the environment.
    (is (t/subtype? [:fn {:request :int :next-handler :any} :int]
                    [:fn {:request :int} :int])))
  (testing "≥2-arg slot — map-callable, matched by name"
    ;; A ≥2-arg slot is a map-callable: `hof-wrap` fills the callee's
    ;; free args BY NAME, so every slot param must name a callee arg.
    (is (t/subtype? [:fn {:request :int :next-handler :any} :int]
                    [:fn {:request :int :next-handler :any} :int]))
    ;; callee missing a name the slot declares — cannot be filled.
    (is (not (t/subtype? [:fn {:other :int :next-handler :any} :int]
                         [:fn {:request :int :next-handler :any} :int])))
    ;; extra callee args beyond the slot's keys are fine (captured).
    (is (t/subtype? [:fn {:request :int :next-handler :any :extra :int} :int]
                    [:fn {:request :int :next-handler :any} :int])))
  (testing "slot-level effect constraint — bound fn must not exceed allowed set"
    ;; sup says \":pred must be PURE\" (#{}). sub says \"I do nothing\" (#{}) → subtype.
    (is (t/subtype? [:fn {:item :int} :bool #{}]
                    [:fn {:item :int} :bool #{}]))
    ;; sub does :io, sup allows pure-only — NOT a subtype.
    (is (not (t/subtype? [:fn {:item :int} :bool #{:io}]
                         [:fn {:item :int} :bool #{}])))
    ;; sub does :io, sup allows {:io :db} — subtype (subset).
    (is (t/subtype? [:fn {:item :int} :bool #{:io}]
                    [:fn {:item :int} :bool #{:io :db}]))
    ;; sub effects empty (pure), sup allows :io — subtype (pure ⊆ anything).
    (is (t/subtype? [:fn {:item :int} :bool #{}]
                    [:fn {:item :int} :bool #{:io}]))
    ;; nil sup-effects = no constraint, anything goes.
    (is (t/subtype? [:fn {:item :int} :bool #{:io :db}]
                    [:fn {:item :int} :bool])))
  (testing "well-formed? accepts both 3- and 4-element fn-types"
    (is (t/well-formed? [:fn {:item :int} :bool]))
    (is (t/well-formed? [:fn {:item :int} :bool #{}]))
    (is (t/well-formed? [:fn {:item :int} :bool #{:io :db}]))
    ;; Bad shape — effects must be set or :any.
    (is (not (t/well-formed? [:fn {:item :int} :bool [:io]])))
    (is (not (t/well-formed? [:fn {:item :int} :bool "io"])))))


(deftest refine-predicates-test
  (is (t/refine-type? [:refine :int [:gt 0]]))
  (is (= :int (t/refine-base [:refine :int [:gt 0]])))
  (is (= [:gt 0] (t/refine-constraint [:refine :int [:gt 0]])))
  (is (t/well-formed? [:refine :int [:gt 0]]))
  (is (not (t/well-formed? [:refine :nope [:gt 0]]))))


(deftest subtype-refine-test
  (testing "refinement is subtype of its base"
    (is (t/subtype? [:refine :int [:gt 0]] :int)))
  (testing "base is NOT subtype of refinement (no implicit narrowing)"
    (is (not (t/subtype? :int [:refine :int [:gt 0]]))))
  (testing "refinement subtypes itself; equal constraints"
    (is (t/subtype? [:refine :int [:gt 0]] [:refine :int [:gt 0]])))
  (testing "different constraints → not subtype-related"
    (is (not (t/subtype? [:refine :int [:gt 0]] [:refine :int [:gte 0]]))))
  (testing "refinement also ⊆ :any and ⊆ :jsonb"
    (is (t/subtype? [:refine :int [:gt 0]] :any))
    (is (t/subtype? [:refine :int [:gt 0]] :jsonb))))


;; -----------------------------------------------------------------------------
;; Unification

(deftest unify-trivial-test
  (is (t/unified? (t/unify :int :int)))
  (is (t/fail? (t/unify :int :text))))


(deftest unify-var-test
  (let [s (t/unify 'a :int)]
    (is (t/unified? s))
    (is (= :int (t/resolve s 'a)))))


(deftest unify-var-union-test
  ;; A type variable binds to a union like any other type — the
  ;; type-var case must be reached BEFORE the union branch, which
  ;; only runs a subtype probe (a free var is a subtype of nothing)
  ;; and would otherwise fail. `:if`'s `:then` slot is the bare var
  ;; `a`; binding a `[:union :null :text]`-returning ref there must
  ;; succeed and pin `a` to the union.
  (testing "type-var on the left binds to a union"
    (let [s (t/unify 'a [:union :null :text])]
      (is (t/unified? s))
      (is (= [:union :null :text] (t/resolve s 'a)))))
  (testing "type-var on the right binds to a union"
    (let [s (t/unify [:union :null :text] 'a)]
      (is (t/unified? s))
      (is (= [:union :null :text] (t/resolve s 'a)))))
  (testing "occurs-check still rejects a recursive union binding"
    (is (t/fail? (t/unify 'a [:union :null [:list 'a]])))))


(deftest unify-fn-test
  (testing "map's signature against add-10's"
    (let [map-sig    [:fn {:f [:fn {:x 'a} 'b], :coll [:list 'a]} [:list 'b]]
          add-10-sig [:fn {:f [:fn {:x :int} :int], :coll [:list 'c]} [:list 'd]]
          s (t/unify map-sig add-10-sig)]
      (is (t/unified? s))
      (is (= :int (t/resolve s 'a)))
      (is (= :int (t/resolve s 'b)))
      (is (= :int (t/resolve s 'c)))
      (is (= :int (t/resolve s 'd))))))


(deftest unify-fn-positional-test
  (testing "fn-types unify by position (sorted-by-key) — arg NAMES are documentation"
    ;; Slot expects [:fn {:item a} b]; ref carries [:fn {:string :text} :text].
    ;; Names differ (:item vs :string), but shape matches → unify binds 'a=:text 'b=:text.
    (let [slot [:fn {:item 'a} 'b]
          ref-t [:fn {:string :text} :text]
          s (t/unify slot ref-t)]
      (is (t/unified? s))
      (is (= :text (t/resolve s 'a)))
      (is (= :text (t/resolve s 'b)))))
  (testing "fn-types unify across reordered keys — sort-by-key gives canonical order"
    ;; Reduce-style: 2-arg, names different. Sort-by-key alphabetises BOTH
    ;; sides → :acc / :item zip with :a / :b respectively.
    (let [slot [:fn {:acc 'a :item 'b} 'a]
          ref-t [:fn {:a :int :b :int} :int]
          s (t/unify slot ref-t)]
      (is (t/unified? s))
      (is (= :int (t/resolve s 'a)))
      (is (= :int (t/resolve s 'b)))))
  (testing "different arity → :fail"
    (is (t/fail? (t/unify [:fn {:x 'a} 'b]
                          [:fn {:x :int :y :int} :int])))))


(deftest unify-occurs-check-test
  (is (t/fail? (t/unify 'a [:list 'a])))
  (is (t/fail? (t/unify 'a [:fn {:x 'a} :int]))))


(deftest unify-list-test
  (let [s (t/unify [:list 'a] [:list :int])]
    (is (t/unified? s))
    (is (= :int (t/resolve s 'a)))))


(deftest unify-record-test
  (let [s (t/unify {:name 'a :age :int} {:name :text :age :int})]
    (is (t/unified? s))
    (is (= :text (t/resolve s 'a))))
  (testing "open-record unification — extras on either side allowed"
    (is (t/unified? (t/unify {:a :int} {:a :int :b :text})))
    (is (t/unified? (t/unify {:a :int :b :text} {:a :int}))))
  (testing "shared keys still unify properly"
    (let [s (t/unify {:a 'a :b :text} {:a :int :c :bool})]
      (is (t/unified? s))
      (is (= :int (t/resolve s 'a)))))
  (testing "fully-disjoint records still fail (no shared keys to unify)"
    (is (t/fail? (t/unify {:a :int} {:b :text})))))


(deftest resolve-nested-test
  (let [subst {'a :int 'b [:list 'a]}]
    (is (= :int (t/resolve subst 'a)))
    (is (= [:list :int] (t/resolve subst 'b)))
    ;; resolve canonicalises fn-types to the 4-element form with `:any`
    ;; in the effects slot (unconstrained) — see `fn-type?` docstring.
    (is (= [:fn {:x :int} :int :any] (t/resolve subst [:fn {:x 'a} 'a])))))


;; -----------------------------------------------------------------------------
;; Realistic scenario: filter signature against add-10 should fail
;; (filter wants a → bool, add-10 returns int)

(deftest filter-rejects-add-10-test
  (let [filter-sig [:fn {:f [:fn {:x 'a} :bool], :coll [:list 'a]} [:list 'a]]
        bad-sig    [:fn {:f [:fn {:x :int} :int],  :coll [:list :int]} [:list :int]]]
    (is (t/fail? (t/unify filter-sig bad-sig)))))


;; -----------------------------------------------------------------------------
;; Union types

(deftest make-union-flattens-and-dedupes
  (testing "singleton collapses"
    (is (= :int (t/make-union [:int]))))
  (testing "duplicates are dropped"
    (is (= :int (t/make-union [:int :int]))))
  (testing "nested unions flatten"
    (is (= [:union :int :null :text]
           (t/make-union [[:union :int :null] :text]))))
  (testing ":any absorbs"
    (is (= :any (t/make-union [:int :any :text]))))
  (testing "subtype absorption — refinement absorbed by its base"
    (is (= :int (t/make-union [:int [:refine :int [:> 0]]])))
    (is (= :int (t/make-union [[:refine :int [:> 0]] :int]))))
  (testing "subtype absorption — narrower refinement absorbed by wider"
    (is (= [:refine :int [:>= 0]]
           (t/make-union [[:refine :int [:>= 0]] [:refine :int [:> 0]]]))))
  (testing "type-vars are kept even when a sibling would otherwise absorb"
    (is (= [:union :null 'a] (t/make-union [:null 'a])))
    (is (= [:union :int 'a] (t/make-union [:int 'a]))))
  (testing "primitive subtype absorbs through the numeric hierarchy"
    (is (= :numeric (t/make-union [:int :numeric])))
    (is (= :numeric (t/make-union [:int :float :numeric])))))


(deftest union-subtype-rules
  (testing "T ⊆ [:union …] when T matches one branch"
    (is (true?  (t/subtype? :int (t/make-union [:int :text]))))
    (is (true?  (t/subtype? :text (t/make-union [:int :text]))))
    (is (false? (t/subtype? :bool (t/make-union [:int :text])))))
  (testing "[:union …] ⊆ S when every branch fits"
    (is (true?  (t/subtype? (t/make-union [:int :null]) :any)))
    (is (false? (t/subtype? (t/make-union [:int :text]) :int))))
  (testing "primitive hierarchy through union"
    (is (true? (t/subtype? :int (t/make-union [:numeric :null]))))))


(deftest nullable-as-union
  (testing "[:union :null T] is the canonical nullable shape"
    (let [nullable-int (t/make-union [:null :int])]
      (is (true?  (t/subtype? :int nullable-int)))
      (is (true?  (t/subtype? :null nullable-int)))
      (is (false? (t/subtype? :text nullable-int)))
      (is (false? (t/subtype? nullable-int :int)))   ; int doesn't accept null
      (is (true?  (t/subtype? nullable-int :any))))))


(deftest union-resolve-substitutes-members
  (let [t [:union 'a :int]
        s (t/unify 'a :text)]
    (is (= [:union :int :text]
           (t/resolve s t)))))


(deftest union-well-formed
  (is (true?  (t/well-formed? [:union :int :text])))
  (is (true?  (t/well-formed? [:union :int [:list :text]])))
  (is (false? (t/well-formed? [:union]))))         ; need ≥ 2 members


;; -----------------------------------------------------------------------------
;; Tagged variants via union-of-records sugar (loaded by the package
;; loader from `:variant [:tag T :tag T …]` shape; here we recreate
;; the desugared form to confirm subtype rules behave as expected).

(deftest freshen-args-renames-type-vars-uniquely
  (let [src   {:item 'a :coll [:list 'a]}
        a-out (t/freshen-args src)
        b-out (t/freshen-args src)]
    (testing "within one freshen call, `'a` stays shared"
      (is (= (:item a-out)
             (t/list-elem (:coll a-out)))))
    (testing "different calls produce different freshened vars"
      (is (not= (:item a-out) (:item b-out))))
    (testing "the original is not modified"
      (is (= 'a (:item src))))))


(deftest freshen-strips-prior-suffix
  (testing "re-freshening 'a-N produces 'a-M, not 'a-N-M — names stay short"
    (let [once   (t/freshen-args {:x 'a})
          twice  (t/freshen-args once)
          three  (t/freshen-args twice)
          x1 (name (:x once))
          x2 (name (:x twice))
          x3 (name (:x three))]
      (is (re-matches #"a-\d+" x1))
      (is (re-matches #"a-\d+" x2))
      (is (re-matches #"a-\d+" x3))
      (is (not= x1 x2))
      (is (not= x2 x3)))))


(deftest aliases-snapshot-returns-current-bindings
  (let [snapshot (t/aliases-snapshot)]
    (is (map? snapshot))
    (is (contains? snapshot :positive-int))
    (is (= [:refine :int [:> 0]] (snapshot :positive-int)))))


(deftest unify-fails-mid-fn-args
  (testing "unify-fn returns ::fail when one arg-position rejects"
    (is (t/fail? (t/unify [:fn {:a :int :b :int} :int]
                          [:fn {:a :int :b :text} :int])))))


(deftest unify-fails-mid-record
  (testing "unify-record returns ::fail when one field rejects"
    (is (t/fail? (t/unify {:a :int :b :int}
                          {:a :int :b :text})))))


(deftest unify-union-fails-when-no-direction-subtypes
  (testing "unify against a union that doesn't subtype-relate"
    (is (t/fail? (t/unify [:union :int :text] :bool)))))


(deftest occurs-check-walks-fn-type-ret
  (testing "occurs? finds a var lurking in a fn-type's return position"
    ;; This trips the occurs check — would build an infinite type
    ;; without it: 'a → [:fn {} 'a].
    (is (t/fail? (t/unify 'a [:fn {} 'a])))))


(deftest freshen-recurses-through-fn-and-refine
  (testing "fn-type freshens both args and ret"
    (let [fresh (t/freshen-args {:f [:fn {:x 'a} 'b]})
          [_ args ret] (:f fresh)
          x-type (:x args)]
      (is (not= 'a x-type))
      (is (not= 'b ret))
      (is (re-matches #"a-\d+" (name x-type)))
      (is (re-matches #"b-\d+" (name ret)))))
  (testing "refine-type freshens base, preserves constraint"
    (let [fresh (t/freshen-args {:n [:refine 'a [:> 0]]})
          [_ base constraint] (:n fresh)]
      (is (not= 'a base))
      (is (re-matches #"a-\d+" (name base)))
      (is (= [:> 0] constraint)))))


(deftest variant-discriminates-on-tag-via-refinement
  (let [ok-branch  {:tag [:refine :keyword [:= :ok]]  :value :int}
        err-branch {:tag [:refine :keyword [:= :err]] :value :text}
        result     (t/make-union [ok-branch err-branch])]
    (testing "concrete ok-record subtypes the union"
      (is (true? (t/subtype? ok-branch result))))
    (testing "wrong tag refuses"
      ;; A branch with [:= :other] tag isn't part of the union.
      (let [other {:tag [:refine :keyword [:= :other]] :value :int}]
        (is (false? (t/subtype? other result)))))
    (testing "any-tag record (no pin) does NOT subtype — refinement rule"
      ;; {:tag :keyword :value :int} drops the constraint, so the
      ;; tag isn't pinned to any specific keyword.
      (let [unpinned {:tag :keyword :value :int}]
        (is (false? (t/subtype? unpinned result)))))))


;; -----------------------------------------------------------------------------
;; Secret type — monotone information-flow marker
;; -----------------------------------------------------------------------------

(deftest secret-predicates-test
  (testing "secret-type? recognises only the canonical 2-element shape"
    (is (t/secret-type? [:secret :text]))
    (is (t/secret-type? [:secret [:list :int]]))
    (is (not (t/secret-type? [:secret])))           ; missing inner
    (is (not (t/secret-type? [:secret :text :extra])))
    (is (not (t/secret-type? [:refine :text [:not= ""]])))
    (is (not (t/secret-type? :text))))

  (testing "secret-inner returns nil for non-secret types"
    (is (= :text (t/secret-inner [:secret :text])))
    (is (nil? (t/secret-inner :text))))

  (testing "make-secret-type is idempotent (no double-wrap)"
    (is (= [:secret :text] (t/make-secret-type :text)))
    (is (= [:secret :text] (t/make-secret-type [:secret :text])))))


(deftest secret-well-formed-test
  (is (t/well-formed? [:secret :text]))
  (is (t/well-formed? [:secret [:list :int]]))
  ;; Inner must itself be well-formed.
  (is (not (t/well-formed? [:secret :nope]))))


(deftest secret-subtype-asymmetric-test
  (testing "secret(T) ⊆ secret(T) — reflexive"
    (is (t/subtype? [:secret :text] [:secret :text])))

  (testing "secret(T) ⊆ secret(T') iff T ⊆ T' — covariant inside"
    (is (t/subtype? [:secret :int] [:secret :numeric]))
    (is (not (t/subtype? [:secret :numeric] [:secret :int]))))

  (testing "secret(T) ⊄ T — CANNOT strip the taint (the whole point)"
    (is (not (t/subtype? [:secret :text] :text)))
    (is (not (t/subtype? [:secret :int] :int)))
    (is (not (t/subtype? [:secret :int] :numeric))))

  (testing "T ⊆ secret(T) — auto-promote into a secret-typed slot is OK"
    ;; Monotone direction — once you say a slot holds a secret, any
    ;; plain value flowing in is tainted on entry. This is what lets
    ;; ordinary string fns (`:substring`, etc.) declare
    ;; `:secret(:text)` slots and still accept both kinds of input;
    ;; the `:return-type-rule` then taints the result iff the actual
    ;; binding was already secret-marked.
    (is (t/subtype? :text [:secret :text]))
    (is (t/subtype? :int [:secret :int]))
    (testing "promotion still respects inner subtyping"
      (is (t/subtype? :int [:secret :numeric]))
      (is (not (t/subtype? :text [:secret :int])))))

  (testing "secret(T) ⊆ :any (the topmost type) — known escape hatch"
    ;; Documented gap: :any-typed slots accept secrets and lose the
    ;; marker. T3 audit identifies which :any slots should become
    ;; secret-aware. For T1 we preserve current top-type semantics.
    (is (t/subtype? [:secret :text] :any)))

  (testing "secret(T) ⊄ :jsonb — jsonb wildcard doesn't auto-launder"
    (is (not (t/subtype? [:secret :text] :jsonb))))

  (testing "a NESTED secret can't be laundered into a jsonb content sink either"
    ;; The jsonb-sink arm accepts record/list/tuple sub-shapes; without
    ;; the `contains-secret?` guard a compound carrying a nested secret
    ;; slipped through and dropped the marker.
    (is (not (t/subtype? {:leak [:secret :text]} :jsonb)))
    (is (not (t/subtype? [:list [:secret :text]] :jsonb)))
    (is (not (t/subtype? [:list [:secret :text]] [:list :jsonb])))
    (is (not (t/subtype? [:tuple :int [:secret :text]] :jsonb)))
    (testing "a secret-free compound still flows into jsonb (unchanged)"
      (is (t/subtype? {:a :text :b :int} :jsonb))
      (is (t/subtype? [:list :text] :jsonb))
      (is (t/subtype? [:tuple :int :text] :jsonb)))))


(deftest secret-resolve-alias-recurses-test
  ;; A registered alias inside `[:secret ...]` expands like in any
  ;; other compound — `well-formed?` would otherwise reject the inner
  ;; keyword as unknown.
  (t/clear-aliases!)
  (try
    (t/register-type-alias! :my-pwd-base :text)
    (is (= [:secret :text]
           (t/resolve-alias [:secret :my-pwd-base])))
    (finally (t/clear-aliases!))))


(deftest secret-freshen-recurses-test
  (testing "freshen-args walks inside :secret"
    (let [fresh (t/freshen-args {:s [:secret 'a]})
          [_ inner] (:s fresh)]
      (is (not= 'a inner))
      (is (re-matches #"a-\d+" (name inner))))))


(deftest contains-secret-recursive-test
  (testing "top-level secret is detected"
    (is (t/contains-secret? [:secret :text])))
  (testing "secret inside compound shapes propagates"
    (is (t/contains-secret? [:list [:secret :text]]))
    (is (t/contains-secret? [:tuple :int [:secret :text]]))
    (is (t/contains-secret? {:user :text :password [:secret :text]}))
    (is (t/contains-secret? [:union :null [:secret :text]]))
    (is (t/contains-secret? [:map :keyword [:secret :int]]))
    (is (t/contains-secret? [:fn {:tok [:secret :text]} :int])))
  (testing "no secret anywhere → false"
    (is (not (t/contains-secret? :text)))
    (is (not (t/contains-secret? [:list :int])))
    (is (not (t/contains-secret? {:a :int :b :text})))
    (is (not (t/contains-secret? [:refine :text [:not= ""]])))
    (is (not (t/contains-secret? :any)))))


(deftest taint-with-secret-if-tainted-propagation-test
  (testing "no secret inputs → static return unchanged"
    (is (= :text (t/taint-with-secret-if-tainted
                   {:s {:type :text} :start {:type :int}}
                   :text))))
  (testing "any secret input → return wrapped"
    (is (= [:secret :text]
           (t/taint-with-secret-if-tainted
             {:s {:type [:secret :text]} :start {:type :int}}
             :text))))
  (testing "deeply-nested secret (inside :list) also propagates"
    (is (= [:secret :int]
           (t/taint-with-secret-if-tainted
             {:xs {:type [:list [:secret :text]]}}
             :int))))
  (testing "idempotent — static return that's already :secret stays :secret"
    (is (= [:secret :text]
           (t/taint-with-secret-if-tainted
             {:s {:type :text}}
             [:secret :text])))
    (is (= [:secret :text]
           (t/taint-with-secret-if-tainted
             {:s {:type [:secret :text]}}
             [:secret :text]))))
  (testing "empty bindings-info — no taint, no wrap"
    (is (= :text (t/taint-with-secret-if-tainted {} :text)))))


;; =============================================================================
;; Per-ns migration stage 2 — alias owner tracking + loud collision
;; =============================================================================

(deftest alias-cross-owner-collision-is-loud
  ;; Two DIFFERENT type-rows registering the same alias name (legal
  ;; under per-namespace names) must produce a visible warn; with
  ;; qualified variants registered the bare name additionally goes
  ;; AMBIGUOUS (see alias-per-ns-qualified-resolution below) — never
  ;; a SILENT shadow.
  (let [warns (atom [])
        owner-a (random-uuid)
        owner-b (random-uuid)]
    ;; The tracker only runs against the GLOBAL registry (override-bound
    ;; contexts skip it), so unbind the fixture's override for the probe
    ;; and clean the single probe name up afterwards.
    (binding [t/*type-aliases-override* nil]
      (try
        (with-redefs [clojure.tools.logging/log*
                      (fn [_ level _ message]
                        (swap! warns conj [level message]))]
          (t/register-type-alias! :collide-probe :text owner-a)
          (testing "same owner re-registering is silent"
            (t/register-type-alias! :collide-probe :text owner-a)
            (is (empty? (filter #(= :warn (first %)) @warns))))
          (testing "a DIFFERENT owner re-binding the name warns"
            (t/register-type-alias! :collide-probe :int owner-b)
            (is (some #(and (= :warn (first %))
                            (re-find #"type-alias collision" (str (second %))))
                      @warns))))
        (finally
          (t/unregister-type-alias! :collide-probe))))))


(deftest alias-per-ns-qualified-resolution
  ;; Per-ns aliases: each type-row registers its bare name AND a
  ;; qualified `:ns.path/name` variant. One owner → bare resolves as
  ;; before. Two owners → bare THROWS naming the qualified candidates;
  ;; each qualified form stays precise.
  (binding [t/*type-aliases-override* nil]
    (let [owner-a (random-uuid)
          owner-b (random-uuid)]
      (try
        (t/register-type-alias! :qual-probe :text owner-a :aa.mod/qual-probe)
        (testing "single owner — bare and qualified both resolve"
          (is (= :text (t/resolve-alias :qual-probe)))
          (is (= :text (t/resolve-alias :aa.mod/qual-probe))))
        (t/register-type-alias! :qual-probe :int owner-b :bb.mod/qual-probe)
        (testing "two owners — bare throws, listing qualified forms"
          (let [ex (try (t/resolve-alias :qual-probe) nil
                        (catch clojure.lang.ExceptionInfo e e))]
            (is (some? ex) "ambiguous bare must throw")
            (is (= :types/ambiguous-alias (:type (ex-data ex))))
            (is (= [:aa.mod/qual-probe :bb.mod/qual-probe]
                   (:candidates (ex-data ex))))))
        (testing "qualified forms keep resolving precisely"
          (is (= :text (t/resolve-alias :aa.mod/qual-probe)))
          (is (= :int (t/resolve-alias :bb.mod/qual-probe))))
        (testing "qualified alias inside a compound type resolves"
          (is (= [:list :int] (t/resolve-alias [:list :bb.mod/qual-probe]))))
        (finally
          (t/unregister-type-alias! :qual-probe))))))


(deftest alias-ambiguity-fires-for-versioned-ns-owners
  ;; Audit-3 regression: a version-materialized (`@`) namespace can't
  ;; register a qualified keyword, and the old bookkeeping only
  ;; tracked owners WITH qualified names — so a collision involving a
  ;; versioned package silently last-write-won. Owners are now
  ;; tracked unconditionally; the bare name throws either way.
  (binding [t/*type-aliases-override* nil]
    (let [owner-a (random-uuid)
          owner-b (random-uuid)]
      (try
        (t/register-type-alias! :vns-probe :text owner-a :aa.mod/vns-probe)
        ;; versioned-ns owner — no EDN-safe qualified form (nil)
        (t/register-type-alias! :vns-probe :int owner-b nil)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ambiguous"
              (t/resolve-alias :vns-probe)))
        (finally
          (t/unregister-type-alias! :vns-probe))))))


(deftest alias-ambiguity-skipped-in-override-contexts
  ;; Isolated (override-bound) registries do their own bookkeeping-free
  ;; registration — a test fixture re-registering a name must not trip
  ;; the GLOBAL ambiguity table.
  (binding [t/*type-aliases-override* (atom {})]
    (t/register-type-alias! :qual-probe-iso :text (random-uuid) :aa.mod/qual-probe-iso)
    (t/register-type-alias! :qual-probe-iso :int (random-uuid) :bb.mod/qual-probe-iso)
    (testing "bare resolves last-write inside the override, no throw"
      (is (= :int (t/resolve-alias :qual-probe-iso))))
    (testing "qualified variants still land in the override atom"
      (is (= :int (t/resolve-alias :bb.mod/qual-probe-iso))))))


;; =============================================================================
;; Generic marker types — registry-driven (:secret is the seeded instance)
;; =============================================================================

(deftest generic-marker-engine-test
  (t/register-marker! :pii-probe {:monotone? true :hide-result? false})
  (try
    (testing "subtype asymmetry holds per tag"
      (is (t/subtype? [:pii-probe :text] [:pii-probe :text]))
      (is (t/subtype? :text [:pii-probe :text]) "auto-promote on entry")
      (is (not (t/subtype? [:pii-probe :text] :text)) "can't strip")
      (is (t/subtype? [:pii-probe :text] :any) "top-type escape hatch"))
    (testing "different tags never satisfy each other"
      (is (not (t/subtype? [:pii-probe :text] [:secret :text])))
      (is (not (t/subtype? [:secret :text] [:pii-probe :text]))))
    (testing "jsonb sink refuses compounds carrying ANY marker"
      (is (not (t/subtype? {:a [:pii-probe :text]} :jsonb))))
    (testing "propagator carries EVERY input marker, deterministically"
      (let [ret (t/taint-with-secret-if-tainted
                  {:a {:type [:secret :text]}
                   :b {:type [:pii-probe :int]}}
                  :text)]
        (is (= [:secret [:pii-probe :text]] ret)
            "both labels wrap the return (sorted tag order)")))
    (testing "hide-result flag drives the redaction predicate"
      (is (t/contains-hide-result-marker? [:secret :text]))
      (is (not (t/contains-hide-result-marker? [:pii-probe :text]))
          ":pii-probe declared :hide-result? false")
      (is (t/contains-hide-result-marker? [:pii-probe [:secret :text]])
          "nested hide-marker still detected"))
    (testing "resolve/freshen keep the tag"
      (is (= [:pii-probe :text] (t/resolve-alias [:pii-probe :text])))
      (is (t/well-formed? [:pii-probe [:list :int]])))
    (testing "structural heads cannot be shadowed"
      (is (thrown-with-msg? Exception #"shadows a structural"
            (t/register-marker! :union {:monotone? true}))))
    (testing "non-monotone markers are rejected loudly (v1 engine)"
      (is (thrown-with-msg? Exception #"non-monotone"
            (t/register-marker! :weird {:monotone? false}))))
    (finally
      (t/unregister-marker! :pii-probe))))


(deftest batch-registration-names-the-dangling-ref-test
  ;; Audit-5: "body not well-formed" alone is un-actionable — the skip
  ;; reason must name WHICH inner ref dangles.
  (let [{:keys [failed]} (t/register-type-aliases-batch
                           [[:audit5-dangling {:field :no-such-type-xyz} nil]])]
    (is (= 1 (count failed)))
    (is (str/includes? (:reason (first failed)) ":no-such-type-xyz")
        "the unresolved inner ref is named in the reason")))

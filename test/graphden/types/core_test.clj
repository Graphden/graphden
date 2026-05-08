(ns graphden.types.core-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
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
  (testing "preserves equality fallback for unknown shapes"
    (is (t/subtype? [:refine :int [:custom-shape 42]]
                    [:refine :int [:custom-shape 42]]))
    (is (not (t/subtype? [:refine :int [:custom-shape 42]]
                         [:refine :int [:custom-shape 100]])))))


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
  (testing "callee may declare extra free args (hof-wrap fills them)"
    ;; A handler fn-graph's compile-time free args list every slot
    ;; downstream might read; hof-wrap captures the names the
    ;; consumer doesn't supply per-call from the environment. So a
    ;; callee `[:fn {:request :T :next-handler :any} :R]` IS a
    ;; subtype of `[:fn {:request :T} :R]` — the consumer only
    ;; promises `:request`; `:next-handler` gets captured.
    (is (t/subtype? [:fn {:request :int :next-handler :any} :int]
                    [:fn {:request :int} :int])))
  (testing "callee missing a key the consumer declares is NOT a subtype"
    ;; Reversal of the above: if the consumer says it'll pass
    ;; `:request`, a callee that doesn't list `:request` at all
    ;; can't be invoked safely.
    (is (not (t/subtype? [:fn {:other :int} :int]
                         [:fn {:request :int} :int]))))
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
  (testing "record key sets must match"
    (is (t/fail? (t/unify {:a :int} {:a :int :b :text})))))


(deftest resolve-nested-test
  (let [subst {'a :int 'b [:list 'a]}]
    (is (= :int (t/resolve subst 'a)))
    (is (= [:list :int] (t/resolve subst 'b)))
    (is (= [:fn {:x :int} :int] (t/resolve subst [:fn {:x 'a} 'a])))))


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
    (is (= :any (t/make-union [:int :any :text])))))


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

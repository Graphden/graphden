(ns graphden.types.subtype-laws-test
  "Algebraic-law coverage for `types.core/subtype?` — the audit noted the
   relation was only ever example-tested, never checked AS a relation, and
   suspected the pragmatically-added arms (`:empty-map` sentinel, record ↔
   `[:map …]` bridging, union fallbacks) might break transitivity.

   Verified here they do NOT: over a universe that deliberately hits those
   arms (atoms, lists incl. nested, `[:secret …]`, records incl. `{}`, generic
   `[:map …]`, subsuming/overlapping unions), `subtype?` is reflexive and
   transitive. It is a PREORDER, not a strict partial order: distinct SYNTAX
   can be semantically equivalent (a subsuming union like `[:union :int
   :numeric]` ≡ `:numeric`), so mutual-subtyping — not `=` — is type
   equivalence. Pure structural forms, no registry / DB → unit suite."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.types.core :as t]))


(def ^:private universe
  "Type forms chosen to exercise every compound arm of `subtype?`, including
   the special-cased ones."
  [:any :int :numeric :float :text :bool :keyword :null :uuid
   [:list :int] [:list :numeric] [:list :any] [:list [:list :int]]
   [:secret :text] [:secret :int] [:secret :any]
   {} {:a :int} {:a :int :b :text} {:a :numeric}
   [:map :text :int] [:map :text :any]
   [:union :int :text] [:union :int :text :null] [:union :int :numeric]])


(deftest subtype-is-reflexive
  (doseq [a universe]
    (is (t/subtype? a a) (str "not reflexive for " (pr-str a)))))


(deftest subtype-is-transitive
  ;; The headline law: a<:b ∧ b<:c ⇒ a<:c, over the special-arm universe.
  (let [fails (for [a universe b universe c universe
                    :when (and (t/subtype? a b)
                               (t/subtype? b c)
                               (not (t/subtype? a c)))]
                [a b c])]
    (is (empty? fails)
        (str "transitivity broken (a<:b, b<:c, but a⊄c) for: "
             (pr-str (take 5 fails))))))


(deftest subtype-is-a-preorder-not-a-strict-poset
  (testing "mutual-subtyping is semantic equivalence, not syntactic ="
    ;; `:int` <: `:numeric`, so the union `[:int ∪ :numeric]` denotes exactly
    ;; the `:numeric` value set — the two are mutual subtypes despite differing
    ;; syntactically. This is why strict antisymmetry does NOT hold and callers
    ;; must treat type equality as mutual-subtyping, never `=`.
    (is (t/subtype? :numeric [:union :int :numeric]))
    (is (t/subtype? [:union :int :numeric] :numeric))
    (is (not= :numeric [:union :int :numeric])))
  (testing ":any is the top type — everything is a subtype of it"
    (doseq [a universe]
      (is (t/subtype? a :any) (str (pr-str a) " should be <: :any")))))


(deftest empty-map-sentinel-laws
  (testing ":empty-map fits homogeneous map shapes (vacuous truth)"
    (is (t/subtype? :empty-map :jsonb))
    (is (t/subtype? :empty-map [:map :keyword :any]))
    (is (t/subtype? :empty-map [:union :null [:map :keyword :int]])))
  (testing ":empty-map does NOT fit a record — record fields are required"
    ;; `{} ⊆ {:name :text}` would promise a `:text` at `(:name {})` = nil.
    (is (not (t/subtype? :empty-map {:name :text})))
    (is (not (t/subtype? :empty-map {:a :int :b :text}))))
  (testing ":empty-map promotes into marker-wrapped map shapes (transitivity fix)"
    ;; Restores `:empty-map ⊆ X ⊆ [:secret :jsonb]` chains that the old
    ;; arm broke by refusing the direct step.
    (is (t/subtype? :empty-map [:secret :jsonb]))
    (is (t/subtype? :empty-map [:secret [:map :keyword :any]]))
    (is (not (t/subtype? :empty-map [:secret :int])))))


(deftest tuple-is-a-fixed-length-list
  (testing "tuple <: list when every position fits the element type"
    (is (t/subtype? [:tuple :int :int] [:list :int]))
    (is (t/subtype? [:tuple :int :text] [:list :any]))
    (is (t/subtype? [:tuple :int :float] [:list :numeric])))
  (testing "position that doesn't fit the elem type refuses"
    (is (not (t/subtype? [:tuple :int :text] [:list :int]))))
  (testing "the reverse direction stays false — no length guarantee"
    (is (not (t/subtype? [:list :int] [:tuple :int :int])))))


(deftest unify-agrees-with-subtype-on-marker-jsonb-refusal
  (testing "unify must not declare a marker-carrying compound jsonb-compatible"
    ;; `subtype?` refuses `[:list [:secret :text]] ⊆ :jsonb` (label would be
    ;; stripped at a jsonb content sink) — unify's jsonb-leniency arm used to
    ;; disagree and succeed. The two relations must answer alike here.
    (is (t/fail? (t/unify :jsonb [:list [:secret :text]])))
    (is (t/fail? (t/unify [:list [:secret :text]] :jsonb)))
    (is (t/fail? (t/unify :jsonb {:token [:secret :text]}))))
  (testing "unmarked compounds keep the jsonb leniency"
    (is (t/unified? (t/unify :jsonb [:list :text])))
    (is (t/unified? (t/unify :jsonb {:a :int})))))


(deftest make-union-of-nothing-is-never
  (testing "empty member list collapses to the bottom type, not [:union]"
    (is (= :never (t/make-union [])))
    (is (= :never (t/make-union [:never])))
    (is (t/well-formed? (t/make-union [])))
    (is (t/subtype? [:list (t/make-union [])] [:list :int])
        "[:list :never] from an empty literal vector fits any list slot")))


(deftest make-union-absorption-agrees-with-subtype
  (testing "record sibling does NOT absorb :empty-map (no subtype relation)"
    ;; Absorbing recorded a branch-union return claiming the record's
    ;; required fields are always present while the runtime can yield {}.
    (is (= [:union :empty-map {:name :text}]
           (t/make-union [:empty-map {:name :text}]))))
  (testing "homogeneous map shapes still absorb it (vacuous truth holds)"
    (is (= :jsonb (t/make-union [:empty-map :jsonb])))
    (is (= [:map :keyword :int] (t/make-union [:empty-map [:map :keyword :int]]))))
  (testing "generic law: an absorbed member must be a subtype of a survivor"
    (doseq [pair [[:empty-map {:name :text}]
                  [:empty-map :jsonb]
                  [[:refine :int [:> 0]] :int]
                  [:never :text]]]
      (let [u (t/make-union pair)
            members (if (t/union-type? u) (t/union-members u) [u])]
        (doseq [m pair]
          (is (or (some #(= m %) members)
                  (some #(t/subtype? m %) members))
              (str (pr-str m) " vanished from " (pr-str u)
                   " without a supertype survivor")))))))


(deftest unify-agrees-with-subtype-on-tuple-list
  (testing "a [:list a] slot bound to a tuple BINDS the elem var"
    (let [s (t/unify [:list 'a] [:tuple :int :int])]
      (is (t/unified? s))
      (is (= :int (get s 'a)))))
  (testing "heterogeneous tuple unifies elem-wise (int then text vs elem var → fail on conflict)"
    (is (t/fail? (t/unify [:list :int] [:tuple :int :text]))))
  (testing "concrete elem accepts conforming tuple"
    (is (t/unified? (t/unify [:list :numeric] [:tuple :int :float])))))

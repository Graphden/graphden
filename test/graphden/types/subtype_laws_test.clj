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

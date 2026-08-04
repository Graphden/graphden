(ns graphden.types.folds-test
  "Parallel-safe: no `with-redefs`. The `root-base-fn-name` walks are
   driven by REAL `record-rich-types-raw!` registrations under
   distinctive `:folds-*` names — the parallel plugin binds
   `*rich-types-override*` per NS-thread, and the `:once`
   `with-isolated-rich-types` fixture covers solo runs, so the writes
   never reach the process-global registry (serial-reduction cluster B).

   Direct unit tests for the structural-fold helpers consolidated in the
   option-3 type-checker hardening (commit f1068b97):
   `types.core/{child-types, type-any?, strip-null}` and
   `registry.core/root-base-fn-name`. Before this they were exercised
   only indirectly through the whole-graph sweep; these pin their
   contracts — most importantly that `child-types` exposes EVERY compound
   type-kind's constituents, the executable form of the 'a missing arm
   lets X slip through' hazard the `type-any?` combinator exists to kill."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.types.core :as t]))


(use-fixtures :once
  exec/with-isolated-rich-types)


(use-fixtures :each
  (fn [test-fn]
    (t/clear-aliases!)
    (t/register-type-alias! :positive-int [:refine :int [:> 0]])
    (test-fn)))


;; -----------------------------------------------------------------------------
;; child-types — the single enumeration every structural fold rides on.

(deftest child-types-covers-every-compound-kind
  (testing "each compound type yields exactly its constituent types"
    (is (= #{:int :text :bool}
           (set (t/child-types [:fn {:x :int :y :text} :bool])))
        "fn → arg types + return type")
    (is (= [:int] (t/child-types [:list :int])))
    (is (= #{:text :int} (set (t/child-types [:map :text :int]))))
    (is (= [:int :text] (t/child-types [:tuple :int :text])))
    (is (= [:int] (t/child-types [:refine :int [:> 0]]))
        "refine yields its base only — the constraint is NOT a type")
    (is (= [:int] (t/child-types [:secret :int])))
    (is (= #{:int :text} (set (t/child-types [:union :int :text]))))
    (is (= #{:int :text} (set (t/child-types {:a :int :b :text})))
        "record yields its field types"))
  (testing "leaf kinds have no children"
    (is (= [] (t/child-types :int)))
    (is (= [] (t/child-types :any)))
    (is (= [] (t/child-types 'a))))
  (testing "GUARD: every compound kind exposes children, every leaf none —
            a type-kind added to core.shapes without a child-types arm folds
            silently as a leaf, so any new kind MUST be added to both places
            and to this list"
    (doseq [compound [[:fn {:x :int} :int] [:list :int] [:map :text :int]
                      [:tuple :int] [:refine :int [:> 0]] [:secret :int]
                      [:union :int :text] {:a :int}]]
      (is (seq (t/child-types compound))
          (str "compound must expose children: " (pr-str compound))))
    (doseq [leaf [:int :text :bool :any 'a]]
      (is (empty? (t/child-types leaf))
          (str "leaf must expose no children: " (pr-str leaf))))))


;; -----------------------------------------------------------------------------
;; type-any? — pred-or-any-nested, over child-types.

(deftest type-any?-finds-nested-and-short-circuits
  (let [marker? (fn [x] (= x :GD-MARK))]
    (testing "finds a marker planted anywhere in each compound shape"
      (is (t/type-any? marker? [:list [:map :text [:secret :GD-MARK]]]))
      (is (t/type-any? marker? [:fn {:x :int} [:tuple :bool :GD-MARK]]))
      (is (t/type-any? marker? [:union :int :GD-MARK]))
      (is (t/type-any? marker? {:a :int :b :GD-MARK}))
      (is (t/type-any? marker? [:refine :GD-MARK [:> 0]])))
    (testing "false when the marker is absent"
      (is (not (t/type-any? marker? [:list [:map :text [:secret :int]]])))
      (is (not (t/type-any? marker? :int)))
      (is (not (t/type-any? marker? 'a))))
    (testing "pred short-circuits at a matching node (no descent needed)"
      (is (t/type-any? t/secret-type? [:secret :int]))))
  (testing "contains-secret? IS type-any? over secret-type?"
    (is (= (t/contains-secret? [:list [:secret :int]])
           (t/type-any? t/secret-type? [:list [:secret :int]])))
    (is (true? (t/contains-secret? [:map :text [:secret :int]])))
    (is (false? (t/contains-secret? [:list :int]))))
  (testing "the type-var leaf (has-type-var?'s use) — descends into secret-inner,
            the one arm where it differs from contains-secret?"
    (is (t/type-any? t/type-var? [:fn {:x 'a} :int]))
    (is (not (t/type-any? t/type-var? [:fn {:x :int} :int])))
    (is (t/type-any? t/type-var? [:secret 'a]))))


;; -----------------------------------------------------------------------------
;; strip-null — nullability removal, single source of truth.

(deftest strip-null-cases
  (is (= :text (t/strip-null [:union :null :text]))
      "a two-member nullable collapses to the sole survivor")
  (is (= [:union :int :text] (t/strip-null [:union :null :int :text])))
  (is (= :never (t/strip-null :null)) "the bare null type becomes bottom")
  (is (= :never (t/strip-null [:union :null])) "a union of only null → bottom")
  (is (= :text (t/strip-null :text)) "a non-nullable type passes through")
  (testing "SECURITY: a secret-wrapped nullable is left intact — :null sits
            INSIDE the marker, not at top level, so the taint never strips"
    (is (= [:secret [:union :null :int]]
           (t/strip-null [:secret [:union :null :int]])))))


;; -----------------------------------------------------------------------------
;; root-base-fn-name — walk the :primary-parent chain to the base-fn root.

(deftest root-base-fn-name-walks-to-root
  ;; Real registry entries (thread-isolated — see the ns docstring), so
  ;; the reader path `root-base-fn-name → rich-type-of` runs unstubbed.
  (registry/record-rich-types-raw!
    :folds-chain-a {:return :any :args {} :primary-parent :folds-chain-b})
  (registry/record-rich-types-raw!
    :folds-chain-b {:return :any :args {} :primary-parent :folds-chain-c})
  (registry/record-rich-types-raw!
    :folds-chain-c {:return :any :args {}})
  (is (= :folds-chain-c (registry/root-base-fn-name :folds-chain-a))
      "chases the chain to the root")
  (is (= :folds-chain-c (registry/root-base-fn-name :folds-chain-c))
      "a root returns itself")
  (is (= :folds-unknown-ref (registry/root-base-fn-name :folds-unknown-ref))
      "an unknown ref returns itself")
  (is (nil? (registry/root-base-fn-name nil)))
  (testing "cycle guard — a mutual-parent loop terminates instead of hanging"
    (registry/record-rich-types-raw!
      :folds-cycle-x {:return :any :args {} :primary-parent :folds-cycle-y})
    (registry/record-rich-types-raw!
      :folds-cycle-y {:return :any :args {} :primary-parent :folds-cycle-x})
    (is (contains? #{:folds-cycle-x :folds-cycle-y}
                   (registry/root-base-fn-name :folds-cycle-x))
        "returns a node in the cycle rather than looping forever")))

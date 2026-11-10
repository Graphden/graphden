(ns ^:serial graphden.types.union-canonicalization-test
  "Canonical union order, absorption and bounded per-member preprocessing."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.types.core :as types]))


(deftest mutually-subtyping-aliases-retain-the-first-canonical-member
  (binding [types/*type-aliases-override* (atom {})
            types/*alias-view* nil]
    (types/register-type-alias! :union-a [:list :int])
    (types/register-type-alias! :union-z [:list :int])
    (is (types/subtype? :union-a :union-z))
    (is (types/subtype? :union-z :union-a))
    (is (= :union-a (types/make-union [:union-z :union-a])))
    (is (= :union-a (types/make-union [:union-a :union-z :union-a])))))


(deftest equal-printed-keys-retain-input-order
  ;; Distinct keyword identities can print alike. A stable sort must preserve
  ;; their original order, which also chooses the mutually-subtype survivor.
  (let [bare (keyword "union-scope/value")
        qualified (keyword "union-scope" "value")
        unqualified (keyword nil "union-scope/value")]
    (is (= bare qualified))
    (is (not= qualified unqualified))
    (is (= (pr-str qualified) (pr-str unqualified)))
    (binding [types/*type-aliases-override* (atom {})
              types/*alias-view* nil]
      (types/register-type-alias! qualified :int)
      (types/register-type-alias! unqualified :int)
      (is (= qualified (types/make-union [qualified unqualified])))
      (is (= unqualified (types/make-union [unqualified qualified]))))))


(deftest nested-execution-variables-remain-separate-union-arms
  (let [callback (fn [execution] {:handler [:fn {} :int #{} execution]})
        e (callback [:execution-var 'E])
        f (callback [:execution-var 'F])
        concrete (callback #{:browser-main})
        result (types/make-union [e concrete f])]
    (is (= #{e f concrete} (set (types/union-members result))))
    (is (= 3 (count (types/union-members result))))
    (is (= result (types/make-union [f concrete e e])))
    (testing "a regular subtype is still absorbed beside protected arms"
      (is (= (types/make-union [e :numeric 'a])
             (types/make-union [e :int :numeric 'a]))))
    (testing "execution constraints do not leak between calls"
      (is (= [:union :int :text] (types/make-union [:text :int]))))))


(deftest union-preprocessing-is-linear-in-distinct-members
  (let [members [:text :uuid :bool :int :text]
        printed (atom 0) inspected (atom 0)
        printer pr-str inspect types/has-execution-vars?
        result (with-redefs [clojure.core/pr-str (fn [& values]
                                                   (swap! printed inc)
                                                   (apply printer values))
                             types/has-execution-vars? (fn [value]
                                                         (swap! inspected inc)
                                                         (inspect value))]
                 (types/make-union members))]
    (is (= [:union :bool :int :text :uuid] result))
    (is (<= @printed 4) "Sort keys are printed at most once per distinct member")
    (is (= 4 @inspected) "Execution-variable walks do not repeat for sibling pairs")))

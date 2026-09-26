(ns graphden.types.check.gradual-test
  "`graphden.types.check.gradual` — the directional judgement
   `check-binding!` makes after `unify` bound the type variables. Pure:
   no registry, no DB."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.types.check.gradual :as gradual]))


(def ^:private ring-request
  {:request-method :keyword :uri :text :headers [:map :text :text] :body :jsonb})


(deftest direction-holes-are-closed-test
  (testing "a wider primitive sibling is refused"
    (is (false? (gradual/fits? {:x :numeric :y :text} {:x :int :y :text} []))))
  (testing "a refinement base into the refinement is refused (a ref, no literal)"
    (is (false? (gradual/fits? {:n :int :y :text} {:n [:refine :int [:> 0]] :y :text} []))))
  (testing "a nullable field into a non-null one is refused"
    (is (false? (gradual/fits? {:n [:union :null :int] :y :text} {:n :int :y :text} []))))
  (testing "a callee demanding a narrower arg than the slot feeds is refused"
    (is (false? (gradual/fits? [:fn {:item [:refine :int [:> 0]]} :text]
                               [:fn {:item :int} :text] []))))
  (testing "the matching shapes still fit"
    (is (true? (gradual/fits? {:x :int :y :text} {:x :int :y :text} [])))
    (is (true? (gradual/fits? [:fn {:item :numeric} :text] [:fn {:item :int} :text] [])))))


(deftest escape-hatches-still-pass-test
  (testing ":jsonb into a jsonb-representable expected, at the top and nested"
    (is (true? (gradual/fits? :jsonb [:union :null [:list :any] [:map :keyword :any]] [])))
    (is (true? (gradual/fits? {:body :jsonb} {:body {:k :int}} [])))
    (is (true? (gradual/fits? :jsonb :int []))))
  (testing ":jsonb is NOT a hole against a marker — the label must not be stripped"
    (is (false? (gradual/fits? :jsonb [:secret :text] []))))
  (testing "an :any shape on either side, covariant and contravariant"
    (is (true? (gradual/fits? {:items [:list :any]} {:items [:list :int]} [])))
    (is (true? (gradual/fits? [:fn {:item [:list :numeric]} :numeric]
                              [:fn {:pair [:list :any]} :numeric] [])))
    (is (true? (gradual/fits? [:fn {:next [:fn {:arg :int} :text]} :text]
                              [:fn {:next :any} :text] []))))
  (testing "a type variable unify left unbound is unconstrained"
    (is (true? (gradual/fits? [:fn {:request 'a} [:union 'b {:status :int}]]
                              [:fn {:request ring-request} {:status :int}] [])))
    (is (true? (gradual/fits? [:union [:map :any :any] 'a-1]
                              [:union :null [:list :any] [:map 'a :any]] []))))
  (testing "records are open on both sides, as unify-record is"
    (is (true? (gradual/fits? {:uri :text :headers :empty-map} ring-request [])))
    (is (true? (gradual/fits? {:x :int :extra :text} {:x :int} [])))))


(deftest literal-value-decides-a-refined-field-test
  (testing "a literal that satisfies the refinement passes"
    (is (true? (gradual/fits? :int [:refine :int [:> 0]] [50])))
    (is (true? (gradual/fits? {:status :int :body :text}
                              {:status [:refine :int [:and [:>= 100] [:<= 599]]] :body :any}
                              [{:status 204 :body "raw"}])))
    (is (true? (gradual/fits? [:list {:n :int}] [:list {:n [:refine :int [:>= 0]]}]
                              [[{:n 0} {:n 2}]]))))
  (testing "a literal that violates it is refused"
    (is (false? (gradual/fits? :int [:refine :int [:> 0]] [0])))
    (is (false? (gradual/fits? [:list {:n :int}] [:list {:n [:refine :int [:>= 0]]}]
                               [[{:n 0} {:n -2}]]))))
  (testing "a text literal against a non-empty-text field"
    (is (true? (gradual/fits? {:name :text} {:name [:refine :text [:not= ""]]} [{:name "q"}])))
    (is (false? (gradual/fits? {:name :text} {:name [:refine :text [:not= ""]]} [{:name ""}])))))


(deftest erase-keeps-shape-test
  (testing "fn effects survive the walk on both sides"
    (is (= [[:fn {:item :int} :text #{:io}] [:fn {:item :int} :text #{}]]
           (gradual/erase [:fn {:item :int} :text #{:io}] [:fn {:item :any} :text #{}] []))))
  (testing "a union expected keeps the member that fit and the rest"
    (is (= [{:k :int} [:union :null {:k :int}]]
           (gradual/erase {:k :any} [:union :null {:k :int}] []))))
  (testing "nothing to erase returns the sides unchanged"
    (is (= [{:x :numeric} {:x :int}] (gradual/erase {:x :numeric} {:x :int} [])))))

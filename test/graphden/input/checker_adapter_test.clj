(ns graphden.input.checker-adapter-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.analysis :as analysis]
    [graphden.input.context :as context]
    [graphden.types.check :as check]))


(deftest structured-literal-uses-the-existing-directional-checker-test
  (let [input {:address [[(random-uuid) []] [(random-uuid)]]
               :expected :int :actual :int :expression {:kind :value :value 12}}]
    (is (= :checked (:status (check/check-input-binding input))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not fit"
          (check/check-input-binding (assoc input :actual :text :expression {:kind :value :value "hidden"}))))
    (is (= :deferred (:status (check/check-input-binding (assoc input :actual :any)))))))


(deftest structured-unification-keeps-concrete-sibling-direction-test
  (let [input {:address [[(random-uuid) []] []]
               :expected {:n :int :value 'a} :actual {:n :numeric :value :text}
               :expression {:kind :ref :fn-id (random-uuid)}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not fit"
          (check/check-input-binding input)))))


(deftest existing-refinement-rejection-is-sanitized-and-deferred-proof-is-explicit-test
  (let [input {:address [[(random-uuid) []] []]
               :expected [:refine :int [:> 0]] :actual :int
               :expression {:kind :value :value -1}}
        error (try (check/check-input-binding input) (catch clojure.lang.ExceptionInfo e e))]
    (is (= :refinement-violation (:reason (ex-data error))))
    (is (not (contains? (ex-data error) :binding)))
    (is (= :deferred (:status (check/check-input-binding (assoc input :expression {:kind :value :value 1})))))))


(deftest shared-input-variable-narrows-the-remaining-field-through-real-checker-test
  (let [[owner root variable x y binding] (repeatedly 6 random-uuid)
        ctx (context/build-context
              {:fns {owner {:input-type-fn-id root}}
               :type-bodies {variable 'a}
               :fields {x {:id x :name "x" :type-fn-id variable :required true}
                        y {:id y :name "y" :type-fn-id variable :required true}}
               :members {root [{:id (random-uuid) :slot-id x :position 0}
                               {:id (random-uuid) :slot-id y :position 1}]}
               :bindings {binding {:id binding :fn-id owner :scope-path [] :target-path [x]
                                   :expression-kind "value" :value-present true :value 3}}})
        result (analysis/analyze-input ctx owner {:check-expression check/check-input-binding})]
    (is (= :checked (:status result)))
    (is (= [[[owner []] [y]]] (:required-addresses result)))
    (is (= :int (get-in result [:remaining-contract :fields 1 :type])))))

(ns graphden.input.checker-adapter-test
  (:require
    [clojure.test :refer [deftest is]]
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

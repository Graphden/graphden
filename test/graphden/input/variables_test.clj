(ns graphden.input.variables-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.variables :as variables]
    [graphden.types.core :as types]))


(deftest independent-uses-do-not-share-polymorphic-solutions-test
  (let [[owner a b] (repeatedly 3 random-uuid)
        left (variables/type-in-scope [owner [a]] 'a)
        right (variables/type-in-scope [owner [b]] 'a)
        solution (types/unify right :text (types/unify left :int {}))]
    (is (not= left right))
    (is (= :int (types/resolve solution left)))
    (is (= :text (types/resolve solution right)))))


(deftest constructor-input-and-return-use-the-same-derived-variable-test
  (let [scope [(random-uuid) [(random-uuid)]]
        descriptor {:type {:configuration 'a}
                    :fields [{:id (random-uuid) :name :configuration :type 'a :required true}]}
        declared (variables/descriptor-in-scope scope descriptor)
        signature (variables/signature-in-scope scope {:args {:configuration 'a} :return 'a})]
    (is (= (:return signature) (get-in signature [:args :configuration])
           (get-in declared [:fields 0 :type]) (get-in declared [:type :configuration])))))


(deftest variable-instantiation-preserves-literal-constraints-and-execution-sort-test
  (let [scope [(random-uuid) []]
        rich [:refine [:fn {:value 'a} 'a #{} [:execution-var 'a]] [:= 'a]]
        result (variables/type-in-scope scope rich)
        callable (types/refine-base result)]
    (is (= [:= 'a] (types/refine-constraint result)))
    (is (not= (types/fn-ret callable) (second (types/fn-execution callable))))
    (is (= result (binding [*print-length* 1 *print-level* 1]
                    (variables/type-in-scope scope rich))))))

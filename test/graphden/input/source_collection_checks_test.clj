(ns graphden.input.source-collection-checks-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.input.source-collection-checks :as checks]))


(deftest a-refined-element-must-come-from-the-same-reference-and-literal-inputs
  (let [[owner callee edge-id field literal-id literal-field] (repeatedly 6 random-uuid)
        address [[owner []] [field]]
        use [owner [edge-id]]
        literal [use [literal-field]]
        old-type [:list :any]
        new-type '[:list [:union :text a]]
        check {:address address :scope (first address) :path [field] :id [address :value-type :field-read]
               :kind :value-type :trigger :field-read}
        edge {:from address :to use :binding-id edge-id :target-fn-id callee :mode :value}
        row {:id literal-id :expression-kind "value" :value-present true :value "name"}
        certificate {:address literal :row row}
        graph (fn [rich]
                {:fn-id owner :dependencies [edge] :proofs {address {:status :deferred :reason :runtime-type-obligation}}
                 :scopes {[] {:bindings {address {:binding-id edge-id :expression {:kind :ref :fn-id callee}
                                                  :expected-type rich :reference {:mode :value :actual-type rich}}}}
                          [edge-id] {:fn-id callee :declaration-order [callee] :declared-contract {:type '{:item a}}
                                     :configured-contract {:type '{:item a}} :signature {:return rich}}}})
        before (graph old-type)
        after (assoc-in (graph new-type) [:scopes [edge-id] :bindings literal]
                        {:binding-id literal-id :expression {:kind :value :value "name"} :reference {:mode :literal :actual-type :text}})
        prove (fn [prior scoped cs]
                (checks/refined-read? prior scoped cs (assoc check :expected-type old-type) (assoc check :expected-type new-type)))]
    (is (prove before after [certificate]))
    (doseq [changed [(assoc-in after [:scopes [edge-id] :configured-contract :type] '{:item b})
                     (assoc-in after [:scopes [edge-id] :signature :return] old-type)
                     (assoc-in after [:scopes [] :bindings address :expected-type] old-type)
                     (assoc-in after [:scopes [edge-id] :bindings literal :reference :actual-type] :keyword)
                     (assoc-in after [:dependencies 0 :mode] :graph-callable)]]
      (is (not (prove before changed [certificate]))))
    (is (not (prove before after [])))
    (is (not (prove before after [(assoc-in certificate [:row :value] 8)])))
    (is (not (prove (assoc-in before [:scopes [edge-id] :configured-contract :type] '{:item b}) after [certificate])))))

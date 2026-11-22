(ns graphden.crud.structured-input-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.structured-input :as structured]
    [graphden.input.context :as context]))


(deftest occupied-projection-concealment-test
  (let [root (random-uuid) record-id (random-uuid) leaf-id (random-uuid) binding-id (random-uuid)
        target [record-id leaf-id]
        address [[root []] target]
        leaf {:id leaf-id :name :name :type :text}
        record {:id record-id :name :profile :type {:name :text} :fields [leaf]}
        contract {:type {:profile {:name :text}} :fields [record]}
        scope {:configured-contract contract :declared-contract contract
               :bindings {address {:origin-fn-id root :binding-id binding-id :expected-type :text}}
               :proofs {address {:status :checked}}}
        graph {:fn-id root :scopes {[] scope} :hidden-input-addresses #{}}
        row {:id binding-id :fn-id root :target-path target :scope-path []
             :expression-kind "value" :value-present true :value "opaque literal"}
        ctx (-> (context/build-context {:bindings {binding-id row}})
                (assoc-in [:markers :hidden-fixture] {:hide-result? true}))
        project (fn [candidate view]
                  (context/call-with-types candidate #(#'structured/visible-occupied-fields candidate view)))]
    (is (= [{:path [:profile :name] :type :text :binding-id binding-id}] (project ctx graph)))
    (testing "secret leaf and ancestor markers, including refined and declared containers"
      (is (empty? (project ctx (-> graph
                                   (assoc-in [:scopes [] :configured-contract :fields 0 :fields 0 :type] [:secret :text])
                                   (assoc-in [:scopes [] :bindings address :expected-type] [:secret :text])))))
      (doseq [[path type] [[[:configured-contract :fields 0 :type] [:secret {:name :text}]]
                           [[:declared-contract :fields 0 :type] [:hidden-fixture {:name :text}]]
                           [[:configured-contract :type] [:refine [:hidden-fixture {:profile {:name :text}}] [:eq nil]]]
                           [[:declared-contract :fields 0 :fields 0 :type] [:hidden-fixture :text]]]]
        (is (empty? (project ctx (assoc-in graph (into [:scopes []] path) type))))))
    (testing "hidden root, ancestor and leaf addresses"
      (doseq [path [[] [record-id] target]]
        (is (empty? (project ctx (assoc graph :hidden-input-addresses #{[[root []] path]}))))))
    (testing "only a checked literal owned by the selected function is projected"
      (is (empty? (project ctx (assoc-in graph [:scopes [] :proofs address :status] :deferred))))
      (is (empty? (project ctx (assoc-in graph [:scopes [] :bindings address :origin-fn-id] (random-uuid)))))
      (is (empty? (project (assoc-in ctx [:bindings binding-id :resolver-fn-id] (random-uuid)) graph))))))

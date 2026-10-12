(ns graphden.executor.source-contract-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.source-contract :as contract]
    [graphden.schema.graph.schema :as schema]
    [graphden.schema.malli.core :as malli]
    [graphden.schema.protocol.protocol :as ds]))


(deftest source-contract-covers-current-slot-schema
  (let [actual (keys (ds/entity-fields (schema/build-schema (malli/create-builder)) :slot))]
    (is (= (conj (disj (set actual) :description :org-id) :id)
           (:slots @#'contract/fields)))))


(deftest source-fingerprints-preserve-all-own-structural-evidence
  (let [id (random-uuid)
        slot (random-uuid)
        binding (random-uuid)
        graph {:fns [{:id id :name "checked" :parent-ids [(random-uuid)]
                      :namespace-id (random-uuid)}]
               :slots [{:id slot :name "value"}]
               :fn-slots [{:id (random-uuid) :fn-id id :slot-id slot :position 0}]
               :bindings [{:id binding :fn-id id :slot-id slot
                           :value-present true :value {:role :view}}]
               :list-items [{:id (random-uuid) :binding-id binding :position 0
                             :value-present true :value :first}]}
        digest #(get (contract/fingerprints %) id)]
    (testing "nullable columns and nonstructural descriptions do not change source"
      (is (= (digest graph)
             (digest (-> graph
                         (update-in [:fns 0] assoc :description "documentation" :org-id "public")
                         (update-in [:bindings 0] assoc :terminal nil))))))
    (testing "every structural change remains observable"
      (doseq [[path value] [[[:fns 0 :branch-local?] true]
                            [[:fns 0 :parent-ids] [(random-uuid)]]
                            [[:slots 0 :source-slot-id] (random-uuid)]
                            [[:fn-slots 0 :position] 1]
                            [[:bindings 0 :value] {:role :initial}]
                            [[:bindings 0 :type-override-fn-id] (random-uuid)]
                            [[:bindings 0 :terminal] true]
                            [[:list-items 0 :position] 1]
                            [[:list-items 0 :value] :second]]]
        (is (not= (digest graph) (digest (assoc-in graph path value))) (str path))))
    (testing "missing or duplicate source rows cannot match"
      (is (not= (digest graph) (digest (assoc graph :bindings []))))
      (is (not= (digest graph) (digest (update graph :bindings into (:bindings graph))))))
    (testing "unrelated functions do not enter the owner's source witness"
      (is (= (digest graph) (digest (update graph :fns conj {:id (random-uuid)})))))))

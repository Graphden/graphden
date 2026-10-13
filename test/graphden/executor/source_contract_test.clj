(ns graphden.executor.source-contract-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.source-contract :as contract]
    [graphden.schema.graph.schema :as schema]
    [graphden.schema.malli.core :as malli]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.core :as postgres]
    [graphden.storage.protocol.core :as storage]
    [graphden.types.check.provenance :as provenance]))


(deftest writer-witness-uses-the-backend-codec-without-reading-current-source
  (let [id (random-uuid) binding-id (random-uuid)
        fields schema/binding-list-item-fields
        backend (postgres/->PostgresStorage nil (atom {:fields-by-entity {:binding-list-item fields}})
                                            (Object.) (atom {}))
        authored {:id (random-uuid) :binding-id binding-id :position 0
                  :value {:selector "&" :declarations {"display" "contents"}}}
        decoded (first (storage/expected-stored-rows backend :binding-list-item [authored]))
        graph {:fns [{:id id :parent-ids [(random-uuid)]}]
               :bindings [{:id binding-id :fn-id id}] :list-items [authored]}
        stored (assoc graph :list-items [decoded])
        definition {:name :checked :parent :const :args {:value (:value authored)}}
        original (provenance/stamp {:return [:secret :jsonb]} definition)
        fingerprint #(get (contract/fingerprints %) id)
        certified (provenance/stamp-storage-input original definition (fingerprint stored))]
    (is (= #{:display} (set (keys (get-in decoded [:value :declarations])))))
    (is (not= (fingerprint graph) (fingerprint stored)))
    (is (provenance/matches-storage-input? certified (fingerprint stored)))
    (is (not (provenance/matches-storage-input?
               certified (fingerprint (assoc-in stored [:list-items 0 :value :declarations :display] "grid")))))
    (is (= (:return original) (:return certified)))
    (is (provenance/matches? certified definition))
    (is (nil? (storage/expected-stored-rows backend :unknown [])))))


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

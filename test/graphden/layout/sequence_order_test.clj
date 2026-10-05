(ns graphden.layout.sequence-order-test
  "Sequence order from real slot/binding rows, including inherited appends."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.layout.graph :as graph]))


(defn- sequence-snapshot
  [mixed?]
  (let [base (random-uuid)
        parent (random-uuid)
        child (random-uuid)
        leaf (random-uuid)
        sequence-type (random-uuid)
        number-type (random-uuid)
        slot (random-uuid)
        parent-binding (random-uuid)
        child-binding (random-uuid)]
    {:child child :leaf leaf :number-type number-type
     :entities
     {:fns [{:id sequence-type :name "sequence" :parent-ids []}
            {:id number-type :name "number" :parent-ids []}
            {:id leaf :name "leaf" :parent-ids [] :return-type-fn-id number-type}
            {:id base :name "base" :parent-ids [] :return-type-fn-id number-type}
            {:id parent :name "parent" :parent-ids [base]}
            {:id child :name "child" :parent-ids [parent]}]
      :slots [{:id slot :name "items" :type-fn-id sequence-type}]
      :fn-slots [{:id (random-uuid) :fn-id base :slot-id slot :position 0}]
      :bindings [{:id parent-binding :fn-id parent :slot-id slot :list-append true}
                 {:id child-binding :fn-id child :slot-id slot :list-append true}]
      ;; Storage iteration order is immaterial; positions specify the lists.
      :list-items [{:id (random-uuid) :binding-id child-binding :position 1 :value 4}
                   {:id (random-uuid) :binding-id parent-binding :position 2 :value 1}
                   {:id (random-uuid) :binding-id child-binding :position 0 :value 3}
                   {:id (random-uuid) :binding-id parent-binding :position 0 :value 2}
                   (merge {:id (random-uuid) :binding-id parent-binding :position 1}
                          (if mixed? {:ref-fn-id leaf} {:value 0}))]}}))


(defn- build-elements
  [root expansions entities]
  (graph/build-graph-elements root expansions
                              (graph/build-lookups (graph/ensure-synth-args entities))))


(defn- sequence-groups
  [layout]
  (let [nodes (into {} (map (juxt (comp :id :data) :data)) (:nodes layout))]
    (->> (:edges layout)
         (map :data)
         (filter :seqGroup)
         (group-by :seqGroup)
         vals
         (mapv (fn [edges]
                 (mapv #(get nodes (:target %)) (sort-by :seqIndex edges)))))))


(deftest inherited-appends-follow-parent-items
  (let [{:keys [child entities]} (sequence-snapshot false)]
    (doseq [[label expansions] [["collapsed" {}]
                                ["expanded" {(str "fn-" child) 1}]]]
      (testing label
        (let [groups (sequence-groups (build-elements child expansions entities))
              members (first groups)
              items (remove :isPlaceholder members)]
          (is (= 1 (count groups)))
          (is (= [2 0 1 3 4] (mapv :value items)))
          (is (= [0 1 2 3 4] (mapv :seqIndex items)))
          (is (= [false false false false false true]
                 (mapv #(true? (:seqTail %)) members))))))))


(deftest scoped-mixed-lists-keep-independent-owner-order
  (let [{:keys [child leaf number-type entities]} (sequence-snapshot true)
        caller (random-uuid)
        base (random-uuid)
        slots [(random-uuid) (random-uuid)]
        entities (-> entities
                     (update :fns into [{:id base :name "caller-base" :parent-ids []
                                         :return-type-fn-id number-type}
                                        {:id caller :name "caller" :parent-ids [base]}])
                     (update :slots into (map-indexed (fn [i id]
                                                        {:id id :name (str "input" i) :required true
                                                         :type-fn-id number-type}) slots))
                     (update :fn-slots into (map-indexed (fn [i id]
                                                           {:id (random-uuid) :fn-id base
                                                            :slot-id id :position i}) slots))
                     (update :bindings into (map (fn [id]
                                                   {:id (random-uuid) :fn-id caller
                                                    :slot-id id :ref-fn-id child}) slots)))
        collapsed (build-elements caller {} entities)
        expansions (into {} (keep (fn [{:keys [data]}]
                                    (when (= (str child) (:originalFnId data))
                                      [(:id data) 1]))) (:nodes collapsed))
        groups (sequence-groups (build-elements caller expansions entities))]
    (is (= 2 (count groups)))
    (doseq [members groups]
      (is (= [2 :leaf 1 3 4]
             (mapv #(if (= (str leaf) (:originalFnId %)) :leaf (:value %))
                   (remove :isPlaceholder members))))
      (is (true? (:seqTail (last members)))))))

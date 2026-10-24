(ns graphden.crud.graph-refs-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.crud.graph-refs :as refs]))


(deftest structural-edges-cover-shared-slots-and-captured-calls
  (let [root (random-uuid)
        parent (random-uuid)
        target (random-uuid)
        other (random-uuid)
        slot-id (random-uuid)
        binding-id (random-uuid)
        graph {:fns [{:id root :parent-ids [parent]
                      :base-fn-id target :return-type-fn-id target
                      :element-fn-id target}
                     {:id other :parent-ids []}
                     {:id parent :parent-ids []}
                     {:id target :parent-ids []}]
               :slots [{:id slot-id :name "callback" :type-fn-id target}]
               :fn-slots [{:fn-id root :slot-id slot-id}
                          {:fn-id other :slot-id slot-id}]
               :bindings [{:id binding-id :fn-id root :slot-id slot-id
                           :ref-fn-id target :resolver-fn-id other
                           :type-override-fn-id target}]
               :list-items [{:binding-id binding-id :ref-fn-id target}
                            {:binding-id binding-id :ref-fn-id target}]}
        edges (refs/edges graph)
        uses (filter #(= target (:target-id %)) edges)]
    (is (= #{[root :ref-of] [root :slot-of] [other :slot-of]
             [root :base-of] [root :return-of] [root :element-of]
             [root :binding-of]}
           (set (map (juxt :fn-id :kind) uses))))
    (is (= 7 (count uses)) "repeated list items are one semantic reference")
    (is (= #{parent target other} (get (refs/by-source graph) root)))
    (is (nil? (get (refs/by-source
                     (update graph :fns
                             #(filterv (fn [row] (not= root (:id row))) %)))
                   root))
        "an owner absent from the visible graph cannot expose its references")))


(deftest shortest-path-is-cycle-safe-and-stays-within-visible-fns
  (let [root (random-uuid)
        nearer (random-uuid)
        middle (random-uuid)
        target (random-uuid)
        graph {:fns [{:id root :parent-ids [middle]}
                     {:id nearer :parent-ids [target]}
                     {:id middle :parent-ids [root target]}
                     {:id target :parent-ids []}]}
        path (refs/shortest-path graph [root] target)
        nearest (refs/shortest-path graph [root nearer] target)]
    (is (= [root middle target] (mapv :fn-id path)))
    (is (= [nearer target] (mapv :fn-id nearest)))
    (is (= :parent-of (:kind (last nearest))))
    (is (nil? (refs/shortest-path
                {:fns [{:id root :return-type-fn-id target}
                       {:id target}]}
                [root] target))
        "a type annotation does not make a function executable from a root")
    (is (nil? (refs/shortest-path
                (update graph :fns
                        #(filterv (fn [row] (not= middle (:id row))) %))
                [root] target)))))

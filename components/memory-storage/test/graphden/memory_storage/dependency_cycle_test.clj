(ns graphden.memory-storage.dependency-cycle-test
  "Tests for memory storage dependency cycle detection."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.memory-storage.interface :as mem]
    [graphden.storage-protocol.interface :as sp]))


(defn- make-graph-schema
  "Creates schema with fn, arg-value, and fn-arg entities.
   Uses normalized schema where arg-value has no owner, and fn-arg binds fn to arg-value."
  []
  (-> (mds/create-builder)
      (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                     {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}})
      ;; arg-value: pure value (no owner-fn-id)
      (ds/add-entity :arg-value #uuid "30000000-0000-0000-0000-000000000001"
                     {:arg-schema-id {:uuid #uuid "30000000-0000-0000-0000-000000000003"
                                      :type :uuid}
                      :value {:uuid #uuid "30000000-0000-0000-0000-000000000004"
                              :type :uuid}})
      ;; fn-arg: binding from fn to arg-value
      (ds/add-entity :fn-arg #uuid "40000000-0000-0000-0000-000000000001"
                     {:fn-id {:uuid #uuid "40000000-0000-0000-0000-000000000002"
                              :type :ref :ref-entity :fn}
                      :arg-schema-id {:uuid #uuid "40000000-0000-0000-0000-000000000003"
                                      :type :uuid}
                      :arg-value-id {:uuid #uuid "40000000-0000-0000-0000-000000000004"
                                     :type :ref :ref-entity :arg-value}})
      ds/build))


(defn- create-arg-value-with-binding!
  "Creates arg-value and fn-arg binding. Returns the arg-value."
  [storage fn-id value]
  (let [av (sp/create-entity storage :arg-value
                             {:arg-schema-id (random-uuid)
                              :value value})]
    (sp/create-entity storage :fn-arg
                      {:fn-id fn-id
                       :arg-schema-id (random-uuid)
                       :arg-value-id (:id av)})
    av))


(deftest diamond-dependency-cycle-detection-test
  (testing "diamond pattern in dependency chain (A->B->D, A->C->D) - no cycle"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      ;; Create diamond: A -> B -> D, A -> C -> D
      ;; When checking from A, both B and C will add D to to-visit
      ;; D will be visited first from one path, then skipped from the other (already-visited)
      (let [fn-d (sp/create-entity storage :fn {:name "d"})
            fn-b (sp/create-entity storage :fn {:name "b"})
            fn-c (sp/create-entity storage :fn {:name "c"})
            fn-a (sp/create-entity storage :fn {:name "a"})
            ;; B -> D
            _ (create-arg-value-with-binding! storage (:id fn-b) (:id fn-d))
            ;; C -> D
            _ (create-arg-value-with-binding! storage (:id fn-c) (:id fn-d))
            ;; A -> B
            _ (create-arg-value-with-binding! storage (:id fn-a) (:id fn-b))
            ;; A -> C
            _ (create-arg-value-with-binding! storage (:id fn-a) (:id fn-c))
            ;; Create X to test diamond traversal
            fn-x (sp/create-entity storage :fn {:name "x"})]
        ;; This should NOT throw - diamond is not a cycle
        ;; Start from X and check adding a reference to A
        ;; This traverses the entire A subgraph including the diamond
        (is (nil? (sp/validate-no-dependency-cycle! storage (:id fn-x) (:id fn-a)))))))

  (testing "diamond pattern with cycle attempt detects correctly"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      ;; Create diamond: A -> B -> D, A -> C -> D, then try D -> A (creates cycle)
      (let [fn-d (sp/create-entity storage :fn {:name "d"})
            fn-b (sp/create-entity storage :fn {:name "b"})
            fn-c (sp/create-entity storage :fn {:name "c"})
            fn-a (sp/create-entity storage :fn {:name "a"})
            ;; B -> D
            _ (create-arg-value-with-binding! storage (:id fn-b) (:id fn-d))
            ;; C -> D
            _ (create-arg-value-with-binding! storage (:id fn-c) (:id fn-d))
            ;; A -> B
            _ (create-arg-value-with-binding! storage (:id fn-a) (:id fn-b))
            ;; A -> C
            _ (create-arg-value-with-binding! storage (:id fn-a) (:id fn-c))]
        ;; Now try to add D -> A, which would create a cycle
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dependency cycle"
              (sp/validate-no-dependency-cycle! storage (:id fn-d) (:id fn-a))))))))

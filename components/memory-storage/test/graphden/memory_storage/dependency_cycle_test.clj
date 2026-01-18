(ns graphden.memory-storage.dependency-cycle-test
  "Tests for memory storage dependency cycle detection."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.memory-storage.interface :as mem]
    [graphden.storage-protocol.interface :as sp]))


(deftest diamond-dependency-cycle-detection-test
  (testing "diamond pattern in dependency chain (A->B->D, A->C->D) - no cycle"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :arg-value #uuid "30000000-0000-0000-0000-000000000001"
                                                {:owner-fn-id {:uuid #uuid "30000000-0000-0000-0000-000000000002"
                                                               :type :ref :ref-entity :fn}
                                                 :arg-schema-id {:uuid #uuid "30000000-0000-0000-0000-000000000003"
                                                                 :type :uuid}
                                                 :value {:uuid #uuid "30000000-0000-0000-0000-000000000004"
                                                         :type :uuid}})
                                 ds/build))
      ;; Create diamond: A -> B -> D, A -> C -> D
      ;; When checking from A, both B and C will add D to to-visit
      ;; D will be visited first from one path, then skipped from the other (already-visited)
      (let [fn-d (sp/create-entity storage :fn {:name "d"})
            fn-b (sp/create-entity storage :fn {:name "b"})
            fn-c (sp/create-entity storage :fn {:name "c"})
            fn-a (sp/create-entity storage :fn {:name "a"})
            ;; B -> D
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-b)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-d)})
            ;; C -> D
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-c)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-d)})
            ;; A -> B
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-a)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-b)})
            ;; A -> C
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-a)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-c)})
            ;; Create X to test diamond traversal
            fn-x (sp/create-entity storage :fn {:name "x"})]
        ;; This should NOT throw - diamond is not a cycle
        ;; Start from X and check adding a reference to A
        ;; This traverses the entire A subgraph including the diamond
        (is (nil? (sp/validate-no-dependency-cycle! storage (:id fn-x) (:id fn-a)))))))

  (testing "diamond pattern with cycle attempt detects correctly"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :arg-value #uuid "30000000-0000-0000-0000-000000000001"
                                                {:owner-fn-id {:uuid #uuid "30000000-0000-0000-0000-000000000002"
                                                               :type :ref :ref-entity :fn}
                                                 :arg-schema-id {:uuid #uuid "30000000-0000-0000-0000-000000000003"
                                                                 :type :uuid}
                                                 :value {:uuid #uuid "30000000-0000-0000-0000-000000000004"
                                                         :type :uuid}})
                                 ds/build))
      ;; Create diamond: A -> B -> D, A -> C -> D, then try D -> A (creates cycle)
      (let [fn-d (sp/create-entity storage :fn {:name "d"})
            fn-b (sp/create-entity storage :fn {:name "b"})
            fn-c (sp/create-entity storage :fn {:name "c"})
            fn-a (sp/create-entity storage :fn {:name "a"})
            ;; B -> D
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-b)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-d)})
            ;; C -> D
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-c)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-d)})
            ;; A -> B
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-a)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-b)})
            ;; A -> C
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-a)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-c)})]
        ;; Now try to add D -> A, which would create a cycle
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dependency cycle"
              (sp/validate-no-dependency-cycle! storage (:id fn-d) (:id fn-a))))))))

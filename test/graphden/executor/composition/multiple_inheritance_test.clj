(ns ^:integration graphden.executor.composition.multiple-inheritance-test
  "End-to-end tests for fn-def multiple inheritance (`:parents [a b]` syntax).

   These tests verify that:
   - sync writes parent-ids correctly to storage
   - args from multiple parents are merged
   - diamond inheritance (two parents sharing a common ancestor) resolves
     correctly: bound args win over free args, free args propagate
   - end-to-end execution produces the expected result"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.interface :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each
  (setup/create-clean-db-fixture)
  exec/with-clean-registry)


;; ============================================================================
;; Storage-level: parents vector → parent-ids in storage
;; ============================================================================

(deftest sync-multiple-parents-test
  (testing ":parents [a b] is stored as parent-ids vector"
    (let [storage (setup/create-test-storage)
          _ (registry/initialize-all!
              storage
              [{:base-a {:args {} :return-type :int :impl (fn [_ _] 1)}
                :base-b {:args {} :return-type :int :impl (fn [_ _] 2)}}])
          ;; child has BOTH parents
          result (fn-composition/sync-fns-to-storage!
                   storage
                   [{:name :child :parents [:base-a :base-b]}])
          child-id (:child result)
          child (sp/read-entity storage :fn child-id)]
      (is (vector? (:parent-ids child)))
      (is (= 2 (count (:parent-ids child))))
      (is (= [(registry/fn-uuid :base-a) (registry/fn-uuid :base-b)]
             (:parent-ids child)))
      (sp/close storage))))


;; ============================================================================
;; Diamond inheritance: two parents binding orthogonal args of a shared base
;; ============================================================================

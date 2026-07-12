(ns ^:integration graphden.fleet.placement-test
  "The fleet placement map (`graphden.fleet.placement`, docs/FLEET_RFC.md §6.1):
   assign a cell to an executor, read it back, and move it (epoch bump) — the
   routing state the internal-forward router (T2.6) reads."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.fleet.placement :as placement]
    [graphden.packages.records :as records]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.placement.schema :as placement-schema]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]))


(use-fixtures :once (setup/create-container-fixture) exec/with-clean-registry)


(defn- storage-with-placement!
  []
  (pth/clean-database-fast! @(resolve 'graphden.executor.test-setup/*container*))
  (let [container @(resolve 'graphden.executor.test-setup/*container*)
        raw (pg/create-storage (pth/get-container-config container))]
    (sp/initialize raw (-> (mds/create-builder) (gds/extend-builder)
                           (vts/extend-builder) (vds/extend-builder)
                           (placement-schema/extend-builder) (ds/build)))
    (sp/upsert-entities raw :fn (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    raw))


(deftest assign-read-and-move-a-cell
  (let [storage (storage-with-placement!)
        entry (setup/create-base-fn! storage "cell-root" :any)
        eid (:id entry)]
    (try
      (testing "an unplaced cell resolves to nil"
        (is (nil? (placement/executor-for storage "acme" eid))))

      (testing "assign! places the cell on an executor"
        (placement/assign! storage {:org "acme" :entry-fn-id eid
                                    :executor-id "pod-1" :epoch 1})
        (is (= "pod-1" (placement/executor-for storage "acme" eid))))

      (testing "a MOVE updates the same row — epoch bumped, executor swapped, no duplicate"
        (placement/assign! storage {:org "acme" :entry-fn-id eid
                                    :executor-id "pod-2" :epoch 2})
        (is (= "pod-2" (placement/executor-for storage "acme" eid)) "now on pod-2")
        (is (= 2 (:epoch (placement/placement-for storage "acme" eid))) "epoch bumped")
        (is (= 1 (count (sp/query-entities storage :placement {:org "acme" :entry-fn-id eid})))
            "one row per (org, entry-fn-id) — a move updates, it doesn't accumulate"))

      (testing "placement is per (org, entry) — another org's key is independent"
        (is (nil? (placement/executor-for storage "beta" eid))))

      (finally (sp/close storage)))))

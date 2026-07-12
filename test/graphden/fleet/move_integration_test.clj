(ns ^:integration graphden.fleet.move-integration-test
  "End-to-end move mechanism (`fleet.controller/move-cell!`, docs/FLEET_RFC.md
   §6.3) against REAL storage + two REAL compiled registries — the runtime the
   unit tests fake. Two ctxs stand in for two executors; the seams route
   `load-cell!` / `evict-cell!` to the right one. Proves a move actually compiles
   the cell onto the target registry, flips the `:placement` epoch, and evicts
   the source — the load-before-flip / evict-after-flip contract on live objects."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as ectx]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.fleet.controller :as ctrl]
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
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (setup/create-container-fixture) exec/with-clean-registry)


(defn- storage!
  "Fresh versioned storage with the `:placement` schema + boot primitives."
  []
  (pth/clean-database-fast! @(resolve 'graphden.executor.test-setup/*container*))
  (let [container @(resolve 'graphden.executor.test-setup/*container*)
        raw (pg/create-storage (pth/get-container-config container))]
    (sp/initialize raw (-> (mds/create-builder) (gds/extend-builder)
                           (vts/extend-builder) (vds/extend-builder)
                           (placement-schema/extend-builder) (ds/build)))
    (sp/upsert-entities raw :fn (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    (vs/wrap-with-versioning raw "main")))


(defn- executor-ctx
  "A fresh executor context over `storage` — its own compiled-registry atom, so
   two of them are two independent 'pods'."
  [storage]
  (ectx/create-context {:storage storage :base-fns (exec/get-default-registry)}))


(deftest move-cell-loads-target-flips-epoch-evicts-source
  (let [storage (storage!)]
    (exec/register-base-fn! :echo-x (fn [args _ctx] (get args :x)))
    (let [base (setup/create-base-fn! storage "echo-x" :any)
          slot (setup/create-slot! storage "x" :any)
          _ (setup/attach-slot! storage (:id base) (:id slot) 0)
          root (setup/create-composed-fn! storage "echo-42" (:id base))
          _ (setup/bind-value! storage (:id root) (:id slot) 42)
          entry (:id root)
          ;; Two 'executors', each its own registry.
          ctxs {"pod-a" (executor-ctx storage) "pod-b" (executor-ctx storage)}
          load-on (fn [exec root-fn-id] (seq (cr/load-cell! (ctxs exec) root-fn-id)))
          evict-on (fn [exec root-fn-id] (cr/evict-cell! (ctxs exec) root-fn-id))
          move! (fn [to]
                  (ctrl/move-cell! storage {:org "acme" :entry-fn-id entry
                                            :to-executor to
                                            :load-on load-on :evict-on evict-on}))]
      (try
        (testing "initial placement: loads on the target, no source to evict"
          (let [r (move! "pod-a")]
            (is (= {:ok true :from nil :to "pod-a" :epoch 1} r))
            (is (cr/cell-held? (ctxs "pod-a") entry) "cell compiled on pod-a")
            (is (not (cr/cell-held? (ctxs "pod-b") entry)) "pod-b holds nothing yet")
            (is (= "pod-a" (placement/executor-for storage "acme" entry)))))

        (testing "move pod-a → pod-b: loads on b, epoch bumped, a evicted"
          (let [r (move! "pod-b")]
            (is (= {:ok true :from "pod-a" :to "pod-b" :epoch 2} r))
            (is (cr/cell-held? (ctxs "pod-b") entry) "cell now compiled on pod-b")
            (is (not (cr/cell-held? (ctxs "pod-a") entry)) "pod-a evicted the cell")
            (is (= "pod-b" (placement/executor-for storage "acme" entry)))
            (is (= 2 (:epoch (placement/placement-for storage "acme" entry))))))

        (testing "the relocated cell still executes on its new holder"
          (is (= 42 (cr/execute (ctxs "pod-b") entry {}))
              "pod-b runs the moved cell"))

        (testing "moving to where it already is is a no-op"
          (is (= {:ok true :from "pod-b" :to "pod-b" :epoch 2 :noop true}
                 (move! "pod-b"))))
        (finally
          (sp/close (vs/unwrap storage)))))))

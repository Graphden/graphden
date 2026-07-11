(ns ^:integration graphden.executor.load-cell-test
  "`cr/load-cell!` (T2.2): compile ONE root's forward ref-closure into the
   registry without a full rebuild — the fleet 'load a cell' primitive
   (docs/FLEET_RFC.md §3). Build two independent roots, load only one, and
   prove the registry holds (and executes) that cell but not the other."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as ectx]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (setup/create-container-fixture) exec/with-clean-registry)


(defn- versioned-storage!
  []
  (pth/clean-database-fast! @(resolve 'graphden.executor.test-setup/*container*))
  (let [container @(resolve 'graphden.executor.test-setup/*container*)
        raw (pg/create-storage (pth/get-container-config container))]
    (sp/initialize raw (-> (mds/create-builder) (gds/extend-builder)
                           (vts/extend-builder) (vds/extend-builder) (ds/build)))
    (sp/upsert-entities raw :fn (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    (vs/wrap-with-versioning raw "main")))


(deftest load-cell-compiles-one-roots-closure-only
  (let [storage (versioned-storage!)]
    (exec/register-base-fn! :echo-x (fn [args _ctx] (get args :x)))
    (let [base (setup/create-base-fn! storage "echo-x" :any)
          slot (setup/create-slot! storage "x" :any)
          _ (setup/attach-slot! storage (:id base) (:id slot) 0)
          root (setup/create-composed-fn! storage "echo-42" (:id base))
          _ (setup/bind-value! storage (:id root) (:id slot) 42)
          other (setup/create-composed-fn! storage "echo-99" (:id base))
          _ (setup/bind-value! storage (:id other) (:id slot) 99)
          ctx (ectx/create-context {:storage storage
                                    :base-fns (exec/get-default-registry)})]
      (try
        (testing "before any load the registry is empty"
          (is (empty? (or @(:compiled-registry ctx) {}))))

        (testing "load-cell! compiles the root's closure"
          (let [cell (cr/load-cell! ctx (:id root))]
            (is (contains? cell (:id root)) "the root is in its own cell")
            (is (contains? @(:compiled-registry ctx) (:id root))
                "root compiled into the registry")))

        (testing "the OTHER independent root was NOT loaded"
          (is (not (contains? @(:compiled-registry ctx) (:id other)))
              "echo-99 is a separate cell — loading echo-42 must not pull it in"))

        (testing "the loaded cell executes"
          (is (= 42 (cr/execute ctx (:id root) {}))))

        (testing "loading the second cell adds it without dropping the first"
          (cr/load-cell! ctx (:id other))
          (is (contains? @(:compiled-registry ctx) (:id root)) "first cell still loaded")
          (is (= 99 (cr/execute ctx (:id other) {})) "second cell executes")
          (is (= 42 (cr/execute ctx (:id root) {})) "first cell still executes"))

        (testing "loading a root outside the shard/graph returns an empty cell"
          (is (empty? (cr/load-cell! ctx (random-uuid)))))

        (finally (sp/close storage))))))

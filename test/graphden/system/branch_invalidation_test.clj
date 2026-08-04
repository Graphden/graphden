(ns graphden.system.branch-invalidation-test
  "Cross-branch compiled-registry invalidation.

   A branch ctx caches its own `{fn-id → closure}` map. A branch with
   no own version rows gets that map copied from `main` at build time
   (`compile-runtime/instantiate-from-templates!`). Its STORAGE resolves
   main's newest rows, but nothing recompiles its registry — so an edit
   on main left the branch executing stale closures forever.

   `branch-router/invalidate-affected-ctxs!` closes that. These tests pin
   both directions: the sweep must reach branches that inherit from the
   written one, and must NOT touch branches that don't."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as ectx]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.schema.executions.schema :as es]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.services.schema :as svcs]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.system.branch-router :as br]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (setup/create-container-fixture) exec/with-clean-registry)


(defn- full-schema
  []
  (-> (mds/create-builder) (gds/extend-builder) (vts/extend-builder)
      (vds/extend-builder) (es/extend-builder) (svcs/extend-builder) (ds/build)))


(defn- fixture!
  "A compiled one-fn graph on `main`: `echo-1` returns its bound `:x`.
   Returns everything a test needs to edit that binding and re-execute."
  []
  (pth/clean-database-fast! @(resolve 'graphden.executor.test-setup/*container*))
  (let [container @(resolve 'graphden.executor.test-setup/*container*)
        raw (pg/create-storage (pth/get-container-config container))]
    (sp/initialize raw (full-schema))
    (sp/upsert-entities raw :fn (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    (exec/register-base-fn! :echo-x (fn [args _ctx] (get args :x)))
    (let [storage (vs/wrap-with-versioning raw "main")
          base (setup/create-base-fn! storage "echo-x" :any)
          slot (setup/create-slot! storage "x" :any)
          _ (setup/attach-slot! storage (:id base) (:id slot) 0)
          composed (setup/create-composed-fn! storage "echo-1" (:id base))
          _ (setup/bind-value! storage (:id composed) (:id slot) 1)
          base-ctx (ectx/create-context {:storage storage
                                         :base-fns (exec/get-default-registry)})]
      (cr/rebuild! base-ctx)
      {:raw raw :storage storage :base-ctx base-ctx :fn-id (:id composed)
       :router (br/create-router base-ctx "echo-1")})))


(defn- set-value!
  "Rewrite `echo-1`'s bound value on `storage`'s branch, then invalidate
   exactly the way `crud.entities/invalidate!` does."
  [{:keys [storage base-ctx router fn-id]} write-storage write-ctx v]
  (let [binding-id (:id (first (sp/query-entities write-storage :binding {:fn-id fn-id})))]
    (sp/update-entity write-storage :binding binding-id {:value v :value-present true})
    (ectx/invalidate-graph-cache! write-ctx #{fn-id})
    (br/invalidate-affected-ctxs! router (vs/current-branch-id write-storage) #{fn-id})
    (when (= write-storage storage) base-ctx)))


(deftest main-edit-reaches-cached-inheriting-branch-test
  (let [{:keys [raw storage base-ctx router fn-id] :as f} (fixture!)
        feature (vs/create-branch! storage "inherits-main")]
    (try
      (let [branch-ctx (br/ctx-for router (:id feature))]
        (testing "precondition: the branch inherits main's value"
          (is (= 1 (cr/execute branch-ctx fn-id {})))
          (is (= 1 (cr/execute base-ctx fn-id {}))))

        (set-value! f storage base-ctx 2)

        (testing "main sees its own edit"
          (is (= 2 (cr/execute base-ctx fn-id {}))))
        (testing "the cached branch recompiles — it has no rows of its own to override with"
          (is (= 2 (cr/execute branch-ctx fn-id {})))))
      (finally (sp/close raw)))))


(deftest branch-edit-does-not-invalidate-main-test
  (let [{:keys [raw storage base-ctx router fn-id] :as f} (fixture!)
        feature (vs/create-branch! storage "writes-its-own")
        branch-storage (vs/->VersionedStorage (vs/unwrap storage) (:id feature))]
    (try
      (let [branch-ctx (br/ctx-for router (:id feature))]
        (set-value! f branch-storage branch-ctx 9)

        (testing "the branch sees its own write"
          (is (= 9 (cr/execute branch-ctx fn-id {}))))
        (testing "main is untouched — version rows are branch-scoped, main does not inherit from a child"
          (is (= 1 (cr/execute base-ctx fn-id {})))))
      (finally (sp/close raw)))))


(deftest sweep-never-builds-an-uncached-branch-test
  (let [{:keys [raw storage base-ctx router fn-id] :as f} (fixture!)
        feature (vs/create-branch! storage "never-served")]
    (try
      (testing "precondition: the pod has never served this branch"
        (is (nil? (get @(:handlers router) (:id feature)))))

      (set-value! f storage base-ctx 3)

      (testing "a write on main must not compile a branch nobody asked this pod about"
        (is (nil? (get @(:handlers router) (:id feature)))))
      (testing "and the branch still builds correctly on first demand"
        (is (= 3 (cr/execute (br/ctx-for router (:id feature)) fn-id {}))))
      (finally (sp/close raw)))))

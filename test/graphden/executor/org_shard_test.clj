(ns graphden.executor.org-shard-test
  "Org-sharded compiled registry.

   A shared cloud pod today compiles EVERY tenant's fns into one
   registry — `compile-storage` reads the graph org-agnostically. That
   is the wall the cloud hits: registry size grows with tenant count,
   not with what the pod actually serves.

   `:executor-orgs` on the ctx restricts the compile to one shard.
   Soundness rests on an invariant verified separately: a fn's ref
   closure never leaves `own-org ∪ un-owned platform rows`, so a shard
   that keeps the platform rows can always resolve what it compiled."
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
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]))


(use-fixtures :once (setup/create-container-fixture) exec/with-clean-registry)


(defn- storage!
  []
  (pth/clean-database-fast! @(resolve 'graphden.executor.test-setup/*container*))
  (let [container @(resolve 'graphden.executor.test-setup/*container*)
        s (pg/create-storage (pth/get-container-config container))]
    (sp/initialize s (-> (mds/create-builder) (gds/extend-builder)
                         (vts/extend-builder) (ds/build)))
    (sp/upsert-entities s :fn (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    s))


(defn- owned-fn!
  "A composed fn stamped with `org` (nil ⇒ an un-owned platform row),
   inheriting the platform's `echo-x` base-fn and binding it to `v`."
  [storage org base-id slot-id nm v]
  (let [f (sp/create-entity storage :fn (cond-> {:name nm :parent-ids [base-id]}
                                          org (assoc :org-id org)))]
    (sp/create-entity storage :binding (cond-> {:fn-id (:id f) :slot-id slot-id
                                                :value v :value-present true}
                                         org (assoc :org-id org)))
    f))


(deftest org-in-shard?-is-the-one-membership-rule-test
  ;; Both the compile filter and the request router ask this. If they ever
  ;; disagreed, a pod would 200 a request for fns it never compiled.
  (testing "no shard ⇒ this executor serves everything"
    (is (cr/org-in-shard? nil "acme"))
    (is (cr/org-in-shard? nil nil)))
  (testing "an un-owned row is the shared platform graph — in every shard"
    (is (cr/org-in-shard? #{"acme"} nil)))
  (testing "a set is already the predicate"
    (is (cr/org-in-shard? #{"acme"} "acme"))
    (is (not (cr/org-in-shard? #{"acme"} "beta"))))
  (testing "so is a fn, for a fleet that doesn't enumerate its tenants"
    (is (cr/org-in-shard? #(= "acme" %) "acme"))
    (is (not (cr/org-in-shard? #(= "acme" %) "beta")))))


(deftest registry-holds-only-this-shards-orgs-test
  (let [s (storage!)]
    (exec/register-base-fn! :echo-x (fn [args _ctx] (get args :x)))
    (let [base (setup/create-base-fn! s "echo-x" :any)
          slot (setup/create-slot! s "x" :any)
          _ (setup/attach-slot! s (:id base) (:id slot) 0)
          platform (owned-fn! s nil (:id base) (:id slot) "platform-fn" 0)
          acme (owned-fn! s "acme" (:id base) (:id slot) "acme-fn" 1)
          beta (owned-fn! s "beta" (:id base) (:id slot) "beta-fn" 2)]
      (try
        (testing "no :executor-orgs ⇒ whole graph compiles (self-hosted default, unchanged)"
          (let [ctx (ectx/create-context {:storage s :base-fns (exec/get-default-registry)})
                reg (cr/rebuild! ctx)]
            (is (contains? reg (:id platform)))
            (is (contains? reg (:id acme)))
            (is (contains? reg (:id beta)))))

        (testing "a shard serving only acme compiles acme + the un-owned platform rows"
          (let [ctx (ectx/create-context {:storage s
                                          :base-fns (exec/get-default-registry)
                                          :executor-orgs #{"acme"}})
                reg (cr/rebuild! ctx)]
            (is (contains? reg (:id platform)) "platform graph is shared by every pod")
            (is (contains? reg (:id acme)))
            (is (not (contains? reg (:id beta))) "another tenant's fn is not this pod's business")))

        (testing "the shard still EXECUTES what it compiled"
          (let [ctx (ectx/create-context {:storage s
                                          :base-fns (exec/get-default-registry)
                                          :executor-orgs #{"acme"}})]
            (cr/rebuild! ctx)
            (is (= 1 (cr/execute ctx (:id acme) {})))
            (is (zero? (cr/execute ctx (:id platform) {})))))

        (testing "a hash-sharded fleet passes a predicate, not an enumeration"
          ;; A cloud pod shouldn't have to list its tenants at boot — it
          ;; only has to answer "is this org mine?". A set already IS that
          ;; predicate, so the ctx accepts either.
          (let [mine? (fn [org] (contains? #{"beta"} org))
                ctx (ectx/create-context {:storage s
                                          :base-fns (exec/get-default-registry)
                                          :executor-orgs mine?})
                reg (cr/rebuild! ctx)]
            (is (contains? reg (:id platform)))
            (is (contains? reg (:id beta)))
            (is (not (contains? reg (:id acme))))))

        (testing "a two-org shard holds both"
          (let [ctx (ectx/create-context {:storage s
                                          :base-fns (exec/get-default-registry)
                                          :executor-orgs #{"acme" "beta"}})
                reg (cr/rebuild! ctx)]
            (is (contains? reg (:id acme)))
            (is (contains? reg (:id beta)))))
        (finally (sp/close s))))))

(ns ^:integration graphden.tenancy.demo-gc-test
  "The ephemeral-org reaper (task #7) against real Postgres. The safety-
   critical property is WRONG-ORG isolation: purging an expired demo org must
   remove exactly its rows and leave every other org (permanent tenants + the
   public org) untouched."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.schema.executions.schema :as es]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.packages.schema :as pkgs]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.services.schema :as svcs]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.tenancy.demo-gc :as demo-gc]
    [graphden.tenancy.org-schema :as org-schema])
  (:import
    (java.time
      Instant)))


(use-fixtures :once (setup/create-container-fixture))


(defn- build-schema
  "The full production schema (so `purge-org!` finds every org-scoped table it
   deletes from) plus the `:org` registry (which carries `:expires-at`)."
  []
  (-> (mds/create-builder)
      (gds/extend-builder)
      (vts/extend-builder)
      (vds/extend-builder)
      (es/extend-builder)
      (svcs/extend-builder)
      (pkgs/extend-builder)
      (org-schema/extend-builder)
      (ds/build)))


(deftest reaper-hard-purges-expired-orgs-only
  (pth/clean-database-fast! setup/*container*)
  (let [storage (pg/create-storage (pth/get-container-config setup/*container*))
        ds (:pool storage)
        now (Instant/now)
        past (Instant/.minusSeconds now 3600)
        future (Instant/.plusSeconds now 3600)]
    (sp/initialize storage (build-schema))
    ;; Three orgs: an expired demo, a permanent tenant (NULL expires-at), and a
    ;; demo whose TTL hasn't passed yet.
    (sp/create-entity storage :org {:name "demo-x" :expires-at past})
    (sp/create-entity storage :org {:name "keep-me"})
    (sp/create-entity storage :org {:name "demo-future" :expires-at future})
    ;; Graph rows for the expired org AND the permanent one — the reaper must
    ;; take the former and spare the latter. Created directly on the raw
    ;; storage with an explicit :org-id (same as rls-test seeds tenant rows).
    (sp/create-entity storage :fn {:name "demo-fn" :org-id "demo-x"})
    (sp/create-entity storage :ns {:name "demo-ns" :org-id "demo-x"})
    (sp/create-entity storage :fn {:name "keep-fn" :org-id "keep-me"})
    (sp/create-entity storage :ns {:name "keep-ns" :org-id "keep-me"})

    (testing "expired-org-names returns ONLY the expired org (NULL + future excluded)"
      (is (= #{"demo-x"} (set (demo-gc/expired-org-names ds now)))))

    (testing "purge-org! on an unknown org is a harmless no-op"
      (is (= "no-such" (do (demo-gc/purge-org! ds "no-such") "no-such"))))

    (testing "sweep! hard-purges the expired org + all its rows"
      (is (= ["demo-x"] (demo-gc/sweep! ds now)))
      (is (empty? (sp/query-entities storage :org {:name "demo-x"}))
          "the org registry row is gone")
      (is (empty? (sp/query-entities storage :fn {:name "demo-fn"}))
          "the org's fn identity row is gone")
      (is (empty? (sp/query-entities storage :ns {:name "demo-ns"}))
          "the org's namespace row is gone"))

    (testing "the permanent org is completely untouched (WRONG-ORG safety)"
      (is (= 1 (count (sp/query-entities storage :org {:name "keep-me"}))))
      (is (= 1 (count (sp/query-entities storage :fn {:name "keep-fn"}))))
      (is (= 1 (count (sp/query-entities storage :ns {:name "keep-ns"})))))

    (testing "a not-yet-expired demo org is left for a later sweep"
      (is (= 1 (count (sp/query-entities storage :org {:name "demo-future"})))))

    (sp/close storage)))

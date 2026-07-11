(ns ^:integration graphden.tenancy.grant-schema-integration-test
  "The :grant entity + StorageBackedGrantStore against real Postgres."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.tenancy.grant :as grant]
    [graphden.tenancy.grant-schema :as grant-schema]))


(defn- subj [s] {:id s :name s})


(use-fixtures :once (setup/create-container-fixture))


(deftest grant-rows-roundtrip-and-drive-can?
  (pth/clean-database-fast! setup/*container*)
  (let [storage (pg/create-storage (pth/get-container-config setup/*container*))
        schema (-> (mds/create-builder)
                   (gds/extend-builder)
                   (grant-schema/extend-builder)
                   (ds/build))]
    (sp/initialize storage schema)
    (testing "the :grant table was created and rows persist"
      (sp/create-entity storage :grant {:subject-id "alice" :subject "alice" :capability "write" :namespace "acme"})
      (sp/create-entity storage :grant {:subject-id "alice" :subject "alice" :capability "admin" :namespace "acme.ops"})
      (sp/create-entity storage :grant {:subject-id "bob" :subject "bob" :capability "read" :namespace "acme"}))
    (let [store (grant-schema/storage-grant-store storage)]
      (testing "can? reads the stored grants (capability text → keyword)"
        (is (grant/can? store (subj "alice") :write "acme.team") "parent grant covers descendant")
        (is (grant/can? store (subj "alice") :write "acme.ops") "admin subsumes :write")
        (is (not (grant/can? store (subj "alice") :read "acme")) "alice has no :read")
        (is (grant/can? store (subj "bob") :read "acme"))
        (is (not (grant/can? store (subj "bob") :write "acme")) "bob only :read"))
      (testing "subjects are isolated"
        (is (= 2 (count (grant/grants-for store (subj "alice")))))
        (is (empty? (grant/grants-for store (subj "carol"))))))
    (sp/close storage)))

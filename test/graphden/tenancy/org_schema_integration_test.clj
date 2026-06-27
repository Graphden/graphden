(ns ^:integration graphden.tenancy.org-schema-integration-test
  "The :org entity (orgs registry, §3.4) against real Postgres."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.tenancy.org-schema :as org-schema]))


(use-fixtures :once (setup/create-container-fixture))


(deftest org-rows-roundtrip-and-enforce-unique-name
  (pth/clean-database-fast! setup/*container*)
  (let [storage (pg/create-storage (pth/get-container-config setup/*container*))
        schema (-> (mds/create-builder)
                   (gds/extend-builder)
                   (org-schema/extend-builder)
                   (ds/build))
        handler-id (random-uuid)]
    (sp/initialize storage schema)
    (testing "the :org table was created and a row persists"
      (sp/create-entity storage :org {:name "acme" :handler-fn-id handler-id})
      (let [rows (sp/query-entities storage :org {:name "acme"})]
        (is (= 1 (count rows)))
        (is (= "acme" (:name (first rows))))
        (is (= handler-id (:handler-fn-id (first rows))))))
    (testing "handler-fn-id is nullable (org exists before its app is provisioned)"
      (sp/create-entity storage :org {:name "beta"})
      (is (nil? (:handler-fn-id (first (sp/query-entities storage :org {:name "beta"}))))))
    (testing "name is UNIQUE — one org per slug"
      (is (thrown? Exception
            (sp/create-entity storage :org {:name "acme" :handler-fn-id (random-uuid)}))))
    (sp/close storage)))

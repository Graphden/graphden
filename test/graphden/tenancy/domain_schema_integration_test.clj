(ns ^:integration graphden.tenancy.domain-schema-integration-test
  "The :domain entity — incl. the Track C :app-label field — against real
   Postgres, resolved through the storage-backed HostResolver."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.tenancy.domain :as domain]
    [graphden.tenancy.domain-schema :as domain-schema]))


(use-fixtures :once (setup/create-container-fixture))


(deftest domain-app-label-roundtrips-and-resolves
  (pth/clean-database-fast! setup/*container*)
  (let [storage (pg/create-storage (pth/get-container-config setup/*container*))
        schema (-> (mds/create-builder)
                   (gds/extend-builder)
                   (domain-schema/extend-builder)
                   (ds/build))
        resolver (domain/storage-host-resolver storage)]
    (sp/initialize storage schema)
    (testing "a verified row WITH :app-label routes at that named app"
      (sp/create-entity storage :domain
                        {:hostname "shop.acme.com" :org "acme" :verified? true :app-label "shop"})
      (is (= {:org "acme" :label "shop"} (domain/target-for-host resolver "shop.acme.com"))))
    (testing "a verified row WITHOUT :app-label routes at the org's default app"
      (sp/create-entity storage :domain {:hostname "acme.com" :org "acme" :verified? true})
      (is (= {:org "acme"} (domain/target-for-host resolver "acme.com")))
      (is (nil? (:app-label (first (sp/query-entities storage :domain {:hostname "acme.com"}))))))
    (testing "an unverified row never resolves, even with an app-label"
      (sp/create-entity storage :domain
                        {:hostname "pending.acme.com" :org "acme" :verified? false :app-label "shop"})
      (is (nil? (domain/target-for-host resolver "pending.acme.com"))))
    (testing "hostname stays UNIQUE"
      (is (thrown? Exception
            (sp/create-entity storage :domain {:hostname "acme.com" :org "other" :verified? true}))))
    (sp/close storage)))

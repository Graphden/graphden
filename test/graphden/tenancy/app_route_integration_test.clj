(ns ^:integration graphden.tenancy.app-route-integration-test
  "The :app-route entity (an org's named apps, Track C) against real Postgres."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.tenancy.app-route :as app-route]
    [graphden.tenancy.app-route-schema :as app-route-schema]))


(use-fixtures :once (setup/create-container-fixture))


(defn- fresh-storage
  []
  (let [storage (pg/create-storage (pth/get-container-config setup/*container*))
        schema (-> (mds/create-builder)
                   (gds/extend-builder)
                   (app-route-schema/extend-builder)
                   (ds/build))]
    (sp/initialize storage schema)
    storage))


(deftest app-route-rows-roundtrip-and-resolve
  (pth/clean-database-fast! setup/*container*)
  (let [storage (fresh-storage)
        shop-fn (random-uuid)
        docs-fn (random-uuid)]
    (sp/create-entity storage :app-route {:org "acme" :label "shop" :handler-fn-id shop-fn})
    (sp/create-entity storage :app-route {:org "acme" :label "docs" :handler-fn-id docs-fn})
    (testing "handler-fn-id-for resolves the routing key (org, label)"
      (is (= shop-fn (app-route/handler-fn-id-for storage "acme" "shop")))
      (is (= docs-fn (app-route/handler-fn-id-for storage "acme" "docs"))))
    (testing "an unrouted (org, label) resolves to nil"
      (is (nil? (app-route/handler-fn-id-for storage "acme" "nope")))
      (is (nil? (app-route/handler-fn-id-for storage "other-org" "shop"))))
    (testing "the label is normalized before lookup (case / whitespace)"
      (is (= shop-fn (app-route/handler-fn-id-for storage "acme" "  SHOP "))))
    (testing "a blank label never resolves"
      (is (nil? (app-route/handler-fn-id-for storage "acme" "   ")))
      (is (nil? (app-route/handler-fn-id-for storage "acme" nil))))
    (testing "routes-for-org lists just that org's apps, sorted by label"
      (is (= ["docs" "shop"] (map :label (app-route/routes-for-org storage "acme"))))
      (is (empty? (app-route/routes-for-org storage "other-org"))))
    (sp/close storage)))


(deftest app-route-label-is-unique-per-org-not-across-orgs
  (pth/clean-database-fast! setup/*container*)
  (let [storage (fresh-storage)]
    (sp/create-entity storage :app-route {:org "acme" :label "shop" :handler-fn-id (random-uuid)})
    (testing "the same label may be routed by a DIFFERENT org"
      (sp/create-entity storage :app-route {:org "beta" :label "shop" :handler-fn-id (random-uuid)})
      (is (some? (app-route/handler-fn-id-for storage "beta" "shop"))))
    (testing "(org, label) is UNIQUE — one handler per app"
      (is (thrown? Exception
            (sp/create-entity storage :app-route
                              {:org "acme" :label "shop" :handler-fn-id (random-uuid)}))))
    (sp/close storage)))

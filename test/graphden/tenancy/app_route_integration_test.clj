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
    (testing "route-by-label resolves a GLOBAL label to its org + handler"
      (is (= {:org "acme" :handler-fn-id shop-fn} (select-keys (app-route/route-by-label storage "shop") [:org :handler-fn-id])))
      (is (= docs-fn (:handler-fn-id (app-route/route-by-label storage "docs")))))
    (testing "an unrouted label resolves to nil"
      (is (nil? (app-route/route-by-label storage "nope"))))
    (testing "the label is normalized before lookup (case / whitespace)"
      (is (= shop-fn (:handler-fn-id (app-route/route-by-label storage "  SHOP ")))))
    (testing "a blank label never resolves"
      (is (nil? (app-route/route-by-label storage "   ")))
      (is (nil? (app-route/route-by-label storage nil))))
    (testing "routes-for-org lists just that org's apps, sorted by label"
      (is (= ["docs" "shop"] (map :label (app-route/routes-for-org storage "acme"))))
      (is (empty? (app-route/routes-for-org storage "other-org"))))
    (sp/close storage)))


(deftest app-route-label-is-globally-unique
  (pth/clean-database-fast! setup/*container*)
  (let [storage (fresh-storage)
        shop-fn (random-uuid)]
    (sp/create-entity storage :app-route {:org "acme" :label "shop" :handler-fn-id shop-fn})
    (testing "the label identifies the app + its owner globally"
      (is (= "acme" (:org (app-route/route-by-label storage "shop")))))
    (testing "label is GLOBALLY UNIQUE — a different org can't claim the same label"
      (is (thrown? Exception
            (sp/create-entity storage :app-route
                              {:org "beta" :label "shop" :handler-fn-id (random-uuid)})))
      ;; still the original owner
      (is (= shop-fn (:handler-fn-id (app-route/route-by-label storage "shop")))))
    (sp/close storage)))

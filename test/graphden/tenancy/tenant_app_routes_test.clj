(ns ^:integration graphden.tenancy.tenant-app-routes-test
  "Tenant app-route CRUD seam (Track C4a) against real Postgres —
   `plan/create-tenant-app-route!` `:org`-stamps + validates the label, the
   list/update/delete seams are `:org`-scoped. `:app-route` stays tenant-
   forbidden, so these run through the platform base storage on the tenant's
   behalf."
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
    [graphden.tenancy.app-route-schema :as app-route-schema]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.plan :as plan]))


(use-fixtures :once (setup/create-container-fixture))


(defn- fresh-storage
  []
  (pth/clean-database-fast! setup/*container*)
  (let [storage (pg/create-storage (pth/get-container-config setup/*container*))]
    (sp/initialize storage (-> (mds/create-builder)
                               (gds/extend-builder)
                               (vts/extend-builder)
                               (vds/extend-builder)
                               (es/extend-builder)
                               (svcs/extend-builder)
                               (pkgs/extend-builder)
                               (app-route-schema/extend-builder)
                               (ds/build)))
    {:storage storage
     :handler-id (:id (sp/create-entity storage :fn {:name "landing"}))
     :handler2-id (:id (sp/create-entity storage :fn {:name "docs"}))}))


(deftest create-tenant-app-route!-gates-tenant-stamps-org-and-validates-label
  (let [{:keys [storage handler-id]} (fresh-storage)]
    (testing "no authenticated tenant (public / nil) → :authz/forbidden"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"authenticated tenant"
            (plan/create-tenant-app-route! storage tc/public-org {:label "shop" :handler-fn-id handler-id})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"authenticated tenant"
            (plan/create-tenant-app-route! storage nil {:label "shop" :handler-fn-id handler-id}))))

    (testing "an invalid label → :app-route/invalid-label, nothing written"
      (doseq [bad ["Has Space" "under_score" "-lead" "trail-" "UPPER.dot" ""]]
        (let [e (try (plan/create-tenant-app-route! storage "acme" {:label bad :handler-fn-id handler-id}) nil
                     (catch clojure.lang.ExceptionInfo ex ex))]
          (is (= :app-route/invalid-label (:type (ex-data e))) (str "rejected: " (pr-str bad)))))
      (is (empty? (sp/query-entities storage :app-route {})) "no invalid label persisted"))

    (testing "a valid label creates the row, :org stamped from the caller"
      (let [row (plan/create-tenant-app-route! storage "acme" {:label "  SHOP " :handler-fn-id handler-id})]
        (is (= "acme" (:org row)))
        (is (= "shop" (:label row)) "normalized (lower-cased + trimmed)")
        (is (= handler-id (:handler-fn-id row)))))

    (testing "a duplicate (org, label) is rejected by the UNIQUE constraint"
      (is (thrown? Exception
            (plan/create-tenant-app-route! storage "acme" {:label "shop" :handler-fn-id handler-id}))))

    (testing "the same label under a DIFFERENT org is fine"
      (is (some? (plan/create-tenant-app-route! storage "beta" {:label "shop" :handler-fn-id handler-id}))))))


(deftest list-tenant-app-routes!-returns-only-the-org-s-own
  (let [{:keys [storage handler-id]} (fresh-storage)]
    (sp/create-entity storage :app-route {:org "acme" :label "shop" :handler-fn-id handler-id})
    (sp/create-entity storage :app-route {:org "acme" :label "docs" :handler-fn-id handler-id})
    (sp/create-entity storage :app-route {:org "other" :label "shop" :handler-fn-id handler-id})
    (testing "a tenant sees only its own apps, sorted by label"
      (is (= ["docs" "shop"] (map :label (plan/list-tenant-app-routes! storage "acme")))))
    (testing "public / nil org → nil"
      (is (nil? (plan/list-tenant-app-routes! storage tc/public-org)))
      (is (nil? (plan/list-tenant-app-routes! storage nil))))))


(deftest update-and-delete-tenant-app-route!-are-ownership-gated
  (let [{:keys [storage handler-id handler2-id]} (fresh-storage)
        mine (sp/create-entity storage :app-route {:org "acme" :label "shop" :handler-fn-id handler-id})
        theirs (sp/create-entity storage :app-route {:org "other" :label "shop" :handler-fn-id handler-id})]
    (testing "an owned app retargets to a different handler (label immutable)"
      (plan/update-tenant-app-route! storage "acme" (:id mine) {:handler-fn-id handler2-id})
      (let [row (sp/read-entity storage :app-route (:id mine))]
        (is (= handler2-id (:handler-fn-id row)))
        (is (= "shop" (:label row)) "the routing key is unchanged")))
    (testing "another org's app can't be retargeted or deleted → :authz/forbidden"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
            (plan/update-tenant-app-route! storage "acme" (:id theirs) {:handler-fn-id handler2-id})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
            (plan/delete-tenant-app-route! storage "acme" (:id theirs))))
      (is (= handler-id (:handler-fn-id (sp/read-entity storage :app-route (:id theirs))))))
    (testing "an unknown id → :authz/forbidden (no existence leak)"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
            (plan/delete-tenant-app-route! storage "acme" (random-uuid)))))
    (testing "an owned app is deleted"
      (plan/delete-tenant-app-route! storage "acme" (:id mine))
      (is (nil? (sp/read-entity storage :app-route (:id mine)))))))

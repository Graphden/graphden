(ns ^:integration graphden.tenancy.tenant-services-test
  "Tenant service create / list seam (task #6 part 4 / FLEET_RFC §7.1) against
   real Postgres — `plan/create-tenant-service!` gates on the DEDICATED tier +
   `:max-services` cap and stamps `:org-id`; `plan/list-tenant-services!`
   filters by `:org-id`. `:service` stays tenant-forbidden (Option B), so these
   run through the platform base storage on the tenant's behalf."
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
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.org-schema :as org-schema]
    [graphden.tenancy.plan :as plan]))


(use-fixtures :once (setup/create-container-fixture))


(defn- build-schema
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


(defn- fresh-storage
  "A clean PG storage with the schema initialised + a `worker` fn to point
   services at, plus a `paid` (dedicated) and `basic` (free) org row."
  []
  (pth/clean-database-fast! setup/*container*)
  (let [storage (pg/create-storage (pth/get-container-config setup/*container*))]
    (sp/initialize storage (build-schema))
    (sp/create-entity storage :org {:name "paid" :plan "dedicated"})
    (sp/create-entity storage :org {:name "basic" :plan "free"})
    {:storage storage
     :worker-id (:id (sp/create-entity storage :fn {:name "worker"}))}))


(deftest create-tenant-service!-gates-on-tenant-and-tier
  (let [{:keys [storage worker-id]} (fresh-storage)
        data {:fn-id worker-id :enabled? true :restart-policy :always}]
    (testing "no authenticated tenant (public / nil org) → :authz/forbidden"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"authenticated tenant"
            (plan/create-tenant-service! storage tc/public-org data)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"authenticated tenant"
            (plan/create-tenant-service! storage nil data))))

    (testing "a shared-tier (free) org may NOT create a service → :authz/forbidden"
      (let [e (try (plan/create-tenant-service! storage "basic" data) nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= :authz/forbidden (:type (ex-data e))))
        (is (= :service/tier-required (:reason (ex-data e)))))
      (is (empty? (sp/query-entities storage :service {})) "nothing was written"))

    (testing "a DEDICATED-tier org creates the service, :org-id stamped from the
              caller (never from data)"
      (let [row (plan/create-tenant-service! storage "paid"
                                             (assoc data :org-id "someone-else"))]
        (is (= "paid" (:org-id row)) ":org-id is the caller's org, not data's")
        (is (= worker-id (:fn-id row)))
        (is (true? (:enabled? row)))
        (is (= :always (:restart-policy row)))
        (is (= 1 (count (sp/query-entities storage :service {}))))))))


(deftest create-tenant-service!-enforces-the-max-services-cap
  (let [{:keys [storage worker-id]} (fresh-storage)
        data {:fn-id worker-id :enabled? true :restart-policy :always}]
    ;; Shrink the dedicated cap to 1 so the test doesn't create 20 rows.
    (with-redefs [plan/plans (assoc-in plan/plans ["dedicated" :max-services] 1)]
      (testing "the first service is under the (redefed) cap of 1"
        (is (some? (plan/create-tenant-service! storage "paid" data))))
      (testing "the second trips the cap → :quota/service-limit, nothing written"
        (let [e (try (plan/create-tenant-service! storage "paid" data) nil
                     (catch clojure.lang.ExceptionInfo ex ex))]
          (is (= :quota/service-limit (:type (ex-data e))))
          (is (re-find #"service limit" (ex-message e))))
        (is (= 1 (count (sp/query-entities storage :service {})))
            "the rejected create wrote nothing")))))


(deftest list-tenant-services!-returns-only-the-org-s-own-rows
  (let [{:keys [storage worker-id]} (fresh-storage)]
    ;; Seed via base with explicit org-ids (bypassing the gate — just fixtures):
    ;; two for "paid", one for "other", one PLATFORM service (no org-id).
    (sp/create-entity storage :service {:fn-id worker-id :enabled? true :restart-policy :always :org-id "paid"})
    (sp/create-entity storage :service {:fn-id worker-id :enabled? false :restart-policy :always :org-id "paid"})
    (sp/create-entity storage :service {:fn-id worker-id :enabled? true :restart-policy :always :org-id "other"})
    (sp/create-entity storage :service {:fn-id worker-id :enabled? true :restart-policy :always})
    (testing "a tenant sees ONLY its own services (not other orgs', not platform)"
      (let [rows (plan/list-tenant-services! storage "paid")]
        (is (= 2 (count rows)))
        (is (every? #(= "paid" (:org-id %)) rows))))
    (testing "the public org / no org → nil (not a tenant view)"
      (is (nil? (plan/list-tenant-services! storage tc/public-org)))
      (is (nil? (plan/list-tenant-services! storage nil))))))


(deftest update-tenant-service!-mutates-only-owned-rows-and-never-the-owner
  (let [{:keys [storage worker-id]} (fresh-storage)
        mine (sp/create-entity storage :service
                               {:fn-id worker-id :enabled? true :restart-policy :always :org-id "paid"})
        others (sp/create-entity storage :service
                                 {:fn-id worker-id :enabled? true :restart-policy :always :org-id "other"})
        platform (sp/create-entity storage :service
                                   {:fn-id worker-id :enabled? true :restart-policy :always})]
    (testing "an owned service updates the writable fields (but NOT :org-id)"
      (plan/update-tenant-service! storage "paid" (:id mine)
                                   {:fn-id worker-id :enabled? false :restart-policy :never
                                    :org-id "hijack"})
      (let [row (sp/read-entity storage :service (:id mine))]
        (is (false? (:enabled? row)))
        (is (= :never (:restart-policy row)))
        (is (= "paid" (:org-id row)) ":org-id is immutable — not reassignable")))
    (testing "another org's service → :authz/forbidden, untouched"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
            (plan/update-tenant-service! storage "paid" (:id others)
                                         {:fn-id worker-id :enabled? false :restart-policy :always})))
      (is (true? (:enabled? (sp/read-entity storage :service (:id others))))))
    (testing "a PLATFORM service (nil :org-id) → :authz/forbidden"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
            (plan/update-tenant-service! storage "paid" (:id platform)
                                         {:fn-id worker-id :enabled? false :restart-policy :always}))))
    (testing "an unknown id → :authz/forbidden (no existence leak)"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
            (plan/update-tenant-service! storage "paid" (random-uuid)
                                         {:fn-id worker-id :restart-policy :always}))))))


(deftest delete-tenant-service!-removes-only-owned-rows
  (let [{:keys [storage worker-id]} (fresh-storage)
        mine (sp/create-entity storage :service
                               {:fn-id worker-id :enabled? true :restart-policy :always :org-id "paid"})
        others (sp/create-entity storage :service
                                 {:fn-id worker-id :enabled? true :restart-policy :always :org-id "other"})]
    (testing "another org's service can't be deleted → :authz/forbidden, survives"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
            (plan/delete-tenant-service! storage "paid" (:id others))))
      (is (some? (sp/read-entity storage :service (:id others)))))
    (testing "an owned service is deleted"
      (plan/delete-tenant-service! storage "paid" (:id mine))
      (is (nil? (sp/read-entity storage :service (:id mine)))))))

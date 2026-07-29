(ns ^:serial graphden.tenancy.plan-test
  "Per-org plan → effect allow-list + row-cap (tasks #4, #7). `^:serial` because
   `install!-wires-the-seams` resets the process-global resolver atoms for the
   duration of one test."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.plan :as plan]
    [graphden.tenancy.storage :as ts]))


(defn- org-store
  "Minimal storage answering `query-entities :org {:name n}` from a
   {name → org-row} map."
  [by-name]
  (reify sp/StorageCRUD
    (read-entity [_ _ _] nil)

    (create-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-entities
      [_ entity-name filt]
      (when (= entity-name :org)
        (some-> (get by-name (:name filt)) vector)))

    (query-entities
      [_ entity-name filt _]
      (when (= entity-name :org)
        (some-> (get by-name (:name filt)) vector)))

    (query-latest-per-group [_ _ _ _] nil)))


(deftest allowed-effects-for-resolves-the-org-plan
  (let [store (org-store {"acme"   {:name "acme"   :plan "network"}
                          "globex" {:name "globex" :plan nil}
                          "weird"  {:name "weird"  :plan "bogus"}})]
    (testing "a paid plan widens the effect allow-list with :network"
      (is (= (conj cr/default-cloud-allowed-effects :network)
             (plan/allowed-effects-for store "acme")))
      (is (contains? (plan/allowed-effects-for store "acme") :network)))
    (testing "nil / unknown plan → the locked free default (no :network)"
      (is (= cr/default-cloud-allowed-effects (plan/allowed-effects-for store "globex")))
      (is (= cr/default-cloud-allowed-effects (plan/allowed-effects-for store "weird")))
      (is (not (contains? (plan/allowed-effects-for store "globex") :network))))
    (testing "the public / platform org is never a tenant → free"
      (is (= cr/default-cloud-allowed-effects (plan/allowed-effects-for store tc/public-org))))
    (testing "a missing org → free"
      (is (= cr/default-cloud-allowed-effects (plan/allowed-effects-for store "nope"))))))


(deftest dedicated-plan-grants-process-so-services-can-actually-run
  ;; The dedicated tier SELLS services; a service spawns its supervised thread
  ;; via `:future`, which records `:process`. Without `:process` in the plan the
  ;; effect gate (`cr/run-service-scoped`) would block every service start — the
  ;; tier would grant services it can't run. It must NOT grant `:raw-sql` though:
  ;; the dedicated pod shares the platform Postgres, so raw SQL is cross-tenant.
  (let [store (org-store {"paid" {:name "paid" :plan "dedicated"}})
        fx (plan/allowed-effects-for store "paid")]
    (is (contains? fx :process) "services need :process to spawn")
    (is (contains? fx :network) "dedicated keeps network's grant")
    (is (not (contains? fx :raw-sql))
        "platform DB stays off-limits even on a dedicated pod (shared Postgres)")))


(deftest over-entity-quota?-never-caps-the-public-org
  ;; The public / platform org holds the shared core+web+app graph (thousands of
  ;; fns); it must never resolve a cap. This short-circuits before `fn-count`, so
  ;; a pool-less stub storage suffices — no PG needed.
  (let [store (org-store {})]
    (is (false? (plan/over-entity-quota? store tc/public-org :fn)))
    (is (false? (plan/over-entity-quota? store tc/public-org :binding-list-item)))
    (is (false? (plan/over-entity-quota? store nil :fn)))
    (is (false? (plan/over-entity-quota? store "acme" :slot))
        "an ungated entity is never over-quota")))


(deftest dedicated-executor?-gates-services-to-the-dedicated-tier
  ;; The SERVICE gate (task #6, FLEET_RFC §7.1): only the `dedicated` tier — which
  ;; is provisioned its own cgroup-limited pod — may create services. The shared
  ;; free/network tiers cannot, because a persistent tenant service is unsafe on a
  ;; runtime the tenant shares.
  (let [store (org-store {"paid"    {:name "paid"    :plan "dedicated"}
                          "acme"    {:name "acme"    :plan "network"}
                          "globex"  {:name "globex"  :plan nil}
                          "weird"   {:name "weird"   :plan "bogus"}})]
    (testing "the dedicated tier grants a dedicated executor + a service allowance"
      (is (true? (plan/dedicated-executor? store "paid")))
      (is (= 20 (plan/max-services-for store "paid"))))
    (testing "the shared paid tier (network) does NOT grant services"
      (is (false? (plan/dedicated-executor? store "acme")))
      (is (zero? (plan/max-services-for store "acme"))))
    (testing "free / unknown / missing slug → no services"
      (is (false? (plan/dedicated-executor? store "globex")))
      (is (false? (plan/dedicated-executor? store "weird")))
      (is (false? (plan/dedicated-executor? store "nope")))
      (is (zero? (plan/max-services-for store "globex"))))
    (testing "the public / platform org is not a tenant service gate → false / 0"
      (is (false? (plan/dedicated-executor? store tc/public-org)))
      (is (zero? (plan/max-services-for store tc/public-org))))))


(deftest install!-wires-the-seams
  (let [store (org-store {"acme" {:name "acme" :plan "network"}})
        saved-fx @cr/cloud-allowed-effects-resolver
        saved-q @ts/entity-quota-exceeded?
        saved-create @ts/create-tenant-service-fn
        saved-list @ts/list-tenant-services-fn
        saved-update @ts/update-tenant-service-fn
        saved-delete @ts/delete-tenant-service-fn]
    (try
      (plan/install! store)
      (testing "after install the effect seam resolves per-org"
        (is (contains? (cr/cloud-allowed-effects-for "acme") :network))
        (is (= cr/default-cloud-allowed-effects (cr/cloud-allowed-effects-for "globex"))
            "an unknown org still falls back to free through the seam"))
      (testing "after install the row-cap seam is a live resolver"
        (is (fn? @ts/entity-quota-exceeded?))
        (is (false? (@ts/entity-quota-exceeded? tc/public-org :fn))
            "the seam never caps the public org"))
      (testing "after install the tenant service create / list / update / delete seams are live"
        (is (fn? @ts/create-tenant-service-fn))
        (is (fn? @ts/list-tenant-services-fn))
        (is (fn? @ts/update-tenant-service-fn))
        (is (fn? @ts/delete-tenant-service-fn))
        (is (nil? (@ts/list-tenant-services-fn tc/public-org))
            "the list seam never returns rows for the public org"))
      (testing "uninstall clears EVERY seam (lifecycle-bound, no cross-test leak)"
        (plan/uninstall!)
        (is (nil? @cr/cloud-allowed-effects-resolver))
        (is (nil? @ts/entity-quota-exceeded?))
        (is (nil? @ts/create-tenant-service-fn))
        (is (nil? @ts/list-tenant-services-fn))
        (is (nil? @ts/update-tenant-service-fn))
        (is (nil? @ts/delete-tenant-service-fn)))
      (finally
        (reset! cr/cloud-allowed-effects-resolver saved-fx)
        (reset! ts/entity-quota-exceeded? saved-q)
        (reset! ts/create-tenant-service-fn saved-create)
        (reset! ts/list-tenant-services-fn saved-list)
        (reset! ts/update-tenant-service-fn saved-update)
        (reset! ts/delete-tenant-service-fn saved-delete)))))

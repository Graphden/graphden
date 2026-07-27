(ns ^:serial graphden.tenancy.plan-test
  "Per-org plan → effect allow-list (task #4). `^:serial` because
   `install!-wires-the-compile-runtime-seam` resets the process-global
   `cr/cloud-allowed-effects-resolver` atom for the duration of one test."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.plan :as plan]))


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


(deftest install!-wires-the-compile-runtime-seam
  (let [store (org-store {"acme" {:name "acme" :plan "network"}})
        saved @cr/cloud-allowed-effects-resolver]
    (try
      (plan/install! store)
      (testing "after install the seam resolves effects per-org"
        (is (contains? (cr/cloud-allowed-effects-for "acme") :network))
        (is (= cr/default-cloud-allowed-effects (cr/cloud-allowed-effects-for "globex"))
            "an unknown org still falls back to free through the seam"))
      (finally (reset! cr/cloud-allowed-effects-resolver saved)))))

(ns graphden.tenancy.operator-bootstrap-test
  "Track A2c — the boot-time operator seed: create-if-absent org + user +
   platform-admin grant, no-op without a password, idempotent."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.grant :as grant]
    [graphden.tenancy.grant-schema :as grant-schema]
    [graphden.tenancy.operator-bootstrap :as ob]))


;; Minimal in-memory StorageCRUD (the seed uses only create/query).
(defrecord Fake
  [rows]

  sp/StorageCRUD

  (create-entity
    [_ en data]
    (let [row (assoc data :id (or (:id data) (str (name en) "-" (count (get @rows en)))))]
      (swap! rows update en (fnil conj []) row) row))


  (read-entity [_ en id] (first (filter #(= id (:id %)) (get @rows en))))


  (update-entity
    [this en id data]
    (swap! rows update en (partial mapv #(if (= id (:id %)) (merge % data) %)))
    (sp/read-entity this en id))


  (delete-entity [_ _ _] nil)


  (query-entities
    [_ en where]
    (filterv (fn [r] (every? (fn [[k v]] (= (get r k) v)) where)) (get @rows en)))


  (query-entities [this en where _] (sp/query-entities this en where))


  (query-latest-per-group [this en where _] (sp/query-entities this en where)))


(defn- fake
  []
  (->Fake (atom {})))


(deftest no-password-is-a-noop
  (let [s (fake)]
    (is (= {:skipped :no-password} (ob/bootstrap! s {:password ""})))
    (is (= {:skipped :no-password} (ob/bootstrap! s {:password nil})))
    (is (empty? (sp/query-entities s :user {})) "nothing seeded")))


(deftest seeds-org-user-and-platform-admin-grant
  (let [s (fake)
        r (ob/bootstrap! s {:password "s3cret-operator-pw" :user "op" :org "graphden" :plan "network"})]
    (testing "summary"
      (is (= {:org "graphden" :user "op" :user-created? true :grant-created? true} r)))
    (testing "the org is a NORMAL tenant on the network plan"
      (let [org (first (sp/query-entities s :org {:name "graphden"}))]
        (is (= "network" (:plan org)))))
    (testing "the user carries a password hash (never the plaintext) + its org"
      (let [u (first (sp/query-entities s :user {:username "op"}))]
        (is (= "graphden" (:org u)))
        (is (string? (:password-hash u)))
        (is (not= "s3cret-operator-pw" (:password-hash u)))))
    (testing "the grant is platform-admin, and the storage grant-store keys
              the operator on it — the A2b enforcement recognises them"
      (let [u (first (sp/query-entities s :user {:username "op"}))
            gstore (grant-schema/storage-grant-store s)]
        (is (grant/platform-admin? gstore {:id (str (:id u)) :name "op" :org "graphden"}))))))


(deftest idempotent-rerun-never-clobbers-nor-duplicates
  (let [s (fake)]
    (ob/bootstrap! s {:password "first-password-xx" :user "op" :org "graphden"})
    (let [hash1 (:password-hash (first (sp/query-entities s :user {:username "op"})))
          r2 (ob/bootstrap! s {:password "DIFFERENT-password" :user "op" :org "graphden"})]
      (testing "rerun creates nothing new"
        (is (= {:org "graphden" :user "op" :user-created? false :grant-created? false} r2))
        (is (= 1 (count (sp/query-entities s :org {:name "graphden"}))))
        (is (= 1 (count (sp/query-entities s :user {:username "op"}))))
        (is (= 1 (count (sp/query-entities s :grant {})))))
      (testing "an in-app password change is NOT clobbered by a redeploy"
        (is (= hash1 (:password-hash (first (sp/query-entities s :user {:username "op"})))))))))

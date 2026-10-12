(ns ^:serial graphden.graph.personal-home-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.graph.metadata :as metadata]
    [graphden.graph.personal-home :as home]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]))


(deftest configured-missing-home-never-invents-a-namespace-or-grant
  (doseq [authority [nil {:namespace-path "users.alice" :source :tenancy :isolated? true}
                     {:namespace-path "another" :source :single-operator :isolated? false}]]
    (with-redefs [tenancy/current-personal-namespace (constantly authority)
                  metadata/namespace-row (constantly nil)
                  sp/create-entity (fn [& _] (throw (AssertionError. "Unexpected CREATE")))]
      (is (= :personal-home-unavailable
             (try (home/ensure-current! {}) nil
                  (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))))))


(deftest explicit-single-operator-setup-is-idempotent-and-creates-only-the-home
  (let [id (random-uuid) actual (atom nil) writes (atom [])]
    (with-redefs [tenancy/current-personal-namespace
                  (constantly {:namespace-path "personal" :source :single-operator :isolated? false})
                  metadata/namespace-row (fn [_ path _] (is (= "personal" path)) @actual)
                  sp/create-entity (fn [_ entity data]
                                     (swap! writes conj [entity data])
                                     (reset! actual (assoc data :id id)))]
      (is (= id (:id (home/ensure-current! {}))))
      (is (= id (:id (home/ensure-current! {}))))
      (is (= [[:ns {:name "personal"}]] @writes)))))


(deftest provisioned-tenancy-home-is-returned-without-changing-its-metadata
  (let [row {:id (random-uuid) :name "alice" :parent-id (random-uuid) :org-id "acme"}]
    (with-redefs [tenancy/current-personal-namespace
                  (constantly {:namespace-path "users.alice" :source :tenancy :isolated? true})
                  metadata/namespace-row (fn [_ path _] (is (= "users.alice" path)) row)
                  sp/create-entity (fn [& _] (throw (AssertionError. "Unexpected CREATE")))]
      (is (= row (home/ensure-current! {}))))))

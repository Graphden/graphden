(ns graphden.editor.components-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.editor.components :as components]
    [graphden.storage.postgres.graph-epoch :as graph-epoch]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router.epoch :as router-epoch]
    [graphden.tenancy.context :as tenancy]
    [graphden.versioning.storage.core :as versioned]))


(def ^:private fn-id (java.util.UUID/randomUUID))
(def ^:private branch-id (java.util.UUID/randomUUID))


(defn- preference-storage
  [selection]
  (let [row {:id (random-uuid) :owner-id "alice" :key "components" :value selection}]
    (reify sp/StorageCRUD
      (create-entity [_ _ _] (throw (UnsupportedOperationException. "Read-only preference fixture")))

      (update-entity [_ _ _ _] (throw (UnsupportedOperationException. "Read-only preference fixture")))

      (delete-entity [_ _ _] (throw (UnsupportedOperationException. "Read-only preference fixture")))

      (read-entity
        [_ entity id]
        (when (and (= :ui-pref entity) (= id (:id row))) row))

      (query-entities
        [this entity where]
        (sp/query-entities this entity where {}))

      (query-entities
        [_ entity where opts]
        (is (= :ui-pref entity))
        (is (= {:owner-id "alice" :key "components"} where))
        (->> [row]
             (filter #(every? (fn [[field value]] (= value (get % field))) where))
             (drop (:offset opts 0))
             (take (:limit opts 1))
             vec))

      (query-latest-per-group
        [this entity where _]
        (sp/query-entities this entity where)))))


(deftest selected-identity-is-owner-org-and-branch-scoped
  (let [selection {"fn-id" (str fn-id) "org" "org-a" "branch-id" (str branch-id)}]
    (with-redefs [tenancy/current-user-id (constantly "alice")
                  tenancy/current-org (constantly "org-a")
                  versioned/current-branch-id (constantly branch-id)]
      (is (= fn-id (components/selected-id (preference-storage selection))))
      (doseq [changed [(assoc selection "org" "org-b")
                       (assoc selection "branch-id" (str (java.util.UUID/randomUUID)))
                       (assoc selection :fn-id (str fn-id))
                       (assoc selection "fn-id" "not-an-identity")
                       nil]]
        (is (thrown? clojure.lang.ExceptionInfo
              (components/selected-id (preference-storage changed))))))))


(deftest unpublished-policy-does-not-authorize-export
  (with-redefs [router-epoch/validated-watermark (constantly 12)
                graph-epoch/epoch-handle identity]
    (testing "a fully applied source can be exported"
      (with-redefs [graph-epoch/current (constantly 12)]
        (is (nil? (components/assert-current-policy! {})))))
    (testing "committed, in-flight and unavailable source epochs fail closed"
      (doseq [epoch [13 100 nil]]
        (with-redefs [graph-epoch/current (constantly epoch)]
          (is (thrown? clojure.lang.ExceptionInfo
                (components/assert-current-policy! {}))))))))

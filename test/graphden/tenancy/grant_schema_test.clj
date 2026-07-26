(ns graphden.tenancy.grant-schema-test
  "Storage-backed grants (PLATFORM_PLAN §4.2): the :grant entity, the
   :db/schema extension seam, and the StorageBackedGrantStore."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.core]
    [graphden.tenancy.grant :as grant]
    [graphden.tenancy.grant-schema :as grant-schema]
    [integrant.core :as ig]))


;; Matching keys on the stable subject-id; in tests id = name = the string.
(defn- subj
  [s]
  {:id s :name s})


(deftest extend-builder-adds-the-grant-entity
  (let [schema (-> (mds/create-builder)
                   (gds/extend-builder)
                   (grant-schema/extend-builder)
                   (ds/build))]
    (is (contains? (set (ds/entities schema)) :grant))
    (is (every? (set (keys (ds/entity-fields schema :grant)))
                [:subject-id :capability :namespace])
        ":grant carries subject-id / capability / namespace")
    (is (nil? (get (ds/entity-fields schema :grant) :subject))
        "denormalized username column retired (audit-2 2b)")))


(deftest db-schema-seam-applies-extensions
  (testing "core schema has no :grant"
    (is (not (contains? (set (ds/entities (ig/init-key :db/schema {}))) :grant))))
  (testing "an extension splices :grant in"
    (is (contains? (set (ds/entities (ig/init-key :db/schema
                                                  {:extensions [grant-schema/extend-builder]})))
                   :grant))))


(defn- grant-storage
  "Storage whose :grant query returns `rows` matching `:subject-id` (the
   stable key the store now queries on)."
  [rows]
  (reify sp/StorageCRUD
    (query-entities
      [_ entity-name where]
      (when (= entity-name :grant)
        (filterv #(= (:subject-id %) (:subject-id where)) rows)))

    (query-entities [_ _ _ _] nil)

    (create-entity [_ _ _] nil)

    (read-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)))


(deftest storage-backed-store-reads-and-keywordizes
  (let [store (grant-schema/storage-grant-store
                (grant-storage
                  [{:subject-id "alice" :subject "alice" :capability "write" :namespace "acme"}
                   {:subject-id "alice" :subject "alice" :capability "admin" :namespace "acme.ops"}]))]
    (testing "grants-for queries by subject-id + keywordizes the capability"
      (is (= [{:subject-kind nil :subject-id "alice" :capability :write :namespace "acme"}
              {:subject-kind nil :subject-id "alice" :capability :admin :namespace "acme.ops"}]
             (grant/grants-for store (subj "alice")))))
    (testing "can? works over the storage-backed grants"
      (is (grant/can? store (subj "alice") :write "acme.billing") "descendant of acme")
      (is (grant/can? store (subj "alice") :write "acme.ops") "admin subsumes :write")
      (is (not (grant/can? store (subj "alice") :read "acme")) "no :read grant"))
    (testing "unknown subject → no grants"
      (is (empty? (grant/grants-for store (subj "bob")))))))

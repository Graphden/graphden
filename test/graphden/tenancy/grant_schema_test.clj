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


(deftest extend-builder-adds-the-grant-entity
  (let [schema (-> (mds/create-builder)
                   (gds/extend-builder)
                   (grant-schema/extend-builder)
                   (ds/build))]
    (is (contains? (set (ds/entities schema)) :grant))
    (is (every? (set (keys (ds/entity-fields schema :grant)))
                [:subject :capability :namespace])
        ":grant carries subject / capability / namespace")))


(deftest db-schema-seam-applies-extensions
  (testing "core schema has no :grant"
    (is (not (contains? (set (ds/entities (ig/init-key :db/schema {}))) :grant))))
  (testing "an extension splices :grant in"
    (is (contains? (set (ds/entities (ig/init-key :db/schema
                                                  {:extensions [grant-schema/extend-builder]})))
                   :grant))))


(defn- grant-storage
  "Storage whose :grant query returns `rows` for matching `:subject`."
  [rows]
  (reify sp/StorageCRUD
    (query-entities
      [_ entity-name where]
      (when (= entity-name :grant)
        (filterv #(= (:subject %) (:subject where)) rows)))

    (query-entities [_ _ _ _] nil)

    (create-entity [_ _ _] nil)

    (read-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-latest-per-group [_ _ _ _] nil)))


(deftest storage-backed-store-reads-and-keywordizes
  (let [store (grant-schema/storage-grant-store
                (grant-storage
                  [{:subject "alice" :capability "write" :namespace "acme"}
                   {:subject "alice" :capability "admin" :namespace "acme.ops"}]))]
    (testing "grants-for queries by subject + keywordizes the capability"
      (is (= [{:subject "alice" :capability :write :namespace "acme"}
              {:subject "alice" :capability :admin :namespace "acme.ops"}]
             (grant/grants-for store "alice"))))
    (testing "can? works over the storage-backed grants"
      (is (grant/can? store "alice" :write "acme.billing") "descendant of acme")
      (is (grant/can? store "alice" :write "acme.ops") "admin subsumes :write")
      (is (not (grant/can? store "alice" :read "acme")) "no :read grant"))
    (testing "unknown subject → no grants"
      (is (empty? (grant/grants-for store "bob"))))))

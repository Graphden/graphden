(ns ^:integration graphden.library.base-fns.core.storage-sync-test
  "Tests for syncing base functions to storage.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.library.base-fns.core :as bf]
    [graphden.library.base-fns.core.test-helpers :as h]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]))


;; Container for PostgreSQL tests
(def ^:dynamic *container* nil)


(use-fixtures :once (pth/create-container-fixture #'*container*))


(use-fixtures :each
  (pth/create-clean-db-fixture #'*container*)
  exec/with-clean-registry)


(defn- create-test-storage
  "Creates a PostgreSQL storage from the current test container.
   Cleans the database and initializes schema before creating storage."
  []
  (pth/clean-database-fast! *container*)
  (let [storage (pg/create-storage (pth/get-container-config *container*))
        schema (gds/build-schema (mds/create-builder))]
    (sp/initialize storage schema)
    storage))


(deftest sync-to-storage-test
  (testing "sync-storage! creates base fns (parent-id=nil) and their args"
    (let [storage (create-test-storage)]
      (try
        ;; First sync - should create all
        (let [result (h/sync-storage! storage)]
          (is (pos? (:created (:fns result))))
          (is (zero? (:updated (:fns result))))
          (is (pos? (:created (:args result))))
          (is (zero? (:updated (:args result)))))

        ;; Verify some base fns exist (parent-id=nil)
        (let [all-fns (sp/query-entities storage :fn {})]
          (is (pos? (count all-fns)))
          ;; Check :add fn exists with parent-id=nil (base fn)
          (is (some #(and (= "add" (:name %))
                          (nil? (:parent-id %))) all-fns)))

        ;; Verify args exist for base fns
        (let [all-args (sp/query-entities storage :arg {})]
          (is (pos? (count all-args)))
          ;; Find :add fn
          (let [add-fn (first (sp/query-entities storage :fn {:name "add"}))
                add-args (sp/query-entities storage :arg {:fn-id (:id add-fn)})]
            (is (= 1 (count add-args)))
            (is (= #{"nums"} (set (map :name add-args))))))
        (finally
          (sp/close storage)))))

  (testing "sync-storage! is idempotent"
    (let [storage (create-test-storage)]
      (try
        ;; First sync
        (h/sync-storage! storage)
        (let [fns-after-first (sp/query-entities storage :fn {})]
          ;; Second sync - should update, not create
          (let [result (h/sync-storage! storage)]
            (is (zero? (:created (:fns result))))
            (is (pos? (:updated (:fns result))))
            (is (zero? (:created (:args result))))
            (is (pos? (:updated (:args result)))))
          ;; Same count of fns
          (is (= (count fns-after-first)
                 (count (sp/query-entities storage :fn {})))))
        (finally
          (sp/close storage)))))

  (testing "get-all-defs returns function definitions"
    (let [defs bf/all-defs]
      (is (map? defs))
      (is (contains? defs :add))
      (is (contains? defs :map))
      (is (= {:nums :jsonb} (:args (:add defs))))
      (is (= :numeric (:return-type (:add defs)))))))

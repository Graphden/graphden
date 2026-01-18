(ns graphden.base-functions.storage-sync-test
  "Tests for syncing base functions to storage."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.base-functions.interface :as bf]
    [graphden.base-functions.test-helpers :as h]
    [graphden.executor.interface :as exec]
    [graphden.graph-storage-memory.interface :as gsm]
    [graphden.storage-protocol.interface :as sp]))


(use-fixtures :each exec/with-clean-registry)


(deftest sync-to-storage-test
  (testing "sync-storage! creates fn-schemas and arg-schemas"
    (let [storage (gsm/create-storage)]
      (try
        ;; First sync - should create all
        (let [result (h/sync-storage! storage)]
          (is (pos? (:created (:fn-schemas result))))
          (is (zero? (:updated (:fn-schemas result))))
          (is (pos? (:created (:arg-schemas result))))
          (is (zero? (:updated (:arg-schemas result)))))

        ;; Verify some fn-schemas exist
        (let [all-schemas (sp/query-entities storage :fn-schema {})]
          (is (pos? (count all-schemas)))
          ;; Check :add fn-schema exists
          (is (some #(= "add" (:name %)) all-schemas))
          ;; Check it has base-fn-name set
          (is (some #(= "add" (:base-fn-name %)) all-schemas)))

        ;; Verify arg-schemas exist
        (let [all-args (sp/query-entities storage :arg-schema {})]
          (is (pos? (count all-args)))
          ;; Find :add's fn-schema
          (let [add-schema (first (sp/query-entities storage :fn-schema {:name "add"}))
                add-args (sp/query-entities storage :arg-schema {:fn-schema-id (:id add-schema)})]
            (is (= 1 (count add-args)))
            (is (= #{"nums"} (set (map :name add-args))))))
        (finally
          (sp/close storage)))))

  (testing "sync-storage! is idempotent"
    (let [storage (gsm/create-storage)]
      (try
        ;; First sync
        (h/sync-storage! storage)
        (let [schemas-after-first (sp/query-entities storage :fn-schema {})]
          ;; Second sync - should update, not create
          (let [result (h/sync-storage! storage)]
            (is (zero? (:created (:fn-schemas result))))
            (is (pos? (:updated (:fn-schemas result))))
            (is (zero? (:created (:arg-schemas result))))
            (is (pos? (:updated (:arg-schemas result)))))
          ;; Same count of schemas
          (is (= (count schemas-after-first)
                 (count (sp/query-entities storage :fn-schema {})))))
        (finally
          (sp/close storage)))))

  (testing "get-all-defs returns function definitions"
    (let [defs (bf/get-all-defs)]
      (is (map? defs))
      (is (contains? defs :add))
      (is (contains? defs :map))
      (is (= {:nums :jsonb} (:args (:add defs))))
      (is (= :numeric (:return-type (:add defs)))))))

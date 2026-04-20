(ns graphden.executor.registry.interface-test
  "Tests for executor.registry.interface - public API for base function registration."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.registry.core :as core]
    [graphden.executor.registry.interface :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each (setup/create-clean-db-fixture))


;; =============================================================================
;; Delegation Tests
;; =============================================================================

(deftest fn-uuid-delegation-test
  (testing "delegates to core/fn-uuid"
    (let [result (registry/fn-uuid :test-fn)]
      (is (uuid? result))
      (is (= result (core/fn-uuid :test-fn))))))


(deftest arg-uuid-delegation-test
  (testing "delegates to core/arg-uuid"
    (let [result (registry/arg-uuid :test-fn :test-arg)]
      (is (uuid? result))
      (is (= result (core/arg-uuid :test-fn :test-arg))))))


(deftest register-base-fns!-delegation-test
  (testing "delegates to core/register-base-fns!"
    (let [defs {:test-fn {:args {:x :int}
                          :return-type :int
                          :impl (fn [_ _] 42)}}]
      ;; Should not throw
      (is (nil? (registry/register-base-fns! defs))))))


(deftest sync-defs-to-storage!-delegation-test
  (testing "delegates to core/sync-defs-to-storage!"
    (let [storage (setup/create-test-storage)
          defs {:iface-test-fn {:args {:a :int}
                                :return-type :int
                                :impl (fn [_ _] nil)}}
          result (registry/sync-defs-to-storage! storage defs)]
      (is (map? result))
      (is (= 1 (get-in result [:fns :created])))
      (sp/close storage))))


;; =============================================================================
;; initialize-with-base-fns! Tests
;; =============================================================================

(deftest initialize-with-base-fns!-test
  (testing "initializes storage with base functions"
    (let [storage (setup/create-test-storage)
          result (registry/initialize-with-base-fns! storage)]
      ;; Should return the storage
      (is (= storage result))
      ;; Check that some base fns exist
      (let [add-id (core/fn-uuid :add)
            add-fn (sp/read-entity storage :fn add-id)]
        (is (some? add-fn))
        (is (= "add" (:name add-fn))))
      (sp/close storage)))

  (testing "closes storage and re-throws on error"
    (let [closed? (atom false)
          mock-storage (reify
                         sp/Storage
                         (initialize [_ _] nil)

                         (close [_] (reset! closed? true))


                         sp/StorageCRUD

                         (read-entity
                           [_ _ _]
                           (throw (ex-info "Mock read error" {:type :test-error})))

                         (create-entity
                           [_ _ _]
                           (throw (ex-info "Mock create error" {:type :test-error})))

                         (update-entity
                           [_ _ _ _]
                           (throw (ex-info "Mock update error" {:type :test-error})))

                         (delete-entity
                           [_ _ _]
                           (throw (ex-info "Mock delete error" {:type :test-error})))

                         (query-entities
                           [_ _ _]
                           (throw (ex-info "Mock query error" {:type :test-error})))


                         sp/StorageBatchCRUD

                         (create-entities
                           [_ _ _]
                           [])

                         (read-entities
                           [_ _ _]
                           {})

                         (update-entities
                           [_ _ _]
                           [])

                         (delete-entities
                           [_ _ _]
                           0)

                         (upsert-entities
                           [_ _ _]
                           (throw (ex-info "Mock upsert error" {:type :test-error}))))]
      (is (thrown? clojure.lang.ExceptionInfo
            (registry/initialize-with-base-fns! mock-storage)))
      (is @closed? "Storage should be closed on error"))))


;; =============================================================================
;; initialize-all! Tests
;; =============================================================================

(deftest initialize-all!-test
  (testing "initializes storage with multiple def-sets"
    (let [storage (setup/create-test-storage)
          defs1 {:init-all-fn1 {:args {:x :int} :return-type :int :impl (fn [_ _] 1)}}
          defs2 {:init-all-fn2 {:args {:y :text} :return-type :text :impl (fn [_ _] "hi")}}
          result (registry/initialize-all! storage [defs1 defs2])]
      ;; Should return the storage
      (is (= storage result))
      ;; Check both fns exist
      (let [fn1-id (core/fn-uuid :init-all-fn1)
            fn2-id (core/fn-uuid :init-all-fn2)]
        (is (some? (sp/read-entity storage :fn fn1-id)))
        (is (some? (sp/read-entity storage :fn fn2-id))))
      (sp/close storage)))

  (testing "handles empty def-sets"
    (let [storage (setup/create-test-storage)
          result (registry/initialize-all! storage [])]
      (is (= storage result))
      (sp/close storage))))


;; =============================================================================
;; create-storage-with-base-fns Tests
;; =============================================================================

(deftest create-storage-with-base-fns-test
  (testing "creates storage and initializes with base functions"
    ;; We need a factory function that creates a storage
    (let [storage (registry/create-storage-with-base-fns setup/create-test-storage)]
      ;; Should return initialized storage
      (is (satisfies? sp/StorageCRUD storage))
      ;; Check that base functions were synced
      (let [add-id (core/fn-uuid :add)
            add-fn (sp/read-entity storage :fn add-id)]
        (is (some? add-fn)))
      (sp/close storage))))


;; The legacy `registry/defbase` macro was removed — impls live in
;; `graphden.executor.defbase` and get tested in `defbase-test`. The
;; interface now only exposes registration + storage-sync helpers.

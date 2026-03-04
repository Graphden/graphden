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

(deftest fn-schema-uuid-delegation-test
  (testing "delegates to core/fn-schema-uuid"
    (let [result (registry/fn-schema-uuid :test-fn)]
      (is (uuid? result))
      (is (= result (core/fn-schema-uuid :test-fn))))))


(deftest arg-schema-uuid-delegation-test
  (testing "delegates to core/arg-schema-uuid"
    (let [result (registry/arg-schema-uuid :test-fn :test-arg)]
      (is (uuid? result))
      (is (= result (core/arg-schema-uuid :test-fn :test-arg))))))


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
      (is (= 1 (get-in result [:fn-schemas :created])))
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
      ;; Check that some base fn-schemas exist
      (let [add-id (core/fn-schema-uuid :add)
            add-schema (sp/read-entity storage :fn-schema add-id)]
        (is (some? add-schema))
        (is (= "add" (:name add-schema))))
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
                           (throw (ex-info "Mock query error" {:type :test-error}))))]
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
      ;; Check both fn-schemas exist
      (let [fn1-id (core/fn-schema-uuid :init-all-fn1)
            fn2-id (core/fn-schema-uuid :init-all-fn2)]
        (is (some? (sp/read-entity storage :fn-schema fn1-id)))
        (is (some? (sp/read-entity storage :fn-schema fn2-id))))
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
      (let [add-id (core/fn-schema-uuid :add)
            add-schema (sp/read-entity storage :fn-schema add-id)]
        (is (some? add-schema)))
      (sp/close storage))))


;; =============================================================================
;; defbase Macro Tests
;; =============================================================================

#_{:clj-kondo/ignore [:unresolved-symbol]}


(registry/defbase test-add-iface
                  {:args {:a :int :b :int}
                   :return-type :int}
                  (+ a b))


#_{:clj-kondo/ignore [:unresolved-symbol]}


(registry/defbase test-if-iface
                  "A conditional function - short-circuit evaluation preserved."
                  {:args {:cond :bool :then :any :else :any}
                   :return-type :any}
                  (if cond then else))


(deftest defbase-macro-test
  (testing "defines function with :impl"
    (is (fn? (:impl test-add-iface)))
    (is (= :int (:return-type test-add-iface)))
    (is (= {:a :int :b :int} (:args test-add-iface))))

  (testing "defines function with docstring"
    (is (fn? (:impl test-if-iface)))
    (is (= :any (:return-type test-if-iface)))
    (is (= {:cond :bool :then :any :else :any} (:args test-if-iface))))

  (testing "impl works with delays"
    (let [impl (:impl test-add-iface)
          result (impl {:a (delay 3) :b (delay 4)} {})]
      (is (= 7 result))))

  (testing "short-circuit evaluation - only needed branch is derefed"
    (let [impl (:impl test-if-iface)
          then-called? (atom false)
          else-called? (atom false)
          then-delay (delay (reset! then-called? true) "then-value")
          else-delay (delay (reset! else-called? true) "else-value")
          ;; When cond is true, only :then should be derefed
          result (impl {:cond (delay true)
                        :then then-delay
                        :else else-delay}
                       {})]
      (is (= "then-value" result))
      (is @then-called?)
      (is (not @else-called?)))))


;; =============================================================================
;; *custom-binding-forms* Tests
;; =============================================================================

(deftest custom-binding-forms-test
  (testing "*custom-binding-forms* is bound"
    (is (set? registry/*custom-binding-forms*))))

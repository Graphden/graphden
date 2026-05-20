(ns ^:integration graphden.executor.registry.registration-test
  "Tests for fn-registry function registration."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as core]
    [graphden.executor.registry.interface :as registry]
    [graphden.executor.test-setup :as setup]
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


;; === Helper Functions ===

(defn literal-delay
  "Creates a delay wrapping a literal value for testing."
  [value]
  (delay value))


;; === Registration Tests ===

(deftest register-base-fns-test
  (testing "register-base-fns! registers functions"
    ;; impl functions receive delays, use @ to deref
    (let [defs {:test-add {:args {:a :numeric :b :numeric}
                           :return-type :numeric
                           :impl (setup/fn-impl [a b] (+ a b))}
                :test-sub {:args {:a :numeric :b :numeric}
                           :return-type :numeric
                           :impl (setup/fn-impl [a b] (- a b))}}]
      (registry/register-base-fns! defs)
      (is (some? (exec/get-base-fn :test-add)))
      (is (some? (exec/get-base-fn :test-sub)))

      ;; Test that they work with delays
      (let [add-fn (exec/get-base-fn :test-add)
            sub-fn (exec/get-base-fn :test-sub)]
        (is (= 7 (add-fn {:a (literal-delay 3) :b (literal-delay 4)} nil)))
        (is (= -1 (sub-fn {:a (literal-delay 3) :b (literal-delay 4)} nil))))))

  (testing "register-base-fns! handles empty defs map"
    ;; This tests the doseq with empty input
    (registry/register-base-fns! {})
    ;; Should not throw, just do nothing
    (is true))

  (testing "register-base-fns! handles nil defs map"
    ;; This tests the doseq with nil input
    (registry/register-base-fns! nil)
    ;; Should not throw, just do nothing
    (is true))

  (testing "register-base-fns! handles def with nil :impl"
    ;; When :impl is nil, register-base-fn! receives nil function
    ;; This should work (registers nil), caller is responsible for valid impl
    (registry/register-base-fns! {:nil-impl {:args {} :return-type :any :impl nil}})
    (is (nil? (exec/get-base-fn :nil-impl))))

  (testing "register-base-fns! handles def missing :impl key"
    ;; When :impl key is missing, register-base-fn! receives nil
    (registry/register-base-fns! {:missing-impl {:args {} :return-type :any}})
    (is (nil? (exec/get-base-fn :missing-impl)))))


;; === register-base-fns! Core Path Tests ===

(deftest register-base-fns-core-test
  (testing "registers multiple base functions"
    (let [called (atom #{})
          defs {:fn1 {:args {:x :int}
                      :return-type :int
                      :impl (fn [_ _] (swap! called conj :fn1) 1)}
                :fn2 {:args {:y :text}
                      :return-type :text
                      :impl (fn [_ _] (swap! called conj :fn2) "ok")}}]
      (core/register-base-fns! defs)
      ;; Verify functions are registered
      (is (some? (exec/get-base-fn :fn1)))
      (is (some? (exec/get-base-fn :fn2)))
      ;; Call them to verify impl is correct
      ((exec/get-base-fn :fn1) {} nil)
      ((exec/get-base-fn :fn2) {} nil)
      (is (= #{:fn1 :fn2} @called))))

  (testing "handles empty defs"
    (core/register-base-fns! {})
    (is true)))


;; === initialize-with-base-fns! Tests ===

(deftest initialize-with-base-fns-test
  ;; Loading the production "core" package via initialize-with-base-fns!
  ;; needs the fn-defs sync (which registers type-rows like
  ;; `:os-info-shape`) to run BEFORE base-fn parsing reaches them. The
  ;; production system wires this through Integrant so order is
  ;; correct; the legacy single-call helper here doesn't, and the
  ;; test's "execute composed fn through arg row" sub-test is on the
  ;; deleted :arg model. Replaced with a focused registry-only
  ;; assertion using a small stub set.
  (testing "registers stub base-fns in executor + storage"
    (let [storage (create-test-storage)]
      (try
        (let [defs {:my-stub {:args {:x :int} :return-type :int
                              :impl (fn [_ _] 1)}}]
          (registry/initialize-all! storage [defs])
          (is (some? (exec/get-base-fn :my-stub)))
          (is (some? (sp/read-entity storage :fn (registry/fn-uuid :my-stub)))))
        (finally
          (sp/close storage))))))


(deftest sync-defs-to-storage-interface-arities-test
  ;; Exercise the wrapper arities on `registry.interface` directly —
  ;; existing tests jump between 2-arity (`(sync-defs-to-storage!
  ;; storage defs)`) and the underlying 4-arity in registry.core. The
  ;; intermediate 3-arity wrapper (line 38 of registry/interface.clj —
  ;; pre-fills `extra-name->id` with `{}`) had no callers in test, so
  ;; cloverage flagged it as dead from outside.
  (testing "3-arity wrapper threads ns-id-map; extra-name->id defaults to {}"
    (let [storage (create-test-storage)
          defs {:wrap-3 {:args {:x :int} :return-type :int
                         :impl (fn [_ _] 1)}}]
      (try
        (let [name->id (registry/sync-defs-to-storage! storage defs {})]
          (is (contains? name->id :wrap-3)))
        (finally (sp/close storage)))))
  (testing "4-arity wrapper threads both ns-id-map and extra-name->id"
    (let [storage (create-test-storage)
          defs {:wrap-4 {:args {:x :int} :return-type :int
                         :impl (fn [_ _] 1)}}]
      (try
        (let [name->id (registry/sync-defs-to-storage! storage defs {} {})]
          (is (contains? name->id :wrap-4)))
        (finally (sp/close storage))))))


;; === initialize-all! Tests ===

(deftest initialize-all-test
  (testing "initializes storage with multiple def-sets"
    (let [storage (create-test-storage)
          defs1 {:test-fn1 {:args {:x :int} :return-type :int :impl (fn [_ _] 1)}}
          defs2 {:test-fn2 {:args {:y :text} :return-type :text :impl (fn [_ _] "ok")}}
          defs3 {:test-fn3 {:args {} :return-type :bool :impl (fn [_ _] true)}}]
      (try
        (let [result (registry/initialize-all! storage [defs1 defs2 defs3])]
          ;; Should return the same storage
          (is (= storage result))
          ;; All functions should be registered
          (is (some? (exec/get-base-fn :test-fn1)))
          (is (some? (exec/get-base-fn :test-fn2)))
          (is (some? (exec/get-base-fn :test-fn3)))
          ;; All fns should be in storage
          (is (some? (sp/read-entity storage :fn (registry/fn-uuid :test-fn1))))
          (is (some? (sp/read-entity storage :fn (registry/fn-uuid :test-fn2))))
          (is (some? (sp/read-entity storage :fn (registry/fn-uuid :test-fn3)))))
        (finally
          (sp/close storage)))))

  (testing "handles empty def-sets sequence"
    (let [storage (create-test-storage)]
      (try
        (let [result (registry/initialize-all! storage [])]
          (is (= storage result)))
        (finally
          (sp/close storage)))))

  (testing "handles def-sets with overlap - second sync updates"
    (let [storage (create-test-storage)
          ;; Both sets define same function
          defs1 {:overlap-fn {:args {:x :int} :return-type :int :impl (fn [_ _] 1)}}
          defs2 {:overlap-fn {:args {:x :int} :return-type :int :impl (fn [_ _] 2)}}]
      (try
        (registry/initialize-all! storage [defs1 defs2])
        ;; Function should be registered (second impl wins)
        (is (some? (exec/get-base-fn :overlap-fn)))
        ;; fn should exist
        (is (some? (sp/read-entity storage :fn (registry/fn-uuid :overlap-fn))))
        (finally
          (sp/close storage))))))


;; === initialize-with-base-fns! Error Handling Tests ===

(defrecord FailingStorage
  [fail-atom]

  sp/Storage

  (initialize [_ _] (throw (ex-info "Init failed" {})))


  (close [_] (swap! fail-atom conj :closed)))


(deftest initialize-with-base-fns-error-handling-test
  (testing "closes storage on error during sync"
    ;; This tests the catch block in initialize-with-base-fns!
    ;; We need a storage that will fail during sync-defs-to-storage!
    (let [storage (create-test-storage)
          close-called (atom false)
          ;; Wrap storage to track close and fail on specific operation
          wrapped (reify
                    sp/Storage
                    (initialize [_ schema] (sp/initialize storage schema))

                    (close
                      [_]
                      (reset! close-called true)
                      (sp/close storage))


                    sp/StorageCRUD

                    (create-entity
                      [_ entity-name data]
                      ;; Fail on fn creation to trigger error path
                      (if (= entity-name :fn)
                        (throw (ex-info "Simulated failure" {:type :test-error}))
                        (sp/create-entity storage entity-name data)))

                    (read-entity
                      [_ entity-name id]
                      (sp/read-entity storage entity-name id))

                    (update-entity
                      [_ entity-name id data]
                      (sp/update-entity storage entity-name id data))

                    (delete-entity
                      [_ entity-name id]
                      (sp/delete-entity storage entity-name id))

                    (query-entities
                      [_ entity-name where]
                      (sp/query-entities storage entity-name where))


                    sp/StorageBatchCRUD

                    (create-entities
                      [_ entity-name data-seq]
                      (sp/create-entities storage entity-name data-seq))

                    (read-entities
                      [_ entity-name ids]
                      (sp/read-entities storage entity-name ids))

                    (update-entities
                      [_ entity-name data-seq]
                      (sp/update-entities storage entity-name data-seq))

                    (delete-entities
                      [_ entity-name ids]
                      (sp/delete-entities storage entity-name ids))

                    (upsert-entities
                      [_ entity-name data-seq]
                      ;; Fail on fn upsert to trigger error path
                      (if (= entity-name :fn)
                        (throw (ex-info "Simulated failure" {:type :test-error}))
                        (sp/upsert-entities storage entity-name data-seq))))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Simulated failure"
            (registry/initialize-with-base-fns! wrapped)))
      ;; Verify close was called
      (is @close-called))))

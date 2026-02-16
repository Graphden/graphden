(ns graphden.executor-runtime.core-test
  "Tests for executor runtime lifecycle management.

   These tests verify:
   - System configuration loading
   - Component initialization order
   - Graceful shutdown
   - Profile handling (:dev, :test, :prod)"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.protocol.interface :as ds]
    [graphden.storage.age.test-setup :as age-setup]
    [graphden.storage.protocol.interface :as sp]
    [graphden.system.interface :as sys]
    [integrant.core :as ig]))


;; =============================================================================
;; Fixtures
;; =============================================================================

(use-fixtures :once (age-setup/container-fixture))


;; =============================================================================
;; Config Tests
;; =============================================================================

(deftest read-config-test
  (testing "read-config returns valid Integrant config for :test profile"
    (let [config (sys/read-config :test)]
      (is (map? config))
      (is (contains? config :db/schema))
      (is (contains? config :db/age))
      (is (contains? config :db/versioned))
      (is (contains? config :exec/base-fns))
      (is (contains? config :exec/context))))

  (testing "read-config returns valid config for :dev profile"
    (let [config (sys/read-config :dev)]
      (is (map? config))
      (is (contains? config :db/schema))
      (is (contains? config :db/age))))

  (testing "read-config returns valid config for :prod profile"
    (let [config (sys/read-config :prod)]
      (is (map? config))
      (is (contains? config :db/schema))
      (is (contains? config :db/age))
      (is (contains? config :http/server)))))


(deftest config-dependencies-test
  (testing ":db/age depends on :db/schema"
    (let [config (sys/read-config :test)
          age-config (:db/age config)
          schema-ref (:schema age-config)]
      ;; ig/ref creates IntegrantRef record
      (is (some? schema-ref))
      (is (= :db/schema (:key schema-ref)))))

  (testing ":db/versioned depends on :db/age"
    (let [config (sys/read-config :test)
          versioned-config (:db/versioned config)
          base-ref (:base-storage versioned-config)]
      (is (some? base-ref))
      (is (= :db/age (:key base-ref)))))

  (testing ":exec/context depends on :db/versioned"
    (let [config (sys/read-config :test)
          context-config (:exec/context config)
          storage-ref (:storage context-config)]
      (is (some? storage-ref))
      (is (= :db/versioned (:key storage-ref))))))


;; =============================================================================
;; System Lifecycle Tests
;; =============================================================================

(deftest partial-system-start-test
  (testing "Can start only schema component"
    (let [jdbc-url (:jdbc-url (age-setup/get-container-config age-setup/*container*))
          config (-> (sys/read-config :test)
                     (assoc-in [:db/age :jdbc-url] jdbc-url))
          system (ig/init config [:db/schema])]
      (try
        (is (some? (:db/schema system)))
        (is (nil? (:db/age system)))
        (is (nil? (:db/versioned system)))
        (finally
          (ig/halt! system)))))

  (testing "Can start schema + age + versioned components"
    (let [jdbc-url (:jdbc-url (age-setup/get-container-config age-setup/*container*))
          config (-> (sys/read-config :test)
                     (assoc-in [:db/age :jdbc-url] jdbc-url))
          system (ig/init config [:db/schema :db/age :db/versioned])]
      (try
        (is (some? (:db/schema system)))
        (is (some? (:db/age system)))
        (is (some? (:db/versioned system)))
        (is (nil? (:exec/context system)))
        (finally
          (ig/halt! system))))))


(deftest full-system-lifecycle-test
  (testing "Full system starts and stops correctly"
    (let [jdbc-url (:jdbc-url (age-setup/get-container-config age-setup/*container*))
          config (-> (sys/read-config :test)
                     (assoc-in [:db/age :jdbc-url] jdbc-url))
          system (ig/init config)]
      (try
        ;; Verify all components are initialized
        (is (some? (:db/schema system)) "Schema should be initialized")
        (is (some? (:db/age system)) "AGE storage should be initialized")
        (is (some? (:db/versioned system)) "Versioned storage should be initialized")
        (is (some? (:exec/base-fns system)) "Base functions should be registered")
        (is (some? (:exec/context system)) "Executor context should be created")
        (finally
          ;; Verify graceful shutdown
          (ig/halt! system))))))


(deftest start-with-overrides-test
  (testing "start-with-overrides! merges config correctly"
    (let [jdbc-url (:jdbc-url (age-setup/get-container-config age-setup/*container*))
          system (sys/start-with-overrides! :test
                                            {:db/age {:jdbc-url jdbc-url}})]
      (try
        (is (some? (:db/schema system)))
        (is (some? (:db/age system)))
        (is (some? (:db/versioned system)))
        (finally
          (sys/stop! system))))))


(deftest system-component-values-test
  (testing "Initialized components have correct types"
    (let [jdbc-url (:jdbc-url (age-setup/get-container-config age-setup/*container*))
          config (-> (sys/read-config :test)
                     (assoc-in [:db/age :jdbc-url] jdbc-url))
          system (ig/init config)]
      (try
        ;; Schema is a DataSchema
        (is (satisfies? ds/DataSchema (:db/schema system)))

        ;; Storage satisfies StorageCRUD
        (is (satisfies? sp/StorageCRUD (:db/age system)))

        ;; Versioned storage also satisfies StorageCRUD
        (is (satisfies? sp/StorageCRUD (:db/versioned system)))

        ;; Context is a map with required keys
        (let [ctx (:exec/context system)]
          (is (map? ctx))
          (is (contains? ctx :storage)))
        (finally
          (ig/halt! system))))))


;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest invalid-profile-test
  (testing "Invalid profile throws on read-config"
    (is (thrown? Exception
          (sys/read-config :invalid-profile)))))


(deftest missing-jdbc-url-test
  (testing "System fails to start without JDBC URL"
    (let [config (sys/read-config :test)]
      ;; :db/age has jdbc-url = nil in test config
      ;; Starting should fail because AGE can't connect
      (is (thrown? Exception
            (ig/init config [:db/schema :db/age]))))))

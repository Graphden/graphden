(ns ^:integration graphden.executor-runtime.core-test
  "Tests for executor runtime lifecycle management.

   These tests verify:
   - System configuration loading
   - Component initialization order
   - Graceful shutdown
   - Profile handling (:dev, :test, :prod)"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor-runtime.core :as rt]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.system.interface :as sys]
    [integrant.core :as ig]))


;; =============================================================================
;; Fixtures
;; =============================================================================

(def ^:dynamic *container* nil)

(use-fixtures :once (pth/create-container-fixture #'*container*))
(use-fixtures :each (pth/create-clean-db-fixture #'*container*))


;; =============================================================================
;; Config Tests
;; =============================================================================

(deftest read-config-test
  (testing "read-config returns valid Integrant config for :test profile"
    (let [config (sys/read-config :test)]
      (is (map? config))
      (is (contains? config :db/schema))
      (is (contains? config :db/postgres))
      (is (contains? config :db/versioned))
      (is (contains? config :exec/base-fns))
      (is (contains? config :exec/context))))

  (testing "read-config returns valid config for :dev profile"
    (let [config (sys/read-config :dev)]
      (is (map? config))
      (is (contains? config :db/schema))
      (is (contains? config :db/postgres))))

  (testing "read-config returns valid config for :prod profile"
    (let [config (sys/read-config :prod)]
      (is (map? config))
      (is (contains? config :db/schema))
      (is (contains? config :db/postgres))
      (is (contains? config :http/server)))))


(deftest config-dependencies-test
  (testing ":db/postgres depends on :db/schema"
    (let [config (sys/read-config :test)
          age-config (:db/postgres config)
          schema-ref (:schema age-config)]
      ;; ig/ref creates IntegrantRef record
      (is (some? schema-ref))
      (is (= :db/schema (:key schema-ref)))))

  (testing ":db/versioned depends on :db/postgres"
    (let [config (sys/read-config :test)
          versioned-config (:db/versioned config)
          base-ref (:base-storage versioned-config)]
      (is (some? base-ref))
      (is (= :db/postgres (:key base-ref)))))

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
    (let [jdbc-url (:jdbc-url (pth/get-container-config *container*))
          config (-> (sys/read-config :test)
                     (assoc-in [:db/postgres :jdbc-url] jdbc-url))
          system (ig/init config [:db/schema])]
      (try
        (is (some? (:db/schema system)))
        (is (nil? (:db/postgres system)))
        (is (nil? (:db/versioned system)))
        (finally
          (ig/halt! system)))))

  (testing "Can start schema + age + versioned components"
    (let [jdbc-url (:jdbc-url (pth/get-container-config *container*))
          config (-> (sys/read-config :test)
                     (assoc-in [:db/postgres :jdbc-url] jdbc-url))
          system (ig/init config [:db/schema :db/postgres :db/versioned])]
      (try
        (is (some? (:db/schema system)))
        (is (some? (:db/postgres system)))
        (is (some? (:db/versioned system)))
        (is (nil? (:exec/context system)))
        (finally
          (ig/halt! system))))))


(deftest full-system-lifecycle-test
  (testing "Full system starts and stops correctly"
    (let [jdbc-url (:jdbc-url (pth/get-container-config *container*))
          config (-> (sys/read-config :test)
                     (assoc-in [:db/postgres :jdbc-url] jdbc-url))
          system (ig/init config)]
      (try
        ;; Verify all components are initialized
        (is (some? (:db/schema system)) "Schema should be initialized")
        (is (some? (:db/postgres system)) "PostgreSQL storage should be initialized")
        (is (some? (:db/versioned system)) "Versioned storage should be initialized")
        (is (some? (:exec/base-fns system)) "Base functions should be registered")
        (is (some? (:exec/context system)) "Executor context should be created")
        (finally
          ;; Verify graceful shutdown
          (ig/halt! system))))))


(deftest start-with-overrides-test
  (testing "start-with-overrides! merges config correctly"
    (let [jdbc-url (:jdbc-url (pth/get-container-config *container*))
          system (sys/start-with-overrides! :test
                                            {:db/postgres {:jdbc-url jdbc-url}})]
      (try
        (is (some? (:db/schema system)))
        (is (some? (:db/postgres system)))
        (is (some? (:db/versioned system)))
        (finally
          (sys/stop! system))))))


(deftest system-component-values-test
  (testing "Initialized components have correct types"
    (let [jdbc-url (:jdbc-url (pth/get-container-config *container*))
          config (-> (sys/read-config :test)
                     (assoc-in [:db/postgres :jdbc-url] jdbc-url))
          system (ig/init config)]
      (try
        ;; Schema is a DataSchema
        (is (satisfies? ds/DataSchema (:db/schema system)))

        ;; Storage satisfies StorageCRUD
        (is (satisfies? sp/StorageCRUD (:db/postgres system)))

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
      ;; :db/postgres has jdbc-url = nil in test config
      ;; Starting should fail because AGE can't connect
      (is (thrown? Exception
            (ig/init config [:db/schema :db/postgres]))))))


;; =============================================================================
;; Executor Runtime Lifecycle Tests
;; =============================================================================

(deftest executor-runtime-start-stop-test
  (let [jdbc-url (:jdbc-url (pth/get-container-config *container*))
        original-read-config sys/read-config]
    ;; Override config to use test container
    (with-redefs [sys/read-config (fn [profile]
                                    (-> (original-read-config profile)
                                        (assoc-in [:db/postgres :jdbc-url] jdbc-url)))]
      (testing "start! returns running system"
        (let [system (rt/start! :test)]
          (try
            (is (map? system))
            (is (some? (:db/schema system)))
            (is (some? (:db/postgres system)))
            (is (some? (:db/versioned system)))
            (finally
              (rt/stop!)))))

      (testing "stop! with no running system does nothing"
        ;; stop! should not throw when nothing is running
        (is (nil? (rt/stop!)))))))


(deftest executor-runtime-restart-test
  (let [jdbc-url (:jdbc-url (pth/get-container-config *container*))
        original-read-config sys/read-config]
    (with-redefs [sys/read-config (fn [profile]
                                    (-> (original-read-config profile)
                                        (assoc-in [:db/postgres :jdbc-url] jdbc-url)))]
      (testing "restart! stops and starts system"
        (let [system1 (rt/start! :test)]
          (try
            (is (some? system1))
            (let [system2 (rt/restart! :test)]
              (is (some? system2))
              (is (not= system1 system2)))
            (finally
              (rt/stop!))))))))


;; =============================================================================
;; System Interface suspend/resume Tests
;; =============================================================================

(deftest suspend-resume-test
  (testing "suspend! works with schema-only system"
    (let [system (ig/init (sys/read-config :test) [:db/schema])]
      (try
        (is (some? (:db/schema system)))
        ;; Suspend should work without error
        (sys/suspend! system)
        (finally
          (ig/halt! system)))))

  (testing "suspend! with multiple components"
    (let [jdbc-url (:jdbc-url (pth/get-container-config *container*))
          config (-> (sys/read-config :test)
                     (assoc-in [:db/postgres :jdbc-url] jdbc-url))
          system (ig/init config [:db/schema :db/postgres :db/versioned])]
      (try
        (is (some? (:db/versioned system)))
        (sys/suspend! system)
        (finally
          (ig/halt! system))))))


;; =============================================================================
;; Start with partial components Test
;; =============================================================================

(deftest start-with-component-keys-test
  (testing "start! with component-keys starts only specified components"
    (let [system (sys/start! :test [:db/schema])]
      (try
        (is (some? (:db/schema system)))
        (is (nil? (:db/postgres system)))
        (finally
          (sys/stop! system))))))


;; =============================================================================
;; Additional init-key Tests
;; =============================================================================

(deftest exec-base-fns-init-test
  (testing ":exec/base-fns returns :registered"
    (let [jdbc-url (:jdbc-url (pth/get-container-config *container*))
          config (-> (sys/read-config :test)
                     (assoc-in [:db/postgres :jdbc-url] jdbc-url))
          system (ig/init config [:db/schema :db/postgres :db/versioned :exec/base-fns])]
      (try
        (is (= :registered (:status (:exec/base-fns system))))
        (finally
          (ig/halt! system))))))


(deftest exec-context-init-test
  (testing ":exec/context creates context with the slim field set"
    (let [jdbc-url (:jdbc-url (pth/get-container-config *container*))
          config (-> (sys/read-config :test)
                     (assoc-in [:db/postgres :jdbc-url] jdbc-url))
          system (ig/init config [:db/schema :db/postgres :db/versioned :exec/context])]
      (try
        (let [ctx (:exec/context system)]
          (is (map? ctx))
          (is (contains? ctx :storage))
          (is (contains? ctx :base-fns))
          (is (contains? ctx :clock))
          (is (contains? ctx :compiled-registry)))
        (finally
          (ig/halt! system))))))


;; =============================================================================
;; Resume Tests
;; =============================================================================

(deftest resume-system-test
  (testing "resume! restarts system after suspend"
    (let [jdbc-url (:jdbc-url (pth/get-container-config *container*))
          original-read-config sys/read-config]
      (with-redefs [sys/read-config (fn [profile]
                                      (-> (original-read-config profile)
                                          (assoc-in [:db/postgres :jdbc-url] jdbc-url)))]
        (let [system (sys/start! :test [:db/schema :db/postgres :db/versioned])]
          (try
            (is (some? (:db/schema system)))
            (sys/suspend! system)
            (let [resumed (sys/resume! system :test)]
              (try
                (is (some? (:db/schema resumed)))
                (finally
                  (sys/stop! resumed))))
            (finally
              ;; Ensure cleanup happens
              (try (sys/stop! system) (catch Exception _)))))))))


;; =============================================================================
;; App Packages Test
;; =============================================================================

(deftest app-packages-init-test
  (testing ":app/packages loads packages from resources"
    (let [config {:app/packages {:package-names ["core"]}}
          system (ig/init config [:app/packages])]
      (try
        (let [packages (:app/packages system)]
          (is (map? packages))
          (is (contains? packages :base-fn-defs))
          (is (contains? packages :fn-defs))
          (is (contains? packages :packages)))
        (finally
          (ig/halt! system))))))


;; =============================================================================
;; Runtime 0-arity and Default Profile Tests
;; =============================================================================

(deftest start-default-profile-test
  (let [jdbc-url (:jdbc-url (pth/get-container-config *container*))
        original-read-config sys/read-config
        profile-used (atom nil)]
    ;; Override to track which profile is used and inject test container URL
    (with-redefs [sys/read-config (fn [profile]
                                    (reset! profile-used profile)
                                    (-> (original-read-config :test)
                                        (assoc-in [:db/postgres :jdbc-url] jdbc-url)))]
      (testing "start! with no args uses :prod profile"
        (let [system (rt/start!)]
          (try
            (is (= :prod @profile-used))
            (is (some? system))
            (finally
              (rt/stop!))))))))


(deftest restart-default-profile-test
  (let [jdbc-url (:jdbc-url (pth/get-container-config *container*))
        original-read-config sys/read-config
        profiles-used (atom [])]
    ;; Override to track which profiles are used
    (with-redefs [sys/read-config (fn [profile]
                                    (swap! profiles-used conj profile)
                                    (-> (original-read-config :test)
                                        (assoc-in [:db/postgres :jdbc-url] jdbc-url)))]
      (testing "restart! with no args uses :prod profile"
        ;; First start with :test
        (rt/start! :test)
        (try
          (reset! profiles-used [])
          ;; Restart with default (should use :prod)
          (let [system (rt/restart!)]
            (is (= [:prod] @profiles-used))
            (is (some? system)))
          (finally
            (rt/stop!)))))))


;; =============================================================================
;; -main Function Tests
;; =============================================================================

;; Note: -main is difficult to fully test because it:
;; 1. Uses Java interop (Runtime/addShutdownHook) which can't be easily mocked
;; 2. Blocks forever with @(promise)
;; Instead we test the components it uses (start!, stop!) are working correctly


;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest stop-returns-nil-test
  (testing "stop! returns nil when system is stopped"
    (let [jdbc-url (:jdbc-url (pth/get-container-config *container*))
          original-read-config sys/read-config]
      (with-redefs [sys/read-config (fn [profile]
                                      (-> (original-read-config profile)
                                          (assoc-in [:db/postgres :jdbc-url] jdbc-url)))]
        (rt/start! :test)
        (let [result (rt/stop!)]
          (is (nil? result)))))))


;; =============================================================================
;; -main Function Tests
;; =============================================================================

;; -main blocks on @(promise), so we redef the indirections it goes
;; through (start!, install-shutdown-hook!, block-forever!) and call it
;; directly. No JVM hooks are leaked.

(deftest main-runs-startup-then-blocks
  (testing "-main calls start! :prod, installs shutdown hook, then blocks"
    (let [profile-used (atom nil)
          hook-installed? (atom false)
          blocked? (atom false)]
      (with-redefs [rt/start! (fn
                                ([] (reset! profile-used :no-arg))
                                ([p] (reset! profile-used p)))
                    rt/install-shutdown-hook! (fn [] (reset! hook-installed? true))
                    rt/block-forever! (fn [] (reset! blocked? true))]
        (rt/-main "ignored")
        (is (= :prod @profile-used) "-main starts with :prod profile")
        (is @hook-installed? "shutdown hook installed")
        (is @blocked? "block step reached")))))

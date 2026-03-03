(ns graphden.executor-runtime.core-test
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
    [graphden.storage.age.test-setup :as age-setup]
    [graphden.storage.protocol.core :as sp]
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
      (is (contains? config :db/postgres))
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


;; =============================================================================
;; Executor Runtime Lifecycle Tests
;; =============================================================================

(deftest executor-runtime-start-stop-test
  (let [jdbc-url (:jdbc-url (age-setup/get-container-config age-setup/*container*))
        original-read-config sys/read-config]
    ;; Override config to use test container
    (with-redefs [sys/read-config (fn [profile]
                                    (-> (original-read-config profile)
                                        (assoc-in [:db/age :jdbc-url] jdbc-url)))]
      (testing "start! returns running system"
        (let [system (rt/start! :test)]
          (try
            (is (map? system))
            (is (some? (:db/schema system)))
            (is (some? (:db/age system)))
            (is (some? (:db/versioned system)))
            (finally
              (rt/stop!)))))

      (testing "stop! with no running system does nothing"
        ;; stop! should not throw when nothing is running
        (is (nil? (rt/stop!)))))))


(deftest executor-runtime-restart-test
  (let [jdbc-url (:jdbc-url (age-setup/get-container-config age-setup/*container*))
        original-read-config sys/read-config]
    (with-redefs [sys/read-config (fn [profile]
                                    (-> (original-read-config profile)
                                        (assoc-in [:db/age :jdbc-url] jdbc-url)))]
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
    (let [jdbc-url (:jdbc-url (age-setup/get-container-config age-setup/*container*))
          config (-> (sys/read-config :test)
                     (assoc-in [:db/age :jdbc-url] jdbc-url))
          system (ig/init config [:db/schema :db/age :db/versioned])]
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
        (is (nil? (:db/age system)))
        (finally
          (sys/stop! system))))))


;; =============================================================================
;; Additional init-key Tests
;; =============================================================================

(deftest exec-base-fns-init-test
  (testing ":exec/base-fns returns :registered"
    (let [jdbc-url (:jdbc-url (age-setup/get-container-config age-setup/*container*))
          config (-> (sys/read-config :test)
                     (assoc-in [:db/age :jdbc-url] jdbc-url))
          system (ig/init config [:db/schema :db/age :db/versioned :exec/base-fns])]
      (try
        (is (= :registered (:exec/base-fns system)))
        (finally
          (ig/halt! system))))))


(deftest exec-context-init-test
  (testing ":exec/context creates context map with correct keys"
    (let [jdbc-url (:jdbc-url (age-setup/get-container-config age-setup/*container*))
          config (-> (sys/read-config :test)
                     (assoc-in [:db/age :jdbc-url] jdbc-url))
          system (ig/init config [:db/schema :db/age :db/versioned :exec/context])]
      (try
        (let [ctx (:exec/context system)]
          (is (map? ctx))
          (is (contains? ctx :storage))
          (is (contains? ctx :max-depth))
          (is (contains? ctx :timeout-ms))
          (is (= 100 (:max-depth ctx)))
          (is (= 5000 (:timeout-ms ctx))))
        (finally
          (ig/halt! system))))))


(deftest exec-context-defaults-test
  (testing ":exec/context uses defaults when max-depth and timeout-ms are nil"
    (let [jdbc-url (:jdbc-url (age-setup/get-container-config age-setup/*container*))
          config (-> (sys/read-config :test)
                     (assoc-in [:db/age :jdbc-url] jdbc-url)
                     (assoc-in [:exec/context :max-depth] nil)
                     (assoc-in [:exec/context :timeout-ms] nil))
          system (ig/init config [:db/schema :db/age :db/versioned :exec/context])]
      (try
        (let [ctx (:exec/context system)]
          (is (= 1000 (:max-depth ctx)))
          (is (= 30000 (:timeout-ms ctx))))
        (finally
          (ig/halt! system))))))


;; =============================================================================
;; Resume Tests
;; =============================================================================

(deftest resume-system-test
  (testing "resume! restarts system after suspend"
    (let [jdbc-url (:jdbc-url (age-setup/get-container-config age-setup/*container*))
          original-read-config sys/read-config]
      (with-redefs [sys/read-config (fn [profile]
                                      (-> (original-read-config profile)
                                          (assoc-in [:db/age :jdbc-url] jdbc-url)))]
        (let [system (sys/start! :test [:db/schema :db/age :db/versioned])]
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
;; App Fn Defs Test
;; =============================================================================

(deftest app-fn-defs-init-test
  (testing ":app/fn-defs returns fn-defs passthrough"
    (let [fn-defs {:my-fn {:name :my-fn :parent :identity :args {}}}
          config {:app/fn-defs fn-defs}
          system (ig/init config [:app/fn-defs])]
      (try
        (is (= fn-defs (:app/fn-defs system)))
        (finally
          (ig/halt! system))))))


;; =============================================================================
;; Runtime 0-arity and Default Profile Tests
;; =============================================================================

(deftest start-default-profile-test
  (let [jdbc-url (:jdbc-url (age-setup/get-container-config age-setup/*container*))
        original-read-config sys/read-config
        profile-used (atom nil)]
    ;; Override to track which profile is used and inject test container URL
    (with-redefs [sys/read-config (fn [profile]
                                    (reset! profile-used profile)
                                    (-> (original-read-config :test)
                                        (assoc-in [:db/age :jdbc-url] jdbc-url)))]
      (testing "start! with no args uses :prod profile"
        (let [system (rt/start!)]
          (try
            (is (= :prod @profile-used))
            (is (some? system))
            (finally
              (rt/stop!))))))))


(deftest restart-default-profile-test
  (let [jdbc-url (:jdbc-url (age-setup/get-container-config age-setup/*container*))
        original-read-config sys/read-config
        profiles-used (atom [])]
    ;; Override to track which profiles are used
    (with-redefs [sys/read-config (fn [profile]
                                    (swap! profiles-used conj profile)
                                    (-> (original-read-config :test)
                                        (assoc-in [:db/age :jdbc-url] jdbc-url)))]
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
    (let [jdbc-url (:jdbc-url (age-setup/get-container-config age-setup/*container*))
          original-read-config sys/read-config]
      (with-redefs [sys/read-config (fn [profile]
                                      (-> (original-read-config profile)
                                          (assoc-in [:db/age :jdbc-url] jdbc-url)))]
        (rt/start! :test)
        (let [result (rt/stop!)]
          (is (nil? result)))))))


;; =============================================================================
;; -main Function Tests
;; =============================================================================

;; Note: -main uses Java interop (Runtime/addShutdownHook) and blocks forever
;; with @(promise). These are intentionally not unit tested.
;; The components it uses (start!, stop!) are tested above.

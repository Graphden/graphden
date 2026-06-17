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
    [graphden.executor.interface :as exec]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.system.interface :as sys]
    [integrant.core :as ig]))


;; =============================================================================
;; Fixtures
;; =============================================================================

(def ^:dynamic *container* nil)


(use-fixtures :once
  (pth/create-container-fixture #'*container*)
  ;; Multiple deftests in this ns boot integrant systems that
  ;; register ~190 package base-fn impls into the global registry.
  ;; Wrap in `with-clean-registry` so those writes land in a
  ;; thread-local override atom — sibling test ns'es running in
  ;; parallel kaocha threads keep their own override and don't race.
  exec/with-clean-registry)


(use-fixtures :each (pth/create-clean-db-fixture #'*container*))


;; =============================================================================
;; Config Tests
;; =============================================================================

;; `read-config-test` lives in `graphden.system.interface-test` — the
;; unit-level home for `sys/read-config`. It covers :test/:dev/:prod
;; profiles, the :exec/service-reconciler key in :prod, and the
;; invalid-profile throw. Don't re-test here.


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
    (let [pg-cfg (pth/get-container-config *container*)
          config (-> (sys/read-config :test)
                     (update :db/postgres merge pg-cfg))
          system (ig/init config [:db/schema])]
      (try
        (is (some? (:db/schema system)))
        (is (nil? (:db/postgres system)))
        (is (nil? (:db/versioned system)))
        (finally
          (ig/halt! system)))))

  (testing "Can start schema + age + versioned components"
    (let [pg-cfg (pth/get-container-config *container*)
          config (-> (sys/read-config :test)
                     (update :db/postgres merge pg-cfg))
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
    (let [pg-cfg (pth/get-container-config *container*)
          config (-> (sys/read-config :test)
                     (update :db/postgres merge pg-cfg))
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
    (let [system (sys/start-with-overrides! :test
                                            {:db/postgres (pth/get-container-config *container*)})]
      (try
        (is (some? (:db/schema system)))
        (is (some? (:db/postgres system)))
        (is (some? (:db/versioned system)))
        (finally
          (sys/stop! system))))))


(deftest system-component-values-test
  (testing "Initialized components have correct types"
    (let [pg-cfg (pth/get-container-config *container*)
          config (-> (sys/read-config :test)
                     (update :db/postgres merge pg-cfg))
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

;; `invalid-profile-test` for `sys/read-config` lives in
;; `graphden.system.interface-test` — see "throws for invalid profile".

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
  (let [overrides {:db/postgres (pth/get-container-config *container*)}]
    (testing "start! returns running system"
      (let [system (rt/start! :test overrides)]
        (try
          (is (map? system))
          (is (some? (:db/schema system)))
          (is (some? (:db/postgres system)))
          (is (some? (:db/versioned system)))
          (finally
            (rt/stop!)))))

    (testing "stop! with no running system does nothing"
      ;; stop! should not throw when nothing is running
      (is (nil? (rt/stop!))))))


(deftest executor-runtime-restart-test
  (let [overrides {:db/postgres (pth/get-container-config *container*)}]
    (testing "restart! stops and starts system"
      (let [system1 (rt/start! :test overrides)]
        (try
          (is (some? system1))
          (let [system2 (rt/restart! :test overrides)]
            (is (some? system2))
            (is (not= system1 system2)))
          (finally
            (rt/stop!)))))))


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
    (let [pg-cfg (pth/get-container-config *container*)
          config (-> (sys/read-config :test)
                     (update :db/postgres merge pg-cfg))
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
    (let [pg-cfg (pth/get-container-config *container*)
          config (-> (sys/read-config :test)
                     (update :db/postgres merge pg-cfg))
          system (ig/init config [:db/schema :db/postgres :db/versioned :exec/base-fns])]
      (try
        (is (= :registered (:status (:exec/base-fns system))))
        (finally
          (ig/halt! system))))))


(deftest exec-context-init-test
  (testing ":exec/context creates context with the slim field set"
    (let [pg-cfg (pth/get-container-config *container*)
          config (-> (sys/read-config :test)
                     (update :db/postgres merge pg-cfg))
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
    (let [overrides {:db/postgres (pth/get-container-config *container*)}
          system (sys/start-with-overrides!
                   :test
                   [:db/schema :db/postgres :db/versioned]
                   overrides)]
      (try
        (is (some? (:db/schema system)))
        (sys/suspend! system)
        ;; `resume!` re-reads the profile config from disk so it needs
        ;; the same DB override. Pass an inline merged-config form.
        (let [config (reduce-kv (fn [c k v] (update c k merge v))
                                (sys/read-config :test)
                                overrides)
              resumed (ig/resume config system)]
          (try
            (is (some? (:db/schema resumed)))
            (finally
              (sys/stop! resumed))))
        (finally
          (try (sys/stop! system) (catch Exception _)))))))


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
  (let [pg-cfg (pth/get-container-config *container*)
        original-read-config sys/read-config
        profile-used (atom nil)]
    ;; `binding` instead of `with-redefs` so the parallel kaocha
    ;; runner doesn't race on `sys/read-config`'s root binding —
    ;; this Var carries `^:dynamic`, the rebind is thread-local.
    (binding [sys/read-config (fn [profile]
                                (reset! profile-used profile)
                                (-> (original-read-config :test)
                                    (update :db/postgres merge pg-cfg)))]
      (testing "start! with no args uses :prod profile"
        (let [system (rt/start!)]
          (try
            (is (= :prod @profile-used))
            (is (some? system))
            (finally
              (rt/stop!))))))))


(deftest restart-default-profile-test
  (let [pg-cfg (pth/get-container-config *container*)
        original-read-config sys/read-config
        profiles-used (atom [])]
    ;; Same `binding`-not-`with-redefs` reasoning as
    ;; start-default-profile-test above.
    (binding [sys/read-config (fn [profile]
                                (swap! profiles-used conj profile)
                                (-> (original-read-config :test)
                                    (update :db/postgres merge pg-cfg)))]
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
    (rt/start! :test {:db/postgres (pth/get-container-config *container*)})
    (let [result (rt/stop!)]
      (is (nil? result)))))


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
      ;; `binding` instead of `with-redefs` — these lifecycle Vars are
      ;; ^:dynamic so the parallel kaocha runner doesn't race on the
      ;; root binding (sibling NS executor_runtime.interface-test
      ;; rebinds the same names).
      (binding [rt/start! (fn
                            ([] (reset! profile-used :no-arg))
                            ([p] (reset! profile-used p)))
                rt/install-shutdown-hook! (fn [] (reset! hook-installed? true))
                rt/block-forever! (fn [] (reset! blocked? true))]
        (rt/-main "ignored")
        (is (= :prod @profile-used) "-main starts with :prod profile")
        (is @hook-installed? "shutdown hook installed")
        (is @blocked? "block step reached")))))

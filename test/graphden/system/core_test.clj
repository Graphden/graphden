(ns graphden.system.core-test
  "Tests for Integrant init-key implementations in system.core.

   Covers:
   - :exec/service-reconciler init/halt/suspend lifecycle (replaces
     the retired :http/server key — see docs/SERVICES.md)
   - :exec/fn-entities init-key"
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.services.reconciler :as recon]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.core :as sys-core]
    [integrant.core :as ig]))


;; =============================================================================
;; :exec/service-reconciler lifecycle Tests
;;
;; Phase 1 service registry: at startup the reconciler queries enabled
;; :service rows. None present → falls back to the package-declared
;; `:startup-fn` (legacy single-shot). On halt-key! both the supervised
;; services and the legacy stopper drain.
;;
;; The init-key path reads from `:storage` and the registry; the mocks
;; below let the test drive both branches (with/without enabled service
;; rows) without spinning up a real container.
;; =============================================================================

(defn- mock-storage
  "Storage stub: query-entities :service returns the seeded service
   rows; everything else returns nil. The reconciler only queries
   :service inside init-key (other reads happen via the running
   service supervisor, which we don't exercise here)."
  [service-rows]
  (reify sp/StorageCRUD
    (query-entities
      [_ entity-type _]
      (when (= entity-type :service) service-rows))

    (query-entities
      [this entity-type where _opts]
      (sp/query-entities this entity-type where))

    (read-entity [_ _ _] nil)

    (create-entity [_ _ _] nil)

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)))


(deftest service-reconciler-init-halt-no-rows-uses-legacy-fallback-test
  (testing "no :service rows → init-key invokes the package's :startup-fn"
    (let [stopped? (atom false)
          startup-called? (atom false)
          ;; Mock the legacy-fallback path: the reconciler resolves
          ;; the fn-id by name then executes it; redef the resolver
          ;; chain to skip real storage lookups.
          fake-stopper (fn [] (reset! stopped? true))]
      (with-redefs [;; Bypass the legacy fallback's fn-id lookup and
                    ;; execution — for this lifecycle test we only
                    ;; care that init→halt drains the stopper.
                    sys-core/start-legacy-fallback!
                    (fn [_ctx _packages]
                      (reset! startup-called? true)
                      fake-stopper)]
        (let [storage (mock-storage [])
              context {:storage storage}
              packages {:startup-fn :test-server}
              opts {:context context :packages packages}
              ;; init: empty :service set + non-nil :startup-fn →
              ;; calls start-legacy-fallback!
              component (ig/init-key :exec/service-reconciler opts)]
          (is @startup-called? "no :service rows → legacy fallback invoked")
          (is (some? (:legacy-stopper component))
              "init returns component map with the stopper handle")
          ;; halt: drains the legacy stopper.
          (ig/halt-key! :exec/service-reconciler component)
          (is @stopped? "halt-key! called the legacy stopper"))))))


(deftest service-reconciler-init-halt-with-rows-skips-legacy-test
  (testing "enabled :service rows present → init-key skips legacy fallback"
    (let [legacy-called? (atom false)
          reconcile-called? (atom false)]
      (with-redefs [sys-core/start-legacy-fallback!
                    (fn [_ _] (reset! legacy-called? true) nil)
                    recon/reconcile-once!
                    (fn [_ _] (reset! reconcile-called? true))]
        (let [;; Single enabled :service row — non-empty triggers
              ;; reconcile, suppresses legacy.
              storage (mock-storage [{:id (random-uuid)
                                      :fn-id (random-uuid)
                                      :enabled? true}])
              context {:storage storage}
              opts {:context context :packages {:startup-fn :ignored}}
              component (ig/init-key :exec/service-reconciler opts)]
          (is (not @legacy-called?)
              ":service rows present → legacy fallback suppressed")
          (is @reconcile-called?
              "reconcile-once! invoked to start the desired services")
          (is (nil? (:legacy-stopper component))
              "no legacy stopper handed back when fallback skipped")
          ;; halt: idempotent on a nil legacy-stopper.
          (ig/halt-key! :exec/service-reconciler component))))))


(deftest service-reconciler-suspend-mirrors-halt-test
  (testing "suspend-key! drains both supervised services + legacy stopper"
    (let [legacy-stopped? (atom false)
          fake-stopper (fn [] (reset! legacy-stopped? true))
          stop-all-called? (atom false)]
      (with-redefs [recon/stop-all! (fn [_] (reset! stop-all-called? true))]
        (let [component {:running (atom {})
                         :context {}
                         :legacy-stopper fake-stopper}]
          (ig/suspend-key! :exec/service-reconciler component)
          (is @legacy-stopped? "suspend invokes legacy stopper")
          (is @stop-all-called? "suspend invokes stop-all! on running"))))))


;; =============================================================================
;; :exec/fn-entities Tests (using mock)
;; =============================================================================

(defn- mock-sync-fns
  "Mock for sync-fns-to-storage! covering all arities (2/3/4/5)."
  ([_storage fn-defs] (mock-sync-fns _storage fn-defs {} {} {}))
  ([_storage fn-defs _ns-id-map] (mock-sync-fns _storage fn-defs _ns-id-map {} {}))
  ([_storage fn-defs _ns-id-map _extra-name->id]
   (mock-sync-fns _storage fn-defs _ns-id-map _extra-name->id {}))
  ([_storage fn-defs _ns-id-map _extra-name->id _extra-defs-by-name]
   (into {}
         (map (fn [fn-def]
                [(:name fn-def) {:id (random-uuid) :name (name (:name fn-def))}])
              fn-defs))))


(deftest fn-entities-init-test
  (testing "init-key creates fn entities from packages"
    (with-redefs [fn-composition/sync-fns-to-storage! mock-sync-fns]
      (let [storage :mock-storage
            packages {:fn-defs [{:name :test-fn :parent :const}
                                {:name :another-fn :parent :add}]}
            opts {:storage storage :packages packages}
            result (ig/init-key :exec/fn-entities opts)]
        (is (map? result) "Should return a map of fn entities")
        (is (contains? result :test-fn) "Should contain test-fn")
        (is (contains? result :another-fn) "Should contain another-fn"))))

  (testing "init-key handles empty fn-defs"
    (with-redefs [fn-composition/sync-fns-to-storage! mock-sync-fns]
      (let [opts {:storage :mock :packages {:fn-defs []}}
            result (ig/init-key :exec/fn-entities opts)]
        (is (map? result))
        (is (empty? result))))))

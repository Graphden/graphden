(ns ^:serial graphden.system.core-test
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
    [integrant.core :as ig]))


;; =============================================================================
;; :exec/service-reconciler lifecycle Tests
;;
;; At startup the reconciler seeds package-declared `:services` into the
;; `:service` table (idempotent — deterministic ids), then queries
;; enabled rows and reconciles. On halt-key! the supervised services
;; drain.
;;
;; The init-key path reads from `:storage`; the mocks below let the test
;; drive both branches (empty / non-empty enabled rows) without spinning
;; up a real container.
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


(deftest service-reconciler-init-empty-storage-test
  (testing "no :service rows + no seeded-services → init-key returns a quiet component"
    (let [reconcile-called? (atom false)]
      (with-redefs [recon/reconcile-once! (fn [_ _] (reset! reconcile-called? true))]
        (let [storage (mock-storage [])
              context {:storage storage}
              opts {:context context :packages {:seeded-services []}}
              component (ig/init-key :exec/service-reconciler opts)]
          (is (not @reconcile-called?)
              "nothing enabled → reconcile-once! not invoked")
          (is (some? (:running component)))
          (ig/halt-key! :exec/service-reconciler component))))))


(deftest service-reconciler-init-with-rows-reconciles-test
  (testing "enabled :service rows present → init-key calls reconcile"
    (let [reconcile-called? (atom false)]
      (with-redefs [recon/reconcile-once!
                    (fn [_ _] (reset! reconcile-called? true))]
        (let [storage (mock-storage [{:id (random-uuid)
                                      :fn-id (random-uuid)
                                      :enabled? true}])
              context {:storage storage}
              opts {:context context :packages {:seeded-services []}}
              component (ig/init-key :exec/service-reconciler opts)]
          (is @reconcile-called?
              "reconcile-once! invoked to start the desired services")
          (ig/halt-key! :exec/service-reconciler component))))))


(deftest service-reconciler-suspend-drains-running-test
  (testing "suspend-key! invokes stop-all! on running"
    (let [stop-all-called? (atom false)]
      (with-redefs [recon/stop-all! (fn [_] (reset! stop-all-called? true))]
        (let [component {:running (atom {}) :context {}}]
          (ig/suspend-key! :exec/service-reconciler component)
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
            opts {:storage storage :packages packages
                  ;; Mock fn-defs don't include the
                  ;; `allowed-type-check-failures` allowlist entries
                  ;; (those live in `web/crud`); skip the gate so the
                  ;; stale-allowlist arm doesn't trip on test mocks.
                  :skip-allowlist-gate? true}
            result (ig/init-key :exec/fn-entities opts)]
        (is (map? result) "Should return a map of fn entities")
        (is (contains? result :test-fn) "Should contain test-fn")
        (is (contains? result :another-fn) "Should contain another-fn"))))

  (testing "init-key handles empty fn-defs"
    (with-redefs [fn-composition/sync-fns-to-storage! mock-sync-fns]
      (let [opts {:storage :mock :packages {:fn-defs []}
                  :skip-allowlist-gate? true}
            result (ig/init-key :exec/fn-entities opts)]
        (is (map? result))
        (is (empty? result))))))

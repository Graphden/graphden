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
    [graphden.system.core]
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

    (query-latest-per-group [_ _ _ _] nil)

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


;; =============================================================================
;; Pure helpers — small dispatch / parsing fns that don't need a system
;; init. Pinned at the unit level so a regression in their behaviour
;; surfaces fast.
;; =============================================================================

(deftest env-truthy?-test
  (let [env-truthy? graphden.system.core/env-truthy?]
    (testing "boolean true / false / nil"
      (is (true?  (env-truthy? true)))
      (is (false? (env-truthy? false)))
      (is (false? (env-truthy? nil))))

    (testing "wire-friendly strings (case-insensitive)"
      (doseq [yes ["1" "true" "TRUE" "True" "yes" "YES" "on" "ON"]]
        (is (true? (env-truthy? yes))
            (str "should be enabled: " yes))))

    (testing "everything else off"
      (doseq [no [""    "0"  "false" "FALSE" "no" "off"
                  "  true  "   ; whitespace not stripped — intentional
                  "yeah" "enabled" "yep"]]
        (is (false? (env-truthy? no))
            (str "should be disabled: " (pr-str no)))))

    (testing "non-string truthy/falsy values pass through boolean"
      (is (true?  (env-truthy? :keyword)))
      (is (true?  (env-truthy? 42)))
      (is (true?  (env-truthy? {:a 1}))))))


(deftest as-instant-test
  (let [as-instant @#'graphden.system.core/as-instant
        target (java.time.Instant/parse "2026-05-21T12:00:00Z")]
    (testing "nil → nil"
      (is (nil? (as-instant nil))))

    (testing "Instant pass-through"
      (is (identical? target (as-instant target))))

    (testing "java.util.Date → Instant via .toInstant"
      (is (= target (as-instant (java.util.Date/from target)))))

    (testing "java.sql.Timestamp → Instant via .toInstant"
      ;; Same toInstant path as java.util.Date — sql.Timestamp extends Date.
      (is (= target (as-instant (java.sql.Timestamp/from target)))))

    (testing "ISO-8601 string → Instant"
      (is (= target (as-instant "2026-05-21T12:00:00Z"))))

    (testing "SQL-style string `YYYY-MM-DD HH:MM:SS` → Instant"
      ;; This is the codec's `replace space with T + strip .0 + append Z`
      ;; fallback path.
      (is (= target (as-instant "2026-05-21 12:00:00"))))

    (testing "SQL-style with trailing .0 fractional → Instant"
      (is (= target (as-instant "2026-05-21 12:00:00.0"))))))


(deftest compute-all-fn-name-ids-test
  (testing "extracts both base-fn-defs and fn-defs into one name→id map"
    ;; keep over `:base-fn-defs` destructures `[fn-name fn-def]` from
    ;; each map entry — `(when fn-name)` filters on the KEY only, so
    ;; a nil-val pair still produces an entry (the val is ignored
    ;; further on). nil-name fn-defs in the vector ARE skipped.
    (let [packages {:base-fn-defs {:my-base {:namespace 'core.x}}
                    :fn-defs [{:name "my-composed" :namespace 'app.y}
                              {:name nil :namespace 'app.skip}
                              {:name "other-composed" :namespace 'core.z}]}
          result (graphden.system.core/compute-all-fn-name-ids packages)]
      (is (= 3 (count result))
          "1 base + 2 named fn-defs (nil-name fn-def skipped)")
      (is (every? uuid? (vals result))
          "all values are UUIDs")
      (is (contains? result :my-base))
      (is (contains? result "my-composed"))
      (is (contains? result "other-composed"))))

  (testing "deterministic — same input → same UUIDs across calls"
    (let [packages {:base-fn-defs {} :fn-defs [{:name "x" :namespace 'a}]}
          r1 (graphden.system.core/compute-all-fn-name-ids packages)
          r2 (graphden.system.core/compute-all-fn-name-ids packages)]
      (is (= r1 r2))))

  (testing "empty input → empty map"
    (is (= {} (graphden.system.core/compute-all-fn-name-ids
                {:base-fn-defs {} :fn-defs []})))))


(deftest validate-no-name-collisions-test
  (let [check @#'graphden.system.core/validate-no-name-collisions!]
    (testing "distinct base-fn + fn-def names pass"
      (is (nil? (check {:base-fn-defs {:foo {}} :fn-defs [{:name :bar} {:name :baz}]}))))
    (testing "a base-fn ↔ fn-def name collision fails loud"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Colliding fn names"
            (check {:base-fn-defs {:foo {}} :fn-defs [{:name :foo}]}))))
    (testing "two fn-defs sharing a name fail too"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Colliding fn names"
            (check {:base-fn-defs {} :fn-defs [{:name :dup} {:name :dup}]}))))
    (testing "anonymous (name nil) fn-defs are excluded from the check"
      (is (nil? (check {:base-fn-defs {:foo {}}
                        :fn-defs [{:name nil} {:name nil} {:name :bar}]}))))))

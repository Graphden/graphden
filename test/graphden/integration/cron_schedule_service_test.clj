(ns ^:integration graphden.integration.cron-schedule-service-test
  "End-to-end acceptance test for the cron-as-service production
   path: derived `:schedule` registered as a `:service` row, picked
   up by the reconciler, supervised, halted.

   Complements `cron-schedule-runtime-test`:
   - That test exercises the executor path (`cr/execute` directly on
     the derived fn-id) — proves the closure-capture chain reaches
     the daemon thread and the stopper unwinds it.
   - This test exercises the SUPERVISOR path (POST :service row →
     `recon/reconcile-once!` starts it; DELETE → reconcile-once!
     stops it) — proves the cron fits into Phase 1 service registry
     end-to-end, which is the real production deployment shape.

   If both pass, an admin can ship a real cron via the editor's
   service-popover with no manual orchestration."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.registry.interface :as registry]
    [graphden.integration.test-helpers :as ith]
    [graphden.services.reconciler :as recon]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]
    [graphden.system.interface :as sys]))


(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))


(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(deftest ^:slow cron-service-lifecycle-through-reconciler-test
  ;; THE service-path acceptance test:
  ;;
  ;;   1. Bring up full system (executor + compiled registry + reconciler)
  ;;   2. Register a counter-bumping `:_cron-service-tick` base-fn
  ;;   3. Sync that base-fn into storage so it's referenceable
  ;;   4. Synthesise `:_cron-service-target :parent :schedule
  ;;        :args {:cron "* * * * * ?" :fn :_cron-service-tick}`
  ;;      — service-eligible (zero free args, inherits :process)
  ;;   5. Create a `:service` row pointing at it (enabled? true)
  ;;   6. `recon/reconcile-once!` → reconciler classifies the row as
  ;;      :to-start, invokes the executor, captures the stopper
  ;;   7. Wait ≥1 second — the cron fires; counter increments
  ;;   8. Disable the :service row (enabled? false)
  ;;   9. `recon/reconcile-once!` again — :to-stop branch, invokes
  ;;      the stopper, the future thread interrupts
  ;;  10. Wait — counter stops incrementing
  ;; Bring up :dev (full package chain + compiled registry) sans the
  ;; reconciler — we drive it manually in-test so we control exactly
  ;; when reconciles happen. Explicit per-call overrides (not
  ;; `with-redefs sys/read-config` / not pre-`register-base-fn!`) so
  ;; concurrent test invocations on one JVM can't race on global
  ;; state.
  (let [container-cfg (th/get-container-config *container*)
        tick-counter (atom 0)
        tick-impl (fn [_args _ctx] (swap! tick-counter inc) nil)
        system (sys/start-with-overrides!
                 :dev
                 [:db/schema
                  :db/postgres
                  :db/versioned
                  :app/packages
                  :exec/base-fns
                  :exec/fn-entities
                  :exec/context
                  :exec/compiled-registry]
                 {:db/postgres (select-keys container-cfg
                                            [:jdbc-url :username :password])
                  :exec/base-fns {:extra-base-fns
                                  {:_cron-service-tick tick-impl}}
                  ;; Minimise the package set: everything this test
                  ;; touches — `:schedule` / `:cron-parse` — lives in
                  ;; `core`, and the service target is synthesised in-test.
                  ;; `web` + `app` (the editor's ~2000 fn-defs) are never
                  ;; referenced, yet syncing + compiling + type-check-
                  ;; sweeping them dominated the runtime (~5 min → the
                  ;; whole full-graph bootstrap for nothing). Loading only
                  ;; `core` + `storage` is a large speedup with no coverage
                  ;; loss.
                  :app/packages {:package-names
                                 ["core" "storage"]}})]
    (try
      (let [storage (:db/versioned system)
            context (:exec/context system)
            ;; Sync the tick base-fn as a storage row so the
            ;; derived schedule can ref-bind it.
            _ (registry/sync-defs-to-storage!
                storage
                {:_cron-service-tick {:args {}
                                      :return-type :null
                                      :effects #{}}})
            schedule-fn (first (sp/query-entities storage :fn
                                                  {:name "schedule"}))
            tick-fn (first (sp/query-entities storage :fn
                                              {:name "_cron-service-tick"}))
            ;; `:cron` is now owned by `:cron-parse` (cron-next-after
            ;; inherits it via `:expr {:parent :cron-parse :args {:cron …}}`).
            cron-slot (ith/slot-by-owner-name storage "cron-parse" "cron")
            fn-slot (ith/slot-by-owner-name storage "_fire-target" "fn")
            derived (sp/create-entity storage :fn
                                      {:name "_cron-service-target"
                                       :parent-ids [(:id schedule-fn)]})
            _ (sp/create-entity storage :binding
                                {:fn-id (:id derived)
                                 :slot-id (:id cron-slot)
                                 :value "* * * * * ?"})
            _ (sp/create-entity storage :binding
                                {:fn-id (:id derived)
                                 :slot-id (:id fn-slot)
                                 :ref-fn-id (:id tick-fn)})
            ;; Force a compile of the freshly-written derived fn
            ;; so the reconciler's executor lookup finds it.
            _ ((requiring-resolve 'graphden.executor.compile-runtime/rebuild!)
               context)
            service-row (sp/create-entity storage :service
                                          {:fn-id (:id derived)
                                           :enabled? true
                                           :restart-policy :always})
            running (atom {})]
        (testing "reconcile-once! starts the cron service"
          (let [r (recon/reconcile-once! context running)]
            (is (= [(:id service-row)] (:started r))
                "reconciler classified the row as :to-start")
            (is (= 1 (count @running))
                "running atom has the entry")
            (is (fn? (-> @running vals first :stopper))
                "stopper-thunk captured from :schedule's return")))
        (testing "cron fires under reconciler supervision"
          ;; Poll until we observe ≥1 tick, capped at 2s.
          (let [deadline (+ (System/currentTimeMillis) 2000)]
            (while (and (< (System/currentTimeMillis) deadline)
                        (zero? @tick-counter))
              (Thread/sleep 50)))
          (is (>= @tick-counter 1)
              (str "expected ≥1 fires within 2s, got " @tick-counter)))
        (testing "disabling the :service row + reconcile-once! stops it"
          (sp/update-entity storage :service (:id service-row)
                            {:enabled? false})
          (let [r (recon/reconcile-once! context running)]
            (is (= [(:id service-row)] (:stopped r))
                "reconciler classified the row as :to-stop")
            (is (zero? (count @running))
                "running atom drained")))
        (testing "after stop, cron no longer fires"
          ;; Same shape as cron-schedule-runtime-test's stop check:
          ;; 1.1s for the in-flight :sleep-until-ms (≤1s for
          ;; "* * * * * ?") to unblock + 300ms stability window.
          (Thread/sleep 1100)
          (let [before-final @tick-counter]
            (Thread/sleep 300)
            (is (= before-final @tick-counter)
                "after reconciler-driven stop, no further increments"))))
      (finally (sys/stop! system)))))

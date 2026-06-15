(ns ^:integration graphden.integration.cron-schedule-runtime-test
  "End-to-end runtime acceptance test for the `:schedule` fn-def
   composition (commits 1–6 of the closure-capture series).

   Type-level acceptance lives in
   `graphden.types.closure-capture-test` (asserts `:schedule`'s
   rich-type has `:cron` and `:fn` as captured args + `:process`
   effect). This file asserts the runtime contract: spawn a derived
   cron, watch a counter increment from inside the background
   thread, halt cleanly via the stopper.

   Uses `sys/start!` to bring up the full Integrant system (with
   real package load + type-alias registration + compiled registry)
   so the test exercises the exact code path production runs. The
   derived `:_cron-runtime-target :parent :schedule :args {:cron …
   :fn :_cron-runtime-tick}` is then written directly to storage and
   compiled-recompiled. `:_cron-runtime-tick` is a 0-arg base-fn
   registered inline whose impl bumps a Clojure atom — that lets the
   test count fires without going through any side-effect machinery."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.registry.core :as registry-core]
    [graphden.executor.registry.interface :as registry]
    [graphden.integration.test-helpers :as ith]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]
    [graphden.system.interface :as sys]))


(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))


(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(deftest ^:slow cron-schedule-fires-and-halts-end-to-end-test
  ;; THE runtime acceptance test for closure-capture's flagship use
  ;; case. Spawns a cron at 1Hz, watches a Clojure atom increment
  ;; from inside the background thread, halts via the stopper,
  ;; asserts the counter stopped.
  ;;
  ;; If this passes:
  ;;   - The full closure-capture chain works at runtime: captured
  ;;     `:fn` (the tick callable) reaches `:_fire-target`'s
  ;;     `:call-noargs` invocation through 2+ levels of HOF wraps.
  ;;   - The `:future` spawn returns a stopper-thunk; the stopper
  ;;     interrupts the loop; the cron unwinds cleanly.
  ;;   - `:cron-next-after` + `:sleep-until-ms` actually compute and
  ;;     wait — not just type-check.
  ;; Use :dev profile — it loads core/web/app/examples packages
  ;; AND brings up the full executor chain (compiled registry).
  ;; Skip :exec/service-reconciler + :exec/cleanup-scheduler so we
  ;; don't bind port 9002 / start a hourly sweep during tests.
  ;; Pass per-test container creds AND the counter-bumping
  ;; `:_cron-runtime-tick` impl via explicit `start-with-overrides!`
  ;; (no `with-redefs sys/read-config`, no pre-`register-base-fn!`)
  ;; so concurrent test invocations on one JVM can't race on global
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
                                  {:_cron-runtime-tick tick-impl}}
                  ;; Cron runtime test only needs `:schedule` (core),
                  ;; `:future` (core), `:cron-parse` + `:cron-next-after`
                  ;; (core), and `:fire-target` (core). Drop the
                  ;; `examples` package — it contributes 171 fn-defs
                  ;; (mostly tutorial demos) that the test doesn't
                  ;; touch but the sync + compile passes still pay for.
                  :app/packages {:package-names
                                 ["core" "storage" "web" "app"]}})]
    (try
      (let [storage (:db/versioned system)
            context (:exec/context system)]
        ;; Hand the counter-tick base-fn into storage as a fn-row
        ;; so the derived schedule can ref-bind it. Re-registering
        ;; in the global registry is harmless; here we only need
        ;; the storage row.
        (registry/sync-defs-to-storage!
          storage
          {:_cron-runtime-tick {:args {}
                                :return-type :null
                                :effects #{}}})
        (let [schedule-fn (first (sp/query-entities storage :fn
                                                    {:name "schedule"}))
              tick-fn (first (sp/query-entities storage :fn
                                                {:name "_cron-runtime-tick"}))
              ;; Captured args of :schedule:
              ;;   :cron — owned by :cron-parse (:cron-next-after is now
              ;;           a `:cron-fire-after`-parent fn-def that
              ;;           inherits `:cron` through the binding
              ;;           `:expr {:parent :cron-parse :args {:cron …}}`)
              ;;   :fn   — the rename slot created by
              ;;           `:_fire-target :args {:func {:as :fn}}`;
              ;;           it's owned by :_fire-target and its
              ;;           :source-slot-id points at :call-noargs :func.
              cron-slot (ith/slot-by-owner-name storage "cron-parse" "cron")
              fn-slot (ith/slot-by-owner-name storage "_fire-target" "fn")]
          (is (some? schedule-fn) ":schedule fn-def loaded from core package")
          (is (some? tick-fn) ":_cron-runtime-tick row created")
          (is (some? cron-slot) ":cron slot resolved")
          (is (some? fn-slot) ":fn rename slot resolved")
          ;; Synthesise the admin-side derived fn-def. Binding
          ;; both captured args here makes it 0-free-arg.
          (let [derived (sp/create-entity storage :fn
                                          {:name "_cron-runtime-target"
                                           :parent-ids [(:id schedule-fn)]})
                _ (sp/create-entity storage :binding
                                    {:fn-id (:id derived)
                                     :slot-id (:id cron-slot)
                                     :value "* * * * * ?"})
                _ (sp/create-entity storage :binding
                                    {:fn-id (:id derived)
                                     :slot-id (:id fn-slot)
                                     :ref-fn-id (:id tick-fn)})
                ;; Force a registry rebuild so the new fn-row
                ;; gets a compiled closure.
                _ (cr/rebuild! context)
                stopper (cr/execute context (:id derived) {})]
            (testing "execute returns a stopper-thunk (schedule's return)"
              (is (fn? stopper) "schedule's return-type is [:fn {} :null]"))
            (testing "cron fires at ~1Hz — counter increments while loop runs"
              ;; Poll until we observe ≥1 tick, capped at 2s. Beats
              ;; the previous flat 2500ms by hitting fast when the
              ;; cron fires inside the first second.
              (let [deadline (+ (System/currentTimeMillis) 2000)]
                (while (and (< (System/currentTimeMillis) deadline)
                            (zero? @tick-counter))
                  (Thread/sleep 50)))
              (is (>= @tick-counter 1)
                  (str "expected ≥1 fires within 2s, got " @tick-counter)))
            (testing "stopper halts the loop — counter stops incrementing"
              (stopper)
              ;; Mock-clock path is too invasive to land cleanly
              ;; (cron primitives use real System/currentTimeMillis
              ;; + Thread/sleep). Instead: poll for stability. After
              ;; the interrupt the daemon takes <100ms to unwind in
              ;; practice; wait up to 1.1s for the next cron-tick
              ;; window to close (sleep-until-ms is bounded ≤1s for
              ;; "* * * * * ?") then verify the counter is stable
              ;; over a 300ms window — much tighter than the old
              ;; flat 2.2s.
              (Thread/sleep 1100)
              (let [before-final @tick-counter]
                (Thread/sleep 300)
                (is (= before-final @tick-counter)
                    "after stopper, no further increments")))
            (testing ":process effect is in :schedule's rich-type"
              (let [eff (some-> (registry-core/rich-type-of :schedule)
                                :effects set)]
                (is (contains? eff :process)
                    "service-eligibility marker present at runtime"))))))
      (finally (sys/stop! system)))))

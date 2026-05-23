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
    [graphden.executor.interface :as exec]
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
  (let [container-cfg (th/get-container-config *container*)
        tick-counter (atom 0)
        original-read-config sys/read-config]
    ;; Register the counter-bumping impl BEFORE the system starts —
    ;; the package loader's base-fn registration is idempotent for
    ;; names already in the registry (overwrites the impl), and our
    ;; sync below adds a fn-row pointing at this impl name.
    (exec/register-base-fn!
      :_cron-runtime-tick
      (fn [_args _ctx] (swap! tick-counter inc) nil))
    (with-redefs [sys/read-config (fn [profile]
                                    (-> (original-read-config profile)
                                        (update :db/postgres merge
                                                (select-keys container-cfg
                                                             [:jdbc-url :username :password]))))]
      ;; Use :dev profile — it loads core/web/app/examples packages
      ;; AND brings up the full executor chain (compiled registry).
      ;; Skip :exec/service-reconciler + :exec/cleanup-scheduler so
      ;; we don't bind port 9002 / start a hourly sweep during tests.
      (let [system (sys/start! :dev [:db/schema
                                     :db/postgres
                                     :db/versioned
                                     :app/packages
                                     :exec/base-fns
                                     :exec/fn-entities
                                     :exec/context
                                     :exec/compiled-registry])]
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
                  ;;   :cron — owned by :cron-next-after directly
                  ;;   :fn   — the rename slot created by
                  ;;           `:_fire-target :args {:func {:as :fn}}`;
                  ;;           it's owned by :_fire-target and its
                  ;;           :source-slot-id points at :call-noargs :func.
                  cron-slot (ith/slot-by-owner-name storage "cron-next-after" "cron")
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
                  (Thread/sleep 2500)
                  (let [mid @tick-counter]
                    (is (>= mid 1)
                        (str "expected ≥1 fires in 2.5s, got " mid))))
                (testing "stopper halts the loop — counter stops incrementing"
                  (stopper)
                  ;; Give the thread up to 1500ms to notice the
                  ;; interrupt and unwind through the next iteration's
                  ;; isInterrupted check (worst case it was just past
                  ;; the check and runs one more tick — :sleep-until-ms
                  ;; is bounded ≤1s for "* * * * * ?").
                  (Thread/sleep 1500)
                  (let [before-final @tick-counter]
                    (Thread/sleep 1500)
                    (is (= before-final @tick-counter)
                        "after stopper, no further increments")))
                (testing ":process effect is in :schedule's rich-type"
                  (let [eff (some-> (registry-core/rich-type-of :schedule)
                                    :effects set)]
                    (is (contains? eff :process)
                        "service-eligibility marker present at runtime"))))))
          (finally (sys/stop! system)))))))

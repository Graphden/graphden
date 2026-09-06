(ns ^:integration graphden.integration.start-all-service-test
  "One service, many triggers — `:start-all` over a `:schedule` and an
   `:interval`, registered as ONE `:service` row and driven by the
   reconciler: both triggers fire under supervision, the one stopper
   stops both, and a `:traced-call`-wrapped target lands every fire as
   an `:fn-execution` row of the tick fn.

   Sibling of `cron-schedule-service-test` (one cron trigger)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.executor.registry.interface :as registry]
    [graphden.integration.test-helpers :as ith]
    [graphden.services.reconciler :as recon]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]
    [graphden.system.interface :as sys]))


(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))


(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(defn- fn-named
  [storage n]
  (or (first (sp/query-entities storage :fn {:name n}))
      (throw (ex-info (str "no fn named " n) {:name n}))))


(deftest ^:slow start-all-service-lifecycle-through-reconciler-test
  (let [container-cfg (th/get-container-config *container*)
        ticks (atom 0)
        tick-impl (fn [_args _ctx] (swap! ticks inc) nil)
        system (sys/start-with-overrides!
                 :dev
                 [:db/schema :db/postgres :db/versioned :app/packages
                  :exec/base-fns :exec/fn-entities :exec/context
                  :exec/compiled-registry]
                 {:db/postgres (select-keys container-cfg
                                            [:jdbc-url :username :password])
                  :exec/base-fns {:extra-base-fns {:_sa-tick tick-impl}}
                  :app/packages {:package-names ["core" "storage"]}})]
    (try
      (let [storage (:db/versioned system)
            context (:exec/context system)
            _ (registry/sync-defs-to-storage!
                storage {:_sa-tick {:args {} :return-type :null :effects #{}}})
            tick-fn (fn-named storage "_sa-tick")
            cron-slot (ith/slot-by-owner-name storage "cron-parse" "cron")
            fn-slot (ith/slot-by-owner-name storage "_fire-target" "fn")
            every-slot (ith/slot-by-owner-name storage "_interval-sleep" "every-ms")
            traced-fn-slot (ith/slot-by-owner-name storage "traced-call" "fn")
            triggers-slot (ith/slot-by-owner-name storage "start-all" "triggers")
            ;; :_sa-tick-traced — the tick behind :traced-call, so each
            ;; interval fire persists as a run of :_sa-tick.
            traced (sp/create-entity storage :fn
                                     {:name "_sa-tick-traced"
                                      :parent-ids [(:id (fn-named storage "traced-call"))]})
            _ (sp/create-entity storage :binding
                                {:fn-id (:id traced) :slot-id (:id traced-fn-slot)
                                 :ref-fn-id (:id tick-fn)})
            cron (sp/create-entity storage :fn
                                   {:name "_sa-cron"
                                    :parent-ids [(:id (fn-named storage "schedule"))]})
            _ (sp/create-entity storage :binding
                                {:fn-id (:id cron) :slot-id (:id cron-slot)
                                 :value "* * * * * ?"})
            _ (sp/create-entity storage :binding
                                {:fn-id (:id cron) :slot-id (:id fn-slot)
                                 :ref-fn-id (:id tick-fn)})
            interval (sp/create-entity storage :fn
                                       {:name "_sa-interval"
                                        :parent-ids [(:id (fn-named storage "interval"))]})
            _ (sp/create-entity storage :binding
                                {:fn-id (:id interval) :slot-id (:id every-slot)
                                 :value 200})
            _ (sp/create-entity storage :binding
                                {:fn-id (:id interval) :slot-id (:id fn-slot)
                                 :ref-fn-id (:id traced)})
            jobs (sp/create-entity storage :fn
                                   {:name "_sa-jobs"
                                    :parent-ids [(:id (fn-named storage "start-all"))]})
            list-bn (sp/create-entity storage :binding
                                      {:fn-id (:id jobs) :slot-id (:id triggers-slot)
                                       :list-append true})
            _ (sp/create-entity storage :binding-list-item
                                {:binding-id (:id list-bn) :position 0
                                 :ref-fn-id (:id cron)})
            _ (sp/create-entity storage :binding-list-item
                                {:binding-id (:id list-bn) :position 1
                                 :ref-fn-id (:id interval)})
            _ ((requiring-resolve 'graphden.executor.compile-runtime/rebuild!) context)
            service-row (sp/create-entity storage :service
                                          {:fn-id (:id jobs)
                                           :enabled? true
                                           :restart-policy :always})
            running (atom {})]
        (testing "the trigger list is service-eligible: nothing blocks starting it"
          (is (empty? (lookup/service-blocking-free-args context (:id jobs)))))
        (testing "reconcile-once! starts the whole trigger set as one service"
          (let [r (recon/reconcile-once! context running)]
            (is (= [(:id service-row)] (:started r)))
            (is (= 1 (count @running)))
            (let [stop (-> @running vals first :stopper)]
              (is (fn? stop))
              (is (true? ((:alive? (meta stop)))) "the combined handle reports liveness"))))
        (testing "both triggers fire under supervision"
          ;; ≤1s cron + 200 ms interval → ≥3 ticks well inside 2.5 s.
          (let [deadline (+ (System/currentTimeMillis) 2500)]
            (while (and (< (System/currentTimeMillis) deadline) (< @ticks 3))
              (Thread/sleep 50)))
          (is (>= @ticks 3) (str "expected ≥3 ticks from two triggers, got " @ticks)))
        (testing "each interval fire is a persisted run of the tick fn (traced-call)"
          (let [rows (sp/query-entities storage :fn-execution {})
                tick-versions (set (map :id (sp/query-entities storage :fn-version
                                                               {:fn-id (:id tick-fn)})))]
            (is (>= (count rows) 1) "the fire landed as an :fn-execution row")
            (is (every? #(contains? tick-versions (:fn-version-id %)) rows)
                "…of the TARGET (the tick fn), not of the trigger")
            (is (every? #(some? (:trace-id %)) rows) "each fire opened a trace of its own")
            (is (= #{:succeeded} (set (map :status rows))))))
        (testing "disabling the row stops BOTH triggers through the one stopper"
          (sp/update-entity storage :service (:id service-row) {:enabled? false})
          (let [r (recon/reconcile-once! context running)]
            (is (= [(:id service-row)] (:stopped r)))
            (is (zero? (count @running))))
          (Thread/sleep 1100)
          (let [before @ticks]
            (Thread/sleep 400)
            (is (= before @ticks) "no fire from either trigger after the stop"))))
      (finally (sys/stop! system)))))

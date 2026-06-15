(ns graphden.services.reconciler-test
  "Tests for `graphden.services.reconciler` — the diff/start/stop
   policy and the storage-driven reconcile pass.

   The pure `diff-desired` is tested in isolation; the start/stop
   pass uses a fixture-built storage + a synthetic base-fn whose
   impl records its calls (via an atom) so we can assert that
   reconcile-once! actually invoked the executor with the right
   service-args and that stoppers fire on shutdown."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.entities]
    [graphden.executor.compile-runtime :as cr-runtime]
    [graphden.executor.context :as ctx]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.schema.executions.schema :as es]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.services.schema :as svcs]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.services.reconciler :as recon]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.system.branch-router :as br]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once
  (setup/create-container-fixture)
  ;; Isolate the runtime base-fn registrations these tests do
  ;; (`exec/register-base-fn! :test-needs-arg` and similar) into a
  ;; thread-local override atom — keeps them out of the process-
  ;; global registry that sibling test ns'es read from.
  exec/with-clean-registry
  ;; `record-rich-types-raw!` writes inside the validate-create-rejects
  ;; tests restore to a stub (effects #{}) rather than dissoc; the
  ;; stub sticks around for sibling integration tests. Force a clean
  ;; teardown of the whole rich-types-registry — see check-test for
  ;; the same fix.
  exec/with-isolated-rich-types)


(defn- full-schema
  []
  (-> (mds/create-builder)
      (gds/extend-builder)
      (vts/extend-builder)
      (vds/extend-builder)
      (es/extend-builder)
      (svcs/extend-builder)
      (ds/build)))


(defn- create-full-storage
  []
  (pth/clean-database-fast! @(resolve 'graphden.executor.test-setup/*container*))
  (let [container @(resolve 'graphden.executor.test-setup/*container*)
        storage (pg/create-storage (pth/get-container-config container))]
    (sp/initialize storage (full-schema))
    (sp/upsert-entities storage :fn
                        (mapv #(dissoc % :kind) (records/boot-primitive-records)))
    (let [branch (sp/create-entity storage :branch
                                   {:name "test-branch"
                                    :created-at (java.time.Instant/now)})]
      (vs/->VersionedStorage storage (:id branch)))))


(defn- test-ctx
  [storage]
  (ctx/create-context {:storage storage :base-fns (exec/get-default-registry)}))


;; ============================================================================
;; Pure: diff-desired
;; ============================================================================

(deftest diff-desired-test
  (testing "empty inputs → empty diff"
    (is (= {:to-start [] :to-stop []}
           (recon/diff-desired #{} #{}))))

  (testing "enabled but not running → start"
    (let [d (recon/diff-desired #{1 2 3} #{2})]
      (is (= #{1 3} (set (:to-start d))))
      (is (= [] (:to-stop d)))))

  (testing "running but not enabled → stop"
    (let [d (recon/diff-desired #{} #{1 2})]
      (is (= [] (:to-start d)))
      (is (= #{1 2} (set (:to-stop d))))))

  (testing "intersection — already running + enabled stays untouched"
    (let [d (recon/diff-desired #{1 2 3} #{2 3 4})]
      (is (= [1] (:to-start d)))
      (is (= [4] (:to-stop d))))))


;; ============================================================================
;; Storage-driven: reconcile-once! actually starts + stops
;; ============================================================================

(defn- make-trackable-fn!
  "Register a no-arg base-fn whose impl records each invocation into
   `calls` and returns a stopper-thunk that records its own invocation
   into `stops`. Mirrors the http-kit shape (return value = stopper).
   Services target fns with no free args; per-instance distinction is
   via different fn-defs (different suffixes / impl-names), not via
   per-call args."
  [storage suffix calls stops]
  (let [base-name (str "test-trackable-" suffix)
        composed-name (str "my-test-trackable-" suffix)
        impl-fn (fn [_args _ctx]
                  (swap! calls conj {:suffix suffix})
                  (fn [] (swap! stops conj {:suffix suffix})))]
    (exec/register-base-fn! (keyword base-name) impl-fn)
    (let [base (setup/create-base-fn! storage base-name :any)]
      {:base base
       :composed (setup/create-composed-fn! storage composed-name (:id base))})))


(defn- make-service-row!
  ([storage fn-id enabled?]
   (make-service-row! storage fn-id enabled? nil))
  ([storage fn-id enabled? branch-id]
   (sp/create-entity storage :service
                     (cond-> {:fn-id fn-id
                              :enabled? enabled?
                              :restart-policy :always}
                       branch-id (assoc :branch-id branch-id)))))


(deftest reconcile-once-starts-enabled-services-test
  (let [storage (create-full-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed}
        (make-trackable-fn! storage "start" calls stops)
        svc (make-service-row! storage (:id composed) true)
        c (test-ctx storage)
        running (atom {})]
    (try
      (let [r (recon/reconcile-once! c running)]
        (testing "diff classified the row as :to-start"
          (is (= [(:id svc)] (:started r)))
          (is (= [] (:stopped r))))
        (testing "the impl was invoked once"
          (is (= [{:suffix "start"}] @calls)))
        (testing "running atom carries the entry"
          (is (= 1 (count @running)))
          (is (= (:id composed) (-> @running vals first :fn-id)))
          (is (fn? (-> @running vals first :stopper)))))
      (finally (sp/close storage)))))


(deftest reconcile-once-stops-disabled-services-test
  (let [storage (create-full-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed}
        (make-trackable-fn! storage "stop-disabled" calls stops)
        svc (make-service-row! storage (:id composed) true)
        c (test-ctx storage)
        running (atom {})]
    (try
      ;; First pass — starts the service.
      (recon/reconcile-once! c running)
      (is (= 1 (count @running)))

      ;; Flip enabled? false — second pass should stop it.
      (sp/update-entity storage :service (:id svc) {:enabled? false})
      (let [r (recon/reconcile-once! c running)]
        (testing "diff classified the row as :to-stop"
          (is (= [] (:started r)))
          (is (= [(:id svc)] (:stopped r))))
        (testing "stopper was invoked"
          (is (= [{:suffix "stop-disabled"}] @stops)))
        (testing "running atom now empty"
          (is (zero? (count @running)))))
      (finally (sp/close storage)))))


(deftest reconcile-once-idempotent-when-running-matches-desired-test
  (let [storage (create-full-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed}
        (make-trackable-fn! storage "idem" calls stops)]
    (make-service-row! storage (:id composed) true)
    (let [c (test-ctx storage)
          running (atom {})]
      (try
        (recon/reconcile-once! c running)
        (is (= 1 (count @calls)) "first pass started")
        ;; Second pass — no DB changes, no work.
        (let [r (recon/reconcile-once! c running)]
          (is (= [] (:started r)))
          (is (= [] (:stopped r))))
        (is (= 1 (count @calls)) "impl was NOT re-invoked on second pass")
        (is (= [] @stops) "no stopper fired")
        (finally (sp/close storage))))))


(deftest stop-all-drains-running-test
  (let [storage (create-full-storage)
        calls (atom [])
        stops (atom [])
        ;; Two DIFFERENT fns (different suffixes / impls) — the model
        ;; says each "deployment" = its own fn-def, so simulating
        ;; "two running services" means two fns + two services.
        {a :composed} (make-trackable-fn! storage "drain-a" calls stops)
        {b :composed} (make-trackable-fn! storage "drain-b" calls stops)]
    (make-service-row! storage (:id a) true)
    (make-service-row! storage (:id b) true)
    (let [c (test-ctx storage)
          running (atom {})]
      (try
        (recon/reconcile-once! c running)
        (is (= 2 (count @running)))
        (recon/stop-all! running)
        (testing "all stoppers called, running cleared"
          (is (zero? (count @running)))
          (is (= #{"drain-a" "drain-b"} (set (map :suffix @stops)))))
        (finally (sp/close storage))))))


;; ============================================================================
;; Generic CRUD smoke — :service is a regular entity, the standard
;; storage protocol should handle it without per-type machinery. If
;; this breaks, the admin's "POST /api/entities/service" workflow
;; (Phase 1 has no /api/services CRUD endpoints) would silently break.
;; ============================================================================

(deftest service-roundtrips-through-generic-crud-test
  (let [storage (create-full-storage)
        {composed :composed}
        (make-trackable-fn! storage "crud-rt" (atom []) (atom []))
        svc-row (sp/create-entity storage :service
                                  {:fn-id (:id composed)
                                   :enabled? false
                                   :restart-policy :on-failure})]
    (try
      (testing "create + read-back of :service preserves all fields"
        (let [r (sp/read-entity storage :service (:id svc-row))]
          (is (some? r))
          (is (= (:id composed) (:fn-id r)))
          (is (false? (:enabled? r)))
          (is (#{:on-failure "on-failure"} (:restart-policy r)))))
      (testing "update flips :enabled? in place (non-versioned)"
        (sp/update-entity storage :service (:id svc-row) {:enabled? true})
        (is (true? (:enabled? (sp/read-entity storage :service (:id svc-row))))))
      (testing "query by :enabled? filters correctly"
        (let [rows (sp/query-entities storage :service {:enabled? true})]
          (is (= 1 (count rows)))
          (is (= (:id svc-row) (-> rows first :id)))))
      (finally (sp/close storage)))))


;; ============================================================================
;; validate-create rejects :service when target fn has free args.
;; The runtime invokes service fns with empty args — leaving a free
;; slot guarantees startup crash, so we refuse upfront.
;; ============================================================================

(deftest validate-create-rejects-service-on-fn-with-free-args-test
  ;; Behavioural test of the two service-eligibility guards used by
  ;; the `:_create-service-free-args-rej` and `:_create-service-no-
  ;; process-rej` graph fn-defs that compose the production
  ;; `:process-create-entity` rejection chain. Calls the underlying
  ;; mechanisms directly (`fn-exec-lookup/free-arg-slot-map` +
  ;; `entities/chain-has-process-effect?`) — same code paths the
  ;; defbase wrappers `free-arg-slot-map` and
  ;; `chain-has-process-effect?` invoke.
  (let [storage (create-full-storage)
        ;; A base-fn with one declared but unbound slot — composed
        ;; instance inherits the slot as a free arg.
        base-name "test-needs-arg"
        composed-name "my-test-needs-arg"
        _impl (exec/register-base-fn! (keyword base-name) (fn [_ _] :ok))
        base (setup/create-base-fn! storage base-name :any)
        port-slot (setup/create-slot! storage "port" :int)
        _ (setup/attach-slot! storage (:id base) (:id port-slot) 0)
        composed (setup/create-composed-fn! storage composed-name (:id base))
        c (test-ctx storage)
        free-arg-slot-map (requiring-resolve
                            'graphden.crud.fn-execution.lookup/free-arg-slot-map)
        chain-process? (requiring-resolve
                         'graphden.crud.entities/chain-has-process-effect?)]
    (try
      (testing "composed fn with unbound :port slot has :port as a free arg"
        (let [free (free-arg-slot-map c (:id composed))]
          (is (contains? free :port)
              ":port surfaces as a free arg → service rejection would fire")))

      (testing "binding the slot collapses :port to empty free-arg map"
        (let [derived-name "my-test-needs-arg-bound"
              derived (setup/create-composed-fn! storage
                                                 derived-name
                                                 (:id composed))]
          (setup/bind-value! storage (:id derived) (:id port-slot) 8080)
          (let [free (free-arg-slot-map c (:id derived))]
            (is (not (contains? free :port))
                ":port bound → no free arg → free-args guard passes"))

          (testing ":process effect declared via rich-types ancestor chain"
            (registry/record-rich-types-raw!
              (keyword derived-name)
              {:args {} :return [:fn {} :null] :effects #{:process}})
            (is (true? (chain-process? storage (:id derived)))
                ":process effect found on the fn itself")
            ;; Cleanup rich-type so it doesn't bleed into other tests.
            (registry/record-rich-types-raw!
              (keyword derived-name)
              {:args {} :return :any :effects #{}}))))

      (testing "without :process effect declared, chain-has-process-effect? is false"
        (let [derived-name "my-test-needs-arg-bound-noeff"
              derived (setup/create-composed-fn! storage
                                                 derived-name
                                                 (:id composed))]
          (setup/bind-value! storage (:id derived) (:id port-slot) 8080)
          (is (false? (chain-process? storage (:id derived)))
              ":process effect NOT declared → service rejection would fire")))
      (finally (sp/close storage)))))


;; ============================================================================
;; Per-branch routing — :service.branch-id picks the per-branch
;; ExecutionContext via branch-router/ctx-for. Tests both the
;; no-router fallback (legacy + tests) and the with-router routing
;; path (production).
;; ============================================================================

(deftest reconcile-records-branch-id-on-running-entry-test
  ;; When `:service.branch-id` is set, the running-atom entry should
  ;; carry it so `restart-services-on-branch!` can identify which
  ;; entries belong to a merge target. Tested without a router so the
  ;; ctx fallback is exercised but the metadata is the focus.
  (let [storage (create-full-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed}
        (make-trackable-fn! storage "branch-record" calls stops)
        feat-id (random-uuid)
        svc (make-service-row! storage (:id composed) true feat-id)
        c (test-ctx storage)
        running (atom {})]
    (try
      (recon/reconcile-once! c running)
      (testing "running entry carries :branch-id from the row"
        (is (= 1 (count @running)))
        (let [entry (-> @running vals first)]
          (is (= feat-id (:branch-id entry)))
          (is (= (:id composed) (:fn-id entry)))))
      (testing "the call was made (no-router path falls back to base ctx)"
        (is (= [{:suffix "branch-record"}] @calls))
        (testing "service-id matches"
          (is (= [(:id svc)] (vec (keys @running))))))
      (finally (sp/close storage)))))


(deftest reconcile-no-router-falls-back-to-base-ctx-test
  ;; `ctx-for-service` returns base-ctx when `branch-router/current-
  ;; router` is nil (tests without `:exec/branch-router` init-key).
  ;; The service starts against the base ctx regardless of its
  ;; `:branch-id` value — same as the legacy single-branch path.
  (let [storage (create-full-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed}
        (make-trackable-fn! storage "no-router" calls stops)
        _ (make-service-row! storage (:id composed) true (random-uuid))
        c (test-ctx storage)
        running (atom {})]
    (try
      (br/clear-active-router!)
      (recon/reconcile-once! c running)
      (is (= [{:suffix "no-router"}] @calls)
          "service started despite branch-id (router was nil → fallback)")
      (finally (sp/close storage) (br/clear-active-router!)))))


(deftest restart-services-on-branch-only-touches-matching-entries-test
  ;; `restart-services-on-branch!` stops + re-starts ONLY services
  ;; whose recorded `:branch-id` matches the target. Services on
  ;; other branches (or with no branch-id) are left running.
  (let [storage (create-full-storage)
        calls-a (atom [])
        stops-a (atom [])
        calls-b (atom [])
        stops-b (atom [])
        {a-composed :composed}
        (make-trackable-fn! storage "restart-a" calls-a stops-a)
        {b-composed :composed}
        (make-trackable-fn! storage "restart-b" calls-b stops-b)
        branch-x (random-uuid)
        branch-y (random-uuid)
        _svc-a (make-service-row! storage (:id a-composed) true branch-x)
        _svc-b (make-service-row! storage (:id b-composed) true branch-y)
        c (test-ctx storage)
        running (atom {})]
    (try
      (br/clear-active-router!)
      ;; Initial reconcile — both running, each tagged with its branch.
      (recon/reconcile-once! c running)
      (is (= 2 (count @running)))
      ;; Restart only branch-x's services.
      (recon/restart-services-on-branch! c running branch-x)
      (testing "only the matching branch's stopper fired"
        (is (= [{:suffix "restart-a"}] @stops-a))
        (is (= [] @stops-b)
            "branch-y service untouched"))
      (testing "the matching service was re-started (reconcile inside restart)"
        (is (= 2 (count @calls-a))
            "branch-x impl was called twice: initial + post-restart")
        (is (= 1 (count @calls-b))
            "branch-y impl was called once (initial only)"))
      (testing "both rows remain in running map"
        (is (= 2 (count @running))))
      (finally (sp/close storage) (br/clear-active-router!)))))


(deftest start-failure-is-recorded-as-nil-stopper-test
  (testing "if the impl throws on start, the service is still tracked"
    (let [storage (create-full-storage)
          base-name "test-failing-svc"
          composed-name "my-test-failing-svc"
          impl-fn (fn [_args _ctx]
                    (throw (ex-info "port in use" {:type :test/bind-err})))]
      (exec/register-base-fn! (keyword base-name) impl-fn)
      (let [base (setup/create-base-fn! storage base-name :any)
            composed (setup/create-composed-fn! storage composed-name (:id base))
            svc (sp/create-entity storage :service
                                  {:fn-id (:id composed)
                                   :enabled? true
                                   :restart-policy :never})  ; no retries
            c (test-ctx storage)
            running (atom {})]
        (try
          (recon/reconcile-once! c running {:max-retries 0 :backoff-ms 0})
          (testing "service is registered in running with nil stopper"
            (is (= 1 (count @running)))
            (is (nil? (-> @running (get (:id svc)) :stopper)))
            (is (some? (-> @running (get (:id svc)) :start-failed-at))
                ":start-failed-at recorded so admin can see we gave up"))
          (testing "subsequent stop is a logged no-op (does not throw)"
            (is (some? (recon/stop-all! running)))
            (is (zero? (count @running))))
          (finally (sp/close storage)))))))


;; ============================================================================
;; Supervisor — `:restart-policy :always` / `:on-failure` retries start
;; on exception (bounded). `:never` gives up after first attempt.
;; ============================================================================

(deftest supervisor-retries-on-start-failure-test
  (let [storage (create-full-storage)
        ;; Impl that fails the first N times then succeeds — lets us
        ;; assert the supervisor's retry loop without sleeping real
        ;; backoff time.
        attempt-counter (atom 0)
        fail-times 2
        base-name "test-flaky-svc"
        composed-name "my-test-flaky-svc"
        impl-fn (fn [_args _ctx]
                  (let [n (swap! attempt-counter inc)]
                    (if (<= n fail-times)
                      (throw (ex-info "transient failure" {:attempt n}))
                      (fn [] :stopped))))]
    (exec/register-base-fn! (keyword base-name) impl-fn)
    (let [base (setup/create-base-fn! storage base-name :any)
          composed (setup/create-composed-fn! storage composed-name (:id base))
          svc (sp/create-entity storage :service
                                {:fn-id (:id composed)
                                 :enabled? true
                                 :restart-policy :always})
          c (test-ctx storage)
          running (atom {})]
      (try
        ;; Backoff 0ms so the test is fast — supervisor still loops the
        ;; specified max-retries times.
        (recon/reconcile-once! c running {:max-retries 5 :backoff-ms 0})
        (testing "the impl was retried until it succeeded"
          (is (= 3 @attempt-counter)
              "2 failures + 1 success = 3 invocations"))
        (testing "running entry has the SUCCESSFUL stopper and start-attempts=3"
          (let [entry (get @running (:id svc))]
            (is (fn? (:stopper entry)))
            (is (= 3 (:start-attempts entry)))
            (is (nil? (:start-failed-at entry))
                "no give-up marker — we eventually succeeded")))
        (finally (sp/close storage))))))


(deftest supervisor-respects-policy-never-test
  (testing ":never policy → single attempt regardless of failure"
    (let [storage (create-full-storage)
          attempt-counter (atom 0)
          base-name "test-fail-never-svc"
          composed-name "my-test-fail-never-svc"
          impl-fn (fn [_args _ctx]
                    (swap! attempt-counter inc)
                    (throw (ex-info "always fails" {})))]
      (exec/register-base-fn! (keyword base-name) impl-fn)
      (let [base (setup/create-base-fn! storage base-name :any)
            composed (setup/create-composed-fn! storage composed-name (:id base))
            svc (sp/create-entity storage :service
                                  {:fn-id (:id composed)
                                   :enabled? true
                                   :restart-policy :never})
            c (test-ctx storage)
            running (atom {})]
        (try
          (recon/reconcile-once! c running {:max-retries 99 :backoff-ms 0})
          (testing "exactly one call — max-retries ignored for :never"
            (is (= 1 @attempt-counter)))
          (testing "start-attempts=1, start-failed-at set"
            (let [entry (get @running (:id svc))]
              (is (= 1 (:start-attempts entry)))
              (is (some? (:start-failed-at entry)))))
          (finally (sp/close storage)))))))


(deftest supervisor-exhausts-retries-and-gives-up-test
  (testing "permanently-failing :always service → bounded attempts, then give up"
    (let [storage (create-full-storage)
          attempt-counter (atom 0)
          base-name "test-permafail-svc"
          composed-name "my-test-permafail-svc"
          impl-fn (fn [_args _ctx]
                    (swap! attempt-counter inc)
                    (throw (ex-info "permafail" {})))]
      (exec/register-base-fn! (keyword base-name) impl-fn)
      (let [base (setup/create-base-fn! storage base-name :any)
            composed (setup/create-composed-fn! storage composed-name (:id base))
            svc (sp/create-entity storage :service
                                  {:fn-id (:id composed)
                                   :enabled? true
                                   :restart-policy :always})
            c (test-ctx storage)
            running (atom {})]
        (try
          (recon/reconcile-once! c running {:max-retries 3 :backoff-ms 0})
          (testing "exactly 1 + max-retries = 4 attempts before giving up"
            (is (= 4 @attempt-counter)))
          (testing "entry exists with :start-failed-at + nil stopper"
            (let [entry (get @running (:id svc))]
              (is (= 4 (:start-attempts entry)))
              (is (nil? (:stopper entry)))
              (is (some? (:start-failed-at entry)))))
          (finally (sp/close storage)))))))


(deftest restart-services-depending-on-recompiles-affected-test
  ;; `restart-services-depending-on!` is the post-edit hook fired
  ;; from `crud.entities/invalidate!`. Cron-loop services hold the
  ;; pre-edit closure by reference, so a fn-graph edit underneath a
  ;; service has to stop + restart it for the new compile to take.
  ;;
  ;; Setup: two services A + B, each on a different fn. Wire a fake
  ;; reverse-dep index where edits to fn-X transitively-affect ONLY
  ;; A. Call the restart hook with seeds #{fn-X}. Expect A to stop +
  ;; restart, B untouched.
  (let [storage (create-full-storage)
        calls-a (atom [])
        stops-a (atom [])
        calls-b (atom [])
        stops-b (atom [])
        {a-composed :composed}
        (make-trackable-fn! storage "restart-dep-a" calls-a stops-a)
        {b-composed :composed}
        (make-trackable-fn! storage "restart-dep-b" calls-b stops-b)
        _svc-a (make-service-row! storage (:id a-composed) true)
        _svc-b (make-service-row! storage (:id b-composed) true)
        c (test-ctx storage)
        running (atom {})
        ;; Fake reverse-dep index: edits to `dep-fn-id` cascade only
        ;; to A. Empty for everything else.
        dep-fn-id (random-uuid)]
    (try
      (br/clear-active-router!)
      ;; Initial reconcile — both running.
      (recon/reconcile-once! c running)
      (is (= 2 (count @running)))
      (is (= 1 (count @calls-a)) "A started once")
      (is (= 1 (count @calls-b)) "B started once")

      ;; Prime the compile-deps reverse index — `dep-fn-id` → #{A}.
      ;; `transitive-blast` includes the seed itself in the blast,
      ;; but A is the only RUNNING service whose fn-id matches the
      ;; closure — B is not in any blast we'll hit.
      (reset! (:compile-deps c) {dep-fn-id #{(:id a-composed)}})

      ;; Hit the restart hook with seeds = #{dep-fn-id}. Expected:
      ;; A stops + restarts, B untouched.
      (recon/restart-services-depending-on! c running #{dep-fn-id})

      (testing "A's stopper fired exactly once"
        (is (= [{:suffix "restart-dep-a"}] @stops-a)))
      (testing "B's stopper NOT fired"
        (is (= [] @stops-b)))
      (testing "A's impl re-invoked (post-restart reconcile)"
        (is (= 2 (count @calls-a))
            "A started twice: initial + post-restart"))
      (testing "B's impl unchanged"
        (is (= 1 (count @calls-b))))
      (testing "running map still tracks both"
        (is (= 2 (count @running))))
      (finally (sp/close storage) (br/clear-active-router!)))))


(deftest restart-services-depending-on-noop-when-deps-cold-test
  ;; Cold start path: `:compile-deps` is nil (registry not built yet)
  ;; — the hook returns the canonical empty diff without touching
  ;; the running atom.
  (let [storage (create-full-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed}
        (make-trackable-fn! storage "restart-dep-cold" calls stops)
        _svc (make-service-row! storage (:id composed) true)
        c (test-ctx storage)
        running (atom {})]
    (try
      (br/clear-active-router!)
      (recon/reconcile-once! c running)
      (is (= 1 (count @running)))
      ;; Reconcile-once! lazily warms `:compile-deps`; force cold to
      ;; exercise the no-op path. Real cold-start is before any
      ;; compile happens — same atom shape.
      (reset! (:compile-deps c) nil)
      (is (nil? @(:compile-deps c)) "deps forced cold for this branch")

      (let [r (recon/restart-services-depending-on!
                c running #{(random-uuid)})]
        (testing "returns empty diff on cold deps"
          (is (= {:started [] :stopped [] :not-our-lock []} r)))
        (testing "running atom untouched"
          (is (= 1 (count @running))))
        (testing "stopper NOT fired"
          (is (= [] @stops))))
      (finally (sp/close storage) (br/clear-active-router!)))))


(deftest restart-services-depending-on-empty-seeds-noop-test
  ;; Empty seed set — even with deps populated, no work to do.
  (let [storage (create-full-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed}
        (make-trackable-fn! storage "restart-dep-empty" calls stops)
        _svc (make-service-row! storage (:id composed) true)
        c (test-ctx storage)
        running (atom {})]
    (try
      (br/clear-active-router!)
      (recon/reconcile-once! c running)
      (reset! (:compile-deps c) {(random-uuid) #{(:id composed)}})

      (let [r (recon/restart-services-depending-on! c running #{})]
        (testing "empty seeds → empty diff"
          (is (= {:started [] :stopped [] :not-our-lock []} r)))
        (testing "stopper NOT fired"
          (is (= [] @stops))))
      (finally (sp/close storage) (br/clear-active-router!)))))


(deftest restart-on-edit-end-to-end-with-real-compile-deps-test
  ;; End-to-end coverage of the Phase-112 restart-on-dependency-edit
  ;; chain WITHOUT a fake compile-deps index. Earlier the sibling
  ;; tests primed `(:compile-deps c)` by hand; that verified the hook
  ;; but masked the fact that the production code resolved
  ;; `transitive-blast` from the wrong namespace and silently no-op'd
  ;; until the user-visible Test 1 caught it. This test exercises the
  ;; REAL `compile-runtime/rebuild!` → `build-reverse-deps` →
  ;; `restart-services-depending-on!` chain so a regression in any
  ;; link shows up here.
  (let [storage (create-full-storage)
        up-calls (atom [])
        up-stops (atom [])
        down-calls (atom [])
        down-stops (atom [])
        ;; UPSTREAM — own base-fn + composed fn-def, own tracker.
        {up-composed :composed}
        (make-trackable-fn! storage "rod-up" up-calls up-stops)
        ;; DOWNSTREAM — own base-fn + composed fn-def, own tracker.
        ;; To make `forward-deps-of` emit an edge into UPSTREAM, the
        ;; composed fn-def lists `[down-base, up-composed]` as its
        ;; parent-ids (multi-inheritance). The down-base contributes
        ;; the IMPL; up-composed contributes the dep edge that the
        ;; reverse-dep index will invert into
        ;; `up-composed → #{down-composed}` — which is exactly what
        ;; the restart-on-edit hook needs.
        {down-composed :composed}
        (let [base-name "test-trackable-rod-down"
              composed-name "my-test-trackable-rod-down"
              impl-fn (fn [_args _ctx]
                        (swap! down-calls conj {:suffix "rod-down"})
                        (fn [] (swap! down-stops conj {:suffix "rod-down"})))
              _ (exec/register-base-fn! (keyword base-name) impl-fn)
              base (setup/create-base-fn! storage base-name :any)
              composed (sp/create-entity storage :fn
                                         {:name composed-name
                                          :parent-ids [(:id base)
                                                       (:id up-composed)]})]
          {:base base :composed composed})
        _svc-up (make-service-row! storage (:id up-composed) true)
        _svc-down (make-service-row! storage (:id down-composed) true)
        c (test-ctx storage)
        running (atom {})]
    (try
      (br/clear-active-router!)

      ;; Phase 1 — initial reconcile starts both services.
      (recon/reconcile-once! c running)
      (is (= 2 (count @running)))
      (is (= 1 (count @up-calls)) "upstream started once")
      (is (= 1 (count @down-calls)) "downstream started once")

      ;; Phase 2 — populate :compile-deps via the REAL rebuild path,
      ;; not a hand-rolled `(reset! ... {})`. This reads the storage
      ;; graph, runs `deps/build-reverse-deps` over it, and primes
      ;; the atom — the same code the production runtime exercises.
      (cr-runtime/rebuild! c)
      (let [rev-deps @(:compile-deps c)]
        (is (some? rev-deps)
            ":compile-deps populated by rebuild!")
        (is (contains? (get rev-deps (:id up-composed) #{})
                       (:id down-composed))
            (str "reverse-dep edge upstream → downstream present "
                 "in the real index — without it, edits to upstream "
                 "wouldn't reach the downstream service. Index: "
                 (pr-str (get rev-deps (:id up-composed))))))

      ;; Phase 3 — call the hook with seed = upstream's id. This
      ;; should cascade through the real reverse-dep index, stop the
      ;; downstream service (which depends on upstream), and
      ;; reconcile to restart it.
      (recon/restart-services-depending-on! c running #{(:id up-composed)})

      (testing "upstream stopped + restarted (it IS in the blast — seeds included)"
        (is (= [{:suffix "rod-up"}] @up-stops))
        (is (= 2 (count @up-calls))))
      (testing "downstream cascaded — stopped + restarted via reverse-dep"
        (is (= [{:suffix "rod-down"}] @down-stops))
        (is (= 2 (count @down-calls))))
      (testing "both services still tracked"
        (is (= 2 (count @running))))
      (finally (sp/close storage) (br/clear-active-router!)))))

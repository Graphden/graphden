(ns graphden.services.reconciler-test
  "Runs in the parallel pool: every former `with-redefs` root-rebind
   now goes through a per-thread binding-able seam —
   `pg-lock/*impl-override*`, `br/*ctx-for-override*` and
   `vres/*collect-branch-chain-override*` — so nothing here mutates
   process-global state.

   Tests for `graphden.services.reconciler` — the diff/start/stop
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
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.schema.services.schema :as svcs]
    [graphden.services.reconciler :as recon]
    [graphden.storage.postgres.advisory-lock :as pg-lock]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.tenancy.context :as tctx]
    [graphden.versioning.storage.resolution :as vres]))


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


;; ============================================================================
;; Pure: diff-desired
;; ============================================================================

(deftest service-in-shard?-keeps-tenant-services-on-their-own-shard-test
  (testing "a PLATFORM service (no :org-id) runs on every pod — any shard"
    (is (true? (recon/service-in-shard? nil {:id 1})))
    (is (true? (recon/service-in-shard? #{"public" "acme"} {:id 1})))
    (is (true? (recon/service-in-shard? (fn [_] false) {:id 1}))))
  (testing "a TENANT service runs ONLY where the shard explicitly names its org"
    ;; nil shard = compile-all shared pod → must NOT run a tenant service
    (is (false? (recon/service-in-shard? nil {:id 2 :org-id "acme"})))
    ;; a set shard that names the org → runs
    (is (true? (recon/service-in-shard? #{"public" "acme"} {:id 2 :org-id "acme"})))
    ;; a set shard that does NOT name the org → does not run
    (is (false? (recon/service-in-shard? #{"public" "beta"} {:id 2 :org-id "acme"})))
    ;; a hash-shard predicate fn is honoured
    (is (true? (recon/service-in-shard? #(= "acme" %) {:id 2 :org-id "acme"})))
    (is (false? (recon/service-in-shard? #(= "beta" %) {:id 2 :org-id "acme"})))))


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
  ;; Audit-3: seeds carry `:cardinality` like the production seeder
  ;; ALWAYS does (system/core.clj) — the old omission drove every
  ;; reconcile-loop test through the nil-cardinality fallback, a path
  ;; prod rows never take.
  ([storage fn-id enabled?]
   (make-service-row! storage fn-id enabled? nil :singleton))
  ([storage fn-id enabled? branch-id]
   (make-service-row! storage fn-id enabled? branch-id :singleton))
  ([storage fn-id enabled? branch-id cardinality]
   (sp/create-entity storage :service
                     (cond-> {:fn-id fn-id
                              :enabled? enabled?
                              :restart-policy :always
                              :cardinality cardinality}
                       branch-id (assoc :branch-id branch-id)))))


(deftest reconcile-once-respects-the-org-shard-test
  ;; The dedicated-shard contract END-TO-END (not just the predicate): a
  ;; TENANT service starts only on a pod whose :executor-orgs shard names
  ;; its org — a compile-all (nil-shard) pod and a foreign shard must both
  ;; leave it alone (docs/FLEET_DEPLOY.md § Dedicated tenant shard).
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed}
        (make-trackable-fn! storage "sharded" calls stops)
        svc (make-service-row! storage (:id composed) true)
        _ (sp/update-entity storage :service (:id svc) {:org-id "acme"})]
    (try
      (testing "a nil-shard (compile-all shared) pod never starts a tenant service"
        (let [c (setup/default-registry-ctx storage)
              running (atom {})
              r (recon/reconcile-once! c running)]
          (is (= [] (:started r)))
          (is (empty? @calls))))
      (testing "a foreign shard leaves it alone too"
        (let [c (assoc (setup/default-registry-ctx storage) :executor-orgs #{"public" "beta"})
              running (atom {})
              r (recon/reconcile-once! c running)]
          (is (= [] (:started r)))
          (is (empty? @calls))))
      (testing "the org's own dedicated shard starts it"
        (let [c (assoc (setup/default-registry-ctx storage) :executor-orgs #{"public" "acme"})
              running (atom {})
              r (recon/reconcile-once! c running)]
          (is (= [(:id svc)] (:started r)))
          (is (= [{:suffix "sharded"}] @calls))
          ;; stop it so the storage teardown isn't racing a live stopper
          (doseq [{:keys [stopper]} (vals @running)] (stopper))))
      (finally (sp/close storage)))))


(deftest reconcile-once-starts-enabled-services-test
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed}
        (make-trackable-fn! storage "start" calls stops)
        svc (make-service-row! storage (:id composed) true)
        c (setup/default-registry-ctx storage)
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
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed}
        (make-trackable-fn! storage "stop-disabled" calls stops)
        svc (make-service-row! storage (:id composed) true)
        c (setup/default-registry-ctx storage)
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


(deftest reconcile-pass-pins-the-platform-org-test
  ;; Regression for the 2026-08-05 prod outage window: the edge-triggered
  ;; pass fires from CRUD writes on an abort-shield thread that CONVEYS
  ;; the requester's `*current-org*`. Under a tenant binding the
  ;; OrgScoped `:service` read returns [] (`:service` is
  ;; tenant-forbidden), so desired = ∅ and the pass stopped EVERY running
  ;; service — a demo org's fn create shut down the platform web-server
  ;; until the next periodic tick. The pass must pin the platform org:
  ;; asserted here through the started fn's own view of `current-org`,
  ;; and through the pass not stopping anything a tenant binding can't
  ;; see. (The [] read itself is tenancy-repo behaviour; its end-to-end
  ;; twin lives there.)
  (let [storage (setup/create-branch-versioned-test-storage)
        seen-org (atom nil)
        base-name "test-org-observer"
        _ (exec/register-base-fn! (keyword base-name)
                                  (fn [_args _ctx]
                                    (reset! seen-org (tctx/current-org))
                                    (fn [])))
        base (setup/create-base-fn! storage base-name :any)
        composed (setup/create-composed-fn! storage "my-test-org-observer" (:id base))
        svc (make-service-row! storage (:id composed) true)
        c (setup/default-registry-ctx storage)
        running (atom {})]
    (try
      (tctx/with-org "acme"
                     (let [r (recon/reconcile-once! c running)]
                       (testing "the pass starts the service even under a tenant caller binding"
                         (is (= [(:id svc)] (:started r)))
                         (is (= [] (:stopped r))))))
      (testing "the pass (and the service start) ran platform-bound"
        (is (= tctx/public-org @seen-org)))
      (tctx/with-org "acme"
                     (let [r (recon/reconcile-once! c running)]
                       (testing "a repeat pass under a tenant binding stops NOTHING"
                         (is (= [] (:stopped r)))
                         (is (= 1 (count @running))))))
      (finally (sp/close storage)))))


(deftest reconcile-once-idempotent-when-running-matches-desired-test
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed}
        (make-trackable-fn! storage "idem" calls stops)]
    (make-service-row! storage (:id composed) true)
    (let [c (setup/default-registry-ctx storage)
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


(deftest reconcile-restarts-on-fn-id-drift-test
  ;; Editing a RUNNING service's :fn-id must restart it. The membership
  ;; diff alone treats the row as unchanged (still enabled + running) and
  ;; would silently ignore the edit until a pod restart.
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {a :composed} (make-trackable-fn! storage "drift-a" calls stops)
        {b :composed} (make-trackable-fn! storage "drift-b" calls stops)
        svc (make-service-row! storage (:id a) true)
        c (setup/default-registry-ctx storage)
        running (atom {})]
    (try
      (recon/reconcile-once! c running)
      (is (= [{:suffix "drift-a"}] @calls) "started on fn A")
      (is (= (:id a) (-> @running vals first :fn-id)))
      ;; Repoint the running service at fn B.
      (sp/update-entity storage :service (:id svc) {:fn-id (:id b)})
      (let [r (recon/reconcile-once! c running)]
        (testing "the drifted service is stopped AND restarted"
          (is (= [(:id svc)] (:stopped r)))
          (is (= [(:id svc)] (:started r))))
        (testing "fn A's stopper ran; fn B's impl now runs"
          (is (= [{:suffix "drift-a"}] @stops) "old instance stopped")
          (is (= [{:suffix "drift-a"} {:suffix "drift-b"}] @calls)
              "new instance started on B"))
        (testing "running entry now points at fn B"
          (is (= (:id b) (-> @running vals first :fn-id)))))
      (finally (sp/close storage)))))


(deftest stop-all-drains-running-test
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        ;; Two DIFFERENT fns (different suffixes / impls) — the model
        ;; says each "deployment" = its own fn-def, so simulating
        ;; "two running services" means two fns + two services.
        {a :composed} (make-trackable-fn! storage "drain-a" calls stops)
        {b :composed} (make-trackable-fn! storage "drain-b" calls stops)]
    (make-service-row! storage (:id a) true)
    (make-service-row! storage (:id b) true)
    (let [c (setup/default-registry-ctx storage)
          running (atom {})]
      (try
        (recon/reconcile-once! c running)
        (is (= 2 (count @running)))
        (recon/stop-all! running)
        (testing "all stoppers called, running cleared"
          (is (zero? (count @running)))
          (is (= #{"drain-a" "drain-b"} (set (map :suffix @stops)))))
        (finally (sp/close storage))))))


(deftest stop-all-serializes-with-reconcile-monitor-test
  ;; L3: stop-all! must hold reconcile-monitor across its drain + reset so a
  ;; reconcile pass still in flight past halt's 5s awaitTermination cap can't
  ;; `swap! running assoc` a just-started service back in AFTER the reset! —
  ;; a leaked running service nothing would stop. Prove the serialization:
  ;; while THIS thread holds reconcile-monitor, a stop-all! on another thread
  ;; blocks, and completes only once we release.
  (let [running (atom {(random-uuid) {:stopper (fn [])}})
        done (promise)
        ;; The real monitor `stop-all!` and every reconcile pass serialize on.
        ;; Bound to a local so splint doesn't trace it back to its `(Object.)`
        ;; def and mis-fire `lint/locking-object` — locking the shared monitor
        ;; is exactly the point of this test.
        monitor @#'recon/reconcile-monitor]
    (locking monitor
      (future (recon/stop-all! running) (deliver done :done))
      (is (= :still-blocked (deref done 300 :still-blocked))
          "stop-all! blocks while reconcile-monitor is held elsewhere")
      (is (seq @running) "running not yet drained"))
    (is (= :done (deref done 2000 :timeout))
        "stop-all! completes after the monitor is released")
    (is (empty? @running) "running drained")))


;; ============================================================================
;; Generic CRUD smoke — :service is a regular entity, the standard
;; storage protocol should handle it without per-type machinery. If
;; this breaks, the admin's "POST /api/entities/service" workflow
;; (Phase 1 has no /api/services CRUD endpoints) would silently break.
;; ============================================================================

(deftest service-roundtrips-through-generic-crud-test
  (let [storage (setup/create-branch-versioned-test-storage)
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
  (let [storage (setup/create-branch-versioned-test-storage)
        ;; A base-fn with one declared but unbound slot — composed
        ;; instance inherits the slot as a free arg.
        base-name "test-needs-arg"
        composed-name "my-test-needs-arg"
        _impl (exec/register-base-fn! (keyword base-name) (fn [_ _] :ok))
        base (setup/create-base-fn! storage base-name :any)
        port-slot (setup/create-slot! storage "port" :int)
        _ (setup/attach-slot! storage (:id base) (:id port-slot) 0)
        composed (setup/create-composed-fn! storage composed-name (:id base))
        c (setup/default-registry-ctx storage)
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
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed}
        (make-trackable-fn! storage "branch-record" calls stops)
        feat-id (random-uuid)
        svc (make-service-row! storage (:id composed) true feat-id)
        c (setup/default-registry-ctx storage)
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
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed}
        (make-trackable-fn! storage "no-router" calls stops)
        _ (make-service-row! storage (:id composed) true (random-uuid))
        c (setup/default-registry-ctx storage)
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
  (let [storage (setup/create-branch-versioned-test-storage)
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
        c (setup/default-registry-ctx storage)
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


(deftest nil-branch-row-normalized-to-default-branch-test
  ;; A legacy `:service` row with no `:branch-id` runs on the router's
  ;; default branch. The running entry must record that EFFECTIVE id,
  ;; so a default-branch `restart-services-on-branch!` (post-merge
  ;; invalidation) restarts it instead of leaving a stale closure
  ;; running — before normalization such rows were silently skipped.
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed}
        (make-trackable-fn! storage "legacy-nil-branch" calls stops)
        _svc (make-service-row! storage (:id composed) true nil)
        default-id (random-uuid)
        c (setup/default-registry-ctx storage)
        running (atom {})]
    (try
      ;; Minimal "router": the reconciler only reads `:default-branch-id`
      ;; off it; `br/ctx-for` is stubbed to the base ctx — which is what
      ;; the real router's seeded default-branch entry resolves to anyway.
      (br/set-active-router! {:default-branch-id default-id})
      (binding [br/*ctx-for-override* (fn [_router branch-id]
                                        (is (= default-id branch-id)
                                            "nil-branch row resolves to the default branch ctx")
                                        c)]
        (recon/reconcile-once! c running)
        (testing "running entry records the default branch id"
          (is (= default-id (-> @running vals first :branch-id))))
        (testing "default-branch restart picks the legacy row up"
          (recon/restart-services-on-branch! c running default-id)
          (is (= 1 (count @stops)) "legacy row was stopped for restart")
          (is (= 2 (count @calls)) "initial + post-restart")))
      (finally
        (br/clear-active-router!)
        (sp/close storage)))))


(deftest start-failure-marks-transient-and-reconverges-test
  ;; S1/S2 regression: a failed start must NOT record a give-up entry
  ;; that diff-desired treats as running forever (which stalled
  ;; reconvergence and, for a lock-gated service, held the advisory
  ;; slot with no sibling failover). It marks the transient
  ;; `::start-failed`, which the next tick drops and RE-ATTEMPTS.
  (let [storage (setup/create-branch-versioned-test-storage)
        base-name "test-failing-svc"
        composed-name "my-test-failing-svc"
        attempts (atom 0)
        fail? (atom true)
        impl-fn (fn [_args _ctx]
                  (swap! attempts inc)
                  (if @fail?
                    (throw (ex-info "port in use" {:type :test/bind-err}))
                    (fn [] :stopped)))]
    (exec/register-base-fn! (keyword base-name) impl-fn)
    (let [base (setup/create-base-fn! storage base-name :any)
          composed (setup/create-composed-fn! storage composed-name (:id base))
          svc (sp/create-entity storage :service
                                {:fn-id (:id composed)
                                 :enabled? true
                                 :restart-policy :never})
          c (setup/default-registry-ctx storage)
          running (atom {})]
      (try
        (recon/reconcile-once! c running {:max-retries 0 :backoff-ms 0})
        (testing "a failed start is a transient ::start-failed placeholder, not a give-up map"
          (is (= :graphden.services.reconciler/start-failed (get @running (:id svc))))
          (is (= 1 @attempts)))
        (testing "the next tick re-attempts (reconvergence) — and succeeds once startable"
          (reset! fail? false)
          (recon/reconcile-once! c running {:max-retries 0 :backoff-ms 0})
          (is (= 2 @attempts) "the failed service was retried, not left for dead")
          (is (map? (get @running (:id svc))) "it is now a live running entry")
          (is (some? (-> @running (get (:id svc)) :stopper))))
        (finally (sp/close storage))))))


;; ============================================================================
;; Supervisor — `:restart-policy :always` / `:on-failure` retries start
;; on exception (bounded). `:never` gives up after first attempt.
;; ============================================================================

(deftest supervisor-retries-on-start-failure-test
  (let [storage (setup/create-branch-versioned-test-storage)
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
          c (setup/default-registry-ctx storage)
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
    (let [storage (setup/create-branch-versioned-test-storage)
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
            c (setup/default-registry-ctx storage)
            running (atom {})]
        (try
          (recon/reconcile-once! c running {:max-retries 99 :backoff-ms 0})
          (testing "exactly one call — max-retries ignored for :never"
            (is (= 1 @attempt-counter)))
          (testing "the failed start is the transient ::start-failed placeholder"
            (is (= :graphden.services.reconciler/start-failed (get @running (:id svc)))))
          (finally (sp/close storage)))))))


(deftest supervisor-exhausts-retries-and-gives-up-test
  (testing "permanently-failing :always service → bounded attempts, then give up"
    (let [storage (setup/create-branch-versioned-test-storage)
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
            c (setup/default-registry-ctx storage)
            running (atom {})]
        (try
          (recon/reconcile-once! c running {:max-retries 3 :backoff-ms 0})
          (testing "exactly 1 + max-retries = 4 attempts within one pass before giving up"
            (is (= 4 @attempt-counter)))
          (testing "the failed start is the transient ::start-failed placeholder
                    (S1/S2: no held slot, reconverges next tick — not a
                    give-up map treated as running forever)"
            (is (= :graphden.services.reconciler/start-failed (get @running (:id svc)))))
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
  (let [storage (setup/create-branch-versioned-test-storage)
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
        c (setup/default-registry-ctx storage)
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
      ;; New `:compile-deps` shape carries forward + reverse together.
      ;; Tests only use the reverse side; forward-deps stays empty.
      (reset! (:compile-deps c)
              {:reverse-deps {dep-fn-id #{(:id a-composed)}}
               :forward-deps {}})

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
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed}
        (make-trackable-fn! storage "restart-dep-cold" calls stops)
        _svc (make-service-row! storage (:id composed) true)
        c (setup/default-registry-ctx storage)
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
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed}
        (make-trackable-fn! storage "restart-dep-empty" calls stops)
        _svc (make-service-row! storage (:id composed) true)
        c (setup/default-registry-ctx storage)
        running (atom {})]
    (try
      (br/clear-active-router!)
      (recon/reconcile-once! c running)
      (reset! (:compile-deps c)
              {:reverse-deps {(random-uuid) #{(:id composed)}}
               :forward-deps {}})

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
  (let [storage (setup/create-branch-versioned-test-storage)
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
        c (setup/default-registry-ctx storage)
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
      ;; graph, runs `deps/build-deps-state` over it, and primes the
      ;; atom — the same code the production runtime exercises.
      ;; New shape: `{:reverse-deps {} :forward-deps {}}`.
      (cr-runtime/rebuild! c)
      (let [deps-state @(:compile-deps c)
            rev-deps (:reverse-deps deps-state)]
        (is (some? rev-deps)
            ":compile-deps :reverse-deps populated by rebuild!")
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


(deftest nil-returning-service-is-a-success-not-a-failure-test
  (testing "a fire-and-forget service whose fn returns nil starts OK (nil is a
            valid stopper) — it must NOT be retried or stamped start-failed"
    (let [storage (setup/create-branch-versioned-test-storage)
          base-name "test-fireforget-svc"
          composed-name "my-test-fireforget-svc"
          ;; returns nil — a legitimate fire-and-forget service (no stopper)
          impl-fn (fn [_args _ctx] nil)]
      (exec/register-base-fn! (keyword base-name) impl-fn)
      (let [base (setup/create-base-fn! storage base-name :any)
            composed (setup/create-composed-fn! storage composed-name (:id base))
            svc (sp/create-entity storage :service
                                  {:fn-id (:id composed)
                                   :enabled? true
                                   :restart-policy :on-failure})
            c (setup/default-registry-ctx storage)
            running (atom {})]
        (try
          (recon/reconcile-once! c running {:max-retries 3 :backoff-ms 0})
          (let [entry (get @running (:id svc))]
            (is (= 1 (count @running)) "service tracked")
            (is (nil? (:stopper entry)) "nil stopper — the fn's fire-and-forget return")
            (is (nil? (:start-failed-at entry)) "NOT a failure — no give-up stamp")
            (is (= 1 (:start-attempts entry)) "succeeded on the first attempt, no retries"))
          (finally
            (recon/stop-all! running)
            (sp/close storage)))))))


;; ============================================================================
;; Cardinality — how many pods run a service at once
;; ============================================================================

(deftest service-cardinality-defaults-to-singleton-test
  (testing "a row written before the field existed keeps lock-gated behaviour"
    (is (= :singleton (svcs/service-cardinality {})))
    (is (= :singleton (svcs/service-cardinality {:cardinality nil})))
    (is (svcs/singleton? {})))
  (testing "an explicit value wins"
    (is (= :per-pod (svcs/service-cardinality {:cardinality :per-pod})))
    (is (not (svcs/singleton? {:cardinality :per-pod}))))
  (testing "effective-pool-size resolves the advisory-lock slot count"
    (is (= 1 (svcs/effective-pool-size {})) "legacy row → singleton → 1 slot")
    (is (= 1 (svcs/effective-pool-size {:cardinality :singleton})))
    (is (nil? (svcs/effective-pool-size {:cardinality :per-pod})) "per-pod → no lock")
    (is (= 3 (svcs/effective-pool-size {:cardinality :pool :pool-size 3})))
    (is (= 1 (svcs/effective-pool-size {:cardinality :pool :pool-size nil}))
        "a :pool with no size degrades to a singleton, not a fan-out")
    (is (= 1 (svcs/effective-pool-size {:cardinality :pool :pool-size 0}))
        "non-positive size degrades to 1"))
  (testing "lock-gated? is true for singleton + pool, false for per-pod"
    (is (svcs/lock-gated? {:cardinality :singleton}))
    (is (svcs/lock-gated? {:cardinality :pool :pool-size 2}))
    (is (not (svcs/lock-gated? {:cardinality :per-pod})))))


(defn- fake-lock-table
  "Model the cluster-wide advisory-lock SLOT table: `try-acquire-slot!`
   succeeds for whoever asks for a given `(service-id, slot)` first,
   `release-slot!` frees it. Returns `[held-atom acquire-fn release-fn]`
   where `held-atom` holds the set of taken `[service-id slot]` keys
   (a singleton uses slot 0, a pool uses 0..N-1)."
  []
  (let [held (atom #{})]
    [held
     (fn [_conn sid slot]
       (let [k [sid slot]]
         (not (contains? (first (swap-vals! held conj k)) k))))
     (fn [_conn sid slot] (swap! held disj [sid slot]) true)]))


(defn- pod-ctx
  "A ctx that looks like it has a lock connection, so the reconciler
   takes the multi-pod code path instead of the nil-conn fallback."
  [storage]
  (assoc (setup/default-registry-ctx storage) :service-locks-connection ::fake-conn))


(deftest singleton-service-runs-on-exactly-one-pod-test
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed} (make-trackable-fn! storage "singleton" calls stops)
        svc (sp/create-entity storage :service
                              {:fn-id (:id composed)
                               :enabled? true
                               :restart-policy :always
                               :cardinality :singleton})
        [_held try-lock release] (fake-lock-table)
        pod-a (atom {})
        pod-b (atom {})]
    (try
      (binding [pg-lock/*impl-override* {:try-acquire-slot! try-lock
                                         :release-slot! release}]
        (let [ra (recon/reconcile-once! (pod-ctx storage) pod-a)
              rb (recon/reconcile-once! (pod-ctx storage) pod-b)]
          (testing "pod A wins the lock and starts it"
            (is (= [(:id svc)] (:started ra)))
            (is (= [] (:not-our-lock ra)))
            (is (true? (:locked? (get @pod-a (:id svc))))))
          (testing "pod B loses and idles"
            (is (= [] (:started rb)))
            (is (= [(:id svc)] (:not-our-lock rb)))
            (is (= ::recon/not-our-lock (get @pod-b (:id svc)))))
          (testing "the fn ran exactly once across the cluster"
            (is (= 1 (count @calls))))))
      (finally
        (recon/stop-all! pod-a)
        (sp/close storage)))))


(deftest per-pod-service-runs-on-every-pod-test
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed} (make-trackable-fn! storage "per-pod" calls stops)
        svc (sp/create-entity storage :service
                              {:fn-id (:id composed)
                               :enabled? true
                               :restart-policy :always
                               :cardinality :per-pod})
        [held try-lock release] (fake-lock-table)
        pod-a (atom {})
        pod-b (atom {})]
    (try
      (binding [pg-lock/*impl-override* {:try-acquire-slot! try-lock
                                         :release-slot! release}]
        (let [ra (recon/reconcile-once! (pod-ctx storage) pod-a)
              rb (recon/reconcile-once! (pod-ctx storage) pod-b)]
          (testing "both pods start their own copy — a listener must bind on each"
            (is (= [(:id svc)] (:started ra)))
            (is (= [(:id svc)] (:started rb)))
            (is (= [] (:not-our-lock rb))))
          (testing "the fn ran once per pod"
            (is (= 2 (count @calls))))
          (testing "no advisory lock was taken"
            (is (= #{} @held))
            (is (false? (:locked? (get @pod-a (:id svc))))))))
      (finally
        (recon/stop-all! pod-a)
        (recon/stop-all! pod-b)
        (sp/close storage)))))


(deftest stop-releases-only-locks-this-pod-holds-test
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed} (make-trackable-fn! storage "release" calls stops)
        svc (sp/create-entity storage :service
                              {:fn-id (:id composed)
                               :enabled? true
                               :restart-policy :always
                               :cardinality :per-pod})
        [_held try-lock] (fake-lock-table)
        released (atom [])
        pod (atom {})]
    (try
      (binding [pg-lock/*impl-override*
                {:try-acquire-slot! try-lock
                 :release-slot!
                 (fn [_conn sid _slot] (swap! released conj sid) true)}]
        (recon/reconcile-once! (pod-ctx storage) pod)
        (sp/update-entity storage :service (:id svc) {:enabled? false})
        (recon/reconcile-once! (pod-ctx storage) pod)
        (testing "a :per-pod service never locked, so stop must not unlock"
          (is (= [] @released))
          (is (= {} @pod))))
      (finally (sp/close storage)))))


(deftest cardinality-flip-restarts-the-service-test
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed} (make-trackable-fn! storage "flip" calls stops)
        svc (sp/create-entity storage :service
                              {:fn-id (:id composed)
                               :enabled? true
                               :restart-policy :always
                               :cardinality :singleton})
        [_held try-lock release] (fake-lock-table)
        pod (atom {})]
    (try
      (binding [pg-lock/*impl-override* {:try-acquire-slot! try-lock
                                         :release-slot! release}]
        (recon/reconcile-once! (pod-ctx storage) pod)
        (is (true? (:locked? (get @pod (:id svc)))))
        (sp/update-entity storage :service (:id svc) {:cardinality :per-pod})
        (let [r (recon/reconcile-once! (pod-ctx storage) pod)]
          (testing "drift detection sees the flip and restarts"
            (is (= [(:id svc)] (:stopped r)))
            (is (= [(:id svc)] (:started r))))
          (testing "the lock is dropped on the way to :per-pod"
            (is (false? (:locked? (get @pod (:id svc))))))
          (testing "it stopped once and started twice overall"
            (is (= 1 (count @stops)))
            (is (= 2 (count @calls))))))
      (finally
        (recon/stop-all! pod)
        (sp/close storage)))))


(deftest pool-service-runs-on-exactly-N-pods-test
  (testing ":pool with :pool-size 2 runs on 2 of 3 pods, each on a distinct slot"
    (let [storage (setup/create-branch-versioned-test-storage)
          calls (atom [])
          stops (atom [])
          {composed :composed} (make-trackable-fn! storage "pool" calls stops)
          svc (sp/create-entity storage :service
                                {:fn-id (:id composed)
                                 :enabled? true
                                 :restart-policy :always
                                 :cardinality :pool
                                 :pool-size 2})
          [_held try-lock release] (fake-lock-table)
          pods [(atom {}) (atom {}) (atom {})]]
      (try
        (binding [pg-lock/*impl-override* {:try-acquire-slot! try-lock
                                           :release-slot! release}]
          (let [results (mapv #(recon/reconcile-once! (pod-ctx storage) %) pods)
                started (filter #(seq (:started %)) results)
                idle (filter #(seq (:not-our-lock %)) results)
                slots (->> pods
                           (keep #(:pool-slot (get @% (:id svc))))
                           set)]
            (testing "exactly 2 pods started it, 1 idled"
              (is (= 2 (count started)))
              (is (= 1 (count idle))))
            (testing "the fn ran exactly twice cluster-wide"
              (is (= 2 (count @calls))))
            (testing "the two runners hold distinct slots (0 and 1)"
              (is (= #{0 1} slots)))))
        (finally
          (doseq [p pods] (recon/stop-all! p))
          (sp/close storage))))))


(deftest not-our-lock-is-retried-so-a-crashed-owner-fails-over-test
  (testing "an idle pod re-attempts the lock each pass; when the owner's slot frees, it takes over"
    (let [storage (setup/create-branch-versioned-test-storage)
          calls (atom [])
          stops (atom [])
          {composed :composed} (make-trackable-fn! storage "failover" calls stops)
          svc (sp/create-entity storage :service
                                {:fn-id (:id composed)
                                 :enabled? true
                                 :restart-policy :always
                                 :cardinality :singleton})
          [_held try-lock release] (fake-lock-table)
          pod-a (atom {})
          pod-b (atom {})]
      (try
        (binding [pg-lock/*impl-override* {:try-acquire-slot! try-lock
                                           :release-slot! release}]
          (recon/reconcile-once! (pod-ctx storage) pod-a)
          (recon/reconcile-once! (pod-ctx storage) pod-b)
          (testing "pod A owns it, pod B idles (::not-our-lock)"
            (is (true? (:locked? (get @pod-a (:id svc)))))
            (is (= ::recon/not-our-lock (get @pod-b (:id svc))))
            (is (= 1 (count @calls))))
          ;; Simulate pod A crashing: its session ends, freeing slot 0.
          (release ::conn (:id svc) 0)
          ;; pod B's next pass drops its stale ::not-our-lock and re-attempts.
          (recon/reconcile-once! (pod-ctx storage) pod-b)
          (testing "pod B took over the freed slot on its next reconcile"
            (is (true? (:locked? (get @pod-b (:id svc)))))
            (is (= 2 (count @calls)) "the fn now runs on pod B too")))
        (finally
          (recon/stop-all! pod-b)
          (sp/close storage))))))


(deftest pool-shrink-restarts-out-of-range-slot-test
  (testing "shrinking :pool-size drifts the entry so the pod re-evaluates its slot"
    (let [storage (setup/create-branch-versioned-test-storage)
          calls (atom [])
          stops (atom [])
          {composed :composed} (make-trackable-fn! storage "shrink" calls stops)
          svc (sp/create-entity storage :service
                                {:fn-id (:id composed)
                                 :enabled? true
                                 :restart-policy :always
                                 :cardinality :pool
                                 :pool-size 2})
          [_held try-lock release] (fake-lock-table)
          pod (atom {})]
      (try
        (binding [pg-lock/*impl-override* {:try-acquire-slot! try-lock
                                           :release-slot! release}]
          (recon/reconcile-once! (pod-ctx storage) pod)
          (is (= 2 (:pool-size (get @pod (:id svc)))))
          (sp/update-entity storage :service (:id svc) {:pool-size 1})
          (let [r (recon/reconcile-once! (pod-ctx storage) pod)]
            (testing "drift detection catches the pool-size change and restarts"
              (is (= [(:id svc)] (:stopped r)))
              (is (= 1 (:pool-size (get @pod (:id svc))))))))
        (finally
          (recon/stop-all! pod)
          (sp/close storage))))))


;; ============================================================================
;; reassert-lock-ownership! — after a lock-conn reconnect, re-take the locks
;; we held, and stop the ones a sibling grabbed during the outage.
;; ============================================================================

(deftest reassert-keeps-services-we-re-acquire-test
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed} (make-trackable-fn! storage "reassert-keep" calls stops)
        svc (sp/create-entity storage :service
                              {:fn-id (:id composed) :enabled? true
                               :restart-policy :always :cardinality :singleton})
        pod (atom {})]
    (try
      ;; Start under a (fake) lock connection so the entry is :locked? true.
      (binding [pg-lock/*impl-override* {:try-acquire-slot! (fn [_ _ _] true)}]
        (recon/reconcile-once! (pod-ctx storage) pod))
      (is (true? (:locked? (get @pod (:id svc)))))
      ;; Reconnect scenario: re-acquire SUCCEEDS (nobody stole it).
      (binding [pg-lock/*impl-override* {:try-acquire-slot! (fn [_ _ _] true)}]
        (#'recon/reassert-lock-ownership! ::fresh-conn pod))
      (testing "service we re-acquired stays running, not stopped"
        (is (contains? @pod (:id svc)))
        (is (empty? @stops)))
      (finally
        (recon/stop-all! pod)
        (sp/close storage)))))


(deftest reassert-stops-a-service-a-sibling-stole-test
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed} (make-trackable-fn! storage "reassert-yield" calls stops)
        svc (sp/create-entity storage :service
                              {:fn-id (:id composed) :enabled? true
                               :restart-policy :always :cardinality :singleton})
        pod (atom {})]
    (try
      (binding [pg-lock/*impl-override* {:try-acquire-slot! (fn [_ _ _] true)}]
        (recon/reconcile-once! (pod-ctx storage) pod))
      (is (true? (:locked? (get @pod (:id svc)))))
      ;; Reconnect scenario: re-acquire FAILS — a sibling took it during the
      ;; outage. The pod must stop its local copy and yield.
      (binding [pg-lock/*impl-override* {:try-acquire-slot! (fn [_ _ _] false)}]
        (#'recon/reassert-lock-ownership! ::fresh-conn pod))
      (testing "we stopped the local copy and dropped the entry"
        (is (not (contains? @pod (:id svc))))
        (is (= 1 (count @stops)) "the stopper fired exactly once"))
      (finally
        (recon/stop-all! pod)
        (sp/close storage)))))


(deftest reconcile-once!-drives-reassert-after-a-reconnect-test
  ;; The reassert UNIT is tested above; this covers the GLUE in reconcile-once!
  ;; (`:service-locks-holder` → ensure-live! → reassert-lock-ownership!), which
  ;; had no coverage — deleting that call would leave every other test green
  ;; while a reconnected pod double-ran a singleton a sibling stole.
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed} (make-trackable-fn! storage "reassert-glue" calls stops)
        svc (sp/create-entity storage :service
                              {:fn-id (:id composed) :enabled? true
                               :restart-policy :always :cardinality :singleton})
        pod (atom {})
        holder (atom {:conn ::stale})
        ctx (assoc (pod-ctx storage) :service-locks-holder holder)]
    (try
      ;; Pass 1: no reconnect (ensure-live! false) — acquire the lock so the
      ;; entry is :locked? true, exactly as a healthy pass would leave it.
      (binding [pg-lock/*impl-override* {:ensure-live! (fn [_] false)
                                         :try-acquire-slot! (fn [_ _ _] true)}]
        (recon/reconcile-once! ctx pod))
      (is (true? (:locked? (get @pod (:id svc)))))
      ;; Pass 2: the holder reconnected (ensure-live! true) → the glue must
      ;; re-assert on the fresh conn. A sibling grabbed the lock during the
      ;; outage (try-lock! false), so reassert stops our local copy.
      (binding [pg-lock/*impl-override* {:ensure-live! (fn [_] true)
                                         :holder-conn (fn [_] ::fresh)
                                         :try-acquire-slot! (fn [_ _ _] false)}]
        (recon/reconcile-once! ctx pod))
      (testing "the reconnect drove reassert: the stolen singleton was stopped"
        (is (= 1 (count @stops)) "the local copy's stopper fired exactly once")
        (is (not (map? (get @pod (:id svc)))) "no longer a live running entry"))
      (finally
        (recon/stop-all! pod)
        (sp/close storage)))))


(deftest per-pod-service-is-untouched-by-reassert-test
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed} (make-trackable-fn! storage "reassert-perpod" calls stops)
        svc (sp/create-entity storage :service
                              {:fn-id (:id composed) :enabled? true
                               :restart-policy :always :cardinality :per-pod})
        c (setup/default-registry-ctx storage)
        pod (atom {})]
    (try
      (recon/reconcile-once! c pod)
      (is (false? (:locked? (get @pod (:id svc)))) ":per-pod never locked")
      ;; Even if try-lock! would fail, a :per-pod entry (:locked? false) is
      ;; skipped — it never depended on a lock.
      (binding [pg-lock/*impl-override*
                {:try-acquire-slot! (fn [_ _ _] (throw (ex-info "should not be called" {})))}]
        (#'recon/reassert-lock-ownership! ::fresh-conn pod))
      (testing ":per-pod service stays running, try-lock! never consulted"
        (is (contains? @pod (:id svc)))
        (is (empty? @stops)))
      (finally
        (recon/stop-all! pod)
        (sp/close storage)))))


(deftest restart-depending-on-scoped-to-branches-seeing-the-edit-test
  ;; Audit-5: the same fn-id runs on many branches with different
  ;; version data; a sibling branch whose view didn't change must not
  ;; be churned. The 4-arity hook restarts an entry only when the
  ;; edited branch is on the entry's branch CHAIN (itself or an
  ;; ancestor); the 3-arity stays conservative.
  (let [storage (setup/create-branch-versioned-test-storage)
        calls-same (atom [])
        stops-same (atom [])
        calls-sib (atom [])
        stops-sib (atom [])
        {same-composed :composed}
        (make-trackable-fn! storage "brscope-same" calls-same stops-same)
        {sib-composed :composed}
        (make-trackable-fn! storage "brscope-sib" calls-sib stops-sib)
        edit-branch (random-uuid)
        sibling-branch (random-uuid)
        _svc-same (make-service-row! storage (:id same-composed) true edit-branch)
        _svc-sib (make-service-row! storage (:id sib-composed) true sibling-branch)
        c (setup/default-registry-ctx storage)
        running (atom {})
        dep-fn-id (random-uuid)]
    (try
      (br/clear-active-router!)
      (recon/reconcile-once! c running)
      (is (= 2 (count @running)))
      ;; Blast hits BOTH services' fns.
      (reset! (:compile-deps c)
              {:reverse-deps {dep-fn-id #{(:id same-composed) (:id sib-composed)}}
               :forward-deps {}})
      ;; Chain lookup: the entries' branch rows don't exist in this
      ;; harness, so stub the chain — each branch is its own root.
      (binding [vres/*collect-branch-chain-override* (fn [_base bid] [bid])]
        (recon/restart-services-depending-on! c running #{dep-fn-id} edit-branch))
      (testing "service on the edited branch restarted"
        (is (= 1 (count @stops-same)))
        (is (= 2 (count @calls-same)) "initial + post-restart"))
      (testing "sibling branch untouched — its view didn't change"
        (is (= [] @stops-sib))
        (is (= 1 (count @calls-sib)) "initial only"))
      (finally (br/clear-active-router!) (sp/close storage)))))


;; ---------------------------------------------------------------------------
;; Endpoints — where a started listener answers
;; ---------------------------------------------------------------------------

(defn- make-listener-fn!
  "Like `make-trackable-fn!`, but the stopper carries the `:endpoint`
   metadata a real `:http-server` handle does."
  [storage suffix port stops]
  (let [base-name (str "test-listener-" suffix)
        composed-name (str "my-test-listener-" suffix)
        impl-fn (fn [_args _ctx]
                  (with-meta (fn [] (swap! stops conj {:suffix suffix}))
                    {:endpoint {:port port}}))]
    (exec/register-base-fn! (keyword base-name) impl-fn)
    (let [base (setup/create-base-fn! storage base-name :any)]
      {:base base
       :composed (setup/create-composed-fn! storage composed-name (:id base))})))


(defn- row-endpoint
  [storage svc-id]
  (:endpoint (first (sp/query-entities storage :service {:id svc-id}))))


(deftest reconcile-records-and-clears-the-listener-endpoint-test
  (let [storage (setup/create-branch-versioned-test-storage)
        stops (atom [])
        {composed :composed} (make-listener-fn! storage "ep" 43210 stops)
        svc (make-service-row! storage (:id composed) true)
        c (setup/default-registry-ctx storage)
        running (atom {})]
    (try
      (recon/reconcile-once! c running)
      (testing "the row records host+port; loopback on a single pod"
        (is (= {:host "127.0.0.1" :port 43210} (row-endpoint storage (:id svc))))
        (is (= {:host "127.0.0.1" :port 43210} (:endpoint (get @running (:id svc))))))
      (testing "disabling the row stops the service and clears the endpoint"
        (sp/update-entity storage :service (:id svc) {:enabled? false})
        (recon/reconcile-once! c running)
        (is (= [{:suffix "ep"}] @stops))
        (is (nil? (row-endpoint storage (:id svc)))))
      (finally (sp/close storage)))))


(deftest reconcile-endpoint-host-is-the-pod-executor-id-test
  (let [storage (setup/create-branch-versioned-test-storage)
        stops (atom [])
        {composed :composed} (make-listener-fn! storage "ep-host" 43211 stops)
        svc (make-service-row! storage (:id composed) true)
        c (assoc (setup/default-registry-ctx storage) :executor-id "graphden-1.graphden-headless")
        running (atom {})]
    (try
      (recon/reconcile-once! c running)
      (is (= {:host "graphden-1.graphden-headless" :port 43211}
             (row-endpoint storage (:id svc))))
      (finally (sp/close storage)))))


(deftest reconcile-non-listener-records-no-endpoint-test
  (let [storage (setup/create-branch-versioned-test-storage)
        calls (atom [])
        stops (atom [])
        {composed :composed} (make-trackable-fn! storage "no-ep" calls stops)
        svc (make-service-row! storage (:id composed) true)
        c (setup/default-registry-ctx storage)
        running (atom {})]
    (try
      (recon/reconcile-once! c running)
      (is (nil? (row-endpoint storage (:id svc))))
      (is (not (contains? (get @running (:id svc)) :endpoint)))
      (finally (sp/close storage)))))


(deftest stop-all-with-ctx-clears-endpoints-test
  (let [storage (setup/create-branch-versioned-test-storage)
        stops (atom [])
        {composed :composed} (make-listener-fn! storage "ep-drain" 43212 stops)
        svc (make-service-row! storage (:id composed) true)
        c (setup/default-registry-ctx storage)
        running (atom {})]
    (try
      (recon/reconcile-once! c running)
      (is (some? (row-endpoint storage (:id svc))))
      (recon/stop-all! running c)
      (is (zero? (count @running)))
      (is (nil? (row-endpoint storage (:id svc))))
      (finally (sp/close storage)))))

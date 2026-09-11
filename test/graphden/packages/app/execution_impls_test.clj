(ns ^:serial graphden.packages.app.execution-impls-test
  "Unit tests for the `app/execution` package's impls — the boundary
   behind `/api/execute*`, the Stats / Errors panels, the service
   badge and the Debug «catch next request» trap.

   Most of this module is `:db`-bound and covered by the integration
   suites. What is NOT covered anywhere is the part that runs BEFORE
   (or INSTEAD OF) the database:

   - the pool-discovery fallback every rollup surface reads through,
   - the degraded shapes each surface returns when there is no pool,
   - the cross-org authorization gate on `:usage-all-org-stats`,
   - the nil-id short-circuits that keep a bad URL off storage,
   - the reconciler / trap RUNTIME-state reshapes, which are pure
     transformations of a process atom into the JSON the editor binds.

   ^:serial — `running-state` / `running-entry` and the debug-catch
   trio read PROCESS-GLOBAL defonce atoms (`services.reconciler/running`,
   `crud.debug-capture`'s trap registry) that the impls reach directly,
   so this NS mutates them. Every test restores what it touched in a
   `finally`, and keys everything by a fresh random uuid.

   Every authorization assertion binds `tc/*current-org*`: unbound is
   the PLATFORM tier, where the cross-org gate is a no-op and the test
   would pass vacuously."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.services.reconciler :as recon]
    [graphden.tenancy.context :as tc]
    [graphden.test-infra.impls :as impls]))


(use-fixtures :once (impls/impls-fixture "app" "execution"))


(defn- call
  "Invoke a base-fn impl the way the executor does: args as delays.
   `delay` is a macro, so the map cannot be built with `update-vals`."
  ([kw args] (call kw args {}))
  ([kw args ctx]
   ((impls/impl-of kw)
    (into {} (map (fn [[k v]] [k (delay v)])) args)
    ctx)))


(defn- priv
  [ns-sym sym]
  (let [v (ns-resolve ns-sym sym)]
    (assert v (str "no such var: " sym))
    @v))


;; =============================================================================
;; stats-pool — where every rollup surface finds its datasource
;; =============================================================================

(deftest stats-pool-falls-back-to-the-branch-storage-when-no-pg-storage
  ;; Regression guard for the 2026-08-26 tutorial finding: the
  ;; single-tenant executor ctx carries NO `:pg-storage`, so a
  ;; pg-storage-only lookup left the 7d history strip and the
  ;; Stats/Errors panels reading zeros while the write side happily
  ;; bumped rows. Both lookups must stay, in this order.
  (let [stats-pool (priv 'graphden.packages.app.execution.impls 'stats-pool)]
    (testing "the privileged :pg-storage pool wins when present"
      (is (= ::privileged
             (stats-pool {:pg-storage {:pool ::privileged}
                          :storage {:pool ::branch}}))))
    (testing "a ctx with only :storage still finds a pool (the single-tenant case)"
      (is (= ::branch (stats-pool {:storage {:pool ::branch}}))))
    (testing "a ctx with neither yields nil rather than throwing"
      (is (nil? (stats-pool {}))))))


;; =============================================================================
;; The rollup surfaces degrade instead of throwing when there is no pool
;; =============================================================================

(deftest fn-stats-raw-answers-zeros-not-nil-without-a-pool
  (testing "the four counters are always present and numeric"
    ;; The history strip renders `runs - failed` arithmetic directly on
    ;; this map; a nil from the storage layer must be coerced HERE, at
    ;; the boundary, or the strip throws instead of showing an empty week.
    (is (= {:runs 0 :failed 0 :cancelled 0 :duration-ms-sum 0}
           (call :fn-stats-raw {:fn-id (random-uuid) :days 7})))))


(deftest org-rollups-degrade-to-empty-shapes-without-a-pool
  (testing "the org summary is a zeroed map, not nil"
    (is (= {:runs 0 :failed 0 :duration-ms-sum 0}
           (call :usage-org-summary {:days 7}))))
  (testing "the run audit answers an empty list, never an error"
    ;; `recent-executions` is read by the review dialog on every open;
    ;; a branch with no versioned storage (or no pool) must show
    ;; \"nothing verified here\", not a failed panel.
    (is (= [] (call :recent-executions {:limit 20}))))
  (testing "the dismiss-all counter reports zero rows acknowledged"
    (is (zero? (call :failure-ack-all {:days 7}))))
  (testing "a single dismiss reports false — nothing was acked"
    (is (false? (call :failure-ack {:execution-id (str (random-uuid))})))
    (is (false? (call :failure-ack {:execution-id "not-a-uuid"}))
        "an unparseable id is coerced away, not passed to SQL")))


;; =============================================================================
;; :usage-all-org-stats — the cross-org authorization gate
;; =============================================================================

(deftest cross-org-stats-are-refused-to-a-tenant-context
  (testing "a tenant org with no platform capability gets [] — the gate arm"
    ;; The guard lives impl-side ON PURPOSE so no graph composition can
    ;; compose its way to another org's counts. `[]` (not nil) is what
    ;; proves the guard branch ran: the unguarded call returns nil when
    ;; there is no pool, so the two arms are distinguishable even here.
    (binding [tc/*current-org* "acme"]
      (is (= [] (call :usage-all-org-stats {:days 7 :limit 10})))))
  (testing "the platform tier reaches the unguarded read"
    (binding [tc/*current-org* tc/public-org]
      (is (nil? (call :usage-all-org-stats {:days 7 :limit 10}))))))


;; =============================================================================
;; Nil-id short-circuits — a bad URL never reaches storage
;; =============================================================================

(deftest execution-reads-and-cancels-short-circuit-on-a-missing-id
  (testing "an unparseable /api/execute/:id answers nil without touching storage"
    ;; The graph's dynamic 404 builder keys on this nil. Without the
    ;; `some?` guard both impls would hand nil straight to the storage
    ;; layer on every malformed URL — the ctx here has no storage at
    ;; all, so reaching it would throw.
    (is (nil? (call :get-execution {:id nil})))
    (is (nil? (call :cancel-execution! {:id nil})))))


(deftest resolve-fn-refuses-a-context-without-storage
  (testing "the typed :execution-error/missing-storage, never an NPE"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                  (call :resolve-fn {:parsed {:fn-name "whatever"}})))]
      (is (= :execution-error/missing-storage (:type (ex-data e)))))))


;; =============================================================================
;; :running-state / :running-entry — the reconciler's verdict, JSON-shaped
;; =============================================================================

(defn- with-running
  "Park `entry` under a fresh service id in the process-global running
   atom, run `f` with that id, then remove it again."
  [entry f]
  (let [sid (random-uuid)]
    (try
      (swap! recon/running assoc sid entry)
      (f sid)
      (finally (swap! recon/running dissoc sid)))))


(deftest running-state-names-why-a-service-is-not-running
  ;; Before this reshape all four non-running placeholders reached the
  ;; editor as a bare \"pending\": a service stuck in restart backoff
  ;; looked exactly like one that had never started. The badge and the
  ;; popover read `:state` as a STRING, so `name` must stay.
  (testing "a live copy is running; one that failed to start says so"
    (with-running {:stopper ::handle :started-at 1}
      #(is (= {:state "running" :next-attempt-at nil}
              (call :running-state {:service-id %}))))
    (with-running {:stopper ::handle :start-failed-at 99}
      #(is (= "start-failed"
              (:state (call :running-state {:service-id %}))))))
  (testing "each sentinel keeps its own name rather than collapsing to pending"
    (doseq [[sentinel expected]
            [[:graphden.services.reconciler/backoff "backoff"]
             [:graphden.services.reconciler/exited "exited"]
             [:graphden.services.reconciler/not-our-lock "not-our-lock"]
             [:graphden.services.reconciler/start-failed "start-failed"]]]
      (with-running sentinel
        #(is (= expected (:state (call :running-state {:service-id %})))
             (str sentinel " must not report as pending")))))
  (testing "an unregistered service is pending"
    (is (= {:state "pending" :next-attempt-at nil}
           (call :running-state {:service-id (random-uuid)})))))


(deftest running-state-renders-the-next-attempt-as-text
  (testing "a backoff deadline is stringified, not handed over as an Instant"
    ;; The whole map is JSON-encoded straight into the services
    ;; response; a java.time.Instant would either serialize as an
    ;; opaque object or break the encoder.
    (let [backoff-atom (ns-resolve 'graphden.services.reconciler 'exit-backoff)
          sid (random-uuid)]
      (try
        (swap! recon/running assoc sid :graphden.services.reconciler/backoff)
        (swap! @backoff-atom assoc sid {:until 1700000000000})
        (let [r (call :running-state {:service-id sid})]
          (is (= "backoff" (:state r)))
          (is (string? (:next-attempt-at r)))
          (is (= (str (java.time.Instant/ofEpochMilli 1700000000000))
                 (:next-attempt-at r))))
        (finally
          (swap! recon/running dissoc sid)
          (swap! @backoff-atom dissoc sid))))))


(deftest running-entry-returns-the-raw-entry-for-graph-composition
  (testing "the entry is handed over unreshaped so `:enrich-running` can add fields"
    (let [entry {:stopper ::handle :started-at 42 :start-attempts 3}]
      (with-running entry #(is (= entry (call :running-entry {:service-id %}))))))
  (testing "an unregistered service is nil, not an empty map"
    ;; `:enrich-running` branches on nil to decide whether a service has
    ;; any in-process state at all.
    (is (nil? (call :running-entry {:service-id (random-uuid)})))))


;; =============================================================================
;; /api/debug/catch — the trap envelopes the Debug panel binds to
;; =============================================================================

(deftest debug-catch-threads-every-option-through-to-the-trap
  ;; The shim's only job is to build the options map. A mistyped key
  ;; would silently arm a catch-all trap with the default TTL — the
  ;; user's prefix and capture choice discarded, with no error.
  (binding [tc/*current-org* (str "org-" (random-uuid))]
    (let [branch-id (random-uuid)]
      (try
        (let [trap (call :debug-catch-arm! {:branch-id branch-id
                                            :path-prefix "/shop"
                                            :capture-values? true
                                            :ttl-ms 5000})]
          (is (= "/shop" (:path-prefix trap)))
          (is (true? (:capture-values? trap)))
          (is (= 5000 (- (:expires-at-ms trap) (:armed-at-ms trap)))
              "the requested TTL, not the default, bounds the trap"))
        (finally (call :debug-catch-disarm! {:branch-id branch-id :_request {}}))))))


(deftest debug-catch-status-and-disarm-envelopes
  (binding [tc/*current-org* (str "org-" (random-uuid))]
    (let [branch-id (random-uuid)]
      (try
        (testing "an unarmed branch reports armed=false with an explicit nil trap"
          ;; The panel renders on these three keys; a missing `:armed`
          ;; key reads as \"unknown\" and the arm button never enables.
          (is (= {:armed false :trap nil :last-captured-execution-id nil}
                 (call :debug-catch-status {:branch-id branch-id :_request {}}))))
        (let [trap (call :debug-catch-arm! {:branch-id branch-id
                                            :path-prefix nil
                                            :capture-values? nil
                                            :ttl-ms nil})]
          (testing "a blank prefix arms a catch-all and capture defaults to false"
            (is (nil? (:path-prefix trap)))
            (is (false? (:capture-values? trap))))
          (testing "status carries the armed trap itself"
            (let [st (call :debug-catch-status {:branch-id branch-id :_request {}})]
              (is (true? (:armed st)))
              (is (= trap (:trap st))))))
        (testing "disarm reports true once, then false — the panel's toggle state"
          (is (= {:disarmed true}
                 (call :debug-catch-disarm! {:branch-id branch-id :_request {}})))
          (is (= {:disarmed false}
                 (call :debug-catch-disarm! {:branch-id branch-id :_request {}})))
          (is (false? (:armed (call :debug-catch-status
                                    {:branch-id branch-id :_request {}})))))
        (finally (call :debug-catch-disarm! {:branch-id branch-id :_request {}}))))))


(deftest debug-catch-traps-are-scoped-to-the-arming-org
  (testing "another org's status never sees this org's trap"
    ;; The registry is keyed [org-id branch-id]. If the impls ever
    ;; dropped the org from the key, one tenant's Debug panel would
    ;; disarm — or read — another tenant's captured request.
    (let [branch-id (random-uuid)
          org-a (str "org-" (random-uuid))
          org-b (str "org-" (random-uuid))]
      (try
        (binding [tc/*current-org* org-a]
          (call :debug-catch-arm! {:branch-id branch-id :path-prefix "/a"
                                   :capture-values? false :ttl-ms 5000}))
        (binding [tc/*current-org* org-b]
          (is (false? (:armed (call :debug-catch-status
                                    {:branch-id branch-id :_request {}}))))
          (is (= {:disarmed false}
                 (call :debug-catch-disarm! {:branch-id branch-id :_request {}}))
              "org B cannot disarm org A's trap"))
        (binding [tc/*current-org* org-a]
          (is (true? (:armed (call :debug-catch-status
                                   {:branch-id branch-id :_request {}})))))
        (finally
          (binding [tc/*current-org* org-a]
            (call :debug-catch-disarm! {:branch-id branch-id :_request {}})))))))

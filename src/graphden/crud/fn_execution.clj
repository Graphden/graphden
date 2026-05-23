(ns graphden.crud.fn-execution
  "Parse / validate / apply stages for the `/api/execute` endpoint.

   An execution submits a fn-graph to the executor in a background
   future, returns the result inline if it finishes within
   `:timeout-ms` (default 10s, capped at 60s), else flips to async
   mode — persists a `:fn-execution` row with `:status :pending` and
   returns `{:status :pending :execution-id}` for polling.

   Persistence policy (see auto-persist matrix in docs/EXECUTION.md):

   | Condition                              | Persist? |
   |----------------------------------------|----------|
   | client `persist?=true`                 | yes      |
   | `declared-effects` ≠ #{} (audit trail) | yes      |
   | timeout fired (need polling target)    | yes      |
   | pure fn finished inline AND ¬persist?  | NO       |

   Cancellation is best-effort: POST /api/execute/:id/cancel sets the
   row's `:cancel-requested?` flag and calls `future-cancel`. The
   executor's `*cancel-check*` dyn-var is bound to a closure that
   reads the flag; on the next caller→callee transition it throws
   `InterruptedException` — the future catches and writes
   `:status :cancelled`. Long-running JDBC / blocking-IO inside a fn
   won't respond to interrupt without explicit `Statement.cancel()`
   (documented as soft).

   This namespace is the public API + orchestrator. Lookups
   (read-only DB navigation) live in `.lookup`; row writes + future
   plumbing + size caps live in `.persist`."
  (:require
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.crud.request :as request]
    [graphden.services.reconciler :as services-recon]
    [graphden.storage.protocol.core :as sp]))


;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private default-timeout-ms 10000)
(def ^:private max-timeout-ms      60000)     ; HTTP proxies bite past ~120s


;; Re-export: tests + the cancel endpoint look up futures by id.
(def lookup-future persist/lookup-future)


;; =============================================================================
;; Parse — JSON body → in-memory parsed map
;; =============================================================================

(defn- safe-uuid
  "`parse-uuid-or-clear` throws on malformed input; we want nil here
   so the validation stage can reject with a clean error instead of
   500-ing on a typo'd UUID."
  [v]
  (when (and v (string? v))
    (try (request/parse-uuid-or-clear v)
         (catch IllegalArgumentException _ nil))))


(defn parse-execute-request
  "Stage 1 — JSON body to `{:fn-id-or-name :args :timeout-ms :persist?}`.
   No DB access; pure transform of incoming bytes.

   Body shape:
     {\"fn-id\":     \"uuid\"            ; XOR with fn-name
      \"fn-name\":   \"add\"
      \"args\":      {\"a\": 1, \"b\": {\"ref\": \"uuid\"}, \"c\": [1, 2]}
      \"timeout-ms\":10000
      \"persist?\":  false}"
  [request]
  (let [body (request/read-json-body request)]
    {:fn-id      (safe-uuid (:fn-id body))
     :fn-name    (when-let [n (:fn-name body)] (str n))
     :args       (or (:args body) {})
     :timeout-ms (or (:timeout-ms body) default-timeout-ms)
     :persist?   (true? (:persist? body))}))


;; =============================================================================
;; Validation — returns nil on success, {:ok false :error …} on rejection
;; =============================================================================

(defn- already-running-as-service?
  "Returns a `{:reason :service-id?}` map when this fn is already
   alive in a way that would conflict with a fresh Run:

   - an enabled `:service` row exists for `fn-id` (managed path) —
     returns `{:source :service :service-id …}`
   - the Phase 1 legacy fallback is active for this fn-id (no DB
     row but the integrant component holds its stopper) — returns
     `{:source :legacy-fallback}`

   Used by `validate-execute` to refuse ad-hoc Run; prevents the
   foot-gun where clicking ▶ on :web-server tries to re-bind its
   already-occupied port."
  [storage fn-id]
  (or (when-let [svc (first (sp/query-entities storage :service
                                               {:fn-id fn-id :enabled? true}))]
        {:source :service :service-id (:id svc)})
      (when-let [handle @services-recon/legacy-handle]
        (when (= fn-id (:fn-id handle))
          {:source :legacy-fallback}))))


(defn validate-execute
  "Stage 2 — pre-flight checks. Returns nil when valid, or a
   `{:ok false :error :error-data}` rejection map.

   Reasons we reject:
     :no-fn               — neither fn-id nor fn-name resolves
     :fn-not-found        — id/name didn't match a fn entity
     :no-version          — fn has no version row (corrupt state)
     :timeout-out-of-range — timeout-ms < 1 or > 60000
     :args-too-large      — serialised args > 256 KB
     :already-running-as-service — fn-id matches an enabled :service
                                   row; the reconciler owns it
     :unknown-arg         — arg name isn't in the fn's free-args
     :missing-required    — *not enforced here* (the executor itself
                            throws on missing required slot at run-
                            time; would duplicate logic to check twice)."
  [ctx parsed]
  (let [storage (request/require-storage ctx)
        fn-id (lookup/resolve-fn-id storage parsed)]
    (cond
      (and (nil? (:fn-id parsed)) (nil? (:fn-name parsed)))
      {:ok false :status :rejected :error "Request must carry :fn-id or :fn-name"
       :error-data {:reason :no-fn}}

      (nil? fn-id)
      {:ok false :status :rejected :error (str "Function not found: "
                                               (or (:fn-name parsed) (:fn-id parsed)))
       :error-data {:reason :fn-not-found}}

      (or (< (:timeout-ms parsed) 1)
          (> (:timeout-ms parsed) max-timeout-ms))
      {:ok false :status :rejected :error (str ":timeout-ms must be in [1, " max-timeout-ms "]")
       :error-data {:reason :timeout-out-of-range :timeout-ms (:timeout-ms parsed)}}

      (> (persist/args-bytes (:args parsed)) persist/max-args-bytes)
      {:ok false :status :rejected :error (str ":args size exceeds " persist/max-args-bytes " bytes")
       :error-data {:reason :args-too-large
                    :bytes (persist/args-bytes (:args parsed))}}

      :else
      (or (when-let [conflict (already-running-as-service? storage fn-id)]
            (let [legacy? (= :legacy-fallback (:source conflict))]
              {:ok false :status :rejected
               :error (if legacy?
                        "Function is already running as the boot fallback service. Pod restart required to free its port, or declare a managed :service row + reconcile to take ownership."
                        "Function is already running as a managed service. Disable the service in /api/entities/service or restart it via /api/services/reconcile.")
               :error-data (cond-> {:reason :already-running-as-service
                                    :source (:source conflict)}
                             (:service-id conflict)
                             (assoc :service-id (:service-id conflict)))}))
          (let [free-args (lookup/free-arg-slot-map ctx fn-id)
                unknown (remove (set (keys free-args))
                                (map keyword (keys (:args parsed))))]
            (when (seq unknown)
              {:ok false :status :rejected :error (str "Unknown arg(s): " (vec unknown))
               :error-data {:reason :unknown-arg
                            :unknown unknown
                            :known (vec (keys free-args))}}))))))


;; =============================================================================
;; Apply — submit future, deref with timeout, dispatch persist
;; =============================================================================

(defn apply-execute
  "Stage 3 — submit the future, deref with timeout. Returns one of:
     {:status :succeeded :result … :execution-id?}
     {:status :pending :execution-id …}
     {:status :failed :error … :error-data … :execution-id?}

   Persistence policy:
   - Pre-create row when we know we need polling capability
     (timeout > a few hundred ms is enough to justify the write —
     keeps the polling client able to find the row even if completion
     races our response).
   - For pure fast fns with `:persist? false`, finalise without ever
     writing a row."
  [ctx parsed]
  (let [storage (request/require-storage ctx)
        ;; Single round-trip for both `:id` and `:name`; the older
        ;; flow did `resolve-fn-id` + a separate `read-entity` to pull
        ;; the name.
        fn-row (lookup/resolve-fn storage parsed)
        fn-id (:id fn-row)
        fn-name (:name fn-row)
        fn-version-id (lookup/resolve-fn-version-id ctx fn-id)
        free-slots (lookup/free-arg-slot-map ctx fn-id)
        declared-eff (persist/declared-effects-of fn-name)
        need-persist? (or (:persist? parsed) (seq declared-eff))
        executor-args (into {}
                            (keep (fn [[k v]]
                                    (when (contains? free-slots (keyword k))
                                      [(keyword k)
                                       (if (persist/ref-arg? v)
                                         (persist/parse-ref-fn-id v)
                                         v)])))
                            (:args parsed))
        cancel-flag (atom false)
        pre-persisted? need-persist?
        row (when pre-persisted?
              (persist/create-pending-with-args!
                storage fn-version-id declared-eff
                (:user-id parsed) (:args parsed) free-slots))
        [fut trace] (persist/run-future ctx fn-id executor-args cancel-flag)
        _   (when row (persist/register-future! (:id row) fut cancel-flag))
        result (try (deref fut (:timeout-ms parsed) ::pending)
                    (catch java.util.concurrent.ExecutionException ee
                      {::ex (java.util.concurrent.ExecutionException/.getCause ee)}))
        ;; Closure (not eager) — only the inline-success/failure
        ;; branches snapshot the trace; timeout branches hand the atom
        ;; off to `record-completion!` which snapshots when the future
        ;; resolves.
        runtime-eff (fn [] (persist/snapshot-runtime-effects trace))]
    (cond
      ;; Timeout AND we haven't pre-persisted — persist lazily so the
      ;; client gets an id to poll. record-completion! tails the future
      ;; to update the row when it finally resolves.
      (and (= ::pending result) (not pre-persisted?))
      (let [r (persist/create-pending-with-args!
                storage fn-version-id declared-eff
                (:user-id parsed) (:args parsed) free-slots)]
        (persist/register-future! (:id r) fut cancel-flag)
        (persist/record-completion! storage (:id r) fut trace declared-eff)
        {:status :pending :execution-id (str (:id r))})

      ;; Timeout AND we pre-persisted — record-completion's tail-future
      ;; fills in :result; client polls our row.
      (= ::pending result)
      (do (persist/record-completion! storage (:id row) fut trace declared-eff)
          {:status :pending :execution-id (str (:id row))})

      ;; Inline failure — write outcome to the row synchronously (if
      ;; persisted) so the polling-by-id case is consistent.
      (and (map? result) (::ex result))
      (let [cause (::ex result)
            eff (runtime-eff)
            outcome (cond-> {:status :failed
                             :error (or (ex-message cause) (str cause))
                             :error-data (ex-data cause)}
                      eff (assoc :runtime-effects eff))]
        (persist/log-effect-drift! (some-> row :id) declared-eff eff)
        (when row
          (persist/write-finished! storage (:id row) outcome)
          (persist/unregister-future! (:id row)))
        (cond-> outcome
          row (assoc :execution-id (str (:id row)))))

      ;; Inline success — same: write synchronously so the GET endpoint
      ;; immediately returns :succeeded, no race window.
      :else
      (let [eff (runtime-eff)]
        (persist/log-effect-drift! (some-> row :id) declared-eff eff)
        (when row
          (persist/write-finished! storage (:id row)
                                   (cond-> {:status :succeeded :result result}
                                     eff (assoc :runtime-effects eff)))
          (persist/unregister-future! (:id row)))
        (cond-> {:status :succeeded
                 :result result
                 :declared-effects declared-eff}
          eff (assoc :runtime-effects eff)
          row (assoc :execution-id (str (:id row))))))))


;; =============================================================================
;; GET /api/execute/:id — read a row + nested args
;; =============================================================================

(defn- args-for-execution
  [storage execution-id]
  (let [arg-rows (sp/query-entities storage :fn-execution-arg
                                    {:execution-id execution-id})
        ;; Push the arg-id set into storage as a SQL IN clause; the
        ;; alternative — full-table-scanning :fn-execution-arg-item and
        ;; grouping in memory — is fine for one row but quadratic when
        ;; GET /api/execute/:id is hit by the polling loop.
        item-rows (when (seq arg-rows)
                    (sp/query-entities storage :fn-execution-arg-item
                                       {:execution-arg-id (mapv :id arg-rows)}))
        items-by-arg (group-by :execution-arg-id item-rows)]
    (mapv (fn [a]
            (assoc a :items
                   (->> (get items-by-arg (:id a) [])
                        (sort-by :position)
                        vec)))
          arg-rows)))


(defn get-execution
  "Public handler for GET /api/execute/:id — returns the row + nested
   args list. nil when not found."
  [ctx execution-id]
  (let [storage (request/require-storage ctx)
        row (sp/read-entity storage :fn-execution execution-id)]
    (when row
      (assoc row :args (args-for-execution storage execution-id)))))


;; =============================================================================
;; GET /api/executions?fn-id=X — list recent runs for one fn (across
;; all of its versions). Used by the editor's history sidebar.
;; =============================================================================

(def ^:private default-history-limit 20)
(def ^:private max-history-limit 100)


(defn list-executions-for-fn
  "Return up to `limit` (default 20, cap 100) :fn-execution rows whose
   fn-version belongs to logical `fn-id` (i.e. any version of that
   base fn), ordered by `:started-at` desc. Each row carries the
   SUMMARY shape the history panel renders — no nested args (call
   get-execution when the user expands a row).

   `limit` is clamped to `[1, max-history-limit]`; nil / non-positive
   values fall back to `default-history-limit`. Returns `[]` when no
   rows match (incl. when the fn was never run)."
  ([ctx fn-id]
   (list-executions-for-fn ctx fn-id nil))
  ([ctx fn-id limit]
   (let [storage (request/require-storage ctx)
         lim (cond
               (or (nil? limit) (not (number? limit)) (< limit 1))
               default-history-limit
               (> limit max-history-limit) max-history-limit
               :else (long limit))
         versions (sp/query-entities storage :fn-version {:fn-id fn-id})
         version-ids (mapv :id versions)]
     (if (empty? version-ids)
       []
       ;; Push the fn-version-id set into the storage query — collection
       ;; values resolve to a SQL `IN` clause (see
       ;; storage.postgres.crud/query-entities). Avoids the full-table
       ;; scan + in-memory filter we'd do otherwise.
       (->> (sp/query-entities storage :fn-execution
                               {:fn-version-id version-ids})
            (sort-by :started-at)
            reverse
            (take lim)
            vec)))))


;; =============================================================================
;; POST /api/execute/:id/cancel
;; =============================================================================

(defn cancel-execution!
  "Mark the row's `:cancel-requested?` true, set the in-process
   cancel-flag (executor's `*cancel-check*` will observe), and
   `future-cancel`. Best-effort — JDBC / blocking-IO in flight won't
   respond to interrupt."
  [ctx execution-id]
  (let [storage (request/require-storage ctx)
        row (sp/read-entity storage :fn-execution execution-id)
        entry (persist/lookup-future execution-id)]
    (when row
      (sp/update-entity storage :fn-execution execution-id
                        {:cancel-requested? true})
      (when entry
        (reset! (:cancel-flag entry) true)
        (future-cancel (:future entry)))
      {:ok true :cancel-requested true})))

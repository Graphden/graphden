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
    [graphden.storage.protocol.core :as sp]))


;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private max-timeout-ms 60000)     ; HTTP proxies bite past ~120s

(def ^:private default-timeout-ms 10000) ; matches `:_execute-timeout-ms` default
;; in `app/execution/fns.edn`


;; Re-export: tests + the cancel endpoint look up futures by id.
(def lookup-future persist/lookup-future)


;; =============================================================================
;; Parse — JSON body → in-memory parsed map
;; =============================================================================

;; `safe-uuid` removed — `request/parse-uuid-or-clear` is now
;; lenient itself (returns nil for non-string / blank / malformed
;; input), so wrapping it in another try/catch was redundant.


;; =============================================================================
;; Validation — returns nil on success, {:ok false :error …} on rejection
;; =============================================================================

(defn- already-running-as-service?
  "Returns `{:source :service :service-id <uuid>}` when an enabled
   `:service` row exists for `fn-id`. Used by `validate-execute` to
   refuse ad-hoc Run on a fn the reconciler already owns — prevents
   the foot-gun where clicking ▶ on :web-server tries to re-bind its
   already-occupied port."
  [storage fn-id]
  (when-let [svc (first (sp/query-entities storage :service
                                           {:fn-id fn-id :enabled? true}))]
    {:source :service :service-id (:id svc)}))


;; === Stage-2 execute-validation guards (C23) ===
;; Decomposed from the old 88-line cond into one defn per guard so
;; the same chain wires as a graph `:cond` fn-def
;; (`:_execute-validation` in app/execution/fns.edn) AND as the
;; back-compat composition below — single source of truth either
;; way. Each guard returns `{:ok false :status :rejected …}` on
;; rejection or nil on pass.

(defn- execute-no-fn-rej
  "Guard 1 — request carried neither `:fn-id` nor `:fn-name`."
  [parsed]
  (when (and (nil? (:fn-id parsed)) (nil? (:fn-name parsed)))
    {:ok false :status :rejected
     :error "Request must carry :fn-id or :fn-name"
     :error-data {:reason :no-fn}}))


(defn- execute-fn-not-found-rej
  "Guard 2 — `:fn-id` or `:fn-name` was supplied but didn't resolve
   to a real `:fn` row. Without this guard, the parsed `:fn-id`
   would slip through to apply-execute and surface as the
   executor's bare \"Function not found: <nil>\" with an empty
   error string."
  [parsed ctx]
  (let [storage (request/require-storage ctx)
        fn-row (lookup/resolve-fn storage parsed)]
    ;; Skip if the no-fn guard already matched.
    (when (and (or (:fn-id parsed) (:fn-name parsed))
               (nil? (:id fn-row)))
      {:ok false :status :rejected
       :error (str "Function not found: "
                   (or (:fn-name parsed) (:fn-id parsed)))
       :error-data {:reason :fn-not-found}})))


(defn- execute-timeout-out-of-range-rej
  "Guard 3 — `:timeout-ms` must land in `[1, max-timeout-ms]`. nil
   timeouts are treated as missing → use the default (mirrors the
   graph path's `:_execute-timeout-ms :coalesce :default 10000`).
   The prior version called `(< nil 1)` which NPE'd on direct
   Clojure callers that omitted `:timeout-ms` — the test suite + the
   HTTP graph path both supplied it, masking the contract gap."
  [parsed]
  (let [t (or (:timeout-ms parsed) default-timeout-ms)]
    (when (or (< t 1) (> t max-timeout-ms))
      {:ok false :status :rejected
       :error (str ":timeout-ms must be in [1, " max-timeout-ms "]")
       :error-data {:reason :timeout-out-of-range :timeout-ms t}})))


(defn- execute-args-too-large-rej
  "Guard 4 — serialised `:args` payload exceeds `max-args-bytes`."
  [parsed]
  (let [n (persist/args-bytes (:args parsed))]
    (when (> n persist/max-args-bytes)
      {:ok false :status :rejected
       :error (str ":args size exceeds " persist/max-args-bytes " bytes")
       :error-data {:reason :args-too-large :bytes n}})))


(defn- execute-already-running-rej
  "Guard 5 — the target fn is already alive as a managed `:service`
   row, so a fresh Run would conflict (the reconciler owns it).
   Returns nil when no enabled service references the fn."
  [parsed ctx]
  (let [storage (request/require-storage ctx)
        fn-row (lookup/resolve-fn storage parsed)
        fn-id (:id fn-row)]
    (when fn-id
      (when-let [conflict (already-running-as-service? storage fn-id)]
        {:ok false :status :rejected
         :error "Function is already running as a managed service. Disable the service in /api/entities/service or restart it via /api/services/reconcile."
         :error-data {:reason :already-running-as-service
                      :source (:source conflict)
                      :service-id (:service-id conflict)}}))))


(defn- execute-unknown-arg-rej
  "Guard 6 — every arg name in `:args` must match one of the fn's
   free-arg slots; an unknown name is a typo that would silently
   ride through apply-execute (the executor ignores unknown keys
   in the args map). Reach requires fn-id to be resolved."
  [parsed ctx]
  (let [storage (request/require-storage ctx)
        fn-row (lookup/resolve-fn storage parsed)
        fn-id (:id fn-row)]
    (when fn-id
      (let [free-args (lookup/free-arg-slot-map ctx fn-id)
            unknown (remove (set (keys free-args))
                            (map keyword (keys (:args parsed))))]
        (when (seq unknown)
          {:ok false :status :rejected
           :error (str "Unknown arg(s): " (vec unknown))
           :error-data {:reason :unknown-arg
                        :unknown unknown
                        :known (vec (keys free-args))}})))))


(defn- execute-malformed-ref-rej
  "Guard 7 — a `{:ref <str>}` arg-shape whose `:ref` isn't a
   well-formed UUID used to slip through validation, land in
   apply-execute as nil, and silently produce nonsense results
   (`{:args {:nums {:ref \"not-a-uuid\"}}}` against `:add`
   returned 0 — sum of an empty list — because `:nums` resolved
   to nil). Reject early with a clean reason."
  [parsed]
  (let [malformed (keep (fn [[k v]]
                          (when (and (persist/ref-arg? v)
                                     (nil? (persist/parse-ref-fn-id v)))
                            {:arg (name k) :raw-ref (:ref v)}))
                        (:args parsed))]
    (when (seq malformed)
      {:ok false :status :rejected
       :error (str "Malformed ref(s) in args: "
                   (vec (map :arg malformed)))
       :error-data {:reason :malformed-ref
                    :malformed (vec malformed)}})))


(defn validate-execute
  "Stage 2 — pre-flight checks. Returns nil when valid, or the first
   matching `{:ok false :status :rejected :error :error-data}`
   rejection. Composes the per-guard helpers above in the same
   order as the `:_execute-validation` graph `:cond` so the Clojure
   path (used by direct callers + tests) stays observationally
   equivalent to the graph path.

   Reasons we reject (see per-guard defns for details):
     :no-fn / :fn-not-found / :timeout-out-of-range / :args-too-large
     / :already-running-as-service / :unknown-arg / :malformed-ref."
  [ctx parsed]
  (or (execute-no-fn-rej parsed)
      (execute-fn-not-found-rej parsed ctx)
      (execute-timeout-out-of-range-rej parsed)
      (execute-args-too-large-rej parsed)
      (execute-already-running-rej parsed ctx)
      (execute-unknown-arg-rej parsed ctx)
      (execute-malformed-ref-rej parsed)))


;; =============================================================================
;; Apply — submit future, deref with timeout, dispatch persist
;; =============================================================================

(defn- finalize-inline-outcome
  "Shared tail for the two inline-resolution arms (`:succeeded` /
   `:failed`) of `apply-execute`. Stamps secret-flow metadata, runs
   the redactor, logs effect drift, writes the row + unregisters the
   future when persisted, and attaches `:execution-id` when there is
   one. Pure on `base-outcome`; side effects scoped to `ctx`.

   `ctx` keys: `:storage` `:row` `:fn-name` `:declared-effects`
   `:runtime-effects`."
  [base-outcome {:keys [storage row fn-name declared-effects runtime-effects]}]
  (let [outcome (->> (cond-> base-outcome
                       runtime-effects (assoc :runtime-effects runtime-effects))
                     (persist/stamp-touched-secret fn-name)
                     (persist/redact-outcome fn-name))]
    (persist/log-effect-drift! (some-> row :id) declared-effects runtime-effects)
    (when row
      (persist/write-finished! storage (:id row) outcome)
      (persist/unregister-future! (:id row)))
    (cond-> outcome
      row (assoc :execution-id (str (:id row))))))


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
        (persist/record-completion! storage (:id r) fn-name fut trace declared-eff)
        {:status :pending :execution-id (str (:id r))})

      ;; Timeout AND we pre-persisted — record-completion's tail-future
      ;; fills in :result; client polls our row.
      (= ::pending result)
      (do (persist/record-completion! storage (:id row) fn-name fut trace declared-eff)
          {:status :pending :execution-id (str (:id row))})

      ;; Inline failure — write outcome to the row synchronously (if
      ;; persisted) so the polling-by-id case is consistent. Redaction
      ;; lifts a tainted fn-def's :error/:error-data into a generic
      ;; hidden form so the secret doesn't leak via the exception
      ;; message (a string that may have wrapped the value).
      (and (map? result) (::ex result))
      (let [cause (::ex result)]
        (finalize-inline-outcome
          {:status :failed
           :error (or (ex-message cause) (str cause))
           :error-data (ex-data cause)}
          {:storage storage :row row :fn-name fn-name
           :declared-effects declared-eff :runtime-effects (runtime-eff)}))

      ;; Inline success — same: write synchronously so the GET endpoint
      ;; immediately returns :succeeded, no race window. Redaction
      ;; lifts a tainted fn-def's :result into nil + `:tainted? true`
      ;; so the JSON response carries metadata only.
      :else
      (-> (finalize-inline-outcome
            {:status :succeeded :result result}
            {:storage storage :row row :fn-name fn-name
             :declared-effects declared-eff :runtime-effects (runtime-eff)})
          (assoc :declared-effects declared-eff)))))


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


(defn- clamp-history-limit
  [limit]
  (cond
    (or (nil? limit) (not (number? limit)) (< limit 1)) default-history-limit
    (> limit max-history-limit)                         max-history-limit
    :else                                               (long limit)))


(defn list-executions-for-fn-version
  "Return up to `limit` :fn-execution rows for the SPECIFIC
   `fn-version-id`, latest first. Drives the `⌛` fn-versions panel's
   per-version expand-to-see-runs UI."
  ([ctx fn-version-id]
   (list-executions-for-fn-version ctx fn-version-id nil))
  ([ctx fn-version-id limit]
   (let [storage (request/require-storage ctx)
         lim (clamp-history-limit limit)]
     (->> (sp/query-entities storage :fn-execution
                             {:fn-version-id fn-version-id})
          (sort-by :started-at)
          reverse
          (take lim)
          vec))))


(defn list-executions-for-fn
  "Return up to `limit` (default 20, cap 100) :fn-execution rows for
   logical `fn-id` AS IT RESOLVES ON THE CURRENT BRANCH — i.e. only
   runs of the version the editor would actually execute if the user
   clicked ▶ right now. Ordered by `:started-at` desc.

   This MATCHES the editor's mental model: branch X is a coherent
   functional view; executions of OTHER versions live behind the `⌛`
   history panel where each version row carries its own count + an
   expand-to-see-runs affordance. Pre-fix this returned `:fn-execution`
   rows for EVERY version of the fn-id regardless of branch, which
   conflated runs that may have had different arg shapes / behaviour
   into one list (and broke Repeat for runs whose arg-shape no longer
   matched the current version).

   Returns `[]` when the fn has no version visible on the current
   branch (e.g. fn never created here AND not inherited) or no runs
   yet."
  ([ctx fn-id]
   (list-executions-for-fn ctx fn-id nil))
  ([ctx fn-id limit]
   (if-let [version-id (lookup/resolve-fn-version-id ctx fn-id)]
     (list-executions-for-fn-version ctx version-id limit)
     [])))


;; =============================================================================
;; GET /api/executions parsing (C6 atoms)
;; =============================================================================

(defn query-param
  "Pull a named query-string parameter from `request`, tolerating both
   reitit's enriched shapes AND raw http-kit requests that haven't
   gone through the enrich middleware.

   Also surfaced as the `:query-param` base-fn in
   `web/crud/impls.clj`."
  [request param-name]
  (or (get-in request [:query-params param-name])
      (get-in request [:query-params (keyword param-name)])
      (some->> (:query-string request)
               (re-find (re-pattern (str "(?:^|&)" param-name "=([^&]+)")))
               second)))


(defn apply-list-executions-by-version
  "Success branch — `?fn-version-id` was supplied (and won the cond
   dispatch over `?fn-id`)."
  [parsed ctx]
  {:ok true
   :executions (list-executions-for-fn-version ctx (:version-id parsed)
                                               (:limit parsed))})


(defn apply-list-executions-by-fn
  "Success branch — `?fn-id` was supplied (no `?fn-version-id`)."
  [parsed ctx]
  {:ok true
   :executions (list-executions-for-fn ctx (:fn-id parsed) (:limit parsed))})


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

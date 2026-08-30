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
    [graphden.executor.compile-runtime :as cr]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [graphden.types.diagnostics :as diag]
    [graphden.util.json-safe :as json-safe]
    [graphden.versioning.storage.core :as vs]))


;; Re-export: tests + the cancel endpoint look up futures by id.
(def lookup-future persist/lookup-future)


(declare apply-execute*)


;; =============================================================================
;; Parse — JSON body → in-memory parsed map
;; =============================================================================

;; Validation (Stage 2) runs entirely in the graph — `:_execute-validation`
;; in `app/execution/fns.edn`, a `:cond` over per-guard rejection-builders
;; (`:_execute-no-fn-err`, `:_execute-fn-not-found-err`, …). No Clojure
;; mirror: the sole caller is POST /api/execute → `:execute`.


;; =============================================================================
;; Apply — submit future, deref with timeout, dispatch persist
;; =============================================================================

(defn- finalize-inline-outcome
  "Shared tail for the two inline-resolution arms (`:succeeded` /
   `:failed`) of `apply-execute`. Stamps secret-flow metadata, runs
   the redactor, logs effect drift, writes the row + unregisters the
   future when persisted, and attaches `:execution-id` when there is
   one. Pure on `base-outcome`; side effects scoped to `ctx`.

   `ctx` keys: `:storage` `:row` `:fn-id` `:declared-effects`
   `:runtime-effects` `:path-trace` (the Debug-P1 snapshot when the
   submission carried `trace?`) `:stats` (the Phase C1 rollup ctx,
   bumped here so the inline arms count exactly once — the async arms
   bump in `record-completion!`)."
  [base-outcome {:keys [storage row fn-id declared-effects runtime-effects path-trace stats]}]
  (let [outcome (->> (cond-> base-outcome
                       runtime-effects (assoc :runtime-effects runtime-effects)
                       path-trace (assoc :path-trace path-trace))
                     (persist/stamp-touched-secret fn-id)
                     (persist/redact-outcome fn-id)
                     (persist/scrub-outcome fn-id))]
    (persist/log-effect-drift! (some-> row :id) declared-effects runtime-effects)
    (persist/bump-usage! stats fn-id (:status outcome))
    (when row
      ;; Unregister even if the terminal write throws (DB error) — else
      ;; the futures-registry entry leaks until the pod restarts.
      (try
        (persist/write-finished! storage (:id row) outcome)
        (finally
          (persist/unregister-future! (:id row)))))
    (cond-> outcome
      row (assoc :execution-id (str (:id row))))))


(defn- type-error-rejection
  "Error-tolerance Phase 4 — the submit-time refusal built when `fn-row`
   has RECORDED type diagnostics on the current branch (400 via the
   standard `:rejected` envelope, reason `:unresolved-type-errors`).

   Judged on what the derived per-branch store has RECORDED: the store
   is empty after a JVM restart until checks re-record (the branch
   router's ctx-build recompute repopulates editor-authored fns
   asynchronously), so absence of an entry means ALLOW — the honest
   contract for derived state. Returns nil when execution may proceed."
  [storage fn-row]
  (let [fn-id (:id fn-row)
        diags (diag/errors-for-fn (vs/current-branch-id storage) fn-id)]
    (when (seq diags)
      {:ok false :status :rejected
       :error (str "Execution refused: fn '" (or (:name fn-row) fn-id)
                   "' has unresolved type errors — "
                   (:message (first diags)))
       :http-status 400
       :error-data {:reason :unresolved-type-errors
                    :fn-id (str fn-id)
                    :diagnostics (vec diags)}})))


(defn apply-execute
  "Stage 3 — submit the future, deref with timeout. Returns one of:
     {:status :succeeded :result … :execution-id? :declared-effects …}
     {:status :pending :execution-id …}
     {:status :failed :error … :error-data … :execution-id?}
     {:ok false :status :rejected …} — submit-time refusal (recorded
       type errors, capacity cap) before any row/future exists.

   Persistence policy:
   - Pre-create row when we know we need polling capability
     (timeout > a few hundred ms is enough to justify the write —
     keeps the polling client able to find the row even if completion
     races our response).
   - For pure fast fns with `:persist? false`, finalise without ever
     writing a row."
  ([ctx parsed]
   ;; Non-graph callers (tests, BYO) — resolve the row here; the graph
   ;; path passes the validation stage's already-resolved `:_execute-fn-row`
   ;; through the 3-arity, so the request does ONE resolve, not two.
   (apply-execute ctx parsed (lookup/resolve-fn (request/require-storage ctx) parsed)))
  ([ctx parsed fn-row]
   (or (type-error-rejection (request/require-storage ctx) fn-row)
       (apply-execute* ctx parsed fn-row))))


(defn- execution-plan
  "Everything the run needs, derived once from the request. Pure apart
   from the two graph lookups; nothing here takes a slot or writes a
   row, so a rejection after this point costs nothing to unwind."
  [ctx parsed fn-row]
  (let [fn-id (:id fn-row)
        ;; Cached: this call was ~1.3–1.9 s uncached and runs once per
        ;; request. Safe here because /api/execute runs after CRUD writes
        ;; (which invalidate), never during one. See lookup ns.
        free-slots (lookup/free-arg-slot-map-cached ctx fn-id)
        declared-eff (persist/declared-effects-of fn-id)
        org (tc/current-org)
        storage (request/require-storage ctx)]
    {:storage storage
     ;; Which branch's view submitted the run — stamped on the persisted
     ;; row so the Errors panel can scope failures per branch-chain.
     :branch-id (vs/current-branch-id storage)
     ;; A tenant's SUBMITTED fn is untrusted graph code, so it runs
     ;; effect-restricted: carry the cloud allow-list on the ctx and the
     ;; executor gates it via `record-effect!` (compile-runtime honours
     ;; `:allowed-effects`). Only the user-fn execution (`run-future`) uses
     ;; this ctx — the platform lookups above and the request handler around
     ;; us stay UNRESTRICTED, because that trusted machinery reads storage
     ;; through `:pg-query` (a `:raw-sql`-recording base-fn) which must not
     ;; be gated. Public org ≡ platform / single-tenant → no restriction.
     :exec-ctx (cond-> ctx
                 (not= org tc/public-org)
                 ;; Per-org effect allow-list resolved from the org's plan
                 ;; (task #4) — free stays locked, a paid tier widens it
                 ;; (e.g. +:network). Falls back to the locked default when
                 ;; no plan resolver is installed.
                 (assoc :allowed-effects (cr/cloud-allowed-effects-for org)))
     :fn-id fn-id
     :fn-version-id (lookup/resolve-fn-version-id ctx fn-id)
     :free-slots free-slots
     :declared-eff declared-eff
     :org org
     ;; Pre-persist when the caller asked for it, or when the fn declares
     ;; effects — an effectful run must leave a row even if it outlives the
     ;; inline window.
     :persist? (or (:persist? parsed) (seq declared-eff))
     :executor-args (into {}
                          (keep (fn [[k v]]
                                  (when (contains? free-slots (keyword k))
                                    [(keyword k)
                                     (if (persist/ref-arg? v)
                                       (persist/parse-ref-fn-id v)
                                       v)])))
                          (:args parsed))
     :cancel-flag (atom false)
     ;; Rollup context (Phase C1): raw pool + org + submit time — threaded
     ;; to every terminal transition so `usage-stat` counts each run once,
     ;; with duration, no matter which arm finishes it. nil pool (bare test
     ;; ctx) → bumps are no-ops.
     :stats-ctx {:pool (:pool (:pg-storage ctx))
                 :org org
                 :start-ms (System/currentTimeMillis)}}))


(def ^:private over-capacity-rejection
  ;; Global or per-org concurrency cap hit — reject WITHOUT creating a
  ;; row or a future, so a client can't pile unbounded compute onto the
  ;; shared JVM. Reuses the standard rejection envelope.
  {:ok false :status :rejected
   :error "Execution capacity exceeded — retry shortly"
   :http-status 429
   :error-data {:reason :over-capacity}})


(defn- submit-run!
  "Pre-persist the pending row when required and hand the fn to the
   executor. Returns `{:row :fut :trace :path-trace}`, or a map under
   `::rejected` when the bounded queue turned the submission away.

   Owns the slot until `run-future` takes it: between
   `acquire-execution-slot!` and that hand-off the pending-row write can
   throw (DB blip / unique / RLS reject), and an unreleased permit leaks
   permanently — the org (then the JVM) would eventually hit the cap and
   reject every execution with `:over-capacity` while nothing runs.
   `release` is idempotent, so the future's finally re-calling it is a
   no-op."
  [{:keys [storage exec-ctx fn-id fn-version-id free-slots declared-eff
           executor-args cancel-flag persist? branch-id]}
   parsed release]
  (try
    (let [row (when persist?
                (persist/create-pending-with-args!
                  storage fn-version-id declared-eff
                  (:user-id parsed) (:args parsed) free-slots branch-id))]
      (try
        (let [[fut trace path-trace]
              (persist/run-future exec-ctx fn-id executor-args cancel-flag release
                                  {:trace? (:trace? parsed)
                                   :capture-values? (:capture-values? parsed)})]
          {:row row :fut fut :trace trace :path-trace path-trace})
        (catch java.util.concurrent.RejectedExecutionException _
          ;; The bounded execution QUEUE is full (P1.2) — park is
          ;; exhausted. Release the per-org slot, drop the orphan
          ;; pending row, and tell the client to retry (503 +
          ;; Retry-After) — NOT 429-reject, NOT an unbounded queue.
          ;; run-future's finally never ran (the task never
          ;; started), so this is the sole release.
          (release)
          (when row
            (try (sp/delete-entity storage :fn-execution (:id row))
                 (catch Exception _ nil)))
          {::rejected
           {:ok false :status :rejected
            :error "Execution queue full — retry shortly"
            :http-status 503 :retry-after 2
            :error-data {:reason :queue-full}}})))
    (catch Exception t
      (release)
      (throw t))))


(defn- await-outcome!
  "Wait out the inline window and turn what happened into the response.

   Four arms: timed out with / without a pre-persisted row (both hand the
   future to `record-completion!` and answer `:pending` with an id to
   poll), inline failure, inline success. Both inline arms write the
   terminal state SYNCHRONOUSLY so a GET by id right after is already
   consistent."
  [{:keys [storage fn-id declared-eff stats-ctx fn-version-id free-slots
           persist? cancel-flag branch-id]}
   parsed {:keys [row fut trace path-trace]}]
  (let [result (try (java.util.concurrent.Future/.get
                      fut (long (:timeout-ms parsed))
                      java.util.concurrent.TimeUnit/MILLISECONDS)
                    (catch java.util.concurrent.TimeoutException _ ::pending)
                    ;; A watchdog/cancel firing inside the deref window
                    ;; surfaces as CancellationException — treat as
                    ;; pending; the reaper records the terminal state.
                    (catch java.util.concurrent.CancellationException _ ::pending)
                    (catch java.util.concurrent.ExecutionException ee
                      {::ex (java.util.concurrent.ExecutionException/.getCause ee)}))
        ;; Closures (not eager) — only the inline-success/failure
        ;; branches snapshot the traces; timeout branches hand the
        ;; atoms off to `record-completion!` which snapshots when the
        ;; future resolves.
        runtime-eff (fn [] (persist/snapshot-runtime-effects trace))
        path-snapshot (fn [] (persist/snapshot-path-trace path-trace))
        finalize-ctx (fn []
                       {:storage storage :row row :fn-id fn-id
                        :declared-effects declared-eff
                        :runtime-effects (runtime-eff)
                        :path-trace (path-snapshot)
                        :stats stats-ctx})]
    (cond
      ;; Timeout AND we haven't pre-persisted — persist lazily so the
      ;; client gets an id to poll. record-completion! tails the future
      ;; to update the row when it finally resolves.
      (and (= ::pending result) (not persist?))
      (let [r (persist/create-pending-with-args!
                storage fn-version-id declared-eff
                (:user-id parsed) (:args parsed) free-slots branch-id)]
        (persist/register-future! (:id r) fut cancel-flag)
        (persist/record-completion! storage (:id r) fn-id fut trace declared-eff stats-ctx path-trace)
        {:status :pending :execution-id (str (:id r))})

      ;; Timeout AND we pre-persisted — record-completion's tail-future
      ;; fills in :result; client polls our row.
      (= ::pending result)
      (do (persist/record-completion! storage (:id row) fn-id fut trace declared-eff stats-ctx path-trace)
          {:status :pending :execution-id (str (:id row))})

      ;; Inline failure. Redaction lifts a tainted fn-def's
      ;; :error/:error-data into a generic hidden form so the secret
      ;; doesn't leak via the exception message (a string that may have
      ;; wrapped the value).
      (and (map? result) (::ex result))
      (let [cause (::ex result)]
        (finalize-inline-outcome
          {:status :failed
           :error (or (ex-message cause) (str cause))
           ;; ex-data is author-controlled and leaves here twice —
           ;; as the response's JSON `error-data` and as the row's
           ;; jsonb. One unencodable leaf would cost the caller the
           ;; whole failure report (500, log ref, nothing else), so
           ;; render such leaves instead of dropping the report.
           :error-data (json-safe/json-safe (ex-data cause))}
          (finalize-ctx)))

      ;; Inline success. Redaction lifts a tainted fn-def's :result into
      ;; nil + `:tainted? true` so the JSON response carries metadata only.
      :else
      (-> (finalize-inline-outcome {:status :succeeded :result result} (finalize-ctx))
          (assoc :declared-effects declared-eff)))))


(defn- apply-execute*
  "The post-admission body of `apply-execute` — see its docstring.
   Derive the plan, take a concurrency slot, submit, await the inline
   window. Each stage can reject, and each rejection is an envelope."
  [ctx parsed fn-row]
  (let [{:keys [storage org] :as plan} (execution-plan ctx parsed fn-row)
        ;; A tenant's per-org cap is enforced FLEET-WIDE (counting pending
        ;; rows in shared storage); the public/platform org keeps the per-pod
        ;; atom. `storage` is the org-scoped request storage, so the fleet
        ;; count sees only this org's own rows.
        release (persist/acquire-execution-slot! storage org (not= org tc/public-org))]
    (if (nil? release)
      over-capacity-rejection
      (let [submit (submit-run! plan parsed release)]
        (if-let [rejected (::rejected submit)]
          rejected
          (do (when-let [row (:row submit)]
                (persist/register-future! (:id row) (:fut submit) (:cancel-flag plan)))
              (await-outcome! plan parsed submit)))))))


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
      (-> row
          ;; READ-time re-redaction: a fn that became secret after this
          ;; run must not keep serving captured values from the stored
          ;; trace (persist/re-redact-path-trace).
          (update :path-trace #(some-> % persist/re-redact-path-trace))
          (assoc :args (args-for-execution storage execution-id))
          ;; The logical base fn behind the frozen fn-version snapshot —
          ;; drives typed-repr dispatch in the execute-result partial
          ;; (`:_er-exec-fn-id`). Derived at read time so the historical
          ;; row itself stays version-pinned.
          (assoc :fn-id (some->> (:fn-version-id row)
                                 (sp/read-entity storage :fn-version)
                                 :fn-id))))))


;; =============================================================================
;; GET /partials/execute-trace — display rows of a persisted trace
;; =============================================================================

(defn- trace-tree-order
  "Depth-first ordering of re-redacted trace `entries` with a `:depth`
   assigned to each. Tree-linked entries (`:seq` present) nest via
   `:parent-seq`, children sorted by `:seq` (entry order); pre-tree
   entries (no `:seq`) keep their stored order at depth 0, after the
   tree."
  [entries]
  (let [{linked true, linkless false} (group-by #(some? (:seq %)) entries)
        present (into #{} (map :seq) linked)
        ;; ORPHANS are roots too: a truncated trace (byte cap dropped
        ;; the oldest entries; entry cap stopped recording before the
        ;; outer frames completed) keeps children whose parent entry
        ;; never landed — walking only nil-parent roots would silently
        ;; drop the entire surviving forest (a captured page render
        ;; over the 10k-entry cap rendered ZERO rows).
        root? (fn [e]
                (let [p (:parent-seq e)]
                  (or (nil? p) (not (contains? present p)))))
        children (group-by :parent-seq (remove root? linked))
        walk (fn walk
               [e depth]
               (cons (assoc e :depth depth)
                     (mapcat #(walk % (inc depth))
                             (sort-by :seq (get children (:seq e))))))]
    (concat (mapcat #(walk % 0) (sort-by :seq (filter root? linked)))
            (map #(assoc % :depth 0) linkless))))


(defn- trace-entry-row
  "One depth-annotated entry → the FACT row the /partials/execute-trace
   graph chain renders from: ids + joined name + the raw status fields.
   All DISPLAY policy — chip kind/label, indent scaling, value
   pretty-printing — is graph composition (`:_ptrace-r-*` in
   app/execution/fns.edn). `:has-value?` marks a PRESENT capture (a
   captured nil is meaningful and must render as `null`, which a bare
   nil-check graph-side could not tell from absent)."
  [names e]
  (let [fn-id (str (:fn-id e))]
    (cond-> {:seq (:seq e)
             :fn-id fn-id
             :fn-name (or (get names fn-id) (str (subs fn-id 0 8) "…"))
             :depth (long (:depth e))
             :cache-hit? (boolean (:cache-hit? e))
             :duration-ms (or (:duration-ms e) 0)}
      (:hidden e) (assoc :hidden (name (:hidden e)))
      (:value-hidden e) (assoc :derived? true)
      (contains? e :value) (assoc :has-value? true :value (:value e))
      (:value-truncated? e) (assoc :value-truncated? true))))


(defn- trace-fn-names
  "`{fn-id-str → name}` for every distinct fn-id in `entries`, resolved
   against the current branch view. A deleted/foreign id simply stays
   unnamed (the row falls back to the short id)."
  [storage entries]
  (let [ids (into [] (comp (keep :fn-id) (map str) (distinct)
                           (keep request/parse-uuid-or-clear))
                  entries)]
    (if (empty? ids)
      {}
      (into {}
            (keep (fn [row] (when (:name row) [(str (:id row)) (:name row)])))
            (sp/query-entities storage :fn {:id ids})))))


(defn trace-display-rows
  "Fact payload for one persisted execution's `:path-trace` —
   `{:found? bool :rows […] :truncated? :values-dropped?}`, rows per
   `trace-entry-row`. Entries pass READ-time re-redaction first
   (`persist/re-redact-path-trace`), then reassemble into the
   depth-first call tree (`:seq`/`:parent-seq`), then join fn names.
   The tree walk + name join is one cohesive read-shaping algorithm;
   the DISPLAY policy (chips, indent, pretty-print) lives in the
   `/partials/execute-trace` graph chain."
  [ctx execution-id]
  (let [storage (request/require-storage ctx)
        row (when execution-id
              (sp/read-entity storage :fn-execution execution-id))
        pt (some-> (:path-trace row) persist/re-redact-path-trace)
        entries (:entries pt)]
    (if (empty? entries)
      {:found? false :rows []}
      (let [names (trace-fn-names storage entries)]
        (cond-> {:found? true
                 :rows (mapv #(trace-entry-row names %)
                             (trace-tree-order entries))}
          (:path-truncated? pt) (assoc :truncated? true)
          (:values-dropped? pt) (assoc :values-dropped? true))))))


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
     ;; ORDER BY + LIMIT push into SQL (:fn-execution is non-versioned,
     ;; so the VersionedStorage decorator delegates opts straight to
     ;; base); :fn-version-id is a :ref (indexed), so Postgres filters
     ;; on the index and returns only the newest `lim` rows instead of
     ;; transferring + sorting a hot fn's whole run history.
     (mapv (fn [row]
             ;; Same READ-time re-redaction as `get-execution` — these
             ;; rows go to the history sidebar with their `:path-trace`.
             (update row :path-trace #(some-> % persist/re-redact-path-trace)))
           (sp/query-entities storage :fn-execution
                              {:fn-version-id fn-version-id}
                              {:order-by [[:started-at :desc]] :limit lim})))))


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
   gone through the enrich middleware. Values from the raw fallback
   are URL-decoded so callers see the same shape regardless of which
   path produced them (reitit's enriched `:query-params` already
   decodes; the regex-extracted fallback didn't, which broke any
   handler whose param value carried percent-encoded JSON / spaces /
   non-ASCII — see `/partials/fn-picker-incompat?expected=%22int%22`
   parsing as a literal `%22int%22` instead of the JSON string
   `\"int\"`).

   Also surfaced as the `:query-param` base-fn in
   `web/crud/impls.clj`."
  [request param-name]
  (or (get-in request [:query-params param-name])
      (get-in request [:query-params (keyword param-name)])
      (some->> (:query-string request)
               (re-find (re-pattern (str "(?:^|&)" param-name "=([^&]+)")))
               second
               ;; Soft-decode: a malformed percent-escape (`%`, `%zz`)
               ;; in an untrusted query string must not 500 the handler.
               request/safe-url-decode)))


;; =============================================================================
;; POST /api/execute/:id/cancel
;; =============================================================================

(defn cancel-execution!
  "Mark the row's `:cancel-requested?` true, then stop the future.

   The future may not be ours. `futures-registry` is per-process, so with
   several pods behind a load balancer the cancel request usually lands
   on a pod that isn't running the execution. When we don't own it, fan
   the request out on `graphden_events` — the owning pod's listener calls
   `persist/cancel-local!`. Without that hop the DB flag would be set and
   the execution would keep running, because `*cancel-check*` reads the
   in-process cancel-flag atom, not the row.

   Best-effort throughout — JDBC / blocking-IO in flight won't respond to
   interrupt, and a single-pod deployment never emits the event."
  [ctx execution-id]
  (let [storage (request/require-storage ctx)
        row (sp/read-entity storage :fn-execution execution-id)]
    (when row
      (sp/update-entity storage :fn-execution execution-id
                        {:cancel-requested? true})
      (when-not (persist/cancel-local! execution-id)
        (when-let [emit (:notify-emitter ctx)]
          ;; Tag with the row's org so the SSE relay fans the cancel out only
          ;; to that org's subscribers (uniform with fn-invalidate events),
          ;; instead of leaking a bare execution UUID to every org's remote
          ;; executors. nil org (single-tenant) still reaches everyone.
          (emit (cond-> {:kind :execution :op :cancel :id (str execution-id)}
                  (:org-id row) (assoc :org-id (:org-id row))))))
      {:ok true :cancel-requested true})))


;; Server-side rendering for `/partials/execute-result?id=X` moved
;; to the graph (`:_er-*` chain in `app/execution/fns.edn`, entry
;; `:_er-body`). §3.3 fix — the previous Clojure-side walker
;; (render-scalar/list/record/tainted/error/succeeded-body helpers
;; + the public render-execute-result-hiccup) hid text labels,
;; cap constants, and shape-dispatch policy in private defns where
;; admins couldn't reach. Graph version exposes:
;; - `:_er-max-list-items` (was Clojure const `max-list-items`)
;; - `:_er-max-json-preview-bytes` (was `max-json-preview-bytes`)
;; - every label, every conditional, every per-item type-dispatch
;;   as named `_er-*` fn-defs.

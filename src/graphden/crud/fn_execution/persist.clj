(ns graphden.crud.fn-execution.persist
  "Write-side helpers for the /api/execute pipeline:

   - In-process futures registry (cancel can look up the Future
     handle and the cancel-flag atom by execution-id).
   - Result / error / error-data size caps + truncation.
   - Row creation + update — :fn-execution + :fn-execution-arg +
     :fn-execution-arg-item.
   - `run-future` — submit the executor invocation as a future with
     the `*cancel-check*` dyn-var bound.
   - `record-completion!` — tail-future that observes outcome and
     writes the terminal row state.

   Extracted from `graphden.crud.fn-execution` so the
   parse/validate/apply orchestrator stays focused on policy."
  (:require
    [cheshire.core :as json]
    [clojure.set]
    [clojure.tools.logging :as log]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.registry.core :as registry]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.core :as types]))


;; =============================================================================
;; Size caps (kept here because every persist path consults them)
;; =============================================================================

(def max-result-bytes    (* 5 1024 1024))   ; 5 MB jsonb cap
(def max-args-bytes      (* 256 1024))      ; 256 KB total args
(def max-error-chars     4096)              ; 4 KB
(def max-error-data-bytes (* 64 1024))      ; 64 KB


;; =============================================================================
;; Futures registry — in-flight executions indexed by execution-id so
;; cancel can find the Future handle and interrupt it. Lives in-process
;; (a server restart loses cancel capability for previously-pending
;; rows; the TTL sweeper picks those up as zombies).
;; =============================================================================

(defonce ^:private futures-registry (atom {}))


(defn register-future!
  [execution-id ^java.util.concurrent.Future fut cancel-flag]
  (swap! futures-registry assoc execution-id
         {:future fut :cancel-flag cancel-flag}))


(defn unregister-future!
  [execution-id]
  (swap! futures-registry dissoc execution-id))


(defn lookup-future
  "Public for tests + cancel endpoint. Returns the registry entry or nil."
  [execution-id]
  (get @futures-registry execution-id))


(defn cancel-local!
  "Cancel `execution-id` if THIS pod is the one running it. Returns true
   when a live future was found and cancelled, false otherwise.

   The registry is per-process, so in a multi-pod deployment the pod that
   receives `POST /api/execute/:id/cancel` is usually NOT the pod running
   the execution. The load balancer picked one; the future lives on
   another. Setting the row's `:cancel-requested?` flag doesn't help by
   itself — the executor's `*cancel-check*` closes over the in-process
   `cancel-flag` atom, not over the DB row. So the receiving pod fans the
   request out over NOTIFY and every pod calls this; at most one of them
   owns the future."
  [execution-id]
  (if-let [entry (lookup-future execution-id)]
    (do (reset! (:cancel-flag entry) true)
        (future-cancel (:future entry))
        true)
    false))


;; =============================================================================
;; Truncation / JSON-size enforcement
;; =============================================================================

(defn truncate-error
  [s]
  (let [s (str s)]
    ;; `max-error-chars` is a CHARACTER budget by design (a human-readable
    ;; message truncated for display), so `count` / `subs` on chars is right
    ;; here — unlike the byte-named caps below.
    (if (<= (count s) max-error-chars)
      s
      (str (subs s 0 max-error-chars) "…"))))


(defn utf8-byte-count
  "UTF-8 byte length of `s`. The size caps are byte budgets (Postgres
   jsonb stores UTF-8), so `(count s)` — which is the UTF-16 CODE-UNIT
   count — under-enforces them by up to ~3× on non-ASCII text."
  ^long [^String s]
  (alength (String/.getBytes s java.nio.charset.StandardCharsets/UTF_8)))


(defn json-bytes-within?
  "Serialize `value` to UTF-8 JSON through a streaming writer that counts
   bytes and ABORTS the moment the count exceeds `limit` — so an oversize
   (or unserializable) value is refused WITHOUT materialising the whole
   JSON string in memory. Returns true iff `value` serialises to ≤ `limit`
   UTF-8 bytes. (`json/generate-string` + `count` would fully realise a
   500 MB result string before a 5 MB cap could reject it.)"
  [value ^long limit]
  (let [counter (java.util.concurrent.atomic.AtomicLong.)
        os (proxy [java.io.OutputStream] []
             (write
               ([b]
                (when (> (java.util.concurrent.atomic.AtomicLong/.incrementAndGet counter) limit)
                  (throw (ex-info "oversize" {::oversize true}))))
               ([_b _off len]
                (when (> (java.util.concurrent.atomic.AtomicLong/.addAndGet counter (long len)) limit)
                  (throw (ex-info "oversize" {::oversize true}))))))
        w (java.io.OutputStreamWriter. os java.nio.charset.StandardCharsets/UTF_8)]
    (try
      (json/generate-stream value w)
      (java.io.Writer/.flush w)
      true
      (catch clojure.lang.ExceptionInfo e
        (when-not (::oversize (ex-data e))
          (log/warn e "Result JSON-encode failed — treating as oversize"))
        false)
      (catch Exception e
        (log/warn e "Result JSON-encode failed — treating as oversize")
        false))))


(defn jsonize-result
  "Test the result against the size cap. Returns `[ok? value-or-nil]` —
   `[true result]` when within cap, `[false nil]` when oversize OR
   when the value can't be JSON-encoded at all (treating unserializable
   results the same as oversize: storage layer would fail downstream
   anyway, better to refuse here with a clean signal). Streams the
   serialization so an oversize result never materialises fully."
  [result]
  (if (json-bytes-within? result max-result-bytes)
    [true result]
    [false nil]))


(defn jsonize-error-data
  [data]
  (let [json-str (try (json/generate-string data)
                      (catch Exception e
                        (log/warn e "Error-data JSON-encode failed — truncating to :type")
                        nil))]
    (if (and json-str (<= (utf8-byte-count json-str) max-error-data-bytes))
      data
      ;; Best-effort: try to keep just the :type key (canonical
      ;; error-code), drop the rest.
      {:type (:type data) :truncated true})))


(defn args-bytes
  [args]
  (utf8-byte-count (json/generate-string args)))


(defn ref-arg?
  [v]
  (and (map? v) (contains? v :ref)))


(defn parse-ref-fn-id
  "`{:ref \"uuid-str\"}` → fn-id UUID (or nil for missing/malformed
   `:ref`). Pure transform — no DB access. Used by `apply-execute` to
   strip the ref-wrapper before handing args to the executor."
  [ref-value]
  (some-> (:ref ref-value) request/parse-uuid-or-clear))


(defn snapshot-runtime-effects
  "Read the captured effect-set from `trace-atom` and convert to the
   wire shape — vec of strings — that ends up on the row's
   `:runtime-effects` field. Returns nil for an empty or absent trace
   so callers can `(when …)` over it without distinguishing the two."
  [trace-atom]
  (when trace-atom
    (some-> @trace-atom seq (->> (mapv name)))))


(defn resolve-ref-version-id
  "`{:ref \"uuid-str\"}` → current `:fn-version-id` for that fn (or nil
   if the fn has no version row). Used by `persist-args!` to write
   `:ref-fn-version-id` on arg rows so historical executions stay
   pinned to the version they ran against."
  [storage ref-value]
  (some->> (parse-ref-fn-id ref-value)
           (lookup/resolve-fn-version-id {:storage storage})))


;; =============================================================================
;; Row writes — pending → terminal state transitions.
;; =============================================================================

(defn declared-effects-of
  "Look up the declared `:effects` set for `fn-name` from rich-types
   registry. Returns a vec of strings (for jsonb wire); nil when fn
   has no effects (pure)."
  [fn-name]
  (when fn-name
    (some-> (registry/rich-type-of (keyword fn-name))
            :effects
            seq
            (->> (mapv name)))))


(defn persist-args!
  "Write one :fn-execution-arg row per supplied arg, with optional
   :fn-execution-arg-item rows for list-typed args. Args without a
   matching slot in `free-slots` are skipped silently — validation
   should have caught those upstream."
  [storage execution-id args free-slots]
  (doseq [[k v] args
          :let [slot-id (get free-slots (keyword k))]
          :when slot-id]
    (cond
      ;; Single ref
      (ref-arg? v)
      (sp/create-entity storage :fn-execution-arg
                        {:execution-id execution-id
                         :slot-id slot-id
                         :value nil
                         :ref-fn-version-id (resolve-ref-version-id storage v)})

      ;; List — create the arg row + per-item rows
      (sequential? v)
      (let [arg-row (sp/create-entity storage :fn-execution-arg
                                      {:execution-id execution-id
                                       :slot-id slot-id
                                       :value nil
                                       :ref-fn-version-id nil})]
        (doseq [[idx item] (map-indexed vector v)]
          (if (ref-arg? item)
            (sp/create-entity storage :fn-execution-arg-item
                              {:execution-arg-id (:id arg-row)
                               :position idx
                               :value nil
                               :ref-fn-version-id (resolve-ref-version-id
                                                    storage item)})
            (sp/create-entity storage :fn-execution-arg-item
                              {:execution-arg-id (:id arg-row)
                               :position idx
                               :value item
                               :ref-fn-version-id nil}))))

      ;; Literal scalar (or map that isn't a ref)
      :else
      (sp/create-entity storage :fn-execution-arg
                        {:execution-id execution-id
                         :slot-id slot-id
                         :value v
                         :ref-fn-version-id nil}))))


(defn create-pending-row!
  "Persist a :fn-execution row with status :pending — called BEFORE
   the future is submitted when we know we'll need polling support."
  [storage fn-version-id declared-effects user-id]
  (sp/create-entity storage :fn-execution
                    {:fn-version-id fn-version-id
                     :started-at (java.time.Instant/now)
                     :status :pending
                     :declared-effects declared-effects
                     :user-id user-id}))


(defn create-pending-with-args!
  "Atomic: create a `:pending` row + write the per-arg rows. Returns
   the parent row. Used by both `apply-execute` pre-persist and lazy-
   persist branches; the future registration happens at the callsite
   because the two paths sequence it differently."
  [storage fn-version-id declared-effects user-id args free-slots]
  (let [r (create-pending-row! storage fn-version-id declared-effects user-id)]
    (persist-args! storage (:id r) args free-slots)
    r))


(defn tainted-fn?
  "True iff `fn-name`'s registered effective return-type carries a
   `:secret` marker anywhere in its structure. Looked up against the
   rich-types registry — same place the type-checker consults. Pure
   data lookup, no name-dispatch on behaviour: every call walks the
   computed type, not a hard-coded fn-name list.

   `fn-name` arrives from storage as a string (the `:fn.name` field
   is `:text`); the registry keys on keywords, mirroring
   `declared-effects-of` just above."
  [fn-name]
  (and fn-name
       (when-let [ret (:return (registry/rich-type-of (keyword fn-name)))]
         (types/contains-secret? ret))))


(defn touches-secret?
  "True iff `fn-name`'s rich-type carries the `:secret` marker on
   its return OR on any of its declared arg slots. Broader than
   `tainted-fn?`: `tainted-fn?` only fires when the fn RETURNS a
   secret; `touches-secret?` also fires when the fn CONSUMES one
   (e.g. `:sql-exec` whose `:password` slot is `[:secret :text]`
   but whose return is plain `:int`). Used by the audit trail to
   flag executions that fed a secret into a side-effecting sink."
  [fn-name]
  (when fn-name
    (when-let [rt (registry/rich-type-of (keyword fn-name))]
      (boolean
        (or (types/contains-secret? (or (:return rt) :any))
            (some (fn [[_ arg-entry]]
                    (types/contains-secret?
                      (or (some-> arg-entry :type) arg-entry)))
                  (:args rt)))))))


(defn stamp-touched-secret
  "Audit trail: set `:touched-secret? true` on `outcome`
   when (a) the fn-def's rich-type touches a `:secret` AND (b) the
   runtime observed at least one side-effect. Both halves matter:
   a pure tainted-aware run isn't an audit event; a runtime-side-
   effect on a fn that never saw a secret isn't either. The
   intersection is the row admins want to review.

   Returns the outcome unchanged when one or both halves are false."
  [fn-name outcome]
  (cond-> outcome
    (and (touches-secret? fn-name)
         (seq (:runtime-effects outcome)))
    (assoc :touched-secret? true)))


(defn redact-outcome
  "If `fn-name` is tainted (per `tainted-fn?`), strip the secret value
   from a succeeded/failed outcome — the result body and the error
   message can both carry the secret in plain text. Replaces them with
   `:tainted?` markers so the caller (and the persisted row) hide the
   value entirely instead of relying on string-matching masks.

   Cancelled outcomes pass through (no value attached). Non-tainted
   outcomes pass through unchanged."
  [fn-name outcome]
  (if (tainted-fn? fn-name)
    (case (:status outcome)
      :succeeded (-> outcome
                     (assoc :result nil :tainted? true)
                     (dissoc :error :error-data))
      :failed    (-> outcome
                     (assoc :tainted? true
                            :error "Result hidden: fn return-type carries :secret marker.")
                     (assoc :error-data {:reason :tainted}))
      outcome)
    outcome))


(defn write-finished!
  "Update an existing row with the future's outcome.
   `outcome` is one of:
     {:status :succeeded :result V [:runtime-effects [\"env\" …]]}
     {:status :failed :error E :error-data ED [:runtime-effects …]}
     {:status :cancelled [:runtime-effects …]}

   When the row's fn-def is tainted, `:result` is persisted as nil
   and a `:tainted? true` flag rides alongside the row's other
   metadata. Pass the outcome through `redact-outcome` BEFORE calling
   this fn."
  [storage execution-id outcome]
  (let [base {:finished-at (java.time.Instant/now)
              :status (:status outcome)}
        body (case (:status outcome)
               :succeeded (if (:tainted? outcome)
                            ;; Hidden — :result stays nil on the row.
                            ;; The error-data sidecar carries the
                            ;; tainted flag so the GET endpoint can
                            ;; surface it without re-reading the
                            ;; rich-types registry on every poll.
                            (assoc base
                                   :result nil
                                   :error-data {:reason :tainted})
                            (let [[ok? v] (jsonize-result (:result outcome))]
                              (assoc base
                                     :result v
                                     :result-truncated? (not ok?))))
               :failed (assoc base
                              :error (truncate-error (:error outcome))
                              :error-data (jsonize-error-data
                                            (:error-data outcome)))
               :cancelled base)
        body (cond-> body
               (:runtime-effects outcome)
               (assoc :runtime-effects (:runtime-effects outcome))
               (:touched-secret? outcome)
               (assoc :touched-secret? true))]
    (sp/update-entity storage :fn-execution execution-id body)))


;; =============================================================================
;; Future submission + completion reaping
;; =============================================================================

;; =============================================================================
;; Concurrency governance — a plain `(future …)` runs on Clojure's UNBOUNDED
;; soloExecutor, so without a cap a client POSTing many expensive fns piles
;; unbounded threads onto the shared JVM (compute-DoS, cross-tenant on
;; feature/tenancy-users).
;;
;; TWO caps, with DIFFERENT scopes:
;;
;; - GLOBAL (`*max-concurrent-executions*`): per-POD. It protects THIS JVM's
;;   soloExecutor from thread exhaustion, so it MUST be per-process — an
;;   atom-counted slot, released when the computation finishes (not when the
;;   HTTP response returns; the future outlives the deref-timeout).
;;
;; - PER-ORG (`*max-concurrent-executions-per-org*`): tenant fairness / quota.
;;   For a TENANT this is FLEET-WIDE — counted from the shared `:fn-execution`
;;   table so N pods enforce ONE budget instead of N×budget. It is self-
;;   healing: the source of truth is the pending rows, so a crashed pod leaks
;;   no counter (the zombie/TTL sweeper reaps its rows), unlike a durable
;;   counter table. For the PUBLIC org (platform / single-tenant) it stays the
;;   per-pod atom — the platform isn't a metered tenant and its executions are
;;   the hot editor path we don't want to add a query to.
;;
;; The fleet count has a bounded TOCTOU slack: two pods can both admit in the
;; same window before either row exists, so an org can transiently exceed the
;; cap by ~(concurrent admissions). That's acceptable for a fairness limit —
;; the GLOBAL per-pod cap remains the exact thread-exhaustion safety net.
;; =============================================================================

(def ^:dynamic *max-concurrent-executions*
  "Global, PER-POD cap on concurrently-running fn-execution futures."
  (or (some-> (System/getenv "GRAPHDEN_MAX_CONCURRENT_EXECUTIONS") parse-long) 128))


(def ^:dynamic *max-concurrent-executions-per-org*
  "Per-org cap. Fleet-wide for tenants, per-pod for the public org."
  (or (some-> (System/getenv "GRAPHDEN_MAX_CONCURRENT_EXECUTIONS_PER_ORG") parse-long) 32))


(defonce ^:private live-executions (atom {:total 0 :by-org {}}))


(defn- over-fleet-org-cap?
  "True when `org` already has at least `*max-concurrent-executions-per-org*`
   non-terminal (`:pending`) executions across the fleet. Counts the shared
   `:fn-execution` table (`:fn-execution` is non-versioned, so `:limit` is
   safe + bounds the scan to cap+1 rows). Fails OPEN on a storage error — the
   global per-pod cap is the safety net, so a transient count failure must not
   wrongly reject."
  [storage org]
  (let [cap *max-concurrent-executions-per-org*]
    (try
      (>= (count (sp/query-entities storage :fn-execution
                                    {:org-id org :status :pending}
                                    {:limit (inc cap)}))
          cap)
      (catch Exception e
        (log/warn e "fleet per-org execution count failed — admitting (global cap still applies)"
                  {:org org})
        false))))


(defn acquire-execution-slot!
  "Try to reserve a concurrency slot for `org`. Returns a 0-arg RELEASE fn on
   success, or nil when a cap is already hit (caller must reject).

   `tenant?` selects the per-org enforcement: true → FLEET-WIDE (count pending
   `:fn-execution` rows in `storage`); false (the public/platform org) → the
   per-pod atom. The global per-pod cap always applies. See the section
   comment above for scope rationale."
  [storage org tenant?]
  (let [[old new] (swap-vals!
                    live-executions
                    (fn [{:keys [total by-org] :as st}]
                      (if (and (< total *max-concurrent-executions*)
                               ;; Tenants gate per-org on the fleet count below;
                               ;; public gates on the local atom here.
                               (or tenant?
                                   (< (get by-org org 0) *max-concurrent-executions-per-org*)))
                        (-> st (update :total inc) (update-in [:by-org org] (fnil inc 0)))
                        st)))]
    (when (not= old new)
      (let [make-release
            (fn []
              ;; Idempotent: the slot must be released EXACTLY once even if both
              ;; the future's `finally` AND a caller's error-path call it.
              (let [released? (atom false)]
                (fn release
                  []
                  (when (compare-and-set! released? false true)
                    (swap! live-executions
                           (fn [st]
                             (-> st
                                 (update :total dec)
                                 (update-in [:by-org org] (fnil dec 0)))))))))
            release (make-release)]
        (if (and tenant? (over-fleet-org-cap? storage org))
          ;; Fleet-wide per-org budget is full — give the global slot back.
          (do (release) nil)
          release)))))


(def ^:dynamic *max-execution-wall-ms*
  "Hard wall-clock deadline for a SINGLE execution. After it, a watchdog
   flips the cancel-flag AND interrupts the future — best-effort: graph
   caller→callee transitions observe `*cancel-check*` and interruptible
   IO (JDBC, sleep) responds to the thread interrupt, but a pure CPU loop
   inside one base-fn cannot be force-killed by the JVM (that residual is
   bounded by the concurrency cap + `*max-graph-iterations*` + `range`
   size limits). nil / 0 disables the watchdog. Only the /api/execute
   path calls `run-future`; services run via the reconciler, unaffected."
  (or (some-> (System/getenv "GRAPHDEN_MAX_EXECUTION_WALL_MS") parse-long) 300000))


(defonce ^:private deadline-scheduler
  (doto (java.util.concurrent.ScheduledThreadPoolExecutor. 1)
    ;; cancelled watchdogs (the common, execution-finished-first case) leave
    ;; the queue promptly instead of lingering until their delay elapses.
    (java.util.concurrent.ScheduledThreadPoolExecutor/.setRemoveOnCancelPolicy true)))


(defn- arm-deadline!
  "Schedule a watchdog that, after `ms`, flips `cancel-flag` + interrupts
   `fut` unless it already finished. Returns the `ScheduledFuture` (so the
   caller cancels it when the execution completes first), or nil when no
   positive deadline is set."
  [ms cancel-flag fut]
  (when (and ms (pos? ms))
    (java.util.concurrent.ScheduledThreadPoolExecutor/.schedule
      deadline-scheduler
      ^Runnable (fn []
                  (when-not (future-done? fut)
                    (reset! cancel-flag true)
                    (future-cancel fut)))
      (long ms) java.util.concurrent.TimeUnit/MILLISECONDS)))


(defn run-future
  "Submit `(executor/execute …)` to a future bound to a cancel-flag
   AND a fresh effect-trace atom. The executor's `*cancel-check*`
   dyn-var is bound to a closure that reads the atom (on flip the next
   caller→callee transition throws); `*effect-trace*` is bound to an
   atom-set that effectful base-fn impls conj into via `record-effect!`.

   `release` (nil-able) is the concurrency-slot releaser from
   `acquire-execution-slot!`; it fires in the future's `finally` so the slot
   frees when the COMPUTATION ends (which may be long after the HTTP deref
   times out), never leaking a permit. A wall-clock watchdog
   (`*max-execution-wall-ms*`) hard-kills a runaway; the finally cancels it
   on normal completion.

   Returns `[future trace-atom]` — the reaper needs the trace atom to
   read the captured effect set after the future resolves."
  [ctx fn-id args cancel-flag release]
  (let [trace (atom #{})
        watchdog (promise)
        bf (bound-fn* ; capture clojure.tools.logging MDC etc.
            (fn []
              (binding [cr/*cancel-check*
                        #(when @cancel-flag
                           (throw (InterruptedException. "execution cancelled")))
                        cr/*effect-trace* trace]
                (cr/execute ctx fn-id args))))
        fut (future
              (try
                (bf)
                (finally
                  ;; @watchdog blocks only until the request thread delivers
                  ;; it (microseconds after this future was created).
                  (some-> @watchdog (java.util.concurrent.ScheduledFuture/.cancel false))
                  (when release (release)))))]
    (deliver watchdog (arm-deadline! *max-execution-wall-ms* cancel-flag fut))
    [fut trace]))


(defn log-effect-drift!
  "Emit a `warn`-level log when runtime-observed effects diverge from
   declared. Two divergence types:

   - widened: runtime ∉ declared — impl performed an un-promised
     effect. Real production signal (impl drift vs rich-type).
   - unobserved: declared ∉ runtime — promised effect didn't fire.
     Could be a conditional branch not taken (benign) or an
     over-declaration (cleanup opportunity).

   Both are surfaced at the same log site so a single grep
   (`:type :execution/effect-drift`) catches every mismatch across
   the fleet without the editor's History panel being open."
  [execution-id declared runtime]
  (when (or (seq declared) (seq runtime))
    (let [d (set declared)
          r (set runtime)
          widened (clojure.set/difference r d)
          unobserved (clojure.set/difference d r)]
      (when (or (seq widened) (seq unobserved))
        (log/warnf "execution effect drift {:type :execution/effect-drift, :execution-id %s, :declared %s, :runtime %s, :widened %s, :unobserved %s}"
                   execution-id (vec d) (vec r) (vec widened) (vec unobserved))))))


(defn record-completion!
  "Background handler: when future resolves (success/fail/interrupt),
   write outcome to the row + clean up registry. `trace-atom` is the
   `*effect-trace*` atom from `run-future`; we snapshot it onto the
   row's `:runtime-effects` field alongside the terminal status, and
   warn-log if it diverges from `declared-effects`.

   `fn-name` is consulted by `redact-outcome` to hide the result body
   when the fn-def's effective return-type is `:secret`-marked. This
   is the async-completion path; the sync inline-success path in
   `apply-execute` redacts independently. Both write the same shape
   to the row."
  [storage execution-id fn-name ^java.util.concurrent.Future fut trace-atom declared-effects]
  (future
    (try
      (let [result @fut
            runtime-eff (snapshot-runtime-effects trace-atom)]
        (log-effect-drift! execution-id declared-effects runtime-eff)
        (write-finished! storage execution-id
                         (->> (cond-> {:status :succeeded :result result}
                                runtime-eff (assoc :runtime-effects runtime-eff))
                              (stamp-touched-secret fn-name)
                              (redact-outcome fn-name))))
      (catch java.util.concurrent.ExecutionException ee
        (let [cause (java.util.concurrent.ExecutionException/.getCause ee)
              runtime-eff (snapshot-runtime-effects trace-atom)]
          (log-effect-drift! execution-id declared-effects runtime-eff)
          (if (instance? InterruptedException cause)
            (write-finished! storage execution-id
                             (cond-> {:status :cancelled}
                               runtime-eff (assoc :runtime-effects runtime-eff)))
            (write-finished! storage execution-id
                             (->> (cond-> {:status :failed
                                           :error (or (ex-message cause) (str cause))
                                           :error-data (when (ex-data cause)
                                                         (ex-data cause))}
                                    runtime-eff (assoc :runtime-effects runtime-eff))
                                  (stamp-touched-secret fn-name)
                                  (redact-outcome fn-name))))))
      (catch java.util.concurrent.CancellationException _
        (write-finished! storage execution-id {:status :cancelled}))
      (catch Exception e
        (log/warn e "Unexpected error reaping execution" execution-id))
      (finally
        (unregister-future! execution-id)))))

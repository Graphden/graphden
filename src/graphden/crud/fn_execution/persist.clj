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
     writes the terminal row state."
  (:require
    [cheshire.core :as json]
    [clojure.set]
    [clojure.tools.logging :as log]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.crud.fn-execution.stats :as stats]
    [graphden.crud.request :as request]
    [graphden.executor.compile-eager :as ce]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.registry.core :as registry]
    [graphden.storage.protocol.config :as storage-config]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.core :as types]
    [graphden.util.json-safe :as json-safe]
    [graphden.util.json-size :as json-size]))


;; =============================================================================
;; Size caps (kept here because every persist path consults them)
;; =============================================================================

(def max-result-bytes    (* 5 1024 1024))   ; 5 MB jsonb cap
(def max-args-bytes      (* 256 1024))      ; 256 KB total args
(def max-error-chars     4096)              ; 4 KB
(def max-error-data-bytes (* 64 1024))      ; 64 KB
(def max-path-trace-bytes (* 256 1024))     ; 256 KB Debug-P1 path trace


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
  "True iff `value` serialises to ≤ `limit` UTF-8 JSON bytes, measured
   through the streaming, abort-at-limit counter
   (`graphden.util.json-size` — extracted so the Debug-P3 value-capture
   seam shares the machinery) — an oversize (or unserializable) value
   is refused WITHOUT materialising the whole JSON string in memory."
  [value ^long limit]
  (some? (json-size/json-bytes-up-to value limit)))


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
  ;; Render unencodable leaves rather than let one of them cost the whole
  ;; report: ex-data is author-controlled, and a lone `java.lang.Class` or
  ;; atom in it used to truncate the row down to `:type` (and, on the inline
  ;; response path, to fail the response outright).
  (let [data (json-safe/json-safe data)
        json-str (try (json/generate-string data)
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


(defn snapshot-path-trace
  "Read the captured execution-path state from `path-trace-atom`
   (Debug P1 — bound as `cr/*path-trace*` by `run-future` when the
   submission carried `trace?`; shape per `ce/new-path-trace`) into
   the row's `:path-trace` jsonb shape:
   `{:entries [{:fn-id … :cache-hit? … :duration-ms … (:value …)} …]}`.
   nil for an absent atom or an empty capture, so callers `(when …)`
   over it like `snapshot-runtime-effects`.

   Debug-P3 value entries have their internal `ce/value-bytes-key`
   accounting stripped; a capture-time oldest-first drop (the 16 MB
   in-memory budget) surfaces as `:values-dropped? true`.

   Byte-capped at `max-path-trace-bytes` via the same streaming
   `json-bytes-within?` counter the result cap uses: oversize traces
   drop OLDEST entries first (10% of the vector per round) and carry
   `:path-truncated? true` INSIDE the json — no extra column."
  [path-trace-atom]
  (when path-trace-atom
    (let [{:keys [entries values-dropped?]} @path-trace-atom]
      (when (seq entries)
        ;; Stringify fn-ids at the boundary — jsonb roundtrips UUIDs as
        ;; strings anyway; doing it here keeps the byte count honest and
        ;; the wire shape explicit.
        (loop [v (mapv #(-> % (update :fn-id str) (dissoc ce/value-bytes-key))
                       entries)
               truncated? false]
          (let [payload (cond-> {:entries v}
                          truncated? (assoc :path-truncated? true)
                          values-dropped? (assoc :values-dropped? true))]
            (cond
              (json-bytes-within? payload max-path-trace-bytes) payload
              (= 1 (count v)) nil   ; single unserializable/oversize entry — refuse
              :else (recur (subvec v (max 1 (quot (count v) 10)))
                           true))))))))


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
  "Look up the declared `:effects` set for the fn IDENTIFIED BY `fn-id`
   from the rich-types registry. Returns a vec of strings (for jsonb
   wire); nil when fn has no effects (pure).

   Keyed by id, not name: per-namespace names may repeat, and an
   effect-set read off a same-named neighbour would mis-classify the
   run (persist-vs-inline decision)."
  [fn-id]
  (when fn-id
    (some-> (registry/rich-type-of-id fn-id)
            :effects
            seq
            (->> (mapv name)))))


(defn- arg-row
  "One `:fn-execution-arg` row for a supplied arg value. Three shapes
   share one row: a ref carries its target in `:ref-fn-version-id`, a
   list carries its content in child rows, and a literal (including a
   map that isn't a ref) keeps `:value`.

   The id is MINTED HERE rather than read back off the write, so the
   whole batch — parent rows and their items — can be built before a
   single statement goes out."
  [storage execution-id slot-id v]
  (let [ref? (ref-arg? v)
        list-arg? (sequential? v)]
    {:id (random-uuid)
     :execution-id execution-id
     :slot-id slot-id
     :value (when-not (or ref? list-arg?) v)
     :ref-fn-version-id (when ref? (resolve-ref-version-id storage v))}))


(defn- item-rows
  "`:fn-execution-arg-item` rows for a list-valued arg, in position
   order. Same value-xor-ref rule as the parent row."
  [storage arg-id v]
  (into []
        (map-indexed
          (fn [idx item]
            (let [ref? (ref-arg? item)]
              {:execution-arg-id arg-id
               :position idx
               :value (when-not ref? item)
               :ref-fn-version-id (when ref? (resolve-ref-version-id storage item))})))
        v))


(defn- create-all!
  "Batch-create `rows` — one statement per chunk of the configured batch
   ceiling. A thousand-element list argument costs a statement, not a
   thousand of them; chunking keeps a pathological one under the
   `*max-batch-size*` guard instead of throwing."
  [storage entity-name rows]
  (doseq [chunk (partition-all storage-config/*max-batch-size* rows)]
    (sp/create-entities storage entity-name (vec chunk))))


(defn persist-args!
  "Write one :fn-execution-arg row per supplied arg, with optional
   :fn-execution-arg-item rows for list-typed args. Args without a
   matching slot in `free-slots` are skipped silently — validation
   should have caught those upstream.

   Two batched writes total (parents, then items), so persistence cost
   tracks the number of ARGS, not the number of list elements."
  [storage execution-id args free-slots]
  (let [prepared (into []
                       (keep (fn [[k v]]
                               (when-let [slot-id (get free-slots (keyword k))]
                                 {:row (arg-row storage execution-id slot-id v)
                                  :value v})))
                       args)
        items (into []
                    (mapcat (fn [{:keys [row value]}]
                              (when (sequential? value)
                                (item-rows storage (:id row) value))))
                    prepared)]
    (create-all! storage :fn-execution-arg (mapv :row prepared))
    (create-all! storage :fn-execution-arg-item items)))


(defn create-pending-row!
  "Persist a :fn-execution row with status :pending — called BEFORE
   the future is submitted when we know we'll need polling support.
   `branch-id` (5-arity) stamps which branch's ExecutionContext ran —
   the Errors panel's visibility scope; nil ≡ branch-unscoped (visible
   everywhere, the pre-feature behaviour)."
  ([storage fn-version-id declared-effects user-id]
   (create-pending-row! storage fn-version-id declared-effects user-id nil))
  ([storage fn-version-id declared-effects user-id branch-id]
   (create-pending-row! storage fn-version-id declared-effects user-id branch-id nil))
  ([storage fn-version-id declared-effects user-id branch-id extra]
   ;; `extra` (6-arity) — fields a caller stamps up front: an explicit
   ;; `:id` (a traced listener binds the id BEFORE the row exists),
   ;; `:trace-id` / `:parent-execution-id` (cross-service tracing).
   (sp/create-entity storage :fn-execution
                     (merge {:fn-version-id fn-version-id
                             :started-at (java.time.Instant/now)
                             :status :pending
                             :declared-effects declared-effects
                             :user-id user-id
                             :branch-id branch-id}
                            extra))))


(defn create-pending-with-args!
  "Atomic: create a `:pending` row + write the per-arg rows. Returns
   the parent row. Used by both `apply-execute` pre-persist and lazy-
   persist branches; the future registration happens at the callsite
   because the two paths sequence it differently."
  ([storage fn-version-id declared-effects user-id args free-slots]
   (create-pending-with-args! storage fn-version-id declared-effects user-id args free-slots nil))
  ([storage fn-version-id declared-effects user-id args free-slots branch-id]
   (let [r (create-pending-row! storage fn-version-id declared-effects user-id branch-id)]
     (persist-args! storage (:id r) args free-slots)
     r)))


(defn tainted-fn?
  "True iff the fn's registered effective return-type carries a
   `:secret` marker anywhere in its structure. Looked up against the
   rich-types registry by the fn's IDENTITY — per-namespace names may
   repeat, and redaction consulting a same-named neighbour's entry
   could unhide a secret result. Pure data lookup, no name-dispatch on
   behaviour: every call walks the computed type, not a hard-coded
   fn-name list."
  [fn-id]
  (and fn-id
       (when-let [ret (:return (registry/rich-type-of-id fn-id))]
         ;; Generic over the marker registry: any marker whose flags say
         ;; :hide-result? (the seeded :secret, or a graph-declared one)
         ;; redacts the result body.
         (types/contains-hide-result-marker? ret))))


(def touches-secret?
  "True iff the fn's rich-type carries the `:secret` marker on its
   return OR on any of its declared arg slots. Broader than
   `tainted-fn?`: `tainted-fn?` only fires when the fn RETURNS a
   secret; `touches-secret?` also fires when the fn CONSUMES one.
   Used by the audit trail (`stamp-touched-secret`) AND by
   compile-eager's path-trace secret skip — the predicate lives in
   `registry.core/touches-secret?` so compile-eager reaches it without
   requiring crud; this var is an alias for the crud-side callsites."
  registry/touches-secret?)


(defn stamp-touched-secret
  "Audit trail: set `:touched-secret? true` on `outcome`
   when (a) the fn-def's rich-type touches a `:secret` AND (b) the
   runtime observed at least one side-effect. Both halves matter:
   a pure tainted-aware run isn't an audit event; a runtime-side-
   effect on a fn that never saw a secret isn't either. The
   intersection is the row admins want to review.

   Returns the outcome unchanged when one or both halves are false."
  [fn-id outcome]
  (cond-> outcome
    (and (touches-secret? fn-id)
         (seq (:runtime-effects outcome)))
    (assoc :touched-secret? true)))


(defn redact-outcome
  "If the fn is tainted (per `tainted-fn?`), strip the secret value
   from a succeeded/failed outcome — the result body and the error
   message can both carry the secret in plain text. Replaces them with
   `:tainted?` markers so the caller (and the persisted row) hide the
   value entirely instead of relying on string-matching masks.

   Cancelled outcomes pass through (no value attached). Non-tainted
   outcomes pass through unchanged."
  [fn-id outcome]
  (if (tainted-fn? fn-id)
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


(def ^:private poisoning-capture-classes
  "Capture classes whose frame OUTPUT taints its consumers (mirrors
   compile-eager's `poisons-ancestors?`): a declaredly-secret return,
   or fail-closed absence of type information."
  #{:secret-output :unknown})


(defn re-redact-path-trace
  "READ-time re-redaction of a persisted `:path-trace` (the
   jsonb-roundtripped shape: keyword keys, stringified ids). Capture-
   time redaction is final for what it HID — a value never captured
   can't resurface — but not for what it captured: a fn whose type
   became secret AFTER the run (marker added, type edited, registry
   entry gone) must not keep serving its historical captured values
   from old rows.

   Re-classifies every entry via `registry/trace-capture-class`
   (id-only — no authored ref-name survives into the row, so the
   stale-name rescue doesn't apply and an id with no current registry
   entry redacts FAIL-CLOSED as `:unknown-type`), then re-poisons the
   `:parent-seq` ancestor chain of every hidden poisoning frame —
   their values derive from the hidden output. Pre-tree traces (no
   `:seq` links) redact only the re-classified entries themselves.

   Idempotent; cheap for the common case (every entry `:plain`, no
   allocation beyond the pass)."
  [pt]
  (let [entries (:entries pt)]
    (if (empty? entries)
      pt
      (let [class-of (memoize
                       (fn [id-str]
                         (registry/trace-capture-class
                           (some-> id-str str request/parse-uuid-or-clear)
                           nil)))
            pairs (mapv (fn [e]
                          (let [cls (class-of (:fn-id e))
                                poisons? (contains? poisoning-capture-classes cls)]
                            (cond
                              ;; already hidden at capture — keep as-is,
                              ;; but its CURRENT class still decides
                              ;; whether it taints ancestors on read
                              (contains? e :hidden) [e poisons?]
                              (= :plain cls) [e false]
                              :else [(-> e
                                         (dissoc :value :value-truncated?
                                                 :value-hidden :duration-ms
                                                 :cache-hit?)
                                         (assoc :hidden (if (= :unknown cls)
                                                          :unknown-type
                                                          :secret)))
                                     poisons?])))
                        entries)
            entries' (mapv first pairs)
            poison-roots (into #{}
                               (comp (filter second)
                                     (keep #(:seq (first %))))
                               pairs)]
        (if (empty? poison-roots)
          (assoc pt :entries entries')
          (let [by-seq (into {}
                             (keep (fn [e]
                                     (when (some? (:seq e)) [(:seq e) e])))
                             entries')
                poison-ancestors (loop [work poison-roots, acc #{}]
                                   (if-let [s (first work)]
                                     (let [p (:parent-seq (get by-seq s))]
                                       (recur (cond-> (disj work s)
                                                (and (some? p) (not (contains? acc p)))
                                                (conj p))
                                              (if (contains? poison-roots s)
                                                acc     ; roots themselves stay as classified
                                                (conj acc s))))
                                     acc))]
            (assoc pt :entries
                   (mapv (fn [e]
                           (if (and (contains? poison-ancestors (:seq e))
                                    (not (contains? e :hidden)))
                             (-> e
                                 (dissoc :value :value-truncated?)
                                 (assoc :value-hidden :secret-derived))
                             e))
                         entries'))))))))


(def tenant-visible-error-type-namespaces
  "Error `:type` namespaces a tenant may see verbatim — errors the tenant's
   own graph/input CAUSED and can act on (docs/ERROR_CODES.md). Everything
   else — `:config-error/*` (infra) and any exception WITHOUT a `:type`
   (raw JDBC/IO/runtime, whose messages can carry SQL text, connection
   strings, file paths) — is internal and gets the ref-envelope instead."
  #{"constraint-violation" "execution" "execution-error" "validation-error"
    "recursion-error" "parse-error" "refinement" "authz" "type-check"
    "domain" "graph-error"})


(defn scrub-outcome
  "Tenant error envelope (LAUNCH_PLAN stage 1.3). When
   `cr/*scrub-internal-errors?*` is bound true and the outcome is a
   failure whose `:error-data :type` is NOT tenant-visible, replace
   `:error`/`:error-data` with an opaque reference and log the full
   outcome server-side under it. Runs AFTER `redact-outcome` — a
   `:secret`-tainted failure is already generic and short-circuits."
  [fn-name outcome]
  (if (and cr/*scrub-internal-errors?*
           (= :failed (:status outcome))
           (not (:tainted? outcome)))
    (let [t (:type (:error-data outcome))]
      (if (and (keyword? t)
               (contains? tenant-visible-error-type-namespaces (namespace t)))
        outcome
        (let [ref (str (random-uuid))]
          (log/error "Scrubbed tenant-facing execution error"
                     {:ref ref :fn fn-name
                      :error (:error outcome)
                      :error-data (:error-data outcome)})
          (assoc outcome
                 :error (str "Internal error, ref: " ref)
                 :error-data {:reason :internal :ref ref}))))
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
               (assoc :touched-secret? true)
               (:path-trace outcome)
               (assoc :path-trace (:path-trace outcome)))]
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
;; - GLOBAL (`*max-concurrent-executions*`): per-POD. Enforced by the bounded
;;   execution POOL below (`make-execution-pool`), NOT this atom: at the
;;   thread cap executions PARK in the pool queue, and only a full queue
;;   refuses (503). The atom still TRACKS `:total` for observability but no
;;   longer rejects on it.
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
  "Global, PER-POD cap on concurrently-RUNNING fn-executions. Sizes the
   bounded execution pool's worker count (`make-execution-pool`); overflow
   PARKS in the pool queue rather than being rejected. NOT an admission
   reject anymore — see `acquire-execution-slot!` (per-org only) and the
   bounded-pool section below."
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

   Per-org enforcement is TWO-LAYER: the per-pod atom cap (below) applies to
   EVERY org — including tenants — bounding an org to
   `*max-concurrent-executions-per-org*` concurrent executions ON THIS POD,
   whether or not they persist a `:fn-execution` row. `tenant?` additionally
   gates FLEET-WIDE on the pending-row count (`over-fleet-org-cap?` below), the
   cross-pod bound for persisted runs. The atom tallies ALL of the org's live
   executions (inline pure + async) and is released in `run-future`'s `finally`.

   Before this, the atom check was short-circuited for tenants (`or tenant?`),
   so a flood of PURE / non-persisted executions (which write no `:fn-execution`
   row) were counted by neither cap and let one tenant monopolise the shared
   pool + queue — the isolation break `docs/SCALING.md` claims is prevented."
  [storage org tenant?]
  (let [[old new] (swap-vals!
                    live-executions
                    (fn [{:keys [by-org] :as st}]
                      ;; PER-ORG fairness — the GLOBAL per-pod bound moved to the
                      ;; bounded execution pool (park-then-503), so this no longer
                      ;; rejects at `*max-concurrent-executions*`. The per-org
                      ;; atom cap applies to every org (tenants gate ADDITIONALLY
                      ;; on the fleet count below). `:total` tracked for
                      ;; observability.
                      (if (< (get by-org org 0) *max-concurrent-executions-per-org*)
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


;; =============================================================================
;; Bounded execution pool — QUEUE, don't reject (P1.2).
;;
;; Executions used to run on Clojure's UNBOUNDED soloExecutor, gated only by
;; the atom cap above (`*max-concurrent-executions*`): the 129th concurrent
;; run was REJECTED (HTTP 429). Operators asked for the opposite — queue the
;; work, don't drop it. This bounded `ThreadPoolExecutor` is that queue:
;;
;;   - `*max-concurrent-executions*` worker threads run at once (the GLOBAL
;;     per-pod concurrency bound — the atom above now enforces only the
;;     PER-ORG fairness slice);
;;   - `*max-execution-queue*` executions PARK in the queue at the thread cap
;;     (core == max, so overflow queues instead of spawning threads);
;;   - only when the QUEUE itself fills does submission refuse
;;     (`RejectedExecutionException` → HTTP 503 + Retry-After) — never an
;;     unbounded queue, which would OOM and defeat "don't crash under load".
;;
;; The returned `java.util.concurrent.Future` supports `.get`/`.cancel`/
;; `.isDone`, so `future-cancel` / `future-done?` keep working unchanged; the
;; two blocking-deref sites (`apply-execute*`, `record-completion!`) use
;; `.get`. Daemon threads: a stuck execution must never block JVM shutdown.
;; =============================================================================

(def ^:dynamic *max-execution-queue*
  "Bounded wait-queue depth in front of the execution thread pool. At the
   thread cap executions PARK here; when THIS fills, submission refuses
   (503 + Retry-After)."
  (or (some-> (System/getenv "GRAPHDEN_MAX_EXECUTION_QUEUE") parse-long) 256))


(defonce ^:private exec-thread-counter (java.util.concurrent.atomic.AtomicLong. 0))


(defn make-execution-pool
  "A bounded execution pool: `threads` core==max daemon workers draining a
   bounded `LinkedBlockingQueue` of `queue-cap`. core==max + a bounded queue
   gives park-then-reject: overflow queues, and a full queue trips the
   default AbortPolicy (`RejectedExecutionException` on submit). Idle workers
   time out (60 s) so a quiet pod isn't holding `threads` threads."
  ^java.util.concurrent.ThreadPoolExecutor [threads queue-cap]
  (let [tf (reify java.util.concurrent.ThreadFactory
             (newThread
               [_ r]
               (doto (Thread. ^Runnable r)
                 (Thread/.setDaemon true)
                 (Thread/.setName (str "gd-exec-"
                                       (java.util.concurrent.atomic.AtomicLong/.getAndIncrement
                                         exec-thread-counter))))))]
    (doto (java.util.concurrent.ThreadPoolExecutor.
            (int threads) (int threads)
            60 java.util.concurrent.TimeUnit/SECONDS
            (java.util.concurrent.LinkedBlockingQueue. (int queue-cap))
            tf)
      (java.util.concurrent.ThreadPoolExecutor/.allowCoreThreadTimeOut true))))


(defonce ^:private default-execution-pool
  (delay (make-execution-pool *max-concurrent-executions* *max-execution-queue*)))


(def ^:dynamic *execution-pool-override*
  "Test seam (mirrors `*compile-permit-override*`): bind a small
   `make-execution-pool` to assert queue-full → 503 deterministically. nil
   (production) = the shared `default-execution-pool` sized from the env
   knobs."
  nil)


(defn current-execution-pool
  "The execution pool a submit should use — the test override or the shared
   default."
  ^java.util.concurrent.ExecutorService []
  (or *execution-pool-override* @default-execution-pool))


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


(defn- execution-thunk
  "The body `run-future` hands to the pool: `(cr/execute …)` under the
   per-run dynamic bindings — cancel-check off `cancel-flag`, the
   effect-trace atom, the run's identity for cross-service tracing
   (`cr/*execution*`), and the path-trace state when the run is traced
   (explicit submit → the trace-all sentinel; ambient sample → the
   selective set keeps gating). `bound-fn*` carries the submitting
   thread's frame (tools.logging MDC, tenancy context) onto the worker."
  [ctx fn-id args {:keys [cancel-flag trace execution path-trace explicit?]}]
  (bound-fn*
    (fn []
      (binding [cr/*cancel-check*
                #(when @cancel-flag
                   (throw (InterruptedException. "execution cancelled")))
                cr/*effect-trace* trace
                cr/*execution* execution]
        (cond
          ;; Explicit trace?/capture-values? submit — the run's own
          ;; traversal is the selected subtree (trace-all sentinel).
          (and path-trace explicit?)
          (binding [cr/*path-trace* path-trace
                    ce/*traced-fn-ids* (atom ce/trace-all)]
            (cr/execute ctx fn-id args))
          ;; Ambient-sampled — the selective set keeps gating which
          ;; frames record; only the per-execution var binds.
          path-trace
          (binding [cr/*path-trace* path-trace]
            (cr/execute ctx fn-id args))
          :else
          (cr/execute ctx fn-id args))))))


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

   `opts` (optional): `:trace?` — Debug P1 execution-path capture
   opt-in from the submit body. When true, `cr/*path-trace*` is bound
   to fresh state (`ce/new-path-trace`) alongside `*effect-trace*`,
   AND `ce/*traced-fn-ids*` is bound to the `ce/trace-all` sentinel so
   every `:ref` frame of THIS execution records (Debug P2 — submitting
   fn X with `trace?` is the user explicitly selecting X's subtree,
   PHILOSOPHY § Debugging constraint 1; the capture stays bounded by
   the 10k-entry + 256 KB caps). `:capture-values?` (Debug P3, behind
   the UI's explicit confirm — constraint 3) IMPLIES `trace?` and
   additionally puts the trace state in value-capture mode (4 KB
   per-entry + 16 MB total budgets in `compile-eager`).

   Neither flag set → the AMBIENT-SAMPLING decision runs (Debug P3,
   constraint 2): when `fn-id` is in the selective `*traced-fn-ids*`
   set, `ce/ambient-sample?` draws ONCE here at binding time; a win
   binds `cr/*path-trace*` (path-only — never values) WITHOUT touching
   `*traced-fn-ids*`, so only frames of selectively-traced fns record.
   A loss (or an fn outside the set) → NO binding at all, so the
   executor's seam pays only its nil-check.

   Returns `[future trace-atom path-trace-atom]` — the reaper needs
   the trace atoms to read the captured sets after the future resolves
   (`path-trace-atom` nil unless traced or sampled)."
  ([ctx fn-id args cancel-flag release]
   (run-future ctx fn-id args cancel-flag release nil))
  ([ctx fn-id args cancel-flag release {:keys [trace? capture-values? execution-id trace-id]}]
   (let [trace (atom #{})
         ;; The run's identity for cross-service tracing: a persisted run
         ;; is its own trace root unless it was itself called into.
         execution (when execution-id
                     {:id execution-id :trace-id (or trace-id execution-id)})
         explicit? (or (true? trace?) (true? capture-values?))
         path-trace (cond
                      explicit? (ce/new-path-trace
                                  {:capture-values? (true? capture-values?)})
                      (ce/ambient-sample? fn-id) (ce/new-path-trace))
         watchdog (promise)
         bf (execution-thunk ctx fn-id args
                             {:cancel-flag cancel-flag :trace trace
                              :execution execution :path-trace path-trace
                              :explicit? explicit?})
         ;; Submit to the BOUNDED execution pool (P1.2) — not Clojure's
         ;; unbounded soloExecutor. A saturated pool (all workers busy AND the
         ;; queue full) throws `RejectedExecutionException` HERE, on the
         ;; submitting thread, which `apply-execute*` maps to 503 + Retry-After.
         ;; The returned j.u.c.Future supports `.get`/`.cancel`/`.isDone`.
         ;; FutureTask + execute, NOT `.submit`: submit's Runnable/Callable
         ;; overload pick rode on the ^Callable hint, which coverage
         ;; instrumentation erases — the reflective submit(Runnable)'s
         ;; Future.get() returns null by contract, nulling every executed
         ;; result under `bb coverage` (same class as abort-shield/run!).
         ;; RejectedExecutionException still throws HERE, on the
         ;; submitting thread — execute shares submit's saturation
         ;; behaviour on a bounded pool.
         fut (let [ft (java.util.concurrent.FutureTask.
                        ^Callable
                        (fn []
                          (try
                            (bf)
                            (finally
                              ;; @watchdog blocks only until the request thread
                              ;; delivers it (microseconds after creation).
                              (some-> @watchdog (java.util.concurrent.ScheduledFuture/.cancel false))
                              (when release (release))))))]
               (java.util.concurrent.ExecutorService/.execute
                 (current-execution-pool) ft)
               ft)]
     (deliver watchdog (arm-deadline! *max-execution-wall-ms* cancel-flag fut))
     [fut trace path-trace])))


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


(defn bump-usage!
  "Rollup increment for one terminal execution (Phase C1). `stats-ctx` is
   `{:pool raw-datasource :org org-or-nil :start-ms submit-epoch-ms}` (nil →
   no-op: caller had no pool). Duration = now - start-ms when known."
  [stats-ctx fn-id status]
  (when-let [pool (:pool stats-ctx)]
    (stats/bump! pool {:org (:org stats-ctx)
                       :fn-id fn-id
                       :status status
                       :duration-ms (when-let [t0 (:start-ms stats-ctx)]
                                      (- (System/currentTimeMillis) (long t0)))})))


(defn record-completion!
  "Background handler: when future resolves (success/fail/interrupt),
   write outcome to the row + clean up registry. `trace-atom` is the
   `*effect-trace*` atom from `run-future`; we snapshot it onto the
   row's `:runtime-effects` field alongside the terminal status, and
   warn-log if it diverges from `declared-effects`. `path-trace-atom`
   (nil-able) is the `*path-trace*` atom when the submission opted in
   via `trace?` — snapshotted onto `:path-trace` the same way.

   `fn-id` is consulted by `redact-outcome` (via the id-keyed registry)
   to hide the result body when the fn-def's effective return-type is
   `:secret`-marked. This is the async-completion path; the sync
   inline-success path in `apply-execute` redacts independently. Both
   write the same shape to the row."
  ([storage execution-id fn-id fut trace-atom declared-effects]
   (record-completion! storage execution-id fn-id fut trace-atom declared-effects nil nil))
  ([storage execution-id fn-id fut trace-atom declared-effects stats-ctx]
   (record-completion! storage execution-id fn-id fut trace-atom declared-effects stats-ctx nil))
  ([storage execution-id fn-id ^java.util.concurrent.Future fut trace-atom declared-effects stats-ctx path-trace-atom]
   (future
     (try
       (let [result (java.util.concurrent.Future/.get fut)
             runtime-eff (snapshot-runtime-effects trace-atom)
             path (snapshot-path-trace path-trace-atom)]
         (log-effect-drift! execution-id declared-effects runtime-eff)
         (write-finished! storage execution-id
                          (->> (cond-> {:status :succeeded :result result}
                                 runtime-eff (assoc :runtime-effects runtime-eff)
                                 path (assoc :path-trace path))
                               (stamp-touched-secret fn-id)
                               (redact-outcome fn-id)
                               (scrub-outcome fn-id)))
         (bump-usage! stats-ctx fn-id :succeeded))
       (catch java.util.concurrent.ExecutionException ee
         (let [cause (java.util.concurrent.ExecutionException/.getCause ee)
               runtime-eff (snapshot-runtime-effects trace-atom)
               path (snapshot-path-trace path-trace-atom)]
           (log-effect-drift! execution-id declared-effects runtime-eff)
           (if (instance? InterruptedException cause)
             (do (write-finished! storage execution-id
                                  (cond-> {:status :cancelled}
                                    runtime-eff (assoc :runtime-effects runtime-eff)
                                    path (assoc :path-trace path)))
                 (bump-usage! stats-ctx fn-id :cancelled))
             (do (write-finished! storage execution-id
                                  (->> (cond-> {:status :failed
                                                :error (or (ex-message cause) (str cause))
                                                :error-data (when (ex-data cause)
                                                              (ex-data cause))}
                                         runtime-eff (assoc :runtime-effects runtime-eff)
                                         path (assoc :path-trace path))
                                       (stamp-touched-secret fn-id)
                                       (redact-outcome fn-id)
                                       (scrub-outcome fn-id)))
                 (bump-usage! stats-ctx fn-id :failed)))))
       (catch java.util.concurrent.CancellationException _
         (let [path (snapshot-path-trace path-trace-atom)]
           (write-finished! storage execution-id
                            (cond-> {:status :cancelled}
                              path (assoc :path-trace path))))
         (bump-usage! stats-ctx fn-id :cancelled))
       (catch Exception e
         (log/warn e "Unexpected error reaping execution" execution-id))
       (finally
         (unregister-future! execution-id))))))

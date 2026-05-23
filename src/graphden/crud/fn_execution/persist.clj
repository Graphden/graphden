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
    [graphden.storage.protocol.core :as sp]))


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


;; =============================================================================
;; Truncation / JSON-size enforcement
;; =============================================================================

(defn truncate-error
  [s]
  (let [s (str s)]
    (if (<= (count s) max-error-chars)
      s
      (str (subs s 0 max-error-chars) "…"))))


(defn jsonize-result
  "Serialize result to test size cap. Returns `[ok? value-or-nil]` —
   `[true result]` when within cap, `[false nil]` when oversize."
  [result]
  (let [json-str (try (json/generate-string result)
                      (catch Exception _ ""))]
    (if (<= (count json-str) max-result-bytes)
      [true result]
      [false nil])))


(defn jsonize-error-data
  [data]
  (let [json-str (try (json/generate-string data)
                      (catch Exception _ "null"))]
    (if (<= (count json-str) max-error-data-bytes)
      data
      ;; Best-effort: try to keep just the :type key (canonical
      ;; error-code), drop the rest.
      {:type (:type data) :truncated true})))


(defn args-bytes
  [args]
  (count (json/generate-string args)))


(defn ref-arg?
  [v]
  (and (map? v) (contains? v :ref)))


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
      (let [ref-fn-id (some-> (:ref v) request/parse-uuid-or-clear)
            version-id (some->> ref-fn-id (lookup/resolve-fn-version-id
                                            {:storage storage}))]
        (sp/create-entity storage :fn-execution-arg
                          {:execution-id execution-id
                           :slot-id slot-id
                           :value nil
                           :ref-fn-version-id version-id}))

      ;; List — create the arg row + per-item rows
      (sequential? v)
      (let [arg-row (sp/create-entity storage :fn-execution-arg
                                      {:execution-id execution-id
                                       :slot-id slot-id
                                       :value nil
                                       :ref-fn-version-id nil})]
        (doseq [[idx item] (map-indexed vector v)]
          (cond
            (ref-arg? item)
            (let [ref-fn-id (some-> (:ref item) request/parse-uuid-or-clear)
                  version-id (some->> ref-fn-id
                                      (lookup/resolve-fn-version-id
                                        {:storage storage}))]
              (sp/create-entity storage :fn-execution-arg-item
                                {:execution-arg-id (:id arg-row)
                                 :position idx
                                 :value nil
                                 :ref-fn-version-id version-id}))
            :else
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


(defn write-finished!
  "Update an existing row with the future's outcome.
   `outcome` is one of:
     {:status :succeeded :result V [:runtime-effects [\"env\" …]]}
     {:status :failed :error E :error-data ED [:runtime-effects …]}
     {:status :cancelled [:runtime-effects …]}"
  [storage execution-id outcome]
  (let [base {:finished-at (java.time.Instant/now)
              :status (:status outcome)}
        body (case (:status outcome)
               :succeeded (let [[ok? v] (jsonize-result (:result outcome))]
                            (assoc base
                                   :result v
                                   :result-truncated? (not ok?)))
               :failed (assoc base
                              :error (truncate-error (:error outcome))
                              :error-data (jsonize-error-data
                                            (:error-data outcome)))
               :cancelled base)
        body (cond-> body
               (:runtime-effects outcome)
               (assoc :runtime-effects (:runtime-effects outcome)))]
    (sp/update-entity storage :fn-execution execution-id body)))


;; =============================================================================
;; Future submission + completion reaping
;; =============================================================================

(defn run-future
  "Submit `(executor/execute …)` to a future bound to a cancel-flag
   AND a fresh effect-trace atom. The executor's `*cancel-check*`
   dyn-var is bound to a closure that reads the atom (on flip the next
   caller→callee transition throws); `*effect-trace*` is bound to an
   atom-set that effectful base-fn impls conj into via `record-effect!`.

   Returns `[future trace-atom]` — the reaper needs the trace atom to
   read the captured effect set after the future resolves."
  [ctx fn-id args cancel-flag]
  (let [trace (atom #{})
        bf (bound-fn* ; capture clojure.tools.logging MDC etc.
            (fn []
              (binding [cr/*cancel-check*
                        #(when @cancel-flag
                           (throw (InterruptedException. "execution cancelled")))
                        cr/*effect-trace* trace]
                (cr/execute ctx fn-id args))))]
    [(future (bf)) trace]))


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
   warn-log if it diverges from `declared-effects`."
  [storage execution-id ^java.util.concurrent.Future fut trace-atom declared-effects]
  (future
    (try
      (let [result @fut
            runtime-eff (when trace-atom
                          (some-> @trace-atom seq (->> (mapv name))))]
        (log-effect-drift! execution-id declared-effects runtime-eff)
        (write-finished! storage execution-id
                         (cond-> {:status :succeeded :result result}
                           runtime-eff (assoc :runtime-effects runtime-eff))))
      (catch java.util.concurrent.ExecutionException ee
        (let [cause (java.util.concurrent.ExecutionException/.getCause ee)
              runtime-eff (when trace-atom
                            (some-> @trace-atom seq (->> (mapv name))))]
          (log-effect-drift! execution-id declared-effects runtime-eff)
          (if (instance? InterruptedException cause)
            (write-finished! storage execution-id
                             (cond-> {:status :cancelled}
                               runtime-eff (assoc :runtime-effects runtime-eff)))
            (write-finished! storage execution-id
                             (cond-> {:status :failed
                                      :error (or (ex-message cause) (str cause))
                                      :error-data (when (ex-data cause)
                                                    (ex-data cause))}
                               runtime-eff (assoc :runtime-effects runtime-eff))))))
      (catch java.util.concurrent.CancellationException _
        (write-finished! storage execution-id {:status :cancelled}))
      (catch Exception e
        (log/warn e "Unexpected error reaping execution" execution-id))
      (finally
        (unregister-future! execution-id)))))

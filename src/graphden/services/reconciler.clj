(ns graphden.services.reconciler
  "Service-registry reconciler — diff `:service` rows (desired state)
   against `running` (actual state in-process), start missing /
   stop removed.

   Phase 1 single-pod model. Reconciliation is poll-driven (a
   `ScheduledExecutorService` ticks every `:period-ms`); the
   integrant component owns the scheduler + `running` atom and
   delegates the per-tick work to `reconcile-once!`.

   The `running` atom shape is
     `{service-id → {:fn-id … :stopper (fn []) :started-at Instant}}`.
   `:stopper` is the value the started fn returned. Web-server-shaped
   fns (http-kit) return a thunk that stops the listener; arbitrary
   fns may return anything — we only call `:stopper` if it's callable.

   The reconciler is intentionally side-effect-y but the policy
   decisions (which IDs to start/stop) are pure and tested via
   `diff-desired`."
  (:require
    [clojure.math]
    [clojure.set]
    [clojure.tools.logging :as log]
    [graphden.executor.compile-runtime :as cr]
    [graphden.storage.protocol.core :as sp]))


;; =============================================================================
;; Production singleton — set on integrant init, drained on halt, read
;; by the /api/services/reconcile endpoint. Tests construct their own
;; atoms and pass to reconcile-once! / stop-all! directly; this
;; defonce is purely the production handle (mirrors the
;; futures-registry pattern in `fn-execution.persist`).
;; =============================================================================

(defonce running
  (atom {}))


;; Phase 1 legacy-fallback handle — when no :service rows exist on
;; boot, the integrant init-key starts the package-declared
;; `:startup-fn` and stashes its `{:fn-id :stopper}` pair here. Two
;; consumers read it:
;;   - `validate-execute`'s `already-running-as-service?` rejects
;;     ad-hoc Run on the same fn (UX protection against the "click ▶
;;     on :web-server while it's bound" foot-gun)
;;   - `reconcile-once!`'s displacement step stops the fallback when
;;     a matching managed service appears, freeing the port
;; nil when no fallback is active. Drops away when Phase 2 retires the
;; fallback path entirely.
(defonce legacy-handle
  (atom nil))


;; =============================================================================
;; Pure: compute the start/stop set from desired (DB rows) vs running.
;; =============================================================================

(defn diff-desired
  "Pure: return `{:to-start [service-id …] :to-stop [service-id …]}`
   given the set of enabled service IDs (from DB) and the set of
   currently-running service IDs (from the in-process atom).

   `to-start` = enabled ∖ running (rows that exist + enabled but
   we haven't started yet).
   `to-stop` = running ∖ enabled (rows we're running that have been
   disabled, deleted, or never existed). Disabled rows are stopped
   alongside deletions — uniform shutdown path."
  [enabled-ids running-ids]
  (let [enabled-set (set enabled-ids)
        running-set (set running-ids)]
    {:to-start (vec (clojure.set/difference enabled-set running-set))
     :to-stop  (vec (clojure.set/difference running-set enabled-set))}))


;; =============================================================================
;; Start / stop a single service.
;;
;; Services don't carry args of their own — the fn pointed at by
;; `:fn-id` is expected to have ZERO free arguments (every slot bound
;; via fn-defs / bindings). To run the same impl with different
;; parameters, create a derived fn-def that binds the slots
;; differently and declare a :service for THAT fn. See
;; `schema/services/schema.clj` for the full rationale.
;; =============================================================================

(defn- start-service-once!
  "One attempt: invoke the fn via the executor. Returns the stopper
   (fn's return value) on success, nil on exception. The fn is called
   synchronously — any startup throw (port-in-use, etc.) is caught
   and surfaced as a nil stopper. Long-running fns block INSIDE the
   impl, not at this call site (web-server returns a stopper thunk
   immediately)."
  [ctx fn-id args svc-id]
  (try
    (log/info "service start" svc-id "fn-id" fn-id)
    (cr/execute ctx fn-id args)
    (catch Exception e
      (log/error e "service start failed" svc-id "fn-id" fn-id)
      nil)))


;; Supervisor retry tuning. Bounded to keep reconcile-once! responsive
;; — even max retries finishes inside ~7s (1+2+4). For Phase 1 this
;; only catches STARTUP failures (e.g. port-in-use); runtime crashes
;; aren't detected (no healthcheck) so `:always` ≡ `:on-failure` in
;; behaviour. Tunable per-call so tests can pin to zero-backoff.
(def ^:private default-max-retries 3)
(def ^:private default-backoff-ms 1000)


(defn- should-retry?
  "Whether `policy` wants another start attempt after a failure."
  [policy]
  ;; Both :always and :on-failure retry on start-exception. :never
  ;; gives up after the first attempt. Future phases distinguish
  ;; :always (also restart on clean stop) once we have a runtime
  ;; watcher.
  (contains? #{:always :on-failure} policy))


(defn start-service!
  "Run the service's fn through the supervisor: try `start-service-once!`,
   on nil-stopper (start failure) sleep + retry up to N times per the
   row's `:restart-policy`. Returns the `running`-atom entry shape:
   `{:fn-id :restart-policy :stopper :started-at :start-attempts}`.

   `:start-attempts` records how many tries it took (1 = success on
   first attempt). When all retries are exhausted, `:stopper` is nil
   and `:start-failed-at` is set — reconcile keeps the entry so we
   don't busy-loop trying to start again on every reconcile pass.

   The fn is invoked with an empty args map — services require the
   target fn to have no free args (enforced at service-create time)."
  ([ctx svc] (start-service! ctx svc {}))
  ([ctx svc {:keys [max-retries backoff-ms]
             :or {max-retries default-max-retries
                  backoff-ms default-backoff-ms}}]
   (let [fn-id (:fn-id svc)
         args {}
         policy (:restart-policy svc)
         max-attempts (if (should-retry? policy) (inc max-retries) 1)]
     (loop [attempt 1]
       (let [stopper (start-service-once! ctx fn-id args (:id svc))]
         (cond
           ;; Success — return entry; clear start-failed-at if it was set.
           (some? stopper)
           {:fn-id fn-id
            :restart-policy policy
            :stopper stopper
            :started-at (java.time.Instant/now)
            :start-attempts attempt}

           ;; Exhausted retries — give up, record the give-up time so
           ;; admin can tell from the running map that we're stuck.
           (>= attempt max-attempts)
           (do (when (> attempt 1)
                 (log/error "service start exhausted retries"
                            {:service-id (:id svc) :attempts attempt}))
               {:fn-id fn-id
                :restart-policy policy
                :stopper nil
                :started-at (java.time.Instant/now)
                :start-attempts attempt
                :start-failed-at (java.time.Instant/now)})

           ;; Retry — exponential backoff (1s, 2s, 4s, …).
           :else
           (let [delay-ms (* (long backoff-ms) (long (clojure.math/pow 2 (dec attempt))))]
             (log/warn "service start failed, retrying"
                       {:service-id (:id svc)
                        :attempt attempt
                        :next-delay-ms delay-ms
                        :policy policy})
             (Thread/sleep delay-ms)
             (recur (inc attempt)))))))))


(defn stop-service!
  "Best-effort stop: call the stopper if it's a fn (http-kit and
   similar return a callable). Other return values are logged and
   dropped — the service won't have an in-process effect to undo."
  [service-id {:keys [stopper] :as entry}]
  (try
    (cond
      (fn? stopper)
      (do (log/info "service stop" service-id)
          (stopper))

      (nil? stopper)
      (log/info "service stop" service-id "(no stopper — start had failed)")

      :else
      (log/warn "service stop" service-id
                "could not stop — fn returned non-callable"
                (type stopper)))
    (catch Exception e
      (log/error e "service stop threw" service-id)))
  entry)


;; =============================================================================
;; One reconciliation pass — read desired, compute diff, apply.
;; =============================================================================

(defn- maybe-displace-legacy!
  "Phase 1: if any enabled service's :fn-id matches the legacy
   fallback's :fn-id, stop the fallback so its port frees up before
   the managed service tries to start. Idempotent — the legacy-handle
   is cleared after the first matching reconcile pass.

   Returns true when displacement happened (for the caller's summary)."
  [enabled-services]
  (when-let [handle @legacy-handle]
    (when (some #(= (:fn-id %) (:fn-id handle)) enabled-services)
      (log/info "managed service for legacy-fallback fn-id detected — stopping legacy"
                {:fn-id (:fn-id handle)})
      (stop-service! ::legacy-fallback {:stopper (:stopper handle)})
      (reset! legacy-handle nil)
      true)))


(defn reconcile-once!
  "One pass: read enabled `:service` rows, compute diff vs
   `running-atom`'s contents, start missing + stop removed. Mutates
   `running-atom` in place.

   If a managed service is declared for the same fn-id as the Phase 1
   legacy fallback, the fallback is stopped FIRST (before start-service!
   runs) so its port is free. Reflected in the return as
   `:legacy-displaced? true`.

   `start-opts` (optional) is passed straight to `start-service!`,
   e.g. `{:max-retries 0 :backoff-ms 0}` keeps tests responsive when
   they intentionally cause start failures.

   Returns `{:started [service-id …] :stopped [service-id …]
              :legacy-displaced? bool}` for logging / tests."
  ([ctx running-atom]
   (reconcile-once! ctx running-atom {}))
  ([ctx running-atom start-opts]
   (let [storage (:storage ctx)
         enabled-services (vec (sp/query-entities storage :service {:enabled? true}))
         enabled-by-id    (into {} (map (juxt :id identity)) enabled-services)
         legacy-displaced? (boolean (maybe-displace-legacy! enabled-services))
         {:keys [to-start to-stop]} (diff-desired (keys enabled-by-id)
                                                  (keys @running-atom))]
     (doseq [sid to-stop]
       (let [entry (get @running-atom sid)]
         (when entry (stop-service! sid entry))
         (swap! running-atom dissoc sid)))
     (doseq [sid to-start]
       (let [svc (get enabled-by-id sid)
             entry (start-service! ctx svc start-opts)]
         (swap! running-atom assoc sid entry)))
     {:started to-start :stopped to-stop
      :legacy-displaced? legacy-displaced?})))


(defn stop-all!
  "Shutdown helper — drains `running-atom` by calling every stopper,
   clears the atom. Called from the integrant `halt-key!`."
  [running-atom]
  (doseq [[sid entry] @running-atom]
    (stop-service! sid entry))
  (reset! running-atom {}))

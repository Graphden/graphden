(ns graphden.monitoring.alerts
  "Built-in domain alerting (Phase C3) — the PURE decision half.

   `decide` takes the current per-org usage totals + the process-wide
   server-error delta + the prior fire-state, and returns the alerts to send
   now plus the next state (cooldown bookkeeping). No IO, no wall-clock read —
   `now-ms` is passed in — so the whole policy is unit-testable and the
   scheduler around it (`system.init.alerter`) stays a thin shell.

   Two rules, both privacy-safe (org name + counts only — never args/results):
   - per-org error SPIKE: over the window, failed/runs ≥ `error-ratio` with at
     least `min-runs` runs.
   - process-wide server errors: `server-error-delta` ≥ `server-error-min`
     since the last check (an operational, not per-tenant, signal).

   Cooldown: an org (or the process key) that already alerted is silent until
   `cooldown-ms` passes, so a sustained incident pages once, not every tick."
  (:require
    [clojure.string :as str]))


(def default-config
  {:error-ratio 0.5      ; ≥50% of a window's runs failing …
   :min-runs 10          ; … over at least this many runs (ignore low-volume noise)
   :server-error-min 20  ; process-wide 5xx count since last check
   :cooldown-ms 3600000  ; re-alert the same key at most hourly
   :window-mins 60})


(defn- alert-key
  [a]
  (if (= :server-errors (:kind a)) "__server__" (str "org:" (:org a))))


(defn decide
  "Pure alert policy. Returns `{:fire [alert…] :state new-state}`.

   `inputs`  — `{:org-totals [{:org :runs :failed}…] :server-error-delta n}`.
   `state`   — `{key → last-fired-ms}` (opaque; thread the returned :state back).
   `cfg`     — merged over `default-config`.
   `now-ms`  — injected clock.

   An alert is `{:kind :error-spike|:server-errors :org? :runs? :failed?
   :ratio? :count? :message}`. Cooldown-suppressed keys are simply absent from
   `:fire`; their prior timestamp carries forward in `:state`."
  [{:keys [org-totals server-error-delta]} state cfg now-ms]
  (let [{:keys [error-ratio min-runs server-error-min cooldown-ms]}
        (merge default-config cfg)
        state (or state {})
        candidates
        (cond-> (for [{:keys [org runs failed]} org-totals
                      :when (and (>= runs min-runs)
                                 (>= (/ (double failed) (double runs)) error-ratio))]
                  {:kind :error-spike :org org :runs runs :failed failed
                   :ratio (/ (double failed) (double runs))
                   :message (format "org %s: %d/%d runs failed (%.0f%%) in the last window"
                                    org failed runs
                                    (* 100.0 (/ (double failed) (double runs))))})
          (and server-error-delta (>= server-error-delta server-error-min))
          (conj {:kind :server-errors :count server-error-delta
                 :message (format "%d server errors (5xx) since the last check"
                                  server-error-delta)}))
        ;; Drop any candidate still inside its cooldown window.
        fresh (remove (fn [a]
                        (when-let [t (get state (alert-key a))]
                          (< (- now-ms t) cooldown-ms)))
                      candidates)
        new-state (reduce (fn [st a] (assoc st (alert-key a) now-ms))
                          state fresh)]
    {:fire (vec fresh) :state new-state}))


(defn summary-text
  "One human line for a batch of fired alerts — the webhook payload body."
  [alerts]
  (str "⚠️ graphden alert — "
       (str/join "; " (map :message alerts))))

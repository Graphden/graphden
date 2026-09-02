(ns graphden.monitoring.alerts
  "Built-in domain alerting (Phase C3) — the PURE decision half.

   `decide` takes the current per-org usage totals + the process-wide
   server-error delta + the prior fire-state, and returns the alerts to send
   now plus the next state (cooldown bookkeeping). No IO, no wall-clock read —
   `now-ms` is passed in — so the whole policy is unit-testable and the
   scheduler around it (`system.init.alerter`) stays a thin shell.

   Three rules, all privacy-safe (org name + counts only — never args/results;
   feedback text is what the reporter deliberately typed for the operator):
   - per-org error SPIKE: over the window, failed/runs ≥ `error-ratio` with at
     least `min-runs` runs.
   - process-wide server errors: `server-error-delta` ≥ `server-error-min`
     since the last check (an operational, not per-tenant, signal).
   - NEW FEEDBACK reports (`:feedback`, the rows the open `POST /api/feedback`
     intake stored since the last tick — docs/MONITORING.md § 4). This is the
     ONLY notification path for the intake: the request handler itself must
     stay inside the cloud's request-level effect gate (no `:env`, no
     `:network`), and the Telegram token is a secret that belongs here, in
     Clojure config, never in a graph read.

   Cooldown: an org (or the process key) that already alerted is silent until
   `cooldown-ms` passes, so a sustained incident pages once, not every tick.
   Feedback is exempt — every report is one event, and the tick already
   batches them; suppressing would silently drop reports."
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


(def ^:private feedback-clip 300)


(defn feedback-message
  "One line per report for the operator ping: `📮 Feedback (bug): text…` —
   category is the intake's validated label, text clipped so one long
   report can't crowd out the rest of the batch."
  [{:keys [category body]}]
  (let [text (str body)]
    (str "📮 Feedback (" category "): "
         (if (> (count text) feedback-clip)
           (str (subs text 0 feedback-clip) "…")
           text))))


(defn decide
  "Pure alert policy. Returns `{:fire [alert…] :state new-state}`.

   `inputs`  — `{:org-totals [{:org :runs :failed}…] :server-error-delta n
                :feedback [{:category :body}…]}` (`:feedback` optional — the
                intake's new rows since the last tick).
   `state`   — `{key → last-fired-ms}` (opaque; thread the returned :state back).
   `cfg`     — merged over `default-config`.
   `now-ms`  — injected clock.

   An alert is `{:kind :error-spike|:server-errors|:feedback :org? :runs?
   :failed? :ratio? :count? :message}`. Cooldown-suppressed keys are simply
   absent from `:fire`; their prior timestamp carries forward in `:state`.
   `:feedback` alerts never enter the cooldown state."
  [{:keys [org-totals server-error-delta feedback]} state cfg now-ms]
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
                          state fresh)
        feedback-alerts (when (seq feedback)
                          [{:kind :feedback :count (count feedback)
                            :message (str/join "\n" (map feedback-message feedback))}])]
    {:fire (into (vec fresh) feedback-alerts) :state new-state}))


(defn summary-text
  "One human line for a batch of fired alerts — the webhook payload body."
  [alerts]
  (str "⚠️ graphden alert — "
       (str/join "; " (map :message alerts))))


(defn alert-request
  "PURE channel selection — given the delivery `cfg` and a message `text`,
   return the `{:url … :body <clojure-map>}` to POST, or `nil` when nothing is
   configured (scheduler stays off).

   Two channels, Telegram taking precedence when its pair is present:
   - Telegram: `:telegram-token` + `:telegram-chat` → the Bot API
     `sendMessage` endpoint with `{:chat_id :text}` (Telegram rejects a bare
     `{text}` — it needs the chat id in-band).
   - generic webhook: `:webhook-url` → `{:text text}` (Slack / Mattermost /
     any JSON `{…}` sink).

   Kept pure (no HTTP, no env) so the routing is unit-testable; the scheduler
   shell does the actual POST."
  [{:keys [webhook-url telegram-token telegram-chat]} text]
  (cond
    (and (not (str/blank? telegram-token)) (not (str/blank? telegram-chat)))
    {:url (str "https://api.telegram.org/bot" telegram-token "/sendMessage")
     :body {:chat_id telegram-chat :text text}}

    (not (str/blank? webhook-url))
    {:url webhook-url :body {:text text}}

    :else nil))

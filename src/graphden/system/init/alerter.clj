(ns graphden.system.init.alerter
  "Built-in domain alerter (Phase C3) — the scheduled shell around
   `graphden.monitoring.alerts/decide`.

   Every `period-ms` it reads the per-org usage totals (`stats/org-totals`,
   counts only) + the process-wide server-error counter delta, runs the pure
   policy, and POSTs any fired alerts through the channel `alerts/alert-request`
   selects — native Telegram (`GRAPHDEN_ALERT_TELEGRAM_TOKEN` +
   `GRAPHDEN_ALERT_TELEGRAM_CHAT` → Bot API `sendMessage`) or a generic
   `{text}` webhook (`GRAPHDEN_ALERT_WEBHOOK`; Slack / Mattermost). The
   fire-state (cooldown) lives in an atom across ticks.

   CONFIG-GATED: wired only when a channel is configured (either the Telegram
   pair or the webhook) — otherwise no scheduler (the domain-alert half is
   opt-in; the /metrics counters remain for an external Prometheus +
   Alertmanager either way). Best-effort: a failed read or POST logs and the
   loop continues."
  (:require
    [cheshire.core :as json]
    [clojure.tools.logging :as log]
    [graphden.crud.fn-execution.stats :as stats]
    [graphden.monitoring.alerts :as alerts]
    [graphden.util.counters :as counters]
    [integrant.core :as ig]
    [org.httpkit.client :as http]))


(defn post-alert!
  "POST the alert; returns true iff DELIVERED. httpkit does NOT throw on
   transport failure — it returns `{:error e}`, and a non-2xx as
   `{:status n}`. The old body only caught exceptions, so a refused
   connection / DNS failure / bad webhook URL / Telegram 4xx was dropped
   with no log AND (see run-once!) still burned the cooldown. Now we
   inspect `:error`/`:status` and only report success on a 2xx. A
   channel that produces no request (nothing configured) is a no-op →
   true (don't wedge the cooldown on a permanent non-delivery)."
  [channel text]
  (if-let [{:keys [url body]} (alerts/alert-request channel text)]
    (try
      (let [{:keys [status error]} @(http/post url
                                               {:headers {"Content-Type" "application/json"}
                                                :timeout 5000
                                                :body (json/generate-string body)})]
        (cond
          error (do (log/warn error "alert POST transport failure") false)
          (and (integer? status) (<= 200 status 299)) true
          :else (do (log/warn "alert POST non-2xx" {:status status}) false)))
      (catch Exception e
        (log/warn e "alert POST failed")
        false))
    true))


(defn run-once!
  "One alerter tick. `state-atom` holds `{:fired {…} :error-base n}` across
   ticks (cooldown + the server-error baseline). Pure decision delegated to
   `alerts/decide`; this only does the reads, the diff, and the POST. Returns
   the alerts it fired (for tests)."
  [pool channel cfg state-atom now-ms]
  (try
    (let [totals (stats/org-totals pool (:window-mins (merge alerts/default-config cfg)))
          err-now (get (counters/snapshot) :http/server-error 0)
          {:keys [error-base]} @state-atom
          err-delta (max 0 (- err-now (or error-base 0)))
          prev-fired (:fired @state-atom)
          {:keys [fire state]} (alerts/decide
                                 {:org-totals totals :server-error-delta err-delta}
                                 prev-fired cfg now-ms)
          ;; Deliver BEFORE committing the cooldown. Advancing `:fired`
          ;; regardless of delivery (the old order) silenced a LOST
          ;; alert's keys for the whole cooldown — a sustained incident
          ;; over a broken channel never paged. Keep the old cooldown
          ;; map when delivery fails so the alert retries next tick.
          ;; `:error-base` is a counter baseline, not a cooldown — it
          ;; advances unconditionally.
          delivered? (if (seq fire)
                       (do (log/warn "domain alert firing" {:count (count fire)})
                           (post-alert! channel (alerts/summary-text fire)))
                       true)]
      (swap! state-atom assoc
             :error-base err-now
             :fired (if delivered? state prev-fired))
      fire)
    (catch Exception e
      (log/warn e "alerter tick failed")
      nil)))


(defmethod ig/init-key :exec/alert-scheduler
  [_ {:keys [context webhook-url telegram-token telegram-chat period-ms config]}]
  (let [channel {:webhook-url webhook-url
                 :telegram-token telegram-token
                 :telegram-chat telegram-chat}]
    ;; A `nil` request means neither channel is configured → stay dormant.
    (if-not (alerts/alert-request channel "")
      (do (log/info "Alerter OFF (no GRAPHDEN_ALERT_TELEGRAM_* or GRAPHDEN_ALERT_WEBHOOK)") nil)
      (let [pool (:pool (:pg-storage context))
            period (or period-ms 300000)
            telegram? (contains? (:body (alerts/alert-request channel "")) :chat_id)
            state (atom {:fired {} :error-base (get (counters/snapshot) :http/server-error 0)})
            scheduler (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
        (log/info "Starting domain alerter —"
                  (if telegram? "Telegram" "webhook") "channel, period" period "ms")
        (java.util.concurrent.ScheduledExecutorService/.scheduleAtFixedRate
          scheduler
          ^Runnable (fn []
                      (run-once! pool channel config state
                                 (System/currentTimeMillis)))
          period period
          java.util.concurrent.TimeUnit/MILLISECONDS)
        scheduler))))


(defmethod ig/halt-key! :exec/alert-scheduler
  [_ ^java.util.concurrent.ScheduledExecutorService scheduler]
  (when scheduler
    (log/info "Stopping domain alerter...")
    (java.util.concurrent.ExecutorService/.shutdown scheduler)))

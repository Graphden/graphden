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


(defn- post-alert!
  [channel text]
  (when-let [{:keys [url body]} (alerts/alert-request channel text)]
    (try
      @(http/post url
                  {:headers {"Content-Type" "application/json"}
                   :timeout 5000
                   :body (json/generate-string body)})
      (catch Exception e
        (log/warn e "alert POST failed")))))


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
          {:keys [fire state]} (alerts/decide
                                 {:org-totals totals :server-error-delta err-delta}
                                 (:fired @state-atom) cfg now-ms)]
      (swap! state-atom assoc :fired state :error-base err-now)
      (when (seq fire)
        (log/warn "domain alert firing" {:count (count fire)})
        (post-alert! channel (alerts/summary-text fire)))
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

# Monitoring & alerting

Three layers, cheapest first. All three are **privacy-safe by
construction** — they count and time, they never read a tenant's args,
results, or error bodies (those stay in the org-scoped, TTL'd
`:fn-execution` rows, sanitised at write time).

## 1. Usage rollups — per-org / per-fn stats (always on)

Every terminal execution increments a pre-aggregated `:usage-stat` row
`(hour bucket, org, fn, status)` with a run count + summed duration
(`graphden.crud.fn-execution.stats/bump!`). Bounded by distinct keys,
not traffic; 90-day retention. Surfaced as the `7d: N runs · M failed ·
avg K ms` strip in the editor's execute-history panel
(`:usage-fn-stats`), and `org-stats` / `org-totals` for tooling. See
[EXECUTION.md § Usage rollups](EXECUTION.md).

## 2. Error log — recent failures viewer (always on)

The editor's **Errors** sidebar section lists an org's recent failed
executions (`:recent-failures` → `GET /partials/error-log`), reading the
already-scrubbed `:error`/`:error-data` off the audit rows. No new
storage. See [EXECUTION.md § Error log](EXECUTION.md).

## 3. Alerting — two complementary paths

### 3a. External: Prometheus + Alertmanager (recommended for ops)

`GET /metrics/prometheus` exposes OpenMetrics gauges, including the
runtime counters — notably `graphden_counters_http_server_error`
(uncaught 5xx, incremented at the error boundary) and the registry /
compile structural counters. Point a Prometheus at it and route
Alertmanager to your channel. Example rules (high 5xx rate, target
down) + a compose overlay ship in the cloud deployment repo
(`deploy/prometheus/` — see its README); a self-hoster scrapes the same
endpoint. This path owns infra-level alerting (availability, latency,
resource) and needs no graphden config beyond exposing `/metrics`.

### 3b. Built-in: domain alerter (opt-in webhook)

For domain conditions Prometheus doesn't naturally see, graphden ships a
small in-process alerter (`:exec/alert-scheduler`). **Dormant unless
`GRAPHDEN_ALERT_WEBHOOK` is set.** When set, every `ALERT_PERIOD_MS`
(default 5 min) it evaluates:

- **per-org error spike** — an org whose failed/total ratio over the
  last hour is ≥ `error-ratio` (default 0.5) with ≥ `min-runs`
  (default 10) runs;
- **process-wide 5xx burst** — `server-error` counter delta ≥
  `server-error-min` (default 20) since the last check.

Fired alerts POST as `{"text": "…"}` to the webhook (Slack / Mattermost
/ any generic-webhook or Telegram relay). A per-key **cooldown**
(default 1 h) means a sustained incident pages once, not every tick.
The decision policy is pure (`graphden.monitoring.alerts/decide`,
unit-tested); the scheduler (`graphden.system.init.alerter`) only does
the reads + the POST.

| env | default | meaning |
|-----|---------|---------|
| `GRAPHDEN_ALERT_WEBHOOK` | *(empty → alerter off)* | webhook the alerts POST to |
| `ALERT_PERIOD_MS` | `300000` | evaluation cadence |

Tune the thresholds via the `:exec/alert-scheduler` `:config` map if the
defaults don't fit your traffic.

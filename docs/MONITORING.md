# Monitoring & alerting

Three layers, cheapest first. All three are **privacy-safe by
construction** — they count and time, they never read a tenant's args,
results, or error bodies (those stay in the org-scoped, TTL'd
`:fn-execution` rows, sanitised at write time).

## 1. Usage rollups — per-org / per-fn stats (always on)

Every terminal execution increments a pre-aggregated `:usage-stat` row
`(hour bucket, org, fn, status)` with a run count + summed duration
(`graphden.crud.fn-execution.stats/bump!`). Bounded by distinct keys,
not traffic; 90-day retention. Surfaced three ways: the `7d: N runs
· M failed · avg K ms` strip in the editor's execute-history panel
(`:usage-fn-stats`); the editor's **Stats** sidebar section
(`GET /partials/stats` — org-scoped headline totals, per-day trend
table, top-fns table; each org sees only its own workspace, except
that a platform-tier principal or a `:view-all-stats` capability
delegate additionally gets the cross-org by-org rollup —
`usage-all-org-stats` returns `[]` for everyone else); and
`org-stats` / `org-totals` for tooling. See
[EXECUTION.md § Usage rollups](EXECUTION.md).

### Tutorial funnel (always on, aggregate only)

The interactive tutorial posts three events per lesson to
`POST /api/tour/progress` — `started`, one `step` per advance, and
`finished` — which bump process counters and therefore ride out on
`/metrics` and `/metrics/prometheus` with everything else:

```
graphden_counters_tour_started_01     # denominator
graphden_counters_tour_step_01_3      # reached step 3
graphden_counters_tour_finished_01    # numerator
```

A scrape is what makes them a funnel: the counters are process-local and
reset with the JVM, while Prometheus keeps the series across restarts
(`increase(...[7d])`). Without a scrape they still answer "since this
process started".

Nothing identifying is recorded — a two-digit lesson id, a step index,
one of three words. No account, no session, no per-user path. The route
is OPEN, because the landing demo's anonymous session is precisely the
population whose drop-off is worth knowing, and both counter base-fns
validate their input rather than trusting it: a caller cannot name a
counter (the map is process-global and unbounded, so that would be a
memory-growth vector).

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
resource).

`/metrics` and `/metrics/prometheus` are **auth-required** when auth is
active (they disclose JVM version / CPU / live load-average / restart
windows — recon material), so a Prometheus scrape must authenticate:
give the scrape job a bearer via `authorization.credentials_file` (a
file holding a valid token). When auth is OFF (no `AUTH_TOKEN` — a local
dev box) they stay open. `/health` is always public (const-only). The
cloud additionally refuses `/metrics` at the TLS edge (Caddy), so it is
never reachable publicly there regardless of auth.

### 3b. Built-in: domain alerter (opt-in — Telegram or webhook)

For domain conditions Prometheus doesn't naturally see, graphden ships a
small in-process alerter (`:exec/alert-scheduler`). **Dormant unless a
channel is configured** (the Telegram pair or the webhook). When on,
every `ALERT_PERIOD_MS` (default 5 min) it evaluates:

- **per-org error spike** — an org whose failed/total ratio over the
  last hour is ≥ `error-ratio` (default 0.5) with ≥ `min-runs`
  (default 10) runs;
- **process-wide 5xx burst** — `server-error` counter delta ≥
  `server-error-min` (default 20) since the last check.

Delivery has two native channels (`graphden.monitoring.alerts/alert-request`
picks one, Telegram winning if both are set):

- **Telegram** — set `GRAPHDEN_ALERT_TELEGRAM_TOKEN` + `_CHAT` and the
  alerter POSTs `{chat_id, text}` straight to the Bot API
  `sendMessage` endpoint. No relay. Create a bot with
  [@BotFather](https://t.me/BotFather) for the token; get the chat id
  by messaging the bot then reading
  `https://api.telegram.org/bot<TOKEN>/getUpdates` (or use a group's
  numeric `-100…` id).
- **Generic webhook** — set `GRAPHDEN_ALERT_WEBHOOK` and it POSTs
  `{"text": "…"}` (Slack / Mattermost / any JSON `{…}` sink).

A per-key **cooldown** (default 1 h) means a sustained incident pages
once, not every tick. The decision policy AND the channel selection are
pure (`graphden.monitoring.alerts`, unit-tested); the scheduler
(`graphden.system.init.alerter`) only does the reads + the POST.

| env | default | meaning |
|-----|---------|---------|
| `GRAPHDEN_ALERT_TELEGRAM_TOKEN` | *(empty)* | Bot API token — with `_CHAT`, enables native Telegram |
| `GRAPHDEN_ALERT_TELEGRAM_CHAT` | *(empty)* | target chat id (user or `-100…` group) |
| `GRAPHDEN_ALERT_WEBHOOK` | *(empty)* | generic `{text}` webhook (used when no Telegram pair) |
| `ALERT_PERIOD_MS` | `300000` | evaluation cadence |

The alerter is off only when **no** channel is configured. Tune the
thresholds via the `:exec/alert-scheduler` `:config` map if the defaults
don't fit your traffic.

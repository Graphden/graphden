# BYO executor — provisioning runbook

How a customer runs the executor on THEIR hardware while the graph stays on
the hub. Architecture and boundaries: [SCALING.md § External / BYO
executor](SCALING.md). This page is the operational checklist — the hub
operator's half, then the customer's half.

The shape in one paragraph: the org's graph lives on the hub, and the hub
keeps serving its **storage plane** (editor, CRUD, export, diff/merge) as for
any tenant. What the hub refuses for a `byo` org is **execution** —
`/api/execute*`, mutating `/api/services` calls, and the org's FaaS app
traffic all answer `421 Misdirected Request`. The customer's own executor
bootstraps the org's graph over HTTP (`GET /api/export/graph-rows`), compiles
it in memory, serves the org's app handler, and stays fresh over the hub's
SSE relay. It never writes: authoring stays in the hub editor.

The flip is **org-granular**: it moves ALL of the org's execution, not one
service. A customer who wants "this one app on my hardware, the rest hosted"
gets a SECOND org for the self-run app (accounts can belong to several orgs)
and flips only that one — see [SCALING.md § Boundaries](SCALING.md).

## Hub operator's half

Prerequisites: the tenancy addon is enabled (multi-tenant cloud), and the
org is on a **paid tier** — the byo flip is tier-gated (`network` /
`dedicated`; `tenancy.plan` `:byo-allowed?`). Flipping a free org fails with
`:org/byo-tier-required`.

1. **Enable the SSE relay** if it isn't already: set `GRAPHDEN_SSE_PORT`
   (Helm: `sse.enabled=true` — adds the env, the container port, and a
   dedicated `<release>-sse` Service). The relay speaks plain HTTP;
   terminate TLS at the ingress/LB in front of it and raise its
   idle-timeout — subscribers hold long-lived streams.
2. **Create the org** (if new) and set its plan:
   `POST /api/orgs/plan {name, plan=network}`.
3. **Mint the executor's token**: an account in the org mints a scoped API
   token via `POST /api/my-tokens` (label it, scope it — the executor only
   READS, so an execute/write-free scope set is right). On a bare
   self-hosted hub (no accounts/tenancy) the single `AUTH_TOKEN` plays this
   role instead.
4. **Flip the org**: `POST /api/orgs/execution-mode {name,
   execution-mode=byo}` (platform-only). The byo memo is dropped in the
   same call, so hosted pods start refusing the org's execution at once.
   Flip back with `execution-mode=hosted` any time — no tier needed for
   the way back.
5. **Route the org's app domain** at the customer's executor (their
   subdomain / custom domain → their box), since the hub now 421s the
   org's FaaS traffic.

## Customer's half

The BYO entrypoint lives in the standard executor image/jar — it is the
same artifact with a different main class.

From the jar / a checkout:

```bash
GRAPHDEN_HUB_URL=https://hub.example.com \
GRAPHDEN_SSE_URL=https://sse.hub.example.com \
GRAPHDEN_EXECUTOR_TOKEN=<the minted bearer> \
GRAPHDEN_EXECUTOR_ORG=acme \
GRAPHDEN_APP_HANDLER_FN=<the org's app handler fn> \
GRAPHDEN_PORT=8080 \
  clojure -M -m graphden.byo
```

From the Docker image, override the CMD (the image's default CMD boots the
full hub server — a BYO pod wants the `graphden.byo` main):

```yaml
# docker-compose.byo.yml — a minimal single-container BYO deployment
services:
  byo-executor:
    image: graphden-executor:latest
    command: ["java", "-cp", "executor-server.jar", "clojure.main", "-m", "graphden.byo"]
    ports: ["8080:8080"]
    environment:
      GRAPHDEN_HUB_URL: https://hub.example.com
      GRAPHDEN_SSE_URL: https://sse.hub.example.com
      GRAPHDEN_EXECUTOR_TOKEN: ${BYO_TOKEN}
      GRAPHDEN_EXECUTOR_ORG: acme
      GRAPHDEN_APP_HANDLER_FN: my-app-handler
      GRAPHDEN_PORT: "8080"
    mem_limit: 2g
```

No Postgres, no vault, no volumes — the whole state is the in-memory graph
snapshot, refetched from the hub.

Environment reference: [DEPLOYMENT.md § BYO entrypoint](DEPLOYMENT.md).
If the hub offers no SSE relay, set `GRAPHDEN_REFRESH_POLL_MS` (e.g.
`30000`) — without either the graph freezes at the bootstrap snapshot and
the log carries a loud WARN.

## Verifying the bootstrap

1. Startup log shows `RemoteStorage bootstrapped` with row counts, then
   `BYO executor serving`.
2. `curl http://localhost:8080/` answers with the org's app.
3. Edit the app in the hub editor → the executor logs
   `RemoteStorage refreshed` (SSE push or the next poll tick) and the next
   request serves the change.

## Troubleshooting

| Symptom | Meaning | Fix |
|---|---|---|
| Bootstrap `GET /api/export/graph-rows` → `401` | Bad/expired token | Re-mint via `/api/my-tokens`; check the `Bearer` prefix isn't doubled |
| Bootstrap → `421` | Should not happen since the 2026-08 fix — the hub's byo refusal is execute-only | Upgrade the hub; export/editor requests must be served for byo orgs |
| Hub editor Run popover → `421` | Expected: execution on hub compute is exactly what the byo flip opts out of | Run through your executor's app endpoint |
| `BYO executor started WITHOUT a live-refresh signal` WARN | No SSE url and no poll cadence | Set `GRAPHDEN_SSE_URL` or `GRAPHDEN_REFRESH_POLL_MS` |
| Graph serves stale values | SSE stream dropped and nothing re-signalled | The source reconnects with backoff and resyncs on connect; check the LB idle-timeout in front of the relay |
| `BYO handler fn not found` at start | `GRAPHDEN_APP_HANDLER_FN` names a fn absent from the org's branch | Check the fn name and `GRAPHDEN_EXECUTOR_BRANCH` |

## Billing

Execution on a BYO executor is not metered — the hub's usage metering
counts `fn_executions` rows in the hub's Postgres, which a BYO executor
never writes (its storage is read-only), and the hub refuses the org's
`/api/execute` anyway. What the org pays for is its paid tier: the storage
plane (editor, branches, export) that keeps living on the hub.

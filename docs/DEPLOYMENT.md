# Deployment Guide

This guide covers deploying the Graphden executor server.

## Prerequisites

- Java 21+ (JRE or JDK)
- PostgreSQL 16 (plain PostgreSQL — graph traversal uses recursive
  CTEs; no database extension is required)
- Docker (optional, for containerized deployment)
- Optionally: an OpenBao / Vault KV v2 server (for the `:vault-get`
  secrets base-fn)

## Building

### Build Uberjar

```bash
clojure -T:build uber
```

This produces `target/executor-server.jar` containing all dependencies.

#### External packages resolve at build time

The build resolves every dependency in `deps.edn` **and** every external
Type-2 package listed in `resources/executor-packages.edn` (see
[PACKAGE_DISTRIBUTION § 5.1](PACKAGE_DISTRIBUTION.md)). The only such package
today is `mathx` (`graphden/graphden-mathx`), pulled by a **git coord onto a
public repo over https** — so `clojure -T:build uber` / `bb rebuild` /
`bb check` resolve it anonymously, with no credentials or ssh-agent.

`bb test` and `bb dev` don't fetch it over the network at all — their aliases
carry an `:override-deps` back onto the in-tree copy, so lint/test stay
offline. If you fork `mathx` into a **private** repo, either register the build
host's public key as a **read-only deploy key** on it, or point the coord at
your own public mirror.

### Build Docker Image

The Dockerfile copies a **pre-built** uberjar, so build the jar first:

```bash
# 1. Build the uberjar
clojure -T:build uber

# 2. Build the Docker image
docker build -t graphden/executor-server .
```

The `bb rebuild` task automates both steps plus a `docker compose`
restart and a `bb verify` build-hash check; use it after code changes.

## Deployment Options

### Option 1: Docker Compose (Recommended)

The included `docker-compose.yml` brings up the full local stack:

```bash
# Build the jar first, then:
docker compose up -d

# View logs
docker compose logs -f executor

# Stop
docker compose down
```

This starts:

- **postgres** — PostgreSQL 16 (the graphden system DB)
- **user-postgres** — a separate "tenant" PostgreSQL the demo fn-graph
  reaches through `:sql-exec` / `:sql-query`
- **openbao** — OpenBao (Vault-compatible) dev-mode KV store for the
  `:vault-get` secrets base-fn
- **openbao-seed** — one-shot seeder for the demo secrets
- **executor** — the Graphden executor server

The executor listens on container port `8080`, published to the host
as **`http://localhost:9002`**.

### Option 1a: Local OFFLINE instance (partially-local workflow)

The default Graphden story is "open the site and work". The LOCAL
instance is the opt-in for two situations: flaky/absent network, or a
cloud prod you don't want to spend on while iterating (test runs on your
own hardware are free). It is the SAME compose stack with two habits:

```bash
# Loopback-only: the editor + your apps are reachable from this machine
# only — "internal requests only" is the port BINDING, not a mode.
GD_BIND=127.0.0.1 docker compose up -d
```

- **Work offline.** Everything is local: editor, branches, executions.
  Runtime config (ports, cron schedules, vault paths) is `branch-local?`
  by design, so nothing of your local wiring can leak into the hub later.
- **Snapshot to git** whenever you like:
  `bb graph-export --url http://localhost:9002 --token $AUTH_TOKEN --out ../my-graph`
  — one EDN file per namespace, byte-stable (a git diff is a graph
  diff). Re-apply anywhere with `bb graph-import`. Preview what an import
  would change first with `graphden.cli diff` (or `push --dry-run`).
  **Note:** a snapshot is the whole graph in plaintext. Vault paths are
  stripped by default (the `:secrets` manifest lists what needs
  re-binding), but the graph STRUCTURE and any inline values travel — so
  treat a snapshot repo like source, not like a secrets store, and don't
  commit it anywhere you wouldn't commit the code.
- **When the network is back, push** your branch to the hub as a
  review branch:
  `clojure -M -m graphden.cli push --local-url http://localhost:9002
  --local-token $AUTH_TOKEN --hub-url https://your-hub --hub-token
  $HUB_TOKEN --branch main --target push/my-feature` — the hub branch is
  created owner-stamped with the `owner` write-policy, and review/merge
  happen with the hub's normal diff → conflicts → merge flow.
- **Pull the hub's main** into a local `hub/main` branch with
  `clojure -M -m graphden.cli pull …`, then merge it locally (editor
  branch popover or `POST /api/branches/main/merge`).
- **`:service` rows never travel** in bundles: your local cron/web-server
  services stay local, the hub's stay on the hub.

The push/pull identity model is deterministic (`uuid-v5(namespace,
name)`), so re-pushing/re-pulling is idempotent, and an fn you created in
the local editor is ADOPTED onto its canonical id on first import rather
than duplicated.

### Option 2: Docker with External Database

If you have an existing PostgreSQL instance:

```bash
docker run -d \
  --name graphden \
  -e JDBC_URL=jdbc:postgresql://your-host:5432/graphden \
  -e DB_USERNAME=graphden \
  -e DB_PASSWORD=your-password \
  -p 9002:8080 \
  graphden/executor-server
```

The container always listens on `8080`; map it to whatever host port
you like (`9002` matches the compose setup). The listen port is fixed
at the Docker layer — there is no port environment variable to change
it inside the container.

### Option 3: Direct JAR Execution

Run the uberjar directly:

```bash
JDBC_URL=jdbc:postgresql://localhost:5432/graphden \
DB_USERNAME=graphden \
DB_PASSWORD=graphden \
java -jar target/executor-server.jar
```

### Option 4: Kubernetes (Helm) — the dynamic fleet

For a multi-pod fleet with automatic placement + rebalancing, deploy the Helm
chart in `deploy/helm/graphden`. It brings up a StatefulSet of executors behind
a headless Service (SRV membership discovery) with the leader-locked placement
controller, and an optional HPA. Full walkthrough: **[FLEET_DEPLOY.md](FLEET_DEPLOY.md)**.

```bash
helm install fleet deploy/helm/graphden \
  --set database.jdbcUrl=jdbc:postgresql://your-managed-pg:5432/graphden \
  --set secrets.authToken=... --set secrets.internalToken=... --set secrets.dbPassword=...
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `JDBC_URL` | `jdbc:postgresql://localhost:5432/graphden` | PostgreSQL connection URL |
| `DB_USERNAME` | `graphden` | Database username |
| `DB_PASSWORD` | `graphden` | Database password |
| `DB_POOL_SIZE` | `10` | HikariCP connection pool size |
| `VAULT_ADDR` | *(empty)* | OpenBao / Vault address (unset → `:vault-get` errors) |
| `VAULT_TOKEN` | *(empty)* | OpenBao / Vault token |
| `AUTH_TOKEN` | *(empty)* | Single-token auth secret — **empty = auth OFF, instance fully open** (see [Authentication](#authentication)) |
| `GRAPHDEN_REGISTRY_TOKEN` | *(empty)* | Bearer used when pulling a package from a REMOTE registry (`POST /api/packages/install` with `source` — [PACKAGE_DISTRIBUTION § 13](PACKAGE_DISTRIBUTION.md#13-self-hosted-install-by-package-type)). Empty ⇒ remote fetches go unauthenticated |
| `GRAPHDEN_SKIP_URL_DRIFT_CHECK` | *(empty)* | `1` to skip the boot URL-drift check |
| `CLEANUP_PERIOD_MS` | `3600000` | `:fn-execution` TTL sweep period (ms) |
| `GRAPHDEN_DEMO_BRANCHES_ENABLED` | *(empty)* | Truthy to seed demo branches |
| `GRAPHDEN_DISABLE_ASSET_OVERRIDES` | *(empty)* | `1` ⇒ every frontend asset serves its shipped classpath baseline, ignoring `:resource-override` rows (bytes AND the effective `?v=` hash). Rescue hatch for a bad in-editor asset override that bricked the editor — restart with it set, revert the override in Operate → Assets, unset, restart. Restart-scoped. |
| `GRAPHDEN_MAX_CONCURRENT_EXECUTIONS` | `128` | Per-pod cap on concurrent `/api/execute` runs (protects the JVM's executor) |
| `GRAPHDEN_MAX_CONCURRENT_EXECUTIONS_PER_ORG` | `32` | Fleet-wide per-org cap (counts non-terminal `:fn-execution` rows across all pods) |

### Authentication

Auth is **provider-driven and optional**:

- **`AUTH_TOKEN` unset / empty → auth is OFF.** No provider is wired, the
  auth-required middleware passes everything through, and the instance runs
  **fully open — no login at all**. This is the intended local /
  self-hosted-on-your-own-machine mode.
- **`AUTH_TOKEN` set → single-token auth is ON.** Every graph read and write
  (`/api/graph/*`, `/api/types*`, CRUD, execute, …) requires
  `Authorization: Bearer <token>`; the editor prompts for the token. There is
  no anonymous read-only view.
- **Tenancy addon active → the addon wires its own storage-token provider**
  (sessions + API keys in the `:token` table; `POST /api/login`, the public
  `GET /login` page, self-serve signup). `AUTH_TOKEN` is not used.

> **SECURITY:** "unconfigured" means **open**, not locked. Never expose an
> instance to an untrusted network without either `AUTH_TOKEN` or the tenancy
> addon. Deploy checklists below assume a set token.

### Accounts (opt-in identity module — see [ACCOUNTS.md](ACCOUNTS.md))

The open `accounts` addon gives a self-hosted instance real users
(passwords, social login, sessions, 2FA) instead of the single token.
Enable it via:

```
GRAPHDEN_ADDON_CONFIGS=graphden/accounts/addon.edn
```

Its addon fragment reads (all optional; features degrade gracefully):

| Variable | Default | Description |
|----------|---------|-------------|
| `RESEND_API_KEY` | *(empty ⇒ LogMailer)* | Resend key for verify/reset email; unset logs the links |
| `GRAPHDEN_MAIL_FROM` | *(built-in sender)* | Override the From address |
| `GRAPHDEN_APP_ORIGIN` | *(unset ⇒ request Host)* | Public origin for OAuth redirects + emailed links |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | *(empty ⇒ off)* | GitHub OAuth — both required to enable |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | *(empty ⇒ off)* | Google OIDC — both required to enable |
| `TELEGRAM_BOT_TOKEN` / `TELEGRAM_BOT_USERNAME` | *(empty ⇒ off)* | Telegram login-widget bot — both required to enable |

### Fleet / sharding (hosted pods — see [SCALING.md](SCALING.md))

| Variable | Default | Description |
|----------|---------|-------------|
| `GRAPHDEN_SSE_PORT` | *(empty)* | Port for the SSE invalidation relay (keeps external/BYO executors fresh). Unset ⇒ relay disabled |
| `GRAPHDEN_EXECUTOR_ORGS` | *(empty)* | Comma/JSON set of org-ids this pod's shard serves. Unset ⇒ compiles everything (single-tenant / unsharded). A request for an org outside the shard gets `421` |
| `GRAPHDEN_BYO_EXECUTOR` | *(empty)* | Truthy ⇒ this pod is a customer's own executor and serves `:byo` orgs (a hosted pod `421`s them) |

### Dynamic fleet — placement controller (see [FLEET_RFC.md](FLEET_RFC.md) + [FLEET_DEPLOY.md](FLEET_DEPLOY.md))

Set on hosted pods that participate in the dynamic fleet. The Helm chart wires all of these.

| Variable | Default | Description |
|----------|---------|-------------|
| `GRAPHDEN_EXECUTOR_ID` | *(empty)* | This pod's fleet identity — its own DNS name (k8s pod FQDN). Set ⇒ forward-hop + the `/internal/fleet/cell` endpoint + the placement controller all activate. Unset ⇒ single-tenant, none of them |
| `GRAPHDEN_PORT` | `8080` | Port sibling pods dial for forward-hop + cell commands |
| `GRAPHDEN_INTERNAL_TOKEN` | *(empty)* | Shared control-plane secret gating `POST /internal/fleet/cell/...`. Unset ⇒ endpoint fail-closes, moves disabled |
| `GRAPHDEN_FLEET_EXECUTORS` | *(empty)* | Explicit comma-separated executor set (static / non-k8s). Takes precedence over DNS discovery |
| `GRAPHDEN_FLEET_DNS` | *(empty)* | Headless-Service SRV name; resolved for live membership when the explicit list is unset (tracks HPA scaling) |
| `GRAPHDEN_FLEET_CONTROLLER_PERIOD_MS` | `30000` | Controller tick period |
| `GRAPHDEN_FLEET_SUSTAIN_TICKS` | `3` | Ticks an imbalance must persist before a rebalance move fires |
| `GRAPHDEN_FLEET_MIN_IMPROVEMENT` | `0.0` | Magnitude floor — a plan is dropped unless it improves imbalance by more than this |
| `GRAPHDEN_FLEET_MAX_MOVES` | *(unbounded)* | Per-tick cap on rebalance moves |

### BYO entrypoint (`clojure -M -m graphden.byo` — see [SCALING.md § External / BYO](SCALING.md))

| Variable | Default | Description |
|----------|---------|-------------|
| `GRAPHDEN_HUB_URL` | *(required)* | Hub base URL serving `GET /api/export/graph-rows` |
| `GRAPHDEN_SSE_URL` | *(empty)* | Hub SSE-relay URL (its `GRAPHDEN_SSE_PORT`). Unset ⇒ no live push — pair with the poll below |
| `GRAPHDEN_REFRESH_POLL_MS` | *(empty)* | Poll the hub for graph changes every N ms when there is no SSE relay. With NEITHER this nor `GRAPHDEN_SSE_URL` set the graph freezes at the bootstrap snapshot (start-byo! warns loudly) |
| `GRAPHDEN_EXECUTOR_TOKEN` | *(required)* | This executor's bearer token |
| `GRAPHDEN_EXECUTOR_ORG` | *(required)* | The single org this executor serves |
| `GRAPHDEN_EXECUTOR_BRANCH` | *(empty)* | Branch to pin (unset ⇒ main) |
| `GRAPHDEN_APP_HANDLER_FN` | `_app-ring-response` | Name of the org's app-handler fn to run per request |
| `GRAPHDEN_PORT` | `8080` | HTTP port to serve on |

Step-by-step provisioning (operator side + customer side) lives in
[BYO_RUNBOOK.md](BYO_RUNBOOK.md).

There is no env-configurable execution max-depth, but the per-execution
wall-clock deadline **is** tunable via `GRAPHDEN_MAX_EXECUTION_WALL_MS`
(default `300000` = 5 min).

## Database Setup

Graphden uses **plain PostgreSQL** — graph traversal is done with
recursive CTEs, so no database extension needs to be installed. Any
standard PostgreSQL 16 database works:

```bash
docker run -d \
  --name graphden-postgres \
  -e POSTGRES_DB=graphden \
  -e POSTGRES_USER=graphden \
  -e POSTGRES_PASSWORD=graphden \
  -p 5432:5432 \
  postgres:16-alpine
```

The executor **auto-migrates its schema on startup** — create an empty
database and point `JDBC_URL` at it; the tables are created on first
boot.

### Multi-tenancy: non-superuser DB role (RLS enforcement)

**Only relevant when running the tenancy addon (the cloud / multi-tenant
deployment).** Single-tenant deployments can ignore this section.

The tenancy addon (the private `graphden-tenancy` repo) installs Postgres
Row-Level-Security policies (`org_isolation`, `FORCE ROW LEVEL SECURITY` —
see `graphden.tenancy.rls`) as a defense-in-depth layer **underneath**
`OrgScopedStorage`: even a raw SQL query that bypasses the storage
decorator is confined to the connection's `graphden.current_org`. The
addon also wraps the pool (`:tenancy/datasource-wrap`) so every borrowed
connection carries `*current-org*` into that GUC.

**But a Postgres superuser — and any role with `BYPASSRLS` — ignores RLS
entirely**, including `FORCE`. The default container role above
(`POSTGRES_USER=graphden`) IS the database superuser, so with it RLS is
inert (OrgScoped still isolates; the RLS layer simply does nothing). To
make RLS actually enforce, **the app must connect as a non-superuser,
non-`BYPASSRLS` role.**

As a superuser (one-time):

```sql
CREATE ROLE graphden_app LOGIN PASSWORD '<secret>' NOSUPERUSER NOBYPASSRLS;

-- The executor auto-initialises the schema on first boot, so the app
-- role needs to create (and will then OWN) the tables — FORCE ROW LEVEL
-- SECURITY (already set by enable-rls!) makes the policy apply to the
-- owner too.
GRANT ALL ON DATABASE graphden TO graphden_app;
GRANT ALL ON SCHEMA public TO graphden_app;
```

Then point the app's `:db/postgres` config at `graphden_app` (not the
superuser): set `DB_USERNAME=graphden_app` / `DB_PASSWORD=<secret>`. On
boot the executor creates the schema as `graphden_app`,
`:tenancy/rls-enabler` installs the policies, and every tenant request
is RLS-confined.

**Verify** the role is actually subject to RLS (a superuser would
silently pass this with isolation off):

```sql
-- as graphden_app, in a transaction:
SELECT set_config('graphden.current_org', 'acme', true);
SELECT name FROM "fn";        -- only acme's rows + public (org_id NULL)
```

The enforcement itself is covered by `graphden.tenancy.rls-test`
(`rls-isolates-raw-queries-by-org` connects via a non-superuser `SET ROLE`
and asserts cross-org rows are invisible).

**Boot guard.** The `:tenancy/rls-enabler` component checks the app's DB
role right after installing the policies (`rls/verify-rls-enforcement!`). If
the app connects as a superuser or a `BYPASSRLS` role — so the policies are
inert — it **fails the boot by default** (`:rls/not-enforced`), so a
misconfigured role can never silently ship with the database-level backstop
disabled. A trusted single-tenant / dev install that deliberately runs on the
superuser role can downgrade this to a prominent `WARN` with
**`GRAPHDEN_STRICT_RLS=false`**. (A correct non-superuser production role is
subject to RLS and passes this guard silently — the guard only ever fires for
a superuser / `BYPASSRLS` role.)

> Without this role switch the deployment is still tenant-isolated by
> `OrgScopedStorage` at the application layer; you only lose the
> database-level backstop. Treat the non-superuser role as **required
> for production multi-tenant**, optional for a trusted single-tenant
> install.

### Resource isolation: shared vs dedicated

RLS + the effect gate isolate tenants by *data* and *capability*, but on the
**shared** tier co-located tenants share the pod's CPU / heap / threads — a
noisy or OOMing neighbour can degrade others. Give a tenant a **hard**
resource boundary with the **dedicated** tier (its own cgroup-limited shard).
See [SCALING.md § Tenant isolation](SCALING.md) and
[FLEET_DEPLOY.md § Dedicated tenant shard](FLEET_DEPLOY.md).

## Health Checks

The server exposes a health endpoint:

```bash
curl http://localhost:9002/health
```

The Docker health check probes `http://localhost:8080/health` (inside
the container) every 30s, with a 90s start-period covering the full
cold boot. `/health` is the **readiness** signal (a graph fn behind the
compiled registry — 200 only while warm). For **liveness** — a restart
decision — use `GET /livez`, a static registry-independent 200: pointing
a restart-on-unhealthy check at `/health` would kill a pod busy on a
runtime recompile and force a slower cold boot. See docs/OPERATIONS.md
§ Health & readiness.

## JVM Configuration

The Docker image runs the jar with these JVM settings:

- `-XX:+UseContainerSupport` — respect container CPU/memory limits
- `-XX:MaxRAMPercentage=75.0` — use 75% of container memory for heap
- `-XX:+ExitOnOutOfMemoryError` — exit cleanly on heap OOM so the
  restart policy brings up a fresh JVM
- `-XX:+HeapDumpOnOutOfMemoryError` / `-XX:HeapDumpPath=/tmp/heap-dump.hprof`

For bare-metal deployment, consider:

```bash
java \
  -Xms512m \
  -Xmx2g \
  -XX:+UseG1GC \
  -jar target/executor-server.jar
```

## Production Checklist

- [ ] Set a strong database password via `DB_PASSWORD`
- [ ] Configure appropriate `DB_POOL_SIZE` based on expected load
- [ ] Set `AUTH_TOKEN` to a strong secret
- [ ] Point `VAULT_ADDR` / `VAULT_TOKEN` at your secrets store if using
      `:vault-get`
- [ ] Leave `GRAPHDEN_DEMO_BRANCHES_ENABLED` unset in production
- [ ] Enable HTTPS via reverse proxy (nginx, traefik)
- [ ] Configure log aggregation (stdout is JSON-compatible)
- [ ] Set up monitoring for `/health`
- [ ] Configure database backups for PostgreSQL — backup strategy, restore,
      PITR, and PG-HA/failover behaviour are in [OPERATIONS.md](OPERATIONS.md)

## Troubleshooting

### Connection Refused to Database

Check that the Postgres container is healthy:

```bash
docker exec graphden-postgres pg_isready -U graphden -d graphden
```

### Out of Memory Errors

Increase JVM heap or container memory:

```bash
docker run -m 4g ...
```

The container is configured to exit on heap OOM and dump the heap to
`/tmp/heap-dump.hprof`, so the restart policy replaces it with a fresh
JVM automatically.

### Slow Startup

Initial startup loads packages, runs the type-check sweep, and
compiles all fn-defs into closures. This is normal and takes
**~30–40 s** (the canonical cold-boot figure — see
[OPERATIONS.md § Health & readiness](OPERATIONS.md); hence the 90 s
Docker start-period).

## Scaling

For horizontal scaling:

1. Use a shared PostgreSQL instance
2. Run multiple executor containers
3. Load balance with nginx/traefik
4. Consider read replicas for heavy read workloads

Each executor maintains its own compiled fn-registry. Cross-pod cache
invalidation is automatic: every pod's `:db/notify-listener` LISTENs on
the `graphden_events` channel, so a mutation on one pod propagates to
siblings within ~1s.

Service ownership depends on the service's `:cardinality`
(see [SERVICES.md](SERVICES.md#cardinality-enum)):

- `:per-pod` — every pod runs it. The seeded `:web-server` is `:per-pod`,
  which is what lets each container bind its own port and answer the load
  balancer's healthcheck.
- `:singleton` — exactly one pod runs it, elected by a Postgres advisory
  lock. Use for cron / `:schedule` loops.

Upgrading a deployment that pre-dates the `:cardinality` field: the
seeder backfills it from `package.edn` on boot, so the `:default`
service becomes `:per-pod` on first start of the new image. Roll pods one
at a time — an old pod holds the `:web-server` advisory lock until it
exits, and a new pod ignores that lock entirely.

Beyond plain replication, the fleet supports **org sharding** (a pod's
`GRAPHDEN_EXECUTOR_ORGS` limits which orgs it compiles + serves; an
off-shard request gets `421 Misdirected Request`), a **fleet-wide per-org
execution quota**, and a full **external / BYO executor** (a customer runs
`graphden.byo` on their own hardware, reading the graph over HTTP and
staying fresh over the SSE relay). See [SCALING.md](SCALING.md) for the
as-built fleet model and the env vars above.

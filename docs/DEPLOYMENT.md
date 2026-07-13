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

#### Private external packages need repo access at build time

The build resolves every dependency in `deps.edn` **and** every external
Type-2 package listed in `resources/executor-packages.edn` (see
[PACKAGE_DISTRIBUTION § 5.1](PACKAGE_DISTRIBUTION.md)). When such a package is
pulled by a **git coord onto a private repo** — as `mathx`
(`BonsaiFlow/graphden-mathx`) is — the build host must be able to read that
repo, or `clojure -T:build uber` / `bb rebuild` / `bb check` fail to resolve it.

On a host whose SSH key is passphrase-protected, unlock it once per session
into an agent so the build can clone non-interactively:

```bash
eval "$(ssh-agent -s)"        # or point -a at a fixed socket you reuse
ssh-add ~/.ssh/id_rsa         # enter the passphrase once
```

`bb test` and `bb dev` do **not** need this — their aliases carry an
`:override-deps` back onto the in-tree copy, so lint/test stay offline. For
unattended CI, either make the package repo public or register the build host's
public key as a **read-only deploy key** on it.

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
| `AUTH_TOKEN` | *(empty)* | Single-token auth secret |
| `GRAPHDEN_SKIP_URL_DRIFT_CHECK` | *(empty)* | `1` to skip the boot URL-drift check |
| `CLEANUP_PERIOD_MS` | `3600000` | `:fn-execution` TTL sweep period (ms) |
| `GRAPHDEN_DEMO_BRANCHES_ENABLED` | *(empty)* | Truthy to seed demo branches |
| `GRAPHDEN_MAX_CONCURRENT_EXECUTIONS` | `128` | Per-pod cap on concurrent `/api/execute` runs (protects the JVM's executor) |
| `GRAPHDEN_MAX_CONCURRENT_EXECUTIONS_PER_ORG` | `32` | Fleet-wide per-org cap (counts non-terminal `:fn-execution` rows across all pods) |

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
| `GRAPHDEN_SSE_URL` | *(empty)* | Hub SSE-relay URL (its `GRAPHDEN_SSE_PORT`). Unset ⇒ bootstrap-only, no live refresh |
| `GRAPHDEN_EXECUTOR_TOKEN` | *(required)* | This executor's bearer token |
| `GRAPHDEN_EXECUTOR_ORG` | *(required)* | The single org this executor serves |
| `GRAPHDEN_EXECUTOR_BRANCH` | *(empty)* | Branch to pin (unset ⇒ main) |
| `GRAPHDEN_APP_HANDLER_FN` | `_app-ring-response` | Name of the org's app-handler fn to run per request |
| `GRAPHDEN_PORT` | `8080` | HTTP port to serve on |

There is no env-configurable execution max-depth or handler timeout (the
per-request bounds are fixed constants).

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

The tenancy addon installs Postgres Row-Level-Security policies
(`org_isolation`, `FORCE ROW LEVEL SECURITY` — see
`graphden.tenancy.rls`) as a defense-in-depth layer **underneath**
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
and asserts cross-org rows are invisible) — model an environment check
on it.

> Without this role switch the deployment is still tenant-isolated by
> `OrgScopedStorage` at the application layer; you only lose the
> database-level backstop. Treat the non-superuser role as **required
> for production multi-tenant**, optional for a trusted single-tenant
> install.

## Health Checks

The server exposes a health endpoint:

```bash
curl http://localhost:9002/health
```

The Docker health check probes `http://localhost:8080/health` (inside
the container) every 30s, with a 90s start-period covering the full
cold boot.

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
- [ ] Configure database backups for PostgreSQL

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
compiles all fn-defs into closures. This is normal and takes ~35s
(hence the 90s Docker start-period).

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

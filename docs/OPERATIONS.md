# Operations runbook (day-2)

For a self-hoster or cloud operator running Graphden in production. Graphden is
**entirely Postgres-backed** — the graph (code), versions, tenants, execution
records, service desired-state, and the fleet placement map all live in one
database. So your day-2 story is largely a **Postgres** story: get backups,
recovery, and (for a fleet) HA right, and the rest follows.

See also [DEPLOYMENT.md](DEPLOYMENT.md) (install/config) and
[SCALING.md](SCALING.md) / [FLEET_DEPLOY.md](FLEET_DEPLOY.md) (fleet).

## What holds state

| State | Where |
|---|---|
| Graph (fns/slots/bindings), branches, versions | Postgres |
| Tenants, users, grants, plans | Postgres |
| Execution records, usage metering | Postgres |
| Service desired-state, fleet placement map | Postgres |
| Secret **values** | Vault / OpenBao (Postgres holds only the paths) |
| Compiled registry, per-branch caches | in-memory (rebuilt from Postgres on boot / NOTIFY) |

Everything durable is in Postgres + Vault. **Back up both.** The in-memory
state is derived and self-rebuilds — it needs no backup.

## Backups

Choose one (managed Postgres usually gives you both):

- **Logical** — periodic `pg_dump` of the graphden database. Simple; restore
  granularity is the dump interval.
- **Physical + WAL archiving (PITR)** — continuous WAL archiving for
  point-in-time recovery. Recommended for production: recover to any moment,
  not just the last dump.

Back up the **Vault/OpenBao** store on its own schedule (its own backup
mechanism) — a graph that reads a secret is useless if the secret is lost.

**Test the restore.** An untested backup is not a backup — periodically restore
into a scratch instance and boot Graphden against it (`bb verify <url>` after).

## Restore

1. Restore Postgres (from dump, or PITR to the target time).
2. Ensure Vault/OpenBao holds the referenced secret paths.
3. Boot the executor against the restored DB. Schema init is idempotent and
   **fleet-safe** (a transaction-scoped advisory lock serializes it — see
   *Migrations & upgrades* below), so a normal boot reconciles cleanly.
4. Verify with `bb verify <base-url>` (per-section build-hash match) and the
   `/health` endpoint.

The in-memory compiled registry rebuilds from the restored rows on boot — no
separate cache restore.

## Postgres HA & failover

Graphden does not manage Postgres HA — use a managed Postgres, or replication
with a failover mechanism (e.g. Patroni, or your cloud's managed failover).
Two things to know about how Graphden interacts with a failover:

- **Advisory locks and `LISTEN/NOTIFY` are session state and are NOT
  replicated.** On failover the singleton/leader advisory locks and every
  NOTIFY subscription are lost with the old primary's connections.
- **Recovery is automatic, with a brief window.** After the connections
  reconnect to the new primary: singleton/leader locks are re-contended
  (`services/reconciler`, the fleet controller), NOTIFY listeners re-subscribe,
  and cache invalidation self-heals via the graph-epoch check + the periodic
  reconcile tick. Nothing needs manual intervention, but expect a short window
  of degraded invalidation/singleton coverage around the failover.

**Recommendation:** point the app's DB connection at a **stable failover
endpoint** (a managed endpoint, or PgBouncer / a virtual IP in front of the
primary) so a failover is a transparent reconnect rather than a config change.
This also matters for CRaC restore (its DB endpoint is baked at checkpoint
time — see [FLEET_RFC.md](FLEET_RFC.md) §5.1).

## Migrations & upgrades

- Schema init/migration runs **automatically on boot** and is **idempotent**.
- It is **fleet-safe**: the whole read → plan → DDL section is serialized across
  pods by a transaction-scoped advisory lock, so N pods booting concurrently
  (fresh DB or a rolling upgrade shipping `ALTER`s) do not race DDL — each pod
  blocks, then sees the migrated schema and no-ops. A rolling upgrade is safe.
- After deploying a new image, run `bb verify <base-url>` to confirm the
  frontend / packages / backend sections all match the shipped build.

## Health & readiness

- `GET /health` — liveness/readiness signal. A pod mid-cold-boot (compiling the
  registry) is not yet ready; give the readiness probe a start-period generous
  enough for the boot compile (see the Dockerfile healthcheck `start-period`).
- `GET /version` — the three build-section hashes (`bb verify` consumes these).
- `GET /metrics` — a JVM + structural-counter snapshot (JSON). For a Prometheus
  scrape target, see the observability notes in [SCALING.md](SCALING.md).

## Suspending an abusive org

To freeze a misbehaving tenant near-instantly, set its plan to `suspended` — no
effects, all row ceilings 0, so it can neither run nor create anything (it can
still delete its own data). The route is platform-only (authenticate as the
platform/operator, not as the tenant):

```bash
# freeze
curl -sS -X POST "$BASE/api/orgs/plan" -H "Authorization: Bearer $OPERATOR_TOKEN" \
  --data-urlencode name=<org-slug> --data-urlencode plan=suspended

# restore (back to its real tier)
curl -sS -X POST "$BASE/api/orgs/plan" -H "Authorization: Bearer $OPERATOR_TOKEN" \
  --data-urlencode name=<org-slug> --data-urlencode plan=free
```

`plan` must be one of `free` / `network` / `dedicated` / `suspended`; an unknown
slug is rejected rather than silently applied. The change is effective on the
org's next request (no memo). The same route upgrades / downgrades a paying org.
See [PLANS.md § Suspending an org](PLANS.md).

## Your responsibility vs Graphden's

Graphden gives you: idempotent fleet-safe schema migration, self-healing
in-memory state, health/version/verify surfaces. **You** own: Postgres
backups, HA, and failover topology; Vault/OpenBao backups; the network
perimeter and TLS; and OS/container patching.

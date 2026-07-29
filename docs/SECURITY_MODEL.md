# Security model

For a customer or self-hoster evaluating how Graphden isolates tenants and
handles their code and secrets. It describes the isolation **architecture** and
its **boundaries** — it is not an exhaustive threat enumeration, and it does
not publish exploit paths. Report vulnerabilities **privately** per the org's
SECURITY policy (private disclosure via GitHub Security Advisories), never a
public issue.

## Trust model

- The **platform operator** is trusted — runs the executor, owns the Postgres,
  holds the Vault / OpenBao root token. On the hosted cloud that is Graphden;
  self-hosting, it is you.
- A **tenant** (an org authoring a graph on the shared cloud) is **untrusted**:
  its graph is arbitrary composition the platform must contain.
- A **single-tenant self-host** has no untrusted tenant — the tenant-isolation
  layers below are not load-bearing there (one org, its own deployment).

## Isolation layers (defense in depth)

A tenant graph on the shared cloud is contained by several independent layers,
so a gap in one does not by itself cross tenants:

1. **Application-layer scoping** — `OrgScopedStorage` stamps and filters every
   entity by the caller's org; a tenant reads and writes only its own rows
   (plus the shared, read-only public library).
2. **Database-layer RLS** — Postgres Row-Level Security (`FORCE`) filters every
   row by the connection's `graphden.current_org`, so even a raw-SQL path that
   bypassed the decorator stays confined. **Strict by default**: the app must
   connect as a non-superuser role or the boot fails
   ([DEPLOYMENT.md § non-superuser DB role](DEPLOYMENT.md)).
3. **Capability gate (effects)** — every base-fn declares its side effects
   (`db` / `network` / `io` / `process` / …); a two-layer effect gate bounds
   what a tenant graph may *do*, by tier. The free tier allows only
   `db` / `state` / `time` / `random`; `network`, `process`, and `raw-sql` are
   withheld ([PLANS.md](PLANS.md)).
4. **Fairness quotas** — per-org row caps (fns, list items), a fleet-wide
   concurrent-execution cap, an outbound egress rate-limit and response
   byte-cap, and a wall-clock execution deadline.
5. **Egress guard (SSRF)** — a tenant's outbound network call is validated
   against a fail-closed classifier that rejects internal / private / loopback
   / cloud-metadata targets (and names that resolve to them) before dialing.
6. **Secret information-flow marker** — values read from the vault are typed
   `[:secret T]`, tracked through composition, and hidden at execution sinks
   (the Run pane). This is **best-effort taint-tracking, not a proven
   non-interference guarantee** — read [SECRETS.md § Scope](SECRETS.md).
7. **Resource isolation** — see below.

## Resource isolation: shared vs dedicated

The shared tier isolates tenants by data and capability and bounds them for
fairness, but co-located tenants share the pod's CPU / heap / threads — a noisy
or OOMing neighbour can degrade others
([SCALING.md § Tenant isolation](SCALING.md)). A tenant that needs a **hard**
resource boundary uses the **dedicated** tier: its own cgroup-limited shard.
Always-on tenant **services** are, for this reason, a dedicated-tier feature
only.

## Secrets

Secrets live in Vault / OpenBao. The platform holds the root token as
infrastructure configuration, never exposed to a tenant graph; a tenant reaches
a secret only through the `:vault-get` base-fn, whose result is typed
`[:secret :text]`. See [SECRETS.md](SECRETS.md).

## Your responsibility (self-host)

Graphden provides the isolation layers above; the deployment perimeter is
yours: Postgres HA / backups / point-in-time recovery, the non-superuser DB
role, the network perimeter and TLS termination, and OS / container patching.
See [DEPLOYMENT.md](DEPLOYMENT.md).

## Reporting

Please report suspected security issues **privately** via the org SECURITY
policy, not a public issue or PR.

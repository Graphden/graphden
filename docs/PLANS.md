# Plans & tiers

Graphden's cloud stratifies tenants into tiers. A tier is a slug on the org
(`:plan`) that resolves to (a) the side **effects** its graph may use and
(b) its **quota** ceilings — the source of truth is `graphden.tenancy.plan`.

**Pricing** is published at [graphden.dev](https://graphden.dev) (or contact
`licensing@graphden.dev`); this page documents what each tier *includes*, not
what it costs.

| | Free | Cloud Shared | Cloud Dedicated |
|---|---|---|---|
| Effects allowed | `db` `state` `time` `random` | + `network` | + `network` `process` |
| Outbound HTTP / SQL (`network`) | ✗ | ✓ (egress-guarded, rate + size capped) | ✓ |
| Outbound calls / min | 120 | 6,000 | ∞ (uncapped) |
| Max fns | 500 | 5,000 | 5,000 |
| Max list items | 50,000 | 500,000 | 500,000 |
| Always-on services | ✗ | ✗ | ✓ (up to 20) |
| Resource isolation | shared pod | shared pod | dedicated cgroup-limited shard |
| `raw-sql` | ✗ | ✗ | ✗ |

Notes:

- **Free** is the locked default; upgrading is a change to the org's `:plan`
  row (no migration).
- **Outbound `network`** is guarded by the SSRF egress broker plus a per-tier,
  per-org outbound-call rate cap (the "outbound calls / min" row) and a response-
  byte cap — see [SECURITY_MODEL.md](SECURITY_MODEL.md). A suspended org's cap is
  0 (no outbound at all).
- **Services** (persistent tenant processes) require the **dedicated** tier: a
  continuous tenant workload needs a hard resource boundary, which only the
  dedicated shard provides — see [SCALING.md § Tenant isolation](SCALING.md).
- **`raw-sql` is never granted on the cloud** — a tenant pod shares the
  platform Postgres, so raw SQL would cross tenants; use the typed storage
  base-fns (`:pg-query` / `:sql-query` / …) instead.
- **Single-tenant self-host** is unrestricted and uncapped — no tier applies.

## Suspending an org (abuse kill-switch)

`suspended` is a special tier — not something a tenant buys, but an operator's
throttle-to-zero for a misbehaving org. It grants **no effects at all** and sets
**every row ceiling to 0**, so a suspended org can neither run any graph
(execution is blocked at the effect gate) nor create any entity (the row-cap
rejects even the first write). Deletion stays allowed, so the tenant can still
clean up its own data.

An operator sets any org's tier — to suspend, to restore, or to upgrade /
downgrade — through the platform-only route:

```
POST /api/orgs/plan     (form-encoded: name=<org-slug>&plan=<tier>)
```

`plan` is one of `free` / `network` / `dedicated` / `suspended`; an unrecognised
slug is rejected (`:plan/unknown`) so a typo can't silently drop an org to the
free default. The change takes effect on the org's next request (the resolver is
not memoised). This route is platform-only — the `:org` entity is tenant-
forbidden, so a tenant cannot change its own or another org's tier.

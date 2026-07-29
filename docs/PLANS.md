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
| Max fns | 500 | 5,000 | 5,000 |
| Max list items | 50,000 | 500,000 | 500,000 |
| Always-on services | ✗ | ✗ | ✓ (up to 20) |
| Resource isolation | shared pod | shared pod | dedicated cgroup-limited shard |
| `raw-sql` | ✗ | ✗ | ✗ |

Notes:

- **Free** is the locked default; upgrading is a change to the org's `:plan`
  row (no migration).
- **Outbound `network`** is guarded by the SSRF egress broker plus per-org rate
  and response-byte caps — see [SECURITY_MODEL.md](SECURITY_MODEL.md).
- **Services** (persistent tenant processes) require the **dedicated** tier: a
  continuous tenant workload needs a hard resource boundary, which only the
  dedicated shard provides — see [SCALING.md § Tenant isolation](SCALING.md).
- **`raw-sql` is never granted on the cloud** — a tenant pod shares the
  platform Postgres, so raw SQL would cross tenants; use the typed storage
  base-fns (`:pg-query` / `:sql-query` / …) instead.
- **Single-tenant self-host** is unrestricted and uncapped — no tier applies.

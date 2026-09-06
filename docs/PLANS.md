# Plans & tiers

Graphden's cloud stratifies tenants into tiers. A tier is a slug on the org
(`:plan`) that resolves to (a) the side **effects** its graph may use and
(b) its **quota** ceilings — the source of truth is `graphden.tenancy.plan`
(in the private `graphden-tenancy` repo).

**Pricing** is published at [graphden.dev](https://graphden.dev) (or contact
`licensing@graphden.dev`); this page documents what each tier *includes*, not
what it costs.

| | Anonymous (demo) | Free (registered) | Cloud Shared | Cloud Dedicated |
|---|---|---|---|---|
| Effects allowed | `db` `state` `time` `random` | + `network` | + `network` | + `network` `process` |
| Outbound HTTP (`network`) | ✗ | ✓ (egress-guarded, rate + size capped) | ✓ | ✓ |
| Connect to your OWN database | ✗ | ✓ (external `:sql-query`/`:sql-exec`) | ✓ | ✓ |
| Outbound calls / min | 0 | 120 | 6,000 | ∞ (uncapped) |
| Max fns | 200 | 500 | 5,000 | 5,000 |
| Max list items | 2,000 | 50,000 | 500,000 | 500,000 |
| Persistence | ephemeral (reaped) | persistent | persistent | persistent |
| Always-on services | ✗ | ✗ | ✗ | ✓ (up to 20) |
| Resource isolation | shared pod | shared pod | shared pod | dedicated cgroup-limited shard |
| BYO executor (run your own — [BYO_RUNBOOK.md](BYO_RUNBOOK.md)) | ✗ | ✗ | ✓ | ✓ |
| `raw-sql` (platform DB) | ✗ | ✗ | ✗ | ✗ |

Notes:

- **Anonymous** is the LOCKED landing-demo tier and the fail-safe default (an
  un-provisioned / un-slugged org resolves here): no outbound network, small
  ceilings, ephemeral (reaped by the demo GC). It exists to let someone *try
  graphs* with nothing to lose.
- **Free (registered)** is what a signed-up account gets — base effects PLUS
  metered `:network`, so you can build a personal Telegram bot, keep a few
  hundred records, and connect to your OWN external database, all within the
  egress rate + size caps + the SSRF / platform-DB guard. It is not a locked
  tier; it is a genuinely useful one. Upgrading to a paid tier is a change to
  the org's `:plan` row (operator `set-org-plan`, no migration).
- **Outbound `network`** is guarded by the SSRF egress broker plus a per-tier,
  per-org outbound-call rate cap (the "outbound calls / min" row) and a response-
  byte cap — see [SECURITY_MODEL.md](SECURITY_MODEL.md). A suspended org's cap is
  0 (no outbound at all).
- **Connecting to your own database** uses the external `:sql-query` /
  `:sql-exec` base-fns; they count as the `network` effect (egress-guarded so
  they can only reach a validated-public host, never the platform DB or an
  internal service) — NOT `raw-sql`.
- **Services** (persistent tenant processes) require the **dedicated** tier: a
  continuous tenant workload needs a hard resource boundary, which only the
  dedicated shard provides — see [SCALING.md § Tenant isolation](SCALING.md).
- **`raw-sql` is never granted on the cloud** — it means arbitrary SQL against
  the *platform* Postgres (`:pg-query` / `:pg-execute` / `:pg-tx`), which a
  tenant pod shares, so it would cross tenants; use the org-scoped
  `:query-entities` family instead.
- **Single-tenant self-host** is unrestricted and uncapped — no tier applies.

## Starting an anonymous demo

A landing visitor gets an anonymous org through the unauthenticated endpoint:

```text
POST /api/demo/start   →   {"token": "<bearer>", "org": "demo-xxxxxxxx"}
```

It mints a throwaway `anonymous`-tier org (locked, no network, small ceilings)
plus a bearer token, and stamps an `:expires-at` so the demo-GC reaper purges
the whole org after the TTL (`GRAPHDEN_DEMO_TTL_MS`, default 1 h). The visitor
uses the returned token like any bearer.

This endpoint is **OFF by default** — a public, zero-friction row-creating
endpoint is an abuse surface. A deploy opts in with
`:tenancy/user-ops {:demo-signup-enabled? true}`. When off it returns 404; when
on it is per-IP rate-limited (429 over the window), and the anonymous tier + TTL
bound each demo's blast radius.

## Founding beta — paid tiers before billing exists

Until billing ships, paid tiers are granted **by hand** and never sold:

- A user asks for a plan (the landing's pricing note links
  `mailto:social@graphden.dev`; the plan-limit errors point at
  `graphden.dev/#pricing`). The operator flips the org with the route below
  (or Operate → Orgs in the editor) and records it in the private register
  (`graphden-internal/docs/FOUNDING_ORGS.md`: org, contact, tier, date, what
  was promised).
- One public condition for everyone: free until billing launches, then a
  fixed discount window on the same tier, in exchange for feedback. Capped
  (30 orgs; Dedicated is a real pod, so at most a handful).
- **Never tied to sponsorship.** Donations to the Open Collective do not buy
  a tier — the fiscal host forbids selling through the collective — so
  "support us and we unlock X" is not a thing this project says.
- The first org that wants to *pay* triggers the billing work: a pilot on a
  free tier meanwhile, then it converts first.

## Suspending an org (abuse kill-switch)

`suspended` is a special tier — not something a tenant buys, but an operator's
throttle-to-zero for a misbehaving org. It grants **no effects at all** and sets
**every row ceiling to 0**, so a suspended org can neither run any graph
(execution is blocked at the effect gate) nor create any entity (the row-cap
rejects even the first write). Deletion stays allowed, so the tenant can still
clean up its own data.

An operator sets any org's tier — to suspend, to restore, or to upgrade /
downgrade — through the platform-only route:

```text
POST /api/orgs/plan     (form-encoded: name=<org-slug>&plan=<tier>)
```

`plan` is one of `free` / `network` / `dedicated` / `suspended`; an unrecognised
slug is rejected (`:plan/unknown`) so a typo can't silently drop an org to the
free default. The change takes effect on the org's next request (the resolver is
not memoised). This route is platform-only — the `:org` entity is tenant-
forbidden, so a tenant cannot change its own or another org's tier.

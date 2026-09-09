# Lesson 34 — Plans & tiers: what the cloud grants each account

**Goal**: by the end of this lesson you can tell which tier an org
is on, predict what its graphs may and may not do, read the quota
badge in the editor, and know where the operator-side levers —
changing a tier, freezing an abuser, handing out a demo — are
documented.

**Concepts introduced**: the `:plan` slug, the access tiers
(anonymous / free / network / dedicated) and the `suspended`
kill-switch, per-tier effects, the egress rate cap, ephemeral demo
orgs.

## The model in one paragraph

On the cloud, every org carries a `:plan` slug. A plan resolves to
two things: the side **effects** its submitted graphs may use (from
Lesson 13) and its **quota** ceilings (fns, list items, outbound
calls/min). That's the whole tier system — a tier is just a named
bundle of "what effects" + "how much". The single source of truth is
`graphden.tenancy.plan`; the human-readable table lives in
[PLANS.md](../PLANS.md).

## The tiers

| Tier | Network? | Own DB? | Fns | Calls/min | Notes |
|---|---|---|---|---|---|
| `anonymous` | ✗ | ✗ | 200 | 0 | locked demo, ephemeral, the fail-safe default |
| `free` (registered) | ✓ metered | ✓ | 500 | 120 | a signed-up account |
| `network` (paid) | ✓ | ✓ | 5,000 | 6,000 | higher ceilings |
| `dedicated` (paid) | ✓ | ✓ | 5,000 | ∞ | its own pod + always-on services (lesson 32) |
| `suspended` | ✗ | ✗ | 0 | 0 | operator freeze — no effects, no writes |

The key line is `anonymous` → `free`. **Anonymous** is what a landing
visitor gets: no outbound network, small ceilings, and the org is
reaped after a TTL — you can *try graphs* with nothing to lose. It is
also the **fail-safe default**: an org with no slug resolves here, so
a mis-provisioned account is locked, never accidentally opened.

**Free (registered)** is what *signing up* gives you (lesson 33),
and it is genuinely useful: base effects PLUS metered `:network`. It
is kept as long as it is used: after 60 days with no sign-in, no API
call, no run and no edit, the org is scheduled for deletion two weeks out and everyone in
it gets an email saying so, plus a last reminder two days before —
one sign-in, one call, one run or one edit keeps it. In
practice that means you can build a personal Telegram bot, keep a few
hundred records, and connect to **your own external database** — the
external `:sql-query` / `:sql-exec` base-fns count as `network`, not
`raw-sql` (arbitrary SQL on the *platform* DB stays forbidden for
everyone; see lesson 13 + [SECURITY_MODEL.md](../SECURITY_MODEL.md)).

Outbound is bounded, not blocked: every external call goes through
the SSRF egress guard (no internal / platform targets) and a
per-tier calls-per-minute cap, so a free bot works but can't be
turned into a DDoS.

## Seeing your quota

The editor shows a small badge above the Explorer's entity list —
`fns: N / max`, the current org's fn count against its ceiling
(fetched from `GET /api/orgs/quota`); hover it and the tooltip names
the plan. It is hidden on an uncapped plan. When you approach the
ceiling it's a nudge to upgrade; if the tooltip ever names
`anonymous` on an account you thought was registered, that's the
fail-safe default telling you the org's `:plan` was never set.

## Changing a tier (operator)

`:org` is a tenant-forbidden entity (lesson 24), so tiers are an
**operator** activity: one platform-only route sets any org's plan,
and `suspended` is the freeze — [PLANS.md § Suspending an
org](../PLANS.md#suspending-an-org-abuse-kill-switch) and
[OPERATIONS.md § Suspending an abusive
org](../OPERATIONS.md#suspending-an-abusive-org) have the route and
the runbook.

Operators also get an extra surface, **Platform** (its account-menu entry appears
only for platform-tier principals): the cross-org registry of
organizations and the platform-access delegation panel — the
UI counterpart to the operator routes above, and the place where
platform capabilities (like `:view-all-stats`) are handed to
delegates.

## Run your own executor (BYO)

Paid tiers (`network` / `dedicated`) can also opt out of executing on
the cloud entirely: the org's graph stays on the hub (editing,
branches, review — everything you've learned), while a **bring-your-own
executor** on the customer's hardware runs the org's app. The flip is
per-ORG and operator-side; the customer's half is one container with a
minted API token. If you want one app self-run and the rest hosted,
that app gets its own org (you can belong to several — lesson 30) and
only that org flips. As the org's owner you can watch it from
**Organization → Executor**: the current mode, whether your executor is
connected to the hub right now, and a ready-to-run snippet with your org
prefilled. The full recipe — both halves, verification, troubleshooting —
is [BYO_RUNBOOK.md](../BYO_RUNBOOK.md).

## Handing out a demo

A landing page mints a throwaway `anonymous` org for a visitor
through an opt-in, rate-limited endpoint — [PLANS.md § Starting an
anonymous demo](../PLANS.md#starting-an-anonymous-demo).

## Try it

(A tenancy-addon instance; steps 2–3 need an operator account.)

1. Sign up a new account (lesson 33) — its org lands on `free`.
   Hover the quota badge: the tooltip names the `free` plan.
2. As the operator, freeze that org with the route from
   [OPERATIONS.md](../OPERATIONS.md#suspending-an-abusive-org)
   (`plan=suspended`). As the account, try to run any fn — the
   effect gate now refuses even `:db`; a create is rejected by the
   zero row-cap.
3. Set it back to `free`; the account works again on its next
   request (the resolver isn't memoised). A typo like `plan=premium`
   is rejected (`:plan/unknown`) and the tier stays put.

## What we glossed over

- **The two-layer effect gate** — how a tier's effect set actually
  gates a submitted graph vs a trusted request
  ([TENANCY_SEAM.md § Effect gate](../TENANCY_SEAM.md#effect-gate)).
- **The egress guard internals** — SSRF classification + the
  per-org rate/size caps ([SECURITY_MODEL.md](../SECURITY_MODEL.md)).
- **Fleet placement** — how an org is sharded to a pod, which is why
  the per-pod rate cap behaves per-org ([SCALING.md](../SCALING.md)).

## Next

[Lesson 35 — Services talking to
services](35-services-talking-to-services.md): one service names
another and calls it over HTTP, with the contract shared in the
graph.

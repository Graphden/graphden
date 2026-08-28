# Lesson 33 — Plans & tiers: what the cloud grants each account

**Goal**: by the end of this lesson you can tell which tier an org
is on, predict what its graphs may and may not do, read the quota
display in the editor, and — as an operator — change an org's tier
(including freezing an abuser) and hand a visitor an anonymous demo.

**Concepts introduced**: the `:plan` slug, the access tiers
(anonymous / free / network / dedicated) and the `suspended`
kill-switch, per-tier effects, the egress rate cap, ephemeral demo
orgs.

## The model in one paragraph

On the cloud, every org carries a `:plan` slug. A plan resolves to
two things: the side **effects** its submitted graphs may use (from
lesson 13) and its **quota** ceilings (fns, list items, outbound
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
| `dedicated` (paid) | ✓ | ✓ | 5,000 | ∞ | its own pod + always-on services (lesson 31) |
| `suspended` | ✗ | ✗ | 0 | 0 | operator freeze — no effects, no writes |

The key line is `anonymous` → `free`. **Anonymous** is what a landing
visitor gets: no outbound network, small ceilings, and the org is
reaped after a TTL — you can *try graphs* with nothing to lose. It is
also the **fail-safe default**: an org with no slug resolves here, so
a mis-provisioned account is locked, never accidentally opened.

**Free (registered)** is what *signing up* gives you (lesson 23),
and it is genuinely useful: base effects PLUS metered `:network`. In
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

The editor shows the current org's usage against its ceilings
(fetched from `GET /api/orgs/quota`) — `plan`, `N / max fns`,
`N / max list items`. When you approach a ceiling it's a nudge to
upgrade; if you ever see plan `anonymous` on an account you thought
was registered, that's the fail-safe default telling you the org's
`:plan` was never set.

## Changing a tier (operator)

`:org` is a tenant-forbidden entity (lesson 23), so tiers are an
**operator** activity. One platform-only route sets any org's plan:

```
POST /api/orgs/plan   (form: name=<org>&plan=<tier>)
```

`plan` must be one of `free` / `network` / `dedicated` / `suspended`
(a typo is rejected, not silently applied). Use it to upgrade a
paying customer — or to **freeze an abuser**: setting `suspended`
gives the org no effects and zero ceilings, so it can neither run nor
create anything, effective on its next request. Restore by setting
its real tier back. (Delete still works, so a frozen tenant can clean
up its own data.)

Operators also get a fourth surface, **Platform** (its account-menu entry appears
only for platform-tier principals): the cross-org registry of
organizations and the platform-access delegation panel — the
UI counterpart to the operator routes above, and the place where
platform capabilities (like `:view-all-stats`) are handed to
delegates.

## Handing out a demo

A landing page mints an anonymous org for a visitor through the
unauthenticated `POST /api/demo/start`, which returns a bearer token
for a throwaway `anonymous` org. It is **off by default** (a public
row-creating endpoint is an abuse surface) — a deploy opts in, and it
is per-IP rate-limited. See [PLANS.md § Starting an anonymous
demo](../PLANS.md).

## Try it

(Operator account on a tenancy-addon instance.)

1. Sign up a new account (lesson 23) — its org lands on `free`.
   Confirm the quota display reads `free`.
2. `POST /api/orgs/plan name=<that-org> plan=suspended`. As that
   account, try to run any fn — the effect gate now refuses even
   `:db`; a create is rejected by the zero row-cap.
3. Set it back to `free`; the account works again on its next
   request (the resolver isn't memoised).
4. Try `plan=premium` — rejected (`:plan/unknown`), the org's tier
   is unchanged.

## What we glossed over

- **The two-layer effect gate** — how a tier's effect set actually
  gates a submitted graph vs a trusted request
  ([TENANCY_SEAM.md § Effect gate](../TENANCY_SEAM.md#effect-gate)).
- **The egress guard internals** — SSRF classification + the
  per-org rate/size caps ([SECURITY_MODEL.md](../SECURITY_MODEL.md)).
- **Fleet placement** — how an org is sharded to a pod, which is why
  the per-pod rate cap behaves per-org ([SCALING.md](../SCALING.md)).

## Next

That's the end of the current tutorial. New lessons are added as
features ship — see [tutorial/README.md](README.md).

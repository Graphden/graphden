# Graphden documentation

Start here. This index is for **readers** — anyone evaluating, self-hosting, or
learning Graphden. (Contributors working *on* the codebase should read the root
[CLAUDE.md](../CLAUDE.md), which additionally indexes the internal engineering
records — ADRs, RFCs, perf notes, migration ledgers.)

## New here? Read in this order

1. [Root README](../README.md) — what Graphden is, and the bet behind it.
2. [PHILOSOPHY.md](PHILOSOPHY.md) — the design principles and rationale.
3. [tutorial/](tutorial/) — hands-on lessons 01–34, from zero to a running
   service. Then the end-to-end [API-poller walkthrough](TUTORIAL_API_POLL.md).
4. [ARCHITECTURE.md](ARCHITECTURE.md) — the data model and execution model.
5. [FAQ.md](FAQ.md) — the sharp objections, answered honestly.

## Using / operating it

| Topic | Doc |
|---|---|
| Deploy & configure (self-host) | [DEPLOYMENT.md](DEPLOYMENT.md) · [CONFIGURATION.md](CONFIGURATION.md) |
| Day-2 ops — backups, restore, PG-HA, upgrades | [OPERATIONS.md](OPERATIONS.md) |
| Monitoring — usage rollups, error viewer, alerting | [MONITORING.md](MONITORING.md) |
| Accounts & sign-in (the opt-in identity module) | [ACCOUNTS.md](ACCOUNTS.md) |
| Keyboard & screen-reader use, and the contract for new UI | [ACCESSIBILITY.md](ACCESSIBILITY.md) |
| Security & tenant isolation | [SECURITY_MODEL.md](SECURITY_MODEL.md) |
| Plans & tiers (what each includes) | [PLANS.md](PLANS.md) |
| Scaling & the executor fleet | [SCALING.md](SCALING.md) · [FLEET_DEPLOY.md](FLEET_DEPLOY.md) |
| BYO executor — run your own executor against the hub | [BYO_RUNBOOK.md](BYO_RUNBOOK.md) |
| What's shipped vs planned | [ROADMAP.md](ROADMAP.md) |
| Error codes | [ERROR_CODES.md](ERROR_CODES.md) |

## Reference (building on Graphden)

| Topic | Doc |
|---|---|
| Writing base-fns & fn-defs | [PACKAGES.md](PACKAGES.md) |
| The type system | [TYPES.md](TYPES.md) |
| Effects & secrets | [SECRETS.md](SECRETS.md) |
| Branches, diff, merge | [VERSIONING.md](VERSIONING.md) |
| Services (long-running fns) | [SERVICES.md](SERVICES.md) |
| Tests (`tests` namespace convention) | [TESTS.md](TESTS.md) |
| Executing a fn (HTTP API) | [EXECUTION.md](EXECUTION.md) |
| Graph constraints | [CONSTRAINTS.md](CONSTRAINTS.md) |
| Distributing packages | [PACKAGE_DISTRIBUTION.md](PACKAGE_DISTRIBUTION.md) |
| Extending below the package layer | [EXTENDING.md](EXTENDING.md) |
| Guide served to external AI authors | [AI_CONTEXT.md](AI_CONTEXT.md) |
| Connecting AI clients (Claude Code / Cursor) to `/mcp` | [MCP_CLIENTS.md](MCP_CLIENTS.md) |

> Design records (ADRs), RFCs, performance notes, and other
> engineering-internal documents live alongside these but are aimed at
> contributors; they are indexed from [CLAUDE.md](../CLAUDE.md) and capture
> history and rationale, not user-facing behaviour.

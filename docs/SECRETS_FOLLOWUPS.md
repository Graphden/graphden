# Secrets-flow followups + project-wide tech-debt

Tracked as TaskCreate entries in the agent harness AND mirrored here
so the list survives context refresh / reload / fresh-agent pickup.

Last updated: 2026-05-29 (after F-1..F-4 + editor-UX switch).

Item `A5` (`:throw :exception` error-message redaction when
`:touched-secret? true`) was explicitly **descoped** by the user and
is not in this list. It can be revived later as `A5-revived` if
needed.

## In progress (this session)

| # | Item | Status |
|---|---|---|
| A1 | Tutorial: update `TUTORIAL_API_POLL.md` to mention `:secret-leaf` shape | done (commit 6ee3a285) |
| A2 | Playwright e2e of Secrets-panel in the new `:secret-leaf` model | done (verified via MCP playwright in session) |
| A3 | Editor: inline `:secret-path` value-form for any `[:secret :text]`-typed slot | done (POST /api/secret-bindings + JS `enterSecretBindingEditMode`, see `SECRETS.md` § Inline secret-path binding) |
| A4 | History-panel: surface `:touched-secret?` (badge + filter) | done (commit 3aa0f030) |
| A6 | `:vault-put` parallel capability gate (currently dormant) | done (commit 6ee3a285) |
| A7 | `:vault-get` deprecation path (back-compat removal) | done (POST /api/secrets/:fn-id/migrate + `:shape` field on list, see `SECRETS.md` § :vault-get deprecation) |
| B8 | Audit the 13 fn-defs failing type-check sweep at startup | done — audit (see `docs/TYPE_CHECK_BACKLOG.md`); per-name fixes deferred |
| B9 | Investigate `register-type-aliases-from-db!` skip warnings (~5-6 form-parsers) | done — audit (see `docs/TYPE_CHECK_BACKLOG.md`); per-name fixes deferred |
| B12 | Naming normalisation: `parent-fn-ids` vs `parent-ids` across JS/docs/code | done (46 occurrences in docs+comments renamed to `parent-ids` matching the schema field; JS dead-fallback dropped) |
| B13 | Tighten `:bearer-token-raw` declared return-type | done (commit 6ee3a285) |

## Out of scope here

| # | Item | Rationale |
|---|---|---|
| B10 | `graphden.system.demo-branches` coverage 2.87% | By design (opt-in seeder); coverage tracker won't go up without changing the feature |
| B11 | `graphden.system.core` coverage 63.72% | Integrant wiring; pre-existing tracked gap, separate effort |

## Doing-order rationale

Cheap wins first (B13, A1, A6), then verification (A2), then
medium UI work (A4), then auditing pre-existing gaps (B8, B9),
then larger architectural pieces (A3, A7). B12 is a project-wide
rename that needs plan-first sign-off before starting.

## How to resume

Each item is a separate commit when done. SECRETS.md gets a
"completed in this followup-cycle" callout per closed item. Use
`TaskList` to see live status; this file is the freeze-dried
mirror.

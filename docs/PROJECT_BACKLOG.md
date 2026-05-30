# Project-wide backlog (post-secrets-flow)

Last updated: 2026-05-29. Mirrors TaskCreate entries in the agent
harness so the list survives context refresh / reload / fresh-agent
pickup.

Items the user **explicitly excluded** from this queue (large /
strategic, picked up separately when time + scope is right):

- Graph-level recursion (`docs/RECURSION.md`)
- I/O client base-fns (HTTP client — ROADMAP 4.4)
- WebSocket live updates (ROADMAP 5.1)
- User + permission system (ROADMAP 5.5)

Everything else outstanding lives below in doing-order.

## Queue

| # | Item | Status | Reference |
|---|---|---|---|
| P1 | Fix 2 biome `useOptionalChain` warnings | done (commit df36643c) | editor-execute-history.js, editor-overlay-arg.js |
| P2 | ROADMAP stale markers (`POST /execute` + Execute button PLANNED→DONE) | done (commit df36643c) | `docs/ROADMAP.md` |
| P3 | B8.2 typevar-slot literal nil/text examples | done (commit df36643c) | `:case` re-typed with key+value typevars (`a` + `b`); `:ex-only-some` documented as B8.3 frontier |
| P4 | B9 `register-type-aliases-from-db!` loader selection | done (commit df36643c) | `type-row-role` discriminates by `:return-type-fn-id`; ~9 startup warnings → 0 |
| P5 | B8.1 string-keyed `[:map :text :text]` classifier extension | done (commit df36643c) | `classify-literal` narrows homogeneous-string-keyed maps; 5 headers fn-defs cleared |
| P6 | B8.3 HOF/typevar under-convergence | done — audit (commit df36643c) + `merge-return-rule` extension; per-case fixes deferred to type-system project | `docs/TYPE_CHECK_BACKLOG.md` § B8.3 — 6 canonical remaining cases categorized |
| P7-C2 | `process-sequence-remove` decomposed | done (commit 16f5194a) | 5 atoms + 2 const + `:cond` skeleton; e2e 400/404/200 |
| P7-C3 | `process-sequence-append` decomposed | done (commit 050471a0) | 6 atoms + 3 const + `:cond` skeleton; e2e 400/400/404/200 |
| P7-C4 | `process-sequence-update` decomposed | done (commit 92ef29d5) | 6 atoms + 3 const + `:cond` skeleton; e2e 400/400/404/200 |
| P7-C5 | `process-delete-entity` decomposed | done (commit ee33ce37) | 10 atoms + 2 const + `:cond` skeleton; 5 paths covering invalid / secret / fn-in-use / ns-non-empty / apply; e2e all 5 |
| P7-S1 | web/html + web/reitit audit | done (this commit) | both clean — only library-adapter wrappers (`hiccup2/html`, `reitit.ring/*`), no heavy delegators. Also audited web/http, web/http-client, web/sql, web/vault, web/branch-router — all clean per skill §3 exception |

## Status: queue empty

Every action item in the queue is closed. The 6 remaining type-check
sweep failures in `docs/TYPE_CHECK_BACKLOG.md` § B8.3 are categorized
as a separate type-system project (HOF/typevar unification,
closure-capture signature mismatch, return-rule `:any` widening) —
not actionable in this queue.

`SECRETS_FOLLOWUPS.md` § "Out of scope": B10 (`system.demo-branches`
coverage, by-design opt-in seeder) and B11 (`system.core` coverage,
integrant wiring) remain documented as separate efforts.

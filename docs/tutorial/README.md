# Graphden Tutorial

> Step-by-step introduction to graphden. Text-only for now —
> per [ROADMAP § Block 0](../ROADMAP.md#block-0--tutorial-framework-continuous)
> the UI integration is a later decision.
>
> **Audience**: someone who can program but has never seen
> graphden before. Each lesson assumes the previous ones and
> nothing else from the project docs.

## How to read this

- Read lessons in order on first pass.
- Each lesson has a **Goal** (what you'll be able to do at the
  end), **Concepts** (vocabulary introduced), and **Try it**
  (something concrete to type into the running editor).
- If a concept needs deeper detail than the lesson gives, the
  lesson links into [ARCHITECTURE.md](../ARCHITECTURE.md),
  [PACKAGES.md](../PACKAGES.md), or wherever it lives.

## Lessons

| # | Lesson | Status |
|---|---|---|
| 01 | [Anatomy of a fn-def](01-fn-defs.md) | ✅ written · ▶ interactive |
| 02 | [Parents and inheritance — single parent, then multiple](02-parents-and-inheritance.md) | ✅ written · ▶ interactive |
| 03 | [Slots and bindings — what they are at the data level](03-slots-and-bindings.md) | ✅ written · ▶ interactive |
| 04 | [Free arguments and how they propagate](04-free-arguments.md) | ✅ written · ▶ interactive |
| 05 | [Types — atomic, refinement, record, union, variant, list](05-types.md) | ✅ written · ▶ interactive |
| 06 | [Higher-order functions and `:fn`-typed slots](06-higher-order-functions.md) | ✅ written · ▶ interactive |
| 07 | [Effects and the `:secret` type-marker](07-effects-and-secrets.md) | ✅ written · ▶ interactive |
| 08 | [Branches — fork, edit, diff, merge](08-branches.md) | ✅ written · ▶ interactive |
| 09 | [Executing a fn — free-arg form, history, cancel](09-executing-a-fn.md) | ✅ written · ▶ interactive |
| 10 | [Services — long-running fns supervised by graphden](10-services.md) | ✅ written |
| 11 | [Packages — namespaces, fns.edn, impls.clj, deps](11-packages.md) | ✅ written |
| 12 | [Composing pages from components](12-components-and-pages.md) | ✅ written · ▶ interactive |
| 13 | [The `:custom-script` escape hatch](13-custom-script-escape-hatch.md) | ✅ written · ▶ interactive |
| 14 | [Distributing packages — publish, install, update, fork](14-distributing-packages.md) | ✅ written |
| 15 | [State — cells, swap, and a graph-native cache](15-state-cells-and-caches.md) | ✅ written · ▶ interactive |
| 16 | [Members — managing who is in your org](16-users-admin.md) | ✅ written · ▶ interactive |
| 17 | [Grants — who may touch what](17-grants.md) | ✅ written · ▶ interactive |
| 18 | [Plans & tiers — what the cloud grants each account](18-plans-and-tiers.md) | ✅ written |
| 19 | [Signing up & signing in: your account](19-signing-up-and-in.md) | ✅ written |
| 20 | [Apps — publishing a fn as a public site](20-apps.md) | ✅ written · ▶ interactive |
| 21 | [Working across organizations](21-working-across-orgs.md) | ✅ written |
| 22 | [Workspaces — scope the editor to your projects](22-workspaces.md) | ✅ written · ▶ interactive |
| 23 | [Finding your way: the lens and the Inspector](23-explorer-and-inspector.md) | ✅ written · ▶ interactive |
| 24 | [Live fragments: htmx from the graph](24-htmx-fragments.md) | ✅ written |
| 25 | Editing the editor: asset overrides | ⏳ planned |
| 26 | [Tests — the `tests` namespace](26-tests.md) | ✅ written · ▶ interactive |
| 27 | [Debugging: traces, the call tree, and catching a request](27-debugging-traces.md) | ✅ written · ▶ interactive |

▶ interactive — the lesson also exists as a guided in-editor tour:
open the editor with `?tutorial=NN` (the landing demo link does this for
lesson 01), or pick “Interactive tutorial” in the account-chip menu —
in an organization workspace the lesson runs on its own `tutorial-NN-*`
branch, and ending it offers branch deletion = full rollback. The tour's
step scripts live in the graph (`app.tour/_tour-lessons`) and are
drift-guarded by `tools/browser-test/edit-tutorial-tour.test.js` —
keep the written lesson's “Try it” section and the tour steps in
sync when either changes.

The picker lists the tours in TEACHING order, grouped into chapters
(Basics → Composing → Running it → The editor → Your organization) — the
numbering above is the written lesson's id, which stays the deep link
(`?tutorial=13`) and is deliberately not the tour's reading order.

The organization tours drive panels only an org owner/admin can open, so
they declare the capability they need (`:requires`). Anywhere that
capability is absent — the public demo, a self-hosted instance with no
tenancy addon — the picker still lists them, disabled, with the reason on
the row.


Lesson 25 will cover the Operate → Assets panel — editing the
editor's own JS/CSS in place (save / revert / diff), the rolling
`?v=` hash, and the JS syntax gate. It stays ⏳ planned until it can
be written self-host-only (the panel is hidden and its writes are
platform-only under the cloud tenancy addon, so the paste-into-the-
editor steps only verify on a single-tenant instance).

New lessons are added as features ship. If a lesson would document
a feature that doesn't yet exist or behaves differently from how
it's described, it stays ⏳ planned until the gap closes.

## End-to-end worked example

Once you've worked through lessons 01–10,
[**Building an API-poller**](../TUTORIAL_API_POLL.md) puts it all
together: a scheduled service that calls an external HTTP API with a
vault-backed bearer token and writes each result into your own Postgres
table — built entirely from fn-defs, no Clojure.

## How to contribute a lesson

A lesson is a short focused walkthrough of **one** concept. Keep:

- ~60-150 lines of markdown total
- One concrete worked example you can paste into the running editor
- Concepts introduced explicitly named (so future lessons can refer)
- No prerequisites beyond the previous lessons in this index

Avoid:

- Re-explaining what earlier lessons already covered (link to them)
- Duplicating reference material that lives in `docs/*.md` (link)
- "Why we did it this way" rationale — that belongs in
  [PHILOSOPHY.md](../PHILOSOPHY.md) and gets linked, not pasted

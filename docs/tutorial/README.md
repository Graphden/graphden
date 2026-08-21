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

Read them top to bottom — the table is in **teaching order**, in the same
five chapters the in-editor picker uses. The number in the first column is
the lesson's **id, not its position**: it is the file name, the deep link
(`?tutorial=13`), and the tour's `:id`, so it stays put when the order
around it changes.

### Basics

| # | Lesson | Status |
|---|---|---|
| 01 | [Anatomy of a fn-def](01-fn-defs.md) | ✅ written · ▶ interactive |
| 02 | [Parents and inheritance — single parent, then multiple](02-parents-and-inheritance.md) | ✅ written · ▶ interactive |
| 03 | [Slots and bindings — what they are at the data level](03-slots-and-bindings.md) | ✅ written · ▶ interactive |
| 04 | [Free arguments and how they propagate](04-free-arguments.md) | ✅ written · ▶ interactive |
| 05 | [Types — atomic, refinement, record, union, variant, list](05-types.md) | ✅ written · ▶ interactive |

### Composing

| # | Lesson | Status |
|---|---|---|
| 06 | [Higher-order functions and `:fn`-typed slots](06-higher-order-functions.md) | ✅ written · ▶ interactive |
| 12 | [Composing pages from components](12-components-and-pages.md) | ✅ written · ▶ interactive |
| 13 | [The `:custom-script` escape hatch](13-custom-script-escape-hatch.md) | ✅ written · ▶ interactive |
| 15 | [State — cells, swap, and a graph-native cache](15-state-cells-and-caches.md) | ✅ written · ▶ interactive |
| 24 | [Live fragments: htmx from the graph](24-htmx-fragments.md) | ✅ written |

### Running it

| # | Lesson | Status |
|---|---|---|
| 09 | [Executing a fn — free-arg form, history, cancel](09-executing-a-fn.md) | ✅ written · ▶ interactive |
| 07 | [Effects and the `:secret` type-marker](07-effects-and-secrets.md) | ✅ written · ▶ interactive |
| 26 | [Tests — the `tests` namespace](26-tests.md) | ✅ written · ▶ interactive |
| 27 | [Debugging: traces, the call tree, and catching a request](27-debugging-traces.md) | ✅ written · ▶ interactive |

### The editor

| # | Lesson | Status |
|---|---|---|
| 23 | [Finding your way: the lens and the Inspector](23-explorer-and-inspector.md) | ✅ written · ▶ interactive |
| 22 | [Workspaces — scope the editor to your projects](22-workspaces.md) | ✅ written · ▶ interactive |
| 08 | [Branches — fork, edit, diff, merge](08-branches.md) | ✅ written · ▶ interactive |
| 25 | [Editing the editor: asset overrides](25-asset-overrides.md) | ✅ written · ▶ interactive |

### Your organization

| # | Lesson | Status |
|---|---|---|
| 16 | [Members — managing who is in your org](16-users-admin.md) | ✅ written · ▶ interactive |
| 17 | [Grants — who may touch what](17-grants.md) | ✅ written · ▶ interactive |
| 20 | [Apps — publishing a fn as a public site](20-apps.md) | ✅ written · ▶ interactive |
| 11 | [Packages — namespaces, fns.edn, impls.clj, deps](11-packages.md) | ✅ written |
| 14 | [Distributing packages — publish, install, update, fork](14-distributing-packages.md) | ✅ written · ▶ interactive |
| 21 | [Working across organizations](21-working-across-orgs.md) | ✅ written · ▶ interactive |
| 10 | [Services — long-running fns supervised by graphden](10-services.md) | ✅ written · ▶ interactive |
| 19 | [Signing up & signing in: your account](19-signing-up-and-in.md) | ✅ written · ▶ interactive |
| 18 | [Plans & tiers — what the cloud grants each account](18-plans-and-tiers.md) | ✅ written · ▶ interactive |

▶ interactive — the lesson also exists as a guided in-editor tour:
open the editor with `?tutorial=NN` (the landing demo link does this for
lesson 01), or pick “Interactive tutorial” in the account-chip menu —
in an organization workspace the lesson runs on its own `tutorial-NN-*`
branch, and ending it offers branch deletion = full rollback. The tour's
step scripts live in the graph (`app.tour/_tour-lessons`) and are
drift-guarded by `tools/browser-test/edit-tutorial-tour.test.js` —
keep the written lesson's “Try it” section and the tour steps in
sync when either changes.

Two lessons have no tour: **11** is about files on disk and `bb rebuild`,
which the editor cannot show, and **24** is a route-wiring marathon that
reads better as text than as thirty steps. Both sit in the chapter their
subject belongs to, which is why the ▶ column is worth reading.

The organization tours drive surfaces not every session has, so they
declare what they need (`:requires`) — a capability (`manage-users`,
`publish-packages`, …), or a named condition: the services tour needs the
**dedicated plan** (services run on an executor the org owns), the
cross-org tour needs organizations to exist at all, and the
asset-override tour needs a single-tenant instance. Anywhere the condition
fails — the public demo, a free-plan org, a self-hosted instance with no
tenancy addon — the picker still lists the lesson, disabled, with the
reason on the row.


Lesson 25 is written **self-host-only** and its tour declares that
(`:requires "assets"`): the Assets panel is hidden under the cloud
tenancy addon and its writes are platform-only, because an editable
shared frontend would be a stored-XSS surface across tenants. On a
single-tenant instance both halves apply as written.

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

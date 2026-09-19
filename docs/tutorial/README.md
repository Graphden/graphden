# Graphden Tutorial

> Step-by-step introduction to graphden: text lessons, most of them
> paired with a guided in-editor tour (the ▶ column below).
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
five chapters the in-editor picker uses, and the numbering is
**sequential in that order**: the number is the file name, the deep link
(`?tutorial=14`), and the tour's `:id`. Inserting a lesson mid-sequence
renumbers everything after it — that is a mechanical, repo-wide
search-and-replace (ids appear only in file names, links, tour `:id`s and
prose references), so keep the numbers honest rather than appending out
of order.

### Basics

| # | Lesson | Status |
|---|---|---|
| 01 | [Anatomy of a fn-def](01-fn-defs.md) | ✅ written · ▶ interactive |
| 02 | [Reading a card — rows, unfolding, closed cards, descriptions](02-reading-a-card.md) | ✅ written · ▶ interactive |
| 03 | [Parents and inheritance — single parent, then multiple](03-parents-and-inheritance.md) | ✅ written · ▶ interactive |
| 04 | [Slots and bindings — what they are at the data level](04-slots-and-bindings.md) | ✅ written · ▶ interactive |
| 05 | [Free arguments and how they propagate](05-free-arguments.md) | ✅ written · ▶ interactive |
| 06 | [Types — atomic, refinement, record, union, variant, list](06-types.md) | ✅ written · ▶ interactive |

### Composing

| # | Lesson | Status |
|---|---|---|
| 07 | [Higher-order functions and `:fn`-typed slots](07-higher-order-functions.md) | ✅ written · ▶ interactive |
| 08 | [Composing pages from components](08-components-and-pages.md) | ✅ written · ▶ interactive |
| 09 | [The `:custom-script` escape hatch](09-custom-script-escape-hatch.md) | ✅ written · ▶ interactive |
| 10 | [State — cells, swap, and a graph-native cache](10-state-cells-and-caches.md) | ✅ written · ▶ interactive |
| 11 | [Recursion: loops without cycles](11-recursion.md) | ✅ written · ▶ interactive |
| 12 | [Live fragments: htmx from the graph](12-htmx-fragments.md) | ✅ written |

### Running it

| # | Lesson | Status |
|---|---|---|
| 13 | [Executing a fn — free-arg form, history, cancel](13-executing-a-fn.md) | ✅ written · ▶ interactive |
| 14 | [Effects and the `:secret` type-marker](14-effects-and-secrets.md) | ✅ written · ▶ interactive |
| 15 | [Tests — the `tests` namespace](15-tests.md) | ✅ written · ▶ interactive |
| 16 | [Debugging: traces, the call tree, and catching a request](16-debugging-traces.md) | ✅ written · ▶ interactive |
| 17 | [When something breaks, and when it just repeats: the problem filters](17-errors-and-diagnostics.md) | ✅ written · ▶ interactive |

### The editor

| # | Lesson | Status |
|---|---|---|
| 18 | [Finding your way: kind filters and the Inspector](18-explorer-and-inspector.md) | ✅ written · ▶ interactive |
| 19 | [Working without the mouse — keyboard & accessibility](19-keyboard-and-accessibility.md) | ✅ written · ▶ interactive |
| 20 | [Filters and views — look at the part of the graph you mean](20-workspaces.md) | ✅ written · ▶ interactive |
| 21 | [Branches — fork, edit, diff, merge](21-branches.md) | ✅ written · ▶ interactive |
| 22 | [Review — propose, approve, protected merge](22-review.md) | ✅ written · ▶ interactive |
| 23 | [Editing the editor: asset overrides](23-asset-overrides.md) | ✅ written · ▶ interactive |
| 24 | [Version history: what changed, and going back](24-version-history.md) | ✅ written · ▶ interactive |

### Your organization

| # | Lesson | Status |
|---|---|---|
| 25 | [Members — managing who is in your org](25-users-admin.md) | ✅ written · ▶ interactive |
| 26 | [Grants — who may touch what](26-grants.md) | ✅ written · ▶ interactive |
| 27 | [Roles — capabilities as a bundle](27-roles.md) | ✅ written · ▶ interactive |
| 28 | [Apps — publishing a fn as a public site](28-apps.md) | ✅ written · ▶ interactive |
| 29 | [Packages — namespaces, fns.edn, impls.clj, deps](29-packages.md) | ✅ written |
| 30 | [Distributing packages — publish, install, update, fork](30-distributing-packages.md) | ✅ written · ▶ interactive |
| 31 | [Working across organizations](31-working-across-orgs.md) | ✅ written · ▶ interactive |
| 32 | [Working offline: a local instance, git snapshots, push/pull](32-offline-and-push.md) | ✅ written |
| 33 | [Services — long-running fns supervised by graphden](33-services.md) | ✅ written · ▶ interactive |
| 34 | [Signing up & signing in: your account](34-signing-up-and-in.md) | ✅ written · ▶ interactive |
| 35 | [Plans & tiers — what the cloud grants each account](35-plans-and-tiers.md) | ✅ written · ▶ interactive |
| 36 | [Services talking to services — the contract lives in the graph](36-services-talking-to-services.md) | ✅ written · ▶ interactive |
| 37 | [Queues — asynchronous work between services](37-queues.md) | ✅ written |
| 38 | [The Marketplace: themes, keyboard layouts, and what others published](38-marketplace-themes-keymaps.md) | ✅ written · ▶ interactive |
| 39 | [The package lifecycle — both sides of a version](39-package-lifecycle.md) | ✅ written |
| 40 | [AI clients and API tokens: the graph over `/mcp`](40-ai-clients-and-api-tokens.md) | ✅ written |

▶ interactive — the lesson also exists as a guided in-editor tour:
open the editor with `?tutorial=NN` (the landing demo link does this for
Lesson 01), or pick “Interactive tutorial” in the account-chip menu —
in an organization workspace the lesson runs on its own `tutorial-NN-*`
branch, and ending it offers branch deletion = full rollback. The tour's
step scripts live in the graph (`app.tour/_tour-lessons`) and are
drift-guarded by `tools/browser-test/edit-tutorial-tour.test.js` —
keep the written lesson's “Try it” section and the tour steps in
sync when either changes.

The catalogue remembers what you have read (in your browser, not in the
graph) and marks it `✓ done`. That mark is yours: the `↺` beside a
finished lesson takes it back off, and **Clear progress** in the
catalogue's footer clears the whole history at once, behind a
confirmation. Lessons have editions (`:version` in the tour script,
bumped when a lesson's flow changes): a lesson you finished that has
since changed is chipped **updated** until you take it again, a lesson
that was not in the catalogue the last time you opened it is chipped
**new**, and the account-chip menu counts both on its “Interactive
tutorial” row. The catalogue's header counts what you have done, what
this session can run, and how many lessons there are. Finishing a lesson offers what to read next right there —
the lesson that follows, plus the first one you have not read when that
is a different lesson — and starting it from the dialog cleans the
finished lesson up first (deleting its branch, or the rows it created).

Six lessons have no tour: **29** is about files on disk and
`bb rebuild`, which the editor cannot show; **12** is a route-wiring
marathon that reads better as text than as thirty steps; **32** is
about running a second, local instance — something one editor session
cannot demonstrate; **37** needs a second service running while the
tour would hold the page; **39** is a two-role loop that a tour cannot
play from one seat without inventing the other person; and **40** is
mostly set-up in a terminal and an AI client, outside the editor. All
six sit in the chapter their subject belongs to, which is why the ▶
column is worth reading.

The organization tours drive surfaces not every session has, so they
declare what they need (`:requires`) — a capability (`manage-users`,
`publish-packages`, …), or a named condition: the services tour needs the
**dedicated plan** (services run on an executor the org owns), the
services-talking-to-services tour additionally needs **your own
instance** (it names the editor's web-server, which a cloud organization
does not own), the cross-org tour needs organizations to exist at all,
and the asset-override tour needs a single-tenant instance. Anywhere the condition
fails — the public demo, a free-plan org, a self-hosted instance with no
tenancy addon — the picker still lists the lesson, disabled, with the
reason on the row.

Lesson 23 is written **self-host-only** and its tour declares that
(`:requires "assets"`): the Assets panel is hidden under the cloud
tenancy addon and its writes are platform-only, because an editable
shared frontend would be a stored-XSS surface across tenants. On a
single-tenant instance both halves apply as written.

New lessons are added as features ship. If a lesson would document
a feature that doesn't yet exist or behaves differently from how
it's described, it stays ⏳ planned until the gap closes.

## End-to-end worked example

Once you've worked through the Basics and Composing chapters
(lessons 01–12) plus Services (lesson 33),
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

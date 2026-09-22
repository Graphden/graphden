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
(`?tutorial=16`), and the tour's `:id`. Inserting a lesson mid-sequence
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
| 06 | [Lists — seed, append, close](06-lists.md) | ✅ written · ▶ interactive |
| 07 | [Optional, required and sealed — what a slot decides beside its value](07-optional-required-sealed.md) | ✅ written · ▶ interactive |
| 08 | [Types — atomic, refinement, record, union, variant, list](08-types.md) | ✅ written · ▶ interactive |

### Composing

| # | Lesson | Status |
|---|---|---|
| 09 | [Higher-order functions and `:fn`-typed slots](09-higher-order-functions.md) | ✅ written · ▶ interactive |
| 10 | [Composing pages from components](10-components-and-pages.md) | ✅ written · ▶ interactive |
| 11 | [The `:custom-script` escape hatch](11-custom-script-escape-hatch.md) | ✅ written · ▶ interactive |
| 12 | [State — cells, swap, and a graph-native cache](12-state-cells-and-caches.md) | ✅ written · ▶ interactive |
| 13 | [Recursion: loops without cycles](13-recursion.md) | ✅ written · ▶ interactive |
| 14 | [Live fragments: htmx from the graph](14-htmx-fragments.md) | ✅ written |

### Running it

| # | Lesson | Status |
|---|---|---|
| 15 | [Executing a fn — free-arg form, history, cancel](15-executing-a-fn.md) | ✅ written · ▶ interactive |
| 16 | [Effects and the `:secret` type-marker](16-effects-and-secrets.md) | ✅ written · ▶ interactive |
| 17 | [Tests — the `tests` namespace](17-tests.md) | ✅ written · ▶ interactive |
| 18 | [Debugging: traces, the call tree, and catching a request](18-debugging-traces.md) | ✅ written · ▶ interactive |
| 19 | [When something breaks, and when it just repeats: the problem filters](19-errors-and-diagnostics.md) | ✅ written · ▶ interactive |

### The editor

| # | Lesson | Status |
|---|---|---|
| 20 | [Finding your way: kind filters and the Inspector](20-explorer-and-inspector.md) | ✅ written · ▶ interactive |
| 21 | [Working without the mouse — keyboard & accessibility](21-keyboard-and-accessibility.md) | ✅ written · ▶ interactive |
| 22 | [Filters and views — look at the part of the graph you mean](22-workspaces.md) | ✅ written · ▶ interactive |
| 23 | [Branches — fork, edit, diff, merge](23-branches.md) | ✅ written · ▶ interactive |
| 24 | [Review — propose, approve, protected merge](24-review.md) | ✅ written · ▶ interactive |
| 25 | [Editing the editor: asset overrides](25-asset-overrides.md) | ✅ written · ▶ interactive |
| 26 | [Version history: what changed, and going back](26-version-history.md) | ✅ written · ▶ interactive |

### Your organization

| # | Lesson | Status |
|---|---|---|
| 27 | [Members — managing who is in your org](27-users-admin.md) | ✅ written · ▶ interactive |
| 28 | [Grants — who may touch what](28-grants.md) | ✅ written · ▶ interactive |
| 29 | [Roles — capabilities as a bundle](29-roles.md) | ✅ written · ▶ interactive |
| 30 | [Apps — publishing a fn as a public site](30-apps.md) | ✅ written · ▶ interactive |
| 31 | [Packages — namespaces, fns.edn, impls.clj, deps](31-packages.md) | ✅ written |
| 32 | [Distributing packages — publish, install, update, fork](32-distributing-packages.md) | ✅ written · ▶ interactive |
| 33 | [Working across organizations](33-working-across-orgs.md) | ✅ written · ▶ interactive |
| 34 | [Working offline: a local instance, git snapshots, push/pull](34-offline-and-push.md) | ✅ written |
| 35 | [Services — long-running fns supervised by graphden](35-services.md) | ✅ written · ▶ interactive |
| 36 | [Signing up & signing in: your account](36-signing-up-and-in.md) | ✅ written · ▶ interactive |
| 37 | [Plans & tiers — what the cloud grants each account](37-plans-and-tiers.md) | ✅ written · ▶ interactive |
| 38 | [Services talking to services — the contract lives in the graph](38-services-talking-to-services.md) | ✅ written · ▶ interactive |
| 39 | [Queues — asynchronous work between services](39-queues.md) | ✅ written |
| 40 | [The Marketplace: themes, keyboard layouts, and what others published](40-marketplace-themes-keymaps.md) | ✅ written · ▶ interactive |
| 41 | [The package lifecycle — both sides of a version](41-package-lifecycle.md) | ✅ written |
| 42 | [AI clients and API tokens: the graph over `/mcp`](42-ai-clients-and-api-tokens.md) | ✅ written |

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

Every tour is the SHOWN half of its lesson; the written lesson is the
whole of it — the model behind the steps, the branches of the topic the
tour does not walk (cardinality and per-branch services in 35, closure
capture in 09, …), the same thing as fn-defs, and a "what we glossed
over" list. The tour says so at the hand-over: its last step links to
the written lesson and says in one line what the text adds, and the
catalogue repeats that line under every lesson you have marked `✓ done`
(the `↗` on any row opens the text). The six lessons without a tour are
listed in the catalogue too, as **text only** rows that open the text —
so the catalogue is the whole tutorial, not just the part the editor can
walk. The link target is `https://graphden.dev/tutorial/` plus the
lesson's file name; a deployment serving its own copy of the lessons (a
translation, an intranet mirror) sets `GRAPHDEN_TUTORIAL_BASE` to it.

Six lessons have no tour: **31** is about files on disk and
`bb rebuild`, which the editor cannot show; **14** is about MOUNTING a
route (`:all` and a rebuild, or publishing as an app — lesson 30) and
watching a page refresh itself, neither of which one editor session can
demonstrate (the component preview cannot fetch a fragment); **34** is
about running a second, local instance — something one editor session
cannot demonstrate; **39** needs a second service running while the
tour would hold the page; **41** is a two-role loop that a tour cannot
play from one seat without inventing the other person; and **42** is
mostly set-up in a terminal and an AI client, outside the editor. All
six sit in the chapter their subject belongs to, which is why the ▶
column is worth reading.

The organization tours drive surfaces not every session has, so they
declare what they need (`:requires`) — a capability (`manage-users`,
`publish-packages`, …), or a named condition: the services tour needs the
**dedicated plan** (services run on an executor the org owns — the
services-talking-to-services tour builds the listener it then calls, so
it needs nothing more), the cross-org tour needs organizations to exist at all,
and the asset-override tour needs a single-tenant instance. Anywhere the condition
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

Once you've worked through the Basics and Composing chapters
(lessons 01–14) plus Services (lesson 35),
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

# Lesson 38 — The package lifecycle: both sides of a version

**Goal**: by the end of this lesson you have walked the whole loop
between a package's **author** and its **consumer** on one instance:
publish, install and build on it, follow versions, fix a package you
did not write, send the fix back, accept it, ship the next version,
retire the old one — and you know which of those doors changes when the
other party is in another organization or on another graphden.

**Concepts**: the two roles, **reference vs inherit** (why one follows
versions and the other does not), **fork when the source is out of
reach**, the **contribution doors** (a proposal on a shared instance, a
hub push or an EDN bundle across instances), **retiring a version**,
and the **install audit** as the author's view of who is where.

This lesson assumes [Lesson 29](29-distributing-packages.md) (publish /
install / update / fork / withdraw, one action at a time) and
[Lesson 21](21-review.md) (propose → approve → merge). Here they are
strung into the loop people actually run.

## Two roles, one instance

The author and the consumer are usually different people — a teammate,
another organization on the cloud, someone on another graphden. To walk
both halves without a second account, this lesson plays them on two
**branches**:

| Branch | Plays | Holds |
|---|---|---|
| `vendor` | the author's trunk | the source namespace `acme.greet` |
| `site` | the consumer's project | a pin on `acme-greet` and a fn built on it |

Both fork from `main`, so neither sees the other's edits — which is
exactly the situation between two parties. The last section says what
changes when they really are two parties.

## Part 1 — the author publishes

1. Click the branch chip; in the create row type `vendor` and create
   it (it forks from the branch you are on — be on `main`).
2. At the bottom of the Explorer click **New namespace** and type
   `acme.greet`. Hover the new row, click its **+**, choose **New
   graph…**, type `greet`, Enter. On the card click **set parent…**
   and pick `const`; click the **+** on `:value`, **Bind literal**,
   enter `"Hello, world."` (a JSON string, quotes included), Save.
3. Hover the `acme.greet` row and click **⬆**. Package name
   `acme-greet`, version `1.0.0`, Publish. (On a cloud with
   organizations, tick **Public** if the consumer is outside yours.)

That is the author's release: an immutable `:package-version` row
holding the namespace's fn-defs, visible in the packages chip and on
the Marketplace.

## Part 2 — the consumer installs and builds on it

1. Switch to `main`, create the branch `site` from it.
2. Open the **packages** chip, expand **+ Install a package**, click
   **Install** on `acme-greet 1.0.0`. The pin appears in the table; the
   Explorer gains a namespace `acme.greet@1-0-0` — the version's fns,
   materialized once and **referenced**, not copied
   ([Lesson 29 § Install](29-distributing-packages.md#install--by-reference-not-by-copy)).
   Note that `acme.greet` itself is *not* here: the author's source
   lives on `vendor`, out of the consumer's reach — as it would be
   across organizations.
3. New namespace `shop`; in it, **New graph…** → `welcome` with parent
   `to-str`. Click the **+** on `:value`, choose **Bind fn-ref**, and
   pick `greet` under `acme.greet@1-0-0`.
4. `⋯` → **▶ Run** on `welcome`: `"Hello, world."`.

**Reference, don't inherit.** `welcome` *references* the package fn
(a binding). Had you made `greet` its **parent** instead, `welcome`
would stay on `1.0.0` forever: an update rewrites your project's
*bindings* to the new version's namespace, but parent links are
identity-level and never rewritten
([ADR — parent-set identity](../adr/ADR-parent-set-identity.md)).
Inherit from a package fn only when you mean "this exact version";
reference it when you mean "whatever version I have pinned".

## Part 3 — the consumer fixes what they do not own

The period at the end of `"Hello, world."` is, let us say, a bug. The
consumer has two doors, and which one is open depends on where the
author's **source** is.

### Door A — the source is on this instance: propose

On a shared instance (a teammate's package), the source namespace is
a branch away. Do not fork — edit the source on a branch of the
author's trunk and propose it, exactly Lesson 21:

1. Switch to `vendor`. In its `⋯` menu choose **⚙ Protection…** and
   set **Required approvals** to `1` — the author's trunk now refuses
   unreviewed merges. (You are wearing the author's hat for this step.)
2. Still on `vendor`, create the branch `fix-greet` (so it forks from
   `vendor`). Open `acme.greet.greet` there and change `:value` to
   `"Hello, world!"`.
3. Switch back to `vendor`. On the `fix-greet` row: `⋯` → **📤
    Propose for review**. Now as the author: `✅` on the row (the badge
    reads `1/1`), then `⇢` — the fix is on the trunk. Nothing has
    shipped yet: the published `1.0.0` is immutable, and the consumer
    on `site` still runs `"Hello, world."`.

### Door B — the source is out of reach: fork

When the package came from another organization or was pulled from
another graphden ([Lesson 29 § another registry](29-distributing-packages.md#installing-from-another-graphdens-registry)),
there is no branch of the author's to edit. **Fork** copies the
version's fns into *your* graph at their original namespace, where
they are yours to edit:

- On `site`, open the packages chip and click **Fork** on
  `acme-greet 1.0.0`. Reload: the Explorer now has `acme.greet` with
  an editable `greet` — a copy on your branch, no pin written. (Try
  it now if you like; `site` does not see `vendor`'s source, so this
  is the out-of-reach case. Delete the copy afterwards or leave it —
  it does not affect the rest.)
- Edit the copy, then point `welcome` at it (**Bind fn-ref** again,
  this time `greet` under `acme.greet`) — or keep the pin for the
  fns you did not change and reference only the fixed one.
- **When Fork refuses** — a namespace built into *this* instance from
  disk answers `package-owned`:
  [Lesson 29 § Fork](29-distributing-packages.md#fork--copy-on-write-when-you-want-to-edit).

Sending the fix back from a fork is the **contribution door**; it
depends on where the author is:

| The author is… | Send the fix as… |
|---|---|
| a teammate on this instance | Door A — no fork needed |
| running a hub you can reach | push your branch: `push/<branch>`, owner-stamped, reviewed on the hub with Δ compare ([Lesson 31 § 3](31-offline-and-push.md#3-push-your-work-to-the-hub)) |
| anywhere you can send a file | the fn-defs as an EDN bundle; the author applies it to a review branch (below) |

The bundle door is one request. The author lands it on a fresh
branch, compares, merges:

```bash
curl -X POST "http://localhost:9002/api/import/graph?target=contrib/greet&create=true" \
  -H "Authorization: Bearer $AUTH_TOKEN" \
  -H "Content-Type: application/edn" \
  --data-binary '{:fns [{:name :greet :namespace "acme.greet"
                         :parent :const :args {:value "Hello, world!"}}]}'
# → {"ok":true,"branch":"contrib/greet","fn-ids":[…],"skipped-owned":[],"adopted":[]}
```

`skipped-owned` lists any fn the bundle tried to write over a built-in
package — the same protection Fork enforces, reported instead of
refused because an import is a snapshot, not a copy of one package.

## Part 4 — the author ships, the consumer follows

1. On `vendor`, hover `acme.greet`, **⬆**, `acme-greet` `1.0.1`,
    Publish. The version is checked against `1.0.0` before the row is
    written: same fns, same args, a changed value — compatible, so a
    patch number is right. (A removed fn or a narrowed arg would be
    refused under `1.x` and need `2.0.0` — [Lesson 29 § Publish](29-distributing-packages.md#publish--freeze-a-namespace-into-the-registry).)
2. On `site`, open the packages chip, type `1.0.1` in the installed
    row's version box and click `↑`. The pin moves and every binding
    of yours that pointed into `acme.greet@1-0-0` now points into
    `acme.greet@1-0-1` — `welcome` included.
3. **▶ Run** `welcome`: `"Hello, world!"`. Type `1.0.0`, `↑` — back to
    the period; `1.0.1`, `↑` — forward again. A consumer who pinned
    `^1.0` gets the same move by typing `latest`.

### Retiring the old version

1. Withdraw `1.0.0`: it is no longer pinned by any branch, so it goes.

    ```bash
    curl -X DELETE "http://localhost:9002/api/packages/withdraw?name=acme-greet&version=1.0.0" \
      -H "Authorization: Bearer $AUTH_TOKEN"
    # → {"ok":true,"withdrawn":"acme-greet"}
    ```

    Now try `version=1.0.1`: **409 `still-installed`** — `site` pins
    it. The gate is per *version*: a branch that moved on does not
    keep the old row alive; a branch that has not moved keeps its
    version until it does.
2. Before withdrawing anything on a real registry, look at the
    **Organization** surface's packages section
    ([Lesson 29 § Governance](29-distributing-packages.md#governance--the-organization-surfaces-packages-view)):
    the **install audit** lists every pin — which branch is on which
    version — so the author knows who a withdrawal would hit and who
    has not updated yet.

## Cleanup

Uninstall on `site` (`×` on the pin) and delete `site` from the
branch popover. `vendor` and `fix-greet` stay: a merged source cannot
be deleted while its target exists, and a branch with children cannot
either — **archive** them instead (`⋯` → Archive), which folds them
into the popover's Merged group ([Lesson 20](20-branches.md)).

## The same loop over HTTP

Every step above has a JSON sibling; `?branch=` picks the branch the
request acts on.

| Step | Request |
|---|---|
| publish | `POST /api/packages/publish?branch=vendor` `{"name":"acme-greet","version":"1.0.0","ns-root":"acme.greet"}` |
| install | `POST /api/packages/install?branch=site` `{"name":"acme-greet","version":"1.0.0"}` → `{"ok":true,"namespace":"acme.greet@1-0-0",…}` |
| fork | `POST /api/packages/fork?branch=site` `{"name":"acme-greet","version":"1.0.0"}` → `{"ok":true,"forked":1}` or `{"ok":false,"reason":"package-owned","owned":[…]}` |
| propose / approve / merge | `POST /api/branches/fix-greet/propose`, `POST /api/branches/fix-greet/approve`, `POST /api/branches/vendor/merge` `{"source":"fix-greet"}` |
| update / rollback | `POST /api/packages/update?branch=site` `{"name":"acme-greet","version":"1.0.1"}` → `{"ok":true,"from":"1.0.0","to":"1.0.1","rewritten-refs":1}` |
| uninstall | `DELETE /api/packages/uninstall?name=acme-greet&branch=site` |
| withdraw | `DELETE /api/packages/withdraw?name=acme-greet&version=1.0.0` |
| contribute a bundle | `POST /api/import/graph?target=<branch>&create=true` with an EDN `{:fns […]}` body |

## When the two parties really are two

| Situation | What changes |
|---|---|
| Same instance, same org | Nothing — Door A is the whole story. |
| Same cloud, another org | The consumer sees the version only if it was published **Public**; the source is out of reach, so Door B. |
| Another graphden | The consumer pulls the version through the remote-install form (a mirror); Door B, and the fix travels as a hub push or a bundle. |
| A package built into the instance | Neither door: the change is a `fns.edn` edit and a `bb rebuild` (Lesson 28). |

## What we glossed over

- **Marketplace metadata** — description, category, tags, the ★
  rating and the ↓ install count the author sees: [Lesson 37](37-marketplace-themes-keymaps.md).
- **Suggested changes** on a proposal (a reviewer's counter-edit,
  applied with one click) and anchored review comments: [Lesson 21](21-review.md).
- **Dependencies** — a bundle records the external fn-names it needs;
  install and fork refuse with `missing-dependencies` when the target
  graph lacks one.
- **Secrets in a package** — a fn that reads a secret publishes with a
  manifest of the paths it needs, and install lists them as
  `needs-definition` until the consumer defines them
  ([Lesson 13](13-effects-and-secrets.md)).

## Next

[Lesson 39 — AI clients and API tokens](39-ai-clients-and-api-tokens.md):
letting an AI coding client read, extend and run the graph on a branch
of its own, and the tokens that bound what it may do. The written docs
continue where the tutorial stops:
[docs/PACKAGE_DISTRIBUTION.md](../PACKAGE_DISTRIBUTION.md) for the
registry's design and API, [docs/MARKETPLACE.md](../MARKETPLACE.md)
for the marketplace.

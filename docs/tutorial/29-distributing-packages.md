# Lesson 29 — Distributing packages: publish, install, update, fork

**Goal**: by the end of this lesson you can publish a namespace
as an immutable package version, then browse the registry and
install / update / roll back / uninstall / fork packages — all
without leaving the graph. Placement follows intent: **install is
a build act**, so it lives on the **Build** surface's *packages*
context-bar chip; **publish is an authoring act on what you built**,
so it lives as a **⬆ action on the namespace** in the Explorer.

**Concepts introduced**: `registry`, `:package-version`,
`publish`, `pin` (`:package-install`), `reference-install` vs
`fork` (copy-on-write), `version constraint` / `latest` /
`rollback`, `withdraw`, the **packages chip** (Build surface) and the
per-namespace **⬆ publish** action, publish **visibility**
(org-private vs public) and the `publish-packages` capability,
and the Organization surface's **governance view**.

## Authoring vs distributing

Lesson 28 was about **authoring** a package — the `fns.edn` /
`impls.clj` / `package.edn` files on disk that load at startup.
This lesson is about **distributing** one: taking a namespace
that already lives in the graph and turning it into a versioned,
installable artifact other branches (and, later, other people)
can pull in.

Two different things share the word "package":

| On disk (Lesson 28) | In the registry (this lesson) |
|---|---|
| A directory loaded at boot | A `:package-version` row: an immutable snapshot of a namespace's fn-defs |
| One copy, shared by the whole install | Named + semver-versioned; many versions coexist |
| Changes when you edit the files + rebuild | Frozen once published — re-publishing the same `(name, version)` is rejected |

## Publish — freeze a namespace into the registry

Publishing exports the fn-def subtree rooted at a namespace and
stores it as a `:package-version`. Take a `mycorp` namespace holding
one fn, `:greet` (a namespace you make in the Explorer; Lesson 28's
on-disk package gives you `mycorp.hello`, which works the same way):

```bash
curl -X POST http://localhost:9002/api/packages/publish \
  -H "Authorization: Bearer $AUTH_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"mycorp-hello","version":"1.0.0","ns-root":"mycorp"}'
# → {"ok":true,"name":"mycorp-hello","version":"1.0.0","fn-count":1,...}
```

`ns-root` is the namespace to snapshot; `name` + `version` are
how the registry indexes it. Change `:greet` and publish again as
`1.1.0` — now the registry holds **both** versions.
`GET /api/packages` lists the index.

The version number is checked, not just recorded. Before writing the
row, publish diffs the bundle against the newest version already
below the one you named: a public fn-def that disappeared, an arg
that is gone or newly required or narrower, a binding you dropped
(so callers must now supply that arg), a wider return type. If the
bundle breaks `1.1.0` and you called it `1.2.0`, the answer is
`{"ok":false,"reason":"breaking-change","previous":"1.1.0","changes":[…]}`
with each change spelled out (`kind`, `fn`, `arg`, `old`, `new`), and
nothing is written. Call it `2.0.0` and it publishes — a break has to
leave the previous version's `^` range, so a consumer pinned with
`^1.1` never auto-advances into it. Adding fn-defs, optional args or
wider types is compatible and publishes under any higher version.

You can also publish **from the editor** — and because publishing
is an authoring act on a namespace, it lives *on the namespace*.
In the Explorer, hover a namespace row and click its **⬆** button
(next to rename / add / hide). A small popover opens with the
package **name** (pre-filled from the namespace's last segment) and
a **version**; the namespace itself is the `ns-root`. Click
**Publish** — the same export-and-freeze step, no `curl` needed, and
it confirms with the published fn-count. The `curl` above is the
programmatic equivalent for scripts / CI. Either way, publishing is
a deliberate "author decides to release" action; installing happens
on the packages chip below.

(The **⬆** action shows only when the optional `registry` package
is installed on the deployment, only when you're signed in — and,
on a multi-tenant cloud, only when you hold the
**`publish-packages`** org capability. The org owner always holds
it; grant it to other members from Roles or Grants on the
Organization surface. On a self-hosted single-tenant install there
is no capability system, so publishing is simply open.)

## Who sees what you publish — visibility

On a multi-tenant cloud, a published version is **private to your
organization by default**: it lands in the registry stamped with
your org, and only your org's members see it when browsing. The ⬆
popover has a **"Public — visible outside your organization"**
checkbox for the explicit opt-in — tick it and the version becomes
visible platform-wide. In the packages chip's browse list, private
versions carry a **`private`** badge. (The programmatic
equivalent: `"public": true` in the publish JSON body.)

On a single-tenant install there is no org boundary to be private
within — the checkbox isn't shown, and every published version is
visible to every user.

## The packages chip — browse & install

Installing is a **build** act — you're adding a building block to
your project — so it lives with the other project-context chips on
the **Build** surface, not on an admin page. In the context bar
(top of the Build surface, alongside the *workspace* and *branch*
chips) click **packages**. A popover opens:

```text
Packages
  Package        Version
  mycorp-hello   1.0.0    [1.0.0 ↑] ×      ← installed pins on THIS branch
  ▾ + Install a package                    ← native <details>, click to open
      mycorp-hello   1.0.0   [Install] [Fork]     ← the registry index
      mycorp-hello   1.1.0   [Install] [Fork]
```

The top table is what's **installed on the current branch**
(remember branches from [Lesson 20](20-branches.md) — pins are
per-branch, so dev and prod can run different versions). The `<details>` below it
is the **registry** — every published version, with an action
per row. (The **packages** chip appears only when the optional
`registry` package is installed on the deployment.)

## Install — by reference, not by copy

Click **Install** next to `mycorp-hello 1.1.0`. Graphden:

1. **Materializes** the version's fns under a version-qualified
   namespace — `mycorp@1-1-0` (dots in the version become
   dashes). Idempotent: a second install is a no-op.
2. Writes a **pin** — a `:package-install` row saying "this
   branch uses `mycorp-hello` at `1.1.0`". The pin, plus the visible
   materialized fns, IS the install.

Nothing is copied into your own namespaces — you **reference**
`mycorp@1-1-0`. The panel refreshes to show the new pin in
the installed table.

## Update / rollback — repoint the pin, rewrite your refs

Each installed row has a version input prefilled with the current
version and an `↑` button. Type a different version and click
`↑`:

- **`1.1.0` → forward** to a newer version.
- **`1.0.0` → rollback** — the same button, an older version. The
  operation is symmetric.
- **`latest`** or a constraint like **`>=1.1`** — the highest
  published match is resolved.

Update doesn't just move the pin: it **rewrites your project's own
references** from the old version-qualified namespace to the new
one (so fns you built on top of `mycorp@1-0-0` now point at
`@1-1-0`). Package-internal refs are left alone. Same version =
no-op.

## Fork — copy-on-write when you want to edit

**Install** references fns you can't change (they're the
package's). When you want to *modify* a package, click **Fork**
instead. Fork **copies** the version's fns into the graph at
their **original** namespace (`mycorp`, not the versioned one), so
they become ordinary editable fn-defs — and writes **no
pin** (it's a copy, not a reference). A short notice confirms it;
reload to see the copied fns in the explorer tree.

**When Fork refuses.** The copy lands at the original namespace on
the same deterministic ids the package loader would use. If that
namespace is a package synced **from disk on this instance**
(Lesson 28), those ids are the platform's own: the "copy" would land
on rows the editor keeps read-only, and the next boot's sync would
overwrite it. So the fork is refused —
`{"ok":false,"reason":"package-owned","owned":["greet", …]}` naming
the fns, and the panel shows the same as a notice. A built-in
package is changed in its `fns.edn`; Fork is for a package that
*arrived through the registry* — the case
[Lesson 38](38-package-lifecycle.md) walks end to end.

| | Install | Fork |
|---|---|---|
| Rows | Referenced (shared) | Copied (yours) |
| Namespace | `ns@version` (qualified) | `ns` (original) |
| Editable? | No | Yes |
| Writes a pin? | Yes | No |
| Refused when | a dependency is missing | a dependency is missing, or the namespace is a built-in package here |

## Uninstall

The `×` on an installed row drops the pin for this branch. The
materialized `ns@version` fns stay (another branch may still
reference them) — uninstall only removes *this branch's* claim on
the package. Remove the last pin and the table collapses to the
empty-state notice.

## Withdraw — retract a published version

Uninstall is the consumer's act; **withdraw** is the publisher's.
A published version is immutable, but not eternal — publishing the
wrong namespace or the wrong number shouldn't be permanent. The
publisher can delete the registry row:

```bash
curl -X DELETE "http://localhost:9002/api/packages/withdraw?name=mycorp-hello&version=1.0.0" \
  -H "Authorization: Bearer $AUTH_TOKEN"
# → {"ok":true,"withdrawn":"mycorp-hello"}
```

Rules:

- **Refused with 409 `still-installed`** while *any* branch still
  pins the package **at that version** — a pin resolves through its
  version row, so withdrawing under it would break installs.
  Consumers unpin (uninstall or update away) first; a branch that
  already moved to `1.1.0` does not keep `1.0.0` alive.
- **404 `no-such-version`** when the `(name, version)` pair is
  unknown.
- Withdrawing is gated by the same **`publish-packages`**
  capability as publishing, and only on your own org's rows — a
  non-publisher can't erase what your org shipped.
- The materialized `ns@version` fns are **not** removed — by then
  they're ordinary graph content, deleted like any other namespace
  if you want them gone too.

There's no panel button for this — withdraw is an API-only,
deliberate act.

## Governance — the Organization surface's packages view

Publish and install are everyday **Build**-surface acts; oversight
lives on the **Organization** surface. Its **packages** section is
a read-only governance view with three parts:

- a **who-may-publish** note — states the `publish-packages`
  capability rule and the default visibility on this deployment;
- the **catalog** — every version *your org* published
  (Package / Version / Visibility / Published);
- the **install audit** — every pin: which package, at which
  version, on which branch, installed when.

It is deliberately *not* an install surface — there's no Install
button here. Reviewing what your org ships and consumes happens
on Organization; acting on it happens on the Build packages chip.

## Try it

1. At the bottom of the Explorer click **New namespace** and type
   `mycorp`. Hover its row, click **+** → **New graph…**, name it
   `greet` (parent `const`, `:value` bound to `"Hello"`). Hover
   `mycorp` again, click **⬆**: package name `mycorp-hello`, version
   `1.0.0`, **Publish** (or use the `curl` above). (If you built
   Lesson 28's on-disk package, `mycorp` already exists — publish its
   `mycorp.hello` namespace instead and read `mycorp.hello@…` for
   `mycorp@…` below.)
2. Change `:greet`'s value, then publish again as `1.1.0` (the **⬆**
   popover again). On a disk package that is a `fns.edn` edit and a
   `bb rebuild` first.
3. Open the **packages** chip in the Build context bar, expand
   **+ Install a package**, click **Install** on `mycorp-hello 1.1.0`.
   Watch the pin appear — and `mycorp@1-1-0` in the Explorer.
4. Type `1.0.0` in the installed row's version box, click `↑` —
   you've rolled back. Type `1.1.0`, `↑` — forward again.
5. **Fork** is the other door, and not one to open here: the copy
   lands at the original namespace on the same ids — the very
   `mycorp` rows you just published from — and on a namespace synced
   from disk it is refused outright (`package-owned`, § Fork above).
   Fork is for a package whose source is *not* on your branch;
   [Lesson 38](38-package-lifecycle.md) plays that with two branches.
6. Click `×` — the pin's gone.
7. Open the **Organization** surface's **packages** section — the
   governance catalog lists both published `mycorp-hello` versions
   with their visibility; the install audit shows a row per pin
   (re-install first if you removed the pin in step 6).
8. Withdraw `mycorp-hello 1.0.0` with the `curl` above — it's
   unpinned, so the row disappears from the registry browse. Now try
   withdrawing `1.1.0` while its pin from step 7 exists: a 409
   `still-installed` refusal. Uninstall first, and the withdraw
   goes through.

## The Marketplace — browsing with more than a name

The chip's browse list is the quick door: every version, an Install
button. Searching, filtering, ratings and install counts are the
**Marketplace** surface (**Browse marketplace →** at the bottom of the
chip) — [lesson 37](37-marketplace-themes-keymaps.md).

## Installing from ANOTHER graphden's registry

The browse `<details>` has one more affordance under the local table:
a small form — **registry URL / package name / version** — that pulls
a published package from a *different* graphden (say, `graphden.dev`
into your self-hosted install). Behind the scenes the version is
**mirrored** into your local registry first (an immutable local copy,
never re-published as public), then installed exactly as above —
reference, pin, secrets manifest and all. If the remote registry
requires auth, the server presents its `GRAPHDEN_REGISTRY_TOKEN`; the
browser never handles that credential. The copy also snapshots the
origin's marketplace numbers (rating, installs) and shows them read-only
— reviews stay where the package was published
([lesson 37](37-marketplace-themes-keymaps.md)).

## Beyond the registry — an external package from its own git repo

Everything above lives **inside one graphden install**. A package that
ships its own base-fn *impls* is code and enters through the build
instead — pulled by git coord via `executor-packages.edn`:
[Lesson 28 § An external package from its own git repo](28-packages.md#an-external-package-from-its-own-git-repo).

## What we glossed over

- **The programmatic API** — every panel action has a JSON
  sibling: `POST /api/packages/{install,update,fork}` and
  `GET /api/packages[/:name/:version]`. The panel's own endpoints
  (`/api/packages/panel-*`) return refreshed HTML instead; the
  JSON ones return `{ok, …}` for scripts and CI. See
  [docs/PACKAGE_DISTRIBUTION.md](../PACKAGE_DISTRIBUTION.md).
- **Dependencies** — a published bundle records the external
  fn-names it depends on; install/fork reject if a dependency is
  absent from the target graph.

## Next

[Lesson 30 — Working across organizations](30-working-across-orgs.md).
For the whole loop between a package's author and its consumers —
fixing someone else's package, sending the fix back, accepting it and
shipping the next version — jump to
[Lesson 38](38-package-lifecycle.md).

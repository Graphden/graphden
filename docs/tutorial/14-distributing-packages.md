# Lesson 14 — Distributing packages: publish, install, update, fork

**Goal**: by the end of this lesson you can publish a namespace
as an immutable package version, then browse the registry and
install / update / roll back / uninstall / fork packages from the
editor's **Packages** panel — all without leaving the graph.

**Concepts introduced**: `registry`, `:package-version`,
`publish`, `pin` (`:package-install`), `reference-install` vs
`fork` (copy-on-write), `version constraint` / `latest` /
`rollback`, the **Packages panel**.

## Authoring vs distributing

Lesson 11 was about **authoring** a package — the `fns.edn` /
`impls.clj` / `package.edn` files on disk that load at startup.
This lesson is about **distributing** one: taking a namespace
that already lives in the graph and turning it into a versioned,
installable artifact other branches (and, later, other people)
can pull in.

Two different things share the word "package":

| On disk (Lesson 11) | In the registry (this lesson) |
|---|---|
| A directory loaded at boot | A `:package-version` row: an immutable snapshot of a namespace's fn-defs |
| One copy, shared by the whole install | Named + semver-versioned; many versions coexist |
| Changes when you edit the files + rebuild | Frozen once published — re-publishing the same `(name, version)` is rejected |

## Publish — freeze a namespace into the registry

Publishing exports the fn-def subtree rooted at a namespace and
stores it as a `:package-version`. Using the `mycorp.hello`
package from Lesson 11:

```bash
curl -X POST http://localhost:9002/api/packages/publish \
  -H "Authorization: Bearer $AUTH_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"hello","version":"1.0.0","ns-root":"mycorp.hello"}'
# → {"ok":true,"name":"hello","version":"1.0.0","fn-count":1,...}
```

`ns-root` is the namespace to snapshot; `name` + `version` are
how the registry indexes it. Bump a fn in `mycorp.hello`,
`bb rebuild`, and publish again as `1.1.0` — now the registry
holds **both** versions. `GET /api/packages` lists the index.

You can also publish **from the editor**: the Packages panel has a
"Publish a namespace" form (name / version / ns-root) — the same
export-and-freeze step, no `curl` needed. The `curl` above is the
programmatic equivalent for scripts / CI. Either way, publishing is
a deliberate "author decides to release" action; everything after
happens in the panel.

## The Packages panel

Open the editor, sign in (the panel is auth-gated), and expand
the sidebar. Below **Secrets** there's a **Packages** section:

```
Packages
  Package        Version
  hello          1.0.0    [1.0.0 ↑] ×      ← installed pins on THIS branch
  ▾ + Install a package                    ← native <details>, click to open
      hello   1.0.0   [Install] [Fork]     ← the registry index
      hello   1.1.0   [Install] [Fork]
```

The top table is what's **installed on the current branch**
(remember branches from Lesson 8 — pins are per-branch, so dev
and prod can run different versions). The `<details>` below it
is the **registry** — every published version, with an action
per row.

## Install — by reference, not by copy

Click **Install** next to `hello 1.1.0`. Graphden:

1. **Materializes** the version's fns under a version-qualified
   namespace — `mycorp.hello@1-1-0` (dots in the version become
   dashes). Idempotent: a second install is a no-op.
2. Writes a **pin** — a `:package-install` row saying "this
   branch uses `hello` at `1.1.0`". The pin, plus the visible
   materialized fns, IS the install.

Nothing is copied into your own namespaces — you **reference**
`mycorp.hello@1-1-0`. The panel refreshes to show the new pin in
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
one (so fns you built on top of `mycorp.hello@1-0-0` now point at
`@1-1-0`). Package-internal refs are left alone. Same version =
no-op.

## Fork — copy-on-write when you want to edit

**Install** references fns you can't change (they're the
package's). When you want to *modify* a package, click **Fork**
instead. Fork **copies** the version's fns into the graph at
their **original** namespace (`mycorp.hello`, not the versioned
one), so they become ordinary editable fn-defs — and writes **no
pin** (it's a copy, not a reference). A short notice confirms it;
reload to see the copied fns in the sidebar tree.

| | Install | Fork |
|---|---|---|
| Rows | Referenced (shared) | Copied (yours) |
| Namespace | `ns@version` (qualified) | `ns` (original) |
| Editable? | No | Yes |
| Writes a pin? | Yes | No |

## Uninstall

The `×` on an installed row drops the pin for this branch. The
materialized `ns@version` fns stay (another branch may still
reference them) — uninstall only removes *this branch's* claim on
the package. Remove the last pin and the table collapses to the
empty-state notice.

## Try it

1. Publish `mycorp.hello` as `hello` `1.0.0` (the `curl` above).
2. Edit `:greet` in `mycorp.hello/fns.edn` (change the greeting),
   `bb rebuild`, publish again as `1.1.0`.
3. In the panel, open **+ Install a package**, click **Install**
   on `hello 1.1.0`. Watch the pin appear.
4. Type `1.0.0` in the installed row's version box, click `↑` —
   you've rolled back. Type `1.1.0`, `↑` — forward again.
5. Click **Fork** on `hello 1.0.0`, reload — `mycorp.hello`'s fns
   are now editable copies.
6. Click `×` — the pin's gone.

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
- **Cross-install distribution** — pulling a package published on
  one graphden install into a *different* one (download as EDN,
  the cloud reference-install with capability grants, Type-2
  impl-packages) is the rest of
  [docs/PACKAGE_DISTRIBUTION.md](../PACKAGE_DISTRIBUTION.md); this
  lesson stays within one install.

## Next

You've reached the end of the current tutorial sequence. From
here, follow the cross-references inside each lesson to the
reference docs in [docs/](..), and read the shipping packages
(`core`, `web`, `app`) as worked examples.

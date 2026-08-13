# Lesson 22 — Workspaces: scope the editor to your projects

**Goal**: by the end of this lesson you can scope the Explorer to
just the projects you're working in, hide namespaces you never
touch, and get the full list back — all without changing anything
for anyone else.

**Concepts introduced**: **workspace** (a personal view scope),
**projects** (root namespaces as ready-made scopes), the
**personal hide** (`⊘` / restore `↺`), and **All functions**.

## The problem

The Explorer shows the *whole* graph — every namespace, every fn.
On a fresh install that's fine; on a real shared graph with many
projects it's overwhelming, and most of it isn't yours. A
**workspace** narrows what *you* see to the namespaces you
actually work in.

One thing to hold on to: a workspace is a **view**, not an entity.
There is no workspace row in the graph, nothing to create or
administer, and nothing you do here is visible to anyone else —
it's a personal preference, stored in your browser (like your
branch choice).

## The workspace chip

In the **Build** surface's context bar — alongside the *branch*
and *packages* chips (Lessons 8 and 14) — sits the **workspace
chip**, showing your current scope. Click it:

```
Workspace — choose what you see
  ◍ All functions                       ← the unscoped default
  ──────────────────────────────
  Projects — tick the namespaces you work in
  ☑ mycorp        Our internal tools    ← root namespaces,
  ☐ demo          Example compositions     with their descriptions
  ──────────────────────────────
  Hidden by you — restore to your view
  ⦸ demo.scratch                 ↺
```

The **Projects** checklist is the graph's **root namespaces** —
each with its description, so a well-described namespace reads
like a project card. You don't *build* a workspace from scratch;
you **adopt** one or more existing projects by ticking them. The
popover stays open while you compose a multi-project scope — each
tick re-scopes the Explorer immediately.

## What "scoped" means

With at least one project ticked:

- the Explorer tree shows only the ticked namespaces **and their
  descendants** — everything else disappears from it;
- the namespace-less **"(primitives)"** bucket (base fns that live
  in no namespace) is hidden too — under a workspace you see
  *your* code, not the standard library;
- **search escapes the scope**: typing in the sidebar search
  always looks across the whole graph, so a scoped-out (or hidden)
  fn is still findable by name;
- nothing else changes: fns outside the scope still exist, still
  run, and references to them still resolve. Scope is about what's
  *listed*, never about what's *reachable*.

**All functions** clears the scope and returns the full view.

## Personal hide — the `⊘`

Sometimes the noise isn't a whole project but one namespace inside
your scope — a scratch area, an archive. Hover any namespace row
in the Explorer and click its **⊘** action (next to rename / add /
publish): the namespace vanishes from *your* view, at any depth.

Hidden namespaces are listed in the workspace popover under
**"Hidden by you"** — click a row's **↺** to restore it. Think of
it as a `.gitignore` for your Explorer: the shared graph is
untouched, teammates still see the namespace, and no permission is
needed to hide (it's your view, not their data).

## Personal + per-browser

Both the ticked projects and the hidden list live in your
browser's local storage. Consequences worth knowing:

- another browser or device starts back at **All functions**
  (there's no cross-device sync);
- signing out doesn't clear it — it's keyed to the browser, not
  the session;
- nothing about your workspace is stored in the graph, so branch
  merges, packages, and other members are entirely unaffected.

## Try it

1. Open the **workspace chip** in the Build context bar. Tick
   `mycorp` — the Explorer collapses to `mycorp.*` and the
   "(primitives)" bucket disappears.
2. Tick a second project — the popover stays open; both trees are
   now in scope.
3. Hover `mycorp.hello` in the Explorer, click **⊘** — it's gone
   from your tree. Reopen the chip: it's listed under **Hidden by
   you**; click **↺** to bring it back.
4. Reload the page — your scope survives.
5. Click **All functions** — the full graph is back.

## Next

Lesson 23 — [Finding your way: the lens and the
Inspector](23-explorer-and-inspector.md): the other half of not
drowning in a big graph — filter by *kind*, and read everything
about a selected fn in one panel.

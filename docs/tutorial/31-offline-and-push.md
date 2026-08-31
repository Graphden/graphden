# Lesson 31 — Working offline: a local instance, git snapshots, push/pull

**Goal**: by the end of this lesson you can run a Graphden instance on
your own machine, work in it with no network, keep git snapshots of your
graph, and — when you're back online — push your work to a hub (your
team's install or the cloud) as a review branch that goes through the
normal diff → merge flow.

**Concepts introduced**: the **local offline instance** (`GD_BIND`),
**graph snapshots in git** (`bb graph-export` / `bb graph-import`),
**push branches** (`push/<name>`), **pull** (`hub/main`), the editor's
**Hub section** (`GRAPHDEN_HUB_URL` + `GRAPHDEN_HUB_TOKEN` → one-click
push/pull), and what does NOT travel (`:service` rows, `branch-local?`
runtime config).

## When you want this

The default Graphden story is simpler than this lesson: open the site,
work. Reach for a local instance when the network is unreliable, or when
your prod is in the cloud but you'd rather burn your own laptop's CPU on
test runs while iterating.

## 1. Start a local instance

You need Docker and a checkout (see
[DEPLOYMENT.md](../DEPLOYMENT.md) for the one-time build):

```bash
GD_BIND=127.0.0.1 docker compose up -d
```

`GD_BIND=127.0.0.1` binds every published port to loopback: the editor
(`http://localhost:9002`) and any app you run are reachable from your
machine only. "Internal requests only" is the port binding — there is no
special mode to configure.

Work exactly as in every other lesson: create fns, branches, run tests.
It is a full instance.

## 2. Keep your graph in git

At any point, snapshot the graph into a directory of per-namespace EDN
files:

```bash
bb graph-export --url http://localhost:9002 --token $AUTH_TOKEN --out ../my-graph
cd ../my-graph && git init && git add -A && git commit -m "snapshot"
```

The layout is one `fns/<namespace>.edn` module file per namespace — the
same shape as the first-party packages — plus a `graphden.edn` manifest.
The files are byte-stable: exporting an unchanged graph twice produces
identical bytes, so **a git diff is a graph diff**. Deleted fns show up
as deletions (the exporter clears the previous snapshot).

To apply a snapshot to any instance (this one, a fresh one, the hub):

```bash
bb graph-import ../my-graph --url http://localhost:9002 --token $AUTH_TOKEN \
   --target restore/from-git --create --prune
```

Imports always land on a NAMED branch — never main — so the way from a
git snapshot into main is the merge flow you know from
[lesson 20](20-branches.md).

## 3. Push your work to the hub

When the network is back:

```bash
clojure -M -m graphden.cli push \
  --local-url http://localhost:9002 --local-token $AUTH_TOKEN \
  --hub-url https://hub.example.com --hub-token $HUB_TOKEN \
  --branch main --target push/my-feature
```

On the hub this creates (or re-snapshots) the branch `push/my-feature`:

- you are stamped as its **owner** and it gets the `owner`
  **write-policy** — only you can update it
  ([lesson 20](20-branches.md) § protected branches);
- a re-push is a fresh snapshot — fns you deleted locally are pruned;
- platform fns are never overwritten (they come back in the report as
  `skipped-owned`), and an fn you created locally is **adopted** onto
  its canonical identity rather than duplicated.

Review happens on the hub with the tools you already know: open the
branch popover, **Δ compare** `push/my-feature` against main, resolve
conflicts if the merge asks, **⇢ merge**.

## 4. Pull the hub's main back down

```bash
clojure -M -m graphden.cli pull \
  --local-url http://localhost:9002 --local-token $AUTH_TOKEN \
  --hub-url https://hub.example.com --hub-token $HUB_TOKEN
```

This lands the hub's main locally as the branch `hub/main`. Merge it
into your local main from the branch popover (or
`POST /api/branches/main/merge` with `{"source": "hub/main"}`). If both
sides changed the same fn since the last sync, you get the standard
conflict modal — pick per entity, retry.

## 5. Push and pull from the editor instead

The CLI above needs nothing but the two instances. But if you wire the
hub into the local instance's environment at start —

```bash
GRAPHDEN_HUB_URL=https://hub.example.com \
GRAPHDEN_HUB_TOKEN=$HUB_TOKEN \
GD_BIND=127.0.0.1 docker compose up -d
```

— the branch popover grows a **Hub** section showing the hub's address
with two buttons:

- **⇡ Push** snapshots the branch you are currently on to the hub as
  `push/<branch>` — the same owner-protected review branch §3 creates;
- **⇣ Pull** lands the hub's main locally as `hub/main` and refreshes
  the branch list, so the next click is the **Δ compare** / **⇢ merge**
  you already know.

The token stays on the server (the browser never sees it), and the hub
address is server configuration — the editor can't point your bearer at
some other host. Where do you get `$HUB_TOKEN`? It's a token the HUB
accepts: on a cloud org mint one in Settings → Account → API tokens; on
a team's self-hosted hub use its `AUTH_TOKEN`.

## What does NOT travel — and why that's right

- **`:service` rows** (your local cron jobs, web servers) never ride a
  bundle. Your local wiring stays local; the hub's stays on the hub.
- **`branch-local?` fns** (ports, schedules, vault paths) import fine
  but never PROPAGATE through a merge into main — the same guarantee
  in-instance branches give ([lesson 20](20-branches.md)).
- **Secrets**: vault paths are stripped from exports by default; the
  bundle carries a manifest of which args need re-binding on the other
  side ([lesson 13](13-effects-and-secrets.md)).

## Recap

- A local instance is the same stack with loopback ports:
  `GD_BIND=127.0.0.1 docker compose up -d`.
- `bb graph-export` / `bb graph-import` round-trip the graph through a
  byte-stable, per-namespace git layout.
- `cli push` publishes your work to the hub as an owner-protected
  `push/<name>` branch; review + merge are the normal branch flow.
- `cli pull` fetches the hub's main as `hub/main`; merging it locally is
  the normal merge flow.
- With `GRAPHDEN_HUB_URL` + `GRAPHDEN_HUB_TOKEN` set, the branch
  popover's **Hub** section does both with one click (⇡ Push / ⇣ Pull).
- Services and runtime config don't leak in either direction.

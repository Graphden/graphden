# Developer code-tour (`devtour`)

A guided, navigable read of the **graphden host codebase** for a developer
who just joined and needs to find their feet in the whole system.

This is the counterpart to [`docs/tutorial/`](../tutorial/README.md): the
tutorial teaches a *user* how to drive the editor; this tour walks a
*contributor* through the Clojure that makes it run — organized into the
system's relatively independent **blocks**, code-first, with real navigation
(a block map, next/prev along a spine, a see-also cross-link, and a Back stack
that returns you along the path you actually took).

## How to read it

Open the generated page in any browser — no running instance, no build:

```text
docs/devtour/index.html
```

Blocks are listed left, roughly in reading order; each block's `after:` line
names what it assumes you have already seen. Start with the **Executor** (the
spine everything else hangs off), then follow the dependency order: Storage,
Branches, Types, CRUD, Packages, Boot, Web, Layout, Editor frontend, Services,
Platform seams, Accounts.

**Boot & lifecycle** is the block to jump to early if you would rather start
from a running process than from a hot path — it walks `-main` → the Integrant
component graph → the router seams, which is the shortest route to seeing how
the other blocks are wired together. **Editor frontend** tours JavaScript
rather than Clojure, on the same anchor-and-bake contract.

## How it works

- **Source of truth:** [`tour.edn`](tour.edn) — a list of `:blocks`, each with
  ordered `:steps`.
- Every step anchors on a **symbol**, never a line number:

  ```clojure
  {:ns graphden.executor.interface :defn execute
   :say "prose (markdown: `code`, **bold**, [links](…))"
   :see [[:executor "create-context"]]}   ; optional cross-links
  ```

- `bb devtour` reads `tour.edn`, pulls the anchored form's **actual source**
  out of the file at generate time, and bakes everything into the single
  self-contained `index.html`.
- `bb devtour-check` (wired into `bb ci`, `:docs` group) fails if any anchor
  no longer resolves to exactly one form, or if `index.html` has drifted from a
  fresh regeneration. So the tour cannot silently point at code that was
  renamed, moved, or deleted — a stale tour turns CI red until someone re-runs
  `bb devtour` and commits.

Anchors resolve by Clojure namespace munging (`graphden.executor.interface` →
`src/graphden/executor/interface.clj`) and match any top-level `def`-form
(`defn`, `defn-`, `def`, `defbase`, `defprotocol`, `defrecord`, …) whose name
symbol equals `:defn`. Two variants:

- **`:file`** instead of `:ns` — an explicit repo-relative path, for package
  impls under `resources/packages/` (they have namespaces but do not live under
  `src/`): `{:file "resources/packages/web/http/impls.clj" :defn http-server …}`.
- **`:dispatch`** — anchor a `defmethod` by its dispatch value; the step is
  then labelled by the dispatch's name:
  `{:ns graphden.tenancy.addon :defn ig/init-key :dispatch :tenancy/request-scope …}`.
- **A `.js` `:file`** — the editor frontend is toured on the same contract.
  A JS anchor matches a top-level `function name(` / `async function name(` /
  `const|let|var name =` declaration at any indentation (several modules wrap
  their body in an IIFE), and the generator scans forward through
  strings / template literals / comments / regex literals to the matching close:
  `{:file "resources/packages/app/editor/editor-main.js" :defn initGraph …}`.
  `:dispatch` is Clojure-only and is rejected on a `.js` anchor. A form the
  scanner cannot balance is a hard error, never a truncated bake.

An anchor that matches no form, or more than one (an ambiguous name / dispatch),
is a hard error — add `:dispatch`, split, or rename. Steps are identified
internally by position, so a block may legitimately tour two forms of the same
name (e.g. the executor's two `execute`s, or storage's two
`resolve-execution-graph`s).

## Adding to the tour

Two kinds of change:

- **Add steps to an existing block** — append `:steps` entries and, if the
  block is still a stub, flip its `:status` to `:toured`.
- **Add a new block** — a new `:blocks` entry with an `:id`, `:title`,
  `:summary`, `:paths`, and an `:after` list of prerequisite block ids.

Then regenerate and verify:

```bash
bb devtour        # rewrite index.html
bb devtour-check  # what CI runs
```

Keep a step's `:say` to a few sentences: what this form does and **why it is
the right next stop** in the narrative — what a newcomer learns here. Point at
deeper reference material (e.g. [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md))
with a link rather than restating it. Do not tour a form that only exists to
satisfy the machinery unless it genuinely carries the story.

A block should only be flipped to `:toured` once its steps read as a coherent
walkthrough on their own — like the tutorial, an incomplete block stays a stub
rather than shipping half a narrative.

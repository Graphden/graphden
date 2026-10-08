# Developer code-tour (`devtour`)

A guided, navigable read of the **graphden host codebase** for a developer
who just joined and needs to find their feet in the whole system.

This is the counterpart to [`docs/tutorial/`](../tutorial/README.md): the
tutorial teaches a *user* how to drive the editor; this tour walks a
*contributor* through the graph definitions, Clojure, and browser code that make
it run — organized into the system's relatively independent **blocks**, code-first, with real navigation
(a block map, next/prev along a spine, a see-also cross-link, and a Back stack
that returns you along the path you actually took).

## How to read it

Three reading paths, one source of truth. All three are baked by `bb devtour`
and drift-checked in CI. The page contains source from the last bake; Emacs and
Org open live files. Regenerate after editing a toured form to keep them aligned.

### 1. The page

```text
docs/devtour/index.html
```

Open it in any browser — no running instance, no build, works from `file://`.

- **Every step has its own URL** (`…/index.html#executor/execute`): deep-link
  one, share it, and the browser's own Back button walks the path you took.
- **Reading progress** is remembered in that browser — ✓ ticks in the map, a
  counter in the header, and a "continue where you left off" link on the intro.
  *Reset* clears it.
- **<kbd>/</kbd> searches** step names, prose and code; <kbd>?</kbd> lists every
  key.
- **Jump out of the tour**: the code card's header is `path:line` (click to
  copy), with buttons that open the same form **in emacs** (see below) or on
  GitHub.
- Forms longer than 60 lines open folded — <kbd>e</kbd> or the button expands.
- Each step and block shows a reading-time estimate; the intro totals the tour.
  The estimate is deliberately crude: prose at ~140 wpm plus code at 15 (Clojure)
  or 20 (JS) lines per minute, plus a fixed cost per step.

### 2. Emacs

`docs/devtour/devtour.el` runs the same tour against **live buffers** — prose in
a side window, the real file at the anchored form beside it — so `xref`, grep,
magit and the REPL are all one keystroke from wherever the tour has you.

```elisp
(load "/path/to/graphden/docs/devtour/devtour.el")
(devtour-annotate-mode 1)   ; optional, see below
```

- `M-x devtour` — start, or resume where you stopped (progress is kept in
  `devtour-progress-file`).
- In the `*devtour*` window: `n` / `p` walk the spine, `b` goes back along the
  path you took, `s` follows a see-also / backlink / same-file link, `i` picks a
  block, `/` jumps to any step by name or prose, `o` moves point into the
  source, `q` quits.
- **evil users** get the same keys in **motion state** (the tour sets its own
  initial state), so `hjkl` still scroll and `gg` still goes to the top — which
  is why the block index is on `i` and not on `g`. Nothing to configure.
- `M-x devtour-here` — the other direction: open the tour **at the form point is
  in**. With `devtour-annotate-mode`, eldoc names that step as you move around
  ordinary source buffers, which is what makes the tour useful long after the
  first read.
- `M-x devtour-reload` — re-read the data after a fresh `bb devtour`, or after
  pointing `devtour-data-file` somewhere else.
- Reading the Russian tour instead: point `devtour-data-file` at its `tour.eld`
  and `M-x devtour-reload`.

To make the page's **emacs** button work, register `org-protocol` once (Linux):

```ini
# ~/.local/share/applications/org-protocol.desktop
[Desktop Entry]
Name=org-protocol
Exec=emacsclient -n %u
Type=Application
Terminal=false
MimeType=x-scheme-handler/org-protocol;
```

```bash
update-desktop-database ~/.local/share/applications/
```

…and `(require 'org-protocol)` in your init, with `devtour.el` loaded — it
registers the `devtour` sub-protocol that opens `file` at `line`.

### 3. Org

`docs/devtour/org/` is the same tour as plain org files (one per block, plus
`index.org` and `glossary.org`) — prose, and a `- source ::` link that opens the real file at the
form (`C-c C-o`). No elisp required; useful if you would rather read in org,
fold with the outline, or keep your own notes next to the steps.

### Where to start

You need basic Clojure and HTTP knowledge, but no prior Graphden experience.
Begin with the first Executor step: it explains a small graph definition and
why composition is stored as data. Read its path through `compile-fn`,
`arg-builder`, `defbase`, and `resolve-arg`; then visit Packages' `process-module`,
`parse-composed`, `add`, and `sync-fn-entities-from-packages!`. This connects an
authored definition to stored rows and an executable function before the deeper
cache, type, and branch mechanisms.

For the full tour, read blocks in dependency order. Boot explains process startup
and is a useful alternative starting point. Editor reads JavaScript plus its
server-side graph-plan and personal-theme boundaries. Services and Platform can
wait until you need long-running functions or deployment policy. The Repositories
block explains how the same core is assembled for self-hosting and cloud.

Technical terms link to definitions outside the numbered steps. Opening a
browser definition leaves progress unchanged; Back returns to the prior step.
In Emacs, prose links are buttons (`TAB`, `RET`, or mouse); a definition opens
in a help window whose return button closes it. Document links open files in
`devtour-repo-root`. Org definitions live in `org/glossary.org`, with ordinary
file links to documents rebased from the Org directory.

The source's `:glossary` is an ordered vector of `{:id :title :say}` maps.
Use `[term](#term/stable-id)` in prose. Definitions may reference one another;
the generator rejects duplicate IDs, unknown definitions, and missing local
documents. Use `../../docs/...` for checkout-relative document links in the
source; the HTML and Org generators adjust their output paths, including
translated output outside this repository. Adding a definition never adds a
step, changes a source anchor, or changes saved progress.

### Follow one edit through the system

Use search (`/`) for these step names; use see-also to follow connections and
Back to return to the place you left. This route traces a user saving a literal
argument in the inspector:

1. **Editor: `enterArgValueEditMode` → `writeBindingFields`.** A type-specific
   widget saves an own binding for a function and slot. The writer chooses POST
   or PUT; `authMutate` encodes the form and uses `authFetch` for credentials.
   The fetch wrapper in `editor-branch-context.js` selects the current branch.
2. **Web: `write-rej`.** Open
   [web/crud-write/fns.edn](../../resources/packages/web/crud-write/fns.edn)
   beside the primitive. `_create-parsed` / `_update-parsed`, the validation
   definitions, and the apply definitions compose parsing, rejection, and writes.
   For PUT, `_update-apply-success` explicitly orders invalidation, notification,
   and response through `:do`; the primitive does not conceal this pipeline.
3. **Branches: `VersionedStorage`.** The storage protocol writes a version in
   the selected branch. **Graph API: `type-check-fn-after-mutation!`** shows why
   a saved edit may have type warnings; secret violations instead require rejection.
4. **Graph API: `notify-after-write!`.** Invalidation updates local caches and
   PostgreSQL notifications reach other processes. **Executor: `registry`**
   and **Branches: `validate-graph-epoch!`** explain refresh and missed-event recovery.
5. **Editor: `buildLookups` → `fetchBackendLayout` → `renderGraph`.** Reloaded
   rows feed client indexes, server layout, and measured browser cards. A later
   Run travels through **Graph API: `apply-execute`** to **Executor: `execute`**.

The generic `crud/create-entity` entry is another API caller; the public editor
route's parse → validate → apply composition lives in the package graph above.

### Make and test a first change

Choose the owning layer before editing. Constants, defaults, references, and
multi-step composition belong in `resources/packages/<package>/<module>/fns.edn`.
Use `impls.clj` for a small primitive adapting a Clojure/Java/library operation,
and `src/graphden/` for shared executor, storage, or checker mechanisms. A base
implementation should not call another registered base implementation: put that
dependency in a graph definition. See [PHILOSOPHY](../PHILOSOPHY.md) and the
[package decision matrix](../PACKAGES.md#5-base-function-vs-fn-def-decision-matrix).

A small first exercise is to extend the existing graph test
`core.tests/add-sums-every-number` in
[core/tests/fns.edn](../../resources/packages/core/tests/fns.edn). Change its
subject's `:nums` from `[1 2 3 4]` to `[1 2 3 4 5]` and its `:expected` from
`10` to `15`, updating the description. Its inline subject inherits `:add`;
the named test inherits `:assert-eq` and passes only when execution does not
throw. This changes a real fn-def and checks the graph route without changing
the arithmetic primitive.

Read [CLAUDE.md](../../CLAUDE.md) and the
[worktree contract](../../dev/wtq/AGENT.md), then work in your claimed checkout:

```bash
bb wt claim first-graph-change "Extend the arithmetic graph test"
# cd to the WORKTREE path printed above; make the edit there
bb type-sweep
bb graph-lint
bb wt test --focus graphden.packages.platform-tests-test/every-platform-test-passes
```

That focused test loads the shipped graph and runs package-owned tests, including
your edited definition. It needs Docker for its isolated database fixture. The
editor's ordinary Run all excludes package-owned tests, so it is not a substitute.
For a primitive change, use its direct host test as well; arithmetic lives in
[arithmetic_test.clj](../../test/graphden/packages/core/arithmetic_test.clj), run
with `bb wt test --focus graphden.packages.core.arithmetic-test`. For a shared
mechanism, find its focused namespace under `test/graphden/` and test the relevant
behavior at that boundary.

Use `bb wt up` to exercise application behavior on your own instance when needed.
After changing a toured form, run `bb devtour`; then `bb lint` checks the edited
checkout and baked tour. Follow the worktree contract for CI and serialized
landing. The fn-def graph tests and Clojure host tests cover different boundaries;
pick the one that actually executes the layer you changed.

## How it works

- **Source of truth:** [`tour.edn`](tour.edn) — a list of `:blocks`, each with
  ordered `:steps`.
- Every step anchors on a **symbol**, never a line number:

  ```clojure
  {:ns graphden.executor.interface :defn execute
   :say "prose (markdown: `code`, **bold**, [links](…))"
   :see [[:executor "create-context"]]}   ; optional cross-links
  ```

- `bb devtour` reads `tour.edn`, resolves every anchor, and writes **three**
  outputs (`scripts/devtour/tour.css` + `tour.js` are the page's sources):

  | Output | For |
  |--------|-----|
  | `index.html` | the standalone page — the anchored form's **actual source** baked in |
  | `tour.eld` | `devtour.el` — the anchor (`:file` + `:head`), never the code, so emacs shows live source |
  | `org/*.org` | the org reading path — prose plus a `file:…::<head>` link per step |

- `bb devtour-check` (wired into `bb ci`, `:docs` group) fails if any anchor no
  longer resolves to exactly one form, or if any baked output has drifted from a
  fresh regeneration (an orphaned `org/*.org` counts). So the tour cannot
  silently point at code that was renamed, moved, or deleted — a stale tour
  turns CI red until someone re-runs `bb devtour` and commits.
- `bb devtour-emacs` runs `tools/devtour-el-test.el` (batch ert): every step's
  head line is findable in the LIVE file, navigation / progress / see-also work,
  the eldoc annotation names the step at point, the org links resolve from the
  org directory and the org-protocol handler opens a file at a line. It SKIPS
  when emacs is not installed.
- `bb devtour-page` drives `index.html` in a real browser
  (`tools/browser-test/devtour-page.test.js`): deep links, Back/Prev/Next,
  progress, search, folding, theme and the jump-out links. Needs no graphden
  stack, so unlike the e2e suite it runs inside `bb ci`; it SKIPS without
  `tools/browser-test/node_modules`.

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
bb devtour        # rewrite index.html + tour.eld + org/
bb devtour-check  # what CI runs (plus bb devtour-emacs / bb devtour-page)
```

Keep a step's `:say` to a few sentences: what this form does and **why it is
the right next stop** in the narrative — what a newcomer learns here. Point at
deeper reference material (e.g. [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md))
with a link rather than restating it. Do not tour a form that only exists to
satisfy the machinery unless it genuinely carries the story.

A block should only be flipped to `:toured` once its steps read as a coherent
walkthrough on their own — like the tutorial, an incomplete block stays a stub
rather than shipping half a narrative.

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

Three reading paths, one source of truth. All three are baked by `bb devtour`
and drift-checked in CI, so they can never disagree about what the code says.

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
  path you took, `s` follows a see-also (or backlink), `g` picks a block, `/`
  jumps to any step by name or prose, `o` moves point into the source, `q` quits.
- `M-x devtour-here` — the other direction: open the tour **at the form point is
  in**. With `devtour-annotate-mode`, eldoc names that step as you move around
  ordinary source buffers, which is what makes the tour useful long after the
  first read.
- Reading the Russian tour instead: point `devtour-data-file` at its `tour.eld`.

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
`index.org`) — prose, and a `- source ::` link that opens the real file at the
form (`C-c C-o`). No elisp required; useful if you would rather read in org,
fold with the outline, or keep your own notes next to the steps.

### Where to start

Blocks are listed roughly in reading order; each block's `after:` line names
what it assumes you have already seen. Start with the **Executor** (the spine
everything else hangs off), then follow the dependency order: Storage, Branches,
Types, CRUD, Packages, Boot, Web, Layout, Editor frontend, Services, Platform
seams, Accounts, and last the Constellation — the six repositories around this
one and the seams they plug into.

**Boot & lifecycle** is the block to jump to early if you would rather start
from a running process than from a hot path — it walks `-main` → the Integrant
component graph → the router seams, which is the shortest route to seeing how
the other blocks are wired together. **Editor frontend** tours JavaScript rather
than Clojure, on the same anchor-and-bake contract.

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

---
name: graphden-ui
description: Frontend changes to the Graphden editor (resources/packages/app/editor/*.js, *.css, *.edn for hiccup). Triggers on UI / editor / sidebar / overlay / styling / tooltip / picker work, or any change to editor-*.js or editor-styles.css. Enforces "prove it works in a real browser" before claiming done — biome lint (JS), stylelint (CSS, design-token discipline), Playwright visual snapshots, Playwright + a11y MCPs for live verification. SKIP for: pure backend (Clojure) changes, package.edn dependency edits, server-side fn-defs that don't render anything.
---

# graphden-ui — UI work that actually gets verified

Frontend = ~7K lines of vanilla JS + one CSS file with `:root` / `body.theme-dark` design tokens. No build step, no module system, no React. Files load in the order declared in `app/editor/fns.edn :_editor-script-paths`.

The recurring failure mode this skill prevents: **claiming "the UI is done" without ever opening a browser or reading a console log.** `bb rebuild` ships the jar; that's not the same as the feature working.

---

## 0. Tools you should be using

This project ships four MCPs wired in `.mcp.json`:

- **`playwright`** (`@playwright/mcp`) — navigate, click, screenshot, read DOM, read console logs. **Use this** instead of writing yet another one-off script under `tools/browser-test/`.
- **`a11y`** (`a11y-mcp`) — WCAG 2.x + colour contrast. Use after every visible style change.
- **`postgres`** — for verifying CRUD writes round-trip when the UI mutates DB state.
- **`clojure`** — REPL/eval against the running JVM (used by `graphden-repl` skill).

Plus three CLI checks wired into `bb`:

- **`bb biome`** — JS lint (~80 pre-existing warnings allowed; new errors block).
- **`bb stylelint`** — CSS lint with design-token enforcement (`scale-unlimited/declaration-strict-value` makes raw `#hex` / `rgb()` outside the `:root` and `body.theme-dark` blocks an error). Auto-fix: `bb stylelint-fix`.
- **`bb visual`** — Playwright visual-regression diff against committed PNG baselines under `tools/visual-tests/tests/editor.spec.js-snapshots/`. After **intentional** UI changes run `bb visual-update` and commit the new PNGs.

A **Stop hook** (`.claude/hooks/check-ui-on-stop.sh`, wired in `.claude/settings.json`) auto-runs biome + stylelint on UI files modified in the working tree at the end of every turn — if either fails with errors (warnings ignored), Claude is forced to fix before stopping. So the skill's "before claiming done" checklist below is enforced, not just guidance.

If a tool name like `mcp__playwright__navigate` doesn't show up in your toolbox, the MCP isn't connected — ask the user to restart Claude Code (the MCPs only register at startup) before continuing.

Fallback (if MCPs unavailable): `tools/browser-test/check-editor.js <fn-name>` writes a screenshot to `/tmp/editor-screenshot.png` and prints console logs. Read both before claiming the change works.

---

## 1. Design tokens — the only colours you may use

`editor-styles.css` defines the palette under `:root` and overrides under `body.theme-dark`. The set is:

| Category | Tokens |
|----------|--------|
| Surfaces | `--bg --sidebar-bg --card-bg --card-row-highlight --header-bg` |
| Text | `--fg --muted-fg --light-fg --header-fg --card-fg --card-header-fg` |
| Borders | `--border --light-border --input-border --card-border` |
| Interaction | `--hover-bg --selected-bg --accent` |
| Status | `--success-fg --error-fg` |
| Effect chips | `--effect-chip-fg --effect-chip-{db,env,io,network,time,misc,random}` |
| Special | `--hof-fg --hof-bg --hof-border --tooltip-bg --tooltip-fg` |
| Depth | `--shadow-sm --shadow-md --shadow-lg --shadow-xl --overlay-hover --overlay-pressed` |

**Hard rule:** No raw `#hex`, `rgb()`, `rgba()` outside the `:root` and `body.theme-dark` blocks. Same rule applies to inline `style=` / `el.style.X = ...` in JS — use `var(--token)`.

If a new shade is genuinely needed, **add a token to both light and dark `:root` blocks first**, then reference it. Don't sprinkle one-off literals. Effect-chip categorical colours intentionally stay identical across themes — they encode kind, not surface.

**Verification — preferred:**

```bash
bb stylelint
```

The `scale-unlimited/declaration-strict-value` rule fails the build on raw colours for `color`, `background[-color]`, `fill`, `stroke` declarations outside the theme blocks. Box-shadow values aren't covered automatically — when adding a colour-bearing `box-shadow`, route it through a token (`var(--error-fg)`, `var(--shadow-sm)`, etc.) yourself.

Manual fallback:

```bash
awk '/^body.theme-dark *\{/,/^}/{next} /^:root *\{/,/^}/{next} /#[0-9a-fA-F]{3,8}\b|rgba?\(/{print NR": "$0}' \
  resources/packages/app/editor/editor-styles.css
```

Empty output = clean.

---

## 2. The "before claiming done" checklist

After ANY editor change, run all six (the Stop hook auto-runs 1 + 2; do the rest yourself):

1. **`bb biome`** — JS lint. Must exit 0 at error level (warnings allowed, errors block).
2. **`bb stylelint`** — CSS lint. Must exit 0. Catches off-palette colours and mis-formed CSS.
3. **`bb rebuild`** — ships the change to the running container. Wait for "✓ smoke OK".
4. **Live browser check** — via Playwright MCP:
   - Navigate to `http://localhost:9002/#<some-fn>` (the Cytoscape canvas only mounts after a fn is selected — the empty editor is not a useful screenshot baseline).
   - Read `console` — zero errors expected.
   - Take a screenshot, eyeball it (overlapping elements, cut-off text, contrast issues).
   - For interactive features: click / type / observe state change.
5. **a11y MCP scan** — pass the loaded page through `a11y` and read findings. Contrast violations and missing keyboard nav are blockers.
6. **`bb visual`** — Playwright visual-regression diff. Either it passes (no unintended layout change) OR it fails and the diff is what you wanted; in the latter case run `bb visual-update` and **commit the new PNGs** alongside your code change.

If any of these fail, **fix before reporting**. Don't ship "the build is green" while the page is broken in the browser.

---

## 3. Common pitfalls

- **Cross-file `let` vs `const`.** Files share globals via window-scope `let cy = …`. Biome's `useConst` will flag these as warnings — they're false positives because biome doesn't see the cross-file reassignment. Leave them as `let`.
- **Cytoscape pan/zoom returns LIVE refs.** Snapshot via primitives (`const x = cy.pan().x`); reading `.x` later sees mutations from later writes. Saved as a memory; don't rediscover.
- **Theme-dark must mirror every new token.** When you add a `--my-token` to `:root`, also add it to `body.theme-dark` — otherwise dark theme inherits the light value and looks broken under a dark page bg.
- **No new build steps.** No bundler, no transpiler, no TypeScript. Plain ES2017+ that the browser executes directly. If you want types, JSDoc — biome reads them.
- **Script load order matters.** New file must be added to `app/editor/fns.edn :_editor-script-paths`, in dependency order. The list lives at `docs/CLAUDE.md` "Frontend Module Structure".
- **`__BUILD_HASH__` placeholder** — lives in `editor-state.js` only, substituted at build time. Don't delete it; otherwise `window.BUILD_HASH` becomes the literal string.

---

## 4. When to add a new editor-*.js file vs. extend an existing one

Add a new file when the new responsibility is genuinely orthogonal (a new overlay type, a new picker, a new edit mode). Extend an existing file when it's a feature of an existing concern. The file map in `CLAUDE.md` "Frontend Module Structure" is the source of truth — keep it updated.

Hard cap: a file > 800 lines is a code smell. `editor-overlays.js` is the worst offender (1150 lines); split before piling on more.

---

## 5. CSS rules of thumb

- Every block of related styles gets a comment naming the visual element it controls.
- Hover / focus / disabled states declared next to the base — not at the bottom of the file.
- `transition` only on `transform` / `opacity` / colour properties — never `width` / `height` / `top` (jank).
- For floating elements (popovers, tooltips), use `position: absolute` parented to a stacking-context owner; `z-index` numbers come from a small set documented at the top of the file.

---

## 6. Graph-first frontend — prefer hiccup partials + htmx over JS

**Default**: render content through the **graph** (fn-defs returning
hiccup at `GET /partials/*`) and let **htmx** swap it in. Fall back
to client-side JS only when graph+htmx would HURT one of:

1. **Performance** — interactions that need to be sub-100ms locally
   (drag, hover, keystroke-by-keystroke validation). htmx round-trip
   costs ~30ms minimum even on localhost.
2. **Architectural cleanliness** — visualization that intrinsically
   binds to a stateful in-page object (Cytoscape canvas, dynamic
   layout pipelines, the singleton arg-overlay manager). These have
   no meaningful server-side representation.
3. **Security** — anywhere the server should NOT see the editing
   state (e.g. unsubmitted password / vault path while typing).
   Once submitted, server takes over.
4. **Speed of editing** — features behind keyboard shortcuts /
   immediate clicks where waiting for the server makes the UI feel
   "slow."

**Concrete priority order when adding or refactoring a UI feature:**

1. **fn-def returning hiccup at `GET /partials/X`** + htmx swap.
   Reference: `:branch-popover`, `:service-popover`,
   `:execute-result-pane`, `:mismatch-explainer-popover`,
   `:fn-picker-incompat-explainer`, the entire `:partials/*` family
   in `resources/packages/app/editor/fns.edn` /
   `app/server/fns.edn`. See `docs/PARTIALS.md` for the recipe.
2. **JS that mounts a server-fetched partial via `htmx.process` /
   `authFetch(...).then(html => el.innerHTML = html)`** — JS owns
   anchored positioning / dismissal lifecycle only, server owns the
   markup.
3. **JS that builds DOM by hand (`document.createElement`,
   `el.appendChild`)** — last resort. Use only when the responsibility
   is genuinely client-only (drag, in-place edit cursor, canvas
   manipulation).

**Anti-patterns this rule forbids:**

- JS string-concatenating HTML (`el.innerHTML = '<div>' + … + '</div>'`)
  when the same shape could live in a fn-def returning hiccup.
- JS building popover body content inline when a `GET /partials/X`
  would compose better.
- JS data-shaping that recomputes server-known state
  (formatting, role classification, free-arg derivation).

**When refactoring is required AND it's a big job, DO IT, don't
defer.** "This is a multi-PR effort" / "leave it to a follow-up
session" / "let me just add a TODO" — these are the dodge. Per
[[feedback_no_excuse_for_pre_existing]] / `feedback_no_halfmeasures`,
the answer is plan it, present the plan, then execute. Past
examples in this codebase: scope=subtree backend + editor migration
(commits `bec65163` + `55bee689`, 2026-06-22 — multi-day in
isolation but landed in one session after the third attempt found
the right architecture); execute-result popover server-rendered
partial (commit `5ba77e3a`, 2026-06-20).

**Detection (auditable):**

```bash
# Long string-built HTML in JS (likely candidates):
grep -nE "innerHTML\s*=\s*['\"]<" resources/packages/app/editor/*.js | head

# DOM-builder hotspots — >10 createElement / appendChild calls in
# one file → suspicious unless it's a placement/lifecycle utility:
python3 << 'EOF'
import re, os
d = 'resources/packages/app/editor'
for f in sorted(os.listdir(d)):
    if not f.endswith('.js'): continue
    with open(os.path.join(d, f)) as fh: s = fh.read()
    n = len(re.findall(r'\b(?:createElement|appendChild|insertAdjacentHTML)\b', s))
    if n >= 20: print(f"  {n:4d} {f}")
EOF

# Existing GET /partials/* binders — pattern to copy:
grep -nE "/partials/" resources/packages/app/editor/*.js
```

Each candidate found here gets a judgment call against the four
exceptions above — most should migrate; the ones that genuinely
need client JS get a one-line `// graph-first-exception: <reason>`
comment so the next audit doesn't re-flag them.

## 7. What this skill DOESN'T do

- It doesn't replace `bb rebuild`. You still ship via the build.
- It doesn't write tests for you. UI tests live in `tools/browser-test/*.test.js`; if the change touches a tested feature, run the relevant test or update it.
- It doesn't know your design vision. It enforces the existing token set + a11y minimum; aesthetic judgment is yours.

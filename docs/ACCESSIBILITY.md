# Accessibility

How the editor stays usable without a mouse, without sight, and with the
text scaled up — and what a change has to do to keep it that way.

This is a contract, not an aspiration: every claim below is pinned by a
test named at the end of its section.

## The three primitives

Everything else is built on these. They live in
`resources/packages/web/runtime/graphden-popover.js` (shared with the
standalone runtime bundle, so they carry no editor dependencies) and
`resources/packages/app/editor/editor-a11y.js`.

| Primitive | What it is for |
|-----------|----------------|
| `focusIntoDialog` / `returnFocusTo` | Move the keyboard into a surface when it opens, and hand it back when it closes |
| `installTabTrap({getEl, isVisible})` | Keep Tab inside an open dialog. Driven by `isVisible()`, so it needs no open/close notification and is inert while closed |
| `setSiblingsInert(el, on)` | Make `aria-modal="true"` true — hide everything else from assistive tech and pointer input |
| `focusableWithin` / `focusSafely` | One definition of "can take focus", instead of the three partial selector lists this codebase used to carry |
| `window.gdAnnounce(msg, {assertive})` | Say something to a screen reader without moving focus |

## Rules for new code

**A new dialog or popover.** Use `installPopoverDismiss` with `trapFocus`
and `getReturnFocus`, or call the primitives directly. Focus entry and
return stay EXPLICIT: a dialog also closes through paths the dismiss
handler never sees (a × button, Cancel, submit), and `getAnchor` is not a
safe stand-in — `editor-secrets.js` deliberately passes a neighbouring
popover there, and returning focus into a different dialog is worse than
doing nothing.

**A new keyboard shortcut.** Register it in `editor-shortcuts.js`; do not
add a `keydown` listener. The registry is what `Space` and `?` render
from, so a binding declared anywhere else is invisible and undiscoverable.
Prefer the leader (`Space x`) — bare keys are nearly exhausted and the
letter keys belong to canvas navigation.

**Any state that changes without moving focus** — a selection, a branch
switch, a lens toggle — calls `gdAnnounce`. A screen reader has no other
way to notice.

**A new interactive element** must be reachable and visible when focused.
If it appears on `:hover`, add `:focus-within` alongside — reachable by
Tab but invisible is worse than absent.

**Sizes.** `font-size` and anything sized to text goes in `rem`. A px box
around a rem glyph overflows the moment the user scales their font. Where
a px floor is a real requirement (WCAG 2.5.5 hit areas), use
`max(28px, 1.75rem)` so the floor holds and the target still grows.

**Motion.** CSS handles the declarative half through the
`@media (prefers-reduced-motion: reduce)` block at the END of
`editor-styles.css` (it must stay last — it overrides at equal
specificity). JS-driven motion asks `prefersReducedMotion()`. Remove the
movement, keep the information: the tree-flash becomes a steady wash, the
busy spinner only slows down because it IS the progress signal.

## The widgets

| Surface | Pattern | Module |
|---------|---------|--------|
| Explorer tree | ARIA tree — arrows, Right/Left expand/collapse, Enter opens, roving tabindex | `editor-tree-keys.js` |
| Graph canvas | Roving tabindex; arrows/hjkl follow EDGES (→ argument, ← consumer). Two levels: `Enter` steps INTO a card's rows, `Escape` backs out; `Shift`+arrows move the node itself | `editor-canvas-keys.js` |
| Pickers (fn, namespace) | Combobox — focus stays in the filter field, `aria-activedescendant` names the highlighted row | `editor-fn-picker.js`, `editor-namespace-picker.js` |
| Inspector tabs | ARIA tabs — `aria-controls`, one tabpanel, ← → Home End | `editor-shell.js` |
| Dialogs | Focus enters, Tab is trapped, Escape returns it | `graphden-popover.js` + each dialog |
| Shortcuts | Registry + `Space` leader + `?` cheatsheet | `editor-shortcuts.js` |

Two deliberate exceptions, both load-bearing:

- **The tour is not trapped.** Its `aria-modal="false"` is correct — steps
  ask the reader to click targets OUTSIDE the popup, so trapping or
  stealing focus would break the thing it exists to do. It announces each
  step instead.
- **Row actions are a `toolbar`, not a `dialog`** (`editor-row-actions.js`),
  which is why they take focus without trapping it.

## Two things that are easy to get wrong

**Rebuilds destroy focus.** The Explorer tree does `innerHTML = ''` on
every `updateEntityList` — and selecting a fn calls it. The canvas rebuilds
its overlays on every render. So both remember the current item by a stable
KEY (fn id / namespace path / node id), never by element reference, and
restore it afterwards. The tree's restore is guarded: it only reclaims
focus if focus was inside the tree beforehand, because plenty of rebuilds
are not user-initiated and grabbing focus then drags the keyboard away from
wherever the user actually was.

**A key handler must not reach into a subtree it does not own.** Both the
card level and the row level claim Escape and Enter — but only when the
card, or the row, ITSELF has focus. Claiming from anywhere in the subtree
steals the key from the popovers and controls living inside, and the
symptom shows up somewhere unrelated (the first time, in a tutorial test
about version history). `edit-a11y-canvas.test.js` fires Escape at a
control inside a card and requires it to arrive unconsumed.

**The canvas has no scroll box.** `#graph-layer` is positioned by a single
CSS transform, so `scrollIntoView` does nothing there. Bringing a node on
screen means writing `viewport.pan` yourself (`ensureNodeVisible`).

## Checking your work

```bash
bb lint-web                       # biome + stylelint (design tokens)
node tools/browser-test/edit-a11y-dialogs.test.js   # focus enters / stays / returns
node tools/browser-test/edit-a11y-tree.test.js      # tree navigation, survives rebuild
node tools/browser-test/edit-a11y-canvas.test.js    # edge-walking, viewport follows
node tools/browser-test/edit-shortcuts.test.js      # leader, guards, cheatsheet
node tools/browser-test/edit-a11y-audit.test.js     # structural sweep + contrast
```

The audit sweep runs in the landing gate with the rest of the e2e suite.
It walks the live DOM in four states — shell with a graph, expanded
Explorer, an open dialog, the cheatsheet — and fails on unnamed controls
and dialogs, `aria-hidden` over something focusable, unlabelled fields,
duplicate ids, missing alt, a missing `main`, and any text sample under
WCAG AA contrast.

It does not use axe-core: axe is MPL-2.0 and `bb license-check` allows
only the MIT/BSD/Apache/ISC family, so adding it would turn CI red. The
rules implemented here are the subset of axe that actually fires on an app
of this shape. The `a11y` MCP runs the real axe out of process, outside
the dependency tree, and stays the tool for ad-hoc scans.

Automated checks do not replace a real screen reader. Before shipping
something that changes how a surface is announced, walk it once with NVDA
(Windows) or VoiceOver (macOS): find a function, read its arguments, run
it.

## What is deliberately not done

- **No skip-link maze.** One skip link, to the graph. The Explorer is the
  only thing worth skipping past.
- **No `aria-live` on the canvas itself.** Node moves are announced by the
  navigation module, which knows what the move MEANT; a live region on the
  layer would read out layout churn.
- **No high-contrast theme.** The token palette is checked against WCAG AA;
  `prefers-contrast` support is unimplemented, not refused.

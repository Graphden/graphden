# Editor row-actions partial — as-shipped reference

> **🔧 Internal engineering record — not user documentation.** Indexed from
> [CLAUDE.md](../CLAUDE.md), outside the reader path (see
> [docs/README.md](README.md)).

The `⋯` row-actions popover is server-rendered: a single partial
`:partial-row-actions` (package `app/editor-row-actions`) serves four
contexts via `?context=…`, and `editor-row-actions.js` owns only the
lifecycle (hover-show, click-pin, fade-out, zoom/pan re-anchor) and the
`data-action` dispatch. This doc is the canonical contract for anyone
extending the partial — and the decision guard for why the *other* popovers
were deliberately left in JS.

## Contexts and buttons

| Context | Used by | Buttons |
|---|---|---|
| `col-header` | ancestor column header; read-only fall-through rows | ns / i / ↗ |
| `cell` | MI cell; parent-edit row | ns / i / ↗ / × Remove-MI / + Add-MI (last two when `editable=true`) |
| `use-site-arg` | argument at a use-site | ns / i / ↗ / × Remove-binding / ✎ Change-value (last two when `editable=true`) |
| `root-row` | the selected fn's root row | ns / i / ↗ / ▶ Run / ⌛ History / ⚙ Service / ▣ Apps / ✎ Rename / + Extend / ✕ Delete |

Disabled-with-reason: `edit-block-reason` is a client-passed query param; the
⚙ reason is computed INSIDE the partial from `:service-blocking-free-args`
(the same predicate the create-service guard uses) — no param.

▣ Apps is tenancy-only, but this CORE partial can't know whether the addon
is loaded — the row renders unconditionally and the CLIENT hides it when
`window.API.api_orgs_apps` is absent (`_applyAppsAvailabilityState`, run on
both fresh and cached renders). Its handler opens `showFnAppsPopover`
(`editor-apps.js`), whose content is the addon's `GET /partials/fn-apps`.

## Query-param matrix (as actually built)

`fn-id`, `context`, `show-open`, `card-fn-id` (cell context), `binding-id`
(use-site context), `editable`, `edit-block-reason`. URL assembly lives in
`loadRowActionsContent` (`editor-row-actions.js`), a thin builder over
`loadPartial`.

## Server output shape (col-header example)

As assembled by the `:_partial-row-actions-{ns,i,open}-button` +
`:_partial-row-actions-col-header*` fn-defs in
`resources/packages/app/editor-row-actions/fns.edn`:

```clojure
[:div {:class "row-actions-content"
       :role "toolbar"
       :aria-label "Row actions"
       :data-context "col-header"
       :data-fn-id <fn-id-str>}
 [:button {:type "button"
           :class "namespace-badge action-icon"
           :data-action "namespace-move"
           :title "Namespace — reveal in Explorer / move"
           :aria-label "Namespace"}
  "ns"]
 [:button {:type "button"
           :class "description-badge action-icon"
           :data-action "description"
           :data-entity-type "fn"
           :title "Description"
           :aria-label "Description"
           :data-description <description-or-"">}   ; assoc'd from the fn row
  "i"]
 ;; Only when show-open ≠ "false" (default-on; callers pass show-open=false,
 ;; e.g. for anonymous fns). Otherwise the entry is nil and stripped by the
 ;; :filter :some? step before hiccup.
 [:a {:class "open-in-new-tab action-icon"
      :data-action "open"
      :target "_blank"
      :aria-label "Open in new tab"
      :title "Open in new tab"
      :href "#<name>"}   ; bare-name HASH fallback — the JS `open` handler
  "↗"]]                  ; overrides it with the qualified name on click
```

## Labeled-menu rendering (redesign 2026-08, commit 463e216c)

The popover renders as a vertical LABELED menu, not a strip of bare
glyphs: every button keeps its glyph AND shows its action name. The
labels come from the `aria-label` already on each button, surfaced via
CSS `.row-actions-content button[aria-label]::after`; a CSS grid gives
a fixed glyph lane so labels align. Consequences:

- **New buttons MUST carry a human-readable `aria-label`** — it doubles
  as the visible menu label, so a missing/cryptic one renders a
  label-less (or nonsense) menu row, not just an a11y gap.
- Delete is styled as the danger row (reads red); a disabled item's
  `aria-label` carries a SHORT inline reason ("Rename — fn in use",
  "Service settings — has free args") with the full sentence in
  `title` and in the click-to-open reason popover. Extend is never
  in-use-blocked — it creates a child, it doesn't modify this fn.
- Choosing an action that opens standalone UI (Run, Version history,
  Rename, …) auto-dismisses the menu; `ns` / `i` keep it open because
  their mini-popovers anchor inside it. The menu's zoom-follow is
  CLAMPED to ~1 (it's a text menu, read at UI scale).
- All rules are scoped inside `.row-actions-content` (popover-only), so
  the same `.action-icon` factory still renders as a compact square
  when used inline on a card row.

## Client dispatch contract

- Dispatch goes through the shared `registerActionHandler` /
  `bindActionDispatch` runtime (`web/runtime/graphden-runtime.js`), not a
  bespoke switch; `editor-row-actions.js` registers its handlers at load
  time.
- Use-site buttons carry only `data-binding-id`; the dispatcher recovers the
  rich arg object from the `_rowActionsUseSiteArgs` registry (populated at
  load time from client `lookups`, pruned on every richTypes refresh).
- Server-side lookups behind the partial: `:fn-row-by-id` (version-resolved
  `:get-entity` — a raw HSQL read here returns CREATE-TIME values and must
  not be reintroduced), `:request-authed?`, `:fn-ns-path` (a `:fix` walk),
  all in `app/lookups/fns.edn`.

## Live constraints

- **`isFnEditable` parity**: col-header / cell / use-site contexts gate
  editability CLIENT-SIDE at click time (`isFnEditable(fnId)` from
  `lookups`); only root-row gates on the server. If a server-side editability
  query is ever added, it must match the client check exactly.
- **Auth gating per button**: the partial checks request auth state inside
  the render (not just route-level 401).
- **Re-anchor**: the popover re-positions on zoom/pan; post-swap binding must
  not break that flow.
- **Fetch-per-open is gone — `_rowActionsHtmlCache`**: the first hover
  for a given URL fetches the partial and caches the swapped HTML
  (bounded FIFO, 300 entries); every later hover renders synchronously
  from the cache with zero fetch. The popover is held invisible
  (opacity 0) until content resolves, so neither a first fetch nor a
  warm open flashes a loading placeholder. **The full request URL is
  the cache key** — auth-derived params (`editable` / owned) are part
  of it, so sign-in/out naturally keys fresh entries; but it also
  means any NEW query param must be part of the URL at fetch time
  (added in `loadRowActionsContent`) or a cached entry for the old URL
  shape will serve stale HTML. The add-MI disabled state is recomputed
  against CURRENT `lookups` on every open, cached or not. The graceful
  error path (`row-actions-error`) still applies when a cache-miss
  fetch fails — keep it working.

## Decision guard: what stays in JS (do not re-attempt migration)

A post-ship re-survey (2026-06) checked every remaining popover for the same
treatment and rejected each — the partial pattern wins only on
**server-data-only** surfaces (route compilation, auth/editability state,
multi-button assembly). The rest is client-cache-driven or canvas-bound:

| Candidate | Why it stays JS |
|---|---|
| description-tooltip body | the dispatcher already gets `data-description` inlined by the partial; sidebar + edge-label callers read it from client-cached `graphData`. A partial re-fetches what the client already has and adds ~30ms per hover. |
| service badge | pure data-projection from the client-cached service map — a partial is a wasted roundtrip. |
| full-name tooltip | pure DOM build from a caller-passed string; no server data involved. |
| edge-label overlay | needs client-side BFS over `lookups` + type resolvers (`expectedSlotType`/`resolveArgType`) + graph-layer-anchored DOM; a server equivalent is a Phase-0-sized effort that splits one overlay render across client and server. |

These fall under `graphden-ui` §6's keep-JS criteria. Revisit only if the
data flow itself changes (e.g. the client stops caching the graph slice a
popover reads).

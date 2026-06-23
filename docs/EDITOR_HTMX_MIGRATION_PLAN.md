# Editor HTMX Migration Plan (Option 3)

**Status**: Planned, not started. Sized ~3-5 days of focused work.
Documented here so a fresh session can execute without re-deriving
the analysis.

**Branch context**: `refactor/popovers-to-graph-htmx`. The four
URL-coupling stages (Options 1+2) shipped earlier in this branch;
`window.API` is live and editor JS uses it for every `/api/*` call.
See the `feat(web/reitit) … feat(editor) … refactor(editor) …`
commits at the branch tip.

## Why this is in a doc instead of already shipped

The remaining editor JS popovers don't fit the existing partial
pattern cleanly. Two structural facts forced a re-scoping:

1. **The editor caches the full graph on the client** (`graphData` —
   sidebar index + selected subtree). Popovers built from this
   cached data don't have a server data-source: a partial would
   re-fetch what's already on the client. Net regression unless
   we ALSO restructure data flow.
2. **Most remaining popovers have rich client state** — hover
   timers, sticky modes, edit-mode textareas, glyph-flip on hover,
   Cytoscape zoom/pan tracking, keystroke filters. Server-rendered
   markup is ~10% of what they do; ~90% is JS lifecycle.

User decision (recorded 2026-06-23): restructure data flow, do the
multi-day refactor, accept +30-200ms popover latency for
architectural cleanliness.

## Phase 0 — Server-side primitives (PREREQUISITE)

Total estimated ~100-150 LOC across two sub-phases. The `:fn-fix`-
or-equivalent recursion concern got sidestepped — see 0b.

| Primitive | Status | Shape | Purpose |
|---|---|---|---|
| `:fn-row-by-id` | ✅ shipped (commit `08c77e7a`) | Pure fn-def: `:storage-query-call` → `:first` → `:decode-row` | Read one fn entity by id. Used by every row-actions partial to look up name / description / namespace-id. |
| `:request-authed?` | ✅ shipped (commit `08c77e7a`) | Thin alias of `:_bearer-equals-env?` | Conditional rendering of edit affordances. |
| `:fn-ns-path` | ✅ shipped (commit `fa990ca9`) | `defbase` with depth-capped loop (no `:fix` needed — base-fn iteration carve-out) | Produce `"core.refinements"` string from a `:namespace-id`. |
| `:fn-usage-count` | ⏳ deferred to Phase A4 | Multi-table count: `binding.ref-fn-id` + `binding-list-item.ref-fn-id` + `fn.parent-ids` membership | Only the root-row buttons need editability gating (✎ rename, ✕ delete). Col-header / MI-cell / use-site contexts gate editability CLIENT-SIDE in the dispatcher — server emits the button always, client re-checks `isFnEditable(fnId)` from `lookups` at click time. This keeps Phase A1-A3 unblocked. |
| `:fn-is-editable?` | ⏳ with 0c | `(zero? :fn-usage-count)` | Same as above. |

**Pattern note**: an existing `:_delete-fn-*` chain in
`web/crud/fns.edn:2107+` already counts the same three sources
for the delete-flow. When 0c lands, write a parallel chain over
`:fn-id` free arg (vs the delete-flow's `:_delete-target-id`),
OR refactor the delete-flow chain to take an `:fn-id` free arg
and reuse it.

## Phase A — Row-actions popover migration

Single partial `:partial-row-actions` handles 5 contexts via a
`?context=…` query param:

| Stage | Context | Buttons | Notes |
|---|---|---|---|
| A1 | `col-header` | ns, i, ↗ | Simplest. `↗` conditional on `show-open` client flag. |
| A2 | `cell` (MI cell) | ns, i, ↗, × Remove-MI, + Add-MI | × removes a parent from the cell-owning fn; + opens MI add picker. |
| A3 | `use-site-arg` | ns, i, ↗, × Remove-binding, ✎ Change-value | × deletes the binding (slot reverts to free-arg); ✎ opens free-arg-bind edit mode. |
| A4 | `root-row` | ▶ Run, ⌛ History, ⚙ Service, ✎ Rename, + Extend, ✕ Delete | Most complex. ⚙ disabled when fn has free args (check server-side). ✕ has destructive confirm + cascade. |
| A5 | (cleanup) | — | Delete `createNamespaceBadge` / `createDescriptionBadge` / `createOpenInNewTabButton` / `applyActionIconBox` from `editor-icons.js` (~400 LOC). Delete `buildColPopoverContent` / `buildCellPopoverContent` / `buildPopoverContent` from `editor-overlay-fn.js` (~150 LOC). |

**Server output shape** (example for col-header):

```clojure
[:div.row-actions-content
 {:role "toolbar"
  :aria-label "Row actions"
  :data-context "col-header"
  :data-fn-id <fn-id>}
 [:button.namespace-badge
  {:type "button"
   :data-action "namespace-move"
   :data-fn-id <fn-id>
   :data-ns-path <ns-path>      ; for hover tooltip
   :aria-label (str "Namespace: " ns-path
                    (when editable? " — click to change"))}
  "ns"]
 [:button.description-badge
  {:type "button"
   :data-action "description"
   :data-entity-type "fn"
   :data-entity-id <fn-id>
   :data-description (or description "")
   :data-name <name>}
  "i"]
 (when show-open?
   [:a {:href (str "?fn=" name)
        :target "_blank"
        :data-action "open"
        :title "Open in new tab"} "↗"])]
```

**Client side** (`editor-row-actions.js` additions):

```js
// Wire-up: replace each buildContent callback with this loader.
async function loadRowActionsContent(host, fnId, context, opts) {
  opts = opts || {};
  host.innerHTML = '<span class="row-actions-loading">…</span>';
  try {
    const url = '/partials/row-actions?fn-id=' + encodeURIComponent(fnId)
              + '&context=' + encodeURIComponent(context)
              + (opts.showOpen === false ? '&show-open=false' : '')
              + (opts.useSiteArgId
                  ? '&use-site-arg-id=' + encodeURIComponent(opts.useSiteArgId)
                  : '');
    const r = await fetch(url);
    if (!r.ok) {
      host.innerHTML = '<span class="row-actions-error">Failed</span>';
      return;
    }
    host.innerHTML = await r.text();
    bindRowActionsDispatch(host);
  } catch (err) { host.innerHTML = '<span class="row-actions-error">Network</span>'; }
}

// Single delegated click handler routes data-action to JS handlers.
function bindRowActionsDispatch(host) {
  host.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-action]');
    if (!btn) return;
    const action = btn.dataset.action;
    const fnId = btn.dataset.fnId;
    const fnEntity = lookups?.fnMap?.get(fnId);
    switch (action) {
      case 'namespace-move':
        if (isAuthenticated() && isFnEditable(fnId) && fnEntity) {
          enterNamespaceMoveEditMode(fnEntity, btn);
        }
        break;
      case 'description':
        showDescriptionTooltip({
          name: btn.dataset.name,
          description: btn.dataset.description,
          entityType: btn.dataset.entityType,
          entityId: btn.dataset.entityId
        }, e);
        break;
      case 'open':
        /* default <a> behavior */
        break;
      case 'run':
        showExecutePopover(fnEntity, btn); break;
      case 'history':
        showFnVersionsPopover(fnEntity, btn); break;
      case 'service':
        showServicePopover(fnEntity, btn); break;
      case 'rename':
        enterFnRenameEditMode(fnEntity, btn); break;
      case 'extend':
        enterExtendEditMode(fnEntity, btn); break;
      case 'delete-fn':
        confirmAndDeleteFn(fnEntity); break;
      case 'remove-mi-parent':
        removeParentInline(getFnById(btn.dataset.cardFnId), fnId); break;
      case 'add-mi-parent':
        addMIParent(getFnById(btn.dataset.cardFnId), null, 1); break;
      case 'remove-binding':
        deleteUseSiteBinding(getUseSiteArg(btn.dataset.useSiteArgId));
        break;
      case 'change-binding-value':
        enterFreeArgBindEditMode(getUseSiteArg(btn.dataset.useSiteArgId), btn);
        break;
    }
  });
}
```

## Phase B — Description tooltip body

Single partial `:partial-description-tooltip` returns the read-mode
body (text + close × + Edit button). JS retains hover lifecycle +
edit-mode textarea + save flow.

The Phase A row-actions dispatcher already opens the tooltip via
`showDescriptionTooltip(...)` with data from `data-*` attrs (no
graphData lookup). For NON-row-actions callers (e.g. the
`createDescriptionBadge` factory used standalone — see Phase A5
deletion), audit + migrate.

## Phase C — Other badges

| Stage | Target |
|---|---|
| C1 | Service badge (●) on root rows → server-rendered with status (running/failed/disabled/pending) computed server-side. |
| C2 | Full-name tooltip → server-rendered. Trivial. |

## Phase D — Cleanup

| Stage | Target |
|---|---|
| D1 | Audit `graphData` usage post-migration. Remove client-side caching of fn-level metadata that the popovers no longer need (description, namespace-id). Keep sidebar index + currently-selected subtree (still required for Cytoscape rendering). |
| D2 | Delete dead JS helpers (`applyActionIconBox`, `applyIconDisabledReason`, etc.). |
| D3 | Lint pass + full `bb test` + browser smoke. |

## Estimated net effect

| Metric | Before | After |
|---|---|---|
| Editor JS LOC | ~7,400 | ~6,000-6,500 (~12-19% reduction) |
| Partials | 14 | 18-20 |
| Interaction latency | <5ms popover open | +30-100ms first open (cached server-side after) |
| Page load | unchanged | unchanged |

## Risk hotspots

- **Auth gating per button**: server needs to check request auth
  state inside the partial (not just route-level 401). Pattern
  needs design.
- **Service badge state**: ⚙ button is disabled when fn has free
  args. Free-args computation is rich-types-dependent — server
  has it but routing through a base-fn needs care.
- **Cytoscape zoom/pan re-anchor**: Phase A doesn't change this;
  row-actions popover still re-positions on `cy.on('zoom pan')`.
  Verify the post-swap `bindRowActionsDispatch` doesn't break
  the existing re-anchor flow.
- **Network errors**: each popover open is now a fetch. Failure
  mode needs to be graceful (show "Failed" placeholder, allow
  retry). Current JS has zero network surface in popovers.
- **`isFnEditable` parity**: client-side check uses `lookups`
  built from graphData. Server-side query needs to match exactly
  — divergence would let users click ✎ on an uneditable fn (or
  vice versa).

## Files touched (estimated)

| File | Change |
|---|---|
| `resources/packages/app/editor/fns.edn` | + ~80 LOC partial-row-actions hiccup |
| `resources/packages/app/editor/fns.edn` | + ~30 LOC partial-description-tooltip hiccup |
| `resources/packages/app/routes/fns.edn` | + 2 new routes |
| `resources/packages/app/server/impls.clj` | + Phase 0 base-fns (~150 LOC) |
| `resources/packages/app/server/fns.edn` | + Phase 0 declarations (~50 LOC) |
| `resources/packages/app/editor/editor-row-actions.js` | + `loadRowActionsContent` + dispatcher (~80 LOC) |
| `resources/packages/app/editor/editor-overlay-fn.js` | – `buildColPopoverContent` etc. (~150 LOC removed) |
| `resources/packages/app/editor/editor-icons.js` | – badge factories (~400 LOC removed) |
| `resources/packages/app/editor/editor-tooltips.js` | – `renderDescriptionTooltip` read-mode (~50 LOC) |

## How to start

1. Implement Phase 0 base-fns in `resources/packages/app/server/impls.clj`
   (or carve out a new module `app/lookups/`). Declare in
   accompanying `fns.edn`. Unit-test each.
2. Phase A1 — single partial for `col-header` context. Wire one
   call site. `bb rebuild` + browser test. Commit.
3. Phases A2-A4 — add contexts one at a time, replacing callers
   one at a time. Commit per stage.
4. Phase A5 — delete the obsoleted badge factories + build
   callbacks. Big diff, mechanical.
5. Phase B onwards — same cadence.

Each stage independently revertable. If `bb rebuild` smoke fails
mid-stage, roll back that stage only.

## Related skills

- `graphden-ui` §6 (graph-first frontend) — the principle this
  whole effort serves.
- `graphden-packages-quality` §3.3 — for the new defbase impls
  (don't accrete composition; keep one library call per impl).
- `graphden-code-quality` §1 (decompose >100-line fns) +
  `graphden-fn-refactor` §3 (user-composability test) — for the
  Phase 0 base-fns.

## When this doc is stale

Delete this doc once Phase D ships. Until then it's the live
plan; refresh as primitives land or scope shifts.

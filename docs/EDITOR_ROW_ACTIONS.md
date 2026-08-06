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
| `root-row` | the selected fn's root row | ns / i / ↗ / ▶ Run / ⌛ History / ⚙ Service / ✎ Rename / + Extend / ✕ Delete |

Disabled-with-reason: `edit-block-reason` is a client-passed query param; the
⚙ reason is computed INSIDE the partial from `:service-blocking-free-args`
(the same predicate the create-service guard uses) — no param.

## Query-param matrix (as actually built)

`fn-id`, `context`, `show-open`, `card-fn-id` (cell context), `binding-id`
(use-site context), `editable`, `edit-block-reason`. URL assembly lives in
`loadRowActionsContent` (`editor-row-actions.js`), a thin builder over
`loadPartial`.

## Server output shape (col-header example)

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
- **Network failure**: each popover open is a fetch — keep the graceful
  "Failed" placeholder path working.

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

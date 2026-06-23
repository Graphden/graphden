# User Sites in Graphden — Plan

**Goal**: enable a Graphden user to build their own site
end-to-end inside the graph editor, without writing JS for the
80% case AND without giving up the escape hatch for the 20%.
The graph stays the single source of truth; no external IDE
hand-off; no per-save rebuild stutter.

The plan progresses in four blocks, each shippable on its own.
After Block 1 the editor itself runs on the new runtime; after
Block 2 a user can compose a simple page from platform
components; after Block 3 the escape hatch is wired; Block 4
makes it production-ready (routing, deploy, tutorial).

## Block 1 — Runtime extraction + event-handler DSL v0

**Scope**

- **`editor-runtime.js`** — extract the generic dispatch /
  partial-load / handler-registry primitives that currently
  live inside `editor-row-actions.js`. Public surface:
  `loadPartial(host, url, opts)`, `bindActionDispatch(host)`,
  `registerActionHandler(action, fn)`.
- **`editor-row-actions.js`** becomes a CONSUMER of the
  runtime — registers its row-actions-specific handlers
  (`namespace-move`, `description`, `run-fn`, `delete-fn`, …)
  via the runtime's registration API.
- **Event-handler DSL v0** — one atom `:dispatch-action` (a
  fn-def whose return shape is a `data-action` attribute pair
  the runtime knows how to dispatch). Demonstrate via one
  existing partial that today inlines the same attrs manually.
- Tests for the dispatch registry + the DSL serializer.

**Done when**: editor functionally unchanged from the user's
perspective; runtime + DSL are reusable building blocks ready
for Block 2's components to consume.

## Block 2 — Starter component library

**Scope**

- New module `resources/packages/web/components/` shipping
  ~10 components: `:button`, `:input`, `:textarea`, `:select`,
  `:checkbox`, `:form`, `:link`, `:image`, `:card`, `:modal`.
- Each component is a fn-def with `:return-type :component`
  (or whatever the type-row turns out to be) + slots for
  variants, content, and event-handlers.
- Each component's JS behavior (focus management, form
  submission, etc.) lives in a component-specific JS file under
  `web/components/<name>/` and is loaded via `editor-runtime`.
- Editor gets a "compose a page" affordance (likely a new
  picker) so the user can drop a component into a graph-built
  page.

**Done when**: a user can build a simple page (header + form +
submit button + result panel) entirely by composing platform
components in the graph, with the form actually working
(submit → POST → server response).

## Block 3 — `:custom-script` escape hatch

**Scope**

- New fn-def shape `:custom-script` with `:value :text`
  (textarea-edited).
- When attached to a page or to a component's event-handler
  slot, the server renders it as inline `<script>…</script>`
  in the page response — OR as a `data-custom-handler` attr the
  runtime evaluates on dispatch (the right shape depends on
  use-site).
- Edit-mode popover with a plain textarea (no Monaco — that's a
  separate later block if it's worth the +500 KB).

**Done when**: the user can express interactive behavior the
platform components don't cover (e.g. a custom hover effect),
without leaving the graph editor or copy-paste through an
external IDE.

## Block 4 — User-site infrastructure + docs + tutorial

**Scope**

- **Routing**: user defines their own `:get-route` /
  `:post-route` fn-defs; they're mounted under a configurable
  prefix (per-site, per-branch, or single-site-per-deploy —
  decided in this block based on prior-block experience).
- **Deploy**: clear path from "I built this in the editor" to
  "it's live somewhere" — likely an integrant key that
  promotes user-defined routes alongside the editor routes,
  with a documented separation.
- **Tutorial**: `docs/tutorial/build-a-site.md` walking through
  building a contact-form-with-server-side-validation site
  start to finish using only graph composition.
- **CLAUDE.md / docs Map**: cross-references to the runtime,
  component library, escape hatch.

**Done when**: a new user can read the tutorial, build the
example end-to-end, deploy it, and have working JS without
opening an editor outside graphden.

## What's explicitly NOT in this plan

- **SCI (small-clojure-interpreter) on the client**. 300 KB JS
  payload + 2-5× perf hit vs compiled JS. The cross-target
  fn-def story is appealing but for typical user sites the
  components + DSL + escape hatch cover the surface without
  paying the interpreter cost. Revisit when there's a real
  high-volume cross-target need (e.g. complex form validators
  that must run identically client + server).
- **Hyperfiddle-style server-side reactivity**. Every keystroke
  → HTTP roundtrip would hit graphden's latency wall (30-100ms
  per interaction locally, 100-300ms on prod). Per-event
  dispatch via Block 1's DSL covers the same UX with no
  roundtrip on common interactions.
- **A bundler / minifier / source-map pipeline**. Editor JS is
  currently ~17 K LOC un-minified ~700 KB. User sites would add
  on top. If/when it becomes a load-time bottleneck we add
  esbuild or similar; before then it's premature.

## Open design questions per block

Each block had "what shape is right" questions answered as the
block shipped. Decisions are recorded here so the resolution
isn't lost; remaining open work is tracked under "v1 follow-
ups" below.

- **Block 1** — runtime delivery: shipped as
  `/assets/graphden-runtime.js` (separate route from
  `/assets/editor.js`). User pages load the runtime bundle
  without dragging in Cytoscape + WebSocket subscriptions.
  See `app.editor/:_graphden-runtime-js-handler`.
- **Block 2** — component typing: shipped as plain
  convention (`:parent :hiccup`). No `:return-type
  :component` type-row; the editor surfaces them via package
  / namespace rather than type. Strict typing can land later
  if a real use-case (e.g. picker UX) needs it.
- **Block 3** — textarea editor: shipped as plain textarea
  with `data-field-kind="text"` and `spellcheck="false"`.
  No syntax-highlighting indicator either way — the +500 KB
  Monaco bundle (or even a CodeMirror lite) waits for real
  user demand.
- **Block 4** — single-site v0 shipped. `:user-site-routes`
  is a single shared mount-point; users edit
  `app/user-site/fns.edn` directly to append routes. The
  declarative-sync resets `:items` on every `bb deploy`, so
  for v0 the EDN IS the source of truth. Multi-site,
  branch-scoped routing, and auto-discovery of user-route
  packages are v1 follow-ups (below).

## v1 follow-ups

Not gating v0 ("can a user build and deploy a site today?" =
yes), but the natural extensions once real users surface real
needs:

- **Auto-discovery of user routes**. Today the user edits
  graphden's EDN to add their routes to `:user-site-routes`.
  Better: a convention where any fn-def in a package marked
  `:user-mountable true` (or returning a `:reitit-route-entry`
  type) gets auto-included at boot. Requires a small schema
  field + a startup-time scan.
- **Branch-scoped sites**. Use graphden's existing per-branch
  fn-versioning to host MULTIPLE user sites on one instance,
  each addressed via the branch picker. The infrastructure
  exists; the routing decision (URL prefix vs hostname vs
  query param) is the open question.
- **Multi-site at the prefix level**. Mount user A's site at
  `/sites/alice`, user B's at `/sites/bob`. Requires a
  `:site-prefix` slot on each route entry + a per-site
  isolation story (auth, secrets, DB).
- **Form-data echo in the demo**. The shipped
  `/demo/contact` POST handler returns a static "Thanks!"
  partial. Demo-quality follow-up: parse the form body
  (`:parse-form-body`), echo the submitted email back in
  the response. Shows the `:parse-form-body` → `:get` →
  `:str` chain end-to-end.
- **Monaco / CodeMirror inline editor** for `:js-source`
  textarea widgets — only worth doing once `:custom-script`
  bodies get long enough that plain `<textarea>` editing
  becomes the friction.

## Status

| Block | Status | Notes |
|---|---|---|
| 1 | shipped | runtime + `editor-row-actions.js` consumer + `:dispatch-action` DSL (`web/runtime`) — landed 2026-06-23 |
| 2 | shipped | starter library (`web/components`): `:button`, `:input`, `:textarea`, `:option`, `:select`, `:checkbox`, `:form`, `:link`, `:image`, `:card` + `:_*-attrs` helpers; built-in handlers `navigate` / `submit-form` (`editor-actions-builtin.js` + `/assets/graphden-runtime.js` bundle); contact-form demo at `/demo/contact` (`app/contact-demo`). Landed 2026-06-23. |
| 3 | shipped | `:js-source` type alias + `:custom-script` + `:wrap-custom-script` for page-level inline JS; `:dispatch-custom` DSL + `custom` action handler for inline button handlers; `:_form-js-source` textarea widget; contact-demo "Wave at me" button demonstrates the escape hatch end-to-end. Landed 2026-06-23. |
| 4 | shipped (v0) | `app.user-site` templates (`:user-page-route`, `:user-page-handler`, `:user-page-rendered`, `:user-runtime-scripts`, `:graphden-runtime-script-tag`, `:user-bootstrap-script`); `:user-site-routes` mount-point spliced into `:all` via `:concat`; contact-demo refactored to consume the templates; Lesson 14 walks through building + deploying. Open questions (multi-site, branch-scoped routing, auto-discovery) deferred to follow-up. Landed 2026-06-23. |

## Related docs

- [docs/EDITOR_HTMX_MIGRATION_PLAN.md](EDITOR_HTMX_MIGRATION_PLAN.md)
  — as-shipped reference for the row-actions partial; the
  runtime extraction in Block 1 reuses its dispatcher pattern.
- [docs/PARTIALS.md](PARTIALS.md) — graph-native HTML partials
  with HTMX 2.x wiring + per-partial recipe.
- `graphden-ui` skill (`.claude/skills/graphden-ui/SKILL.md`) —
  JS file organisation rules, design-token policy, the four
  "keep JS" exceptions in §6 that scope which surfaces are
  migration candidates.

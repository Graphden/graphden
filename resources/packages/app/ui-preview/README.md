# Graph-backed editor account menu

The ordinary editor uses bundled graph plans for the account menu and function
picker and Explorer navigation trail by default, including when tenancy is active. These plans contain only
shipped package definitions; enabling them does not export tenant graphs or
change another user's preferences. Regenerate the checked-in artifacts with
`clojure -M:dev tools/ui_preview/generate_builtin_plans.clj` after changing their
source graphs. The generator validates the supported primitive ABI and records
a source fingerprint; its tests check that the artifacts match the sources.

Settings → Appearance can select an ordinary personal theme graph. It executes
on the server through the normal access checks, with effects disabled and a
bounded execution time. Only the validated theme result reaches the browser.
The selected graph and its last successful colors are stored in the existing
owner-scoped preference. This is independent of the developer review URLs below.

The editable product review runs in the real editor on a self-hosted instance.
Prepare an editable copy of the ordinary account-menu and theme graphs:

```sh
bb -cp src tools/ui_preview/prepare.clj --account-menu --picker http://localhost:9100 account-menu-review user.account-menu
```

`AUTH_TOKEN` supplies authentication when required. Preparation refuses tenancy
and existing branches before writing. It creates ordinary functions in a unique
namespace on the requested new branch and validates the plan through the
authenticated `/ui-preview/plan` read endpoint. The printed URL opens the editor
with three explicit entry UUIDs and the review branch. Opening or reloading that
URL never imports a graph or executes an arbitrary function named in the URL.
Package templates retain their ordinary package-ownership protection.

`account-menu-initial`, `account-menu-update`, and `account-menu-view` are the
three entry functions. The graph creates the existing `auth-menu` frame and the
real Settings and Organization buttons. The host retains identity, capability-
gated destinations, tutorial badges, authentication/session actions and community
links. It supplies the currently available item list to the graph, which controls
keyboard navigation across all those items, opening/closing state and motion
parameters. Browser focus, geometry, animation playback and authorized callbacks
remain host effects. Enter/Space activate the existing native button or link.

Theme inputs are the effective saved/built-in theme before this graph layer:
`{:accent text :canvas-background text}`. Output maps use the actual CSS token
strings `--gd-flow`, `--bg`, and the scoped `--gd-account-menu-hover`. Existing
preferences and light/dark styling remain the base; graph overrides do not write
back to the preference store.

Color values extend `color-const`, whose value slot is narrowed to the ordinary
`color` refinement. Its registered form offers RGB, alpha and manual HEX input,
and its compact representation is drawn next to canvas literals. The host
resolves effective CSS theme colors to HEX before supplying the pure graph.

To edit a shared value, open `theme-canvas-background`, remove its `value`
reference to `_theme-base-canvas-background` and bind a color string instead.
Both canvas and menu hover consume it. Then open `account-menu-hover`, remove
its reference to `theme-canvas-background` and bind another color; only the menu
hover changes. `theme-accent` similarly controls the real shared accent token.
`account-menu-key-map` exposes the ordinary keyboard mapping; changing its
ArrowUp key to `k` changes navigation in the real menu.

After saving an edit, reload the browser document to load its updated plan.
Selecting another graph only changes the editor selection; this bounded review
does not yet refresh the running menu plan automatically after every save.

The renderer uses locally bundled Preact for DOM
reconciliation. `GraphdenRenderer` accepts live Hiccup values, including finite
sequences produced by graph `map`, and preserves keyed elements between renders.
Keys are normalized to strings; duplicate sibling keys are rejected before
rendering. Component owners must dispose their renderer when the surface closes.
It does not evaluate graph functions or attach business callbacks. Native host
code owns authorized actions, focus, anchored positioning and animation playback.

`account-menu-common-tree` supplies the managed menu subtree;
`account-menu-styles` supplies structured selector/declaration records. Theme
colors feed those declarations through ordinary graph dependencies. The host
checks and scopes rules to the component before serializing CSS. Matching live
components share one stylesheet; disposal releases it. Arbitrary stylesheet
text, HTML, script attributes and network URLs are outside this renderer contract.
The picker uses one managed list subtree; native search input, type checking,
ranking, selection callbacks and mismatch explanation retain their existing
owners. `picker-section`, `picker-row` and `picker-effect` create the actual
markup through ordinary nested `map` calls. The runtime budget for this review
is 150,000 operations with a 10,000-node renderer budget. Differential fixtures
cover 120 rows with all nine effect badges, including 120 separate namespaces,
and validate the live result through the DOM-tree adapter. This graph backend
supports at most 120 visible rows and 120 sections per popup; larger category
lists release the graph component and retain the existing native renderer, so
namespaces remain available. This is a bounded review, not an unrestricted
browser implementation of all Graphden functions.

There is no application transpilation step or CDN dependency: Preact uses the
existing, separate vendor-build pipeline.

The browser backend supports `const`, `if`, `equal?`, `list`, `map`, `get`,
`assoc`, `zipmap`, `count`, `add`, `mod`, and `hiccup`. The server resolves
inheritance, bindings and renames with the existing compiler helpers. The client
executes an ID-based derived plan; it does not resolve graph names or redefine
inheritance. Static `map` callbacks support zero or one lambda parameter and
ordinary captured inputs; identity references and functions that produce
callables are rejected. JVM `map` eagerly realizes callbacks but returns a
sequence, which the browser preserves. Calls and arguments are lazy and memoized
per entry invocation.
Keyword/string identity is preserved. Values are nil, booleans, safe integers,
strings, keywords, vectors and maps with string or keyword keys; finite result
sequences are materialized with a bound. Unsupported values/operations fail.

Arbitrary graph-plan export is unavailable when tenancy is active. This does not
disable the shipped default plans or server-evaluated personal themes.
The authenticated export captures
one read-only database snapshot and derives its type policy from those same
rows in isolated registries. Original classifications are frozen before that read from already resolved
built-in type shapes, with fixed built-in secret semantics. Custom marker tags
and unresolved aliases in cached signatures are refused: separately mutable
registries cannot safely reinterpret them. Live composed type entries and
resolver caches do not authorize exports alone: each cached signature must carry a matching
internal checksum of its checked definition, and both its original policy and
the fresh check must allow capture. A missing/mismatched checksum fails closed.
The checksum is never part of the public type payload or browser plan. This
first backend targets ordinary imported/rechecked copies; package-authored
compositions whose source shape differs from stored rows can be refused.
Concealed functions, secret classifications, failed
type checks and unsupported reachable dependencies are rejected; exception
causes are not exposed. The editor receives no exported graph until authenticated loading succeeds.

The original `/ui-preview` three-choice example is retained as a technical
harness for independent mount state and interpreter parity. The preparation
command without `--account-menu` creates that harness; it is not the product
review artifact. Its existing browser/JVM tests remain separate from real-editor
coverage. Actual account-menu review must exercise graph editing, native actions,
light/dark/custom themes, keyboard/focus, accessibility and intermediate animation
frames in the editor itself.

The Explorer trail uses `app.ui-recents` ordinary initial/update/model/view functions.
The graph owns named selection dedupe, the six-entry persisted trail, pin toggles,
selected/pinned filtering, visibility, row Hiccup and scoped styles. Its host owns
localStorage validation and persistence, namespace lookup, delegated navigation and
renderer cleanup. A personal component selection loads candidate entry functions; a rejected
candidate retains valid state through the built-in fallback.

The bounded primitive subset also includes `filter`, `take`, `concat`, `str` and
`str-starts-with?`. Filtering returns an eager sequence; take/concat return vectors,
matching the JVM primitives. `str` accepts scalar parts (nil, text, keywords,
booleans and safe integers); collections are rejected because their JVM printed
representation is outside this browser subset.

Managed graph hosts own their event targets through the renderer's private DOM
ownership registry. The legacy `bindActionDispatch` skips those targets before
reading action attributes. Component host callbacks still handle their own
controls; native sibling actions retain the legacy dispatcher. Disposal releases
ownership. Author-supplied DOM attributes cannot claim or bypass ownership.

`GraphdenComponent` owns a component's state and serial event queue independently
of its DOM mount. Its entry contract is `initial(inputs) -> state`,
`update(state,event,inputs) -> {state,requests}`, and `view(state,inputs) -> view`.
Before committing a transition it validates the next state, complete view markup
and styles, and every bounded request against fixed host descriptors. Rendering
must succeed before state commit and effects. Initial loading and runtime
replacement perform no requests; a rejected candidate preserves the active state
and runtime. Disposal and successful replacement cancel pending effects and
ignore their late completions. Reentrant events run after the current commit.

Recents requests are `persist-trail`, `persist-pins` (each contains only entries),
and `navigate-fn` (only function ID and qualified name). The host fixes storage
keys and navigation callbacks and validates UUID identities. A storage snapshot
change produces an explicit `sync` event without persistence requests. Removing
the DOM host disposes only the renderer; controller state survives a remount.

Validation failure prevents a transition's render, state commit and all effects.
A host effect failure happens after a successful state commit: previously run
effects are not rolled back, later requests in that batch are skipped, and queued
events are cleared. The recents adapter reports storage failures while retaining
the visible committed state; it does not claim the change was saved.

Recents accepts up to 1000 pinned identities and six recent entries. Its local
runtime budget is 1,000,000 operations and the renderer budget is 10,000 nodes;
the upper-bound fixture executes initial/view/update and validates the complete
tree within both limits.

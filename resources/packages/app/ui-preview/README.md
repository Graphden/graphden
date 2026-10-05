# Graph-backed editor account menu

The product review runs in the real editor on an isolated self-hosted instance.
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

The next, unmerged renderer slice uses locally bundled Preact for DOM
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
is 100,000 operations: 120 rows with all nine supported effect badges and a
folded group require 89,705 in the differential fixture. It is a bounded review,
not an unrestricted browser implementation of all Graphden functions.

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

Export is unavailable when tenancy is active. The authenticated export captures
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

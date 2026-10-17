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

The browser backend supports `const`, `if`, `equal?`, `nil?`, `coalesce`,
`list`, `map`, `filter`, `group-by`, `sort-by`, `get`, `assoc`, `zipmap`,
`count`, `take`, `concat`, `vals`, `add`, `mod`, `str`, `str-starts-with?`,
`str-lower`, `str-trim`, `str-contains?`, and `hiccup`. The server resolves
inheritance, bindings and renames with the existing compiler helpers. The client
executes an ID-based derived plan; it does not resolve graph names or redefine
inheritance. Static `map` callbacks support zero or one lambda parameter and
ordinary captured inputs; identity references and functions that produce
callables are rejected. JVM `map` eagerly realizes callbacks but returns a
sequence, which the browser preserves. Calls and arguments are lazy and memoized
per entry invocation. Each operation forces the same arguments as its JVM
counterpart: `if` selects one branch; `coalesce` implements the JVM's `or`,
selecting its lazy default when the value is nil or false.
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

## Personal component manifests

Appearance can copy account-menu, function-picker, Recents, Inspector, Explorer
and the tutorial catalogue
independently, or create a complete copy. Its `ui` entry is an ordinary list or a composition
of retained and new lists through `concat`, returning the named
`app.ui-components/ui-component-manifest` type. Each item has this shape:

```clojure
{:component-id uuid
 :entries [{:role :initial :fn-id uuid}
           {:role :update :fn-id uuid}
           {:role :view :fn-id uuid}]}
```

The component discriminator is the UUID of the shipped view:
`app.ui-account-menu/account-menu-view`, `app.ui-fn-picker/picker-view`,
`app.ui-recents/recents-view`, `app.ui-inspector/inspector-view`,
`app.ui-explorer/explorer-toolbar-view`, `app.ui-tutorial-catalog/catalog-view`, `app.ui-canvas/canvas-view`, or `app.ui-canvas-values/canvas-values-view`.
Menu, Recents, Inspector and Explorer require all three roles; the picker and
catalogue require only `:view`. Entry UUIDs select personal graph functions.
The subtypes are `ui-component-entry`, `ui-component-entries`, and
`ui-component-descriptor` in `app.ui-components`.

The configuration graph constructs descriptors through ordinary `const`,
`list`, `zipmap`, and bounded `concat` dependencies. It is projected with the
compiler's binding and inheritance rules; no user graph is executed to discover identities.
UUIDs must originate in a `const` value binding narrowed to `:fn-ref`:

```clojure
{:name :_recents-view-id :parent :const :return-type :uuid
 :args {:value {:ref :my-ui.recents/recents-view :type :fn-ref}}}
```

An identity edge produces a UUID, so `:fn-ref` is a subtype of `:uuid`.
The reverse relation is false: a UUID literal cannot bind an identity slot.
The identity edge reads neither the target's result nor its effects.

A literal UUID, computed identifier, resolver, arbitrary operation, duplicate
component/role, or malformed descriptor refuses the personal plan. Projection
is finite: at most 64 descriptors, three entries per descriptor, 64 nested
calls, and 4096 projection visits, in addition to the source collection bounds.
Field names and roles are finite keyword literals. Component bodies retain the
normal bounded browser ABI rather than the narrower manifest projection ABI.

`POST /api/ui/components/plan` accepts exactly
`{"component":"account-menu"}`, `{"component":"fn-picker"}`,
`{"component":"recents"}`, `{"component":"inspector"}`,
`{"component":"explorer"}`, `{"component":"tutorial-catalog"}`, `{"component":"canvas"}`, or `{"component":"canvas-values"}`. The existing owner/org/branch-scoped `components`
preference selects the configuration. Each request authorizes manifest source,
checks its original secret classification and snapshot provenance, then reads
and exports only that component's body closure. Other entry targets provide
authorized identity metadata without loading their bindings. The response
contains the bounded selected plan and navigation roots, never the source
manifest or other component plans.

Fixed shipped discriminator metadata uses the explicit `:identity-only?`
source permission. The server owns its UUID/namespace allowlist; this permission
does not grant access to the discriminator's implementation. Ordinary body
collection still requires the original source grants. A component missing from
an otherwise valid manifest returns `ok: false`, `code: "component-missing"`,
and `fallback: "builtin"`; unknown descriptors do not become executable plans.
The classified missing-component response selects the built-in component and
releases the previous override without a warning. Other refusals retain an
explicit diagnostic.

Legacy configurations inheriting `app.ui-components/ui-components` continue to
use the exact four identity slots (`menu-initial`, `menu-update`, `menu-view`,
`picker-view`). They remain selectable and exportable; Recents uses the built-in
component when that old configuration has no Recents or catalogue descriptor. No fifth slot
is added to the legacy interface.

## Exact creation cleanup

Tutorial UI manifests use `POST /api/functions/delete-batch` with an explicit
branch header and exact `{id, name, namespace-id}` tuples. The server repeats
identity, write-access and external-dependency checks inside one writer
transaction. A stale tuple or prohibited reference rejects the whole set;
the creation receipt does not grant additional access.

The response partitions only the requested UUIDs into `deleted` and
`already-absent`. The client retires receipts only after validating this entire
partition. Lost, partial or refused replies keep the ledger available for
retry. Ordinary lesson creations retain their existing individual cleanup path,
and namespaces are removed only after confirmed function cleanup.

## Selective personal copies

Appearance offers **Personalize account menu**, **Personalize function picker**,
**Personalize recent functions**, **Personalize Inspector**, **Personalize Explorer**,
and **Personalize tutorial catalogue**. Once that group is present, its action opens
its existing view instead. Creation copies only the selected shipped component
and its necessary owned dependencies. Recents and picker copy no theme graphs;
the menu copies its shared color leaves without selecting a different theme.
**Create personal graphs** retains the complete bundle used by Lesson 25.

The existing preview/apply endpoints accept an optional `component` field with
one of the shipped component groups above. Preview resolves the current
owner/org/branch selection on the server and validates its manifest source under
policy frozen before reads. Apply repeats that proof and rejects a stale preview
or an already-present group. Client roots are never source authorization.

An addition creates an ordinary `concat` manifest referencing the existing
manifest and a new descriptor list. Earlier graph identities, authored composition,
unknown descriptors and edits remain intact. A legacy four-slot configuration is
adapted once into descriptors referencing its original entries. No existing
component body is copied or rewritten. The create-only receipt lists only newly
created functions and namespaces; retained references are never cleanup-owned.

The receipt's `expected-preference` is an owner-authoritative row presence/value
comparison. `PUT /api/prefs/:key` optionally accepts
`{"value": document, "expected": {"present": true, "value": previousDocument}}`.
For an absent row use `{"present": false, "value": null}`; an existing row storing
null has `present: true`. Old PUT callers keep their original contract. Both
writers acquire the same owner/key transaction lock, and conditional conflicts
return 409 `stale-preference` without the current value. Invalid comparisons
return 400; storage without transactional support refuses conditional writes.

Graph creation and preference activation are separate commits. A selection
change during creation, including in another tab, leaves the new graphs available
without replacing the newer preference. Tutorial cleanup restores the earlier
selection only while the current preference still selects its deleted config;
it never deletes retained component identities or changes an unrelated theme.

The Inspector component owns its header, kind label, ARIA tabs, Overview's
Used-by section and scoped style rules through ordinary typed
`initial/update/view` graphs. The same three entries select the `chrome` or
`usages` surface from typed inputs; each surface retains its own state and
empty renderer host. Used-by graphs deduplicate `(kind, fn, slot)`, group and
sort callers, fold anonymous rows, cap each group at 20 named rows and filter
the rendered rows when more than 30 usages are present. Public names sort
before private names, with case-insensitive name order and stable identity ties. The
server still authorizes the usages API; the browser receives only its visible
metadata and validates navigation against that response. A legacy personal
Inspector that provides only chrome keeps its customized header and tabs;
Used-by uses the bundled section and marks this local compatibility fallback.
The host validates
four fixed tab identities and finite load/focus/close/resume requests. Only those four
tab IDs may appear in graph chrome, once each; native host IDs cannot be shadowed. Native server
panels and diff conversations are sibling hosts; chrome updates never replace
Run controls or discussion drafts. The open state controls the narrow sheet;
the wide native column remains available after a sheet closes. Body ownership lasts
until selection, tab or account replacement. Each read has its own latest-wins
token and abort signal; closing interrupts reads, and a wide viewport resumes
only those interrupted reads in their original native hosts. Initial Run forms
may finish while hidden. A separate focus epoch prevents their old autofocus
from firing after close, while keyboard tab navigation suppresses autofocus.
CodeMirror disposal precedes actual native body replacement. Used-by reads
share the same body owner and latest-wins/resume lifecycle; replacing their
data retains the graph's filter text and keyed input without replacing the
server Overview subtree. Inspector plans use the existing bounded browser
budget of 150,000 operations and 10,000 renderer nodes for this section, including
the server's maximum 400-row usages response.

The Explorer toolbar, function-row, namespace-header, filter-choice and saved-view popup presentations share the ordinary
`app.ui-explorer` initial/update/view identities. The view's typed `surface`
input selects the finite kind/status toolbar, one tree body, an active filter
chip, one Add filter option or one saved-view popup item. Host
projections supply only already authorized cache summaries: kind and problem
flags, current test/service status, the display name, and a secret's vault path
(never its value). The graph chooses marker Hiccup and scoped styles. Empty
markers produce no DOM. Each loaded row gets a small renderer host; the list
holds one shared row stylesheet and the same runtime serves every row, without
a whole-tree cap. A legacy personal Explorer view that only supports the toolbar
keeps that toolbar and uses the shipped graph for rows, with a visible section
diagnostic.

The graph emits only finite toolbar filter/detail and exact filter/view-choice
requests; native adapters
invoke the existing mutation functions. Native treeitems retain function IDs,
ARIA, focus, selection, actions, lazy namespace loading, search, filter/view
membership, and their existing truncation semantics. Filter buttons retain
their native identities and accessibility labels; the graph owns their passive
body and scoped styles, while the chooser keeps its name input and fn picker
stable across asynchronous view-list refresh. The view popup keeps its native
input and keyed action buttons in place while graph-list replies update only
the affected rows; an account change closes it and rejects old results. The graph's
`initial` with `surface=view-form` also returns a validated policy record from
the current filter/edit/auth/API/pending facts and draft name. It chooses form
visibility, save or edit mode, button availability, Enter target and the empty
name message. The host applies this model to its existing controls, and still
checks live authority and the pending request before any save. A personal plan
without this form policy falls back only in the view section. Graph output cannot add an ID,
destination, interactive element, or authorized action. One runtime and one
shared choice stylesheet serve all loaded choices without a collection cap.

Create-secret, Dismiss-all and Run-all buttons remain native siblings because
their pending HTTP operations own their labels and disabled state. The graph
projects only their visibility. Hiding a pending action or refreshing counts must
preserve the exact button and operation; account/context changes invalidate old
personal-plan replies. Legacy personal plans without filter surfaces retain
their valid toolbar and use the shipped filter presentation with a section
diagnostic. Shared native chip styling still serves the comparison lens.
Toolbar layout/count rules are structured graph
styles. Appearance can personalize this complete toolbar as one component while
retaining unrelated authored descriptors and themes.

The tutorial catalogue is a view-only `app.ui-tutorial-catalog` component.
Its `inputs` record contains ordered, authorized lesson display facts, a
browser-local query, resume display facts and a text-only copy string. The
ordinary graph filters first, then uses pure `core.hof/partition-by` to form
adjacent chapter runs; A/B/A can remain three runs or become one A run when B
is filtered away. It returns passive Hiccup for introduction, counts, row
bodies and empty state, plus a bounded structured stylesheet and resume label.
The host validates every returned row against the original lesson order and
keeps local reading history, requirement checks, URLs, native controls, focus
and actions. One shared stylesheet is attached only to passive graph hosts,
never the dialog or its controls. Old personal manifests without this view use
the shared default while other selected components keep their identities.

### Passive canvas presentation

`app.ui-canvas/canvas-view` is a view-only component. Personalize **canvas
presentation** in Appearance to create an ordinary child and edit its graph;
shared defaults, full templates and selective copies use the same component
identity. It owns passive bodies within the existing canvas, with no new
requests, authentication decisions, geometry or editing behavior.

Its one `inputs` record has these fields:

| Field | Type / values | Default |
| --- | --- | --- |
| `surface` | `fn-name`, `root-diagnostics`, `branch-local`, `deep-free` | Explicit for every call |
| `name` | Nullable text; existing display label with one private prefix removed | `nil` |
| `secret`, `root` | Boolean display classifications | `false` |
| `type-errors`, `failed-runs`, `lint` | Non-negative integer counts | `0` |
| `branch-state` | `off`, `own`, `inherited` | `off` |
| `branch-seed` | Nullable public source label | `nil` |
| `deep-free` | List of argument names | `[]` |

The host fills irrelevant fields with these defaults so one precise record
supports four finite surfaces without changing the evaluator ABI. No function
ID, secret path/value, editable flag or effect authority enters the record.
The existing `displayLabel` projection remains native; the graph determines
its displayed body. Root diagnostic hosts exist only on the navigation root,
even if a personal view would ignore `root`.

The exact output is `{tree, title, styles}`. `tree` uses the existing Hiccup
value type and only passive `span`, `small`, or `strong` elements, with bounded
attributes and component-local classes. `styles` uses the existing structured
rules (`selector` text and text-keyed declaration map); all surfaces and inputs
of one selected view return the same common stylesheet. Rules scope only empty
body hosts, never native controls or their outer rows. A changed scope is
restored after rendering; overlay disposal releases bodies and the shared
sheet. A malformed personal surface diagnoses the component and selects the
builtin for all its bodies.

`title` can customize metadata presentation; an empty value preserves the
native explanation. Native branch-local accessibility labels and the
"Click to change" suffix remain independent of that value. The outer strip
retains edit guards, keyboard behavior and its popover anchor. Deep-free names
remain in order with the same `⇣` and collapsed inter-name spaces, with no
visible row truncation. A runtime-budget failure reports presentation failure
explicitly, including the free-argument count if the builtin also cannot render.

Fn-row Run/actions/service controls, full-name tooltips, diff markers,
expansion/hover, native ARIA, layout measurement and viewport motion retain
their existing owners. Account replacement or a settled preference-owner change disposes bodies before
a delayed personal-plan reply can install old content. First preference
hydration retains bodies only when authentication, organization and branch
still match the context that supplied their facts.

### Passive value and type presentation

`app.ui-canvas-values/canvas-values-view` has only the `view` role. It is a
separate ordinary component so existing personal `canvas-view` inputs and
plans keep their exact contract. Appearance's **value and type presentation**
choice supports the same authorized defaults, full-copy and selective-copy
paths as the other components.

Its precise `inputs` record contains:

| Field | Type / values | Host default |
| --- | --- | --- |
| `surface` | `value-label`, `type-chip`, `type-history` | Explicit for every call |
| `label`, `secondary`, `secondary-title` | Text | Empty text |
| `secondary-kind` | `none`, `refinement`, `narrowing` | `none` |
| `history` | List of `{label: text, source-label: text, kind: enum}` | `[]` |
| History `kind` | `none`, `binding-override`, `slot` | Explicit for every entry |

Irrelevant fields receive those defaults; they do not add optional semantics to
record subtyping. Native type/refinement formatters project the existing primary,
secondary and tooltip labels. A history `source-label` is the existing public
source-name join with a comma and space, not identities or a second type-resolution algorithm.
The graph composes arrow, parentheses, kind copy, passive trees and styles.

The exact output remains `{tree, title, styles}` with the existing Hiccup and
structured-style types. All surfaces share one stable stylesheet. This new
role accepts only passive `span`, `small`, and `strong` nodes, component-local
classes and in-flow styles after the shared CSS policy normalizes them. Its
finite style contract refuses positioning/offsets, fixed heights, negative or
computed margins and other paint that escapes measured flow. Numeric spacing
uses non-negative `px`, `rem`, or `em` lengths; line height is `normal` or a
unitless number at least one. Invalid personal output diagnoses the component
and falls back to its builtin across all bodies. A builtin budget failure
reports an explicit failure, including the history entry count.

The native value click target, type chip, sequence brackets, editing/expansion,
provenance, drag handles, native titles and ARIA keep their exact owners. The
component's optional title does not replace their full accessible explanations.
Native truncation and whitespace glyphs still produce the value label. Secret
references skip this component entirely: their path and secret data never
become its inputs. The already sanitized `literalRepr` is rendered through the
existing representation path in a sibling preview host; neither renderer owns
or replaces the other's subtree.

Both canvas components reuse one passive-host lifetime implementation and a
coalesced native measured-layout pass. It reads intrinsic value-card widths,
actual edge-label widths and natural card heights, reserves history row space,
and uses the existing grid/column rules and edge anchors. A sequence gap includes
its actual member controls. It can shrink again after returning to the builtin;
function-card width, dragged positions and the viewport transform retain their
existing contracts. Scope changes dispose node and edge bodies, release sheets,
abort old requests and reject late replies before rendering another owner.

### Inheritance and function metadata presentation

`app.ui-canvas-inheritance/inheritance-view` is a separate passive component
with `initial` and `view` entries. `initial` receives one authorized BFS ordinal,
finite clickability/equivalence facts and fixed-size prior presentation state.
It returns a complete ordinal receipt and next state. The host stages the whole
sequence before joining receipts to its original row owners. A refused personal
step discards the entire staged sequence and reruns the builtin from the original
seed. Clickable and MI boundaries, row ownership, root ancestry and legal MI
columns are checked against native facts. A personal graph may split a passive
group; its new separators and endpoints update the retained native rows in place.
The synchronous paint surface returns only a finite tone and boldness receipt.

`view` renders passive MI and column labels with scoped styles. Native code
retains BFS, IDs, event targets, drag/hover suppression, expansion callbacks,
accessibility and measured geometry. Named single-row labels still use the
existing canvas component. Each policy step has a 2048-operation limit; a
sequence has N such bounded steps, rather than one shared 2048-operation budget.
No new ancestry depth limit is imposed. Pan and zoom do not rerun these policies.

`app.ui-canvas-metadata/metadata-view` has only `view`. It receives formatted
return/refinement summaries, ordered lambda parameter labels, and authorized
computed/declared effect membership. Ordinary graph functions build the return,
lambda and effect bodies, including drift/ghost choices and scoped styles.
Native siblings retain real edit/provenance buttons, accessible names and
permission checks. Secret values, function IDs and registries are absent from
both component inputs. Existing canvas and value/type component contracts stay
compatible. Both components can be copied and selected through Appearance.

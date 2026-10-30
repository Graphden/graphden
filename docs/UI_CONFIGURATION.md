# Ordinary editor configuration

The editor resolves a function named `editor` in the current authority's
personal namespace. If it is absent, the ordinary `app.ui-config/editor-defaults`
graph supplies shared entries. Principal, organization and branch come from
authenticated ingress, not submitted resolver arguments. The protected resolver
uses a bounded metadata lookup; it never dynamically executes a user UUID.

Personal configuration is an ordinary map from component identity to entry
descriptors. Each entry has `role` and `fn-id`. Normal references, list manifests
and the historical four-slot record can be adapted and merged with defaults.
Only actual authorized identity references carry identity provenance; a literal
or computed UUID does not become an entry reference by matching its shape.
Projection is a bounded supported subset, not arbitrary JVM evaluation. An
individual descriptor list must not repeat a component identity; the list-to-map
adapter rejects duplicates even though unrestricted JVM `into` would keep the
last value. Overrides belong in an explicit ordered merge instead.

**Personalize** creates small role children inheriting the currently selected
definitions and an ordinary editor root merging defaults, the prior root and
the new entries. It does not copy the shared component closure. Deeply referenced
helpers retain their original identities: inheritance does not redirect them.
An already owned, writable role opens for editing; a shared read-only custom
role is inherited into a new personal child. Group personalization leaves theme
selection unchanged. The explicit full-copy action remains available as an
advanced compatibility tool.

For example, the personal account-menu view has a free `theme` record input.
Bind `accent` and `canvas-background` on that child to capture personal tokens
through the shared dependency graph. This creates an own binding rather than
changing packaged helpers. Filled ancestor slots remain sealed; changing a
deeply referenced helper requires an exposed reference or an explicit local path.

Explorer compare presentation uses the same selected `initial`/`update`/`view`
roles as the toolbar. The host supplies already authorized diff labels and
bounded counts; the graph renders row badges, namespace aggregates, ghost
labels and lens buttons. Its lens update must return the exact selected button
receipt before the native lens changes. The existing toolbar `inputs` record
keeps its former shape, while the `view` graph reads an optional typed `diff`
argument through `get` with a default. An older personal view that lacks the
compare surface falls back only for compare presentation. The native editor
still owns branch and account context, ghost navigation, focus, review actions
and all diff data reads.

Inspector compare presentation uses two more `view` surfaces in the existing
`app.ui-inspector` roles: `compare-head` and `compare-entry`. Its unchanged
`inputs` record still accepts explicitly pinned personal descendants; a
genuinely optional typed `diff` argument carries only authorized display
fields. The graph chooses the change marker, branch-local/effects presentation,
entry label, field pair versus preview, and scoped styles. A legacy personal
view without these surfaces falls back for this section alone. The native
Inspector retains the exact anchor wrappers, comment buttons, threads and
draft controls across graph reloads and compare refreshes; graph nodes cannot
claim their IDs or actions. Review and suggestion previews keep the shared
native diff renderer's existing behavior.

The tutorial catalogue is another ordinary graph component:
`app.ui-tutorial-catalog/catalog-view` and `session-view`. Its catalogue view receives ordered lesson display
facts and a browser-local filter, then composes counts, adjacent chapter runs,
status badges, row and introduction bodies, resume copy and scoped styles.
The filter groups rows *after* matching, so an A/B/A source list can become
one A section when the B row is filtered out. The native tutorial keeps reading
history, requirement checks, URLs, focus, navigation and all Read/Unmark/Clear
controls. Personal styles are scoped to passive graph body hosts and cannot
cover those native controls; an older personal manifest can use the shared
catalogue view without replacing its other selected components.
The same component's `:query` callback renders step and end-card layout from
the active lesson's display facts. Native controls, copy chips, step predicates,
spotlight and cleanup retain their existing owners. A historical personal
catalogue with only `:view` uses the shipped `:query` callback.

The canvas has a separate view-only `app.ui-canvas/canvas-view` component.
It presents ordinary function names, secret classification marks, root
problem badges and passive branch-local/deep-free annotations. Native overlay
owners retain controls, edit guards, focus, expansion and geometry. Inputs are
finite authorized display facts, with no secret contents or source identities;
see the [canvas contract](../resources/packages/app/ui-preview/README.md#passive-canvas-presentation).

Value labels and type-chip/history bodies use the independent view-only
`app.ui-canvas-values/canvas-values-view` identity. Appearance can personalize
it without changing an existing custom canvas view. The value preview remains
the existing sanitized graph representation in a sibling native host; secret
paths and values never enter this presentation component. Canonical type resolution,
edit/expand anchors and accessibility remain native.
See the [value/type contract](../resources/packages/app/ui-preview/README.md#passive-value-and-type-presentation).

Inheritance groups and MI label bodies use
`app.ui-canvas-inheritance/inheritance-view`, with a pure `initial` entry for
checked grouping and finite paint receipts. Return/refinement, ordered lambda
and effect drift/ghost bodies use the view-only
`app.ui-canvas-metadata/metadata-view`. They have separate identities from the
older canvas components. Native rows keep expansion events, focus, edit controls
and accessible anchors when presentation changes. See the
[fn-card contract](../resources/packages/app/ui-preview/README.md#inheritance-and-function-metadata-presentation).

Expanded type structure and human/compact formatting use
`app.ui-canvas-type-structure/type-structure-view` with a pure `initial` entry.
Argument/source labels, seal explanations and flag-form copy use
`app.ui-canvas-bindings/bindings-view`. Its pure `bindings-initial` entry supplies
the λ and branch-local form's initial mode, checked and disabled state. Picker
values, type references, native validation, binder blocking and writes retain
their canonical authority. Runtime changes update retained passive children without
replacing focused native controls. See the
[expanded type/binding contract](../resources/packages/app/ui-preview/README.md#expanded-type-and-binding-presentation).

Replacing an editor root renames the old root privately in the current branch
and creates a new UUID named `editor`. Its parent relationships are retained.
Other branches and references to the old UUID keep their composition. A stored
legacy selection migrates by reference; an authored editor root wins. After
migration, legacy preference mutation requires the expected preference and
configuration revision; an unconditional stale caller cannot replace the root.

## Personal namespace authority

Tenancy's configured personal-namespace authority discovers the exact stable
principal namespace. Trusted account/org lifecycle provisions it after fresh
membership and platform checks inside the writer transaction. Read-only
resolution does not provision it or mint grants. A missing configured home
returns a diagnostic; shared defaults remain independently available.

Without tenancy, one operator profile uses the fixed `personal` namespace.
Explicit setup creates that namespace through ordinary decorated namespace
CRUD. This is one profile, not anonymous user isolation. Setup creates no
function and the namespace remains profile infrastructure after tutorial
cleanup. A truly anonymous tenancy guest uses the checked shipped artifact,
rather than a synthetic authorized principal.

## Source authorization and publication

Default and personal component plans use the same snapshot source exporter.
ACL precedes binding/value reads; current source checks cannot erase the
original checked secret classification. Manifest identity edges read metadata
only; exporting a selected component separately authorizes its actual body
closure. The browser host retains account operations, storage, search,
navigation and native DOM ownership.

Shared browser-source subtrees are published through ordinary organization
`view-impl` grants, with execute granted only to the required configuration
entry namespace. Namespace grants include descendants; published subtrees and
their dependencies must remain audited. Package origin alone grants no body
access, and no grant on the `app` or root namespace is added.

New-org creation seeds the frozen deployment publication policy atomically.
Existing organizations require explicit reviewed operator maintenance.
Authentication, reads, restart and ordinary redeploy do not restore a revoked
grant. Forward-only delta maintenance binds exact predecessor and current
artifacts, admits only newly introduced source namespaces, and checks the
frozen organization cohort and grant audit before the first write in one
writer transaction. A revoked old grant remains absent. Its artifact and
deployment label are bound to the reviewed plan. Never automatically replay
a completed or uncertain APPLY; a new republish requires a new operator
decision.

## Configuration HTTP operations

These authenticated POST routes live under `/api/ui/configuration/`.

| Operation | Contract |
|---|---|
| `plan` | Resolve and export one selected component under source ACL; return configuration revision and authoritative legacy preference expectation. |
| `prepare` | Migrate an actual legacy selection by reference, or explicitly set up the home with `{home:true}`. Home setup creates no editor function. |
| `preview` | Read-only scoped plan for `personalize` or `activate`; return all original created IDs, source witnesses and expected restore state before APPLY. |
| `apply` | Rebuild and compare that exact proposal under current policy/source/CAS; create only planned new identities atomically. |
| `reconcile` | Read-only confirmation of the original uncertain APPLY state; partial, edited or missing state remains uncertain. Never recreate reserved identities. |
| `cleanup-preview` | Read-only new deletion proposal using the original exact cohort and unchanged prior restore anchor; show current names and source stamps before consent. |
| `undo` | Atomically validate/delete the exact cohort and restore the previous root's versioned name; later changes cause conflict. |
| `reconcile-undo` | Read-only scoped resolved-absence and exact restored-source confirmation against a witness staged before DELETE; no repeated mutation or newly adopted stamp. |

Preview and creation receipts are immutable. A failed activation of a previously
created full bundle leaves that bundle available; a lost APPLY response is reconciled against the original scoped
witness. The client does not refresh expectations to turn an unrelated edit
into permission to delete it. Intentional tutorial edits use a separate visible
cleanup review. LIFO cleanup confirms one activation at a time; another edit
after review requires a new review. Profile infrastructure and retained prior
identities never enter the new-ID deletion receipt.

An atomic function cohort contains at most 4096 functions. The same static
bound applies to creation preflight, full-copy browser manifests, staged exact
receipts and cleanup/undo; a larger proposal is refused before writing.
Creation writes bounded database batches inside one writer transaction, so a
late failed batch rolls back the whole cohort. The selective component-copy
receipt remains limited to 512 functions. Source-export and browser-plan
limits, account quotas, authorization and the 30-second write shield remain
separate constraints. If a write times out or its response is lost, reconcile
the original staged receipt: a timeout alone does not prove rollback.

## Picker root callback

The handler-selected editor root exposes `:fn-picker {:view callback}` as an
ordinary graph value. Its unary callback receives `{:model ...}` and returns
`{:tree ... :styles ...}`. The picker host owns search, candidate identities,
compatibility, events, focus and keyboard navigation. The passive renderer
validates Hiccup and scoped styles; callbacks cannot supply DOM handlers.

For the shipped default root only, the temporary read-only compatibility
adapter projects the current user's existing personal picker view into that
unary envelope. The selected source closure passes the root request's current
source authorization and snapshot checks. Missing or revoked selected source
refuses the root export; it never selects an older plan. A custom root's
ordinary refs are authoritative. Remove the compatibility projection after the
deterministic personal-root migration has replaced its readers and records.
For C6, `scripts/ui_legacy_audit.sql` inventories only counts of old
`components` preferences and non-shipped `editor`/`editor-root` identities.
Run it against each installation with
`psql -X -v ON_ERROR_STOP=1 -f scripts/ui_legacy_audit.sql` using a role that
can see every tenant. It sets
`row_security=off` so a role filtered by RLS fails instead of reporting a false
zero. These are conservative candidates, not proof of an active selection:
inspect any nonzero cohort through the authenticated read-only root migration
preview, then apply each exact proposal under current ACL/CAS. Keep the
compatibility adapter until every installation has zero candidates and the
old read/write paths are confirmed unused.
Root replacement immediately closes a mounted picker and retires its render
receipt; old asynchronous loads cannot reactivate it.
Completing the same root's load preserves a native picker opened during loading.
That popup stays native and carries no graph render receipt until it is closed;
the next root replacement still retires it immediately.

## Tutorial catalogue root callback

The handler-selected editor root exposes `:tutorial-catalog {:view callback :query callback}`
as an ordinary graph value. Each unary callback receives `{:inputs ...}`;
`:view` returns the catalogue view model, and `:query` returns passive step or
end-card nodes.
The native host keeps history, requirements, navigation, focus and lesson
actions. It validates row identities and passive Hiccup before painting, and
accepts the root render receipt only after the complete host paint succeeds.

The shipped default root's temporary read-only compatibility adapter projects
an existing personal catalogue view through the checked source closure. A
missing, revoked or malformed selected root callback closes the catalogue;
it cannot reactivate a built-in view. Root replacement retires pending and
open catalogue work, styles and receipts. The same root's initial load may
complete without cancelling the opening catalogue.

## Execution, feedback and keyboard roots

The editor root exposes `:execution-status`, `:execution-path` and `:feedback`
as ordinary view callbacks. The shipped browser plans keep status and feedback
presentation usable when the instance backend is unavailable. Status receives
only bounded display strings; native code still polls, cancels and handles
HTTP. The execution path receives value-free trace metrics and renders per-fn
badges plus the summary panel. Captured values stay in the native, client-held
popover and secret frames cannot be unhidden by a personal graph. Feedback
renders the form from the selected root or the shipped plan; the host keeps
focus, consent, error collection and direct intake delivery. Failure counts
are offered as an attachment only for an authenticated session.

Keyboard settings use the `:keymap` view callback. The graph receives the live
shortcut entries and personal source summary, renders the table, and marks
duplicate key sequences. The host owns the keydown capture listener, IME
boundary, shortcut dispatch, marketplace actions and owner-scoped preference
write. A shipped browser plan keeps the settings panel usable while a personal
root is unavailable.

Appearance settings use the `:appearance` view callback. The graph receives
only validated display values for the active theme, saved versions, current
editor draft and token groups. The browser keeps the editable draft, validates
colour and font values, applies CSS, writes the owner-scoped preference and
evaluates the separately selected ordinary theme graph. Typing in a font or
colour control does not replace that focused control. The shipped browser plan
is the fallback when the selected component cannot be used.

The organization switcher uses the `:org-switcher` view callback for its
membership list. The graph receives names, owner flags, plan labels and the
current organization; it receives no session cookie, token or credential.
The host verifies every rendered switch target against the fetched membership
set and owns navigation, creation, focus and account-change invalidation.

The `:authoring` view supplies the namespace picker choices, the create menu,
the function edit namespace chooser, the shared create/rename input row and
the type form shell from one ordinary graph. The
host projects only authorized namespace IDs and paths, verifies every returned
choice against that set, and retains focus, IME, in-progress values, geometry,
validation and writes. Type-specific constraint editors remain native controls
inside the graph-provided shell; switching a kind preserves the shared draft.

The Settings sign-in methods section uses the `:account-methods` view callback.
The graph receives provider names, display email, verification state, unlink
availability and configured OAuth providers, with no password, TOTP secret,
session cookie or API token. The host fetches session-scoped data, verifies
every rendered unlink target and OAuth link, owns all account actions and
discards responses after an account change.

The Settings 2FA status and API token list use the `:account-security` view
callback. The graph receives only enabled state and token display metadata;
the host validates action targets and owns enrollment, one-time secrets,
revocation, and session-change invalidation.

The `:publish-form` view supplies the namespace, theme and keymap publish
fields from one graph. Inputs contain only the kind, tenancy availability and
namespace display path. The host checks the exact control set and owns the
publish endpoint, authorization, payload, submission, dialog lifetime and focus.

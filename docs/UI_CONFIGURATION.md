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

The tutorial catalogue is another ordinary view-only component:
`app.ui-tutorial-catalog/catalog-view`. It receives ordered lesson display
facts and a browser-local filter, then composes counts, adjacent chapter runs,
status badges, row and introduction bodies, resume copy and scoped styles.
The filter groups rows *after* matching, so an A/B/A source list can become
one A section when the B row is filtered out. The native tutorial keeps reading
history, requirement checks, URLs, focus, navigation and all Read/Unmark/Clear
controls. Personal styles are scoped to passive graph body hosts and cannot
cover those native controls; an older personal manifest can use the shared
catalogue view without replacing its other selected components.

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

An atomic function cohort contains at most 2048 functions. The same static
bound applies to creation preflight, full-copy browser manifests, staged exact
receipts and cleanup/undo; a larger proposal is refused before writing.
Creation writes bounded database batches inside one writer transaction, so a
late failed batch rolls back the whole cohort. The selective component-copy
receipt remains limited to 512 functions. Source-export and browser-plan
limits, account quotas, authorization and the 30-second write shield remain
separate constraints. If a write times out or its response is lost, reconcile
the original staged receipt: a timeout alone does not prove rollback.

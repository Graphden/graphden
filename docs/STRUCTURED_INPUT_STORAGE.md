# Structured input storage: offline rehearsal

This is the additive storage stage of the structured input transition. The
production loader, checker, executor and editor still use v1 slots and bindings.
There is no dual writer and no automatic live-data conversion. New input binding
entities are excluded from generic public reads and writes. Activating a function's
input contract requires the internal offline capability; no request parameter
grants that capability. The tenancy adapter must also deny these entities before
this schema reaches a tenant-facing deployment.

## Identity and history

`fn.input-type-fn-id` is nullable and versioned. Its target is a record type using
the existing `fn`, immutable `slot`, and ordered `fn-slot` identities. Descriptor
memberships use the existing membership version mechanism. A missing input type
means v1 authority; it does not mean a new empty input type.

`input-binding` and `input-binding-version` are separate additive entities. The
identity carries `org-id`; its mirrored data carries the owner `fn-id`,
`scope-path`, `target-path`, expression and field metadata. Both paths are JSONB
vectors of UUIDs, normalized to UUID values after reading. Names are presentation
labels and never identify fields or reference uses.

- `target-path` contains declared slot IDs; `[]` addresses the whole input.
- `scope-path` contains reference binding IDs and distinguishes separate uses of
  one shared definition. It never clones the target definition or its bindings.
- `expression-kind` is `value`, `ref`, `projection`, or null for metadata only.
  A value uses `value-present=true`, including an explicit null literal. Refs
  carry `ref-fn-id`; projections carry source scope and field paths.
- `required`, `terminal`, `description`, and `type-override-fn-id` retain field
  metadata independently of whether the binding occupies the field.

A value, ref or projection occupies its entire addressed subtree. Ancestor and
descendant writes conflict; sibling writes compose. Reusing one shared binding
origin deduplicates; another binding ID at the same path conflicts, even when its
value is equal. Metadata alone does not occupy a field. Terminal inherited
metadata prevents descendant overrides.

Offline singular and bulk writes validate the resulting branch view under one
conservative advisory lock for all structured bindings. Merge validation occurs
inside the merge transaction and rolls back conflicting merges. Replacement and
removal through `graphden.input.storage` require the current version ID under the
same lock. Tombstones, branch resolution, merge cutoffs and branch deletion reuse
the versioning machinery. JSONB path references retain slot and binding identities
during reclamation. Legacy function cleanup conservatively retains owners and
targets referenced by v2 rows until those rows are explicitly removed.

This validator checks structural scope/path validity and occupancy among the
resolved supplied rows. It does **not** recursively instantiate a referenced
function's occupied or missing inputs into every use scope. It also does not
perform expected-type/HOF classification, evaluate refs, or compute the effective
remaining input. Field traversal uses declared record memberships; aliases that
need expansion must be materialized by the type layer before this validator.
Those responsibilities belong to the following checker and
executor stages; public v2 writers remain disabled until they exist.

## Artifact workflow

Use a stopped, isolated PostgreSQL copy. Initialize it through the ordinary schema
migration mechanism first; the stable schema UUIDs support both fresh creation
and additive upgrade. Keep its raw storage handle rather than a versioning or
tenancy wrapper. This API performs the conversion without starting the executor:

```clojure
(require '[graphden.input.offline :as offline])

;; raw-storage is the initialized storage of the isolated copy.
(def rehearsal (offline/artifact (offline/snapshot raw-storage)))
(get-in rehearsal [:plan :exceptions])
(spit "/private/rehearsal.edn" (offline/encode rehearsal))

(def checked (offline/decode (slurp "/private/rehearsal.edn")))
(offline/apply! raw-storage checked)   ; {:status :applied}
(offline/apply! raw-storage checked)   ; {:status :already-applied}
(offline/restore! raw-storage checked) ; {:status :restored}
```

Artifacts contain literal graph data and raw history; store them as private
database backups. No repository fixture should contain an operator's snapshot.
The EDN reader accepts exactly one form, rejects unknown tags and reader
evaluation, and preserves nanosecond timestamps with explicit tags.

The plan contains `:changes`, `:additions`, and `:mapping`, with source and target
fingerprints. It preserves raw branches, merge rows, tombstones, change stamps and
binding version IDs. Descriptor and membership IDs are deterministic; inherited
memberships retain their source versions. Binding identities stay shared.
`migration/binding-origin` accepts `[owner-fn-id use-path]` and a field path so
source adapters can reuse the same origins without global name lookup.

Apply regenerates the plan, verifies its source and target checksums, and compares
the current raw graph snapshot under the ordinary semantic writer lock. Every
branch's resulting structural view is checked before commit. Any failure rolls
back the transaction. Exact target retries are no-ops. Restore requires the exact
target fingerprint and refuses to erase later edits; it restores the captured
v1 rows and removes only the added rows. Additive SQL columns and tables remain.
Snapshot capture itself assumes the database copy is stopped; apply detects a
different snapshot rather than treating it as approval to continue.

## Explicitly unsupported conversions

Any exception blocks the entire artifact. The inventory remains readable and
deterministic so a subsequent semantic adapter can resolve it. Current reports
cover list append/closed/item state requiring builders, rename projections,
cross-reference inputs, multiple membership origins for one slot, historical
membership moves, historical executable/type role changes, binding trait policy,
owner mismatch, inconsistent legacy expressions, and already structured data. Invalid branch structure also fails
the transactional validation; it is never silently discarded.

Parent sets are unversioned in the v1 storage model. The conversion preserves that
model's raw identity and version information, including historical membership
tombstones; it does not invent historical parent sets that v1 never stored.

`migration/legacy-binding` is a read projection for representable top-level v2
rows only. Nested scopes/paths and projection expressions fail explicitly. It is
not a second writer or a complete v2 runtime reader. The legacy history UI and
interactive editor tutorials remain on v1 until their later transition stages.

## Verification

`graphden.input.storage-test` uses PostgreSQL and the ordinary schema/versioning
decorators. It covers fresh and repeated upgrade, inherited and concurrent prefix
conflicts, atomic merges, tombstones and branch-only identity cleanup, shared use
scopes, current-version edits, history-preserving artifact apply/restore, and
stale snapshot rejection. Pure tests cover schema mirrors, offline admission,
origin stability, safe EDN, unsupported reports and fingerprint fidelity. The
developer tour links directly to these implementation boundaries.

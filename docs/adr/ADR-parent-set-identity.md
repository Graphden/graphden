# ADR: the parent-set is structural identity, not versioned state

**Status: decided (2026-07-23). Option (b) — identity + gate — is the
permanent semantic; option (a) — versioning `:parent-ids` — is
REJECTED, not deferred.**

## Context

`:parent-ids` is a `:ref-many` junction on the fn IDENTITY row. It is
NOT versioned: a re-parent commits once and is visible to every branch
instantly, while the re-parent cascade's binding migration writes
`*-version` rows on the request branch only. Ungated, a re-parent from
a feature branch left every other branch resolving NEW parents over
OLD bindings — bindings whose slots fell out of the new inheritance
closure silently unbind, changing prod behaviour with no prod-visible
edit (audit item 8, 2026-07).

Two coherent designs existed:

- **(a) Version the parent-set** — an ordered `parent-ids` array on
  `fn_version`, branch-resolved like bindings, with version-aware
  reverse-parent queries.
- **(b) Parent-set = structural identity** — the junction stays
  identity-level; a write-time gate makes the desync impossible
  instead of representable.

## Decision: (b)

The gate already shipped (`validation/reparent-cross-branch-rej`):
a parent-set change is rejected unless the request branch is the ROOT
branch AND no other branch holds `:fn-version` / `:binding-version` /
`:fn-slot-version` rows for the fn (the reject names the diverging
branches — merge or delete them first). Parent-preserving updates and
non-versioned storages are unaffected. This ADR fixes that gate as the
*final* semantic rather than a stopgap.

## Why (a) is rejected

1. **The parent-set defines the slot closure, and slots are identity.**
   A composed fn's exposed slots are computed from the `parent-ids`
   BFS closure over IDENTITY rows; `fn-slot` junctions, MI-collision
   checks, free-arg propagation and the compile pipeline's inheritance
   walk all consume that closure without branch context. Making
   `parent-ids` branch-varying makes the *closure itself*
   branch-varying — every one of those consumers would need a branch
   parameter, and a `binding`'s validity (its `(fn-id, slot-id)` pair)
   would become a per-branch fact. That is not an array column; it is
   a re-architecture of the identity model that
   [ADR-identity-model.md](ADR-identity-model.md) deliberately keeps
   cross-branch.
2. **Hot-path cost.** The closure walk sits on the compile/execute hot
   path. Branch-resolving it means `DISTINCT ON` version resolution
   inside every closure computation — the exact merge-on-read shape
   that produced a 20-minute checker hang before snapshot-at-bind
   (see the perf ledger). The identity junction is a plain indexed
   read.
3. **Minimal entities (principle #2).** (a) adds a versioned mirror of
   a junction, reverse-parent version queries, and merge semantics for
   parent conflicts. (b) adds one write-time predicate.
4. **The blocked case IS the corrupting case.** The gate only rejects
   re-parents that would desync branches. Re-parenting a fn nobody has
   branch-forked is untouched; converge-then-reparent is the honest
   workflow, and the reject message names the branches to converge.

## What "re-parent" is, semantically

Changing a fn's parents changes which slots it exposes — its
*structural interface*. That is closer to defining a different fn than
to editing this one's state; treating it as an identity-level,
all-branches operation (like rename-in-place or namespace-move)
matches how the editor's re-parent cascade already behaves: a heavy,
explicit, migration-running action, not a casual per-branch edit.

## Revisit trigger

Only if branch-local *inheritance experiments* become a real product
requirement (a branch that redefines what a fn inherits, not just its
bindings). The cheaper door is copy-on-write: fork the fn into the
branch (new identity, edited parent-set) and merge by ref-rewrite —
the machinery the package registry's fork path already uses — leaving
identity semantics untouched.

## Pointers

- Gate: `src/graphden/crud/validation.clj`
  (`reparent-cross-branch-rej`), tests in
  `test/graphden/crud/validation_test.clj`.
- Behaviour doc: [VERSIONING.md § Parent-set edits](../VERSIONING.md).
- Identity model: [ADR-identity-model.md](ADR-identity-model.md).

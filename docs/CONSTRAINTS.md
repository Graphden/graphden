# Graph Constraints Reference

This document describes the integrity constraints enforced by the
graphden storage layer. The slot/fn-slot/binding model handles most
shape correctness through schema-level uniqueness (`UNIQUE` on the
junction tables); the only protocol-level constraint that needs
explicit code is dependency-cycle detection.

## Entities

- **fn** — function or type-row. Inheritance via `parent-ids`
  (many-to-many junction).
- **slot** — atomic `(name, type-fn-id)` pair, immutable post-create.
- **fn-slot** — junction: which slots a fn exposes, with position.
- **binding** — per-`(fn, slot)` customization (value, ref, rename,
  type override, terminal seal, list flags).
- **binding-list-item** — sequence content under a list-typed
  binding, ordered by position.

## Protocol

```clojure
(defprotocol GraphConstraints
  (validate-no-dependency-cycle! [this owner-fn-id ref-fn-id]))
```

Storage implementations enforce this by walking the
`fn → binding.ref-fn-id → binding-list-item.ref-fn-id` graph.

## Constraints

### 1. No Dependency Cycle

**Rule:** The dependency graph (any `ref-fn-id`, in either bindings
or list-items) cannot form a cycle.

**Rationale:** Cycles cause infinite recursion at compile / execute
time.

**Example:**

```text
fn add-numbers
  binding {slot s-other, ref-fn-id multiply-numbers}

fn multiply-numbers
  binding {slot s-other, ref-fn-id add-numbers}    ✗ dependency-cycle
```

**Self-reference at the per-binding layer:** `validate-no-dependency-
cycle-impl` carves out `owner-fn-id == ref-fn-id` — the bare self-ref
write doesn't throw at this check. HOWEVER the higher-level
`topological-sort` (`executor/composition/deps.clj`) runs over the
whole fn-def set at sync time and rejects any cycle, including bare
self-refs. End result: **graph-level recursion is structurally
impossible today** — neither self-ref nor mutual-ref nor any
parent+ref combination passes both checks. See
`docs/ARCHITECTURE.md § Part 3 — Recursion and Cycles` for the
empirical demonstration, and [RECURSION.md](RECURSION.md) for the
`:fix`-based recursion path (shipped) that adds recursion without a
graph cycle.

**Error:** `:constraint-violation/dependency-cycle`

### 2. Schema-level uniqueness

These hold by virtue of `UNIQUE` constraints in the schema, not the
protocol:

| Entity | Unique key | Note |
|---|---|---|
| `fn` | `(anonymous-hash)` | anonymous fn dedup |
| `fn-slot` | `(fn-id, slot-id)` | a slot is exposed at most once per fn |
| `binding` | `(fn-id, slot-id)` | one binding per `(fn, slot)` |

Two former base-table `UNIQUE` keys were retired because their
invariant is a property of the per-branch RESOLVED VIEW, not of the
identity table (soft-deleted identity rows persist by design and would
occupy the key forever; cross-branch divergence must not be blocked):

| Entity | Retired key | Now enforced by |
|---|---|---|
| `fn` | `(namespace-id, name)` | `check-fn-name-collision!` in `VersionedStorage` — live-view check per branch, advisory-lock-serialized. Error: `:constraint-violation/fn-name-collision` |
| `binding-list-item` | `(binding-id, position)` | `check-list-item-position-collision!` in `VersionedStorage`. Error: `:constraint-violation/position-collision` |

`slot.name` and `slot.type-fn-id` are NOT individually unique — two
slots with the same name and type are distinct identities (sharing
is opt-in via fn-slot pointing at the same slot-id).

## Implementation

| Storage | Location |
|---|---|
| all backends | `src/graphden/storage/protocol/constraints.clj` — `validate-no-dependency-cycle-impl` walks `read-entities` results (backend-agnostic in-memory `StorageCRUD` traversal, NOT a SQL CTE); postgres delegates to it |

The `GraphConstraints` extension is wired generically through
`graphden.storage.protocol.generic-constraints`.

## Testing

`src/graphden/storage/protocol/contract_tests.clj` exercises the
protocol against any storage that registers `GraphConstraints`.
The suite covers cycle detection (allow nil ref, reject self,
allow distinct fn) plus concurrent CRUD.

```bash
bb test  # runs unit tests
```

Inheritance regression e2e tests live in
`tools/browser-test/edit-inheritance-regression.test.js`:

- (a) parent scalar binding hides child placeholder
- (b) inherited sequence visible via expansion
- (c) child override masks parent
- (d) child list-append extends parent items

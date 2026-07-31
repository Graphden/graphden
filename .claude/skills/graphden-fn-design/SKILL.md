---
name: graphden-fn-design
description: Design rules for creating Graphden fn-defs — when to give a fn an explicit public name vs make it private (`_`-prefix), when to use multiple inheritance vs a single parent, when to extract a subgraph into its own private namespace, and how the auto-naming / inline-display rules work. Use when writing or restructuring `fns.edn` entries, splitting a large fn-def, deciding whether to introduce a new helper, or auditing existing definitions for naming hygiene. Triggers on phrases like "how to name it", "anonymous or named", "make it shared", "extract a helper", "where to put an fn", "MI or a single parent", "reuse", "private function".
---

# graphden-fn-design — fn-def declaration rules

Purpose of the skill: when adding / restructuring fn-defs, know precisely
**whether an explicit name is needed**, **whether to put it in a private
namespace**, **whether to use MI or not**, and what display consequences
follow from the choice. This system is the analog of Clojure's `defn` /
`defn-` / `let` / `letfn`, translated onto graph-storage.

## Basic analogy with Clojure

```clojure
;; Clojure                                  ;; Graphden EDN
(defn web-server [port handler] …)          {:name :web-server
                                              :parent :http-server
                                              :args {:port 8080
                                                     :handler :_app-ring-response}}

(defn- _app-ring-response [req] …)          {:name :_app-ring-response
                                              :parent :router
                                              :args {…}}

(defn make-server [p h]                     ;; inline composite type:
  {:port p :handler h})                     ;; {:input {:port :int :handler :fn}}
```

| Concept | Clojure | Graphden |
|---|---|---|
| Public reusable fn | `defn` | `:name :public-name` (no `_`) |
| Private helper | `defn-` | `:name :_private-name` (`_`-prefix) |
| Inline value | `(let [x …] …)` | `{:value x}` binding |
| Inline composite type | inline map literal | `:input {:k T}` / `:type {:k T}` (anonymous-hash deduped) |

## 1. Public name vs `_`-private — decision at the moment of writing EDN

**Add the `_`-prefix if all three conditions hold:**

1. **The fn-def is not part of an API/contract.** It is an implementation
   detail of some larger public fn — not a standalone unit.
2. **No reuse is planned.** One use-site today and one tomorrow.
3. **The name carries no meaning on its own.** If the name "doesn't sound
   right" without the context of the parent fn-def (`_app-ring-response`
   only makes sense next to `web-server`), that is a marker for `_`.

**A name without a prefix — if at least one of:**

- the fn-def is reused (≥ 2 use-sites today or planned for tomorrow).
- the fn-def is a recognizable entity of the domain vocabulary
  (`web-server`, `http-server`, `json-ok-response`).
- you want to reference it from outside the package (when exporting).

**The `_`-prefix is a UI marker, not a separate entity.** Under the hood it
is an ordinary fn with an ordinary name. UI:

- hides the name on the graph (shows it inline in the parent's body on expand);
- on hover, the `i`-tooltip shows the auto-name + a rename option;
- in the sidebar, `_`-fns are hidden under a fold / in a private-namespace.

## 2. Auto-name for a private fn — format

When the author did NOT specify a name explicitly (anonymous fn-def via the
UI "extract into helper" or a legacy-EDN import without a name), we generate
it stably:

```
auto-name = "_" + <parent-fn-name> + "-" + <slot-name>
namespace = <parent-fn-namespace>
```

Example: on "extract into helper" of the `:handler` binding on fn `web-server`
(namespace `app.server`) → `:_web-server-handler` in namespace
`app.server`.

**Stability rule:** on a re-sync of the same EDN, the auto-name must
match → a deterministic UUID via `(parent-name, slot-name)`. A name
conflict (the same auto-name is already taken) → a suffix `-2`,
`-3`, …

This algorithm is **per-use-site**: two different places that extracted the
same piece of logic will get **different** fn-rows. There is no dedup — this
is a deliberate choice for private fn-defs (they have reference semantics,
not value semantics).

## 3. Inline composite types — a different mechanism (shape-dedup)

`:input {:k T}` / `:type {:k T}` declare an **anonymous composite type**
(record-shape). Here it is the opposite: the same shape in two places
**shares** one fn-row via the `anonymous-hash` UNIQUE constraint.

```edn
{:name :greet-handler-A
 :input {:user-name :text :greeting :text}}   ; ← shape-hash X

{:name :greet-handler-B
 :input {:user-name :text :greeting :text}}   ; ← the same shape-hash X → the same fn-row
```

The auto-name for such rows: `_anon-<shape-hash[0..7]>` in the namespace of
the fn-def that declared them (if there are two, the lexicographically first
one is chosen — sync is deterministic).

**When to write an inline composite vs a `_`-private fn:**

| You want | Use |
|---|---|
| Describe the shape of a record (record / type) | inline composite (`:input` / `:type`) |
| Describe behavior (a computation graph) | `_`-private fn-def with `:parent` |

A composite is a description of a value. A private fn is a description of a
computation. Do not conflate them.

## 4. Named constants — `:parent :const`

To give a NAME to a concrete value (security headers, default config,
fallback responses, etc.), write a fn-def that extends `:const`:

```edn
{:name :default-security-headers
 :parent :const
 :return-type :security-headers-shape
 :args {:value {:X-Content-Type-Options "nosniff"
                :X-Frame-Options        "DENY"
                ...}}}
```

`:const :args {:value a} :return-type a` is an identity base-fn,
literally "return your own value". Extending it with a literal in `:value`
produces an fn-graph that, on evaluation, returns the literal.

**This is the only form for named values.** In the schema there is no sixth
"named-value" entity kind (see `src/graphden/schema/graph/
schema.clj` — an fn-row is base / composed / record-type / refinement
/ list-type / primitive, that's all). Any attempt to avoid the `:const`-wrapper
requires either adding an entity kind (#2 violation — minimal entities),
or parser sugar that hides this structure (#3 violation — explicit
over implicit).

The two-story card in the editor (`my-constant / const`) is an
honest depiction: "this is a composed fn-def with one parent `:const`,
whose return equals the given `:value`". It is not a redundant layer, it is
the **minimal necessary ceremony** for a value to be able to be NAMED and
REFERENCED via `{:ref :name}` / bare keyword refs.

When to write `:return-type T` on a named constant: always. Without an
explicit return-type, `:const`'s rule will return the type inferred by
classify-literal (`:jsonb` for maps/vectors, `:int` for numbers, etc.) — which
is usually too wide a type. Pinning to a concrete record/refinement
shows the reader exactly what this constant represents.

## 5. Multiple inheritance — when it is justified

`:parents [:a :b]` (instead of `:parent :a`) is a **mix-in**: the fn gets
the slots of both parents. Used in three situations:

1. **Categorizing behavior via separate concerns.** `:assoc-handler
   :parents [:assoc-fn :assoc-empty]` — `:assoc-fn` brings the type
   policy (the `:value` slot has type `:fn`), `:assoc-empty` brings the
   starting content (an empty record). Each parent is one
   orthogonal characteristic.
2. **Composition in the style of traits.** `:authed-route :parents [:get-route
   :auth-required]` — `:get-route` gives the structure (`:path`, `:handler`),
   `:auth-required` mixes in the middleware stack.
3. **Refinement without copying.** When you already have two fns `:a` and `:b`
   whose slot-sets are useful to combine without rewriting.

**MI is contraindicated when:**

- The parents' slots conflict on `(name, type)` — sync will error.
  You can check this via `bb test` integration tests or via
  `composition.validation`.
- One of the parents is itself composed (parents-of-parents) and its slots
  overlap with the other — a diamond. Technically it works, but readability
  drops. Better to extract a common grandparent as an explicit `_`-private
  foundation.
- "I want the fn to do both X and Y" — that is a bad reason. MI describes
  **shape**, not behavior composition. Behavior composition = ordinary
  ref-bindings inside `:args`.

**Heuristic:** if after reading `:parents [a b]` it is unclear which
slots come from where, rewrite it as single-parent + `_`-helper.

## 6. Grouping into a namespace — when to extract a separate one

Every fn lives in a namespace (taken from the `:namespace` field of the fns.edn file).
Create a new namespace when:

1. **≥ 5 fn-defs are united by a common theme** (`web.html`, `core.arithmetic`,
   `app.editor`). Fewer — stuff them into an existing one.
2. **All fns inside are private (`_`-prefix or planned-private).**
   Then the namespace becomes a "folder for internal implementation" —
   the editor sidebar can collapse it by default.
3. **A natural uses-from-elsewhere boundary.** That is, the pattern is:
   other packages import 2-3 public fns, while the private ones stay
   local.

**Do not create a namespace for the sake of:**

- A single fn (even a complex one) — it just lives in an existing one.
- A technical split by kind (`utils`, `helpers`) — bikeshedding without
  semantic load.

**A `:private?` flag on the namespace** (if introduced) hints to the editor
collapse-by-default + new fns in it auto-get the `_`-prefix.
It is equivalent to every fn in it having a `_`. Use it for
large deeply-private modules (like `app.server.internal`).

## 7. Decomposition — when to "split" a large fn

In Clojure `(defn big-fn [x] (let [a (...) b (...) c (...)] (...)))` ←
if there are many lets, we extract into `defn-`. The same criterion here:

**Split a fn into private helpers when:**

- The fn body has ≥ 4-5 ref-bindings to different intermediate computations.
- On expand in the editor, so many nodes appear that it becomes
  unreadable.
- Some layer (preprocessing, validation, post-format) is semantically
  distinct.

**Do not split when:**

- 1-2 computation steps → inline.
- The same shape repeats → use an inline composite type
  instead of a private fn (more precise semantically, gets deduplicated).
- Decomposition has no natural boundary — an attempt purely to
  "shrink the body" would create poorly named `_step1`, `_step2`.

## 8. Display rules — what the user will see

| Fn kind | Sidebar | On the graph on expand | `i`-tooltip |
|---|---|---|---|
| Public (`name` without `_`) | visible | separate node | name + namespace + description |
| Private (`_name`) **with one use-site** | hidden / in private ns | **inline in the parent's body** | auto/explicit-name + rename-affordance |
| Private (`_name`) **with ≥ 2 use-sites** | hidden / in private ns | **separate node** (like public, but the name dimmed / no `_` in the label) | same |
| Anonymous composite (inline `:input`) | not visible | not shown separately (the structure is part of the parent's slot list) | `_anon-<hash>` in the parent's `i` |

UI rules:

1. **`_`-prefix or `:private? true`** is a marker for "not an API surface" —
   it determines sidebar visibility and hidden-prefix-in-the-label.
2. **Inline vs a separate node** is determined SEPARATELY, by
   the number of use-sites: one = inline (the parent's body), two or
   more = a separate node (this is already a shared subroutine, drawing N
   copies of the body is pointless).
3. Auto-inline only for **named-by-author** private fns (i.e. actually
   written in EDN with the `_`-prefix). Auto-named-by-shape (inline composite
   types via `anonymous-hash`) — the display logic is built into the parent
   (shown through the fn's own `:input`/`:type` slots).

This way the concept "open the graph = see the body" works with natural
intuition: opening a `_`-fn with one use-site — it gets inlined,
no fan-out happens. A `_`-fn with reuse gets its own
node, but the name is quieter (no `_`-prefix in the label, dim).

If a `_`-fn suddenly becomes reused (1 → 2 use-sites) —
the display automatically switches to "separate node" on the next
render. The decision is per-render, not per-decl.

## 9. Quick decision flowchart

```
Want to add an fn-def?
  │
  ├─ Is it a data shape (record / value type)?
  │    └→ inline composite in the parent's `:input` / `:type`.
  │
  ├─ Is it behavior that is reused?
  │    └→ public name (no `_`), in the semantically correct namespace.
  │
  ├─ Is it behavior, one use-site, name not standalone?
  │    └→ `_<parent>-<slot>` private name, in the parent's namespace.
  │
  └─ Is it a shape used in N places with an identical structure?
       └→ inline composite — anonymous-hash will collapse it into one row.

Multiple parents?
  ├─ Each parent brings an ORTHOGONAL slot-set → MI is fine.
  └─ Otherwise → single parent + `_`-helper for the common foundation.

Planning to decompose a large fn?
  ├─ Natural layers (validation / format / etc) → `_`-helpers.
  ├─ The same piece repeats → public, not private.
  └─ Just want to shrink the body without a logical cut → do NOT split.
```

## 10. Anti-patterns

- **`_`-prefix on a public-API fn.** If someone references it from another
  package — it is no longer private.
- **A public name without reuse.** "Let's name it just in case" — it needlessly
  clutters the namespace + sidebar. Make it `_`-private.
- **MI for the sake of a feature-mix of behavior.** MI is about slot-shape, not
  about "I need to glue logic A and B". Logic is glued via ref-bindings
  in `:args`.
- **Namespace per fn.** An `app.server.web-server` ns with one fn-def
  inside is overhead. Put it in `app.server`.
- **Auto-name by hand.** Do not write `_anon-3f2a` yourself — it is an internal
  name, it is generated. If you want to name it explicitly — name it
  in a human way.
- **Fake polymorphism during specialization.** If you inherit a
  generic primitive (`:invoke`, `:if`, `:map`, …) and in your fn-def the
  type-variable from the parent stops being genuinely polymorphic —
  close it ON THIS fn-def, not downstream. The slot must honestly
  declare its contract; the user should not have to go to the callers
  to read their `:type`-pins to understand what `:func` expects.

  A type-var stays free only when:
  - **(a)** more than 1 callsite genuinely uses it with different types
    (legit polymorphism: `:invoke.return = b`, `:if.return = a`,
    `:map.return = [:list b]`), OR
  - **(b)** it is passthrough — `:return-type` matches one of the
    bound slot-vars and the fn's role is literally "return what you were given"
    (`:const`, `:identity`, `:constantly`).

  Otherwise — **(c) fake polymorphism**, close it. Example (historical):
  `:router-result :parent :invoke` was at first `[:fn {:arg :ring-
  request-shape} b] :return-type b`, while the real contract "return =
  `:ring-response-shape`" hung on the downstream pin
  `:router-ring-response.m {:type :ring-response-shape}`. The chip on the
  `:func` slot showed `→'b`, the contract was hidden. We closed it:
  `:func :type [:fn {:arg :ring-request-shape} :ring-response-shape]
  :return-type :ring-response-shape`. The chip now reads
  `(arg:ring-request-shape)→ring-response-shape` without trips to the
  use-site.

  If YOU do need a generic version with a different return-shape — extend
  the primitive (`:invoke`) directly, not the narrowed child.

## 11. Links to other places

- Implementation of the `_`-prefix UI rules: `editor-overlay-*.js`,
  `editor-sidebar.js`.
- Loading fn-defs from EDN: `src/graphden/packages/loader.clj`.
- The shape-dedup parser for inline composites: `src/graphden/packages/records/ids.clj`
  (`shape-hash`, `anonymous-fn-id`; `records.clj` only re-exports `anonymous-fn-id`).
- Validate-no-duplicate-names + naming rules: `composition.validation`.
- Live-checking an fn-def in the REPL: see the `graphden-repl` skill.

## 12. What is planned (not implemented right now)

- **Export of a package as EDN.** A planned feature: dump user
  fn-defs as a portable package. That is why **all** fns must have
  stable names (including ones that are currently anonymous via
  shape-dedup) — otherwise there would be no way to reconstruct them when
  importing onto another instance.
  Auto-naming makes this feature feasible.
- **A `:private?` flag** on a namespace + on individual fn-defs — a UI hint
  for the display rules. The equivalent of the `_`-prefix convention. When we
  introduce it, we will pick one of the two as canonical.
- **The "extract into helper"** UI button in the editor — takes an inline
  binding and automatically creates a `_<parent>-<slot>` private fn with
  reference refactoring.

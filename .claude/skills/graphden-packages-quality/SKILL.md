---
name: graphden-packages-quality
description: Quality of `resources/packages/**/{fns.edn,impls.clj}` — narrow types and type-aliases, minimal base-fn impls, **audit of ANY Clojure helpers in impls (including private `defn-`, middleware closures, handler wraps — not only `defbase` bodies)**, correct use of named vs anonymous fn-defs. Apply on ANY touch of the package layer — even if the edit looks like "just added a private helper to impls" or "Ring middleware glue", it is still the package layer and a candidate for graph-decomposition. Also as an explicit check of existing code ("go through the packages", "narrow the types", "clean up fns.edn", "check impls for extra logic"). Triggers — phrases like "fn-def", "fns.edn", "impls.clj", "base-fn", "private helper", "defn- in impls", "middleware", "ring wrap", "handler closure", "orchestration", "cache wrap", "post-process", "type too wide", ":jsonb", ":any", "type alias", "long union", ":nullable-*", "named or anonymous", "extract into helper", "impl contains logic", "MI vs single-parent", "namespace for fn", "fn-def reuse", "available to admin", "should be a concrete type". SKIP for: pure Clojure src/test code (→ `graphden-code-quality`), pure REPL-debug hypotheses (→ `graphden-repl`), frontend (.js/.css) — a separate skill.
---

# graphden-packages-quality — types, impls, fn-defs in `resources/packages/`

The goal of this skill: **keep the Graphden package layer in a shape in which
it stays explainable to an external contributor** — narrow types, named-and-
reused aliases, minimal base-fn impls (all composition in the graph), and
correct use of named vs anonymous fn-defs.

This skill is the common entry point. For details it delegates:

- **`graphden-fn-refactor`** — decomposition of large / non-atomic
  base-fn impls. Applies BOTH to new code (new impls must also
  be atomic) AND to auditing existing ones.
- **`graphden-fn-design`** — naming rules for fn-defs (`_`-private vs
  public, MI vs single parent, namespaces, `:const` wrappers). Applies
  BOTH to new code AND to auditing existing ones.
- **`graphden-code-quality`** — the Clojure side (src/), if an edit
  drags src changes along with it.

This file adds **rules specific to the package layer** that are absent
in the delegates: type-narrowing, type-aliasing, and the check order.

**Apply both when writing new code and as an explicit check of
existing code.** If a new fn-def / type / impl passes this skill on
the first pass — you won't have to come back. If you're asked to "check the types" /
"clean up the package" — that's an explicit rerun over the same list.

## 0. Sanity checks before starting

```bash
# Must be zeros. If red — close it BEFORE refactoring.
bb check                   # lint
clojure -M:dev tools/reachability_audit.clj | grep -E 'Unreachable COMPOSED|^$' -A1 | head -10
```

In the REPL (`graphden-repl` skill) — for checking a hypothesis against the
current live graph:

```clojure
(types/resolve-alias :nullable-text)      ; => [:union :null :text]
(registry/rich-type-of :my-fn)            ; => {:return … :args … :effects …}
```

## 1. Types should be as narrow as possible

**Anti-pattern**: writing `:jsonb` or `:any` in a slot type, when the real
contract is a concrete record / refinement / union. A wide type breaks:

1. **The type-checker doesn't see errors** — `:jsonb` accepts anything
   jsonb-shaped; the real mismatch surfaces at runtime.
2. **The editor doesn't show the right type-chip** — the user doesn't
   see what the slot expects.
3. **`form-picker` (`/api/value-form`) doesn't offer the right widget**
   — `:jsonb` → generic JSON editor; `:port` → number-input with range
   validation.

### 1.1 What to narrow first

| Symptom | Replacement |
|---|---|
| `:jsonb` for a record-shape input | inline `{:k1 T1 :k2 T2}` or a named record type-row |
| `:jsonb` for a map-shape input | `[:map K V]` (`:keyword-map`, `:text-keyed-map`, `:text-map`) |
| `:jsonb` for a list | `[:list T]` |
| `:any` for a callable | `:fn` (HOF-wrap) or `[:fn args ret]` (structural) |
| `:any` for an already-built value | a concrete type-row or `:jsonb` if it really is jsonb-shaped |
| `:int` for an HTTP port | `:port` or `:user-port` (refinement) |
| `:text` for a URL | `:url` (refinement) |
| `:text` for required-non-blank | `:non-blank-text` |

**When `:jsonb` / `:any` IS justified**:

- `:jsonb` — a genuinely arbitrary JSON-shaped payload (e.g. user-
  supplied request body before parsing).
- `:any` — an escape hatch for an already-built Clojure function (NOT for
  a callable, but for an already-built fn-value) or for passthrough
  semantics (`:const :value` — coerced back via a rule).

### 1.2 How to find narrowing candidates

```bash
# `:jsonb` in slot types — look at every use-site:
grep -rE ':type :jsonb|"jsonb"' resources/packages --include='*.edn' | head -20

# `:any` in slot types — same:
grep -rE ':type :any' resources/packages --include='*.edn' | head -20

# `[:union :null …]` longer than one line — an alias candidate:
grep -rEn '\[:union :null :[a-z]' resources/packages --include='*.edn' | head -10
```

For each case — ask:

1. What does this slot actually read at runtime?
2. Could the value be a violation (a record instead of an int)?
3. Is there a real known type? If yes — narrow it.

## 2. Type aliases — recurring shapes get a name

**Rule**: if **the same structural form** (`[:union :null
:text]` / `[:map :keyword :any]` / etc.) appears in **5+ places**
across the packages — it deserves a name and reuse.

### 2.1 The ready-made set of aliases

They all live in `resources/packages/core/refinements/fns.edn`:

| Alias | Structure | Why |
|---|---|---|
| `:nullable-text` | `[:union :null :text]` | 37+ inline sites before aliasing |
| `:nullable-uuid` | `[:union :null :uuid]` | 24+ sites |
| `:nullable-jsonb` | `[:union :null :jsonb]` | 6+ sites |
| `:nullable-int` | `[:union :null :int]` | sequence-position, optional limits |
| `:nullable-keyword` | `[:union :null :keyword]` | variant tags from optional sources |
| `:keyword-or-text` | `[:union :keyword :text]` | post-JSONB-roundtrip identifiers |
| `:type-expression` | `[:union :keyword :text [:list :any] :keyword-map]` | type expr representations |
| `:keyword-map` | `[:map :keyword :any]` | decoded entity rows, parsed forms |
| `:nullable-keyword-map` | `[:union :null :keyword-map]` | read-or-nil entity sites |
| `:text-map` | `[:map :text :text]` | HTTP headers, form-urlencoded, vault metadata |
| `:text-keyed-map` | `[:map :text :any]` | raw JDBC rows, layout expansion maps |
| `:path-segment` | `[:union :keyword :int :text]` | `:get-in` / `:assoc-in` segments |
| `:positive-int` / `:non-negative-int` / `:negative-int` | refinements on `:int` | numeric bounds |
| `:port` (`1..65535`) / `:user-port` (`1024..`) / `:http-status` (`100..599`) | refinements on `:int` | domain ranges |
| `:percent` (`0..100`) / `:probability` (`0..1`) | refinements on `:numeric` | bounded numerics |
| `:non-empty-text` / `:non-blank-text` / `:url` | refinements on `:text` | text invariants |

Refinements (the `:_refinement-narrow` template) give a runtime check —
`:ensure-positive-int :args {:value 42}` → `42` or throws
`:refinement/violated`.

### 2.2 When to introduce a new alias

**YES** (a name is justified):

- ≥ 5 use-sites of the same structural form.
- A semantic name adds meaning (`:port` is better than `[:int {:constraint
  [:and [:>= 1] [:<= 65535]]}]`).
- Reuse within a single domain (HTTP-related shapes, secret-flow shapes).
- **A decoded entity-row shape with a fixed schema** (`:fn-row-shape`,
  `:branch-version-row-shape`, `:vault-metadata-shape`) — the name
  documents the SOURCE of the data, even at a SINGLE callsite. See § 2.2.1.

**NO** (an alias isn't needed):

- 1-2 use-sites of an **anonymous** structural form — the inline form
  is easier to read (`{:keys [name id]}` vs `:_some-result-shape`).
- "Let's name it just in case" — it clutters the alias namespace.
- Aliasing within a single fn-def — that's an inline composite type (see
  `graphden-fn-design` §3), not an alias.

### 2.2.1 Single-use alias — do NOT remove automatically

**Important for audits:** the "≥ 5 use-sites" rule applies ONLY
to the decision of "whether to introduce a new alias". An already-existing single-use alias
is **not subject to automatic inlining** — its name carries information
that the inline shape would lose.

Concretely:

- `:_resolve-binding-versions-decode :return-type :binding-version-row-shape`
  reads in a second — "this function decodes a binding-version row".
- The inline variant (`:return-type {:id :uuid :binding-id :uuid :branch-id :uuid
  :fn-id :uuid :slot-id :uuid :value :nullable-jsonb :value-present
  [:union :null :bool] :ref-fn-id :nullable-uuid ...}`) — 13 lines,
  and the reader has to recall that this is a binding-version.

An alpha name = free type documentation. Stripping it for the sake of "the
5+ rule" cuts meaning, not complexity.

**When a single-use alias CAN genuinely be removed:**

- If the name is synthetic (`:_some-step1-result`) with no domain meaning —
  it's scaffolding, not an alias. Inline it at the callsite.
- If the shape is so simple (1-2 fields) that the name adds noise, not
  meaning (`:_count-result {:count :int}` → better inlined).
- If the alias duplicates another already-existing one with the same shape — merge.

Otherwise a single-use named shape is **type documentation**, and it's
cheap. Leave it.

### 2.3 Where to put a new alias

| Group | File |
|---|---|
| Generic shapes (`:nullable-*`, `:keyword-map`) | `core/refinements/fns.edn` |
| HTTP-specific shapes (`:ring-request-shape`, `:ring-response-shape`) | `web/ring-adapter/fns.edn` |
| Domain-specific shapes (`:security-headers-shape`) | the corresponding package |
| Refinement narrowers (`:ensure-X`) | together with the alias, in `core/refinements/fns.edn` |

When adding an alias **update the comment block** at the start of the section with
the current call-site count (the source of truth for "> 5 inline sites").

### 2.4 Sync-time gotcha — type-aliases must be registered BEFORE parsing

Currently `initialize-with-base-fns!` calls `register-type-aliases!`
via `requiring-resolve` before base-fn validation. If you introduce a new
alias and a base-fn references it in `:return-type` — sync must
register the alias BEFORE it validates the base-fns.

This is handled by `system/core/register-type-aliases!`. You don't need to do
anything extra **if the alias lives in `core/refinements/`**
(it loads first). If it's inside `web/`/`app/` — there may be a
load-order issue; usually resolved by putting the alias in `core`.

## 3. base-fn impls — minimality

**Delegates to `graphden-fn-refactor`** (§3 user-composability test, §4
decomposition recipe). In brief:

- **A single direct library/Java call** + boundary-coercion → OK.
- **Executor core** (`if`/`cond`/`try`/`atom`/`future`/`sleep`/`=`) → OK.
- **An algorithm with an invariant** (journalled-txn with rollback, cycle-guarded
  recursion) → OK.
- **Everything else** — composition, move it into `fns.edn`.

### 3.1 Extra criterion for NEW impls (this skill, not fn-refactor)

When writing a **new** impl: always ask the user-composability
test BEFORE writing.

> If a Graphden user wants to vary ONE of the steps of my
> new impl — will they have to write a new Clojure impl?

If YES — switch to decompose-from-scratch, not "I'll start with a big
impl, then split it up". It comes out cheaper.

### 3.2 Sanity checks for an impl

```clojure
;; In the REPL:
(:impl @(resolve 'graphden.packages.core.logic.impls/equal?-fn))
;; → #object[...] — a function

;; Is the body exactly one or two lines of real code?
;; If there's a distinct cond/case/let — a split candidate.
```

```bash
# Long defbase in impls.clj — candidates for §1 fn-refactor:
python3 << 'EOF'
import re, os
for root, _, files in os.walk('/root/projects/graphden/resources/packages'):
    for f in files:
        if f != 'impls.clj': continue
        path = os.path.join(root, f)
        with open(path) as fh: content = fh.read()
        matches = list(re.finditer(r'^\(defbase\s+(\S+)', content, re.M))
        for i, m in enumerate(matches):
            start = m.start()
            end = matches[i+1].start() if i+1 < len(matches) else len(content)
            n = content[start:end].count('\n')
            if n >= 20:
                print(f"  {n:4d} {m.group(1):30s} {path.split('packages/')[1]}")
EOF
```

≥ 20 lines of defbase — go through `graphden-fn-refactor` §3-§4. Every
"I'm not splitting" justification — explicit (§1.5 fn-refactor).

### 3.3 Hidden composition in private helpers (not only in `defbase`)

**The most common hole:** you add a `(defn- foo …)` to `impls.clj` for
"glue" (Ring middleware, cache wrap, multi-step orchestration). From
the standpoint of the existing checklists it isn't a `defbase`, isn't a fn-def, isn't a
type — formally it slips through. But semantically it's **composition
that belongs in the graph**.

Symptoms (any ≥ 1 — a reason to stop):

| Symptom | What it means |
|---|---|
| `defn-` returns `(fn [req] …)` (closure-handler) | Wrap-style middleware — should be a fn-def via `:if`/`:call`/`:cond` (the `:branch-routing-wrap` pattern in `web/branch-router/fns.edn`). |
| `defn-` orchestrates ≥ 3 steps: `(let [a (step1 …) b (step2 a) …] (final …))` | This is composition. Each step is a base-fn candidate, the glue is a fn-def. |
| `defn-` branches on a response/request shape condition (`if-let`, `cond` over headers, `when` over content-type) | Conditional logic belongs in the graph (`:if`/`:cond` over predicate base-fns). Pure runtime branching is the only exception. |
| `defn-` mutates state (`swap!`/`reset!`/`alter`) AND takes data from the request side | Mutation is fine in impls (state lives there), BUT access to it should be through narrow base-fns (`*-get`, `*-put!`), and the decision of "when to read / when to write" — in a fn-def. |
| `defn-` uses the phrase "orchestrate", "process", "pipeline", "wrap", "chain" in its name or docstring | A semantic marker of composition. |
| `defn-` is called from a `defbase` body as a "convenience helper" | If a base impl delegates to a helper — composition is already hidden. Extract the helper into a separate base-fn (or a series of base-fns) and glue it via a fn-def. |

```bash
# Find every private helper in impls.clj — check each > 10 lines:
python3 << 'EOF'
import re, os
for root, _, files in os.walk('/root/projects/graphden/resources/packages'):
    for f in files:
        if f != 'impls.clj': continue
        path = os.path.join(root, f)
        with open(path) as fh: content = fh.read()
        matches = list(re.finditer(r'^\(defn-?\s+(\S+)', content, re.M))
        for i, m in enumerate(matches):
            start = m.start()
            end = matches[i+1].start() if i+1 < len(matches) else len(content)
            n = content[start:end].count('\n')
            if n >= 10:
                line = content[:start].count('\n') + 1
                print(f"  {n:4d} {m.group(1):28s} {path.split('packages/')[1]}:{line}")
EOF
```

```bash
# Closure-returning helpers (wraps/middleware) — almost always composition:
grep -rEn '^\(defn-?\s+\S+.*\n.*\(fn\s+\[req' resources/packages --include='impls.clj' | head
# Pipeline helpers with three+ steps:
grep -rEnB1 '\(->>\s+\S+\s+\S+\s+\S+\s+\S+' resources/packages --include='impls.clj' | head
# Names with orchestration markers:
grep -rEn '^\(defn-?\s+(\S*orchestr|\S*pipeline|\S*-wrap|run-handler|process-\S+|chain-)' resources/packages --include='impls.clj'
```

**Refactor recipe:**

1. **Split** the private helper into 2-N narrow base-fns — each does
   one step (cache lookup, encode-body, header-attach, etc.). Their impl —
   one or two lines.
2. **Declare** each base-fn in `fns.edn` alongside — argument types,
   return, effects.
3. **Glue** them into a graph wrap via `:if` / `:cond` / `:call` —
   the reference example is `:branch-routing-wrap` in
   `resources/packages/web/branch-router/fns.edn`:

   ```edn
   {:name :branch-routing-wrap
    :parent :if
    :args {:test :_branch-router-installed?
           :then :_branch-dispatched
           :else :base-handler-fallback
           :base-handler {:type [:fn …] :description "…"}}}
   ```

4. **Delete** the old private helper. Composition is now visible.
5. **Cover with a test** at the graph level — the handler chain through the wrap
   should work end-to-end (smoke + integration suite).

**When a private helper in impls is OK:**

- Thin boundary-coercion for a library call (`String/.getBytes`,
  `(java.io.InputStream/.read …)`, etc.) inside a single base-fn.
- A one-expression helper (≤ 3 lines), no branching, no state.
- Internal state-management for a single atomic primitive (FIFO
  eviction inside a cache-put base-fn — but if the eviction decision
  depends on request data, it goes in the graph).

## 4. fn-defs — named vs anonymous

**Delegates to `graphden-fn-design`** (§1 public vs `_`-private, §2 auto-
name, §3 inline composite, §5 MI vs single-parent, §6 namespaces, §7
decomposition). In brief:

- **Public name (no `_`)** — the fn is reused (≥ 2 use-sites today
  or planned), or it's a recognizable domain entity.
- **`_`-private** — a single use-site, the name carries no meaning outside the parent.
- **Inline composite** (`:input {:k T}` / `:type {:k T}`) — an anonymous
  record-shape; shape-deduped via `anonymous-hash`.
- **MI (`:parents [a b]`)** — orthogonal slot sets (mix-in trait),
  not "behavior glue".

### 4.1 Extra criterion for NEW fn-defs (this skill, not fn-design)

When writing a **new** fn-def: ask BEFORE naming.

**Rule 1 (when name is required)**: give an explicit public name —
required if:

- A recognizable domain entity (`web-server`, `json-ok-response`).
- ≥ 2 use-sites are already planned (explicitly now or in the roadmap).
- It will be exported from the package (consumers from other packages).

**Rule 2 (when `_`-private suffices)**: a `_`-private name — when
there's a single use-site + the name "sounds" only next to the parent. It's
the equivalent of Clojure's `defn-`.

**Rule 3 (when anonymous suffices)**: inline `{:parent :X :args
{:value …}}` — when you need a one-off literal-wrapping at a single use-site,
and a `_`-name would be synthetic ("step1"). It's the equivalent of Clojure's
`let`.

**Anti-pattern**: giving a public name "just in case". It clutters the
namespace + sidebar. → Make it `_`-private; we'll promote it when reuse
appears.

### 4.1.1 Single-use `_`-private fn-defs — do NOT inline automatically

The mirror of § 2.2.1 for **fn-defs**. When an audit sees a chain of 20-30
`_private-prefix-*` fn-defs where each is used once — that's NOT
an automatic reason to inline them into anonymous `{:parent :X :args …}`.

The step names are **documentation of the transformation order**, even without
reuse:

- `:_partial-mismatch-prov-tier-source-fn-id-text` → tells the reader
  "this is a stringified fn-id for an HTML data-attr". The inline variant
  `{:parent :to-str :args {:value {:parent :get :args {…}}}}` inside a
  large `:hiccup` assembly hides the intent in an anonymous nesting.
- `:_partial-mismatch-prov-tier-has-source?` → a boolean check with a
  readable name; the inline `{:parent :some? :args {:value …}}` loses
  "this branch depends on the fact that the source is present".
- `:_partial-mismatch-prov-tier-row-built` → the assembled hiccup row;
  the name is a seam-mark that "this is the final row, hidden only under
  a conditional".

**When a single-use `_`-private is really worth inlining:**

- The name is FULLY synthetic (`:_step1`, `:_tmp`, `:_inner`) — it's
  scaffolding with no meaningful content.
- The wrapping is trivial and the sole use is in a NEIGHBORING fn-def
  (`{:parent :str :args {:value :_x-as-text}}` where `_x-as-text` is
  `:to-str` of the neighbor) — yes, inline it, if the reader sees
  EVERYTHING in one place.
- A 1-2-line `_`-private that would read better as a `:let` binding
  inside the parent (but the fn-graph doesn't support `:let`, so this is
  a moral analogy — actually leave it).

**Otherwise** named scaffolding = pipeline documentation. § 4.1's rule
"inline when the `_`-name would be synthetic ("step1")" — is about the
ABSENCE of a domain name, not about "used once".
`-fn-id-text` / `-has-source?` / `-row-built` — are NOT synthetic.

### 4.2 Sanity checks for fn-defs

```bash
# Find a fn-def explicitly declared but NOT registered in reachability:
clojure -M:dev tools/reachability_audit.clj 2>&1 | grep -A50 'Unreachable COMPOSED'

# It could be:
# - Genuine dead code (delete).
# - A dynamic-dispatch false positive (e.g. `:postgres-storage-impl` —
#   bound at runtime). Grep the name across `src/` — if there's a string-ref,
#   leave it and add a "dynamic dispatch" comment.
```

```bash
# fn-def names are unique per-(namespace, name) — NOT globally (ADR-identity
# stage 5). Only base-fn names (`defbase`) are globally unique. A bare ref to a
# name defined in several namespaces must be qualified (`:other.ns/name`) or
# sync throws `:packages/ambiguous-ref`. Before naming, grep for a clash in the
# SAME namespace and for any base-fn of that name:
grep -rE ":name :the-target-name\b|defbase the-target-name\b" resources/packages/
```

### 4.3 Multi-parent (`:parents [A B]`) — the rule

`graphden-fn-design` §5 gives three "justified" cases (categorization,
trait-mixin, refinement). Here — a stricter **BINARY RULE for
the moment of writing**, plus a `bb`-checkable sanity-test.

**The rule (formulated):**

> MI is justified **if and only if** each parent
> represents a **separate axis of description** of the child — and not a separate step
> in its behavior. Each axis adds a NON-overlapping set of slots
> and a NON-conflicting contract.

**The axes test — "conjunction of nouns" vs "conjunction of verbs":**
translate `(child :parents [A B])` into natural language:

- ✅ **Nouns** (this **IS-A** A AND **IS-A** B):
  - "`:postgres-storage-impl` IS-A `:Storage` (type-row protocol)
    AND IS-A a concrete-impl-with-binding-set (own slots for pg-query
    bindings)". Type-row + impl-shape = two orthogonal axes.
  - "`:authed-get-route` IS-A `:get-route` (path + handler shape)
    AND IS-A `:auth-required` (middleware chain)". Route-shape +
    capability-marker.
  - "`:assoc-handler` IS-A `:assoc-fn` (slot types) AND IS-A
    `:assoc-empty` (empty-map seed)". Type-shape + initial-value.
- ❌ **Verbs** (this **DOES** A AND **DOES** B):
  - "`:_my-handler` parses AND validates AND writes" — this is behavior
    in three steps, it is assembled via `:if`/`:cond` glue +
    ref-bindings in `:args` (see `graphden-fn-refactor` § "handler =
    parse → validate → apply"), NOT via MI.

**The binary slot-collision test (what sync will check
automatically)**: let `own-slots(P)` be the set of slot-names that
parent `P` CONTRIBUTES to its `:fn-slots` junction. MI is permissible iff:

```text
own-slots(A) ∩ own-slots(B)  ⊆  {slots that child OVERRIDES via :args}
```

If the intersection isn't covered by overrides — sync will fail on the slot-
collision check (`composition.validation`). If you're "covering with
overrides because they compose semantically, but I'll pin both" —
**that's a mixture**: you're no longer describing a shape, you're papering over a
conflict. In that case rewrite it as single-parent + composition.

**The "MI saves typing" rejection heuristic**: if the choice between
single-parent + 5 ref-bindings **vs** two parents without ref-bindings
is made for the sake of **brevity** — MI is not the choice. MI describes what a child IS,
it doesn't build behavior via a shortcut.

**Sanity check for an existing MI fn-def — via the DB** (see § 5 below —
the DB is better than grep for this):

```clojure
;; In the REPL — the real slot names of a fn-def after MI-merge:
(let [fn-id (:id (first (sp/query-entities storage :fn {:name "my-mi-fn"})))]
  (->> (sp/query-entities storage :fn-slot {:fn-id fn-id})
       (map (fn [fs]
              (let [slot (sp/read-entity storage :slot (:slot-id fs))
                    type-fn (sp/read-entity storage :fn (:type-fn-id slot))]
                {:slot-name (:name slot)
                 :slot-type (:name type-fn)
                 :from-parent? (not= (:fn-id fs) fn-id)})))))
;; Every slot should be explainable: "this is from parent A" / "from parent B"
;; / "own override". A "no idea where from" slot → a parent contributed extra →
;; MI isn't justified, break it up.
```

**Common MI-in-today's-graph examples** for calibrating your instincts:

| Fn-def | Parents | Why MI |
|---|---|---|
| `:postgres-storage-impl` | `[:Storage]` (singleton) | type-row impl pattern; the type-row itself sets the protocol obligations, the child binds them to pg-query |
| `:authed-get-route` | `[:get :auth-required]` | route-shape + middleware (two axes) |
| `:resolve-versioned-rows` | `[:filter :ResolveVersionedRowsInput]` | filter behavior + the input type-row contract (`:version-id-field` etc.) |

## 5. EDN-grep vs DB-query — methodology

EDN is the **source**, the DB (after `bb rebuild` / `bb deploy`) is the
**synced graph**. They have different levels of visibility, and for
different questions the right tool differs.

### 5.1 When the DB is better than grep

| Question | Why the DB | EDN-grep misses |
|---|---|---|
| "Where is fn-def `X` used?" | `:binding :ref-fn-id X` + `:binding-list-item :ref-fn-id X` | EDN doesn't see synthetic `_anon-*` refs that the parser created from inline `{:parent :X …}` forms |
| "Which fn-defs are duplicated by shape?" | `(group-by :anonymous-hash)` — shape-dedup is done at the DB level | EDN sees two `{:input {:a :int}}` — but doesn't know they're shape-deduped into one fn-row |
| "What slots does fn-def `X` actually have after MI?" | `:fn-slot {:fn-id X}` (the full set after parent BFS) | EDN sees only OWN slots, not inherited ones |
| "Where is the type `:jsonb` used?" | `(query-entities :slot {:type-fn-id :jsonb-id})` | EDN sees `:type :jsonb` in declarations, but not computed types (when the type-checker inferred a shape) |
| "Which refinements actually appear in the graph?" | `(query-entities :fn {})` filter by `:base-fn-id` | EDN sees declarations, but not the runtime-effective set |
| "What's fn-def `X`'s computed return-type?" | the rich-types registry in the JVM — not serialized to the DB, but visible from the REPL | EDN sees the DECLARED return-type, not the INFERRED one |

### 5.2 When EDN-grep is right

| Question | Why EDN |
|---|---|
| "Where is `:type :jsonb` declared?" (for narrowing) | The source of the edit is EDN; you need to find the DECLARATIONS, not the runtime effect |
| "Where does a docstring / `:description` mention X?" | The DB stores the description, but grep over the EDN text is more readable |
| "Where should a new fn-def go?" (namespace pick) | You need to see how the neighbors are structured — EDN with comments is clearer than a DB dump |
| "What's the shape of an inline literal in `:value`?" | Literals are stored as JSONB — EDN is easier to read |

### 5.3 The idiomatic workflow for finding / editing

1. **Query the DB** (REPL `sp/query-entities`, `curl /api/graph/entities`,
   the `pg-query` base-fn in the live graph). Get the list of fn-names /
   fn-ids.
2. **Grep by fn-name in EDN** — `grep -rE ":name :the-name\b"
   resources/packages` to find the source.
3. **Edit the EDN**, run `bb rebuild`.
4. **Verify via the DB** — repeat the same query and make sure the result
   changed as expected.

### 5.4 Practical queries

```clojure
;; ── In the REPL ───────────────────────────────────────────────────
;; From the live system (`bb repl` connected to dev) or via a test:
(require '[graphden.storage.protocol.core :as sp])
(def storage (-> integrant.repl.state/system :db/versioned))

;; (a) All refs to :equal? — where and in which slot:
(let [equal?-id (:id (first (sp/query-entities storage :fn {:name "equal?"})))]
  (->> (sp/query-entities storage :binding {:ref-fn-id equal?-id})
       (map (fn [b]
              {:owner (-> (sp/read-entity storage :fn (:fn-id b)) :name)
               :slot  (-> (sp/read-entity storage :slot (:slot-id b)) :name)}))))
;; → [{:owner "_bearer-equals-env?" :slot "a"} …]

;; (b) All fn-rows with the same shape (structural duplicates):
(->> (sp/query-entities storage :fn {})
     (filter :anonymous-hash)
     (group-by :anonymous-hash)
     (filter (fn [[_ fns]] (> (count fns) 1))))
;; → empty = shape-dedup worked; otherwise — a bug in the parser

;; (c) All slots of type :jsonb (potentially too wide):
(let [jsonb-id (:id (first (sp/query-entities storage :fn {:name "jsonb"})))]
  (->> (sp/query-entities storage :slot {:type-fn-id jsonb-id})
       (map (fn [s]
              {:slot-name (:name s)
               :owners (->> (sp/query-entities storage :fn-slot {:slot-id (:id s)})
                            (map #(-> (sp/read-entity storage :fn (:fn-id %)) :name)))}))))
;; → grouped by slot-name; look for extra widely-shaped declarations
```

```bash
# ── Via curl + jq ─────────────────────────────────────────────────
AUTH=Bearer $AUTH_TOKEN  # if /api/graph/entities is auth-required

# (a) All fns with a given name-prefix:
curl -s http://localhost:8080/api/graph/entities -H "Authorization: $AUTH" \
  | jq '.fns | map(select(.name | startswith("_secret-")))'

# (b) Composed fn-defs with empty parent-ids — candidates
#     for type-row OR base-fn (distinguish by return-type-fn-id):
curl -s http://localhost:8080/api/graph/entities -H "Authorization: $AUTH" \
  | jq '.fns | map(select((.parent_ids == null or (.parent_ids | length == 0))
                          and (.return_type_fn_id == null)
                          and (.name != null)))'
# → type-rows (base-fns would have return_type_fn_id; composed would have parent_ids)
```

```clojure
;; ── Via the :pg-query base-fn (if you want to run it from the graph itself) ──
;; In the REPL:
(exec/execute-by-name *context* "pg-query"
                      {:hsql {:select [:name]
                              :from [:fn]
                              :where [:and
                                      [:= :return_type_fn_id nil]
                                      [:is :parent_ids nil]
                                      [:not= :name nil]]}})
;; → the list of type-row names
```

### 5.5 When the DB is not yet in the right state

If you've just added a fn-def to EDN, the DB doesn't see it yet until
`bb rebuild` / `bb deploy`. The declarative sync **doesn't remove** rows
that dropped out of EDN — they accumulate in the dev DB. Therefore:

- For **finding dead code** (what's in the DB but not in EDN) — you need
  `bb deploy` (truncate + clean sync), not `bb rebuild` (see
  `graphden-fn-refactor` §7).
- For **finding what's-in-EDN-but-broken** — `bb rebuild` is enough.
- For **production debugging** — the DB of the production server, WITHOUT a rebuild
  (which is done only on deploy — NO `bb rebuild` against prod).

## 6. The check order for existing packages

```bash
# 1. Reachability — is there dead code?
clojure -M:dev tools/reachability_audit.clj 2>&1 | grep -A20 'Unreachable COMPOSED'

# 2. Types too wide in slot declarations:
grep -rEn ':type :jsonb|:type :any' resources/packages --include='*.edn'

# 3. Long inline unions — alias candidates:
grep -rEnB1 ':type \[:union :null :' resources/packages --include='*.edn' | head -20

# 4. Long base-fn impls — candidates for §3 of this skill:
# (see §3.2 above — the Python script)

# 5. An anonymous fn-def with an explicitly set `_anon-…` name — a bug:
grep -rE ':name :_anon-' resources/packages --include='*.edn'

# 6. A public-named fn-def with a single use-site — a candidate for `_`-private:
# (requires analyzed reachability + a grep over `:parent :X` / `:ref X`;
#  done interactively, not by checklist)
```

## 7. Tests for a new / edited package

**Tests for security-critical fns** (see `graphden-code-quality` §12)
— mandatory as a regression sentinel.

Example (from this session): `:constant-time-equal?` added → `test/graphden/
packages/core/logic_test.clj` created with tests for:

- matching strings → true,
- non-matching → false (mismatch at first / last / length boundary),
- nil / non-string → false (differs from `:equal?` behavior).

**Pattern**: slurp+eval `impls.clj` via the loader's `load-module-impls`
(see `concurrency_test.clj` / `logic_test.clj` / `refinements_test.clj`
for a template). This is unit-level — no full bootstrap needed.

**When a unit test isn't enough** — add a behavioral test via
`bootstrap-crud-graph-from-golden!` (see `executor/compile-packages-
test.clj` / `refinements_test.clj`). It drives the fn-def through the executor
over a really synced graph.

## 8. Workflow

### 8.1 A new base-fn

1. Before writing — go through the user-composability test (`graphden-fn-
   refactor` §3) — maybe it's composition, not a base-fn.
2. If it's a base-fn after all: write the impl minimally (1-2 lines of body),
   declare the type in `fns.edn` (a narrow type, see §1; an alias if it recurs,
   see §2).
3. If security-critical (any compare-with-secret, any `:secret
   T` consumer) — write a test-sentinel (see §7).
4. `bb rebuild` → `bb verify` → smoke.

### 8.2 A new fn-def

1. Decide: named (public) / `_`-private / inline (see §4).
2. Declare via `:parent <p>` (for a single parent) or
   `:parents [a b]` for MI — BUT only when the §4.3 binary-test
   passes. By default single-parent + ref-bindings in `:args`.
3. Narrow the slot types (see §1); use an alias if the structure
   recurs (see §2).
4. If ≥ 4-5 ref-bindings → consider decomposition (`graphden-fn-
   design` §7).
5. `bb rebuild` → smoke. **Verify via the DB** (see §5.3 step 4) —
   repeat the same query used for finding, and make sure
   the result changed as expected.

### 8.3 Auditing existing packages

1. **Baseline** — §0 sanity + reachability audit + the list of slow tests.
2. **Scan per §6** — how many candidates for each kind of edit.
   **For structural questions use the DB** (see §5.1), not grep.
3. **Summary** before edits — priority: security/correctness >
   widening types > dead code > naming hygiene > alias unification >
   MI clean-up.
4. **Edit per-target, per-commit** — each significant change is a
   separate commit. Apply `graphden-code-quality` §13.3 commit
   rules.
5. **`bb rebuild` + `bb verify` + focused tests** after each
   commit. **Verify via the DB** that the edit achieved its goal.
6. **Final sweep** — `bb test` or `bb ci`.

## 9. Anti-patterns

- **"I'm narrowing types" without checking the runtime semantics.** A slot type
  `:jsonb` → `:keyword-map` — add a breakpoint / REPL check
  (see §5.4): are the bindings really always keyword-keyed? Narrowing
  without verify = breaking runtime.
- **An alias for the alias's sake.** A name is needed only when it adds
  MEANING. `:int-or-text` is worse than inline `[:union :int :text]` —
  because for those 2 use-sites reading `[:union :int :text]`
  is clearer.
- **A `_`-prefix on a public-API fn-def.** If someone references it from
  another package — it's no longer private. Promote it (see
  `graphden-fn-design` §1).
- **An inline composite type for a single-use-site that SUDDENLY got
  copy-pasted into two-three places.** → Rename it into a `:_some-shape`
  alias (or a regular type-row) and use it by name. Otherwise
  the shape-hash dedups, but the semantics are hidden.
- **Deleting a dead fn-def without a grep over comments.** The name may
  be mentioned in a neighboring fn-def's docstring — the comment
  will go stale.
- **"I'll rewrite everything with refinements"** — `:int → :positive-int`
  everywhere. Refinements add a runtime check (`:ensure-X` throw on
  violation); if the data flow isn't controlled at the input,
  you'll get a runtime crash. Narrow types where the input contract is
  explicit (admin forms, parsed bodies); NOT for transit-types between
  internal fns.
- **MI for the sake of "behavior glue".** "I want the fn to do both X and Y" —
  that's step composition, not axes-of-shape (see §4.3). MI describes
  what the child IS-A, not what it DOES. Rewrite it via `:if`/`:cond` +
  ref-bindings.
- **MI instead of single-parent + ref-bindings "for brevity".**
  If the choice of "two parents without `:args`" vs "one parent + 5
  `:args` bindings" is made for a compact fns.edn — it's an
  illusory saving. The sync-time slot-collision check catches some
  such mixtures, but not all — some slip through and the reader sees
  slots "from somewhere".
- **Grep over EDN where the DB is needed.** Searching for "where fn-def
  `X` is used" via a grep over `:parent :X` misses synthetic
  `_anon-*` refs (the parser created them from inline `{:parent :X …}` forms).
  Likewise: a grep over shape misses `anonymous-hash`-
  deduped fns. For structural questions use the DB (see §5.1).
- **A DB-query without `bb rebuild` after editing EDN.** The declarative sync
  binds EDN ⇄ DB only on a rebuild. Edit → MANDATORY
  rebuild → verify via the DB. Without a rebuild the query will show the old
  state, easy to believe "it didn't work".

## 10. Relationships with other skills

- **`graphden-fn-refactor`** — details on decomposing impls (§3 user-
  composability test, §4 recipe). This skill calls it for
  specifics.
- **`graphden-fn-design`** — details on naming / MI / namespaces /
  `:const` wrappers. This skill calls it for specifics.
- **`graphden-code-quality`** — Clojure src/ — a sister skill. If
  a package edit drags a src change along (a new impl needs a
  helper in `src/`, or `system/core` needs seeding) —
  switch over.
- **`graphden-repl`** — debugging hypotheses against the live graph.
  ALWAYS used when editing runtime-important fns.
- **CLAUDE.md** + **docs/PACKAGES.md § Composition Best Practices** —
  the primary source of the project principles. This skill is the operational arm.

## 11. What counts as "nothing left to dig into" (for the package layer)

The final self-check before closing:

- [ ] `bb check` green (0 warnings)
- [ ] `bb rebuild` successful, `bb verify` shows the sections in sync
- [ ] Reachability audit — no NEW unreachable composed fn-defs
- [ ] Every `:jsonb` / `:any` in new declarations is JUSTIFIED (§1.1)
- [ ] Every long inline union is either used 1-2 times, or has
      received an alias name (§2.2)
- [ ] Every new base-fn impl passed the user-composability test (§3)
- [ ] Every security-critical impl is covered by a regression sentinel
      (§7)
- [ ] Every `:parents [A B]` declaration passed the §4.3 binary-test
      (axes-of-shape, not behavior-mix) AND a REPL check of the real
      slot names (no "where's this from?" slots)
- [ ] For structural questions (use-sites, duplicates, computed
      shapes) a DB-query was used (§5.1), not EDN-grep
- [ ] Every significant edit was verified via the DB (§5.3 step 4) —
      the repeated query shows the expected NEW state
- [ ] Every new named fn-def is justified by reuse or a domain
      entity (§4.1, rule 1)
- [ ] Every `_`-private fn-def is justified (§4.1, rule 2)
- [ ] Every commit is a separate concept-value-unit (see
      `graphden-code-quality` §13.3)

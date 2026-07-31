---
name: graphden-fn-refactor
description: Staged decomposition of large / non-atomic base-fn impls in Graphden, plus finding and removing dead code in the graph. Use when a `defbase` impl is itself big, calls a nearby hand-written helper, or delegates to a heavy `src/` namespace — the hidden composition must be lifted into a graph fn-def; OR when auditing the graph for unused / unreachable fn-defs. Encodes the staged "split one big impl into a few smaller base-fns glued by a fn-def" method, the parse→validate→apply handler pattern, and the reachability-audit technique. Triggers on phrases like "split this impl", "big impl", "impl calls a neighboring function", "make the base-fn atomic", "break into base-fn + fn-def", "decompose impl", "hidden composition", "dead code", "unused fns", "what else is dead". SKIP for: pure fn-def naming/structure questions (→ graphden-fn-design), pure frontend, package.edn edits.
---

# graphden-fn-refactor — decomposing impls into atomic base-fn + fn-def

The skill's job: systematically fix a violation of Graphden's core principle —
**the Graphden user writes fn-defs, not base-fns**. Anything the user might
need to vary without asking an admin for a new Clojure impl should be visible
as a graph fn-def, not hidden in a `defbase`. The narrow list of what
legitimately stays a `defbase` is in §3 below; the criterion is the
user-composability test.

Any multi-step processing is *composition*. Composition belongs in
`fns.edn`, not in `impls.clj`.

`web/crud` and `app/layout` have already been decomposed with this method
(stages L1–L2, C1–C4) — see the finished examples in their `fns.edn`. The
method below is general, for any next target.

## 1. What counts as a violation

A violation is a `defbase` that has:

- **(a) a large body** — > ~5 lines of real logic (excluding input-validation);
- **(b) a call to another base-fn** — hidden composition A→B, which belongs in the graph;
- **(c) delegation into a large hand-written function** — the `defbase` is thin, but
  it calls `helper/process-foo` from a neighboring `src/` namespace, where a
  multi-step pipeline lives. A thin wrapper doesn't save it: the composition is
  still hidden in code, not in the graph (violates principle #3 — explicit over implicit).

Type **(c)** is the most common and the most insidious: `impls.clj` looks like a thin shim, while
the whole pipeline (`parse → validate → write → respond`, `parse → load → compute`)
sits in `src/`.

## 2. First: is the target alive?

**Before decomposing, make sure the target actually runs.** A thin `defbase`
may delegate into a dead `src/` pipeline that has no callers at all.
Decomposing dead code into N graph fn-defs is worse than
useless: you add entities under code that never runs
(violates #2 and #6).

Check:

- is the fn reachable from the app root (`web-server`) / startup-fn / a route?
- is the route it serves actually hit — does the editor JS call it,
  does `curl` return a meaningful response, do the DOM hooks (`#modal-content`, custom
  events) exist in the editor JS?

If the target is dead → **delete the whole cluster** (impl + fn-def + route + handler),
don't decompose. Real case: server-side rendering of `/partials/entity-*` —
a leftover from the pre-cytoscape htmx editor; a decomposition attempt revealed it was
dead (editor JS doesn't call it, `parse-uri-segments` doesn't know its paths) →
~430 lines deleted instead of "sawing up a corpse".

For a systematic dead-code search see §9 (reachability audit).

## 3. The "may stay a base-fn" criterion — the user-composability test

The main test before defending any `defbase` from decomposition:

> **Can our user (a NON-admin who writes fn-defs) change the behavior
> of this fn by varying the composition in the graph — without an admin's
> help adding a new Clojure impl?**

If the ANSWER is "no, you'd have to write a base-fn", then by definition it hides
composition that belongs in the graph. This is a violation of principle #3 (explicit
over implicit) and it also breaks Graphden's promise — *"code = graph in the DB"*
without needing a Clojure developer for every micro-change.

**Any multi-step processing is composition.** Composition belongs in
`fns.edn`, not in `impls.clj`. Period.

The narrow list of `defbase`s that LEGITIMATELY remain a Clojure impl:

### 3.1. ONE direct library / Java / `clojure.core` call + boundary-coercion

The body is exactly `(lib/something arg ...)`, plus ONE minimal shape-coercion
strictly at the boundary with the library (graphden form → library form).

Coercion OK:

- `vec` for a seq argument of a library that expects a vector
- `(keyword key)` where the library expects a keyword and the JSONB roundtrip gave a string
- `(constantly response)` where the library expects `(fn [req] resp)`

Coercion NOT OK (this is already composition, cut it):

- Any `cond` / `case` / `when-let` branch on domain logic
- Assembling a structure from several parts (`{:status ... :body ...}` built
  from computed arguments = composition, not coercion)
- Domain literals nailed into the code (`[:meta {:charset "utf-8"}]`,
  `"<p class='error'>"`, hardcoded status 400)

The coercion test: **if the user supplied the data already in the library's
required form, would the body shrink to a single line `(lib/fn …)`**? Then the wrapper
is honest. If the user would still have to add the same branch
by hand — then the branch is domain logic, cut it.

Examples of honest library wrappers: `render-hiccup` (`(str (h/html hiccup))`),
`ring-router-fn` (`(reitit/router (coerce routes))`), `ring-handler-fn`,
`pg-query`, `sha256-hex-fn`, `str-split-fn`, `sleep-fn`.

### 3.2. Executor core

Executor primitives used by the ENTIRE rest of the graph, which
by definition cannot be expressed via the graph (because the graph is assembled OUT
of them):

- Control flow: `cond` / `case` / `if` / `try` / `and` / `or`
- Concurrency core: `future` / `loop-until-interrupted` / `sleep` /
  `sleep-until-ms` / `cron-next-after`
- State: `atom` / `swap-conj` / `deref`
- Comparison / structural: `=` / `nil?` / `some?` / `get` / `assoc`

This is the analog of those ~10 clojure.core functions everything else
stands on. They are not decomposed — they ARE the floor.

### 3.3. An algorithm with an invariant that cannot be split

The narrow list:

- **Journalled txn with rollback** — the body + on-throw-rollback of ONE `:try`
  must see the SAME `journal` atom. This is `apply-update-record-
  type-body`'s shape: body — inside the `:try`, rollback — in `:on-throw`,
  both read the journal. Phases INSIDE the body are helpers (names + code readability),
  but the `:try` boundary is indivisible.
- **Cycle-guarded recursion** — `letfn` with mutual recursion + a cycle-set,
  like `layout.graph/build-graph-elements`. The cycle-set must survive
  all recursive calls as one shared state; splitting it across separate
  base-fns is impossible.

Note: this is NOT a list of "everything that looks like one algorithm", it is a closed
list of KINDS of invariants. If you're forcing something else into "one algorithm" —
you're applying the old paragraph 3 all over again.

### 3.4. What is NOT on this list (and why I used to be wrong there)

- ❌ "Library-adapter boilerplate" — this was a loophole. `html-page` assembles
  a hardcoded `[:html [:head [:meta charset=utf-8] ...] [:body ...]]` — this is
  not a boundary, it's a page template, and the user must be able to vary it.
  It decomposes into `:hiccup-element` (an atomic primitive) + graph assembly.
- ❌ "Response assembly" (`_rejection-response`, error-builders) — the literals
  `{:status 400 :body "<p class=error>...</p>"}` are response composition,
  not coercion. It decomposes into `:ring-response` + `:str-concat` + `:get`.
- ❌ "Decomposition would just be _step1/_step2 with no meaning" — if the SEAMS don't name,
  then either it's item 3.1 / 3.2 / 3.3 (then leave it), or you're looking
  for the seams wrong. Seams exist — `parse / load / validate / transform / write /
  format / respond` always yield names. If your `defbase` has NONE
  of these — that's already suspicious, most likely a skewed decomposition.

### 3.5. What to do when you're short of new atomic primitives

During decomposition it often turns out the graph composition needs a base
brick that doesn't exist yet — for example `:hiccup-element` (tag + attrs +
children), `:str-concat`, `:ring-response-builder`. This is **normal and
expected**. Staged decomposition (§4):

1. You split a large base-fn into several smaller ones + a fn-def glue.
2. In the next stage, INSIDE the glue you find the next layer of composition
   that needs to be spelled out in the graph — and it turns out you need a new
   atomic primitive.
3. You add it as a minimal base-fn per rule 3.1 (ONE library call)
   or 3.2 (executor primitive).
4. Then the glue is rewritten through the new primitive — the intermediate-layer
   `defbase` disappears.

This is exactly "iterating to perfect cleanliness": each time we reduce everything to
{user composition in the graph} + {a minimal vocabulary of base-fns per
rules 3.1–3.3}.

## 4. Method: one decomposition = one stage

**Don't try to saw a large impl down to an atom in a single pass.** Proceed
in stages: at each stage one large impl → several SMALLER ones + one
fn-def "glue". The resulting base-fns aren't atomic yet — that's fine,
the next stage cuts them further.

### The recipe for one stage

1. **Pick a target** — one base-fn with a violation (a/b/c). Check §2 (alive?).
2. **Find the seams** — split the body into semantic steps: `parse` / `load` /
   `validate` / `transform` / `write` / `format`. Each step you can
   name is a candidate for its own base-fn.
3. **Create the smaller base-fns** — for each seam a `_`-private base-fn
   (`graphden-fn-design` §1). A "one algorithm" step — acceptably a large base-fn,
   cut later.
4. **Write the fn-def glue** in `fns.edn` — it COMPOSES the new base-fns and
   reproduces the original behavior.
5. **Replace the original** — the fn-def takes the NAME of the old base-fn; the old `defbase`
   and its line in the `impls` map are deleted. The name is preserved → all references intact.
6. **Declare the types** of the new base-fns in `fns.edn` (see §6).
7. **Verify** equivalence (REPL/live endpoint), then ONE `bb rebuild`
   → `bb verify` → smoke.
8. **Next stage** — cut one of the new base-fns further.

### The canonical pattern: handler = parse → validate → apply

A request-handler `defbase` almost always cuts into three stages, glued by `:if`
or `:cond` (that's how C2–C4 in `web/crud` are built):

- `_parse-*` (base-fn) — parses the Ring request → a data bundle.
- `_validate-*` (base-fn) — guards → **the rejection response itself** (or nil).
  By returning a ready response form rather than an abstract "verdict", you let
  the `:if` reuse the validation ref as the `:then` branch — a separate
  response builder is not needed.
- `_apply-*` (base-fn) — the uncuttable imperative core (DB write, etc.) →
  a success response.
- The glue:

  ```clojure
  {:name :process-foo
   :parent :if
   :args {:test :_rejected?              ; (some? validation)
          :then :_validate-foo            ; rejection response = the validation result itself
          :else :_apply-foo
          :validation :_validate-foo      ; shared sub-result, cached
          :parsed :_parse-foo}}
  ```

  `:cond` instead of `:if` — when the guards have different static messages (each
  guard = a clause `[predicate error-response]`, `:else` = apply). `:if`/`:cond`
  are **lazy** — the write (`_apply-*`) runs ONLY when validation passed.
- Tests: repoint the existing behavioral tests at a local helper that
  reproduces `parse→validate→apply` (a test analog of the graph).

### fn-def glue vs `src/` helper — which to pick for a seam

- The step is a pipeline of pure value transformations → **fn-def glue** (visible in the graph). ⬅ the goal.
- The step is stateful recursion / a mutable accumulator / a transaction with rollback →
  leave it in `src/` behind ONE base-fn (`_apply-*`).

## 5. ctx-threading

A `defbase` implicitly receives the symbol `ctx` (storage, graph-cache). A ctx-dependent
step becomes an ordinary base-fn that reads `ctx` in its body — and composes cleanly
into a fn-def. Example: `_load-graph-cached []` — a 0-arity base-fn, body
`(load-graph-entities ctx)`; in the fn-def it's bound via a ref-binding with no arguments.

## 6. Types, sync, and the JSONB roundtrip

Graphden type-checks ALL fn-defs at sync (topological order).
For new base-fns in `fns.edn`:

- `:args {:x {:type T :description "…"}}` — the type of each slot; `:return-type T`.
- A slot that accepts a READY value from a base-fn ref (not a callable) → NOT `:any`:
  `:any` = "don't touch", the executor won't force-deref the delay. Use `:jsonb`
  (or a concrete record). `:any` — only for an already-built Clojure fn,
  `:fn` — for an fn-graph under an HOF wrap.
- The name is checked for GLOBAL uniqueness, including `_`-private ones — grep
  `:name :foo` / `defbase foo` over `resources/packages/` before naming.

**Gotcha: the JSONB roundtrip keywordizes keys, but stringifies nested keyword values.**
Literals in a fn-def's `:value` / `:args` are stored as JSONB. On read, map keys
are keywordized, while keyword VALUES inside vectors/maps come back as STRINGS:
`[:p {:class "x"}]` → `["p" {:class "x"}]`, `[["ID" :id]]` → `[["ID" "id"]]`.
Consequences:

- `:const` with raw hiccup breaks (tag `:p` → `"p"`, hiccup2 expects a keyword) —
  build hiccup via the base-fn `:hiccup` (it keywordizes the tag);
- a base-fn that consumes keyword data from a JSONB source must keywordize
  at the boundary (`(keyword key-raw)`).

## 7. Workflow

Debug in the live nREPL (skill `graphden-repl`), `bb rebuild` — once at the end,
then `bb verify` (per-section ✓/✗) → endpoint smoke → `bb ci`.

Declarative sync **does not delete** rows that dropped out of `fns.edn` — they accumulate
in the dev DB. Deleted a fn-def → for a clean state (and a trustworthy
reachability audit) you need `bb deploy` (truncate + clean sync), not `bb rebuild`.

Commit per-stage (a checkpoint per stage) — but only when the user asks.

## 8. Anti-patterns

- **Decomposing dead code.** Sawing up a target with no callers —
  +N entities under code that never runs. §2 first.
- **Big-bang.** Sawing up a 1400-line namespace in a single pass — a guarantee
  of regressions. Only in stages, verifying each stage.
- **"I don't see seams" as an excuse not to decompose.** If you didn't find
  nameable seams (`parse` / `load` / `validate` / `transform` / `write` /
  `format` / `respond`) — that doesn't mean there are none, and it's certainly no grounds
  not to cut. It means: either the target really is from the narrow list §3.1–3.3
  (then leave it explicitly by that rule, not "because it's one algorithm"),
  or you're looking at it from the wrong angle — try decomposing by the domain
  concepts of the response / request, not by lines of code.
- **Chopping into `_step1/_step2/_step3` without semantic names —
  obfuscation, not decomposition.** If a name honestly reduces to "the first step
  does something, the second continues" — those aren't seams. Either find domain names,
  or it's §3.1–3.3 (by rule, not by feel).
- **A base-fn calling a base-fn in new code.** If step A needs step B —
  that's a graph edge (fn-def glue), not a call in Clojure.
- **A "convenient" wrapper over >1 call.** A new base-fn around `parse + validate` —
  the same violation (a), just smaller.
- **Silent rollback on a blocker.** If a seam turned out uncuttable or the target
  turned out dead — surface the finding and replan, don't roll back quietly.
- **Committing without being asked.**

## 9. Reachability audit — finding dead code

To find unused fn-defs across the whole graph:

1. `bb deploy` — a clean DB (otherwise accumulated junk skews the result).
2. `curl /api/graph/entities` — dump `fns` / `slots` / `fn-slots` / `bindings`
   / `list-items`.
3. Build the fn→fn edge graph: `parent-ids`; `base-fn-id` / `element-fn-id` /
   `return-type-fn-id`; the type of each own slot (`fn-slot`→`slot`→
   `type-fn-id`); the fn's bindings (`ref-fn-id`, `type-override-fn-id`) and their
   `binding-list-item.ref-fn-id`. This is the FULL set of reference mechanisms.
4. BFS from the roots: `web-server` + all fns of the `examples` package.
5. Unreachable **named composed fn-defs** — candidates for dead code
   (as the `/partials` rendering was). Unreachable **base-fns and type-rows** — these are
   the language vocabulary (primitives `div`/`first`, types `uuid`/`percent`); the app
   uses a subset — NOT dead code.
6. For suspects, grep the name over `resources/` — filter out matches in
   comments/descriptions/substrings before deleting.

## 10. Links

- `graphden-repl` — the REPL loop, verifying an impl without a rebuild. Use ALWAYS.
- `graphden-fn-design` — how to name new fns (`_`-private vs public), MI,
  namespace, `:const` wrappers. Apply at steps 3–4 of the recipe.
- CLAUDE.md § "Base Function Philosophy", `docs/PHILOSOPHY.md`,
  `docs/PACKAGES.md § Composition Best Practices` — the primary source of the rules.

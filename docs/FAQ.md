# FAQ — Common objections & answers

This document collects the sharp questions a skeptical engineer, reviewer,
or investor asks on first contact with Graphden, and the honest answer to
each. It is a living document: when a new objection lands, add it here
rather than re-arguing it from scratch.

Positioning context lives in [README.md](../README.md) ("Why this exists")
and [docs/PHILOSOPHY.md](PHILOSOPHY.md); this file is the rebuttal layer on
top of them.

---

## 1. "One person + AI produced ~100k LOC — is that impressive, or is it AI slop you don't understand?"

**Short answer:** Neither. Graphden's own construction is the first instance
of its own thesis, told honestly across the three tiers it defines
([PHILOSOPHY § three-tier ecosystem](PHILOSOPHY.md#the-three-tier-ecosystem)).
The volume proves nothing; the *coherence* proves the human supplied the
parts AI does not — judgment, architecture, review — and the app layer
already bootstraps onto the substrate.

**Detail — the engine is the text-authoring tax, paid once:**

- The engine (`executor` / `storage` / `types`) is Clojure *text*, authored
  by directing AI under heavy human review. That is deliberately the
  *painful* path: free-form text is the hostile target the project exists to
  replace, and building the engine on it is how the motivation was earned
  first-hand. Do not hide this — hiding it invites "so is your engine full of
  the AI slop you warn about?"
- The compensating discipline is what answers that: a layered
  storage-protocol architecture, a mutually-recursive type checker that
  survives **2,000+ tests** (`test/` holds ~2.2k `deftest`), an ADR for every
  load-bearing decision, 0 `TODO`/`FIXME` and 0 stray `println` left in
  `src`, public API funneled through `interface.clj`, and a rebase (not
  merge-soup) history — ~10 merge commits out of ~1.5k. AI supplies
  throughput; it does **not** supply a correct build-vs-buy ADR rejecting
  Dolt. The tax text imposes on machine-authored code was paid once, under
  review, to build the substrate that removes it for everyone above.

**Detail — the application layer is the bootstrap dividend:**

- Graphden's own application layer already runs as **fn-graphs on Graphden**
  — tier-2 composition over the base-fns, the exact tier the thesis says an
  AI can stand in for while a human reviews the graph diff:
  - The application root — the HTTP server itself — is the `:web-server`
    fn-def (`:parent :http-server`), not Clojure:
    [`resources/packages/app/server/fns.edn`](../resources/packages/app/server/fns.edn).
  - Its HTTP routes are fn-defs (`:health`, `:version`, `:editor`, …):
    [`resources/packages/app/routes/fns.edn`](../resources/packages/app/routes/fns.edn).
  - The editor's entire server side is ~540 fn-defs:
    [`resources/packages/app/editor/fns.edn`](../resources/packages/app/editor/fns.edn).
  - (Honest boundary: the *engine* below and the editor's *browser* JS are
    text; the application server composed on top is graph.)

**Framing note (do not deliver as "AI wrote it, be impressed"):** the claim
is that the engine paid the text tax once under review to earn the substrate,
and the app layer riding that substrate is the first small evidence it pays
off one tier up. Coherence is the proof, not line count.

**Strength:** Strong once split by tier (engine = tax paid once; app layer =
dividend). Self-defeating if flattened to "AI did it," which invites both
"then it's not impressive" and "then you don't understand it."

---

## 2. "You reinvented versioning / types / orchestration instead of using off-the-shelf tools (NIH)."

**Short answer:** Each build-vs-buy call was researched, and the reasoning
is specific, not reflexive. The strongest case is versioning.

**Detail — versioning/branching/merge on Postgres:**

- Datomic and XTDB are *different databases*. Adopting them is not "add a
  versioning library," it is a full storage migration.
- Dolt supports only the MySQL wire protocol — adopting it is a covert
  database swap as well.
- Graphden already runs on Postgres and **depends on Postgres-specific
  features that the versioning system is co-designed with**: row-level
  security (RLS) for multi-tenancy, recursive CTEs for graph traversal,
  advisory locks for singleton services, branch-scoped `LISTEN/NOTIFY` for
  cache invalidation. Migrating to a versioned DB would force
  re-implementing all of that on the new engine. Keeping Postgres and
  writing the branch model on top is the *cheaper and less risky* path, not
  the vain one. See
  [docs/adr/ADR-versioning-vs-offtheshelf.md](adr/ADR-versioning-vs-offtheshelf.md).

**Detail — type system:**

- Own it rather than minimize it. There is no off-the-shelf *gradual,
  effect-tracking* type system for a graph substrate; the requirement
  (per-slot types, propagated effect sets, secret-flow tracking, refinement
  narrowing) does not map onto an existing library.
- It is not built from nothing: malli supplies the schema primitives
  underneath. The novel layer is the graph-aware checker over them.

**Detail — fleet orchestration:**

- Deployment already targets Kubernetes; autoscaling uses KEDA, not a
  hand-rolled scaler. What *is* custom is the **cell-placement + rebalance
  controller** — because "which tenant cell lives on which pod" is a
  domain-specific placement problem k8s/KEDA do not model. The fair question
  here is not "why not Knative" but "was custom placement needed *yet*"
  (see objection 7 and
  [docs/FLEET_RFC.md](FLEET_RFC.md), which gates the un-shipped parts on
  evidence).

**Strength:** Strong for versioning (put the three-line
Datomic/XTDB/Dolt dismissal prominently in the ADR). Medium for types (own
the novelty, don't call it "not that custom"). The k8s/Knative framing in
the original critique was imprecise — concede that and narrow it to the
placement controller.

---

## 3. "So much bespoke dev tooling (merge-queue, dev-tour, drift-guards) — process over product."

**Short answer:** The tooling was cheap to build (also AI-produced, in
parallel), and each piece exists to make AI-directed development on this
codebase faster and safer. Low cost defuses the "wasted effort" version of
this objection entirely.

**Detail:**

- The merge-queue (`dev/wtq/`) serializes landing for *parallel* development
  streams; the dev-tour drift-guard keeps onboarding docs honest against the
  code they point at. These are force-multipliers for the exact development
  model Graphden runs on, not decoration.
- The one part of this objection that survives is different from cost:
  *tooling maturity is not market validation.* Building good process does
  not prove anyone wants the product. That concern is real and is answered
  by objection 5, not by this one.

**Strength:** Strong on the cost axis. It does **not** address market
validation — keep the two separate and don't let a good answer here paper
over objection 5.

---

## 4. "Bus factor is 1, and the paradigm is idiosyncratic — onboarding a second person is expensive."

**Short answer:** True that authorship is concentrated; the mitigants are
deliberate and partly already in place.

**Detail:**

- The bus-factor mitigant is **not** the monorepo layout — splitting repos
  would not reduce single-author risk. The real mitigants are: extensive
  docs + ADRs, 2.2k tests as executable spec, a symbol-anchored developer
  tour, and the fact that a *second* developer onboards the same way the
  first one built it — with AI over that documentation.
- The monorepo is a separate, defensible choice on its own merits: clear
  internal module boundaries (`executor` / `storage` / `versioning` /
  `types` / `crud` / `tenancy` / `fleet`), each understandable in isolation.
  Splitting into repos was considered and deferred, and satellite pieces
  that *did* warrant their own repos are already out (`graphden-mathx`,
  `graphden-examples`, `graphden-cloud`).

**Strength:** Half-answer as originally phrased (monorepo ≠ bus-factor fix).
Strong once re-centered on docs + tests + AI-onboarding + the already-split
satellites.

---

## 5. "Visual/graph programming is a graveyard — why does this one work?"

**Short answer:** Graphden is not "Scratch for everything." It sits on three
specific bets that the failed attempts did not have.

**Detail — the three differentiators:**

1. **We visualize Lisp, which is already ~AST — not control-flow symbols.**
   Scratch-style tools fail trying to render `if`/`for`/`while` as blocks: a
   forced metaphor over a fundamentally textual, imperative structure.
   Graphden visualizes a homoiconic, composition-first substrate where the
   graph *is* the natural shape of the program, not a costume on top of it.
   This is the sharpest, least-obvious point — lead with it.
2. **The diff is a graph, and it is editable.** Read-only "see your code as a
   graph" tools exist (e.g. code-as-graph visualizers). Graphden's unit of
   value is the *editable* graph plus a structural diff/merge of changes —
   review and authorship in the same surface, not a viewer bolted beside a
   text editor.
3. **The AI-era review bet.** As models write more code, humans read less of
   it, and reviewing prose diffs is tedious and error-prone. The wager is
   that reviewing a typed, effect-annotated, structurally-valid *graph* of a
   change is easier than reviewing text. This is stated as a **bet, not a
   proven fact** — it is the central hypothesis the product now needs to test
   in the market.

**Category context:** Tools like n8n validate that node-graph composition has
real demand — but they operate at coarse integration granularity. Graphden
composes fine-grained, *typed and effect-tracked* functions. The category is
alive; the wedge is the substrate + the review ergonomics above.

**Strength:** Strongest raw material of all seven, but currently a jumble.
Structure it as: category is validated → our wedge (Lisp≈AST, editable diff,
typed/effect) → the explicit unproven bet. The Lisp-is-already-AST line is
the best single sentence in the whole pitch.

---

## 6. "Some files are huge (`types/check.clj` ~2.3k lines) — poor decomposition."

**Short answer:** Splitting was attempted and rejected on structural
grounds, and the reason is now documented in the file's own docstring so it
is not re-litigated.

**Detail:**

- The semantic core is one **mutually-recursive strongly-connected
  component**: `effective-binding-type` ↔ `effective-ref-return` ↔
  `effective-ref-return-uncached`, with an outer loop through
  `base-fn-type-rule` → `bindings-info-for-rule` → back to
  `effective-binding-type`. The forward `declare` at the top of the rule
  section is the tell.
- The recursion is inherent to the problem: a ref's declared return type is
  resolved by re-firing its base-fn's `:return-type-rule`, and a rule can
  only fire once its args' types are known — the two are co-recursive.
- In Clojure a mutually-recursive SCC cannot be split across namespaces
  without a var/registry indirection seam introduced *only* to break the
  compile cycle — trading a long-but-linear read for a scattered one. The
  hard-to-understand part *is* the core; shaving leaf helpers (shape
  predicates, message formatting) leaves it whole and scatters a shared
  vocabulary. Net cognitive win: negative.
- Leaf-only extraction is the move that does pay off, and it is already done
  where it does (`types.check.literals`).

This was independently re-verified during review; the analysis is captured
in the `graphden.types.check` namespace docstring.

**Strength:** Strong, and now backed by a verified, in-repo record. This is a
falsifiable answer that survived being tested — the best kind.

---

## 7. "No load testing — performance is unproven."

**Short answer:** Correct: full load/soak testing has not been run yet, and
that is a deliberate sequencing choice, not an omission.

**Detail:**

- Distinguish two different things. **Microbenchmarks exist** — the executor
  hot path is measured (~µs-per-node range, within budget; see
  [docs/PERF_NOTES.md](PERF_NOTES.md)). **Load/soak testing under a realistic
  concurrent workload has not been done.**
- Deferring load testing is *coherent* with the project's stage: modeling a
  workload that does not yet exist would be exactly the "solving problems you
  don't have" trap. Load testing is scheduled to follow the first real
  usage, when there is a workload worth modeling.
- The honest boundary to hold: perf *claims* are limited to what the
  microbenchmarks actually measured. Do not let "we have hot-path numbers"
  drift into "we know it scales" — those are different statements.

**Strength:** Fine as deliberate sequencing. The only failure mode is
conflating the microbenchmarks you have with the load testing you don't —
keep them clearly separate.

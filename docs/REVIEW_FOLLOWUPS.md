# Review follow-ups (branch `review/followups`)

Working ledger for the five items raised in the 2026-07-11 skeptical
review. Kept on an isolated worktree so it doesn't collide with the
`feature/dynamic-fleet` work happening in parallel. Anything marked
**needs-window** requires the shared Docker container (`bb rebuild` +
REPL on :9099) and is queued for an exclusive window.

| # | Item | Status |
|---|------|--------|
| 1 | `free-arg-slot-map` per-request cost | **✅ FIXED + verified live (~1.5 s → 0.04 ms, 30 000×)** |
| 2 | Type-system: keep / cut / re-found | **✅ decision documented (below)** |
| 3 | Base-fn hidden-composition: mechanical guard | **✅ test written + passing (0 violations, 0 false-pos)** |
| 4 | Documentation (README + docs sprawl) | **✅ README rewritten; docs-split plan below** |
| 5 | Reinventing git: off-the-shelf options | **✅ spike findings below; live prototype = separate infra** |

Verified in the 2026-07-11 window (worktree `bb rebuild` on the shared
container, testcontainer suites): all changes green. Also done: untracked
the 93 committed `node_modules` files (`755c2bbd`, on
`feature/dynamic-fleet`).

**Lint note (pre-existing, NOT mine):** a full `bb check` on this branch
reports ~10 cljstyle warnings + splint findings elsewhere in the tree.
They pre-date this work (inherited from `feature/dynamic-fleet` HEAD) and
are left untouched to avoid merge noise on the neighbour's branch. My six
changed/added files are clj-kondo / cljstyle / splint clean.

---

## 1 — `free-arg-slot-map` is the real `/api/execute` latency

**The headline finding, measured live.** The executor is ~1 µs/node, but
that benchmarks `execute` by fn-id. The path a user hits (`apply-execute`
→ `free-arg-slot-map`) costs **1.3–1.9 s per request**, uncached, because
it walks the fn's subgraph through the versioned-storage stack every
time. Full write-up, numbers, and the memoization fix:
[`adr/ADR-free-arg-slot-map-perf.md`](adr/ADR-free-arg-slot-map-perf.md).

Code (this branch):

- `src/graphden/crud/fn_execution/free_arg_cache.clj` — new leaf-ns memo.
- `src/graphden/crud/fn_execution/lookup.clj` — `free-arg-slot-map` stays
  PURE; new `free-arg-slot-map-cached` for the hot path.
- `src/graphden/crud/fn_execution.clj` — `apply-execute` uses the cached
  variant.
- `src/graphden/executor/context.clj` — clear on `invalidate-graph-cache!`.
- `test/graphden/crud/fn_execution/free_arg_cache_test.clj` — unit test.

**Verified live (2026-07-11):** warm ~0.04 ms (was ~1.5 s), output
identical to baseline (`cached=pure? true`), invalidation clears on both
arities, `72 tests 0 failures`. The window caught a real bug — the first
version cached the pure fn and broke `closure-capture` (direct callers
mutate-without-invalidate); fixed by the pure/cached split. Full results
and the caught-bug write-up in the ADR.

### 1b (follow-on, not this pass): unify the two free-arg walkers

There are two implementations of "compute a fn's free args":
`lookup/free-args-via` (CRUD path) and `registry/deep-free-ext-*`
(executor path, already in-memory off `:graph-cache`). The memo above
makes the slow one cheap without touching semantics. The *deeper* fix is
to delete the versioned BFS entirely and source from `:graph-cache` — but
only after proving the two walkers agree (closure-capture, HOF call-site
subtraction, `value-present`). That's a DRY win and removes even the
first-post-edit cost; deferred until it has its own test pass.

---

## 2 — Type system: decision

**Context.** The checker is ~2,300 lines (~6,900 with helpers).
~60–65% is essential type theory (subtype/unify/records/refinements —
the actual product value: "field typo caught at save time"). ~35–40% is
accidental complexity that exists ONLY to reconcile the data model's
**global slot identity** with the type system's need for **per-flow
identity** (Pass 2/3 caller-narrowing, per-use-site anon-naming
+30% registry bloat, hand-written `:type T` assertions, the hard-gate
sweep). `docs/TYPE_SYSTEM_DECISIONS.md` already diagnoses this and even
sketches the clean fix (slot-id in the registry key) — then chose the
pile of workarounds over the foundational change.

**Decision: pursue (A) now, keep (B) as the option; do NOT stay in the
current middle.**

- **(A) Cut to a best-effort / gradual checker (~800–1,200 lines).** Drop
  *soundness* as a goal. Keep `classify-literal`, `subtype?`, the
  structural record rules. Treat an un-narrowable slot as `:any`: show
  the hint you can compute, don't flag what you can't prove. Deletes
  essentially all of `narrowing.clj`, the α' plumbing, anon-splitting,
  the `:type T` ritual, and the Phase-E gate. **This is the recommended
  first move** — it removes the maintenance-risk / bus-factor-1 surface
  immediately, and it matches the actual product goal (hints +
  self-doc + catch-obvious-mistakes), which does NOT require
  zero-false-positive soundness.
- **(B) Re-found the slot model: per-fn slot instances.** Each fn-def's
  use of a slot becomes its own identity. Types are then naturally
  call-context-local; the entire reason Pass 2/3 exists disappears, the
  anon-naming hack becomes unnecessary, and most `:type T` assertions
  collapse — *while keeping soundness*. Bigger change (touches storage +
  runtime), but with no clients the window is open. Do this ONLY if
  soundness becomes a real product promise.
- **Do NOT keep the status quo** ("sound but fighting the foundation") —
  it's the worst of both: maximum complexity AND still needs hand-holding.

**Why it's safe to move now:** no external clients, so the type surface
can change without migration cost. This is the single best time to make
this call.

**Not doing here:** the actual refactor (A or B) is a multi-day effort
and its own branch — this ledger records the decision and rationale, per
the "plan-first for large refactors" rule.

---

## 3 — Base-fn hidden composition: mechanical guard

Replaces "please run the skill" with a red build.
`test/graphden/packages/base_fn_isolation_test.clj` statically scans
every `impls.clj`: a `defbase` body that calls another `defbase`
symbol (same-ns shadowing, or a qualified cross-ns base-fn) fails the
test. Zero clojure.core false positives (a base-fn `map` shadows
`clojure.core/map` in its own ns). Direct calls only — composition
hidden inside a private `defn-` helper stays with the skill's human
review (documented in the test's ns docstring).

**First run (2026-07-11): PASSED** — 0 violations across all `impls.clj`,
0 false positives. The existing base-fn layer is clean, and the heuristic
didn't misfire on any real source shape. Ready to wire into `bb check` /
CI as a standing gate.

**Optional hardening (deferred):** a dynamic thread-local guard in the
test profile — wrap each registered base-fn impl so re-entry into
another registered impl throws. Zero false positives, but only catches
what the suite executes. The static test covers all base-fns
regardless of coverage, so it's the primary gate.

---

## 4 — Documentation

**Problem:** README is 242 lines; `docs/` is 46 files / ~21k lines. The
storefront is under-built while the interior is over-documented, and the
interior mixes "current truth" with "diary" (sweep ledgers, rejected
ADRs).

**Plan:**

1. **Rewrite README** as the product pitch: what/why in the first
   screen, a 60-second "here's the idea" + demo, then quickstart. (The
   product-hypothesis defense from the review is the raw material.)
2. **One-page ARCHITECTURE-for-newcomers** — the CLAUDE.md doc-map is
   great for contributors but there's no single on-ramp.
3. **Split current-truth from history.** Move ledger/ADR-of-rejected-
   paths docs (`TYPE_CHECK_BACKLOG`, sweep ledgers) under `docs/history/`
   or `docs/adr/` so top-level `docs/` reads as the live spec. (This new
   `docs/adr/` dir is the start of that convention.)
4. **Mark proposal vs shipped** everywhere (FLEET_RFC is a proposal
   living next to shipped docs — partly labelled already).

**README rewrite: DONE** (this branch). New pitch leads with the
substrate/why-now thesis, fixes drift (removed the auto-parallelization
"goal" that FLEET_RFC lists as a non-goal; corrected stale module tables
— `web/http-kit/` / `executor/base-fns/` / storage `AGE` / "50+ base
functions" → the real `src/graphden/` layout + 253 base-fns), keeps the
example + quickstart. The `docs/adr/` dir started here is the beginning of
the current-truth / history split (items 3–4). Remaining: the one-page
newcomer ARCHITECTURE and the `docs/history/` moves — low-risk, queued.

---

## 5 — Reinventing git: off-the-shelf options (spike)

The project hand-rolls branch/diff/merge/conflict + `branch-local?` +
per-branch propagation on top of Postgres (`VersionedStorage` decorator +
soft-delete + a merge engine). Three existing families were evaluated:

- **Dolt** — "git for SQL tables": branch/merge/diff on relational data.
  The graph IS relational rows, so Dolt could provide table-level
  branching **for free**, potentially replacing the `VersionedStorage`
  decorator + soft-delete + row-versioning. **Best candidate for a
  spike.**
- **TerminusDB** — literally "git for graph data" (RDF), with
  branch/merge/diff/clone built in. Closest *conceptual* match, but its
  document/RDF model would mean fighting *its* data model instead of the
  slot/binding one.
- **Datomic** — native immutability + time-travel (`as-of`); branches are
  harder but the temporal substrate is built-in.

**Honest boundary:** storage-level branching is a solved problem
(Dolt/TerminusDB). The part that is genuinely yours and NOT off-the-shelf
is **semantic merge of typed fn-graphs** — conflict detection,
`branch-local?` monotonic-OR, per-branch version propagation. No tool
does that; it's inherent to the domain, so a custom layer is justified.

**Recommendation:** a time-boxed Dolt spike — model the entity tables in
Dolt, see how much of `versioning/storage` + soft-delete + merge-plumbing
it deletes, keep the semantic-merge layer on top. Decide by "lines
deleted vs operational surface added", not by novelty. Not started;
needs its own scratch environment (does NOT touch the shared container).

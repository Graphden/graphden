# Performance budgets

How this repo notices that something got slower — and why it counts events
instead of timing them.

> Counting note: `pg_catalog.pg_type` statements are excluded from every
> scenario's count — they are the PgJDBC driver's once-per-connection
> type-cache introspection, not application round trips, and which
> scenario absorbed them used to depend on kaocha's random test order
> (the `graph-layout :max 0` budget went red by seed).

## The argument

Every performance regression this project has actually shipped a fix for was a
**count that moved**, not a clock that slipped:

| Commit | What was wrong |
|--------|----------------|
| `9d66adc0` | a write that changed no closure dropped the compiled registry |
| `0b74f1dc` | a `:service` write full-cleared the compiled registry |
| `d925ec35` | a divergent branch's ctx was full-rebuilt, not delta-compiled |
| `9c1b882a` | a write dropped the graph cache instead of splicing it |
| `6840f542` | the type-check sweep ran once per namespace, not once per JVM |
| list-secrets | scanned the graph instead of filtering in SQL (~9x) |

Meanwhile both attempts to chase a *timing* win failed. `docs/PERF_NOTES.md`
records one point-fix that measured **"~5% — inside the run's std-dev, i.e.
noise"**, and a `snake->kw` memoisation that made the suite **slower** and was
reverted.

So the gate reads counts. A count is machine-independent by construction: `1` is
`1` on a laptop, on `ubuntu-latest`, and at the load average 75 that
`scripts/ci.clj` measured the last time two runs shared this box. It needs no
tolerance band, no normalisation, and it cannot flake on a noisy neighbour.

Wall-clock is still recorded — it is just never gated. See
[Where the time went](#where-the-time-went).

## Using it

```bash
bb test-unit     # → perf/runs/unit.edn
bb test-perf     # → perf/runs/perf.edn  (SQL round trips per operation)
bb perf          # compare both against perf/budgets.edn
bb perf-update   # accept the current readings, then commit the diff
```

`bb ci` runs all three: `perf` sits in the `:post-test` group, a third wave that
runs after the suites because it grades what they wrote.

The loop is the same one `bb visual` / `bb visual-update` established:
`perf/budgets.edn` is a committed reference set, reviewed in the diff, refreshed
only by an explicit human commit. `perf/runs/` is gitignored — those are this
host, this moment.

## What a failure means

```
✗ :registry/delta-fell-back-to-rebuild     3 / max 0
    A caller named its changed fn-ids and STILL paid for a full graph
    recompile: delta-recompile! hit a cold reverse-deps index and silently
    called rebuild!. ...
```

It did **not** fail because the box was busy — that is the point of counting. So
either the change is wrong, or the count is legitimately new. If it is
legitimate, run `bb perf-update` and say why in the commit message. `perf-update`
prints every budget it **raises**, because lowering one is a win being recorded
and raising one is a regression being accepted, and those must not look alike.

## What belongs in a budget

Only counters that are **invariant to how many tests exist**.

`bb perf` grades the whole unit suite, so a counter that ticks once per write —
`:registry/delta-recompile` (346), `:registry/rebuild` (107) — grows every time
somebody adds a test. Gating it would fail honest PRs, and the fix
(`bb perf-update`) would train everyone to re-baseline without reading. That is
how a gate becomes a rubber stamp. Those counters are reported as trend data
instead; promoting one later is a one-line edit.

The invariants that are gated today:

| Counter | Max | Catches |
|---------|-----|---------|
| `:fixture/container-boot` | 1 | a caller bypassing the shared container (~3 s each) |
| `:fixture/golden-bootstrap` | 3 | a namespace inventing a new package set (~14 s each); three are sanctioned — `[core web app]`, `[core web app registry mcp]` (registry/export/mcp suites), and the cheap `[core]` (~3 s, pure-core NSes) |
| `:fixture/type-check-sweep` | 0 | the ~24 s sweep leaking into the unit suite (was ~40 s before the 2026-08-17 `effective-ref-return` memoization) |
| `:registry/delta-fell-back-to-rebuild` | 0 | a delta silently becoming a full rebuild |
| `:sql/graph-entities-tree` | 1 | the sidebar's first paint reading rows it doesn't paint |
| `:sql/create-fn` | 20 | the write path re-reading what it already had |

## How the SQL is counted

At the database, via `pg_stat_statements` — not by instrumenting the storage
layer, which has no single seam (`jdbc/execute!` is called from ~30 sites across
`storage/postgres/`). The extension sees every statement by construction and
cannot drift out of sync with the code the way a hand-placed counter can.

It also counts the right thing: statements are **normalised** before counting, so
an N+1 arrives pre-diagnosed as one row reading `calls=200` — you get the
offending statement text, not 200 lines to read.

Three properties are load-bearing, and all three were found by watching a count
flip rather than by reasoning:

- **The dbid filter.** `pg_stat_statements` is cluster-wide and the suite runs
  namespaces in parallel with a database each. A bare `pg_stat_statements_reset()`
  would erase a sibling's in-flight measurement.
- **The warm-up.** `measure` runs the scenario once, discards it, then measures
  the second call. Cold-cache cost is paid once per process; the number that says
  whether an endpoint is expensive is what it costs on the millionth request.
- **`--no-randomize`** on the `:perf` suite (owned by the `bb test-perf` task).
  The scenarios share one `:once` graph, so a write scenario leaves the compiled
  registry different from how it found it and the next read either pays for the
  recompile or doesn't. Under kaocha's default randomisation this measured
  `create-fn=18 / tree=3` on one run and `20 / 1` on the next — the same total
  work (21), redistributed by whoever went first. Warm-up cannot fix that: it
  absorbs a scenario's own cold start, not its neighbour's edit.

  A flag rather than config, because **kaocha has no per-suite randomize
  toggle**. `kaocha.plugin.randomize` reads `:kaocha.plugin.randomize/randomize?`
  (or the `:randomize?` shorthand `kaocha.config/normalize` expands) from the
  config **root** — `normalize-test-suite` never renames it onto a suite map, and
  `post-load` recurses the test-plan without reading any per-testable key. So the
  only config-shaped answer would kill randomisation for every suite, and only
  `:perf` needs determinism.

  The first attempt set `:kaocha/randomize? false` on the suite. That is not a
  kaocha key at any level — zero occurrences in the jar — so it rode along as an
  inert map entry: unread, and unrejected, because `s/keys` is open and no spec
  claims it. Wrong name *and* wrong level; either alone would have been enough,
  and neither produced a warning. Worth knowing generally: a typo'd kaocha config
  key fails silently and looks exactly like a key that didn't work.

A budget on an irreproducible count is a flake with a rationale, so nothing gets
gated until it has been measured identical twice in a row.

A scenario must also **assert that its operation succeeded**. One that quietly
400s does no work, measures zero queries, and sails under any budget — a perf
suite reporting "free" for a broken endpoint is worse than none.

## The frontend

```bash
bb wt up                                            # isolated stack, prints a port
GRAPHDEN_URL=http://localhost:<port> bb perf-frontend
```

Same argument, browser side: what the editor **asks for**, not how long the paint
took. "The first paint makes 7 API calls and pulls 5.3 MB" is 7 and 5.3 MB on any
machine. And counts are what regressed — `/api/graph/entities` was 4.5 MB before
it was scoped, and the entire lazy-fn-index work exists because the editor used
to mirror every fn in the graph. A request count and a byte count would have
caught both on the day.

Nothing is instrumented in the app: Playwright sees every request by
construction, which is the browser-side equivalent of reading
`pg_stat_statements` instead of wrapping the JDBC calls.

Current reading (one stack; see the caveat below):

| Scenario | API calls | KB on the wire | KB decoded | DOM nodes |
|----------|-----------|-----|-----|-----------|
| `load-web-server` | 11 | 903 | 7809 | 711 |
| `sidebar-expand-namespace` | 13 | 904 | 7817 | 1065 |

Expanding one namespace costs exactly **two** extra `?scope=namespace` requests.
That difference *is* the lazy fn-index. If the tree ever goes back to shipping
every fn up front, the expand count collapses toward the load count and the
payload follows it up.

**Wire and decoded are different numbers and only one of them is traffic.** The
server gzips and every browser asks it to, so `/api/types` is 388 KB on the wire
and 2454 KB after decoding — 6x apart. An earlier version of this table quoted
only the decoded figure and called it what the first paint "pulls", which
overstated the network cost by ~8x. Both are kept now because both are real
costs: the wire number is what the network carries, the decoded number is what
the JS engine parses and holds.

Where it goes, decoded:

| Response | KB |
|----------|-----|
| `?scope=subtree&root-id=<web-server>` | 5325 |
| **`/api/types`** | **2454** |
| the other nine, together | ~30 |

`/api/types` is a **full snapshot of the rich-type registry** — every fn-def's
`:return` and `:args`, ~2700 of them — and the editor holds all of it in one
global (`richTypes`). That is precisely the full mirror the lazy-fn-index work
removed for `graphData.fns`; this endpoint was missed. At 388 KB gzipped it is
not a network crisis, but it was 2.4 MB parsed and retained to render type chips
for the handful of fns actually on screen.

**Scoped (finding K), the way `/api/graph/entities` was.** The bulk payload now
drops the per-entry fields nothing paints from: `:resolved-bindings` (~36 %, read
only server-side now — the return-type-rule popover became a server partial, so
the former `GET /api/types?fn=<name>` per-fn backfill branch was REMOVED with its
one consumer), `:description` (~12 %, the editor sources descriptions from graph
rows, not the type registry), `:primary-parent` (its one client reader, the
strips' rule-owner walk, moved server-side into the layout strip facts), and
`:source-file` / `:source-line` / `:tags` / `:arg-effects` / `:call-time-effects`
(zero editor reads). Measured on a live stack at the time of the original cut:
**2454 KB → 1047 KB decoded (−57 %), 388 KB → 119 KB gzipped (−69 %)**, with
`:return` / `:args` / `:effects` / `:slot-types` / `:nav-types` kept for the bulk
chip/strip paint. `all-rich-types` stays FULL (the internal
`/api/types/candidates` source-of-truth); the lean-bulk shaping lives in
`api-rich-types`. This is a payload/network/parse win — it is
**not** the e2e-flake fix (see "What fills the heap" below).

**These counts are NOT gated**, and cannot be as they stand: they are exact and
stable within a stack (three runs, identical) but their input is not pinned. The
same scenario read 10 requests on one stack and 11 on another — the extra an
`/api/secrets` fetch that exists only where secrets are seeded, because each
worktree has its own DB volume with its own seed. `bb perf-frontend` prints them;
`perf/budgets.edn` deliberately has no `:frontend` suite.

> **A caution, learned here.** This scenario first reported *7* calls with
> `?scope=search` and `/api/graph/layout` duplicated, and that duplicate was
> briefly written up as an editor defect. It was not. `newContext` ends with
> `page.goto(BASE + '/')`, so the harness was navigating **by hash onto an
> already-booting editor**: the boot's five calls happened before the listener
> attached, and the hashchange raced `initGraph`'s own hash-read so both resolved
> the same name. The fix is one `page.goto('about:blank')`. A measurement harness
> is code, and it gets its facts wrong the same way any other code does — the
> only reason this was caught is that an independent probe disagreed with it.

**This one is not in GitHub Actions**, for the same reason `bb visual` isn't: it
needs a built, running editor. It is declared in `:optional-suites`, so `bb perf`
grades it when its report exists and **says out loud** when it skips — a budget
silently skipped for want of a report reads as a pass, and that is the failure
mode this whole file exists to avoid.

## Asking a running executor what it just did

`/metrics` carries the counters under `counters`, next to the JVM snapshot:

```json
{"jvm": {…}, "memory": {…}, "counters": {"registry/rebuild": 1, "compile/all-miss": 1}}
```

Memory and thread counts say how *loaded* an executor is. These say what it
*did* — a registry full-clear, a delta that silently became a rebuild. Those are
the events that make some **later** request slow, and they used to leave nothing
behind to read, which is why diagnosing one always started from a stack trace and
a guess, minutes after the fact.

`run-edit-tests.sh` samples them around each e2e file and prints the delta:

```
[ 13s  executor=998.1MiB]  registry/delta-recompile=8
```

### What that measurement settled

The e2e suite flakes in **8 of 14** gate runs — always "failed once, passed on
retry", always a different innocent file (`edit-execute` ×3, `edit-fn-picker`,
`edit-type-edit-variant`, `edit-branch-lifecycle`, `edit-fn-create`,
`edit-edge-rename`). `scoped-fn-index` needed six gate runs to land. Every
landing is a coin flip at ~40 minutes a throw.

The obvious suspect was a compiled-registry full-clear: it makes the next request
rebuild the whole graph (49.8 s at 4137 fns), which would time a test out at 10 s
and let the retry through ten seconds later. That is the exact shape of the
symptom.

**It is wrong.** Across all 56 files: `registry/invalidate-full` = **0**,
`registry/delta-fell-back-to-rebuild` = **0**. There are no full-clears in this
suite at all — the delta-invalidation fixes hold — so no flake here can be
explained by one. The suite also ran **clean** (0 flakes, 0 leaks) on an isolated
stack under low load.

Two other suspects die cheaply, from logs that already existed:

- **Not restarts or OOM.** The `OutOfMemory` hit in every flaked gate log is the
  string `-XX:+ExitOnOutOfMemoryError` in the Dockerfile, and it appears in the
  *clean* runs too.
- **Not entity leakage.** The `LEAKED` detector is clean in all eight flaked
  runs. That class was found and closed already, and its comment in
  `run-edit-tests.sh` is worth reading — it describes this exact trap.

What is left is host load — and that was chased to a root cause, 2026-07-17.

**The flake is a G1 compaction Full-GC pause.** A SIGQUIT thread dump taken
during a slow op shows NO thread executing graphden code — every request handler
is suspended, because the JVM is in a stop-the-world Full GC. A wait that outlives
the pause times out; the retry, landing outside a pause, passes. That is exactly
"a random file flakes, green on retry".

**What fills the heap.** The executor's live set is only ~250 MB, but it runs at
~1.2 GB median. The large API responses — `?scope=subtree` up to 4.5 MB,
`?scope=index` 1.6 MB, `/api/types` 2.4 MB — are G1 **humongous** objects (larger
than half a 1 MB region); 756 humongous allocations at idle alone, from the
reconciler's periodic graph reads. They fragment the heap into compaction Full
GCs. The pressure is **aggregate** — the sum of many normal-sized-but-humongous
responses across the suite — not one dominant endpoint.

**The flake is NOT a single-endpoint over-fetch (measured 2026-07-16).** An
earlier version of this section claimed "the e2e flake and finding K are the same
bug" and pointed at `/api/types`. Direct measurement refuted it, so nobody
re-attempts scoping `/api/types` as the flake fix:

- `/api/types` is **~0.1 %** of bytes served during an editor load — scoping it
  removes 0.1 % of heap pressure, not the flake.
- The "441 K wrap calls / 463 GB" figure that made it look dominant was a **probe
  artifact**: `process-response` is invoked **52–126× per single HTTP request**
  (the encode-wrap graph re-enters per composed step), and the probe summed bytes
  once per invocation. A page load is **5** browser requests, not thousands.
- Those 52–126 re-invocations are cheap **call-cache hits**, not real work:
  `?scope=index` returns in 0.21 s, far under the ~1.7 s that 57× full
  re-evaluation would cost. So no single response's allocation dominates, and
  there is no clean "scope this one endpoint" flake fix — the 16 MB region flag
  below is the mitigation that actually moves the needle.

The wrap-chain multi-invocation is real but cheap; whether it is worth
restructuring is tracked separately (it is an efficiency question on the hot
path, not the flake driver).

**Measured, so nobody re-tries them as the fix:**

| tried | result |
|-------|--------|
| 2 GB → 3 GB heap | flake unchanged — it is allocation-rate, not heap size |
| `G1HeapRegionSize=16m` | idle humongous 756 → 0, flake ~11 → ~5 on a loaded box; 5–7 still flaked at the gate's 3 GB config. **Shipped as a partial mitigation** — enough to keep the suite landable, not an elimination |
| `InitiatingHeapOccupancyPercent=30` | no heap-median drop — not shipped |
| (already in the Dockerfile) more heap, `G1PeriodicGCInterval`, ZGC | all rejected there for the footprint problem; none touch this |

Two flags ship in the Dockerfile: `-Xlog:gc` (the whole diagnosis took a session
because there was zero GC observability — now it is one `docker logs` grep) and
`-XX:G1HeapRegionSize=16m` (the partial mitigation above, labelled as such so it
is never read as the fix). A **complete** fix would cut the aggregate humongous-
allocation rate — no single endpoint carries it, so it is broad allocation
reduction, not one response to scope. Until then, `run-edit-tests.sh`'s retry
masks the residual, and the gate counts the masked flake as a failure. (Finding K
— `/api/types` over-fetch — is a real payload/network win worth doing on its own
merits, but it is **not** this flake's fix; see the measurement above.)

Eight hypotheses were ruled out by measurement getting here (registry full-clear,
restarts, OOM, entity leaks, http-kit thread starvation — it uses virtual threads,
there is no 4-worker pool — CPU saturation, connection-pool exhaustion, and heap
size). The instrument that finally cracked it: `console.error` + per-op timing in
`edit-test-helpers.js`, which turned "a wait timed out" into "the update took 21 s
under a full heap".

## The one measurable win

`--focus` on a single 33 ms pure-logic unit test cost **8.5 s**, with
`:fixture/container-boot` = 1 and `:fixture/golden-bootstrap` = 1 — a PostgreSQL
container and a ~14 s golden bootstrap, for a test that touches no database.
`:kaocha.plugin/shared-container`'s `pre-run` did both unconditionally, on every
kaocha invocation.

The eager work is right for the full suite: it keeps the sync off the parallel
critical path. It cannot help a single-namespace run — there is no such path to
keep it off, and a namespace that genuinely needs the container or the golden
pays the identical cost lazily (`get-container` and `ensure-golden!` are both
lazy and JVM-wide idempotent). So `pre-run` — which receives the **test-plan**,
and therefore knows — now skips both when the plan holds one namespace.

```text
kaocha wall, --focus one 33 ms test:   8510 ms  ->  116 ms   (73x)
full unit suite:  1539 tests green, container-boot 1, golden-bootstrap 1,
                  229 s fixture — unchanged, inside the band
```

That is the only unambiguous, large, reproducible win in this whole effort, and
the reason is worth keeping: it changes **only the case where the optimisation
cannot possibly help**, and leaves the case it was written for untouched. Every
other plausible fix attempted here — memoising a hot fn, coalescing the compile
dogpile, growing the compile cache — measured as noise or worse.

## The trend — wall-clock that survives leaving the machine

`bb perf` also prints a `trend` section, and it **never fails the run**.

`docs/PERF_NOTES.md` lists `?scope=tree` at ~15 ms. That number cannot be a
baseline: it describes the box it was measured on. So `graphden.perf.calibrate`
measures a **reference workload in the same run, against the same pool** — one
empty `SELECT 1` round trip — and reports each scenario as a ratio to it. "This
endpoint costs 22 round trips" is a statement about the code; a slow box slows the
reference too.

There are two references, not one. Normalising a database-bound operation by a
CPU loop would measure the ratio of the host's disk to its ALU — a fact about the
machine. The API scenarios are round-trip-bound, so `:db-units` is the honest
one; `:cpu-units` exists for anything that isn't.

Read together with the counts, this says things neither can say alone:

| Scenario | Queries | Round-trip-equivalents of time |
|----------|---------|-------------------------------|
| `graph-entities-tree` | 1 | **29.26** |
| `create-fn` | 20 | **2009.28** |

Creating one `:fn` issues 20 queries and costs ~two thousand round trips' worth
of time. The time is not in the SQL — it is in the compile, exactly as PERF_NOTES
describes ("477 ms of its 918 ms"). The count says the database is fine; the
trend says the work is elsewhere.

**The honest limit:** normalisation narrows the noise, it does not remove it. It
cannot see a neighbour that steals the CPU during the scenario but not during the
calibration. The band is 2x, and that is not conservatism — inside it, the number
means nothing. Expect this to catch "twice as slow", never "10% slower". That is
why it reports and the counts gate.

## Where the time went

`kaocha.plugin/perf` measures nothing itself. `:kaocha.plugin/profiling` already
stamps a duration on every testable, so

```
ns-duration − Σ(child var durations) ≈ the :once fixture cost
```

That is the "~51 s of fixture against ~0.14 s of assertions" number `tests.edn`
quotes — now computed per namespace on every run, rather than by hand, once.

These durations are reported and **never gated**. They are not comparable across
machines, nor across runs on this one.

First full reading of the unit suite (158 namespaces):

- **237 s fixture vs 306 s assertions — about 1:1**, not the 360:1 the received
  wisdom describes. That ratio was an integration-suite number; the unit suite is
  not fixture-dominated.
- **Three namespaces own ~98% of the fixture cost**: `effect-gating-test`
  (79.1 s fixture / 0.1 s tests), `packages.export-test` (77.7 / 16.5),
  `packages.registry-test` (75.7 / 61.0). The cost is concentrated, not smeared —
  which means it is attackable.
- `:registry/rebuild` fires **107 times**, against one golden bootstrap.

Recording it is the point: those are the first per-namespace fixture numbers this
suite has ever kept. What came of chasing them is worth writing down too, because
half of it was wrong.

**Confirmed and fixed — but for correctness, not speed.** The three expensive
namespaces all shared **one database**. `bootstrap-crud-graph-from-golden!`'s
0-arity read `(ns-name *ns*)` inside the fixture body, which kaocha runs on a
worker thread where `*ns*` is `user` — measured, not inferred:

```
at-load        = graphden.scratch.ns-probe-test
at-fixture-run = user
```

All 26 callers asked for a database named `user`, and the idempotency guard
handed every one of them the first caller's database. That is `registry-test`
writing fn rows `export-test` reads, under 8-way parallelism, in randomised
order — a race held off by luck. Fixed by capturing the namespace at
macroexpansion (`test_setup.clj`), and by making the template part of a
database's identity (`shared_container.clj`), since "empty database for this NS"
and "clone of this golden for this NS" are two different databases that were
colliding on one name. `:fixture/ns-db-clone` went 1 → 3, which is the proof.

**Not confirmed: that any of this was the ~232 s.** The theory was a compile
dogpile — three threads missing one cold cache key and each compiling the same
~2600-fn graph, with 79.1/77.7/75.7 s "converging to within 4%" as the
fingerprint. `compile-all` really was check-then-act and now coalesces via a
cached delay. It changed nothing measurable: the three read 77.2/74.6/74.5 s
afterwards, and suite fixture totals across four runs went 237 / 245 / 258 /
231 s — one band, no signal. **The prediction of ~150 s saved did not happen,
and the convergence was suggestive, not evidence.**

What the instrument did find, by counting the work instead of the ask:
`:compile/all-miss` **101** against `:compile/all-hit` **10** — a 7% hit rate on
a cache whose whole job is to be hit, with `compile-all-cache-max-size` at **2**
against 8 parallel threads. That looked like a cache sized into uselessness.

**It was tested, and it is not.** Size 4 versus size 2 over the unit suite:

| | misses | hits | suite fixture | the 3 golden NSes |
|---|---|---|---|---|
| size 2 | 101 | 10 | 239 s | 78.8 / 77.3 s |
| size 4 | 100 | 11 | 214 s | 69.3 / 68.9 s |

One fewer miss, both totals inside the run-to-run band. Eviction is not what
those namespaces wait on. The arithmetic that does fit: a ~2600-fn compile costs
~55-60 s, `compile-all`'s delay already coalesces all three onto ONE of them, and
the other two **block** on it — so each still reads ~70 s (14 s golden bootstrap +
~60 s compile) while only one compile runs. A bigger cache cannot shorten a queue
for work that has to happen once.

So the cache is exonerated, and the honest remaining target is the compile
itself: ~60 s for ~2600 fns, needed by three namespaces. Nothing here makes that
cheaper. Two things were corrected on the way out: the cache called itself an
LRU and has always been a FIFO (a hit never promotes), and that is now what it
says — the behaviour was left alone precisely because the measurement gives no
reason to change it.

Note none of this was visible until a counter sat on the **compile** rather than
on `rebuild!`, whose 107 reads identically before and after every change
described here.

## Adding a scenario

1. Add a `deftest ^:perf` to `test/graphden/perf/scenarios_test.clj`.
2. Wrap the operation in `psql/record!` with a `:sql/…` event key.
3. Assert the operation succeeded, and that `(pos? queries)`.
4. `bb test-perf`, then `bb perf-update`, then write a real `:why` — it is what
   prints when the budget fails, for someone with no context.

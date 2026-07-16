# Performance budgets

How this repo notices that something got slower — and why it counts events
instead of timing them.

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
| `:fixture/golden-bootstrap` | 1 | a namespace inventing a new package set (~14 s) |
| `:fixture/type-check-sweep` | 0 | the ~40 s sweep leaking into the unit suite |
| `:registry/delta-fell-back-to-rebuild` | 0 | a delta silently becoming a full rebuild |
| `:sql/graph-entities-tree` | 3 | the sidebar's first paint reading rows it doesn't paint |
| `:sql/create-fn` | 18 | the write path re-reading what it already had |

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

  Note the `:kaocha/randomize? false` **config key does not take effect here** —
  it was tried, and the counts kept flipping. The CLI flag does. That is why the
  flag lives in the bb task and no dead key sits in `tests.edn` claiming
  otherwise.

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

Current reading:

| Scenario | API calls | KB | DOM nodes |
|----------|-----------|-----|-----------|
| `load-web-server` | 7 | 5334 | 718 |
| `sidebar-expand-namespace` | 9 | 5338 | 1072 |

Expanding one namespace costs exactly **two** extra `?scope=namespace` requests
and ~4 KB. That difference *is* the lazy fn-index. If the tree ever goes back to
shipping every fn up front, the expand count collapses toward the load count and
the load payload follows it up — a regression no other check in this repo would
notice.

The first paint also fires `?scope=search` **twice** and `/api/graph/layout`
**twice**. Recorded, not fixed: the budget of 7 is what it costs today, and the
duplicate is now a visible number rather than a thing nobody had counted.

**This one is not in GitHub Actions**, for the same reason `bb visual` isn't: it
needs a built, running editor. It is declared in `:optional-suites`, so `bb perf`
grades it when its report exists and **says out loud** when it skips — a budget
silently skipped for want of a report reads as a pass, and that is the failure
mode this whole file exists to avoid.

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
| `graph-entities-tree` | 3 | **22** |
| `create-fn` | 18 | **4065** |

Creating one `:fn` issues 18 queries and costs four thousand round trips' worth
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

None of that is fixed here. Recording it is the point: those are the first
per-namespace fixture numbers this suite has ever kept.

## Adding a scenario

1. Add a `deftest ^:perf` to `test/graphden/perf/scenarios_test.clj`.
2. Wrap the operation in `psql/record!` with a `:sql/…` event key.
3. Assert the operation succeeded, and that `(pos? queries)`.
4. `bb test-perf`, then `bb perf-update`, then write a real `:why` — it is what
   prints when the budget fails, for someone with no context.

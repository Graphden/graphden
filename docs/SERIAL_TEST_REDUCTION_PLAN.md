# `^:serial` test reduction — long-form plan

**Status**: parked. Real debt, multi-day work, no urgency (`bb ci`
wall-time 186 s is already 60% below the 459 s pre-parallel baseline
per `[parallel-test races]`).

## Scope

19 test namespaces are tagged `^:serial`, which the kaocha parallel
plugin runs sequentially instead of distributing across the bounded
thread pool. Each tag is forced by `with-redefs` on a non-`^:dynamic`
symbol — `with-redefs` mutates the root binding process-globally,
so concurrent NS-threads calling the same symbol would see the stub.

### Distribution

| Cluster | NSes | Symbols redef'd |
|---|---|---|
| **Third-party** (cannot fix without rewriting tests) | 5 | `next.jdbc/execute!`, `next.jdbc/execute-one!`, `next.jdbc.result-set/metadata` |
| **Hot-path internal symbols** (would need API change) | 6 | `executor.interface/make-single-arg-callable`, `registry/rich-type-of`, `crud.type-check/type-check-fn-after-mutation!` |
| **Test convenience** (could rewrite via fixture injection) | 7 | Integrant lifecycle (`ig/halt!`, `ig/suspend!`, `sys/read-config`), reconciler hooks, etc. |

## Why not a quick pilot

The natural pilot — make one hot-path symbol `^:dynamic` — adds
runtime dispatch overhead on production callsites called per
execute. The cost is real (no inline cache; per-call thread-local
lookup) and the benefit is "one NS becomes parallelisable".
That's a poor trade.

## Real fix shape (when prioritised)

For each cluster:

### 1. Third-party (5 NSes)

`storage.postgres.{sql_errors,edge_cases,crud,pool_and_edge_cases}_test`

+ `storage.protocol.redact_test` redef `next.jdbc/execute*`.

**Approach**: introduce a thin internal indirection in
`graphden.storage.postgres.core` — `(defn- execute-via [ds q] (jdbc/execute! ds q))`
that tests can `with-redefs` against. Or pass a `^:dynamic *jdbc-fn*`
through the storage record and stub via `binding`.

**Cost**: ~half day per NS. Risk: subtle perf cost on jdbc hot path
if the indirection isn't inlined.

### 2. Hot-path internal symbols (6 NSes)

`executor.runtime_test`, `crud.fn_execution.persist_test`,
`crud.entities_test`, `types.check_test`,
`integration.{secret_flow,find_fn_usages_graph,smoke_pass}_test`.

**Approach**: refactor the test to pass the stub explicitly instead
of redef'ing. E.g., `runtime/hof-callable` could take an optional
callable-factory; tests pass a stub.

**Cost**: ~1 day per NS (each test deftest needs a different surgery).
Risk: production API gets a new arg solely for test convenience.

### 3. Test convenience (7 NSes)

`system.{interface,core,branch-router}_test`,
`integration.{branches_lifecycle,cron_schedule_service}_test`,
`crud.types_api_graph_test`, `crud.secrets_test`.

**Approach**: use kaocha's `isolation-vars` extension point. Add the
relevant integrant keys / reconciler hooks to the plugin's
`isolation-var-seeders` list so each NS-thread gets its own snapshot.

**Cost**: ~half day total — mostly editing
`test/kaocha/plugin/parallel.clj`. Lowest-risk cluster.

## Recommended order if/when prioritised

1. Cluster 3 first (lowest risk, biggest unit of work removed). Half day.
2. Cluster 2 case-by-case as test refactors come up for unrelated reasons.
3. Cluster 1 last — perf risk on jdbc hot path needs benchmarking.

## When to revisit

+ If `bb ci` wall-time regresses past ~300 s on a workhorse host.
+ If a new shared-state surface gets introduced (then ALL ^:serial
  pinning decisions deserve a re-look).
+ If the kaocha parallel plugin gains a per-test isolation primitive
  (would obsolete most of this work).

Otherwise: leave it.

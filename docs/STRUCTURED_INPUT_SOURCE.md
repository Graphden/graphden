# Structured input source transition tools

These tools prepare and inspect a migration. The production package loader
still uses the existing format; no command below rewrites installed graphs.
A supported source rewrite is not a proof of new-loader or runtime equivalence.

## Read-only corpus report

Provide every source repository explicitly, including declared package
dependencies. Build copies and external-package mirrors are excluded:

```sh
bb --classpath src:scripts scripts/structured_input_dry_run.clj \
  --repo core . --repo addon /path/to/addon > /tmp/input-report.edn
```

An optional `--manifest /path/to/inventory.edn` requires an exact match with a
saved `structured_input_inventory.clj` report. The scanner checks each source
checksum before parsing and repeats the inventory afterward. A changed source
or repository identity invalidates the report.

The report includes source identities, definition origins, counts and
blockers. It excludes literal payloads and proposed function bodies. Exit 0
means the analysis finished: inspect `:counts`, `:blockers` and
`:coverage-complete?` before drawing any conclusion about migration readiness.
The report explicitly leaves new-loader, runtime and history equivalence
unverified. There is no file-writing `--apply` option.

Package analysis sees only declared transitive dependencies. References in a
successful dependency context are qualified before contexts are combined, so
an unrelated package cannot change an existing bare name's meaning. Modules
within one package are prepared together, allowing forward references.

## Semantic source planner

`graphden.input.source/plan-module` accepts original definitions and the
existing `records.parse/prepare-module` result. It preserves original rich
forms, parsed records and binding origins in its private ledger. Log only the
safe aggregate counts and blockers, not that ledger or the original sources.

Supported rewrites currently include base argument declarations, unchanged
type declarations, and provable whole-value/reference bindings. Other forms
are reported explicitly: list builders, anonymous identity mapping, rename
projections, referenced input scopes, metadata and unknown binding policies.
Secret-path resolution must never become an ordinary literal value.

A base argument's documenting `:value` annotation remains a free input;
conversion does not create a new binding or runtime default. Existing
implementation fallback behavior is preserved. Ordinary fields named
`value`, `ref` or `default` do not introduce new control semantics.

## Expression grammar and source edits

`graphden.input.syntax/parse-expression` accepts a resolved descriptor,
existing input scope, authoring expression and frozen identity context:

```clojure
{:user {:name "Alice" :email [:ref :lookup-email]}
 :options [:value {:ref "ordinary data"}]}
```

Maps fill declared record fields partially. Literal collections use an
explicit value wrapper; bare scalar keywords are literal values. References
are explicit and remain unevaluated. The expected field type is retained for
later value/HOF dispatch. The caller supplies binding origins and a resolved
namespace context; a global bare-name index is insufficient.

The Babashka `structured-input-cst` helper converts supported planner entries
into exact source edits. It can rename argument keys, wrap literal/ref leaves
and change an old single-operator map to a vector expression. Unsupported
entry moves fail explicitly. Literal contents are never recursively rewritten.

Tracked CST positions locate original text slices. Applying the slices
preserves comments, CRLF, Unicode and multiline string payloads without
rendering the whole syntax tree again. `plan` checks source and target
checksums; `apply-text` returns text only. A second application to the exact
target is a no-op, and a stale or inconsistent patch is rejected.

## Focused verification

```sh
bb structured-input-inventory-test
bb structured-input-cst-test
bb structured-input-dry-run-test
```

The JVM suites `graphden.input.core-test`, `graphden.input.syntax-test` and
`graphden.input.source-test` cover binding laws, expression interpretation and
legacy semantic classification. These focused checks do not replace the
native landing gate or the eventual whole-corpus execution comparison.

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

## Offline version 2 module reader

`graphden.input.module/parse-module-v2` accepts a module map and
`{:context-for resolver}`. The module must carry `:input-format 2`:

```clojure
{:input-format 2
 :namespace "orders"
 :fns [{:name :invoice
        :parent :invoice-template
        :bind {:customer {:name "Alice"}
               :options [:value {:ref "ordinary data"}]}}]}
```

The resolver receives each original definition with its module namespace
attached. It must provide a frozen, verified context:

| Context key | Required meaning |
| --- | --- |
| `:fn-id` | UUID from `records.ids/fn-id` for the source namespace/name |
| `:scope` | `[fn-id []]`, the definition's root input occurrence |
| `:input-type-id` | UUID matching the owner's `:input-type-fn-id` declaration |
| `:contract` | Resolved **declared** input descriptor, with UUID field identities |
| `:name->id` | Namespace-resolved keyword → UUID map, without ambiguous sentinels |
| `:binding-id` | Pure `(fn [scope field-id-path] ...)` returning the preserved origin UUID |
| `:declaration-records` | Validated declaration rows, including the owner `:fn` row |
| `:origin-ledger` | Opaque provenance map retained with the parsed definition |

Type-only declarations require their source owner, declaration rows and origin
ledger, with no callable contract. A shared input-type declaration already in
the resolver's frozen graph need not be repeated in the returned records.
This source API deliberately requires name-derived owner UUIDs; migration of
arbitrary database-owned identities needs a separate provenance adapter.

The result contains `:module` metadata, `:definitions` with original `:source`,
declared `:contract`, parsed `:expression`, `:binding-state`, `:bindings` and
origin ledger, plus combined `:records`. Rich `:input` declarations and top-level
definition metadata are retained. The reader validates structural shapes,
addresses, binding origins and occupancy before returning any result. It does
not write records, register types, execute refs, or convert declarations into
legacy `:args`.

Missing required fields are allowed in a partial definition. Documented default
values remain annotations. This structural validation does **not** prove value
or ref type compatibility, graph readiness, or value/HOF/callable dispatch.
Resolving declarations, inheritance and names belongs to the caller; union
arms are never chosen by this reader.

`emit-module-v2` accepts the parsed result and the same context options. It
emits explicit partial/value/ref syntax, preserves declarations and metadata,
and reparses to verify declaration and binding-origin stability. Formatting
and comments remain the responsibility of the CST writer. Parsed output and
origin ledgers can contain literal payloads; do not print them in summary logs.

The production loader accepts only absent or explicit version 1 headers.
It rejects version 2, unknown headers and top-level definition `:input`, `:bind`
or `:configure` keys before any implementation in the resolved package closure
is evaluated. Legacy arguments and literal maps may still use those names.
Preflight snapshots package metadata and every module's parsed source per load
call; processing consumes that same snapshot without rereading source files.

## Focused verification

```sh
bb structured-input-inventory-test
bb structured-input-cst-test
bb structured-input-dry-run-test
bb structured-input-module-test
```

The JVM suites `graphden.input.core-test`, `graphden.input.syntax-test`,
`graphden.input.source-test` and `graphden.input.module-test` cover binding laws,
expression interpretation, legacy semantic classification and offline module
round trips. `graphden.packages.input-format-test` verifies the production
boundary and preflight using tiny synthetic packages. These focused checks do
not replace the native landing gate or whole-corpus execution comparison.

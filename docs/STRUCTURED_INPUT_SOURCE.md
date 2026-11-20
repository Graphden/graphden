# Structured input source transition tools

These tools prepare and inspect a migration. The production package loader
still uses the existing format; no command below rewrites installed graphs.
Source verification covers authored declarations and bindings in the
supported subset.

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
`:verification :authored-source-equivalence :verified-definitions` counts the
definitions that passed the offline comparison. Full loader, runtime and
history equivalence are still unverified. The report is read-only.

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
type declarations, independently owned composed fields, and provable whole-value
or reference bindings. A bound top-level field can retain a named type
assertion or a list, map, tuple or union assertion with named leaves.
The verifier compares the rich form as well as its storage ID, since
different collection types can share that ID.

A direct anonymous binding becomes `[:ref {…}]` with a nested declaration.
The reader uses the original namespace, host name, field and normalized
reference spellings to reproduce each legacy child UUID. The host name of
a nested child is the UUID-derived synthetic name of its enclosing child.
The CST edit preserves the authored reference spelling and argument order.

Children can bind multiple fields and contain direct inline descendants.
A bare scalar stays bare, a bare reference `P` becomes `[:ref P]`, and a
value wrapper `{:value x}` becomes `[:value x]`. The child declaration's
`:legacy-forms` map records the field keys whose original spelling needs
extra information to recover the legacy hash. `:ref-wrapper` preserves
`{:ref P}`, and `:bare-value` preserves a bare collection treated as a
whole value by the legacy loader. The reader keeps absent arguments
distinct from `:bind {}`. It derives and verifies the rows and binding
origins recursively for every child.

Inline annotations on the enclosing binding and inline list items still
have explicit blockers. List builders, rename projections, referenced
input scopes, assertions that require inline type rows and unknown binding
policies can also prevent conversion. Secret paths keep resolver semantics.

`graphden.input.source/verify-module` checks each supported plan entry against
the same prepared package context. The offline adapter lowers the actual
v2 `:input` declaration to rows, resolves inherited fields in parent order,
and gives the v2 reader the original binding identities and reference scope.
Field IDs and the derived input type and membership IDs use the storage
migration's mapping. Missing ancestor declarations, inheritance cycles and
distinct inherited fields with the same public name produce blockers.

The verifier parses, emits and rereads v2 EDN, then compares declaration rows,
rich field contracts and authored bindings with the legacy plan. Literals
retain collection kind and the distinction between absent and present nil.
The adapter uses the legacy declaration lowerer temporarily. The v2 parser
handles binding expressions and its own row validation.

### Proving a reference-use address

`graphden.input.source-scope/verify-binding` checks a source-plan entry whose
only blocker is one cross-reference binding. Supply its original source path,
a frozen v2 snapshot containing the owner's other bindings, and a function
that calls `analyze-effective-input` with the checker for that snapshot.

The verifier accepts one direct value-producing reference owned by the
source function. It finds the legacy slot UUID in all declared use scopes,
including occupied uses. The unique occurrence must have a free public
input route, and the reference, field and binding identities must match
the legacy rows. Reanalysis on a private snapshot checks the new binding
and each pre-existing occupied address. The result contains the scoped row
and its source and public paths. Keep this result private like the plan.
The original source blocker stays in place pending a scoped EDN writer.

A field of a named result record can have the same name as a constructor
input. The legacy parser may target the result type's field UUID even when
the public input surface displays the constructor's field under that name.
The verifier rejects that substitution. Repeated uses of the same target
slot also require a separate migration rule.

The offline `graphden.input.source-snapshot/package-index` accepts original
forms and the prepared package including visible dependencies. Pass the index,
owner UUID, and frozen base-function registry entries to
`source-snapshot/verify-binding`. It omits the one candidate binding from
a private baseline, verifies reachable declarations through the v2 reader and
uses their field IDs as implementation argument paths. The stage 4 signature
engine and directional checker run before and after adding the scoped row.
Only single-parent closures with supported primitive declarations currently
supply this proof. A declaration gap identifies the dependency UUID and source
blocker codes. Public source files and source-plan counts require a separate
scoped writer.

Run the focused JVM tests with `bb wt test --focus graphden.input.source-scope-test
--focus graphden.input.source-snapshot-test`.

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
and change an old single-operator map to a vector expression. Independently owned
composed fields move to `:input` while inherited bindings move to `:bind`.
Same-line trailing comments follow their values, standalone comments precede
the next field, and declaration ordering retains original slot positions.
Fields forwarding an inherited or referenced slot require a projection and
remain blocked. Other entry moves fail explicitly. Literal contents are
kept verbatim.

For example, `{:x {:ref :query :type :text}}` becomes
`:bind {:x [:ref :query]}` and `:configure {[:x] {:type :text}}`. The path
identifies the field, while the expression and its type assertion share one
binding identity. The CST edit carries the original type token and its
comments to the configuration entry.

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
| `:resolve-config-type` | Resolve a configured field's type to its canonical `:type` form and `:type-override-fn-id` UUID in the module's frozen namespace context |

Type-only declarations require their source owner, declaration rows and origin
ledger, with no callable contract. A shared input-type declaration already in
the resolver's frozen graph need not be repeated in the returned records.
This source API deliberately requires name-derived owner UUIDs; migration of
arbitrary database-owned identities needs a separate provenance adapter.

The result contains `:module` metadata, `:definitions` with original `:source`,
declared `:contract`, parsed `:expression`, `:binding-state`, `:bindings` and
origin ledger, plus combined `:records`. Rich `:input` declarations and top-level
definition metadata are retained. The reader validates structural shapes,
addresses, binding origins and occupancy before returning any result. The
offline source verifier supplies a resolver for migrated declarations. The
general reader also accepts frozen contexts for independently authored v2
fixtures.

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

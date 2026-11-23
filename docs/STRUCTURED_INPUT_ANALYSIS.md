# Offline structured input analysis

These APIs operate on a frozen graph snapshot. They do not activate the v2
package loader, public writers, execution, or signature publication. Production
continues to use the existing path. See [storage](STRUCTURED_INPUT_STORAGE.md)
and [source normalization](STRUCTURED_INPUT_SOURCE.md) for the adjacent layers.

`graphden.input.context/build-context` accepts ID-keyed functions, fields,
memberships, authored bindings, rich type bodies and signatures. The caller
supplies namespace-resolved aliases and alias-to-type IDs; missing names never
fall back to the process registry. The context captures marker policy. A future
caller-owned cache must include graph epoch, branch/revision and type/marker
policy in its snapshot identity; a function row version alone is insufficient.
There is no shared analysis cache yet. Contexts without an explicit snapshot ID
receive a fresh context token; callers must not reuse it for changed snapshot data.

The `signatures/infer-use` adapter feeds canonical scoped facts into
`types.check/infer-input-signature`. It requires explicit implementation
provenance: `{:base-fn-id UUID :root-entry frozen-entry :argument-paths
{ABI-key [field-ID ...]}}`. These paths also serve the compiler; implementation
functions are never invoked during analysis. Whole and partial literal records
remain data, and projections retain their source-cell identity. Root signature
variables are instantiated separately and joined to input types through the
existing directional checker. The adapter returns substitutions and proofs for
the graph checker to reconcile; invoking it alone does not complete analysis.

Existing return rules can read other rich signatures. The adapter therefore
binds both global and tenant registry views to isolated snapshot data. Derived
use IDs distinguish repeated targets inside that view and are never persisted.
The common return-rule, effect, declaration and execution-support policies are
reused. Pass `:implementations` and `:infer-signature
graphden.types.check/infer-input-signature` to `analyze-effective-input` to
compute signatures before final reference classification. Explicit projections
participate in dependency ordering. A target's authored/configured inputs
determine its initial return and reference mode before incoming lexical captures
are routed; caller captures do not retroactively switch that mode. The common
graph solution then resolves correlated input and return variables.
Each inferred scope retains `:signature-source` (base identity, original root
entry and argument paths) so compilation can verify it uses the same evidence.
Changing implementation/type-rule evidence requires a new context snapshot.

`:coverage :signatures` reports whether this inference adapter ran. Without
it, the API still consumes caller-supplied frozen signatures and reports that
signature inference was not performed. Source loading and production signature
publication remain inactive.

`descriptor/resolve-contract` resolves the function's authored `input-type-fn-id`
using existing type expansion and shape primitives. Field IDs come from declared
memberships. Repeated record occurrences can share child IDs because their full
paths differ. Dynamic maps remain atomic. Union alternatives remain alternatives;
a union at the input root is rejected rather than choosing an arm. Marker and
refinement wrappers survive resolution. Missing types and executable rows used
as input type declarations are errors.

Stored missing/nil `required` means false. A native source adapter emits explicit
booleans or sets `:field-default-required? true`; an explicit nil remains false.
This policy is applied before the binding algebra, whose descriptor default is
required=true. The source reader consumes authored descriptors, not generated
remaining or inherited descriptors.

`inheritance/declared-view` combines parent declarations by stable field identity.
A diamond visits its shared ancestor once. Distinct field IDs with the same
public name are ambiguous. Visible bindings retain `origin-fn-id` and their
original binding IDs; the returned rows are an analysis view, never write or
migration payloads. Whole-record marker/refinement obligations remain attached.

`analysis/analyze-input` checks the local declared record and authored bindings.
It returns occupancy, residual fields, required addresses, type substitutions,
proofs, obligations and explicit coverage diagnostics. Metadata overlays strengthen
requiredness, narrow types through the existing checker, and enforce terminal
locks against descendants and caller-use overlays. They update a separate
configured contract, preserving the authored declaration. Supply
`graphden.types.check/check-input-binding` as `:check-expression` to use the
existing directional checker. An absent adapter never means success. Gradual
values and refinement obligations remain deferred, and error data omits literal
payloads. Required missing fields describe invocation readiness independently of
whether a definition's types have been checked.

A verified source snapshot can retain an annotation or projection pin's rich
type in `:source-binding-types`, keyed by `[binding-id type-id]`. This prevents
a collection stored under the legacy `:sequence` FK from losing its element
type during metadata application. Projection pins require the exact source
alias, target and binding rows. The declared type must account for its physical
FK. Restoring the descriptor is local to the binding; it does not redefine the
shared primitive. Ordinary metadata subtype/marker checks and runtime
obligations still apply. A missing checker remains incomplete coverage.

## Guard proof boundary

Legacy `types.check.narrowing` recognizes direct `some?`, `nil?` and `is-a?`
guards. A taken `is-a?` arm can project the target's structural type while
preserving markers. Its false arm keeps the original type. The existing
per-function override map is not a proof that two scoped value occurrences
are the same cell, and cannot be imported into a frozen graph by display name.

The shipped `web.html/_normalized-tag` illustrates the remaining boundary.
Its `is-a? :text` false arm still returns the nullable generic `_element-tag`.
The outer `_is-hiccup-element?` is an `and` of sequence/non-empty/tag checks,
with an `or` over `_first-of-value`. That producer and `_element-tag` have
different declaration UUIDs. Concluding keyword in the false arm requires
both the composed guard and proof that the reads share the same input cell.
Direct-guard legacy semantics supplies neither fact. The resulting
`equal?.a` conflict (`:keyword` versus `[:union :keyword :null a]`) remains
refused rather than being converted to an unproved return witness.

This migration keeps the boundary in
[the composed-guard decision](TYPE_SYSTEM_DECISIONS.md#phase-170-v2--rejected-composed-guard-narrowing).
It preserves explicit authored type pins through closed-instance/source-row
proofs. It does not add `and`/`or` recognition or infer equivalence between
producer declarations. Any future guard proof must identify the guarded value
cell and branch use through verified source paths; alias names or a permissive
legacy sweep result alone are insufficient.

`analysis/analyze-effective-input` expands evaluated value and callable-producer
references into `:scopes`, keyed by vectors of reference binding IDs. Its
`:dependencies` record source addresses, target use scopes, target function IDs
and reference modes. `:required-addresses` retain those scopes. Each use checks
its scoped overlays together with the target's authored occupancy. Two uses do
not share an overlay, and an already occupied target field cannot be rebound.
Shared declarations and bindings remain shared; this graph does not mint types
or clone functions. The per-scope contracts are derived data and are never fed
back into the authored input declaration.

Reference classification uses the same callable-producer predicate and callable
contract construction as existing checker/compiler paths. Callable-producing
refs open constructor inputs, not the parameters of the callable they return.
Identity `fn-ref` edges import neither target inputs nor evaluation dependencies.
The shared `types.reference/call-site-contract` preserves the existing checker's
capture type/effect projection, including authored `lambda-params []`.

Stored-value resolver annotations remain a distinct `:resolved-value` operation.
Their output is never reclassified as identity or an implicit graph callable.
`:resolver-arguments` records the stored literal at the resolver's single
effective parameter address; this is a derived literal source, not a read of
the destination's computed result. A zero-parameter resolver ignores the value;
multiple effective parameters are rejected. Resolver scopes establish lexical
boundaries: internal references can capture resolver inputs, but never ambient
caller fields. Argument and result types pass through the same checker. The
compiler must explicitly support this operation before executing such a plan.

Projections carry typed source addresses across use scopes. Required projections
activate their source paths, including optional record ancestors; source
occupancy and chained routes remain visible. The existing marker propagator
retains content labels when reading a field. A common substitution reconciles
constructor inputs/returns and projection routes, with directional checks replayed
against the common solution. Scoped variable identities use the existing type
freshener and are never written back to authored names or declarations.

Graph reference cycles and projection cycles (including cycles crossing reference
uses) are rejected. This preserves the existing DAG invariant and the `:fix`
recursion model; it does not introduce recursive graph semantics.

The derived `:public-input` surface exposes typed nested fields with authored
names. A bound reference field becomes a `:reference-inputs` group: its children
are the referenced function's remaining inputs, never its result fields. The
route ledger retains source addresses, field origins, result addresses and kinds.
Completion constraints stay separate from a pruned residual record shape.
`surface/bind-input` requires the same frozen snapshot identity and resolves
through these routes before invoking the existing syntax/algebra. It cannot
rebind a reference result or an occupied child. This API is separate from source
`:bind`; source parsing continues to use authored declarations.

Callable parameters and captures use the shared runtime selection rules:
authored `lambda-params []` captures everything, structural names take priority,
and positional inference must be unambiguous. `:closures` describes invocation
parameter routes and captures. `:input-routes` contains derived source-cell
aliases, including stable-identity inputs forwarded through value refs. These
are not new binding rows. Hidden invocation/captured addresses remain in the
declared contract and disappear only from external remaining inputs.

Implicit lexical routing requires the same full field-ID path in an enclosing
use scope. Same-name fields with distinct origins remain distinct. Legacy
name-fallback captures must be lowered to explicit projections using migration
origins; the migrator must report unresolved cases rather than silently exposing
new free inputs. The conformance fixture covers both the native distinction and
the explicit projection that restores the legacy connection.

Legacy env initializers have another boundary. `compile-eager` installs an env
thunk under its name and slot routes, then restores the caller's original
values for those keys while evaluating the thunk. A closer inherited env
binding can replace that initializer entirely. A reference bound to a primitive
input does not have the same inheritance behavior.

For example, the original binding for `web.crud/_search-normalized` addresses
the inner `str-trim.string` slot and reaches the outer `str-replace.string`
through an env thunk. Moving it to that outer slot while keeping its UUID
preserves standalone formatting: `"  APP/Fn  "` still becomes `"app.fn"`.
But this derived declaration changes behavior under the same remap:

```clojure
{:name :derived
 :parent :web.crud/_search-normalized
 :args {:string {:value "  ORIGINAL/CASE  "}}}
```

Legacy execution returns `"  ORIGINAL.CASE  "`; the remapped graph returns `""`
when no query input is supplied. The descendant's env literal used to hide the
initializer. Matching names, preserved UUIDs, local type checking and a passing
standalone execution do not prove this rewrite across inheritance.

Keep this source case blocked until migration preserves the effective env
precedence of the final derived owner and distinguishes incoming caller cells
from local initializers. A scoped input route cannot silently replace an
occupied initializer. Do not infer an input record from the env map: that map
is lexical runtime state with its own forcing and precedence rules.
The [source snapshot regression](../test/graphden/input/source_snapshot_test.clj)
covers the shipped formatter and this derived counterexample.

A `:checked` graph has completed supported analysis, not necessarily validated
all runtime values. Deferred checks are explicit `:obligations` with stable
scope/path/kind identities and `:field-read` or `:container-materialization`
triggers. A compiler must consume a supported obligation or refuse it; a container
obligation cannot eagerly force unrelated fields when one field is read.
Runtime execution and signature publication are not activated by this API.

Deferred checks at one address can carry independently scoped type variables,
for example `[:union :null [:map a1 :any]]` and the same shape with `a2`.
Their printed shapes do not prove that the variables have the same binding.
Keep the conflict until projection/signature inference supplies that relation,
or the validation boundary explicitly represents both checks. Erasing the
variables or keeping only one check cannot establish a certificate.

Frozen source implementations retain their base field declarations alongside
explicit ABI paths. An unchanged inherited field with the same UUID and source
type proves that its declaration variable and ABI variable share a binder at
that use. Inference derives a value-variable renaming through the common type
walker before checking arguments and returns. Missing origins, changed field
identities or changed types do not supply that relation; independent uses stay
separately instantiated. The relation removes duplicate obligations while
preserving the remaining runtime check.

A source-only scoped binding replay may retain field-read obligations when
the complete obligation set is identical before and after adding the proved
rows. The certificate carries that set. Dropping, adding or changing a check,
or requiring container materialization, refuses the replay. This preserves the
existing validation boundary; it does not grant executable admission.

Generated list operands retain the legacy list-item UUID and position. Frozen
source fragments prove the list owner and actual attached use; compact origin
witnesses accompany the list link and operand edges in scope certificates.
The source reader checks them against current legacy binding and item rows,
then applies the same identity formulas to the scoped path. Missing or changed
positions, owners, targets and use scopes refuse the replay. This initial proof
covers a root list contribution; inherited tail continuations need separate
evidence. Literal operands do not become references.

An untyped legacy positional `:as` field may have no raw target type while its
original slot row has primitive `:any`. A versioned source witness can prove
that exact case through the original owner/slot, list binding, item position
and UUID, optional field policy and membership. The frozen provider checks the
same slot and converted input membership; the offline reader reconstructs the
witness from current legacy parser rows. The raw nil target stays unchanged.
Unknown witness versions, historical row stamps, shifted paths and changed
type/requiredness refuse this proof. It grants no general nil-to-any conversion
or executable admission.

Approving a pure native type callback does not make every composite obligation executable;
the compiler must still consume its supported checks or refuse the plan.
Marked and refined identity inputs remain identity edges; the checker receives
their target UUID as a known value for policy validation. Marked callable inputs
use the existing invocation-signature projection. Refined callable policies
without a supported proof remain explicit diagnostics, never value evaluation.
Reference cycles are rejected; they are never treated as empty input. A locally empty remaining record is not proof that the graph is
ready. Public activation requires the remaining analyzer and runtime work.

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

The separate `signatures/infer-use` adapter feeds canonical scoped facts into
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
reused. Integration of this adapter into reference classification remains
separate work; current graph coverage does not certify inferred signatures.

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

A `:checked` graph has completed supported analysis, not necessarily validated
all runtime values. Deferred checks are explicit `:obligations` with stable
scope/path/kind identities and `:field-read` or `:container-materialization`
triggers. A compiler must consume a supported obligation or refuse it; a container
obligation cannot eagerly force unrelated fields when one field is read.
Runtime execution and signature publication are not activated by this API. Wrapped identity or
callable policies that lack an adapter are diagnosed rather than evaluated in
the wrong mode. Reference cycles are rejected; they are never treated as empty input. A locally empty remaining record is not proof that the graph is
ready. Public activation requires the remaining analyzer and runtime work.

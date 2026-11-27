# Structured input and partial binding

Status: accepted transition contract. Pure binding, expression parsing and
read-only source planning are available as transition APIs. Stored graphs,
package loading and production execution still use the slot model until
their respective migration stages land. See the
[source transition tools](../STRUCTURED_INPUT_SOURCE.md) for current boundaries.

## One input contract

A graph function has one structured input. Each record field can be supplied
as a whole value or constructed from bindings to its declared children.
Remaining fields form the function's public input. Invocation stays explicit:
an unbound optional field remains bindable but does not prevent invocation.

Rich types keep their existing representation. A resolved input descriptor
adds ordered field identities and optionality separately:

```clojure
{:type {:sender :user}
 :fields [{:id sender-id :name :sender :type :user :required true
           :fields [{:id name-id :name :name :type :text :required true}
                    {:id email-id :name :email :type :text :required false}]}]}
```

`graphden.input.core/input-contract` validates this structural descriptor; it
does not resolve aliases, infer types or invent field IDs. The graph resolver
supplies the descriptor from a frozen graph/type context. In particular,
optional metadata is not inserted into the maps understood by `types.core`
as rich record types. Dynamic dictionaries have no expandable fields.

The full effective input and projections must belong to a consistent graph
version. The residual descriptor is derived, not an independently mutable
stored type. Updating a dependency's free inputs must preserve old versions
and other branches' contracts.

## Address and binding identity

A field address consists of an existing input scope and a vector of field
IDs. `sender.name` and `recipient.name` have different prefixes even when
both use the same User field definition. Names resolve to addresses at
authoring boundaries; execution does not identify fields by display names.

A scope is not an instruction to instantiate a referenced function again.
Existing shared named references retain their sharing and evaluation cache;
independent inline instances retain their separate inputs. Both directions
are migration invariants.

Every binding has its own stable ID. A diamond inheritance join can deduplicate
the same binding at the same address with the same contents. Equal values
from different binding origins still conflict. Replaying a user bind is an
error, not inheritance deduplication.

Whole-value binding occupies the target and its entire subtree. Structural
partial binding occupies only supplied descendants. No new binding may
overlap an occupied address in either prefix direction. Disjoint siblings
can be bound in either order with the same result. Storage must check these
rules atomically, including concurrent edits and branch merges; uniqueness
of the complete path alone is insufficient.

Replacing or removing an author's own binding is an explicit versioned edit
with dependent revalidation. It is not a second bind and cannot override a
fixed inherited binding.

## Partial records and expressions

The normalized algebra accepts expressions with explicit kinds:

```clojure
{:kind :partial
 :fields {sender-id
          {:kind :partial
           :fields {name-id {:kind :value :binding-id name-binding-id
                             :value "Alice"}
                    email-id {:kind :ref :binding-id email-binding-id
                              :fn-id lookup-email-id}}}}}
```

Whole expressions are `:value`, `:ref` or a resolved `:projection` from an
external input. Presence of a `:value` key is significant: nil, false and an
empty map are values, not missing bindings. A literal's contents are never
recursively interpreted as binding operators. An empty partial expression
introduces no binding and does not activate an optional record.

The new EDN authoring format will normalize incomplete maps into the partial
node. To keep ordinary data fields named `value`, `ref`, `type` or `fields`
unambiguous, explicit expression operators use tagged vector forms:

```clojure
{:bind {:sender {:name [:value "Alice"]
                 :email [:ref :find-email]}
        :recipient [:ref :find-user]
        :options [:value {:value 1 :ref "ordinary data"}]}}
```

These forms are a transition grammar contract. The standalone
`graphden.input.syntax` parser/exporter supports value/ref expressions and
partial maps against a supplied resolved descriptor; it is not yet connected
to the production package loader. Bare scalars (including keywords) are
literals; references always use `[:ref name]`. Callers supply a frozen
reference index and a binding-identity function to preserve existing origins. Literal maps/vectors have an explicit
value wrapper; a parser must not infer ref meaning from literal contents.
The semantic migrator preserves existing literal maps as whole values and
distinguishes declarations, defaults, narrowing, renames and actual binds.

## References at any depth

A nested field has exactly the same reference behavior as an existing
top-level argument. The effective expected type selects the calling mode:

- ordinary data input: evaluate the referenced graph only when needed;
- callable input and ordinary graph: wrap it as a HOF;
- callable input and a graph producing a callable: obtain that callable;
- identity input (`:fn-ref`): supply identity without executing the graph.

Binding itself never executes a reference. An entire computed record closes
the target subtree; the referenced graph's own free and captured inputs may
still contribute to the enclosing function's input. Ref resolver semantics,
effects, execution support, secrecy and view markers are preserved.

The stage-1 algebra retains the target's rich `:expected-type` alongside each
opaque expression. It does not duplicate the existing runtime classifier.
Later compiler integration extracts a common leaf classifier instead of
creating a different set of nested HOF rules.

`remaining-input` reports locally missing field paths and unresolved ref or
projection dependencies. An empty local missing set is NOT proof that the
whole function is ready: referenced graphs can still have free inputs.

## Optional records, variants and collections

An absent optional record does not block invocation. Supplying a descendant
activates the record, so its required children must then be supplied. Default
values remain fallback behavior, not occupying binds. A whole empty record
and an absent record remain distinguishable.

Variant selection must be unambiguous before addressing a branch-specific
field. Structural binding may narrow the possible variants; it must not
choose one according to declaration order. Alias expansion is bounded by
the type resolver. Constraints remain attached to the original contract;
the occupancy algebra does not prove cross-field refinements by deleting
bound keys from a rich type.

Whole dictionaries and lists are atomic. Existing append/closed list chains
migrate to explicit list-building composition with an open continuation,
preserving order, laziness, sharing and available configuration inputs.
Extending a builder does not reopen an already bound list or dictionary.

## Implementation boundaries and evidence

Stage 1 exposes `input-contract`, `resolve-address`, `bind-input`,
`merge-bindings` and `remaining-input` in `graphden.input.core`.
`test/graphden/input/core_test.clj` verifies occupancy and identity laws without
storage, full package sweeps or executing referenced graphs. Value type
compatibility, transitive free inputs, runtime materialization and atomic DB
mutation remain the responsibility of the later integrated stages.

The transition proceeds through storage, semantic EDN migration, checking,
JVM compilation, browser compilation, editor/API integration and complete
corpus conversion. Temporary adapters have a removal stage; the final system
has one canonical model. Fresh install and history-preserving upgrade both
must work before removal. The previous name-based propagation and versioned
parent-set decisions are not implicitly reopened by this change.

## Source inventory

Run `bb scripts/structured_input_inventory.clj --repo core .` to snapshot the
canonical package sources without executing their implementations. Additional
repositories are explicit repeated `--repo LABEL PATH` arguments; the tool
does not discover sibling or private repositories. Output contains source
checksums and Git identities, relative file paths, module coverage and syntax
counts. It excludes build copies, external mirrors and saved database graphs.

The inventory distinguishes syntactic candidates from resolved meaning:
bare keywords and vectors cannot be classified as refs/append without their
target contracts. A full semantic migrator must use resolved graphs, not
these counts as an oracle. The fast inventory selftest runs in the affected
CI stage before JVM suites; it is not a replacement for corpus migration
or database/history validation.

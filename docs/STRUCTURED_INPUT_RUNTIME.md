# Structured input runtime foundation

`graphden.input.runtime` is an isolated JVM foundation. The production
compiler still builds legacy arguments. The isolated checked-plan fixture
compiler below uses these views without activating a package format. Native
tests are required: Babashka cannot create the
`APersistentMap`/`AMapEntry` proxy subclasses used here.

## Cells and record lookup

`field-cell` wraps an explicit resolver with the existing executor
`runtime/thunk`. Each cell computes at most once, including concurrent reads
and failures. Allocate cells inside each invocation. Reuse the same cell for
projections of one producer; do not share cells across independent calls,
branches or compiled global constants.

`value-cell` wraps an opaque value. It never interprets a map, keyword,
callable, delay or other `IDeref` as an expression. `record-view` takes a map
from field names to explicit cells. A nested record is an explicitly supplied
record-view value, not a recursive reinterpretation of a dictionary.
Field names, including newly associated fields, must be keywords, matching
declared record descriptors. Other dictionary key domains stay ordinary opaque
maps. This prevents a custom value key from running code when a cache token
is hashed; invalid field names are rejected before hashing them.

```clojure
(let [user (record-view {:name (field-cell load-name)
                         :email (field-cell load-email)})]
  (get user :name)) ; load-email is not called
```

The view extends Clojure's `APersistentMap`. Its map entries extend `AMapEntry`
with deferred value access. Entries must remain lazy too: otherwise `keys`
would compute values merely by creating ordinary `[key value]` pairs.

| Operation | Value consumption |
| --- | --- |
| `get`, keyword lookup, `get-in`, destructuring | Only requested fields |
| `contains?`, `count`, `keys`, entry `key` | No field values |
| `assoc`, `dissoc`, `with-meta`, `empty` | Preserve untouched cells without reading them |
| Entry `val`/`nth 1`, `vals`, `reduce-kv` | Requested entry values; reduced traversal stops |
| Equality, map hashing, `into {}`, printing, serialization | Ordinary map value consumption |

Persistent modifications create another view; the original remains unchanged.
The inherited Java Map surface is immutable. Nil, false and missing keys retain
ordinary map distinctions. A base implementation sees values on lookup,
including ordinary callable values, rather than compiler-created delays.

## Cache identity is separate from value equality

`cache-token` returns the view's field-name → cell-identity map. Hashing or
comparing it does not run cells. Views using the same cells have the same
token; metadata is irrelevant. Replacing/removing a field changes only that
part of the token. Independent equal computations intentionally have distinct
tokens, preserving their origins rather than deduplicating by shape.

This token is a foundation seam, **not** a replacement for the current
compiler's cache key. Ordinary value-equal arguments must keep their existing
sharing. `compile-eager/fa-key-for-cache` currently projects free arguments,
then `call-with-cache` hashes that map. A lazy record value cannot simply be
inserted there: ordinary map hashing would read its fields. Compiler wiring
must use raw occurrence cells/tokens before exposing lookup views to impls,
and prove sharing for repeated refs, captures and projections. Arbitrary
containers holding a view must not bypass that separation.

## Consumption boundaries

`materialize-record` explicitly converts record views to ordinary maps,
recursing only into other explicit record views. Literal dictionaries,
sequences, functions and caller-provided delays remain opaque. It is not a
general JSON coercion or an untrusted-data size limiter. Streaming serializers
may read the map directly and keep their existing byte/depth budgets.

`diagnostic-summary` returns only kind and field count. Diagnostics must use
this instead of printing or serializing a deferred record. In particular,
`compile-eager/render-captured-value` admits only bounded eager data before
calling the streaming JSON size counter. Deferred values receive the existing
truncated-value marker. This also prevents capture from realizing an ordinary
lazy sequence. Optional tracing must not execute an otherwise unused field.

Escaping results also need an explicit policy: consume server/JSON output
while its execution/snapshot context is valid. Do not capture every dynamic
binding with a blanket `bound-fn`: long-lived HOF handlers must enter fresh
request scopes, following the existing request-call-cache and capture rules.
The record foundation neither captures dynamic request identity nor selects
reference dispatch. The fixture compiler consumes checked dispatch below.

## Checked-plan fixture compiler

`graphden.input.compile/compile-checked` returns the existing executor closure
ABI `(fn [address-values ctx])`. Its options contain a frozen analysis context
and an explicit resolved base implementation table:

```clojure
{:context frozen-context
 :implementations {target-id {:base-fn-id base-id
                              :impl resolved-base-function
                              :root-entry frozen-rich-signature
                              :argument-paths {:base-argument [field-id]}}}}
```

Input analysis does not resolve a composed function's base implementation.
The caller supplies that mapping; public field renames do not change the base
ABI. The frozen root entry's argument names must match the ABI paths.
`:compile-time-value?` and nonempty `:lazy-seq-args` require separate lowering
and are explicitly rejected. The fixture closure is installed only in a test registry and called via
`compile-runtime/execute`. It uses that entry point's authorization, effect and
cancellation boundaries. Dependencies enter the existing `call-with-cache`
through `compile-eager/invoke-compiled`, preserving its tracing, always-fresh
policy and target-ID-based effect eviction. There is no second execute driver.
As with the existing executor, the compiled registry and active rich-type
registry must refer to the same snapshot. Tests bind an isolated rich-type
registry for trace policy; compilation never publishes it globally. Missing
registry entries retain the existing fail-closed hidden trace behavior.

`input.ir/lower-checked` requires checked recursive coverage and the same
snapshot ID. It lowers literals, identity refs, evaluated refs, callable
producers, structural records, exact-address projections and derived input
routes. Free leaf addresses remain invocation parameters. Whole record inputs,
projection into an opaque producer's descendant, graph-callable closures and
sequence builders are not supported by this slice. Container obligations are
rejected. Supported primitive `:value-type`/`:field-read` obligations run through
the existing validator inside the once-cell; failed reads are memoized too.

`input.frame/instantiate` allocates and freezes the complete cell table before
any field can execute. A base implementation receives a plain map of outer
runtime thunks. This extra boundary is intentional: `defbase` resolves an outer
thunk exactly once, so an Atom, Delay or marked callable returned by a reference
is passed through unchanged. Nested structured records remain lookup views.
Optional absence, explicit nil and false are distinct. No default is invented.

Memo keys are built without field reads. Known eager literal/parameter values
retain value equality, with their field guard type. A computed occurrence is
identified by its authored binding ID, target and transitive input keys. Its
fresh runtime cell address is not a call specialization: otherwise two uses of
one named parent containing its own reference would repeat the parent's effect.
Scoped overrides change actual input keys; the use path is not a blanket cache
partition. The fixture refuses unknown/lazy input key shapes or inputs exceeding
the eager-data admission budget instead of silently replacing equality with a
fresh identity and repeating effects. Supporting additional external value
domains requires an explicit key representation.

This is preparation for a shared canonical compiler, not completed runtime
migration. The production legacy compiler is still the oracle. Future work must
normalize its slot/ref/sequence/HOF inputs into the same IR, finish closures and
consumption obligations, and remove the parallel lowering paths before runtime
migration can be considered complete.

## Later browser parity

The browser interpreter already has `Thunk`, `LazySequence`, Map-based
primitives and an invocation-local frame cache in
`resources/packages/app/ui-preview/browser-runtime.js`. A later record view
should use those conventions: Map-compatible `get`/`has`/`keys`/`entries`,
deferred values and identity-based frame/cell caching. A plain object Proxy
would miss the existing `instanceof Map` paths. This is a design note only;
no browser runtime or wire ABI change is implemented here.

## Verification

`graphden.input.runtime-test` exercises ordinary `defbase` lookup, nested
destructuring, opaque literals, persistent map operations, symmetric equality
and Clojure/Java hashes, entry access, serialization, nonforcing memo tokens,
shared producers, separate invocations, cached failures and concurrent reads.
Run it through `bb wt test --focus graphden.input.runtime-test`. The native
landing gate and later JVM/browser differential execution remain required.

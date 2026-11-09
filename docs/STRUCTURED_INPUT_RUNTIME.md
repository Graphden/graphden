# Structured input runtime foundation

`graphden.input.runtime` is an isolated JVM foundation. The production
compiler still builds legacy arguments; no package or execution path uses
these record views yet. Native tests are required: Babashka cannot create the
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
`compile-eager/render-captured-value` currently calls the streaming JSON size
counter; enabling record views requires a nonforcing policy there first.
Optional tracing must not execute an otherwise unused field.

Escaping results also need an explicit policy: consume server/JSON output
while its execution/snapshot context is valid. Do not capture every dynamic
binding with a blanket `bound-fn`: long-lived HOF handlers must enter fresh
request scopes, following the existing request-call-cache and capture rules.
The foundation neither captures dynamic request identity nor selects HOF,
callable-producing-ref, ordinary ref or identity-ref dispatch.

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

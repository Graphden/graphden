# Secrets — `:secret` information-flow type-marker

This doc describes how graphden protects secret values (DB passwords, API
tokens, etc.) from leaking through composition and through the `▶ Run`
result-pane. The protection is structural — implemented in the type
system + the executor's response shaper — not value-based; transformations
like `base64(secret)` and `hash(secret)` are also hidden because the
TYPE of the result carries the marker, regardless of what the value
actually is.

> **Scope — read this first.** This is **best-effort taint-tracking, not a
> proven non-interference / information-flow guarantee.** Concretely: (1)
> propagation is a per-base-fn opt-in flag (`:taint-propagate?`), so a new
> content-passing base-fn that forgets it will silently declassify — there is
> no structural check that every such fn carries it (see the T3 audit below);
> (2) `[:secret T] ⊆ :any` is TRUE, so a secret flowing into any `:any`-typed
> slot loses its marker (the escape hatch documented below); (3) covert
> channels remain open — notably exception messages (`:throw`, see Known
> limits). Treat this as a strong guard against *accidental* leaks (echoing a
> secret into the Run pane, a log, or an HTTP body), not as a boundary you can
> rely on against an adversary who controls the graph.

## The marker

**Generalized (2026-07-22):** `:secret` is now the SEEDED instance of a
registry-driven marker engine (`types.core.shapes/register-marker!`).
Any `[<tag> <inner>]` with a registered tag gets the same asymmetric
subtyping, the same jsonb-sink laundering guard, and — because the
propagators below carry EVERY marker present on any input — the same
propagation through the already-annotated base-fns, with NO new
per-fn annotations. A new marker is declared IN THE GRAPH:

```clojure
{:name :pii
 :marker {:monotone? true :hide-result? false}}
```

`:hide-result? true` opts the marker into the `/api/execute` redaction
(T4 below reads `contains-hide-result-marker?`, not `:secret`
specifically). Marker rows store their flags in `:constraint` under
`:marker-def`, so the DB-driven path re-registers them without the EDN
source; usage (`[:pii :text]`) degrades to the inner type at the
storage layer exactly like `[:secret T]`.

**Runtime half — SHIPPED:** a binding may carry `resolver-fn-id`
(nullable ref → `:fn`): the stored `:value` becomes the INPUT to that
graph fn at arg-resolution time ("stored value → runtime value"),
evaluated through the standard 1-arg callable machinery. Authoring
form: `{:resolver :vault-get :value "kv/path"}` (fns.edn round-trips
it; the exporter emits the same form). `:vault-get` is the canonical
secret resolver — a normal admin-gated base-fn returning
`[:secret :text]` — making `:override-kind :secret-path` the legacy
special case of the same mechanism. `validation/resolver-rej` refuses
a resolver whose registered return carries a hide-result marker when
the target slot's type carries none (the runtime laundering guard,
mirroring `secret-path-rej`). A new external value source (consul,
KMS, feature flags) is now: write one base-fn, use it as `:resolver`
— no executor change.

`[:secret <inner>]` is a refinement-marker over any type. Subtype
direction is asymmetric:

```text
[:secret T] ⊆ [:secret T']  iff  T ⊆ T'   (covariant inside)
[:secret T] ⊆ T             FALSE         (can't strip the taint)
T          ⊆ [:secret T']   iff  T ⊆ T'   (auto-promote on entry —
                                           monotone direction; plain
                                           values become tainted when
                                           they flow into secret slots)
[:secret T] ⊆ :any          TRUE          (top type, known escape hatch)
```

The marker doesn't change the underlying runtime type — a
`[:secret :text]` value is still a string at runtime. The marker only
exists in the type-system and the rich-types registry. The storage
layer (`graphden.packages.records.types`) recurses through
`[:secret <inner>]` to the inner type when computing the slot's
`type-fn-id` foreign key, so secret bindings store like any other
text binding.

Predicates and constructors:

| Function | Purpose |
|---|---|
| `(secret-type? t)` | `[:secret <inner>]`? |
| `(secret-inner t)` | The inner type, or nil. |
| `(make-secret-type inner)` | Idempotent — `[:secret :text]` wrapping `[:secret :text]` stays `[:secret :text]`. |
| `(contains-secret? t)` | True if `[:secret …]` appears anywhere — top-level, list element, record field, union branch, fn arg/ret, refinement base. |

## Propagation through composition

Each base-fn that touches user content opts in with a declarative
`:taint-propagate? true` flag next to its `defbase` in `impls.clj`:

```clojure
:str-upper {:impl str-upper-fn :taint-propagate? true}

:assoc     {:impl assoc-any-fn
            :return-type-rule assoc-return-rule
            :taint-propagate? true}
```

The checker applies the propagation CENTRALLY (in
`compute-return-type` and `effective-ref-return-uncached`): the
structural layer runs first — a hand `:return-type-rule` when one
exists, else the declared-signature fallback (`signature-return`) —
then, iff the flag is set and any input carried a marker, the result
is lifted into `[:secret …]`. A rule fn is purely structural; taint
can no longer be forgotten when adding one. (The old per-site
`types/wrap-with-taint` closure-wrapping is gone; the helper remains
only as the engine primitive `taint-with-secret-if-tainted`.)

`enforce-declared-return!` in `types/check` allows the computed
return-type to be `[:secret T]` even when the fn-def declared plain
`T` — the marker is propagation metadata, not a widening. The
*registered* return-type carries the marker so downstream type-check
refuses to drop it.

**Flow protection vs Error Tolerance (security carve-out).** Secret
subtype violations remain HARD save-time rejects, deliberately exempt
from the Error Tolerance flip (ROADMAP Block 3) that made ordinary
type failures warn-and-persist. A user write that launders a
`[:secret …]` flow into a plain slot is rolled back (create: row
deleted; update: fields restored; sequence append/update and tighten:
same revert shapes) and returns the pre-tolerance `{:error …}` / 400
envelope, with NO entry recorded in the diagnostics store — the row
doesn't exist. The reasoning: the warn-and-persist path's compensating
gate is execute-refusal over the *derived, in-memory* diagnostics
store (best-effort, bounded post-restart recompute) — acceptable for
iteration ergonomics, too thin for a security class. The secret
guarantee must not depend on the derived store, so it stays enforced
at the write, like the structural gates (cycles, name collisions, MI).
Detection is `graphden.crud.type-check/secret-diagnostic?` — the
diagnostic's type-carrying keys (`:expected`/`:actual`/`:declared`/
`:computed`) folded with `contains-secret?`.

## Audited base-fns (T3)

| Package | Status |
|---|---|
| `core/strings` | All 22 fns propagate (content-passing). |
| `core/collections` | All 35 fns wrap existing structural rules. |
| `core/logic` | All 15 fns propagate (passthrough / conditional). |
| `core/arithmetic` | All 14 fns propagate (`:add` `:sub` `:mul` `:div` `:mod` `:quot` `:neg` `:abs` `:eq` `:neq` `:lt` `:lte` `:gt` `:gte`). `(eq secret 42)` leaks; `:lt` / `:gt` / `:eq` / `:neq` included. |
| `core/system` | 15 fns propagate. Bare environment readers (`:jvm-version`, `:env`, etc.) take no user input so taint can't enter — left bare. |
| `core/hof` | All 13 fns propagate (content-passing by construction: `coll` elements / `init` / the captured `value` flow into the result). Pre-2026-08-19 the package was unflagged — `(:map f secret-coll)` statically laundered the marker (result typed from `f`'s plain return). |
| `core/refinements` | The `:ensure-*` narrowers (`:ensure-positive-int`, `:ensure-non-empty-text`) preserve taint structurally — refinement impls carry no `:taint-propagate?` flag. |
| `web/html` | `:render-hiccup` / `:hiccup` propagate — they serialize/assemble a tree whose `[:list :any]` arm (and `:hiccup`'s `:any` attr values) can carry a secret. `:h-raw` is bare — its `:string` input can't accept a `[:secret :text]`. |
| `web/vault` | `:secret-leaf` declares `[:secret :text]` return directly. |
| `app/reprs` | `:render-value-repr` and `:tabulate-records` propagate — their record/`:any` inputs can carry a marked value and the output (repr hiccup / cell strings) derives from it. `:svg-polyline-points` (`[:list :numeric]` slot) and `:fn-return-type` (id in, type expr out) are bare — taint can't enter. |

`web/http-*` and `web/sql` SQL impls are sinks — they don't return
the input content, they send it as a side-effect. Mark a sink's
slot `[:secret :text]` to require its inputs be secret-aware
(currently `:sql-exec/:password` and `:sql-query/:password`).

## Backend hide at `/api/execute` (T4)

`graphden.crud.fn-execution.persist/redact-outcome` consults
`(tainted-fn? fn-name)`, which walks the rich-types registry's
`:return` via `types/contains-secret?`. If the marker is present:

- Inline succeeded response → `{status: succeeded, result: nil,
  tainted?: true}`. No value crosses the wire.
- Persisted row → `:result null`, `:error-data {:reason :tainted}`
  sidecar.
- Failed outcome → `:error` replaced with a generic message, since
  the exception text might embed the value.
- Cancelled outcomes pass through unchanged.

Both the synchronous inline path (`apply-execute`) and the
asynchronous tail-future path (`record-completion!`) call
`redact-outcome` before writing the row.

## Result rendering (T5)

The tainted-vs-plain decision is made at the SINK (T4 `redact-outcome`),
not in the browser — by the time a body reaches any renderer the secret
value is already gone (`:result nil`, `:error-data {:reason :tainted}`),
so there is no client-side "is this tainted?" branch to get wrong. The
former editor-side path (`isTaintedExecuteResponse` / `renderTaintedPane`
in `editor-execute-result.js`) was **removed 2026-06-18**.

Rendering is now a **server-rendered graph partial**: `/partials/execute-result`
(GET by `id`) and `/partials/execute-result-inline` (POST the non-persisted
inline body) share one `:render-execute-result-hiccup` walker
(`resources/packages/app/editor-execute/fns.edn`). It renders the
already-redacted body — a hidden/nil result simply shows no value — and
`editor-execute.js` swaps that HTML into the result host. The remaining JS
(`editor-execute-result.js`) is render-only spinner/pending/error helpers
with no taint logic.

## Admin UX — Secrets panel (B2 / C1 / C2)

The Secrets sidebar section (`editor-secrets.js`) lists every
fn-def whose `parent-ids` is exactly `[:secret-leaf]`. Each entry:

- **Name + path** in the row.
- **Rotate** — opens a popover with a single value field; on submit
  PUTs to `/api/secrets/:fn-id/value` which calls
  `clients.vault/put-secret` for a new KV v2 version.
- **Delete** — bounces through `/api/secrets/:fn-id` to atomically
  cascade-delete the OpenBao value AND the fn-def. The generic
  `/api/entities/fn/:fn-id` endpoint guards against the same
  delete-without-vault-cleanup hole.

`Create` form (path + value + name + description + namespace) opens
the same path: atomic vault-put + graphden fn-def create through
`crud/secrets/create-secret`. Rolls back via vault-delete if the
graphden write fails.

## Composing in user-graph

The current pattern (works through every protection layer above):

```clojure
;; admin: create secret via the Secrets panel (path + value)
;; → graphden auto-creates `_db-password parent :secret-leaf path:"user-db/password"`
;;   (with a `:vault-get` RESOLVER binding on `:in` — the path in :value)

;; user fn-def:
_my-sql-call
  :parent :sql-exec
  :args {:url      "jdbc:postgresql://..."
         :user     "userapp"
         :password <ref to _db-password>   ; slot is [:secret :text]
         :sql      "INSERT ..."
         :params   [...]}
```

`:sql-exec/:password` slot accepts the ref because
`[:secret :text] ⊆ [:secret :text]`. The same slot accepts a plain
literal too (auto-promote). The SQL impl receives the password value
at runtime — it's still a string, the marker only affects type-check
and the result-hide.

If `_my-sql-call`'s return-type doesn't carry the marker, `▶ Run` on
it returns the actual SQL result normally. If a user composed
`_my-sql-call`'s **inputs** into the response somehow (e.g., echoing
the password back), the return type would be tainted and the result
would be hidden.

## Secret-aware sinks (B: sink-side capability narrowing)

The asymmetric subtyping from T1 means **any plain `:text` slot on
a side-effecting base-fn automatically refuses secret-typed inputs
at sync-time**. That closes the casual exfil path "shove `<secret>`
into `:http-request/:url` or `:body` of a hand-rolled post".

It ALSO blocks the *legitimate* "secret as auth header" pattern,
because building `{"Authorization" (str-concat "Bearer " <secret>)}`
produces a record with a `[:secret :text]` value, which can't flow
into the `[:map :text :text]`-typed `:http-request/:headers` either.

The resolution is a **dedicated secret-bearing slot** declared
directly as accepting `[:secret :text]`, with the impl embedding the
value internally where impl-side trust is the only path:

| Sink | Slot taking `:secret` | Notes |
|---|---|---|
| `:http-request` (whole ladder) | `:auth-value` (`[:union :null [:secret :text]]`) | inherited by `:http-get` / `:http-post` / … presets |
| `:sql-exec` | `:password` (T6) | |
| `:sql-query` | `:password` (T6) | |
| `:vault-put` | `:value` (B) | |

On the HTTP ladder the split lives INSIDE the one primitive:
`:http-request`'s generic `:headers` slot stays `[:map :text :text]`
and refuses any secret to compose-leak through it, while the
dedicated `:auth-value` slot accepts `[:secret :text]` structurally
and the impl injects it as the `Authorization` header at the wire
boundary (auth wins on key collision). The slot takes the FULL
header value (scheme included — `"Bearer xxx"`, `"Basic xxx"`,
`"Token xxx"`, …) so one slot covers every scheme, with the caller
composing the scheme prefix at the graph layer (`:str` of
`"Bearer "` + the secret-typed token preserves the `[:secret :text]`
taint into the `:auth-value` boundary). Every per-method preset
(`:http-get`, `:http-post`, …) inherits both slots unchanged;
`:http-get-with-authorization` survives as a back-compat rename
preset (`:headers` re-exposed as `:extra-headers`).

This is the "B: sink-side capability narrowing" pattern. It doesn't
prevent the impl from then sending the secret over the wire (that's
the whole point — auth works), but it FORCES every secret-bearing
sink to declare itself, so adding a new exfil channel is a visible
code change, not an accidental composition.

`:headers` (and its `:extra-headers` rename) stays plain
`[:map :text :text]` — a user can't sneak a SECOND secret into the
headers map under the cover of the legitimate token. Defence-in-
depth against "one legit auth + one exfil header".

## `:any`-slot escape hatch

`[:secret T] ⊆ :any` is TRUE (T1's early-case "sup = :any → true"),
so a secret flowing into a generic `:any` slot LOSES the marker at
the type level. This is the documented escape hatch — but in
practice it's defanged by the T3 audit: every content-passing fn
with `:any` slots (`:assoc`, `:get`, `:conj`, `:select-keys`,
`:invoke`, etc.) carries `:taint-propagate? true`, so the
RESULT type is lifted back into `[:secret …]` and the marker
round-trips.

Audit of `:any`-slot uses across the standard library:

| Category | Example | Mitigation |
|---|---|---|
| Content-passing collection ops | `:assoc/:value`, `:conj/:item`, `:get/:default` | T3 propagator on each — secret-in → secret-out at type level |
| HOF callable arg | `:invoke/:func` (the fn-typed arg's args may be `:any`) | T3 propagator on `:invoke` lifts the call result |
| `:slurp/:input` | InputStream/Reader source | n/a — not a content-bearing string slot |
| `:throw/:exception` | Exception value | **Limit**: see #2 below |

No `:network` / `:io` / `:db` sink uses `:any` for a content slot
(verified by grep across `resources/packages/web/*/fns.edn`).
The only remaining concern is `:throw` — see Known limits.

## Sharing / export policy — "never silently"

A secret's VALUE never enters graph storage, so no export can leak it.
The vault PATH does live in the graph (`binding.value` on a
`:vault-get`-resolver binding) and is org-topology information — so
every share-shaped bundle treats it explicitly:

- **`GET /api/export/graph`** and **`POST /api/packages/publish`**
  STRIP vault paths by default: the `{:secret-path …}` arg entry is
  dropped, the slot reverts to a free `[:secret T]` arg, and the
  bundle carries a `:secrets` manifest (`[{:fn … :arg …} …]`) plus a
  `:secret-paths-included?` flag. Both sides are told: the publish
  response (and the Packages-panel publish notice) lists what was
  stripped; the install envelope surfaces the same manifest as
  `:needs-definition` (and the panel shows a "needs secrets defined"
  notice). `GET /api/export/graph?include-secret-paths=true` opts back
  in for org-internal migration (same vault on both ends).
- **Round-trip form**: the exporter emits `{:secret-path "kv/path"}`
  (never a `{:value …}` literal — the wire keyword survives the enum
  retirement) and the parser restores a `:vault-get`-resolver binding
  from it — so an included path re-imports as a working secret
  binding, not as a literal string holding the path.
- **`GET /api/export/graph-rows`** (BYO-executor bootstrap) keeps
  `:secret-path` rows verbatim BY DESIGN: it is an org-scoped,
  auth-required operational channel and the remote executor needs the
  path to deref the org's own vault at run time. It is not a sharing
  surface.
- An installed-but-undefined secret is just a fn whose `:in` slot is a
  free `[:secret :text]` arg — validate-execute reports it, and the
  editor's inline secret-binding form (vault path + write-only value)
  is the affordance to define it.

## Path-trace capture (T6)

The Debug path trace (docs/EXECUTION.md § Path trace) captures
per-frame return values — a second read surface with its own
layered redaction:

- **Capture-time classification** (`registry/trace-capture-class`):
  a secret-touching frame records `{:hidden :secret}` and its value
  is never read into the buffer; a frame with NO registry entry
  fails CLOSED (`{:hidden :unknown-type}`) — absence of type
  information hides, it does not capture.
- **Ancestor poisoning** — a `:secret`-returning (or unknown) frame
  marks every open ancestor frame; those record `:value-hidden
  :secret-derived` instead of a value. This is the DYNAMIC
  complement to the static rules: it covers flows the checker can't
  see (`:any` slots, cells reset to secrets, unregistered fns) for
  the concrete execution being traced.
- **Read-time re-redaction** (`persist/re-redact-path-trace`) —
  every read of a stored trace re-classifies through the CURRENT
  registry, so a fn that became secret after the run stops serving
  its historical captured values; ancestor chains re-poison via the
  stored `:parent-seq` links.
- **HOF lambda bodies stay untraced** — `hof-wrap` invokes them
  outside the trace seam, so per-item lambda values never enter the
  buffer (a blind spot, but a leak-free one; tracing them would
  need a per-value secret story first).

## Known limits

1. **Side-effect exfiltration via secret-aware sinks themselves** —
   once a sink declares a `[:secret :text]` slot, it's TRUSTED to
   handle the value responsibly. A malicious impl of
   `:http-request` could log the token before sending it.
   That's why the secret-aware sink list above is audited — adding
   a new one is a visible code change, not an accidental composition.
   Same trust model as today's `:io` impls: at some point the
   secret leaves the type system, and the impl that handles it is
   the line of defence.

2. **Exception messages can leak through `:throw`** — `:throw`'s
   `:exception :any` slot accepts any value including a string
   built via `(:str "secret is " <secret-leaf-ref>)`. The
   propagator on `:str` lifts the result to `[:secret :text]`; the
   `:exception :any` slot accepts it (escape hatch). At runtime
   the exception fires; the exception MESSAGE embeds the secret.
   `/api/execute` redacts the error string ONLY when the fn-def's
   recorded return-type carries `:secret` (T4) — a fn whose return
   is plain `:int` but which throws-with-secret internally would
   leak the message. The audit trail (below) detects this case at
   runtime by inspecting whether the execution touched a secret-
   typed binding transitively; tighter mitigation (rewrite the
   error message conditionally) is reserved for a later pass.

3. **`:any`-typed slots** are the documented escape hatch. A
   `[:secret :text]` value flows into an `:any` slot and the marker
   is lost. This composes into a two-step laundering path the direct
   guards can't see: `[:list [:secret :text]] ⊆ [:list :any]`
   (covariance + top) and `[:list :any] ⊆ :jsonb` — so a
   list-of-secrets can reach a jsonb sink through an `:any`-widened
   intermediate even though the direct `[:list [:secret :text]] ⊆
   :jsonb` step is refused (`contains-marker?` guard, in BOTH
   `subtype?` and `unify` since 2026-08-15). Label tracking ends
   where `:any` begins — that is the price of having a top type. A
   future audit pass could narrow specific `:any` slots to refuse
   secrets.

4. **`:secret-leaf` is gated at the create path.** A user can't
   `parent :secret-leaf` directly through `/api/entities/fn` or any
   other generic create path — the gate in `crud.entities/create-
   entity` (via `secret-leaf-capability-rej`) refuses any `:fn`
   create whose `:parent-ids` contains the `:secret-leaf` row UNLESS the data
   carries the `:_admin-secret-create` marker, which only
   `crud.secrets/create-secret` sets (and strips before the row
   reaches storage). The Secrets-panel admin path continues to
   work; everything else gets a 409 / 400 with a pointer to
   `/api/secrets`. The orthogonal "write any secret from user-graph
   via `:vault-put`" hole is closed by the same gate covering
   `:vault-put`, `:vault-delete`, `:vault-metadata-put`.

## Audit trail

`:fn-execution.touched-secret?` is set to `true` on rows where
BOTH halves are satisfied:

- `(persist/touches-secret? fn-name)` — the rich-types registry
    entry for the executed fn-def carries a `:secret` marker on
    its return OR on any of its declared arg slots. Strictly
    broader than `tainted-fn?`: a sink like `:sql-exec` whose
    `:password` slot is `[:secret :text]` but whose return is
    `:int` still trips this predicate.

- `(seq runtime-effects)` — the run actually observed a
    side-effect (`:network`, `:io`, `:db`, …). A pure tainted-
    aware run that produced no effects isn't an audit event.

The combination — "secret crossed into a side-effecting sink" — is
what admins want to review for exfiltration. Persisted as a
boolean column (`fn-execution-touched-secret-field-uuid` in
`schema/executions/schema.clj`); not on the hot path.

Read access: `GET /api/executions?fn-id=X` returns the flag on
each row, and the Runs tab reads it: a flagged row carries a 🔒, and
the **Secret flows** chip in the rollup strip narrows the list to
those rows (`/partials/execute-history?fn-id=X&secrets=1`).

The flag is an audit marker only — it does NOT hide anything. The
Run pane's `Result hidden` notice keys on the redactor's own markers
(`:tainted?` on the inline response, `:error-data {:reason :tainted}`
on a persisted row), which fire only when the fn's RETURN carries the
marker. An effectful run of a fn with a merely secret-capable slot
(`:http-get` with its `:auth-value`) trips the audit flag and still
shows its response (`execute_result_tainted_test`).

## Binding-IS-secret model

The secret lives as a TYPED BINDING, not a separate fn-def shape.
Three pieces:

- **Schema**: the binding's `:resolver-fn-id` FK pointing at
    `:vault-get` — the generic value-resolver mechanism
    (`schema/graph/schema.clj`). The old `:override-kind
    :secret-path` enum marker is fully RETIRED: stage 1
    (2026-07-23) switched writers to the resolver form with an
    idempotent boot migration for legacy rows, and stage 2b has
    since **dropped the column** (`ds/retire-field :binding
    :override-kind` in `schema/graph/schema.clj`) — there is no
    read-compat branch left (the enum's other values were dead:
    `:fixed` superseded by `:terminal`, `:default` write-only).

- **Executor**: `compile/bindings.clj/classify-slot` recognises a
    value-binding carrying `:resolver-fn-id` and emits a
    `:kind :resolved-value` shape (`:stored` = the binding's
    `:value`, i.e. the vault path). At arg-resolution time the
    resolver graph fn (`:vault-get`) is executed with the stored
    value as input, calling `clients.vault/get-secret` on
    `(:vault ctx)`. The dereferenced secret flows into BOTH
    `:args` (what the impl receives) and `:aug` (so inner
    ref-chains that reference the slot by ext-name receive the
    same value).

- **Validation gate**: `crud/validation.clj/resolver-rej` — when a
    binding's resolver fn's registered RETURN carries a
    hide-result marker (`:vault-get` → `[:secret :text]`), the
    target slot's rich type must carry a marker too. Without this
    gate, a user could point any plain `:text`-typed binding at
    `:vault-get`, the executor would dereference, and the secret
    value would silently flow into a non-secret slot — bypassing
    T1's structural enforcement at runtime.

The Secrets-panel admin flow writes new secrets with this shape:

- `:secret-leaf` base-fn (in `web/vault/fns.edn`) — a pure
    passthrough whose `:in` slot is `[:secret :text]`. The impl
    just returns its arg; the executor's `:resolved-value` case
    has already dereferenced via vault by the time the impl runs.
- `crud/secrets/create-secret` writes
    `parent-ids=[<secret-leaf-id>]` + a binding with
    `:resolver-fn-id=<vault-get>` + `:value=<path>`.
- `crud.secrets/find-usages`, `delete-secret`, `rotate-secret`
    accept only the secret-leaf shape — `shape/secret-fn?` takes
    the secret-leaf id and checks `[secret-leaf-id]` parent-ids.
- `crud.entities/secret-leaf-capability-rej` gates
    `:secret-leaf`, `:vault-put`, `:vault-delete`,
    `:vault-metadata-put`, so user-graph can't `parent` any of
    those via generic endpoints.
- Editor `isSecretFn` (in `editor-secrets.js`) renders the 🔒
    badge on every fn-def whose `parent-ids` is exactly
    `[:secret-leaf]`.
- `crud.secrets/create-secret` explicitly calls
    `tc/type-check-fn-after-mutation!` on the new fn-def so its
    inherited `[:secret :text]` return-type lands in the rich-
    types registry; otherwise `tainted-fn?` wouldn't see the
    marker and T4's redaction wouldn't fire.
- `validation/secret-path-rej` walks `binding.slot-id →
    fn-slot.fn-id → fn.name → rich-types[name] → :args →
    slot-name keyword → :type` and asks `contains-secret?`. The
    storage layer drops `[:secret T]` down to its inner type's
    `fn-id` for the `:type-fn-id` foreign key (see
    `packages/records/types.clj`), so the marker lives only in
    the rich-types registry on the slot-owning fn-row's args; the
    validation gate has to read it from there.

## Inline secret-path binding

When a slot's effective type carries a hide-result MARKER
(`[:secret T]`, or any graph-declared marker with `:hide-result?
true`), the dispatch is form-by-type THROUGH THE GRAPH: the
value-form registry (`:_value-form-registry` in `app/forms/fns.edn`)
maps the marker type to `:_form-secret-binding` via a vector-typed
row (`[["secret" "any"] "_form-secret-binding"]`); `/api/value-form`
answers with a `data-form-widget="secret-binding"` mount, and the
editor routes on THAT (`formWidgetName` in `editor-edit-modes.js`) —
no tag names live client-side (the old hardcoded `isSecretType`
branch is gone). A new marker opts in by adding its own registry row.
For a new binding the editor opens a dedicated 2-field form:

| Field | Purpose |
|---|---|
| Vault path | The KV v2 location the secret lives at (e.g. `postgres/password`). Stored in `binding.value`. |
| Initial value | The actual secret string. POSTed to vault, never persisted in graphden. |

On submit the form POSTs to `/api/secret-bindings` (sibling to
`/api/secrets/*` — separate path to avoid reitit's
`/secrets/:fn-id` literal-vs-param conflict). The handler
(`graphden.crud.secrets/create-inline-binding`) does:

1. Validate `fn-id` + `slot-id` exist; reject if a binding already
   exists on `(fn-id, slot-id)`.
2. `vault/put-secret` at the supplied path.
3. `crud-entities/create-entity :binding` with
   `:resolver-fn-id <vault-get>` and `:value <path>`. This runs the
   normal `resolver-rej` gate — the gate rejects when the slot's
   rich-type doesn't carry `:secret`, in which case we
   `vault/delete-secret` to keep the stores consistent.

The frontend branch only fires when the slot has NO existing binding
(`!arg['binding-id']`). For an existing binding, the regular popover
opens — the user must `Delete` first to revert to a free-arg, then
re-open to bind the secret. Rotation of an inline-bound secret is
covered by `PUT /api/secrets/:fn-id/value` for the wrapper-fn-def
shape only — inline rotation is followup work.

Verified end-to-end in 2026-05-29 smoke:

- Gate-reject: a legacy `:override-kind` write → `:constraint-violation/override-kind-retired`; a `:vault-get`-resolver write into a non-secret slot → `:capability/resolver-marker-laundering`, vault rolled back (subsequent read 404).
- Positive bind on `:sql-exec/:password` of a freshly-created composed fn → `binding` row with the `:vault-get` resolver + `:value` path, vault holds the value at the path.

## Tests

| File | What it covers |
|---|---|
| `test/graphden/types/core_test.clj` | `[:secret T]` predicates, well-formed, subtype asymmetry (5 cases), `:any` escape hatch, `:jsonb` non-launder, alias resolution, freshen recursion, `contains-secret?` recursion, `taint-with-secret-if-tainted` propagation rules. |
| `test/graphden/types/check_test.clj` | `enforce-declared-return!` relaxation for tainted computed against plain declared; tainted ref bubbles into recorded return; clean inputs leave plain; structural leak rejection; auto-promote into secret slot. |
| `test/graphden/crud/fn_execution_test.clj` | `apply-execute` hides result for `[:secret …]`-return fn-defs; persisted row stores `:result nil + :error-data {:reason :tainted}`. |
| `test/graphden/integration/secret_flow_test.clj` | End-to-end: secret-leaf → str-upper composes (recorded return `[:secret :text]`), downstream plain-text sink rejects, plain text into secret slot auto-promotes, secret-ref into secret slot composes. |

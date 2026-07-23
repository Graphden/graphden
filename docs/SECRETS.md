# Secrets — `:secret` information-flow type-marker

This doc describes how graphden protects secret values (DB passwords, API
tokens, etc.) from leaking through composition and through the `▶ Run`
result-pane. The protection is structural — implemented in the type
system + the executor's response shaper — not value-based; transformations
like `base64(secret)` and `hash(secret)` are also hidden because the
TYPE of the result carries the marker, regardless of what the value
actually is.

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

```
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

## Audited base-fns (T3)

| Package | Status |
|---|---|
| `core/strings` | All 18 fns propagate (content-passing). |
| `core/collections` | All 35 fns wrap existing structural rules. |
| `core/logic` | All 11 fns propagate (passthrough / conditional). |
| `core/arithmetic` | All 13 fns propagate. `(eq secret 42)` leaks; `:lt` / `:gt` / `:eq` / `:neq` included. |
| `core/system` | 12 fns propagate. Bare environment readers (`:jvm-version`, `:env`, etc.) take no user input so taint can't enter — left bare. |
| `core/refinements` | All 7 `:ensure-*` narrowers propagate. |
| `web/html` | `:render-hiccup` / `:hiccup` propagate — they serialize/assemble a tree whose `[:list :any]` arm (and `:hiccup`'s `:any` attr values) can carry a secret. `:h-raw` is bare — its `:string` input can't accept a `[:secret :text]`. |
| `web/vault` | `:secret-leaf` declares `[:secret :text]` return directly. |

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

## Editor-side rendering (T5)

`editor-execute-result.js`:

- `isTaintedExecuteResponse(body)` — detects both the inline shape
  (`{tainted?: true}`) and the persisted shape
  (`{error-data: {reason: "tainted"}}`).
- `renderTaintedPane()` — a 🔒 card with "Result hidden" + a one-line
  hint pointing at the debug-with-plain-text workflow.

`editor-execute.js` checks `isTaintedExecuteResponse` BEFORE the
normal result render in both the inline-success path and the
polling-finalisation path.

`editor-execute-history.js` shows a 🔒 badge in the row head and
the preview text "hidden — secret-typed".

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
;;   (with a `:override-kind :secret-path` binding on `:in`)

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
into `:http-get/:url` or `:body` of a hand-rolled post".

It ALSO blocks the *legitimate* "secret as auth header" pattern,
because building `{"Authorization" (str-concat "Bearer " <secret>)}`
produces a record with a `[:secret :text]` value, which can't flow
into `[:map :text :text]`-typed `:http-get/:headers` either.

The resolution is to provide **secret-aware sibling base-fns** that
declare the secret-bearing slot directly (`[:secret :text]`) and
embed the value internally where impl-side trust is the only path:

| Sink | Secret-aware variant | Slot taking `:secret` |
|---|---|---|
| `:http-get` | `:http-get-with-authorization` | `:auth-value` |
| `:sql-exec` | `:sql-exec` itself (T6) | `:password` |
| `:sql-query` | `:sql-query` itself (T6) | `:password` |
| `:vault-put` | `:vault-put` itself (B) | `:value` |

The split is intentional: plain `:http-get` stays generic-payload
(`[:map :text :text]` headers, `:text` url) and refuses any secret
to compose-leak through it. Users who need auth flow MUST go
through `:http-get-with-authorization`, which makes the auth-bearing
slot's type EXPLICIT. The slot accepts the FULL `Authorization`
header value (scheme included — `"Bearer xxx"`, `"Basic xxx"`,
`"Token xxx"`, …) so a single sink covers every scheme, with the
caller composing the scheme prefix at the graph layer
(`:str` of `"Bearer "` + the secret-typed token preserves the
`[:secret :text]` taint through the `:auth-value` boundary).

This is the "B: sink-side capability narrowing" pattern. It doesn't
prevent the impl from then sending the secret over the wire (that's
the whole point — auth works), but it FORCES every secret-bearing
sink to declare itself, so adding a new exfil channel is a visible
code change, not an accidental composition.

`:extra-headers` on `:http-get-with-authorization` stays plain
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
The vault PATH does live in the graph (`binding.value` under
`:override-kind :secret-path`) and is org-topology information — so
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
  (never a `{:value …}` literal) and the parser restores
  `:override-kind :secret-path` from it — so an included path
  re-imports as a working secret binding, not as a literal string
  holding the path.
- **`GET /api/export/graph-rows`** (BYO-executor bootstrap) keeps
  `:secret-path` rows verbatim BY DESIGN: it is an org-scoped,
  auth-required operational channel and the remote executor needs the
  path to deref the org's own vault at run time. It is not a sharing
  surface.
- An installed-but-undefined secret is just a fn whose `:in` slot is a
  free `[:secret :text]` arg — validate-execute reports it, and the
  editor's inline secret-binding form (vault path + write-only value)
  is the affordance to define it.

## Known limits

1. **Side-effect exfiltration via secret-aware sinks themselves** —
   once a sink declares a `[:secret :text]` slot, it's TRUSTED to
   handle the value responsibly. A malicious impl of
   `:http-get-with-authorization` could log the token before sending it.
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
   is lost. A future audit pass could narrow specific `:any` slots
   to refuse secrets.

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
each row. The future "Secret flows" history tab can filter on
`touched-secret? = true` to surface just the audit-relevant rows.

## Binding-IS-secret model

The secret lives as a TYPED BINDING, not a separate fn-def shape.
Three pieces:

- **Schema**: the binding's `:resolver-fn-id` FK pointing at
    `:vault-get` — the generic value-resolver mechanism
    (`schema/graph/schema.clj`). The old `:override-kind
    :secret-path` enum marker is RETIRED (audit-2 stage 1,
    2026-07-23): writers emit the resolver form only, a boot
    migration (`system.core/migrate-secret-path-bindings!`)
    rewrites legacy rows idempotently, and the executor keeps a
    read-compat branch for not-yet-migrated rows until stage 2
    drops the column (the enum's other values were dead: `:fixed`
    superseded by `:terminal`, `:default` write-only).

- **Executor**: `compile/bindings.clj/classify-slot` recognises
    `:override-kind :secret-path` and emits a `:kind :secret-value`
    shape. The eager arg-builder (`compile_eager.clj`, via the
    `vault-get-secret` delay) handles `:secret-value` by calling
    `clients.vault/get-secret` on `(:vault ctx)` with the binding's
    `:value` field as the path. The dereferenced
    secret flows into BOTH `:args` (what the impl receives) and
    `:aug` (so inner ref-chains that reference the slot by ext-name
    receive the same value).

- **Validation gate**: `crud/validation.clj/secret-path-rej`
    refuses `:override-kind :secret-path` on slots whose effective
    rich-type doesn't carry the `:secret` marker. Without this
    gate, a user could mark any plain `:text`-typed binding as
    secret-path, the executor would dereference, and the secret
    value would silently flow into a non-secret slot — bypassing
    T1's structural enforcement at runtime.

The Secrets-panel admin flow writes new secrets with this shape:

- `:secret-leaf` base-fn (in `web/vault/fns.edn`) — a pure
    passthrough whose `:in` slot is `[:secret :text]`. The impl
    just returns its arg; the executor's `:secret-value` case
    has already dereferenced via vault by the time the impl runs.
- `crud/secrets/create-secret` writes
    `parent-ids=[<secret-leaf-id>]` + a binding with
    `:override-kind :secret-path` + `:value=<path>`.
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
   `:override-kind :secret-path` and `:value <path>`. This runs the
   normal `secret-path-rej` gate — the gate rejects when the slot's
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
- Positive bind on `:sql-exec/:password` of a freshly-created composed fn → `binding` row with `:override-kind :secret-path`, vault holds the value at the path.

## Tests

| File | What it covers |
|---|---|
| `test/graphden/types/core_test.clj` | `[:secret T]` predicates, well-formed, subtype asymmetry (5 cases), `:any` escape hatch, `:jsonb` non-launder, alias resolution, freshen recursion, `contains-secret?` recursion, `taint-with-secret-if-tainted` propagation rules. |
| `test/graphden/types/check_test.clj` | `enforce-declared-return!` relaxation for tainted computed against plain declared; tainted ref bubbles into recorded return; clean inputs leave plain; structural leak rejection; auto-promote into secret slot. |
| `test/graphden/crud/fn_execution_test.clj` | `apply-execute` hides result for `[:secret …]`-return fn-defs; persisted row stores `:result nil + :error-data {:reason :tainted}`. |
| `test/graphden/integration/secret_flow_test.clj` | End-to-end: secret-leaf → str-upper composes (recorded return `[:secret :text]`), downstream plain-text sink rejects, plain text into secret slot auto-promotes, secret-ref into secret slot composes. |

# Lesson 07 — Effects and the `:secret` type-marker

**Goal**: by the end of this lesson you understand how effects
get declared and propagate, what `:expects-effects` is for, and
how the `:secret` type-marker prevents secret values from
leaking into non-secret sinks.

**Concepts introduced**: `effect category`, `:effects` (computed),
`:expects-effects` (declared), `effect drift`, `:secret` type-
marker, `taint propagation`, `return-type-rule`, `executor hide
on :secret`.

## The eight effect categories

```
:db        Reads or writes graphden's storage
:network   Outbound HTTP / TCP
:io        Disk / filesystem
:env       Reads OS environment variables
:time      Reads wall-clock time
:random    Non-deterministic input
:process   Spawns supervised background work (service-eligibility marker)
:raw-sql   Arbitrary SQL bypassing org-scoped storage (cloud-blocked)
```

Each category is a single keyword. Effects are EXPLICIT — there's
no "effectful? true" generic flag. If you write
`:effects #{:do}`, the validator rejects: `:do` isn't in the
vocabulary.

Why so few? Because each category corresponds to a real
property a caller might care about:

- "is this pure?" → check `:effects` is empty
- "does this hit the DB?" → check `:effects` contains `:db`
- "can this fn be a service?" → check it has `:process`

The category set is closed by design (see [docs/PHILOSOPHY.md
§ Effects](../PHILOSOPHY.md)).

## Where effects come from

Base-fns DECLARE their effects in `fns.edn`:

```edn
{:name :pg-query
 :args {:sql {:type :text}}
 :return-type :jsonb
 :effects #{:db}}

{:name :sha256-hex
 :args {:input {:type :bytes}}
 :return-type :text
 :effects #{}}   ; pure
```

Composed fn-defs DON'T declare their own effects — the type-
checker COMPUTES them from the parent chain + every ref in
their bindings. So:

```edn
{:name :user-query
 :parent :pg-query
 :args  {:sql "SELECT id, name FROM users"}}
```

`:user-query`'s computed effects are `:pg-query`'s effects:
`#{:db}`. If `:user-query` also ref'd `:current-time-ms`
(which has `:effects #{:time}`), the computed set would be
`#{:db :time}`.

The editor's effects-strip on each fn-card shows the computed
set as colored chips — one per category.

## `:expects-effects` — the author's contract

Optionally, the author can DECLARE what effects they expect
their fn to have:

```edn
{:name :user-query
 :parent :pg-query
 :args  {:sql "SELECT id, name FROM users"}
 :expects-effects #{:db}}
```

The type-checker compares declared vs computed and surfaces
DRIFT:

- **Computed ⊃ declared** — the fn produces an effect the
  author didn't expect. Likely a bug (a hidden ref pulled in
  something). Editor draws a red outline on the drift chip.
- **Computed ⊂ declared** — declared an effect that never
  actually fires. Harmless but stale; editor draws the chip as
  a ghost (outlined).

`:expects-effects` is OPTIONAL. Most fn-defs don't bother — the
computed set is enough. The cases where you'd add it:

- A library-boundary fn-def where you want the contract pinned
  (so a future ref-edit doesn't silently add a network call).
- A service-eligible fn where `:process` MUST be present (the
  service-create guard asserts this from rich-types).

## Editing the contract from the card (✎)

You don't have to touch `fns.edn` to manage `:expects-effects` —
the effects strip at the bottom of a fn-card carries a `✎`
pencil (visible when you're signed in and the card is the
selected fn). Clicking it opens a small server-rendered form:

- Two radio modes: **no contract** ("Drift checker is off for
  this fn") and **explicit contract** ("Drift checker compares
  computed effects against the ticked set").
- Under them, one checkbox per declarable category — the full
  canonical set of eight, including `:process` and `:raw-sql`.
  The checkboxes stay disabled until you pick *explicit
  contract*.
- **Pinned purity**: pick *explicit contract* and tick NOTHING.
  That saves an EMPTY declared set — "I assert this fn is
  pure" — so any effect that later creeps in via a ref edit
  lights up as drift.

Save writes the fn row's `:expects-effects`; switching back to
*no contract* and saving clears it (the drift checker turns
off). The checkbox roster comes from the same server-side set
that sync-time validation accepts, so the form can never offer
an undeclarable category.

### Try it

1. Select any pure fn-def of yours (e.g. the `:greet` from
   lesson 01) and click `✎` on its (empty) effects strip.
2. Pick *explicit contract*, tick nothing, Save. You've pinned
   purity.
3. Now bind one of its args to a ref that reaches `:env` or
   `:pg-query`. The computed effect appears as a chip with a
   red outline — drift against your pinned-pure contract.
4. Reopen `✎` — the form comes back pre-filled from the saved
   contract. Tick the offending category (or switch to *no
   contract*) and Save to clear the drift.

## Effect propagation through HOFs

When a HOF takes a `:fn`-typed callback, the callback's effects
lift onto the HOF's effective set. So:

```edn
{:name :map-and-save
 :parent :map
 :args  {:func :save-record  ; :save-record has :effects #{:db}
         :coll :all-users}}
```

`:map`'s OWN declared effects are `#{}` (the impl is pure — it
just iterates). But `:map-and-save`'s computed effects include
`:db` because the callback ref'd from `:func` has `:db`.

The propagation is automatic. The type-checker walks `:fn`-
typed bindings and unions in the referenced fn's effect set.

## The `:secret` type-marker

`:secret` is a TYPE-LEVEL marker — not an effect, not a field.
It wraps an existing type to mark "this value is sensitive":

```edn
{:name :secret-leaf
 :args {:in {:type [:secret :text]}}
 :return-type [:secret :text]
 :tags #{:admin-only-vault :secret-shape}}
```

`[:secret :text]` is a structural type (lesson 05). Its key
property is **asymmetric subtyping**:

- `:text` ⊆ `[:secret :text]` — a plain text VALUE
  flows into a secret slot (the slot wraps the value with
  taint).
- `[:secret :text]` ⊄ `:text` — a secret value REFUSES to
  flow into a plain text slot. Sync-time type-check rejects.

The asymmetry is the security property. Once a value is
secret-tainted, it can ONLY land in slots that are also
typed `[:secret …]`. Plain `:text` sinks (like a log statement
or a response body) are sync-time errors.

### Per-base-fn `:return-type-rule`

For base-fns that handle user data (e.g. `:str-concat`,
`:get`), the rule for propagating taint is declared at the
impl-side via `:return-type-rule`:

- `taint-with-secret-if-tainted` — if ANY arg is `[:secret T]`,
  the return is `[:secret T']`. So `(str-concat "Hello "
  username)` where `username` is secret produces a secret
  string.
- `wrap-with-taint` — return is always `[:secret T]` (used by
  vault-getters: anything that comes out is secret).

The rules live in each base-fn's `impls.clj` map (see
`docs/SECRETS.md` for the audit of which base-fns propagate).
You don't write them; library authors do. As a fn-def author,
you just see the chip turn `[:secret :text]` and know "this
value is now tainted."

## The executor hides secret returns

When a fn's return type is `[:secret T]`, `/api/execute` does
NOT include the result in the response. Instead:

```json
{
  "status": "succeeded",
  "result-hidden": true,
  "result-type": ["secret", "text"]
}
```

The editor's execute-result pane shows "Result hidden — value
is secret-typed" instead of the actual value. History rows show
a 🔒 badge instead of a truncated value preview.

This applies AT THE BOUNDARY (HTTP response). Inside the
graph, secret values flow freely between fn-defs — they're
just typed.

## The admin secrets UX

The Secrets sidebar panel in the editor is the canonical
admin flow:

1. Admin clicks `+` on the panel.
2. Form asks for name, vault path, value, description.
3. Submit writes:
   - The secret VALUE to OpenBao at the given path.
   - A fn-def parented from `:secret-leaf` with a `:secret-path`
     binding (override-kind `:secret-path`) carrying the vault path.
4. Other fn-defs ref the new secret-leaf fn-def. At execute
   time, the executor reads the vault path from OpenBao,
   binds the value to the slot, runs the rest of the graph.

The graphden DB never holds the secret value. Only the path.

## Try it

1. Find `:current-time-ms` in the editor. Its effects strip
   shows ONE chip: `:time`. Click the chip — the explainer
   popover gives the plain-English description.

2. Build a fn-def with deliberate drift:

   ```edn
   {:name :tutorial-pure-claim
    :parent :env
    :args  {:name {:value "AUTH_TOKEN"}}
    :expects-effects #{}}
   ```

   `:env` has `:effects #{:env}`. You declared `#{}`. Save —
   the chip strip shows a RED `:env` chip (drift: undeclared).
   The hover-title says "Drift (undeclared)".

3. Find any `:secret-leaf`-parented fn-def. Try ref'ing it
   from a slot typed `:text`:

   ```edn
   {:name :tutorial-secret-leak
    :parent :str-concat
    :args  {:parts ["token=" :my-secret-leaf]}}
   ```

   Save — type-checker rejects: `[:secret :text]` ⊄ `:text` for
   slot `:parts.[*]`. The error explains the asymmetric
   subtyping rule.

   …unless `:str-concat`'s `:return-type-rule` is
   `taint-with-secret-if-tainted`, in which case the bind passes
   and the RETURN type becomes `[:secret :text]`. The poison
   spreads upward — which is the intended behavior.

## What we glossed over

- **Effect drift logging at execute time** — the runtime ALSO
  observes which effects actually fired and logs drift against
  the declared set. So `:expects-effects #{}` for a fn that
  hits the network at runtime gets a drift log entry, not just
  the editor red outline.
- **`:effects #{:do}` and other invalid tags** — the validator
  rejects unknown categories at sync time. See `types.check`.
- **Taint-flow audit** — which base-fns currently propagate
  taint vs which don't (and why). See [docs/SECRETS.md](../SECRETS.md).

## Next

Lesson 08 — Branches ([already written](08-branches.md))

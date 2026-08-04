# Graph-native HTML partials (HTMX)

How editor popovers / panels / sub-lists get their content from the
graph instead of being built in JavaScript. Every partial is a chain
of fn-defs in `app/editor/fns.edn` that returns hiccup, exposed at
`GET /partials/<name>?...` as `text/html`. The editor's JS either
fetches the partial explicitly and swaps the response, or — with
HTMX wired up — the server fragment itself declares the trigger /
target / swap mode via `hx-*` attributes.

The win is graph control: the popover's TEXT (descriptions, labels,
help blurbs), STRUCTURE (rows, headers, sections), DATA (which DB
query feeds the list), and INTERACTIVITY (which row click fetches
what) all live as fn-defs that the editor itself can inspect,
type-check, branch-isolate, and rewrite. JS shrinks to inherent
client concerns (mount lifecycle, anchored positioning, dismissal).

Many editor popovers/panels already fetch server partials — see the
`:partial-*` fn-defs in `app/editor/fns.edn` (effect-explainer,
fn-versions, branch-diff, provenance, secrets, mismatch-explainer,
execute-history, service-popover, …). That set is the template for any
remaining client-built popover.

---

## Architecture

```
        ┌────────────────────────────────────────────────────────┐
        │                  Editor page (HTML)                    │
        │   ┌───────────┐                                        │
        │   │  JS module│  fetch(/partials/X?…)  ←──┐            │
        │   │ (~lifecycle│                          │            │
        │   │   + mount) │  innerHTML = response    │            │
        │   └───────────┘  htmx.process(mount)      │            │
        │                                           │            │
        │   ┌──────────────────────┐                │            │
        │   │ Mount-point <div>    │  ◄─────────────┘            │
        │   │ swapped innerHTML    │                             │
        │   │ may carry hx-* attrs │  ─── secondary swap ──┐     │
        │   └──────────────────────┘                       │     │
        │                                                   │     │
        │   ┌──────────────────────┐                        │     │
        │   │ Inner mount-div      │  ◄─── htmx hx-get ─────┘     │
        │   │ (data-execs-mount,   │                              │
        │   │ filled by row click) │                              │
        │   └──────────────────────┘                              │
        └────────────────────────────────────────────────────────┘
                                  │
                                  │ GET /partials/X
                                  ▼
            ┌──────────────────────────────────────────┐
            │   app.editor partial fn-def chain         │
            │   :_partial-X-name        — request parse │
            │   :_partial-X-data        — DB / state    │
            │   :_partial-X-row         — per-row HOF   │
            │   :_partial-X-fragment    — full hiccup   │
            │   :_partial-X-html        — render-hiccup │
            │   :_partial-X-handler     — text/html OK  │
            └──────────────────────────────────────────┘
```

Two-layer fetch model:

- **Outer**: explicit JS fetch (`authFetch` for auth-required routes;
  plain `fetch` otherwise) → swap into a top-level popover mount.
  Always call `htmx.process(mount)` AFTER `innerHTML =` so any
  `hx-*` attributes inside the swapped content get bound — HTMX
  only auto-binds at page-load, not on later DOM writes.

- **Inner** (optional, only when the partial has lazy sub-panels):
  HTMX-driven via `hx-get` / `hx-target` / `hx-swap` on elements
  inside the outer fragment. No JS needed for these — the swap +
  binding happens automatically once `htmx.process` is called.

---

## Recipe — adding a new partial

1. **Find an existing data chain** that produces what the popover
   shows as JSON. Look in `app/branches/fns.edn`,
   `app/execution/fns.edn`, etc. for the `*-handler` whose
   `json-handler :data` references the data-producing fn-def
   you want. Reuse that fn-def directly in the partial — the
   partial is just the HTML projection of the same source of
   truth.

2. **Add fn-defs to `resources/packages/app/editor/fns.edn`**
   under the `;; HTMX PARTIALS` section. Naming convention is
   `:_partial-<name>-*` for the chain pieces, with one public-ish
   `:_partial-<name>-handler` at the bottom. Decompose into:

   - **Request parse**: `:query-param` (or `:_uri-split` for path
     segments) to pull params out. Coerce types as needed
     (`:str-to-keyword` for keyword map lookups — JSONB
     roundtrip keywordizes literal-map keys; `:parse-uuid` for
     entity ids).
   - **Data**: reference the existing data-producing fn-def, OR
     compose a fresh `:storage-query-call` chain if the data is
     partial-specific.
   - **Per-row HOF callback**: a `_`-private fn-def using
     `{:as :item}` to bind one row, extracting fields via
     `:get :coll {:as :item} :key {:value :field-name} :default …`.
     Returns a `:hiccup` vector.
   - **Map over rows**: `:map :func :_partial-X-row :coll <data>`.
   - **Container hiccup**: `:hiccup :tag "div" :attrs {...} :children [...]`.
   - **Render**: `:render-hiccup :hiccup :_partial-X-fragment`.
   - **Handler**: `:_partial-X-handler :parent :html-ok-response
     :args {:body :_partial-X-html}`. Use
     `:cached-svg-ok-response` template style if the response is
     pure-deterministic and you want the URL-keyed response cache
     to short-circuit.

3. **Register the route** in `resources/packages/app/routes/fns.edn`
   (`:parent :get-route` for public, `:get-auth-required` for
   admin-only) and add the route's name to the `:all` items list
   in `resources/packages/app/route-groups/fns.edn`.

4. **Bind the trigger in JS** (or use HTMX declaratively):

   - **Plain fetch**: `await fetch('/partials/X?...')`,
     `mount.innerHTML = await resp.text()`,
     `window.htmx?.process(mount)`. Bind any post-swap click
     handlers by selector.
   - **HTMX declarative**: put `hx-get="/partials/X?..."`,
     `hx-target="#mount"`, `hx-swap="innerHTML"` on the trigger
     element. HTMX dispatches the request, swaps, and processes
     nested hx-* automatically.

5. **Verify** with the existing e2e (`tools/browser-test/`).
   Selectors usually survive because we keep the same CSS class
   names + `data-*` attributes the legacy JS-built markup carried.

---

## HTMX integration details

### Loaded from CDN

```edn
{:name :head
 :parent :with-cdn-script
 :args {:head :head-elements-0
        :url "https://unpkg.com/htmx.org@2.0.4/dist/htmx.min.js"}}
```

### Auth bridge

HTMX makes its own `fetch()` — it does NOT use `authFetch`. Without
a bridge, every auth-required partial 401s. The bridge lives at the
end of `editor-auth.js`:

```js
document.body.addEventListener('htmx:configRequest', (evt) => {
  const pw = getAuthPassword();
  if (pw) evt.detail.headers.Authorization = 'Bearer ' + pw;
});
```

`htmx:configRequest` fires before each HTMX request. The handler
attaches the Bearer token from the same localStorage entry
`authFetch` reads, so HTMX inherits the session login.

### Post-swap processing

When JS replaces `innerHTML` directly, HTMX does NOT re-scan for
`hx-*` attributes. Wrap the assignment:

```js
function swapAndProcess(el, html) {
  el.innerHTML = html;
  if (window.htmx?.process) window.htmx.process(el);
}
```

Now any `hx-get` inside the swapped content is live. (This is only
needed for the OUTER swap done by JS. The INNER swaps HTMX does
itself already call process on their results.)

### Declarative dismissal

The close `×` on a popover can use the legacy JS handler bound by
selector (`[data-explainer-close]`) OR a declarative `hx-on:click`:

```edn
[:button {:type "button"
          :hx-on:click "this.closest('.type-explainer').classList.remove('visible')"
          :aria-label "Close"} "×"]
```

The selector-based version stays viable as long as the JS module
has a small `bindFragmentDismiss(rootEl)` that runs after each
swap. Either is fine — the inline-JS version puts MORE logic in
the graph at the cost of stringy hyperscript.

---

## Gotchas — things to look up, NOT to re-derive

These are the friction points hit during the first three migrations.
Each one cost a `bb rebuild` cycle to surface.

### 1. `:parse-uuid`'s slot is `:string`

```edn
{:parent :parse-uuid :args {:string :foo}}
```

### 2. `:zero?` is for a number, `:empty?` for a collection

`:zero?` exists (`core/logic`). To test whether a COUNT is zero, prefer
checking emptiness of the collection directly over counting-then-comparing:

```edn
;; a collection is empty
{:parent :empty? :args {:coll :_partial-X-rows}}
;; a number is zero
{:parent :zero? :args {:number :_count}}
```

### 3. Inline anonymous `{:parent ...}` fn-defs ARE supported (since the anon-lift pre-pass)

The parser lifts an inline `{:parent X :args Y}` map in arg-value
position into a synthetic `_anon-<hash>` named fn-def
(`expand-inline-anons-in-module` in `packages/records/parse.clj`),
including nested anons. The synthetic name mixes the use-site tuple, so
identical-shape anons at different use-sites stay distinct entries.

```edn
;; WORKS — the inner :assoc is lifted to a synthetic _anon-… fn-def
{:parent :assoc
 :args {:map {:parent :assoc :args {:map {:value {}} :key {:value :a} :value …}}
        :key {:value :b}
        :value …}}
```

Still prefer a NAMED `:_`-step when the sub-chain is reused, needs a
comment, or should show up under a readable name in sync logs /
type-check output — the naming guidance in
[graphden-fn-design](PACKAGES.md#naming-guidelines) applies unchanged.

### 4. Literal-map keys are keywordized after JSONB roundtrip

If a literal lookup map uses string keys in `fns.edn`, they come back
as keywords. Coerce the lookup key accordingly:

```edn
{:name :_effect-descriptions
 :parent :const
 :args {:value {"db" "Reads or writes storage." …}}}   ; ← becomes {:db …}

;; Lookup BY a query-string param (which is text) → coerce to keyword
{:name :_partial-X-name-kw
 :parent :str-to-keyword
 :args {:string :_partial-X-name}}

{:parent :get :args {:coll :_effect-descriptions
                     :key :_partial-X-name-kw
                     :default {:value "(unknown)"}}}
```

### 5. fn-refs inside `:value` literals are stored as literal keywords

`{:value [:div :_my-fn-ref]}` does NOT resolve `:_my-fn-ref` — it's
stored as the literal keyword `:_my-fn-ref`. To embed a computed
value in a hiccup vector, build the parent with `:hiccup` and pass
the fn-ref as a child:

```edn
;; WRONG — :_partial-X-text stays literal
{:parent :const :args {:value [:div :_partial-X-text]}}

;; RIGHT — :hiccup composes children at compose-time
{:parent :hiccup
 :args {:tag {:value "div"}
        :attrs {:value {:class "x"}}
        :children [:_partial-X-text]}}
```

### 6. HTMX `hx-target="next [data-execs-mount]"`

Use `next` (or `closest`, `find`, `previous`) to target relative to
the trigger element. `[data-execs-mount]` is a CSS attribute
selector — the swap looks for the next sibling matching the
selector. Combined with a sibling mount-div, the trigger is
self-locating and doesn't need globally-unique IDs.

### 7. `event.stopPropagation()` on inner-button click

If the parent element has `hx-get` and an inner button has its own
click handler, the button handler MUST call `stopPropagation()` or
both will fire (HTMX listens at the same bubble phase as regular
listeners). The existing pattern in `bindFnVersionsActions` does
this correctly.

### 8. JSON serialises enum fields as STRINGS, in-graph they're KEYWORDS

Server data layers return keywords for enum-shaped fields (`:status
:succeeded`, `:change :modified`, `:entity-name :fn`); the JSON-handler
flattens to strings on the wire (`"succeeded"` / `"modified"` /
`"fn"`). Inside the graph, your `:equal?` comparison must use the
KEYWORD literal:

```edn
;; WRONG — :a is a kw at runtime, :b is a string, never equal
{:parent :equal? :args {:a :_row-status :b {:value "succeeded"}}}

;; RIGHT — match against the keyword form
{:parent :equal? :args {:a :_row-status :b {:value :succeeded}}}
```

The trap is observability: the partial silently renders empty
sections / wrong dispatch instead of failing loudly. After this
gotcha hit twice (execute-history `:status`, branch-diff `:change`
and `:entity-name`), the affected fields got closed-enum types
(`:diff-change-kw`, `:diff-entity-name-kw`) so downstream sites
can pin the kind explicitly. `:equal?` itself stays untyped today
— sync-time guard for `:kw` vs `:string` compares would need a
typed-equal variant; deferred.

### 9. `:decode-row` parses timestamptz to `java.sql.Timestamp`, not a String

`:subs` (and other string ops) directly throw `count not supported
on this type: Timestamp` when handed a decoded `:started-at` /
`:created-at`. Coerce first:

```edn
{:name :_partial-X-ts-text  :parent :to-str  :args {:value :_partial-X-row-ts}}
{:name :_partial-X-ts-short :parent :subs    :args {:string :_partial-X-ts-text
                                                    :start {:value 0}
                                                    :end {:value 16}}}
```

`:to-str` calls `(str ts)` which uses Timestamp's SQL-shape
`.toString` — `YYYY-MM-DD HH:MM:SS.…`. First 16 chars cover
both that and ISO `YYYY-MM-DDTHH:MM:SS…`.

### 10. `:cond` + `:get` narrowing — annotate error-branch return-types

When a fn-def's chain wraps a `:cond` whose branches return
DIFFERENT envelope shapes (success vs error union), downstream
`:get :key X` on the result may opaque out to `:any` for the
non-success branches, and `:count` / `:empty?` / `:str-join`
downstream trips the type-check sweep.

`get-return-rule` (`core/collections/impls.clj`) ALREADY narrows
through union-typed `:coll` by mapping over union members. The
opacifier is upstream: a `:cond` branch that uses `:parent
:zipmap` with literal keys but NO `:return-type` annotation
infers to `[:map :any [:union <val-types>]]` instead of the
precise record. That opaque entry, unioned with the
success-shape record, then dominates `:get`'s per-member
narrowing — `:get :key :diffs` on `[:map :any T]` falls to the
default's type, so the union of "default" + "diff-list" can't
type-check `:count`.

**Fix**: pin `:return-type :<your-record-shape>` on every error-
branch fn-def. The record-shape type can be a shared alias
(e.g. `:_error-result-shape` = `{:ok :bool :error :text}` in
`web/crud/fns.edn`) so several handlers reuse it.

Branch-diff example: `:_diff-err-target-missing` and
`:_diff-err-source-missing` both use `:parent :zipmap` —
adding `:return-type :_error-result-shape` to each turned the
cond's combined return into a union of precise records, and 4
allowlisted partial fn-defs (`:_partial-bd-count` /
`:-empty?` / `:-source-name` / `:-target-name`) type-checked
cleanly without the allowlist band-aid.

Sweep-search heuristic — find more sites like this:

```clojure
;; REPL — fn-defs whose computed return is a union including an
;; opaque [:map :any …] entry:
(require '[graphden.executor.registry.core :as reg]
         '[clojure.string :as s])
(->> (reg/rich-types-snapshot)
     (filter (fn [[k v]]
               (and (s/starts-with? (str k) ":_")
                    (let [r (:return v)]
                      (and (vector? r) (= :union (first r))
                           (some (fn [m] (and (vector? m) (= :map (first m))))
                                 (rest r)))))))
     (map first) sort)
```

~85 fn-defs across the graph today match this pattern. Each is a
candidate — but not all are bugs (some genuinely hold open-shape
map data). Audit at need; the systemic fix is the same — pin
`:return-type` to a precise record-shape on the opaque branch.

### 11. Convention: string-input base-fns use `:string`, NOT `:s`

`:subs`, `:str-len`, `:str-upper`, `:str-lower`, `:str-trim`,
`:str-split`, `:str-to-keyword`, `:str-replace`, and
`:parse-uuid` ALL use `:string` as the input slot name (the last
two were renamed from `:s` for consistency). When you add a new
string-input base-fn, follow the convention; the type-checker
catches `:s` vs `:string` mistakes at sync time with the
"non-existent slot" error.

---

## File map (all shipped partials)

The full registry lives in `resources/packages/app/routes/fns.edn`
(every `:partial-*` route) with the hiccup chains in
`app/editor/fns.edn` (`;; HTMX PARTIALS` and the sections after it),
`app/execution/fns.edn` (execute-result / service-popover /
execute-history) and `app/{branches,secrets,registry}` for their
panels. 23 partials as of 2026-07-19, by consumer surface:

| Partial (route name)                | Path (+key params)                                        | JS consumer |
|-------------------------------------|-----------------------------------------------------------|-------------|
| `:partial-effect`                    | GET /partials/effect?effect=                              | editor-effect-explainer.js |
| `:partial-auth-form`                 | GET /partials/auth-form (public — IS the login form)      | editor-auth.js |
| `:partial-fn-versions`               | GET /partials/fn-versions?fn-id= (auth)                   | editor-fn-versions.js |
| `:partial-fn-version-executions`     | GET /partials/fn-version-executions?fn-version-id= (auth) | per-row htmx load |
| `:partial-execute-history`           | GET /partials/execute-history?fn-id= (auth)               | editor-execute-history.js |
| `:partial-execute-result`            | GET /partials/execute-result?id= (auth)                   | execute orchestrator + history |
| `:partial-execute-result-inline`     | POST /partials/execute-result-inline (auth)               | non-persisted inline Run |
| `:partial-execute-result-effects`    | GET /partials/execute-result-effects?runtime=&declared=   | editor-execute.js |
| `:partial-execute-popover`           | GET /partials/execute-popover?fn-id= (auth)               | editor-execute.js (Run-popover shell) |
| `:partial-branch-popover`            | GET /partials/branch-popover                              | editor-branches.js |
| `:partial-branch-diff`               | GET /partials/branch-diff?target=&source=                 | editor-branch-diff.js |
| `:partial-merge-conflicts`           | POST /partials/merge-conflicts                            | editor-branches.js (conflict modal) |
| `:partial-mismatch-explainer`        | GET /partials/mismatch-explainer?binding-id= + optional item-id   | editor-mismatch-explainer.js |
| `:partial-provenance`                | GET /partials/provenance?binding-id= + optional item-id (public)  | editor-provenance-popover.js |
| `:partial-return-type-rule`          | GET /partials/return-type-rule?fn= (public)               | editor-provenance-popover.js (Type-rule popover) |
| `:partial-fn-picker-incompat`        | GET /partials/fn-picker-incompat?expected=&candidate-fn-id= (auth) | editor-fn-picker.js |
| `:partial-type-name-datalist`        | GET /partials/type-name-datalist (auth)                   | editor-create-type.js (name autocomplete) |
| `:partial-compatible-type-options`   | GET /partials/compatible-type-options?expected= + optional current / primitives=true (auth) | editor-edit-modes-type.js + editor-overlay-type-expand.js |
| `:partial-expects-effects-form`      | GET /partials/expects-effects-form?fn-id= (auth)          | editor-edit-modes-fn.js (✎ effects) |
| `:partial-service-popover`           | GET /partials/service-popover?fn-id= (auth)               | editor-service-popover.js |
| `:partial-secret-create-form`        | GET /partials/secret-create-form (auth)                   | editor-secrets.js |
| `:partial-secret-rotate-form`        | GET /partials/secret-rotate-form?fn-id= (auth)            | editor-secrets.js |
| `:partial-row-actions`               | GET /partials/row-actions?fn-id=&context= (public; edit affordances re-gated client-side) | editor-row-actions.js |

Sidebar admin/packages panels (`/partials/grants-admin`,
`/partials/users-admin`, `/partials/packages-panel`) are registered
by their addon/route groups rather than `app.routes` — same pattern.

---

## Open questions / next steps

- **Switch + restore via HTMX**: `restore` could become
  `hx-post="/api/fns/:id/versions/restore" hx-confirm="…?"`,
  removing the JS `restoreFnVersion` flow entirely. Requires a
  POST endpoint that takes `?version-id=` and does the same PUT
  internally.

- ~~**Inline-anon fn-def support**~~ — DONE: the parser lifts inline
  `{:parent X :args Y}` into synthetic `_anon-…` fn-defs (see gotcha
  3 above). Rewriting the existing named `:_`-steps into inline form
  is pure churn — new partials just use it where a step has no
  independent meaning.

- ~~**A dedicated `app.editor.partials` namespace**~~ — RESOLVED by the
  feature-module split instead: `app/editor/fns.edn` is now the
  assembly/chrome core (plus the shared partial helpers and the
  effect-explainer + auth-form partials), and each partial lives in
  the module of its FEATURE — `editor-row-actions/`,
  `editor-provenance/`, `editor-execute/`, `editor-edit-forms/`,
  `editor-branches/`, `editor-panels/`. All modules keep
  `:namespace "app.editor"` (fn identity is uuid-v5(ns, name), so
  only the file layout changed). Partials were deliberately NOT
  grouped into one partials namespace — they stay with their
  features.

### 12. Bare-route handlers: `:lambda-params` must be `[]` or `[:request]`

A route WITHOUT middleware (`:get-route` / `:post-route` parents) hands its
compiled handler the **raw ring request, positionally** (reitit → the
shape-callable). Only two shapes thread that correctly:

- `[]` — static response, request ignored.
- `[:request]` — the 1-arg shape puts the request under `:request`.

Anything else breaks silently at the wire (while `cr/execute` with an explicit
`{:request …}` map still works — tests must use `rc/dispatch` to see it):

- 2+ params (`[:request :limit]`) → the map-callable treats the ring request
  AS the lambda-value map → `:request` resolves nil → `parse-form-body`
  returns `{}` and every field is blank. This is how cloud signup returned
  "already taken" for every input.
- 1 param under another name (`[:children]`) → the whole request lands in that
  slot. This is how the auth popover rendered `<request-method>…` + the reitit
  router object inside its `<input>`s.

Middlewared routes (`:post` / `:put` / `:get-auth-required`) thread through
the middleware chain and tolerate wider shapes. Corollary: pin `:children
{:value []}` on leaf `:hiccup` elements whose text/glyph is set by JS —
an unpinned `:children` free re-derives and re-breaks the handler shape.

Guarded by `route-handler-shape-guard-test` (unit) + the
`*-over-the-wire` integration tests — extend those when adding public routes.

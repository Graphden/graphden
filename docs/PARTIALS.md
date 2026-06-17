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

Currently migrated: `editor-effect-explainer.js`, `editor-fn-versions.js`.
Pattern is the template for the rest (`editor-branch-diff.js`,
`editor-provenance-popover.js`, `editor-secrets.js`, …).

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
 :args {:head :_head-with-cytoscape
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

### 1. `:parse-uuid`'s slot is `:s`, not `:string`

```edn
;; WRONG — type-checker rejects "non-existent slot"
{:parent :parse-uuid :args {:string :foo}}

;; RIGHT
{:parent :parse-uuid :args {:s :foo}}
```

### 2. No `:zero?` primitive — use `:empty?` over the collection

```edn
;; WRONG — :zero? doesn't exist
{:parent :zero? :args {:number :_count}}

;; RIGHT — check emptiness directly
{:parent :empty? :args {:coll :_partial-X-rows}}
```

### 3. Inline anonymous `{:parent ...}` fn-defs are NOT supported

The parser only accepts inline `{:value <literal>}` for arg-bindings.
Anything compositional has to be a named fn-def, even when used once.

```edn
;; WRONG — parser rejects inline-anon
{:parent :assoc
 :args {:map {:parent :assoc :args {:map {:value {}} :key {:value :a} :value …}}
        :key {:value :b}
        :value …}}

;; RIGHT — extract the inner :assoc into a named step
{:name :_X-attrs-base
 :parent :assoc
 :args {:map {:value {}} :key {:value :a} :value …}}

{:name :_X-attrs
 :parent :assoc
 :args {:map :_X-attrs-base :key {:value :b} :value …}}
```

For complex hiccup attrs maps with multiple keys (class + data-*  +
hx-*), this means one named fn-def per `:assoc` step.

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

### 10. The type-checker can't flow-narrow through `cond + get`

When a fn-def is a `:cond` returning different envelope shapes
(success vs error union), downstream `:get :key X` on the result
returns `:any` from the type-checker's POV, and a `:count` /
`:empty?` / `:str-join` on that often trips
`types/sweep-regression`. Runtime is fine — the cond's apply
branch is the only path the partial-renderer's caller reaches.

Until the type-checker grows cond-then-get narrowing, the
work-around is to add the affected fn-def names to
`graphden.types.check/allowed-type-check-failures` with a
comment pointing at this section.

### 11. Convention: string-input base-fns use `:string`, NOT `:s`

`:subs`, `:str-len`, `:str-upper`, `:str-lower`, `:str-trim`,
`:str-split`, `:str-to-keyword`, `:str-replace`, and
`:parse-uuid` ALL use `:string` as the input slot name (the last
two were renamed from `:s` for consistency). When you add a new
string-input base-fn, follow the convention; the type-checker
catches `:s` vs `:string` mistakes at sync time with the
"non-existent slot" error.

---

## File map (current migrations)

```
resources/packages/app/editor/fns.edn
  ;; HTMX PARTIALS section:
  effect-explainer    — :_effect-descriptions, :_partial-effect-{name,name-kw,description,structural-text,header,description-div,structural-div,fragment,html,handler}
  fn-versions         — :_partial-fn-versions-{fn-id-str,fn-id,current-branch,data,rows,count,empty?},
                        :_partial-fnv-row-{branch-name,version-id,created-at,ts-short,on-current?,branch-class,branch-span,ts-span,description-text,meta-div,restore-{attrs,btn},switch-{title-prefix,attrs-base,attrs,btn-shown,btn-hidden,btn},execs-url,top-{attrs-base,attrs-with-get,attrs},top,execs-mount,attrs,row},
                        :_partial-fnv-{rendered-rows,list,header-title,count-text,header-text,header,empty-msg,body,fragment,html},
                        :_partial-fn-versions-handler
  fn-version-executions — :_partial-fnv-execs-{rows,count,empty?},
                          :_partial-fnv-exec-{status,started-at,ts-short,status-class,status-attrs,status-span,ts-span,row,rendered-rows,list,empty-msg,body,html,handler}

resources/packages/app/routes/fns.edn
  :partial-effect                     — GET /partials/effect
  :partial-fn-versions                — GET /partials/fn-versions (auth)
  :partial-fn-version-executions      — GET /partials/fn-version-executions (auth)

resources/packages/app/editor/editor-effect-explainer.js  (86 LOC — was 132)
resources/packages/app/editor/editor-fn-versions.js       (201 LOC — was 369)
```

---

## Open questions / next steps

- **Switch + restore via HTMX**: `restore` could become
  `hx-post="/api/fns/:id/versions/restore" hx-confirm="…?"`,
  removing the JS `restoreFnVersion` flow entirely. Requires a
  POST endpoint that takes `?version-id=` and does the same PUT
  internally.

- **Inline-anon fn-def support**: every `:assoc` chain in the
  partials decomposes into 2-4 named steps. The parser COULD
  accept inline `{:parent X :args Y}` and synthesise a `_anon-…`
  name — would cut ~30% of the partial fn-defs. Not a blocker;
  cosmetic.

- **A dedicated `app.editor.partials` namespace** once the partials
  section in `app/editor/fns.edn` outgrows the same-file convention.
  Cut-over criterion: ~50+ fn-defs purely for partials, or once
  partials need their own dependencies (currently they reuse
  `app.branches` and `app.execution` chains directly).

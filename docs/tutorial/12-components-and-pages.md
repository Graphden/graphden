## Lesson 12 — Composing pages from components

**Goal**: by the end of this lesson you can build a working
HTML page — header, form, submit button, response panel —
and mount it as a route, entirely by composing fn-defs from
the platform's `web.components` library and the `app.page`
templates.

**Concepts introduced**: `web.components`, `:button`, `:input`,
`:textarea`, `:select`, `:option`, `:checkbox`, `:form`,
`:link`, `:image`, `:card`, `:dispatch-action`, `data-action`,
`/assets/graphden-runtime.js`, `submit-form` handler,
`navigate` handler, `:html-page-route`, `:html-page-handler`,
`:html-page-rendered`, `:graphden-runtime-scripts`.

## The starter component library

`web.components` ships ten primitives, each a thin fn-def over
`:hiccup` that pins the variable bits (label, content, children)
as slots and inherits an optional `:attrs` map you can extend.

| fn-def | Renders | Required slots | Optional |
|---|---|---|---|
| `:button` | `<button>label</button>` | `:label` | `:attrs` |
| `:input` | `<input/>` | — | `:attrs` |
| `:textarea` | `<textarea>content</textarea>` | `:content` | `:attrs` |
| `:select` | `<select>options...</select>` | `:options` | `:attrs` |
| `:option` | `<option>label</option>` | `:label` | `:attrs` |
| `:checkbox` | `<input type="checkbox"/>` (`type` merged in) | — | `:attrs` |
| `:form` | `<form>children...</form>` | `:children` | `:attrs` |
| `:link` | `<a href=...>label</a>` | `:href`, `:label` | `:attrs` |
| `:image` | `<img src=... alt=.../>` | `:src`, `:alt` | `:attrs` |
| `:card` | `<div class="card">children...</div>` | `:children` | `:attrs` |

`:attrs` is the inherited slot from `:hiccup`. Bind it to a
keyword-map to add `:class` / `:id` / `:placeholder` / etc.
For `:link`, `:image`, `:card`, and `:checkbox` the
required-or-default slots are merged on top of caller-supplied
`:attrs`, so you can't accidentally lose the platform shape.

## Try it: the smallest example

Open the editor, hit "New fn-def", and create:

```clojure
{:name :my-run-button
 :parent :button
 :args {:label "Run"}}
```

Execute it. The result hiccup is `[:button "Run"]`. You just
built your first component composition.

Now extend it with caller attrs:

```clojure
{:name :my-styled-button
 :parent :button
 :args {:label "Save"
        :attrs {:class "primary" :data-cy "save"}}}
```

Execute. Result: `[:button {:class "primary" :data-cy "save"} "Save"]`.

## Wiring a click handler — the DSL

Click behaviour rides on `data-action="X"` attributes that a
small JS dispatcher routes to a registered handler. The
`:dispatch-action` atom in `web.runtime` builds the
`{:data-action <name>}` attrs map; merge it onto your button's
`:attrs` to wire a click.

The platform pre-registers two built-in handlers:

| `data-action` | Behaviour |
|---|---|
| `navigate` | Reads `data-href`, sets `window.location.href`. |
| `submit-form` | Finds the nearest `<form>` ancestor, POSTs its fields, swaps the response into `data-target` (CSS selector) or back into the form. |

Example — a button that takes the user to /about:

```clojure
{:name :goto-about
 :parent :button
 :args {:label "About"
        :attrs {:parent :merge
                :args {:maps [{:parent :dispatch-action
                               :args {:action "navigate"}}
                              {:value {:data-href "/about"}}]}}}}
```

Run it; the hiccup output is:

```clojure
[:button {:data-action "navigate" :data-href "/about"} "About"]
```

Two `data-*` attrs: one tells the dispatcher which handler to
run, the other carries the per-handler input.

## Adding a page route

Components give you HICCUP. To serve a hiccup tree as an HTML
page at a URL you need: an `<html>`/`<head>`/`<body>` wrapper,
a hiccup-to-string render, a 200 OK response with `text/html`
content-type, and a route binding. The `app.page` module
collapses this 4-step chain into one template:

```clojure
{:name :my-about-page
 :parent :html-page-route
 :args {:path "/about"
        :title "About"
        :page-body :my-about-body          ; a fn-def returning hiccup
        :scripts {:value []}}}              ; no JS needed for this page
```

That's it. Four pins — `:path`, `:title`, `:page-body`,
`:scripts` — gets you a working reitit-shaped route entry. The
template (`:html-page-route` in `app/page/fns.edn`) wraps
`:html-page` → `:render-hiccup` → `:html-ok-response` →
`:get-route` so you don't have to know that chain exists.

> **Why `:page-body` and not `:body`?** The chain plumbs
> `:body` through two layers — the hiccup page body and the
> HTTP response body. Without the rename they'd collide. The
> caller-visible name is `:page-body` to keep it unambiguous.

To mount it, edit `resources/packages/app/route-groups/fns.edn`
and append `:my-about-page` to `:all`'s `:items`. Run
`bb rebuild` (or `bb deploy` if your DB is dirty). Visit
`http://localhost:9002/about`.

### When the simple template isn't enough

`:html-page-route` is GET-only. For other shapes drop down:

- **Same path, multiple methods** (e.g. GET + POST on
  `/contact`): use `:html-page-handler` (just the Ring
  handler — no route wrap) on the GET side of a method-map
  merge. The contact-form demo (`app/contact-demo/fns.edn`)
  does this; copy `:_demo-contact-{get,post}-data` +
  `:_demo-contact-methods` + `:demo-contact`.
- **Non-Ring sinks** (write the rendered HTML to a file,
  return it as an email body): use `:html-page-rendered`
  which returns the raw text.

All three templates expose the same `:title` / `:page-body` /
`:scripts` free args — pick the layer you need.

## Wiring the runtime

If your page has any `data-action="..."` buttons (a form
submit, a `navigate`, an inline `custom` handler), bind
`:scripts` to `:graphden-runtime-scripts`:

```clojure
{:name :my-contact-page
 :parent :html-page-route
 :args {:path "/contact"
        :title "Contact us"
        :page-body :my-contact-body
        :scripts :graphden-runtime-scripts}}    ; ← that's it
```

`:graphden-runtime-scripts` (in `app/editor/fns.edn`) is a
two-element list:

1. `<script src="/assets/graphden-runtime.js">` — loads the
   dispatcher + built-in handlers (`submit-form`, `navigate`,
   `custom`).
2. An inline `<script>` calling
   `bindActionDispatch(document.body)` on `DOMContentLoaded`
   so the runtime starts routing clicks.

After this any button in `:my-contact-body` whose `:attrs`
were built with `:dispatch-action` / `:dispatch-custom` /
`submit-form` will work — no extra wiring.

> **Why not `/assets/editor.js`?** That bundle is ~700 KB
> and initialises Cytoscape + WebSocket subscriptions that
> crash a non-editor page. `/assets/graphden-runtime.js`
> is the minimal subset (~9 KB) — just the dispatcher +
> built-in handlers.

## A full page: the contact-form demo

`app.contact-demo` (shipped) composes the ten primitives, the
DSL, and the page templates into a working contact form.
Visit `/demo/contact` in the running editor; the page is:

```
┌──────────────────────────────────────────────────────┐
│  Contact us                                          │
│                                                      │
│  Demo — every element on this page is composed       │
│  from web.components + web.runtime fn-defs.          │
│                                                      │
│  [you@example.com .....................]             │
│  [Tell us how we can help.........]                  │
│  [                                ]                  │
│                                                      │
│  [ Send ]                                            │
│                                                      │
│  (← submit response swaps in here)                   │
└──────────────────────────────────────────────────────┘
```

Source: `resources/packages/app/contact-demo/fns.edn`. The
shape:

1. `:_contact-demo-email-input` — `:parent :input`, `:attrs`
   bound to a literal `{:type "email" :name "email" ...}`.
2. `:_contact-demo-message-textarea` — same shape for textarea.
3. `:_contact-demo-submit-button` — `:parent :button`, label
   "Send", `:attrs` merged from `:dispatch-action` (action
   "submit-form") + literal `{:type "submit" :data-target
   "#contact-result"}`.
4. `:_contact-demo-result-panel` — empty `<div id="contact-result">`.
5. `:_contact-demo-form` — `:parent :form`, children = the four
   items above in order.
6. `:_contact-demo-page-body` — header + intro + form + result
   panel wrapped in a sized `<div>`.
7. `:_contact-demo-page-handler` — one fn-def with
   `:parent :html-page-handler` pinning `:title` /
   `:page-body` / `:scripts`. That's the WHOLE page handler.
8. `:_demo-contact-{get,post}-data`, `:_demo-contact-methods`,
   `:demo-contact` — the method-map merge so GET and POST
   share the `/demo/contact` path.

## Try it: extend the demo

Add a name field. Edit `:_contact-demo-form`'s `:children`
list to prepend an extra input:

```clojure
{:name :_contact-demo-name-input
 :parent :input
 :args {:attrs {:value {:type "text" :name "name"
                        :placeholder "Your name" :required true
                        :style "width:100%;padding:8px;margin:6px 0"}}}}
```

Then update `:_contact-demo-form` `:children` to
`[:_contact-demo-name-input
  :_contact-demo-email-input
  :_contact-demo-message-textarea
  :_contact-demo-submit-button]`.

`bb rebuild` and reload `/demo/contact` — the name field
appears above the email, submit still works, the server's
thanks partial swaps in.

## What's next

Lessons 1–11 covered the graph model. Lesson 12 turns it on
itself: every element on a user-facing page is a fn-def, the
dispatch is graph-visible, the response is a fn-def too. The
escape hatch for the 20% of behaviour the components don't
cover (a custom hover effect, a one-off computed style) is
Lesson 13's `:custom-script` block.

Multi-tenancy — multiple users hosting their own sites on
one graphden instance, with their own deploys, secrets, and
auth-isolated routes — is a separate future phase. Today's
mount-point is the single shared `:all` items list (you edit
the EDN to add a route).

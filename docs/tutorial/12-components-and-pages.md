## Lesson 12 — Composing pages from components

**Goal**: by the end of this lesson you can build a working
HTML page — header, form, submit button, response panel —
and mount it as a route, entirely by composing fn-defs from
the platform's `web.components` library and the `app.page`
templates.

**Concepts introduced**: `web.components`, `:button`, `:input`,
`:textarea`, `:select`, `:option`, `:checkbox`, `:form`,
`:link`, `:image`, `:card`, the text/layout set (`:heading`,
`:paragraph`, `:stack`, `:row`, `:nav-bar`, `:unordered-list`,
`:list-item`, `:table`, `:table-row`, `:table-cell`,
`:table-header-cell`, `:field-label`), convenience templates
(`:submit-button`, `:click-button`, `:navigate-button`,
`:custom-button`), `:dispatch-action`, `data-action`,
`/assets/graphden-runtime.js`, `submit-form` handler,
`navigate` handler, `:html-page-route`, `:html-page-handler`,
`:html-page-rendered`, `:stylesheet-route`,
`:custom-stylesheet`, `:graphden-runtime-scripts`,
`:graphden-page-head`, `/assets/graphden-components.css`.

## The starter component library

`web.components` ships two groups of primitives, each a thin
fn-def over `:hiccup` that pins the variable bits (label,
content, children) as slots and inherits an optional `:attrs`
map you can extend.

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

And the text/layout set:

| fn-def | Renders | Required slots | Optional |
|---|---|---|---|
| `:heading` | `<h1>`..`<h6>` by `:level` | `:level` (1-6), `:content` | `:attrs` |
| `:paragraph` | `<p>children...</p>` | `:children` | `:attrs` |
| `:stack` | `<div class="stack">` (vertical flex) | `:children` | `:attrs` |
| `:row` | `<div class="row">` (horizontal flex) | `:children` | `:attrs` |
| `:nav-bar` | `<nav>children...</nav>` | `:children` | `:attrs` |
| `:unordered-list` | `<ul>children...</ul>` | `:children` | `:attrs` |
| `:list-item` | `<li>children...</li>` | `:children` | `:attrs` |
| `:table` | `<table>rows...</table>` | `:children` | `:attrs` |
| `:table-row` | `<tr>cells...</tr>` | `:children` | `:attrs` |
| `:table-cell` / `:table-header-cell` | `<td>` / `<th>` | `:children` | `:attrs` |
| `:field-label` | `<label>children...</label>` | `:children` | `:attrs` |

For example, a two-level page skeleton:

```clojure
{:name :my-about-body
 :parent :stack
 :args {:children [{:parent :heading
                    :args {:level 1 :content "About us"}}
                   {:parent :paragraph
                    :args {:children ["We build things."]}}]}}
```

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

## Wiring a click handler

Click behaviour rides on `data-action="X"` attributes that a
small JS dispatcher routes to a registered handler. The platform
pre-registers three handlers:

| `data-action` | Behaviour |
|---|---|
| `navigate` | Reads `data-href`, sets `window.location.href`. |
| `submit-form` | Finds the nearest `<form>` ancestor, POSTs its fields, swaps the response into `data-target` (CSS selector) or back into the form. |
| `custom` | Evaluates `data-custom-handler` as `(btn, event, host) => …` — the escape hatch (see Lesson 13). |

Four convenience templates cover the common cases — each pre-
wires the matching `data-action` so you only think about the
visible bits:

| Template | Free args | Renders |
|---|---|---|
| `:submit-button` | `:label`, `:extras` | `<button data-action="submit-form" type="submit">label</button>` |
| `:navigate-button` | `:label`, `:href`, `:extras` | `<button data-action="navigate" data-href="...">label</button>` |
| `:click-button` | `:label`, `:action`, `:extras` | `<button data-action="<your-action>">label</button>` |
| `:custom-button` | `:label`, `:body`, `:extras` | `<button data-action="custom" data-custom-handler="...">label</button>` |

Example — a button that takes the user to `/about`:

```clojure
{:name :goto-about
 :parent :navigate-button
 :args {:label "About"
        :href "/about"
        :extras {:value {}}}}
```

Result hiccup:

```clojure
[:button {:data-action "navigate" :data-href "/about"} "About"]
```

`:extras` is the catch-all for extra attrs (`:class`, `:id`,
`:type "button"`, per-handler `data-*` payloads). Caller's
`:extras` win on conflict (Clojure `merge` semantics) so you
can always override platform defaults.

### The raw form

The convenience templates are sugar over a `:merge` +
`:dispatch-action` chain on `:button`'s `:attrs`. If you need
something the four templates don't cover (a handler that needs
multiple `data-*` attrs the template doesn't expose, a button
that combines two action sources), build it yourself:

```clojure
{:name :my-fancy-button
 :parent :button
 :args {:label "Save"
        :attrs {:parent :merge
                :args {:maps [{:parent :dispatch-action
                               :args {:action "submit-form"}}
                              {:value {:type "submit"
                                       :data-target "#result"
                                       :data-tracking-id "save-cta"}}]}}}}
```

The `:dispatch-action` atom (in `web.runtime`) builds the
`{:data-action <name>}` half; the literal map adds the rest.
Templates package this pattern.

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
        :body :my-about-body              ; a fn-def returning hiccup
        :head :graphden-page-head          ; default stylesheet
        :scripts {:value []}}}             ; no JS needed for this page
```

Five pins — `:path`, `:title`, `:body`, `:head`, `:scripts` —
get you a working reitit-shaped route entry. The template
(`:html-page-route` in `app/page/fns.edn`) wraps `:html-page`
→ `:render-hiccup` → `:html-ok-response` → `:get-route` so you
don't have to know that chain exists.

`:graphden-page-head` is a drop-in `:head` bundle that includes
the components stylesheet `<link>` (default styling for
`button`/`input`/`form`/etc — see "Default styling" below).
Pass `{:value []}` if you want no stylesheet.

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

All three templates expose the same `:title` / `:body` /
`:head` / `:scripts` free args — pick the layer you need.

## Default styling

`:graphden-page-head` injects a `<link rel="stylesheet"
href="/assets/graphden-components.css?v=…">` tag into `<head>`.
The stylesheet (`resources/packages/app/editor/components.css`)
uses tag-level selectors (`button`, `input`, `textarea`,
`form`, `a`, `img`, `h1-h3`) so the platform components get
sensible defaults — padding, focus rings, primary-button
color for `type="submit"`, form gap — without any inline
`:style` attrs at the call site.

CSS design tokens (`--gd-primary-bg`, `--gd-radius`, etc.)
live at the top of the file; re-theme by overriding them in
your own stylesheet that you append to `:head`.

### Your own stylesheet, from the graph

Two ways to add CSS without leaving the editor:

- **Inline** — a `:custom-stylesheet` const wrapped in
  `:wrap-custom-style`, appended to the page's `:head` list:

  ```clojure
  {:name :my-theme
   :parent :custom-stylesheet
   :args {:body ".card { border-width: 2px; }"}}

  {:name :my-theme-style-tag
   :parent :wrap-custom-style
   :args {:body :my-theme}}
  ```

- **Served** — mount the same body at its own URL with
  `:stylesheet-route` and `<link>` it from any number of pages:

  ```clojure
  {:name :my-styles-route
   :parent :stylesheet-route
   :args {:path "/styles.css" :css :my-theme}}
  ```

  The route serves `text/css` with no cache directives, so an
  edit in the editor shows on the next reload.

If you don't want the default stylesheet — pass
`:head {:value []}` and the page gets no styling beyond
browser defaults.

## Wiring the runtime

If your page has any `data-action="..."` buttons (a form
submit, a `navigate`, an inline `custom` handler), bind
`:scripts` to `:graphden-runtime-scripts`:

```clojure
{:name :my-contact-page
 :parent :html-page-route
 :args {:path "/contact"
        :title "Contact us"
        :body :my-contact-body
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
> and initialises the graph renderer + WebSocket subscriptions
> that crash a non-editor page. `/assets/graphden-runtime.js`
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
3. `:_contact-demo-submit-button` — `:parent :submit-button`,
   `:label "Send"`, `:extras {:data-target "#contact-result"}`.
4. `:_contact-demo-result-panel` — empty `<div id="contact-result">`.
5. `:_contact-demo-form` — `:parent :form`, children = the four
   items above in order.
6. `:_contact-demo-page-body` — header + intro + form + result
   panel wrapped in a sized `<div>`. Plus an outside-the-form
   `:_contact-demo-custom-button` (`:parent :custom-button`)
   demonstrating the escape hatch.
7. `:_contact-demo-page-handler` — one fn-def with
   `:parent :html-page-handler` pinning `:title` /
   `:body` / `:head` / `:scripts`. That's the WHOLE
   page handler.
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

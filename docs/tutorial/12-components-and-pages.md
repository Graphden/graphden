## Lesson 12 — Composing pages from components

**Goal**: by the end of this lesson you can build a working
HTML page — header, form, submit button, response panel —
entirely by composing the platform's `web.components` library,
without writing a line of JS or hand-rolling hiccup.

**Concepts introduced**: `web.components`, `:button`, `:input`,
`:textarea`, `:select`, `:option`, `:checkbox`, `:form`,
`:link`, `:image`, `:card`, `:dispatch-action`, `data-action`,
`/assets/graphden-runtime.js`, `submit-form` handler,
`navigate` handler.

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

## A full page: the contact-form demo

`app.contact-demo` (shipped) composes the ten primitives + the
DSL into a working contact form. Visit `/demo/contact` in the
running editor; the page is:

```
┌──────────────────────────────────────────────────────┐
│  Contact us                                          │
│                                                      │
│  Block 2 demo — every element on this page is        │
│  composed from web.components + web.runtime fn-defs. │
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
7. `:_contact-demo-page` — `:parent :html-page` with the runtime
   bundle in `:scripts`.

## The runtime bundle

User pages load `/assets/graphden-runtime.js` (NOT `editor.js`
— that one initialises Cytoscape and would crash a non-editor
page). The runtime bundle is the concatenation of
`editor-runtime.js` (dispatcher + handler registry + partial
loader) and `editor-actions-builtin.js` (the `navigate` and
`submit-form` handlers).

Bootstrap requirement: after the bundle loads, the page must
call `bindActionDispatch(document.body)` once to start
listening for `data-action` clicks. The demo page ships this
as an inline `<script>` in the page's `:scripts` list:

```clojure
{:name :_contact-demo-bootstrap-script
 :parent :hiccup
 :args {:tag {:value "script"}
        :attrs {:value {}}
        :children {:value
                   ["document.addEventListener('DOMContentLoaded', "
                    " function () { bindActionDispatch(document.body); });"]}}}
```

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
cover (a custom hover effect, a one-off computed style) is the
`:custom-script` block coming in user-sites Block 3 — see
`docs/USER_SITES_PLAN.md`.

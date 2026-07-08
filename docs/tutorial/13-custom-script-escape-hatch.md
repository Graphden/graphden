## Lesson 13 — The `:custom-script` escape hatch

**Goal**: by the end of this lesson you can express interactive
behaviour the platform components don't cover — a custom hover
effect, a one-off DOM tweak, integration with a 3rd-party JS
library — without leaving the graph editor and without
modifying any Clojure code.

**Concepts introduced**: `:js-source` type alias,
`:custom-script`, `:wrap-custom-script`, `:dispatch-custom`,
`data-action="custom"`, `data-custom-handler`, escape hatch.

## When you'd reach for this

Lesson 12 covered the 10 platform components and the two
built-in click handlers (`navigate`, `submit-form`). Real
sites usually need a sliver of behaviour outside that scope —
a tooltip on hover, a date-picker library, a confetti burst
on form submit, scroll-to-anchor, etc. Rather than every such
need becoming a feature request for the platform, the escape
hatch lets you drop in raw JS through the same fn-def surface
you use for everything else.

Two shapes, picked by where you need the JS to live:

| Where the JS runs | Use |
|---|---|
| Once, when the page loads (script setup) | `:wrap-custom-script` in the page's `:scripts` list |
| Per-click on a button | `:dispatch-custom` bound to the button's `:attrs` |

## Page-level inline JS

`:wrap-custom-script` renders a JS body as
`<script>body</script>` for inclusion in a page's `:scripts`
list. Free arg `:body` is typed `:js-source` (the editor's
textarea widget kicks in).

Worked example — a script that decorates every `[data-tip]`
element on the page with its tooltip text:

```clojure
{:name :my-tooltip-script
 :parent :wrap-custom-script
 :args {:body "document.querySelectorAll('[data-tip]').forEach(e => { e.title = e.dataset.tip; });"}}
```

Then in your page:

```clojure
{:name :my-page
 :parent :html-page
 :args {:title "Tips demo"
        :body :my-page-body
        :scripts [:graphden-runtime-script-tag :my-tooltip-script]}}
```

Reload, hover over any `[data-tip]` element — the browser
tooltip appears.

### When to use `:custom-script` instead

`:wrap-custom-script` takes the JS body inline as `:body`. If
the SAME body needs to appear both as a `<script>` AND as a
button's inline handler (a rare case), pull it into a named
`:custom-script` and reference it from both:

```clojure
{:name :tooltip-init-body
 :parent :custom-script
 :args {:body "document.querySelectorAll('[data-tip]').forEach(e => { e.title = e.dataset.tip; });"}}

{:name :my-tooltip-script-tag
 :parent :wrap-custom-script
 :args {:body :tooltip-init-body}}
```

For the 90% case where the body is used once, skip the
intermediate `:custom-script` — bind `:body` directly on
`:wrap-custom-script` / `:dispatch-custom`.

## Per-click inline handlers

`:dispatch-custom` builds the button-attrs shape
`{:data-action "custom" :data-custom-handler <body>}`. The
runtime's `custom` action handler reads `data-custom-handler`
on click and evaluates it as:

```javascript
(new Function('btn', 'event', 'host', body))(btn, event, host);
```

So your JS body has three bindings in scope:

| Binding | What |
|---|---|
| `btn` | The clicked button element (has `dataset.*` for any extra `data-*` attrs you attach) |
| `event` | The click event (`event.target`, `event.preventDefault()`, …) |
| `host` | The dispatch host element (the root the dispatcher was bound to) |

Worked example — a "Wave at me" button that toggles a result
panel's emoji content with no server roundtrip (this is the
button shipped on `/demo/contact`):

```clojure
{:name :my-wave-button
 :parent :custom-button
 :args {:label "Wave at me"
        :body "var t = document.getElementById('contact-result'); t.textContent = (t.textContent.trim() === '👋') ? '' : '👋';"
        :extras {:value {:type "button"}}}}
```

`:custom-button` (in `web.components`, Lesson 12) is the
convenience template — its `:body` slot is `:js-source`-typed,
which gives you the textarea editor widget instead of a
single-line input.

If you'd rather see the underlying composition, the raw shape
is `:button` with `:attrs` built from
`:dispatch-custom` + `:extras` via `:merge`. The template just
packages it.

## What happens on parse / runtime error

If your body is syntactically invalid JS or throws at
runtime, the `custom` action handler catches the error and
logs it to `console.error` — the click is a silent no-op,
sibling buttons keep working. The point: a typo in one
escape-hatch body doesn't take down the rest of your page.

```
custom handler: parse failed — Unexpected token '%'
custom handler: runtime error — Cannot read properties of null
```

Both surface in DevTools. There's no syntax check at graph-save
time — the runtime JS parser is the only validator.

## Editor widget

A slot typed `:js-source` (which `:body` is, on all three
escape-hatch entries) resolves through `/api/value-form` to a
multi-line `<textarea rows="8">` — the same shape as the JSON
editor but skipping JSON.parse so the value round-trips as a
plain string. No syntax highlighting; no Monaco. That decision
is deliberate: the +500 KB bundle isn't worth it until the
inline-edit experience is a real bottleneck.

## Try it: extend `/demo/contact`

The contact-form demo already ships one escape-hatch button.
Add a second that, on click, fills the message textarea with a
canned template:

```clojure
{:name :_contact-demo-fill-template-button
 :parent :custom-button
 :args {:label "Use template"
        :body "document.querySelector('textarea[name=message]').value = 'Hi, I\\'d like to know more about ...';"
        :extras {:value {:type "button"}}}}
```

Append it to `:_contact-demo-page-body`'s `:children`, run
`bb rebuild`, reload `/demo/contact`. Click "Use template" —
the textarea fills.

## What you've now got

| Layer | What | Lessons |
|---|---|---|
| Graph model | fn-defs, slots, bindings, types, effects | 1–7 |
| Process | branches, executing fns, services | 8–10 |
| Code re-use | packages | 11 |
| User-facing UI | components, dispatch, page routes | 12 |
| Escape hatch | `:custom-script` / `:dispatch-custom` | 13 |
| Distribution | publish, install, update, fork | 14 |

Lesson 14 continues from packages (Lesson 11): once you've
authored a namespace, it shows how to publish it as a versioned
artifact and install / update / fork it across branches from the
editor's Packages panel.

Multi-tenancy (multiple users hosting their own sites on one
graphden instance, each with their own deploys / secrets /
auth-isolated routes) is a separate future phase. Today every
fn-def in this graphden installation shares the same surface;
Lesson 12 covers how to mount your own page routes alongside
the editor's, Lesson 13 gives you the JS escape hatch.

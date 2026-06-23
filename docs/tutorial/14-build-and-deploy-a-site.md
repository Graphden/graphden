## Lesson 14 — Build and deploy your own site

**Goal**: by the end of this lesson you've built a small site —
a couple of pages with forms — and have it serving on your
graphden instance, mounted alongside the editor.

**Concepts introduced**: `app.user-site` templates
(`:user-page-route`, `:user-page-handler`, `:user-page-rendered`,
`:user-runtime-scripts`), `:user-site-routes` mount-point,
declarative-sync gotcha.

## The shape of a user site

A user site is a list of route fn-defs the user adds to
`:user-site-routes` (in `resources/packages/app/user-site/fns.edn`).
On `bb deploy`, the reitit router walks `:all`, which concats
`:_base-routes` (graphden's own surface — editor + /api +
partials + demo) with `:user-site-routes` (the user's). Both
sets are siblings at the top level — `/about` and `/api/...`
share the same router.

The user-site module ships a handful of templates that
collapse common chains into one fn-def. You only need to
think about the body (the hiccup) and the path (the URL).

## Building your first page

Imagine an "About" page at `/about`. Three fn-defs:

```clojure
;; The hiccup body — anything goes; reuse components from
;; web.components, mix in plain hiccup, whatever.
{:name :about-page-body
 :parent :hiccup
 :args {:tag {:value "div"}
        :attrs {:value {:style "max-width:520px;margin:2em auto;font-family:system-ui"}}
        :children {:value [[:h1 "About"]
                           [:p "Hi! This page is built entirely from fn-defs."]
                           [:p "Source: " [:code ":about-page-body"]]]}}}

;; The route. :user-page-route bundles get-route +
;; html-ok-response + render-hiccup + html-page into one
;; declaration. Free args: :path, :title, :page-body, :scripts.
{:name :about-page
 :parent :user-page-route
 :args {:path "/about"
        :title "About"
        :page-body :about-page-body
        :scripts {:value []}}}  ; no JS needed

;; Append to the mount-point so the router picks it up.
;; (See "The mount-point gotcha" below — for now do it by
;; editing the EDN.)
```

You wire it in by editing `resources/packages/app/user-site/fns.edn`,
appending `:about-page` to `:user-site-routes`'s `:items`:

```clojure
{:name :user-site-routes
 :parent :list
 :args {:items [:demo-contact :about-page]}}     ; ← add here
```

`bb deploy`, visit `http://localhost:9002/about` — page renders.

### Why `:page-body` not `:body`?

Because `:user-page-route` plumbs `:body` through to two
different layers: the hiccup page body, and the HTTP response
body. They'd collide if both were called `:body`. The free
arg you pass at the top is `:page-body`; the user-site
template renames internally so it ends up at the right slot.

## A page with the runtime bundled in

If your page has any `data-action="..."` buttons (a form
submit, a navigate, an inline `custom` handler), bind
`:scripts` to `:user-runtime-scripts` — that drops in both
the runtime bundle (`<script src="/assets/graphden-runtime.js">`)
and an inline `<script>` that calls
`bindActionDispatch(document.body)` on DOMContentLoaded.

```clojure
{:name :contact-page
 :parent :user-page-route
 :args {:path "/contact"
        :title "Contact us"
        :page-body :contact-page-body
        :scripts :user-runtime-scripts}}     ; ← that's it
```

After this any button in `:contact-page-body` whose `:attrs`
were built from `:dispatch-action` / `:dispatch-custom` /
`:submit-form` will work — no extra wiring needed.

## A page with a form (full method-map dance)

`:user-page-route` is GET-only. If you need POST on the same
path, fall back to the building blocks:

```clojure
;; GET handler — use :user-page-handler (not :user-page-route)
;; so it composes into a method-map.
{:name :_my-form-get-handler
 :parent :user-page-handler
 :args {:title "Subscribe"
        :page-body :my-form-page-body
        :scripts :user-runtime-scripts}}

;; POST handler — your call, anything that returns a Ring response
{:name :_my-form-post-handler
 :parent :html-ok-response
 :args {:body :my-thanks-html-text}}

;; Method-data per method
{:name :_my-form-get-data
 :parent :method-map
 :args {:method "get" :handler :_my-form-get-handler}}

{:name :_my-form-post-data
 :parent :method-map
 :args {:method "post" :handler :_my-form-post-handler}}

;; Merge into the {get post} shape reitit wants
{:name :_my-form-methods
 :parent :merge
 :args {:maps [:_my-form-get-data :_my-form-post-data]}}

;; The route entry: ["/subscribe" {get … post …}]
{:name :my-form-route
 :parent :list
 :args {:items ["/subscribe" :_my-form-methods]}}
```

Then `:my-form-route` goes into `:user-site-routes`'s
`:items`. Same shape as the contact-demo (`:demo-contact` in
`app/contact-demo/fns.edn`) — copy it.

## The mount-point gotcha

`:user-site-routes` is a fn-def declared in graphden's EDN.
Every `bb deploy` runs declarative sync, which RESETS the
fn-def's `:items` binding to whatever the EDN says. If you
add `:my-page` to `:items` through the editor and then run
`bb deploy`, your addition gets wiped.

For v0 the two paths forward are:

1. **Edit the EDN.** Append your route fn-defs to
   `app/user-site/fns.edn`'s `:user-site-routes :items`,
   commit, deploy. Survives redeploys because it IS the
   source of truth.

2. **Maintain your routes in a downstream package.** Create
   `resources/packages/mysite/` and define your fn-defs
   there. The catch: today's `:user-site-routes` is owned by
   `app.user-site`, so you can't add to its `:items`
   without editing graphden's EDN. Branch-scoped sites and
   auto-discovery (where `:user-site-routes` would aggregate
   from any package marking its routes "user-mountable")
   are tracked open questions in
   `docs/USER_SITES_PLAN.md` Block 4.

Path 1 is the documented path for v0. If you're building
through the editor for exploration, that's fine — your fn-defs
persist in the DB across editor sessions. When you want to
SHIP, copy the EDN-form of your fn-defs into your fork's
`app/user-site/fns.edn`.

## The shipped example

`/demo/contact` (live on every graphden instance) is built
this way. See:

- `resources/packages/app/contact-demo/fns.edn` — the page
  body, components, GET / POST handlers, and the multi-
  method route entry `:demo-contact`.
- `resources/packages/app/user-site/fns.edn` —
  `:user-site-routes` lists `:demo-contact` as its sole
  default entry.

Read those files alongside this lesson — they're the
paste-into-your-fork starting point.

## What you've now got

| Layer | Lessons |
|---|---|
| Graph model | 1–7 |
| Process | 8–10 |
| Code re-use | 11 |
| User-facing UI | 12 |
| Escape hatch | 13 |
| Site delivery | 14 (this lesson) |

The full pipeline: model your behaviour as fn-defs (1–11),
render it through components + the runtime (12), drop in raw
JS where the platform doesn't suffice (13), mount it under
your own routes (14). All graph-visible, all branchable, all
type-checked, all editable through the editor.

The remaining open questions — multi-site hosting,
branch-scoped routing, auto-discovery of user route packages —
are tracked in `docs/USER_SITES_PLAN.md`. They're not gating
"can a user build and deploy a site today"; they're "how
opinionated should the deploy story be once we have real
users telling us what they want".

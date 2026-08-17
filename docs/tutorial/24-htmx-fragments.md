# Lesson 24 — Live fragments: htmx from the graph

**Goal**: by the end of this lesson your page has a button that
fetches a server-rendered fragment and swaps it into the page —
no custom JS, no page reload — with the data, the markup and the
wiring all visible in the graph.

**Concepts introduced**: `web.htmx` (`:hx-get-attrs`,
`:hx-post-attrs`, `:hx-button`, `:hx-swap-mode`),
`:fragment-route` / `:fragment-post-route` /
`:html-fragment-handler` (app.page), `:with-htmx`,
`/assets/htmx.min.js` (vendored — no CDN).

## The idea

The editor's own popovers work this way (see
`docs/PARTIALS.md`): an element carries `hx-get="/partials/…"`,
htmx fetches the URL, and the returned HTML swaps into a target.
`web.htmx` gives your pages the same vocabulary. A *fragment* is
just a fn returning hiccup, served by `:fragment-route` as
`text/html` with no page shell — htmx drops it into the DOM.

Each request executes the fragment's graph afresh, so whatever
the graph computes — a query, a counter, a clock — is live.

## Try it: a server clock

Three fn-defs. First the fragment — the current server time,
recomputed on every request:

```clojure
{:name :clock-fragment
 :parent :wrap-element
 :args {:tag "p"
        :content {:parent :to-str
                  :args {:value {:parent :current-time-ms
                                 :args {}}}}}}
```

Serve it at its own URL:

```clojure
{:name :clock-fragment-route
 :parent :fragment-route
 :args {:path "/fragments/clock"
        :fragment :clock-fragment}}
```

Then a page whose button fetches it. The button is a plain
`:button` with its `:attrs` built by `:hx-get-attrs`; the
`:target` names the element the fragment swaps into:

```clojure
{:name :clock-page-body
 :parent :stack
 :args {:children
        [{:parent :heading
          :args {:level 2 :content "Server clock"}}
         {:parent :button
          :args {:label "Refresh"
                 :attrs {:parent :hx-get-attrs
                         :args {:url "/fragments/clock"
                                :target "#clock-out"}}}}
         {:parent :card
          :args {:children ["press Refresh"]
                 :attrs {:value {:id "clock-out"}}}}]}}

{:name :clock-page
 :parent :html-page-route
 :args {:path "/clock"
        :title "Server clock"
        :body :clock-page-body
        :head {:parent :with-htmx
               :args {:head :graphden-page-head}}
        :scripts {:value []}}}
```

`:with-htmx` appends the htmx `<script>` to the head list you
give it — here on top of the default stylesheet head. The bundle
is served locally at `/assets/htmx.min.js` (vendored into the
platform, hash-busted per deploy), so pages work with no CDN and
no external dependency.

Mount `:clock-page` and `:clock-fragment-route` the same way as
any route (lesson 12: the `:all` items list locally, an app route
on the cloud). Open `/clock`, press **Refresh** — the number
changes on every click, straight from a fresh graph execution.

## Auto-refresh — `:trigger`

`:hx-get-attrs` takes an optional `:trigger` — any htmx trigger
spec. Replace the button with a self-updating panel:

```clojure
{:parent :card
 :args {:children ["…"]
        :attrs {:parent :hx-get-attrs
                :args {:url "/fragments/clock"
                       :trigger "load, every 5s"}}}}
```

No `:target` — the fragment swaps into the element itself.
`:swap` (an `:hx-swap-mode` closed enum — the editor offers a
select) picks the strategy when `innerHTML` isn't what you want.

## Forms — POST fragments

`:hx-post-attrs` on a `<form>` makes htmx serialize the fields
into the POST body; `:hx-button` is the one-liner button for it
(the htmx twin of `:submit-button`). On the server, a fragment
that reads the submitted fields needs the ring request — declare
your own handler child (the template itself can't, because
`:lambda-params` must name a real free arg):

```clojure
{:name :vote-fragment
 :parent :wrap-element
 :args {:tag "p"
        :content {:parent :str
                  :args {:parts
                         ["you voted: "
                          {:parent :get
                           :args {:coll {:parent :parse-form-body
                                         :args {:request {:as :request}}}
                                  :key "choice"
                                  :default "nothing"}}]}}}}

{:name :vote-fragment-handler
 :lambda-params [:request]
 :parent :html-fragment-handler
 :args {:fragment :vote-fragment}}

{:name :vote-fragment-route
 :parent :post-route
 :args {:path "/fragments/vote"
        :handler :vote-fragment-handler}}
```

The page side is a `:form` whose `:attrs` come from
`:hx-post-attrs {:url "/fragments/vote" :target "#vote-out"}` —
fields, button, target panel exactly as in the clock example.

## Push, not poll — SSE streams

`hx-trigger="every 5s"` polls. For genuinely live panels the
server can PUSH instead: `:sse-fragment-handler` (app.page) keeps
the connection open as a Server-Sent-Events stream, re-renders
the fragment on an interval server-side, and pushes **only when
the HTML changed**. The client side is one attrs builder:

```clojure
{:name :sse-clock-handler
 :lambda-params [:request]
 :parent :sse-fragment-handler
 :args {:fragment :clock-fragment
        :interval-ms 1000}}

{:name :sse-clock-route
 :parent :get-route
 :args {:path "/streams/clock"
        :handler :sse-clock-handler}}

{:name :sse-clock-panel
 :parent :card
 :args {:children ["connecting…"]
        :attrs {:parent :sse-connect-attrs
                :args {:url "/streams/clock"}}}}
```

Put `:sse-clock-panel` in the page body, and take
`:with-htmx-sse` instead of `:with-htmx` in `:head` (it adds the
SSE extension on top of htmx — both served locally). The panel's
content is replaced on every push; unchanged ticks cost the
client nothing.

Streams are bounded by design: each closes itself after
`:max-lifetime-ms` (default 5 min, capped at 30) and the
browser's EventSource transparently reconnects, so a page left
open keeps updating through stream generations. A
deployment-wide cap (`GRAPHDEN_SSE_MAX_STREAMS`, default 200)
turns overload into a clean 503 + retry instead of resource
exhaustion.

Live demo: the contact-form demo page (`/demo/contact`, lesson
12) now carries exactly this panel — a server clock streaming
over `/demo/contact/clock`.

## When to use which layer

| Need | Take |
|---|---|
| Click → run a registered JS handler | `:dispatch-action` (lesson 12) |
| Click/submit → fetch a **server** fragment | `web.htmx` + `:fragment-route` (this lesson) |
| Server-pushed live panel (no polling) | `:sse-connect-attrs` + `:sse-fragment-route` (this lesson) |
| One-off DOM behaviour no vocabulary covers | `:custom-script` (lesson 13) |

htmx fragments keep the behaviour server-side: the fragment is a
graph fn you can inspect, type-check, branch and reuse — the same
property the editor relies on for its own UI.

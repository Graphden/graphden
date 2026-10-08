# Lesson 14 — Live fragments: htmx from the graph

**Goal**: by the end of this lesson your page has a button that
fetches a server-rendered fragment and swaps it into the page —
no custom JS, no page reload — with the data, the markup and the
wiring all visible in the graph.

**Concepts introduced**: `web.htmx` (`:hx-get-attrs`,
`:hx-post-attrs`, `:hx-button`, `:hx-swap-mode`,
`:sse-connect-attrs`), `:fragment-route` /
`:html-fragment-handler` / `:sse-fragment-handler` (app.page),
`:wake-on-writes`, `:with-htmx` / `:with-htmx-sse`,
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

## Try it in an isolated HTML preview

The interactive lesson needs an account browser session, **manage-apps**
access and a deployment with an isolated apps domain. Extend
`web.response/html-ok-response` and bind its `:body` to the HTML string below.
Open **⋯ → Apps**, choose a unique subdomain label and click **+ Add app**.
This ordinary app route provides the organization's isolated host; the lesson
records its exact UUID for cleanup. Then close Apps and choose **⋯ → HTTP →
Preview HTML handler → Open HTML preview**. The preview captures the exact
current branch and starts no background service. **Finish** removes the
lesson's app route before removing its handler and branch.

```html
<!doctype html><html><head><meta name="htmx-config" content='{"selfRequestsOnly":false,"withCredentials":false,"allowEval":false}'><script src="assets/htmx.min.js"></script></head><body><button hx-get="fragment" hx-select="#fragment" hx-target="#out">Refresh</button><div id="out"><p id="fragment">First fragment</p></div></body></html>
```

Keep the preview tab open. Edit only `First fragment` to `Second fragment`
in the graph's saved body. In the preview tab click **Refresh**: htmx makes
an HTTPS request to `fragment`, selects `#fragment` from the response and
replaces `#out`, without a page reload. The full page remains in the response
so opening a fresh preview still gives you the same working button. A handler
can instead branch on `request.uri` and return just the requested fragment;
the regular route example below shows that separation.

The preview link expires after **two minutes**. If it is denied, return to
the same graph's **HTTP** popover, click **Preview HTML handler** again and
open the new link. Close the expired tab and continue with the new one;
you do not need to recreate the graph. Treat the URL as a temporary
capability: do not share or log it. Deleting its captured branch denies
requests; it never falls back to main.

The sandbox has an opaque origin and cannot read editor or app cookies.
Only the relative vendored `assets/htmx.min.js` script is available. The
explicit htmx configuration permits its cross-origin sandbox request without
credentials or evaluated expressions. Requests stay inside that capsule;
external scripts, redirects, response cookies and streaming responses are
not allowed. Existing app routes below support the wider page/stream
examples on a configured app origin. On a self-hosted installation without
an isolated apps domain, use such an independently configured origin; the
temporary text/JSON host never serves HTML on the editor origin.

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
any route (lesson 10's `:all` list on a self-hosted instance, or
as an app — lesson 30). Open `/clock`, press **Refresh** — the number
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

### Event-driven, not just interval-driven

Add `:wake-on-writes true` to the handler and the interval stops
being the latency: any write on the platform's event bus (a graph
edit, a `:create-entity` from another page, a cron writing rows)
triggers one debounced extra render, so a data change reaches every
subscribed page in well under a second — while `:interval-ms`
degrades to a keepalive ceiling:

```clojure
{:name :sse-clock-handler
 :lambda-params [:request]
 :parent :sse-fragment-handler
 :args {:fragment :clock-fragment
        :interval-ms 5000
        :wake-on-writes true}}
```

Spurious wakes are cheap — a wake is one server-side render plus a
hash compare, and only a **changed** fragment is pushed.

Streams are bounded by design: each closes itself after
`:max-lifetime-ms` (default 5 min, capped at 30) and the
browser's EventSource transparently reconnects, so a page left
open keeps updating through stream generations. A
deployment-wide cap (`GRAPHDEN_SSE_MAX_STREAMS`, default 200)
turns overload into a clean 503 + retry instead of resource
exhaustion.

Live demo: the contact-form demo page (`/demo/contact`, lesson
10) carries exactly this panel — a server clock streaming over
`/demo/contact/clock`; save any fn in the editor and watch it
jump ahead of its 5-second keepalive.

## When to use which layer

| Need | Take |
|---|---|
| Click → run a registered JS handler | `:dispatch-action` (lesson 10) |
| Click/submit → fetch a **server** fragment | `web.htmx` + `:fragment-route` (this lesson) |
| Server-pushed live panel (no polling) | `:sse-connect-attrs` + `:sse-fragment-handler` on a `:get-route` (this lesson) |
| One-off DOM behaviour no vocabulary covers | `:custom-script` (lesson 11) |

htmx fragments keep the behaviour server-side: the fragment is a
graph fn you can inspect, type-check, branch and reuse — the same
property the editor relies on for its own UI.

## Next

[Lesson 15 — Executing a fn: free-arg form, history, cancel](15-executing-a-fn.md)

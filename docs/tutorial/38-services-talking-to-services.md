# Lesson 38 — HTTP calls by function identity

**Goal:** name a published handler by UUID, resolve its public URL, make an actual
HTTP GET, and receive a changed response after editing the graph.

**You need:** the authenticated finite HTTP host from lesson 35. Ordinary cloud
users can take this lesson; it creates no persistent service or listener port.

## Try it

1. Extend `text-ok-response` as `tutorial-contract-answer` and bind its `:body`
   to `hello`. Choose **⋯ → HTTP → Publish for 30 minutes**.
2. Extend `service-endpoint` as `tutorial-http-address`. Bind its `:service` slot
   with **Bind fn-ref** to `tutorial-contract-answer`. This stores the handler
   UUID without executing it.
3. **Run** the address function and confirm the database effect if requested.
   Read its result: `host`, `port`, and `url`. Keep the whole URL, including any
   publication path. On a deployment behind TLS this is an HTTPS URL.
4. After Run finishes, copy that URL into another browser tab. The real HTTP
   response must show `hello`. Return to the editor.
5. Edit the handler's bound literal to `updated` and save. Leave its publication
   and the address function's fn-ref unchanged.
6. Reload the same public tab. Its response must now be `updated`.
7. Stop the publication through **⋯ → HTTP**. Reload its URL to verify it is
   unavailable, then finish the lesson and remove its created items.

These requests run sequentially, so the exercise also works with one execution
slot. A busy executor may return 503: finish another run and retry. The browser
request is real HTTP, and the published handler still obeys its execution quota,
current permissions, effect restrictions, and deadline.

## Calling from a graph

`service-get` composes the same identity resolution with an ordinary outbound
HTTP GET. Bind `:service` to the handler's fn-ref and `:path` to `/hello`. Its
result contains the HTTP status, headers and body. Network permissions, outbound
address checks, rate limits and timeouts still apply.

A graph calling a handler on its own executor needs capacity for both calls:
its Run holds an execution slot and worker while waiting for the HTTP response.
With one organization slot, the handler returns 503. A saturated shared worker
pool can also cause a timeout. Use sufficient capacity or a separately hosted
endpoint for such nested calls; publishing does not increase these limits.

Core resolves a base URL containing its reserved publication path; cloud resolves
an isolated apps origin. Do not strip that base path when building a request.
Never put a private bearer token in the public URL. The handler receives
sanitized request fields and returns bounded plain text or JSON.

## The same identity seam for persistent services

On a self-hosted or dedicated executor, `service-endpoint` first prefers an
active listener instance on the caller's branch. The reconciler maintains its
host, port and heartbeat. A temporary publication is a separate fallback, and
an ordinary configured cloud app may also resolve through the existing addon.
The function UUID still names the target without invoking it. No active endpoint
means `service/not-running`; it does not guess another branch.

The following sections describe shared contracts and the existing persistent
service model. Temporary publications do not create service-instance rows or
promise persistent-service tracing.

## The contract lives in the graph

For a larger application, define paths and response shapes once, then reference
them from both the producer and consumer:

```edn
;; svc-orders.api — the contract: paths + shapes, owned by the producer's team
{:name :orders-path :parent :const :args {:value "/orders"}}
{:name :orders-shape :type {:orders [:list :int]}}

;; the producer's route, built from the contract
{:name :orders-route :parent :get-route
 :args {:path :orders-path :handler-fn :orders-ok}}

;; the consumer, built from the same contract
{:name :fetch-orders :parent :service-get-json
 :args {:service :orders-service :path :orders-path}}
```

Change the path constant's value and both the route and consumer use the new
path. Narrow `:orders-shape` and the type-checker reports which graph no
longer matches. The contract is an ordinary type function (lesson 08),
so no separate interface-definition file is needed. With two teams in one
org, the contract namespace belongs to the producer's team and the
consumer's team holds `read` on it (lesson 28 — a role can be the
grant's subject, so the team is one row).

## On a multi-pod fleet, and on the cloud

- **Fleet** (docs/SCALING.md): the recorded host is the pod's
  `executor-id` — the same pod-FQDN the fleet's forward-hop dials — so
  a `:singleton` producer on pod 2 is reachable from a consumer on
  pod 1. A `:per-pod` listener records whichever pod started last;
  any of them serves.
- **Cloud**: a tenant has no ports. A fn published as an app (lesson
  30) resolves to its public origin, `https://<label>.graphden.app`,
  and the call is an ordinary outbound request — egress-guarded and
  rate-capped like any other. The graph is the same; only the answer
  to *where* differs.
- **Mutual calls** are legal: `svc-a` may name `svc-b` and `svc-b`
  name `svc-a`. The `:fn-ref` edge is an identity, not a dependency,
  so the cycle rule (lesson 13) does not fire.

## Following a call across services

For persistent HTTP services, Trace (lesson 18) can follow a call beyond
`:http-get`. Run `:fetch-orders` from the Run pane
and open the run's result: under it, a **Downstream calls** list names
`:orders-ring`, the producer's handler, with its status. Open the
producer's Runs tab: the request `:fetch-orders` made is a run of its
own there, and its details show the same trace id as the caller.

The mechanics: a persisted run knows its id; `:service-get` sends it
along as `X-Graphden-Trace`; `:http-server` sees the header and
records the request it handles as an execution linked to the caller
(a request without the header is not recorded — normal traffic pays
nothing). Every hop shares the top-level run's trace id, so a chain
`A → B → C` is one tree you can walk from A
([docs/EXECUTION.md § Tracing across services](../EXECUTION.md#tracing-across-services)).

## What we glossed over

- Why `:fn-ref` is its own type and not the HOF `:fn` slot — the HOF
  slot hands the impl something to *call*, and for a fn that returns
  a callable (every listener does) that means evaluating it. See
  [docs/TYPES.md](../TYPES.md#structural-types-records).
- Liveness: a copy that dies in place (the listener stops, a daemon
  thread ends) is noticed on the next tick and restarted per the row's
  `restart-policy`; a pod that crashes leaves a row whose heartbeat
  goes stale, so consumers stop picking it within 45 seconds
  ([docs/SERVICES.md § Liveness](../SERVICES.md#liveness--a-copy-that-died-in-place)).
- Asynchronous work between services is the next lesson
  ([39 — Queues](39-queues.md)).

## Next

[Lesson 39 — Queues](39-queues.md): asynchronous work between
services.

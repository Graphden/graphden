# Lesson 35 — Services talking to services: the contract lives in the graph

**Goal**: by the end of this lesson you can run two services, have
one call the other over HTTP by *naming* it — no address typed
anywhere — and keep the contract between them (paths, shapes) in one
place that both sides reference, so a change moves both and the
type-checker catches a drift.

**Concepts introduced**: `:service-endpoint`, the `:fn-ref` slot type
(a fn's *identity*), `:service-instance`, `:service-get` /
`:service-get-json`, the contract namespace, `service/not-running`.

**You need**: lesson 32 (a service is a no-arg fn the reconciler keeps
alive) and lesson 20 (branches) — a self-hosted instance, or a
dedicated-tier org on the cloud (services need one; see the last
section for what changes there).

## The idea in one paragraph

A service that listens somewhere *answers* somewhere. When the
reconciler starts an `:http-server` service it records where — a
`:service-instance` row with the host, the bound port and a heartbeat
— and deletes it when the service stops. Another fn can then ask for that address by
naming the service fn itself: `:service-endpoint :service
:orders-service`. That slot is typed `:fn-ref`, which means *the fn's
identity*: the consumer receives the producer's id, never runs it
(binding a listener there does not start a second one), and the edge
is not a dependency — so two services may even name each other. From
the address, `:service-get` builds the URL and `:http-get` does the
rest. The address is data the platform knows, the call is explicit
HTTP in the graph, and nothing is typed by hand.

## Try it

You will create a producer service on a free port, a consumer that
calls it, and watch the address appear and disappear.

1. **The producer.** Add these fn-defs the way you add any (the editor,
   `upsert-fn-defs` over `/mcp` — [docs/MCP_CLIENTS.md](../MCP_CLIENTS.md) —
   or a package module, lesson 28). Pick a port nothing else uses:

   ```edn
   {:name :orders-ok :parent :json-ok-response
    :args {:body "{\"orders\":[1,2,3]}"}}

   {:name :orders-ring :parent :encode-stringify-wrap
    :args {:base-handler :orders-ok}}

   {:name :orders-service :parent :http-server
    :args {:handler :orders-ring :port 9101}}
   ```

   `:json-ok-response` is the 200 + `application/json` template;
   `:encode-stringify-wrap` is the post-processing wrap every listener
   wants (it turns the header map back into the strings http-kit
   expects). `:orders-service` is service-eligible: no free args,
   declares `:process`.

2. **Make it a service.** Click `⚙` on `:orders-service` → "Make
   service" → *Create & reconcile* (lesson 32). The reconciler starts
   it within a second. Open the `⚙` popover again: under the status
   line, *Running copies* lists the copy — `127.0.0.1:9101 · local ·
   seen <time>` — and the time moves every fifteen seconds. The same
   rows are `GET /api/entities/service-instance?service-id=<id>` (on
   the cloud you see your org's). That is the
   reconciler talking: `:http-server` returned its handle with the
   bound port as metadata, the pod that started it added its own host,
   and it heartbeats the row every tick.

3. **The consumer.** Add:

   ```edn
   {:name :fetch-orders :parent :service-get-json
    :args {:service :orders-service :path "/orders"}}
   ```

   Look at `:fetch-orders` on the canvas: an edge to `:orders-service`
   into the `service` slot — the slot's type badge says `fn-ref`. Its
   free args are `headers`, `auth-value`, `timeout-ms` (from
   `:http-get`); `service` and `path` are bound.

   Extending `:service-get-json` from the editor works the same way:
   the card shows `service` and `path` as dashed placeholders even
   though both live deeper in the template (on `:service-endpoint` and
   the URL join) — a card lists every hole of its composition, the
   same set the Run pane asks for — and the `+` binds them on your fn.

4. **Run it.** ▶ on `:fetch-orders` — the Run pane shows
   `{:orders [1 2 3]}`. Trace (lesson 15) shows the chain:
   `:service-endpoint` (a `:db` read of the row) → `:service-url` →
   `:http-get` against `http://127.0.0.1:9101/orders` → `:parse-json`.

5. **Stop the producer.** Disable the service (`⚙` popover, or
   `PUT /api/entities/service/<id>` with `{"enabled?": false}`). The
   instance row is deleted. ▶ on `:fetch-orders`
   again: `service/not-running` — *no live instance for fn …
   (the service row exists but no copy is alive, or it is not a
   listener)*. Nothing guessed, nothing cached: the consumer is told
   the truth, and a real consumer wraps the call in `:try` / a retry
   (the reconciler brings the producer back on its own; lesson 32's
   restart policy).

6. **Enable it again.** The instance is back, the call works again.
   Delete the service and the three fn-defs when you are done.

## The contract lives in the graph

In the walkthrough the path `"/orders"` and the response shape were
typed twice — once in the producer, once in the consumer. Put them in
one place both sides reference and they cannot drift:

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

Rename the path constant and both the route and the consumer follow;
narrow `:orders-shape` and the type-checker reports the side that no
longer matches at write time — the `protobuf` effect, with no separate
IDL, because a type *is* a fn here (lesson 05). With two teams in one
org, the contract namespace belongs to the producer's team and the
consumer's team holds `read` on it (lesson 25 — a role can be the
grant's subject, so the team is one row).

## On a multi-pod fleet, and on the cloud

- **Fleet** (docs/SCALING.md): the recorded host is the pod's
  `executor-id` — the same pod-FQDN the fleet's forward-hop dials — so
  a `:singleton` producer on pod 2 is reachable from a consumer on
  pod 1. A `:per-pod` listener records whichever pod started last;
  any of them serves.
- **Cloud**: a tenant has no ports. A fn published as an app (lesson
  27) resolves to its public origin, `https://<label>.graphden.app`,
  and the call is an ordinary outbound request — egress-guarded and
  rate-capped like any other. The graph is the same; only the answer
  to *where* differs.
- **Mutual calls** are legal: `svc-a` may name `svc-b` and `svc-b`
  name `svc-a`. The `:fn-ref` edge is an identity, not a dependency,
  so the cycle rule (lesson 10) does not fire.

## Following a call across services

Trace (lesson 15) shows the call tree of one execution, and in step 4
it ended at `:http-get` — the producer's side was a separate world.
It isn't any more. Run `:fetch-orders` from the Run pane once again
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
  ([36 — Queues](36-queues.md)).

## Next

Lesson 28 — Packages ([already written](28-packages.md)): put the
contract namespace in a package so another org can install it.

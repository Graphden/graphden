# Lesson 36 — Queues: asynchronous work between services

**Goal**: by the end of this lesson you can hand work from one
service to another without either waiting for the other — publish a
message, run a consumer service that handles it, watch a failing
message retry and then land in the dead-letter state, and keep the
message's shape as a contract in the graph.

**Concepts introduced**: `:queue-publish`, `:queue-message`,
`:pg-queue-consumer` / `:queue-consumer`, `:take` / `:ack` / `:nack`
as swappable backend slots, visibility timeout, retry, dead letter,
the `NOTIFY` wake.

**You need**: lesson 32 (services) and lesson 35 (two services
talking over HTTP) — this lesson is the asynchronous counterpart.

## Why a queue, and why Postgres

In lesson 35 the consumer *called* the producer and waited. That is
right for a question that needs an answer now. For "an order was
placed, ship it eventually" it is wrong: if shipping is slow or down,
ordering should not fail. A queue decouples the two — the producer
drops a message and moves on; the consumer takes it when it can.

Graphden's queue is a Postgres table, not a broker. It has the shape
every Postgres job queue converges on: a message is *claimed* with
`FOR UPDATE SKIP LOCKED` (two workers never take the same row), the
claim holds a **visibility timeout** (a worker that dies mid-way
loses its claim and the message comes back), a failed handler
**retries** after a delay, and after a bounded number of attempts the
message is parked as **dead** with the error kept. A `NOTIFY` wakes an
idle consumer the moment something is published, so nothing polls
hot. No second service to install, and on the cloud a tenant sees only
its own messages like any other row.

## Try it

1. **A producer.** Add:

   ```edn
   {:name :order-placed :parent :queue-publish
    :args {:queue "orders" :delay-ms 0}}
   ```

   `:payload` stays free. ▶ on `:order-placed`, enter
   `{"sku": "A-1", "qty": 2}` as the payload, run. The result is the
   message id. `GET /api/entities/queue-message` lists one row:
   `queue: orders`, `state: pending`, `attempts: 0`.

2. **A handler and a consumer.** The handler here forwards the order
   to a second queue — a second service would take it from there:

   ```edn
   {:name :_order-payload :parent :get
    :args {:coll {:as :message} :key {:value :payload} :default nil}}

   {:name :ship-order :parent :queue-publish
    :args {:queue "shipping" :payload :_order-payload :delay-ms 0}}

   {:name :orders-worker :parent :pg-queue-consumer
    :args {:queue "orders" :handler :ship-order}}
   ```

   A handler is any fn with a `message` free arg; it receives
   `{id, queue, payload, attempts}`. `:orders-worker` has no free args
   and inherits `:process` from `:future`, so it is service-eligible.

3. **Run the consumer.** `⚙` on `:orders-worker` → "Make service" →
   *Create & reconcile* (lesson 32). Within a second the pending
   order is gone from `orders` and a row appeared on `shipping` with
   the same payload — the worker took the message, ran the handler,
   and acked it (an ack deletes the row).

4. **Publish while it runs.** ▶ `:order-placed` again. The worker was
   waiting on the `NOTIFY` bus; the message is handled at once, not on
   the next poll.

5. **Break the handler.** Add a consumer whose handler throws on the
   payload — parsing a non-JSON string does:

   ```edn
   {:name :_raw-payload :parent :to-str :args {:value :_order-payload}}

   {:name :parse-order :parent :parse-json
    :args {:string :_raw-payload :keywordize true}}

   {:name :strict-worker :parent :pg-queue-consumer
    :args {:queue "strict" :handler :parse-order}}
   ```

   Make `:strict-worker` a service, then publish `"not json"` on
   `strict` (a derived `:queue-publish` with `:queue "strict"`, or
   `:order-placed` with the queue rebound). Watch the row: `attempts`
   climbs by one every five seconds (the default retry delay) and
   `error` carries the parser's message; after the fifth attempt
   `state` is `dead` and the worker leaves it alone. Fix the handler,
   then open **Operate → Queues**: every queue with its pending / dead
   counts, and the dead letter with its error — *Requeue* puts it back
   (`:queue-requeue`: pending, attempts 0, error cleared) and it is
   handled; *Delete* drops it.

6. **Follow a message across the queue.** Run `:order-placed` from the
   Run pane (a persisted run has an identity — lesson 35) and open the
   run: under *Downstream calls* sits the worker's handling of that
   very message — a child execution of your run, with the message as
   its argument. `:queue-publish` stamps the publisher's trace on the
   row, and the consumer runs its handler through `:call-traced`, so
   the call tree continues past the queue exactly as it does past a
   socket.

7. Delete both services and the queue rows when you are done.

## The knobs, and the backend

The defaults live on three private fn-defs: `:_pg-queue-take` (batches
of 10, a 30 s visibility timeout, a 5 s wait on an empty queue),
`:_pg-queue-nack` (retry after 5 s, dead after 5 attempts) and
`:_pg-queue-extend` (renews the 30 s claim; the consumer beats it every
`:lease-every-ms` = 10 s while your handler runs, so a slow handler
keeps its message). To change them, derive your own and bind them on
your consumer:

```edn
{:name :_fast-nack :parent :queue-nack :args {:retry-ms 500 :max-attempts 3}}

{:name :orders-worker :parent :pg-queue-consumer
 :args {:queue "orders" :handler :ship-order :nack :_fast-nack}}
```

That works because `:take`, `:ack`, `:nack` and `:extend` are
*fn-typed slots* of `:queue-consumer` — the loop, the try/ack/nack,
the lease heartbeat and the handler call are graph composition that
does not know what a queue is. A broker package (Kafka, NATS) would
bind its own primitives to the same slots; your consumers, handlers
and contracts would not change.

## The contract lives in the graph

As with HTTP (lesson 35), put the message's shape in a type-row both
sides reference:

```edn
;; orders.api
{:name :order-shape :type {:sku :text :qty :int}}

;; producer — the payload slot narrowed to the contract
{:name :order-placed :parent :queue-publish
 :args {:queue "orders" :delay-ms 0 :payload {:as :payload :type :order-shape}}}

;; consumer handler — the message's payload read as the contract
{:name :_order-payload :parent :get
 :args {:coll {:as :message} :key {:value :payload} :default nil}}
```

Change `:order-shape` and the type-checker reports the side that no
longer fits at write time.

## What we glossed over

- Ordering: a single `:singleton` worker drains roughly in publish
  order; a `:pool` of workers handles messages in parallel, and a
  retried message goes to the back of its delay. Strict ordering per
  key is not a promise.
- At-least-once: a worker that dies between handling and acking sees
  the message again after the visibility timeout (a live worker keeps
  renewing its claim, so only a dead one loses it). Make handlers
  idempotent, or key the side effect on the message id.
- Dead letters stay until you requeue or delete them (Operate →
  Queues); there is no automatic sweep.

## Next

Lesson 28 — Packages ([already written](28-packages.md)).

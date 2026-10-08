# Lesson 39 — Queues: asynchronous work between services

**Goal**: by the end of this lesson you can hand work from one
service to another without either waiting for the other — publish a
message, run a consumer service that handles it, watch a failing
message retry and then land in the dead-letter state, and keep the
message's shape as a contract in the graph.

**Concepts introduced**: `:queue-publish`, `:queue-message`,
`:pg-queue-consumer` / `:queue-consumer`, `:take` / `:ack` / `:nack`
as swappable backend slots, visibility timeout, retry, dead letter,
the `NOTIFY` wake.

**You need:** permission to manage persistent services, a self-hosted or dedicated
executor, and an available service slot. A shared cloud plan can publish queue
messages but cannot start this consumer. Lesson 35's temporary HTTP publication
does not grant background-process permission or service quota.

## Why a queue, and why Postgres

An HTTP caller waits for a response. A queue lets a producer hand work to a
consumer that runs independently. Graphden stores messages in PostgreSQL:
`FOR UPDATE SKIP LOCKED` claims a due row, a visibility deadline releases the
claim if its worker dies, and failed handling retries before reaching the
`dead` state. A `NOTIFY` wakes an idle consumer. There is no separate broker.

Message delivery is at least once. Make effects idempotent: a worker can perform
an effect and die before acknowledging the message.

## Try it: publish, fail, requeue, repair, acknowledge

Choose a fresh queue name such as `tutorial-39-<fresh UUID>` and use that exact
text in both definitions below. Queue messages and service rows are not
versioned graph data; deleting the lesson branch does not remove them.

1. Extend `queue-publish` as `tutorial-queue-publish`. Bind `:queue` to the new
   queue name and `:delay-ms` to `0`. Leave `:payload` free.
2. **Run** it with payload `"sample"`, with **Save to history** enabled. Record
   the execution ID and the returned message UUID. Operate → Queues now shows
   one pending message on this queue. Publishing alone does not run a consumer.
3. Extend `parse-json` as `tutorial-queue-handler`. Bind `:string` to the text
   `not JSON`. On `:keywordize`, choose **Bind literal**, check the checkbox
   and **Save** an own `true` binding. The inherited default still leaves an
   optional callable argument; this explicit binding closes it. This
   deliberately failing handler now has no free arguments; it ignores the
   supplied message and demonstrates failure and acknowledgement without an
   external side effect.
4. Extend `pg-queue-consumer` as `tutorial-queue-worker`. Bind `:queue` to the
   same unique name, and bind its callable `:handler` slot to
   `tutorial-queue-handler`.
5. Open **⋯ → Service settings** on the worker. Select the lesson's exact branch,
   keep **Enabled** on and use **singleton**. Choose **Create & reconcile**.
   Record this service's UUID. Verify that its running instance appears; a
   saved desired-state row alone is not proof that the worker started.
6. Reopen Operate → Queues as it works. Each failed claim increments attempts.
   With the production defaults, retries wait five seconds; after five attempts
   the exact message is **dead** with its error recorded. This can take longer
   on a busy executor. Do not republish while waiting.
7. Disable this worker through its Service settings and **Save & reconcile**.
   Verify its running instance disappears before continuing.
8. In Operate → Queues, find this unique queue's dead message and choose
   **Requeue**. The same UUID becomes pending, with attempts reset to zero and
   the error cleared. It remains pending while the consumer is stopped.
9. Edit `tutorial-queue-handler`'s own `:string` literal to `{}`. Enable the
   existing worker again on the same branch. The corrected graph returns
   successfully, so the consumer ACKs the message: that exact message row
   disappears. Inspect its handling execution to distinguish success from a
   manual deletion.
10. Disable the recorded service UUID and verify that no registered running
    instance remains, then **Delete service**. Remove any remaining message by its recorded UUID. Only
    then delete the lesson functions or branch.

The ordinary graph for this exercise is:

```edn
{:name :tutorial-queue-publish :parent :queue-publish
 :args {:queue "tutorial-39-<fresh UUID>" :delay-ms 0}}

{:name :tutorial-queue-handler :parent :parse-json
 :args {:string "not JSON"}}

{:name :tutorial-queue-worker :parent :pg-queue-consumer
 :args {:queue "tutorial-39-<fresh UUID>" :handler :tutorial-queue-handler}}
```

The handler's literal is an own binding, so editing it does not change a shared
ancestor. The worker's callable reference stays fixed through repair. A real
handler can instead expose a `:message` input receiving
`{id, queue, payload, attempts, trace-id, parent-execution-id}` and read its
payload through ordinary graph composition.

If a publish response is lost, do not blindly run it again: it may already have
queued a message. Stop the known worker before recovery. A known persisted
execution ID can recover its result and exact message UUID. An unidentified
attempt remains unresolved; neither a matching function name nor the latest
queue row proves which message it created. Do not delete messages or services
by a shared name, and do not claim branch deletion cleans up either kind.

The consumer records traced handling executions. A message published by a
persisted Run carries that run's trace identity, so its successful or failed
handling appears under **Downstream calls**. This is an actual background
worker, unlike lesson 35's finite request handler.

## The knobs, and the backend

`pg-queue-consumer` binds its backend references to private definitions:
batches of 10, a 30-second visibility timeout, a five-second empty wait,
a five-second retry delay, and five attempts. Its ancestor `queue-consumer`
also sets a ten-second heartbeat period.

Those inherited bound references cannot be replaced by adding a binding on an
intermediate child. To choose different backend parameters, derive the earlier
`queue-consumer` ancestor, where `:take`, `:ack`, `:nack`, and `:extend` remain
open, and supply ordinary configured callables:

```edn
{:name :orders-take :parent :queue-take
 :args {:queue "orders" :batch 10 :visibility-ms 30000 :wait-ms 5000}}
{:name :orders-nack :parent :queue-nack
 :args {:retry-ms 500 :max-attempts 3}}
{:name :orders-extend :parent :queue-extend
 :args {:visibility-ms 30000}}

{:name :orders-worker :parent :queue-consumer
 :args {:take :orders-take :ack :queue-ack :nack :orders-nack
        :extend :orders-extend :handler :ship-order}}
```

The loop, try/ack/nack, heartbeat, and handler call are graph composition. A
broker package can supply different backend primitives to those same open
callable slots. A backend without leases derives `queue-consumer-leaseless`,
which supplies its existing no-op extension callable.

## The contract lives in the graph

As with HTTP (lesson 38), put the message's shape in a type-row both
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

After changing `:order-shape`, inspect the affected compositions' type
diagnostics. Shared types expose incompatible producer or consumer shapes;
this does not promise that every editing endpoint rejects an incomplete graph.

## What we glossed over

- Ordering: a single `:singleton` worker drains roughly in publish
  order; a `:pool` of workers handles messages in parallel, and a
  retried message goes to the back of its delay. Strict ordering per
  key is not a promise.
- At-least-once: a worker that dies between handling and acking sees
  the message again after the visibility timeout (a live worker keeps
  renewing its claim, so only a dead one loses it). Make handlers
  idempotent, or key the side effect on the message id.
- Dead letters stay until you requeue or delete them (Organization →
  Queues); there is no automatic sweep.

## Next

[Lesson 40 — The Marketplace](40-marketplace-themes-keymaps.md):
themes, keyboard layouts, and what others published.

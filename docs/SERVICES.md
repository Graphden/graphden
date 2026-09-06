# Service Registry

Declarative long-running services backed by the `:service` entity.
Packages declare baseline services in `package.edn`; an admin can
also write `:service` rows directly through `/api/entities/service`.
The reconciler turns enabled rows into running futures, supervises
startup failures, and stops them on shutdown.

Reconcile fires on integrant init, on every CRUD mutation, on a
`service:*` NOTIFY from a sibling pod, **and on a periodic tick**
(`:exec/service-reconciler`, ~15s). The tick makes the reconciler
level-triggered rather than purely edge-driven: it re-takes a
`:singleton`'s advisory lock after the holder pod crashes (the crash
emits no NOTIFY), picks up an out-of-band DB edit, and reconverges a
transient start failure — all without a restart.

**Multi-pod coordination is built.** Every pod runs its own
reconciler; a service's `:cardinality` decides how many pods run it
(`:singleton` → advisory-lock-gated, `:per-pod` → everywhere). Cron
schedules come in a later phase (see § Roadmap).

## Why services?

Before this feature, exactly one long-running fn was supported via a
package's `:startup-fn` field — typically `:web-server` baked into
`app/package.edn`. You couldn't:

- Run a second long-running fn alongside (e.g. metrics server on a
  different port)
- Declaratively manage what's alive — restart, disable, change config
- Get protection against accidentally running an already-running fn
  via the executor (Run ▶ on `:web-server` from the editor used to
  crash with `Address already in use`)

The `:service` registry fixes all three. Packages now contribute
baseline services through a `:services [...]` declaration in their
`package.edn` — the reconciler seeds them idempotently at boot, the
admin retains the option to disable them.

## The model: a service IS a no-arg fn

A `:service` row is just a marker on a fn that says "keep this alive".
The fn it points at must have **no start-blocking free arguments** —
every slot the fn needs to compute/configure itself at start must be
bound via fn-defs / bindings. The reconciler invokes the fn with empty
args. (A listener's handler args — supplied by the deferred invoker per
request/tick — are NOT start-blocking; see § Guard below.)

To run the same impl with different parameters, **create a derived
fn-def that binds the slot differently**, then declare a service for
the derived fn:

```edn
{:name :web-server-9001 :parent :http-server
 :args {:handler :_app-ring-response :port 9001}}
```

Then `POST /api/entities/service {fn-id: <web-server-9001-id>, enabled?: true, restart-policy: always}`.

This is intentional: it keeps service config visible in the fn-graph
(versioned, type-checked, composable) and avoids duplicating the
binding mechanism. Service rows stay tiny and declarative.

The redundant alternative would be a separate `:service-arg` table
that overlays slot values — we evaluated it and rejected it: it
duplicates `:binding` shape, hides config from the graph, and adds an
entity type that the project's "minimal entities" principle pushes
against.

Env-based config falls out of this naturally — `:port {:ref :_port-from-env}`
where `:_port-from-env` is itself a fn-def calling `:env "PORT"` and
parsing the result. Visible in the graph, type-checked, versioned.

### Service shapes

The service registry doesn't care WHAT shape of long-running work the
fn spawns — only that the fn's return is a 0-arg stopper-thunk and
that the fn declares the `:process` effect. Two patterns ship with
graphden today:

| Pattern | Parent | Shape | Example |
|---------|--------|-------|---------|
| **Long-lived listener** | `:http-server` (Ring/http-kit) | bind a port + a handler fn → returns a stopper that releases the port | `:web-server` in `packages/app/server` |
| **Cron-driven loop** | `:schedule` (composed over `:future` + `:loop-until-interrupted` + `:sleep-until-ms` + `:cron-next-after` + `:call-noargs`) | bind `:cron` (Quartz cron-6 string) + `:fn` (any 0-arg callable) → returns a stopper that interrupts the daemon thread | `:ex-cron-heartbeat` in `packages/examples/schedule-cron` |

`:schedule` is itself a pure fn-def composition — no monolithic
base-fn. The two captured args `:cron` and `:fn` propagate through
three HOF boundaries via closure-capture (docs/CLOSURE_CAPTURE.md).
This is the canonical template for adding more long-running patterns
(queue consumer, websocket listener, file watcher): compose over the
existing concurrency primitives, return a stopper, declare `:process`.

### Startup steps — schema migrations

A service fn is any no-arg fn, so "do X before the listener comes up"
is plain sequencing — `:do` — not a field on the `:service` row. The
`storage/pg` package ships the Flyway shape as two fn-def templates,
`:migration` and `:migrate` (composition only, no new base-fn — the
same role `:schedule` plays for cron):

```edn
{:name :m-001 :parent :migration
 :args {:id "001-users"
        :ddl {:value {:create-table [:users :if-not-exists]
                      :with-columns [[:id :bigserial [:primary-key]]
                                     [:email :text]]}}}}

{:name :m-002 :parent :migration
 :args {:id "002-users-created-at"
        :ddl {:value {:alter-table :users :add-column [:created_at :timestamptz]}}}}

{:name :app-migrate :parent :migrate :args {:migrations [:m-001 :m-002]}}

;; The service fn: migrate, then start the listener whose stopper
;; `:do` returns (it also inherits the listener's `:process` effect).
{:name :app-service :parent :do :args {:steps [:app-migrate :web-server]}}
```

| | |
|---|---|
| **One migration** | `:migration` — inside a transaction, when the `schema_migrations` journal has no marker for `:id`, run `:ddl` (a HoneySQL map) and insert the marker (returns 1); otherwise a no-op (nil). |
| **The run** | `:migrate` — ONE transaction under `pg_advisory_xact_lock`: ensures the journal table, then runs `:migrations` in order. N pods starting at once serialise and the late ones find nothing pending; a throwing migration rolls the whole run back, markers included. |
| **Adding one** | Append to the list — `{:migrations {:append [:m-003]}}` on a derived fn-def, or edit the vector. The write hook restarts every service whose closure contains the list (`invalidation.clj`), the `:do` runs the migrator again, only the new id is pending. |
| **Merging** | `:merge-post-commit!` restarts the same set on the target branch, so migrations added on a feature branch apply on `main`'s next start. |
| **Ids** | Stable, append-only, sortable strings (`"001-users"`). A duplicate id in one run fails the journal insert's primary key and rolls the run back — loudly, by design. |

Each migration is a graph node, not a row of data: the editor shows the
journal as the `:migrations` chain, and the type-checker sees every
`:ddl`. `graphden.packages.storage.migrations-test` pins the contract.

**Branches version the graph, not Postgres.** `:migration` runs
against graphden's OWN datasource, so a service on a feature branch
migrates the same physical database `main`'s service uses, and the
journal table is shared. Keep platform-table migrations on the branch
that owns the deployment; for a user database of your own, put the
DSN in a `branch-local?` `:env` fn-def and compose over `web/sql`
(`:sql-exec` / `:sql-query`) instead — then each branch can point at
its own database. Both templates record the `:raw-sql` effect, which
the cloud effect gate forbids to tenant graphs
([SECURITY_MODEL.md](SECURITY_MODEL.md)) — on the cloud they are a
platform / self-host affordance, not a tenant one.

## Storage schema

One non-versioned entity + two enums (`:restart-policy`, `:cardinality`;
admin mutates in place; the audit trail for what actually ran lives in
`:fn-execution` rows the services spawn).

### `:service`

| Field             | Type              | Notes                                                              |
|-------------------|-------------------|--------------------------------------------------------------------|
| `:id`             | `:uuid`           | Returned to clients as `service-id`.                              |
| `:fn-id`          | `:ref :fn`        | **Logical** fn id — service tracks the current graph; editing the fn picks up at next restart. (Compare `:fn-execution.fn-version-id` which is a frozen snapshot.) Must point at a fn with no start-blocking free args (§ Guard). |
| `:enabled?`       | `:bool`           | Reconciler only starts enabled rows. Toggle this + reconcile to stop a service without deleting it. |
| `:restart-policy` | `:restart-policy` | `:always` / `:on-failure` / `:never` — see § Supervisor below.    |
| `:cardinality`    | `:cardinality`    | `:singleton` / `:per-pod` / `:pool` — how many pods run it at once; see § Cardinality. Nullable; nil ≡ `:singleton` (rows that pre-date the field). |
| `:pool-size`      | `:int`            | Pod count for `:cardinality :pool` (ignored otherwise). Nullable — a `:pool` row with nil/non-positive size degrades to a singleton. |
| `:branch-id`      | `:ref :branch`    | Per-branch scope. Reconciler routes the start through `branch-router/ctx-for branch-id`, so the same `:fn-id` can run with branch-specific bindings on dev + prod simultaneously. Nullable — nil is normalized to the router's default branch at reconcile time (`effective-branch-id`), so a legacy nil-branch row behaves exactly like an explicit default-branch row, including the post-merge `restart-services-depending-on!` pass. Without a router (tests) nil falls back to the reconciler's base ctx. The editor's ⚙ popover picker defaults to the editor's current branch on create. |
| `:org-id`         | `:text` (null)    | **Tenant owner; NULL ≡ platform.** Load-bearing for fleet sharding: the reconciler drops services whose org this pod doesn't serve (`service-in-shard?`), so a dedicated tenant's services run only on its own pod. Stamped by the tenant service-create endpoint (NOT `OrgScopedStorage` — `:service` is tenant-forbidden write-through). |

### `:service-instance`

One row per RUNNING copy (§ Endpoints, § Liveness) — the desired-state
row says *keep it running*, an instance row says *this pod runs it,
here, and was alive at `seen-at`*. Written by the pod that starts the
copy, deleted on every stop path, heartbeat every reconcile tick.

| Field          | Type            | Notes                                                                 |
|----------------|-----------------|-----------------------------------------------------------------------|
| `:service-id`  | `:ref :service` | The desired-state row this copy belongs to.                           |
| `:executor-id` | `:text`         | The pod — its fleet `:executor-id`, `"local"` on a single pod.        |
| `:host`        | `:text`         | Where the copy answers — the executor-id, loopback on a single pod.   |
| `:port`        | `:int` (null)   | The port the listener bound (from the handle's `:endpoint` metadata); nil ≡ not a listener (a cron loop still has a row — it heartbeats). |
| `:started-at`  | `:timestamptz`  |                                                                       |
| `:seen-at`     | `:timestamptz`  | Heartbeat. Older than `default-stale-after-ms` (45 s, three ticks) ⇒ presumed dead: consumers ignore it, and after ten windows any pod deletes it. |
| `:org-id`      | `:text` (null)  | The service's tenant, copied from its `:service` row when the copy starts (the reconciler writes with the platform handle). Org-scoped on the cloud: a tenant reads ITS running copies — the ⚙ popover's *Running copies* — while the `:service` row stays platform-only. |

### `:restart-policy` enum

`:always`, `:on-failure`, `:never`.

### `:cardinality` enum

How many pods run the service simultaneously:

| Value | Lock? | Use it for | If you get it wrong |
|-------|-------|-----------|---------------------|
| `:singleton` | `pg_try_advisory_lock(service-id, slot 0)` on the pod's dedicated lock connection; losers idle with a `::not-our-lock` placeholder | cron / `:schedule` loops, one-shot migrations, anything whose side-effects must happen once per tick | a `:per-pod` cron fires N times per tick, once per pod |
| `:per-pod` | none — every pod starts its own copy | listeners (`:http-server`), anything a load balancer fans traffic into | a `:singleton` listener means only ONE pod ever binds a port; every other pod fails its healthcheck and the LB sees a single backend no matter how many pods you run |
| `:pool` (`:pool-size N`) | races for the first free of **N** advisory-lock slots (keys `service-id+0 … +N-1`); holds it | a background worker that should be redundant / parallel across a **bounded** number of pods — not one, not all | too small an N under-provisions; too large just caps at the pod count |

`:pool` generalises `:singleton` (a pool of 1, slot 0). With ≥ N pods
exactly N run; with fewer pods, one copy each. The pool SIZE is fixed —
load-driven autoscaling of N is intentionally out of scope (it would need
a per-service load signal + a scaling controller; the request path scales
via cells + HPA instead — see docs/SCALING.md).

`app/package.edn` declares the seeded `:default → :web-server` service
as `:per-pod` for exactly this reason.

The running-atom entry records `:locked?` — whether *this* pod holds the
advisory lock — so stopping a service releases only locks it actually
took. A cardinality flip is picked up by the reconciler's config-drift
detector and stop+restarts the service (dropping the lock on the way to
`:per-pod`, racing for it on the way back).

Upgrade note: the seeder backfills `:cardinality` onto an existing row
whose value is nil, taking it from the package declaration. That is what
moves a pre-existing `:default` row off the singleton default. It only
touches nil, so a deliberately-set value survives, same as `:enabled?`.

### Per-branch services

`reconcile-once!` groups enabled rows by `:branch-id`, asks the
branch-router for each branch's `ExecutionContext` (built lazily
via `build-actual-entry!`), and starts the service against that
ctx. A typical workflow:

```clojure
;; dev:  port 9001 (sticky-local, doesn't merge to main)
{:name :dev-server  :parent :http-server
 :args {:handler :app-handler  :port 9001}}

;; main: port 8080 (also sticky-local)
{:name :prod-server :parent :http-server
 :args {:handler :app-handler  :port 8080}}
```

Two `:service` rows — `{:fn-id :dev-server  :branch-id dev}` and
`{:fn-id :prod-server :branch-id main}` — both run.

The merge's `:merge-post-commit!` step calls
`recon/restart-services-depending-on!` on the target, seeded by the
merged fn-ids — only the services whose closure the merge touched
restart — so cron loops pick up new fn-versions (HTTP servers re-
read their registry lazily, cron closes over the fn-graph at
spawn time). `delete-branch!` cascade-soft-disables services
scoped to the deleted branch before the row goes away.

Port / resource conflicts surface as OS-level failures —
`Address already in use` records `:start-failed-at` and shows up
in the running-atom for the UI badge.

## Reconciler

Lives in `graphden.services.reconciler`. Diff-driven, idempotent.

### Lifecycle

1. **Init** (`:exec/service-reconciler` integrant key):
   - Reset the production singleton `recon/running`.
   - Seed package-declared `:services` into the `:service` table
     (idempotent — see § Packages-based seeding below).
   - Read enabled `:service` rows from DB.
   - If any are enabled → `reconcile-once!` starts each.

2. **Reconcile** (`POST /api/services/reconcile` or programmatic
   `reconcile-once!`):
   - Read enabled rows.
   - Diff desired set (DB) vs running set (in-process atom). Stop
     extra entries, start missing ones with empty args (the fn is
     fully bound by definition).

3. **Halt** (`halt-key!`): drain `recon/running` via `stop-all!`.

### Running-atom shape

```clojure
{service-uuid → {:fn-id            :uuid
                 :restart-policy   :always | :on-failure | :never
                 :cardinality      :singleton | :per-pod | :pool
                 :pool-size        Int | nil (slot count; 1 for singleton)
                 :locked?          Bool
                 :pool-slot        Int | nil (which advisory-lock slot we hold)
                 :branch-id        :uuid (effective branch: the row's, or the
                                          router's default for nil-branch rows;
                                          absent only when no router is active)
                 :stopper          (fn []) | nil
                 :instance-id      :uuid (this copy's `:service-instance` row —
                                          heartbeat each tick, deleted on stop)
                 :started-at       Instant
                 :start-attempts   Int
                 :start-failed-at  Instant (set only when retries exhausted)}}
```

`:stopper` is whatever the fn returned (web-server-shape: a thunk
that stops the listener; other shapes are logged on stop but otherwise
ignored). `nil` means the start failed and retries were exhausted.

`:locked?` says whether THIS pod holds one of the service's advisory-lock
slots, and `:pool-slot` records WHICH slot (0 for a singleton), so stop
releases exactly the key it took and a post-reconnect re-assert re-takes
the right one. Only lock-gated (`:singleton` / `:pool`) services take a
slot; `:cardinality` + `:pool-size` are mirrored onto the entry so the
config-drift detector notices an admin flipping either.

A pod that couldn't get a slot stores the sentinel `::not-our-lock`
instead of a map. That placeholder is **transient**: the top of every
reconcile pass drops it, so the service is re-attempted each pass. This is
what makes the periodic tick heal a crashed owner — its slot auto-releases
(no NOTIFY), and a sibling re-acquires it on the next tick rather than
idling until a `:service` edit happens by.

## Supervisor

`start-service!` wraps `start-service-once!` with a bounded
exponential-backoff retry loop per `:restart-policy`:

| Policy        | Behaviour                                                  |
|---------------|------------------------------------------------------------|
| `:always`     | Retry on start exception up to `max-retries` (default 3) with backoff 1s → 2s → 4s. No runtime watcher yet, so this is currently equivalent to `:on-failure`. |
| `:on-failure` | Same as `:always` today. A future phase distinguishes "clean exit" vs "crash" once we have a runtime watcher. |
| `:never`      | Single attempt. On failure, record `:start-failed-at`, leave `:stopper` nil. |

After the retries within a single pass are exhausted, the reconciler
does **not** hold the entry as "running" forever. It records the
**transient `::start-failed`** sentinel and RELEASES the advisory slot,
so (a) a healthy sibling can immediately fail over and take the service,
and (b) the top of the *next* reconcile pass drops the sentinel and
**RE-ATTEMPTS** the start (one cheap retry-free attempt per tick). So a
transient cause — a port briefly taken, a file not yet present — self-heals
on the next pass with no operator action. Admin can still poll for
`:start-failed-at` to see which services are currently failing to start;
a persistent failure (bad `:fn-id`, permanently-taken port) is re-probed
each pass until the underlying issue is fixed.

Tests can override the retry behaviour via the optional `start-opts`
arg on `reconcile-once!`: `{:max-retries 0 :backoff-ms 0}` for
zero-time tests; production defaults apply when omitted.

## HTTP API

All endpoints require bearer-token auth.

### `POST /api/entities/service` (generic CRUD)

Form-encoded body. Create a service row.

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/x-www-form-urlencoded" \
     -d "fn-id=$FN_UUID&enabled?=true&restart-policy=always" \
     http://server/api/entities/service
```

`PUT /api/entities/service/:id` updates the row in place;
`DELETE /api/entities/service/:id` removes it.

**Rejection: start-blocking free args on target fn.** If `fn-id` points
at a fn with a free arg it needs to START (a direct unbound operand, or
one lifted through a data slot), the create is rejected with:

```html
<p class="error">Cannot make a :service for a fn that has free args:
  [:nums]. Create a derived fn-def that binds them, then
  declare a :service for the derived fn.</p>
```

The guard uses `:service-blocking-free-args`, the service-ability
projection of `:free-arg-slot-map`: it drops the fn's own top-level
CALLBACK-slot subtrees (an `:http-server` handler, a `:schedule` body),
because those free args are the callback's per-invocation concern —
supplied by the deferred invoker (per request / per tick), not needed
to start the listener/loop. So a whole-app listener like `web-server`
(whose ~45 free args all live below its `:handler` HOF slot) is
service-able, while a genuinely unstartable fn is still rejected. The
full free-arg surface stays visible to `/api/execute`'s arg form.

### `POST /api/services/reconcile`

Trigger reconciliation on demand — the admin's "apply changes"
button. (A periodic tick also reconciles every ~15s, so an
out-of-band change is picked up either way; this endpoint just makes
it immediate.)

```jsonc
{"ok": true,
 "reconcile": {"started": ["uuid", …],
               "stopped": ["uuid", …]}}
```

**Caveat: never call this endpoint to displace the very web-server
that serves it.** http-kit's stop interrupts in-flight request
threads, including the one running the reconcile call — the managed
service start fails mid-query and the port ends up unbound.
Workarounds:

- Restart the container so init-key picks up the new rows directly
- Trigger reconcile from a non-HTTP path (REPL, CLI, future
  supervisor daemon)
- Wait for the periodic reconcile tick (~15s) to apply it

For services that DON'T displace the API-serving web-server (a
metrics server on a different port, say), the endpoint works fine
synchronously.

## Already-running rejection

When `validate-execute` resolves a Run request to a fn-id that the
service registry already considers alive, the request is rejected
upfront with `:status :rejected`:

```jsonc
{"ok": false, "status": "rejected",
 "error": "Function is already running as a managed service. …",
 "error-data": {"reason": "already-running-as-service",
                "source": "service",
                "service-id": "uuid"}}
```

Prevents the foot-gun where clicking ▶ on `:web-server` in the editor
tries to re-bind its port.

## Endpoints — where a service answers

A service that listens somewhere *answers* somewhere, and the other
services want that address. The service fn IS the value:

1. `:http-server` returns its stopper with `{:endpoint {:port p}}`
   metadata — the port actually bound (so `:port 0` reports the
   OS-picked one). The handle's contract (a 0-arg stopper) is unchanged.
2. The reconciler, on the pod that started the copy, writes a
   `:service-instance` row — its own dialable host (the fleet
   `:executor-id`, a pod-FQDN in k8s; loopback on a single pod), the
   port, and a heartbeat it refreshes every tick — and deletes it on
   every stop path (`stop-and-forget!`, the lost-lock stop, `stop-all!`
   with a ctx at halt / CRaC). A `:pool` or `:per-pod` service has one
   row per copy. A crashed pod never deletes its row: the heartbeat goes
   stale, consumers stop picking it after one window (45 s), and any pod
   reaps it after ten — a scan each reconciler runs at most once per
   window, not on every graph write (§ Liveness).
3. A consumer names the producer through a **`:fn-ref` slot** —
   `:service-endpoint :service :orders-service`. `:fn-ref` is the
   identity primitive ([TYPES.md](TYPES.md#structural-types-records)):
   the impl receives the fn's *id*, the fn is never evaluated (binding a
   listener does not start a second one), its free args don't surface,
   and the edge is not a dependency — so two services may name each
   other ([CONSTRAINTS.md](CONSTRAINTS.md#1-no-dependency-cycle)).
   `graphden.services.endpoint/resolve-endpoint` picks a LIVE listener
   instance of the fn's enabled row on the caller's branch (a nil-branch
   row — package-seeded — serves every branch), this pod's own copy
   first (loopback beats a hop), then the freshest heartbeat; else it
   asks the addon-installed `resolver` seam (on the cloud
   an `:app-route`'s public origin — service-to-service traffic goes
   through the public domain like any outbound call, SSRF-guarded, no
   internal address to exempt), else throws `:service/not-running`.
4. `web/service` composes the call: `:service-url` → `:service-get` /
   `:service-post` (`:http-get` / `:http-post` with `:url` = origin +
   `:path`) → `:service-get-json` / `:service-post-json` (body parsed).
   Free args: `:service`, `:path`, plus http-client's `:headers` /
   `:auth-value` / `:timeout-ms` (and `:body` on POST). The caller's
   trace header rides along, so the producer records the request as an
   execution linked to the caller
   ([EXECUTION.md § Tracing across services](EXECUTION.md#tracing-across-services)).

The **contract** between two services lives in the graph too: a shared
namespace (`svc-a.api`) holds the path constants and the request /
response type-rows that BOTH sides reference — the producer's
`:get-route` + handler return type, the consumer's `:service-get` +
decoded result narrowed to the same shape. One edit moves both sides;
the type-checker catches a drift at write time. Tutorial:
[lesson 35](tutorial/35-services-talking-to-services.md).

## Liveness — a copy that died in place

Start-time failures were always caught (port in use, a throw in the
constructor). A copy that started and THEN died — the listener object
stopped, the daemon thread ended — used to sit in `running` as alive
forever. Now a handle may carry two more metadata keys, and the
reconciler's per-tick pass reads them:

| Key       | Set by                                   | Meaning                                             |
|-----------|------------------------------------------|-----------------------------------------------------|
| `:alive?` | `:http-server` (http-kit's listener status), `:future` (thread state) | 0-arg probe: is the copy still running? |
| `:exit`   | `:future`                                | atom — nil while running, `:done` after a clean return / interrupt, `:failed` after an uncaught throw |

Each `reconcile-once!` pass, before the diff, `check-liveness!` walks
this pod's running copies: a live one gets its heartbeat; a dead one is
stopped (lock released, instance row deleted) and then the row's
`:restart-policy` finally means what it says —

| Policy        | On a copy that died in place                                                  |
|---------------|-------------------------------------------------------------------------------|
| `:always`     | restarted in the same pass                                                    |
| `:on-failure` | restarted only when `:exit` is `:failed`; a clean exit is parked              |
| `:never`      | parked                                                                        |

*Parked* = an `::exited` placeholder in `running` that keeps the diff
quiet; toggling the row (`enabled?` off → on) runs it again. A handle
without `:alive?` (a nil stopper, a fire-and-forget) is trusted as
running, as before. Stale instance rows of a pod that CRASHED (no pass
ever ran there) are ignored by consumers after one window and deleted
by whichever pod ticks next after ten (each reconciler scans for
them at most once per window — reconcile also runs on every graph
write, and that path must not pay for the scan).

## Queues — asynchronous work between services

HTTP (§ Endpoints) is synchronous: the consumer waits, and a slow or
absent producer is the consumer's problem. A queue decouples them. It
is Postgres-backed — one table, the standard shape every PG job queue
uses (Oban, graphile-worker, River, pgmq): `FOR UPDATE SKIP LOCKED`
for the claim, a visibility timeout while a consumer holds a message,
bounded retries with a delay, a dead-letter state, and a `NOTIFY`
(`queue:publish:<name>`) that wakes a waiting consumer so it never
polls hot. No extension, no second service to run; RLS / org-scoping
apply as to any row. A broker (Kafka, NATS) stays possible as an
external package: the graph contract below doesn't name the backend.

### `:queue-message`

Non-versioned work rows (schema `graphden.schema.queue.schema`):

| Field | Type | Notes |
|---|---|---|
| `:queue` | `:text` (indexed) | The channel name. |
| `:payload` | `:jsonb` | Whatever was published — narrow it to the contract's type-row on both sides. |
| `:org-id` | `:text` (null) | Tenant owner (stamped by the org-scoped decorator on the cloud). |
| `:state` | `:text` | `pending` (takeable) or `dead` (retries exhausted, kept for inspection). A successful ack DELETES the row — the table holds work, not history. |
| `:attempts` | `:int` | Claims so far. |
| `:available-at` | `:timestamptz` | Due time (publish delay / retry delay). |
| `:locked-until` | `:timestamptz` (null) | The visibility lock of the current claim. |
| `:error` | `:text` (null) | The last handler's message, kept on retry and on dead. |
| `:trace-id`, `:parent-execution-id` | `:uuid` (null) | The publisher's trace and execution (`cr/*execution*` at publish time); the consumer's handler runs as a child of that execution (§ Tracing across services in [EXECUTION.md](EXECUTION.md#tracing-across-services)). Published outside a persisted run, the message opens a trace of its own (`:trace-id` fresh, no parent) — the handling is persisted either way. |

### The primitives (`storage/queue`)

| Base-fn | Does |
|---|---|
| `:queue-publish` `queue payload delay-ms` | insert + `NOTIFY`; returns the id |
| `:queue-take` `queue batch visibility-ms wait-ms` | claim up to `batch` due messages (`SKIP LOCKED`, `attempts+1`, lock for `visibility-ms`); when none is due, wait up to `wait-ms` for a publish and try once more |
| `:queue-ack` `message-id` | delete |
| `:queue-nack` `message-id error retry-ms max-attempts` | release for retry after `retry-ms`, or `dead` once `attempts ≥ max-attempts` |
| `:queue-extend` `message-id visibility-ms` | renew the claim's lock — the lease heartbeat of a handler that outlives its claim (true while the row is still pending) |
| `:queue-requeue` `message-id` | a dead letter back to `pending` (attempts 0, error cleared, consumers woken) — what Operate → Queues' *Requeue* calls |
| `:queue-stats` | one row per queue — `{:queue :pending :in-flight :dead}` — from ONE aggregate query, org-scoped like the entity read |
| `:queue-dead-letters` `limit` | the newest `limit` dead letters, org-scoped |

### The consumer template

`:queue-consumer` is a service like any other (`:future` → a loop →
take a batch → run `:handler` on each → ack on return, nack on a
throw). While the handler runs, `:extend` is called every
`:lease-every-ms` under `:with-heartbeat`, so a handler slower than
the visibility timeout keeps its claim instead of being re-delivered
mid-flight. The handler itself runs through `:call-traced`: with the
trace ids the publisher stamped on the message, its execution is
persisted as a child of the publisher's — the Run pane's *Downstream
calls* continue across the queue exactly as across HTTP. Its `:take` /
`:ack` / `:nack` / `:extend` are **fn-typed slots** (`:lease-every-ms`
a knob), so the backend is a binding: `:pg-queue-consumer` binds the
Postgres primitives above (batches of 10, 30 s visibility renewed every
10 s, 5 s wait; retry after 5 s, dead after 5 attempts); a broker
package binds its own `:take` / `:ack` / `:nack` to the same template
and keeps the template's no-op `:extend` (`:_queue-no-extend`) when it
has no leases. A consumer
is two bindings away:

```edn
{:name :orders-worker :parent :pg-queue-consumer
 :args {:queue "orders" :handler :handle-order}}
```

`:handle-order` sees the message under `message`
(`{id, queue, payload, attempts}`). To change the knobs, derive your
own take / nack (`{:parent :queue-take :args {…}}`) and bind them to
`:take` / `:nack` on the consumer. Make the worker a `:service`
(`:singleton` for a strict in-order-ish drain, `:pool` for parallel
workers — a claim is exclusive either way). The **contract** is the
message's type-row, referenced by the publisher's payload and the
handler's `message` narrowing, exactly like the HTTP contract.

**Operate → Queues** (`GET /partials/queues-panel`, `editor-queues.js`)
lists every queue with its pending / in-flight / dead counts
(`:queue-stats` — one aggregate query) and the newest 200 dead letters
(`:queue-dead-letters`) with *Requeue* (`:queue-requeue`) and *Delete* —
org-scoped on the cloud, so a tenant sees its own queues, and the same
cost on a queue of a million rows as on ten. Tutorial:
[lesson 36](tutorial/36-queues.md).

## Packages-based seeding

Packages contribute baseline `:service` rows through a
`:services [...]` field in their `package.edn`. Each entry is a map:

```edn
{:name :default                  ; required — seed name, used for id
 :fn-name :web-server             ; required — fn-name to resolve
 :enabled? true                   ; optional, default true
 :restart-policy :always          ; optional, default :always
 :description "..."}              ; optional, comment-only
```

The reconciler invokes `seed-package-services!` at integrant init.
For each entry:

1. Computes a deterministic service-id via
   `ids/seeded-service-id package-name name` so re-runs land on the
   same row.
2. Resolves `:fn-name` against the `:fn` table; if not found, logs
   and skips (admin can re-create the fn and the next boot picks it
   up).
3. If the row already exists, leaves it alone — an admin's
   `:enabled?` toggle survives restarts.
4. Otherwise creates the row with the seed defaults.

The seeded rows go through the regular reconcile path — no separate
fallback code, no separate stopper handle. Each seed becomes a
fully-supervised service.

`app/package.edn` ships one seed by default — `:default → :web-server`.
Other packages can add their own; the loader aggregates seeds across
all loaded packages.

## Roadmap

| Step | What |
|------|------|
| Done | `:service` schema, reconciler, integrant, generic CRUD via /api/entities/service, supervisor for startup failures, packages-based seeding, already-running rejection, validation that target fn has zero free args |
| Done | Multi-pod: per-pod reconcilers, PG advisory-lock ownership for `:singleton` services, `:cardinality` so `:per-pod` listeners run everywhere, `service:*` NOTIFY so siblings reconcile within ~1s, lock auto-release on pod crash. No `:owner-pod-id` column — ownership is implicit in who holds the lock. |
| Done | Periodic reconcile tick (`:exec/service-reconciler`, ~15s) — level-triggered convergence: re-takes a `:singleton` lock after the holder crashes (no NOTIFY is emitted), picks up out-of-band DB edits, reconverges transient start failures. Retry-free under `reconcile-monitor` so a failing start never blocks the listener. The tick actually heals a crash because the reconciler now drops `::not-our-lock` placeholders at the top of every pass (they were sticky before, so an idle pod never re-attempted the freed lock). |
| Done | `:pool` cardinality (`:pool-size N`) — a service runs on up to N pods, coordinated by N advisory-lock slots (generalises `:singleton` = slot 0). Fixed `:pool-size` only; load-driven autoscaling of N is out of scope (the request path scales via cells + HPA). |
| Done | Startup steps — `:migration` / `:migrate` templates in `storage/pg` (§ Startup steps): journaled, advisory-locked, one-transaction schema migrations sequenced ahead of the listener with `:do`. |
| Next | `:service-schedule` 1-to-many for cron/interval triggers; UI Services panel (row-actions "Make service" + sidebar "Only services" filter) |
| Done | Advisory-lock connection-drop reconnect + re-acquire. The lock connection is held behind a reconnecting holder; every reconcile pass runs `advisory-lock/ensure-live!`, and on a reconnect `reassert-lock-ownership!` re-takes each `:singleton` this pod was running (stopping any a sibling stole during the outage). Closes the "two pods double-run one service until the next reconcile" window. |
| Done | Cross-pod cancel routing for `:fn-execution` — `execution:cancel:<id>` NOTIFY fan-out; see [EXECUTION.md](EXECUTION.md). |
| Done | Endpoints — one `:service-instance` row per running copy (host + bound port from the handle's `:endpoint` metadata, heartbeat each tick), deleted on stop; `:service-endpoint` (web/service) resolves a service fn named through a `:fn-ref` slot to a LIVE copy, with the addon `resolver` seam for cloud app-routes (§ Endpoints). |
| Done | Liveness — handles carry `:alive?` / `:exit`; the per-tick pass restarts a copy that died in place per `:restart-policy` (`:always` any exit, `:on-failure` a throw, `:never` parks), heartbeats live copies and reaps the rows a crashed pod left (§ Liveness). |
| Done | Queues — `:queue-message` (Postgres, `SKIP LOCKED` + `NOTIFY` wake), the four primitives, and the backend-swappable `:queue-consumer` / `:pg-queue-consumer` templates (§ Queues). |
| Future | Pluggable supervisor strategies |

## Code locations

- Schema: `src/graphden/schema/services/schema.clj`
- Reconciler: `src/graphden/services/reconciler.clj`
- Endpoint resolution: `src/graphden/services/endpoint.clj` (+ the `:service-endpoint` base-fn and call templates in `resources/packages/web/service/`)
- Queue: `src/graphden/schema/queue/schema.clj`, `resources/packages/storage/queue/` (primitives + the consumer templates)
- Integrant: `src/graphden/system/core.clj` → `:exec/service-reconciler`
- Form parser: graph-native — `resources/packages/app/execution/fns.edn`
- Create-service guards: `resources/packages/web/crud/fns.edn` → `:_create-service-free-args-rej` (fn has start-blocking free args) and `:_create-service-no-process-rej` (fn doesn't declare the `:process` effect)
- Already-running / displacement check: graph-native — `resources/packages/app/execution/fns.edn`
- HTTP endpoint: `resources/packages/app/execution/{fns.edn,impls.clj}` → `:_reconcile-services`
- Route: `resources/packages/app/routes/fns.edn` → `:api-services-reconcile`
- Tests: `test/graphden/services/reconciler_test.clj`,
         `test/graphden/services/service_endpoint_e2e_test.clj` (two services over a real socket),
         `test/graphden/packages/web/service_test.clj`, `test/graphden/executor/fn_ref_test.clj`,
         `test/graphden/packages/storage/queue_test.clj`, `test/graphden/services/queue_consumer_e2e_test.clj`,
         `test/graphden/packages/app/execution_routes_test.clj`,
         `test/graphden/crud/fn_execution_test.clj` (already-running cases),
         `test/graphden/crud/entities_test.clj` (parser cases)

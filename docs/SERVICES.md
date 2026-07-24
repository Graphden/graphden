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

## Storage schema

One non-versioned entity + one enum (admin mutates in place; the
audit trail for what actually ran lives in `:fn-execution` rows the
services spawn).

### `:service`

| Field             | Type              | Notes                                                              |
|-------------------|-------------------|--------------------------------------------------------------------|
| `:id`             | `:uuid`           | Returned to clients as `service-id`.                              |
| `:fn-id`          | `:ref :fn`        | **Logical** fn id — service tracks the current graph; editing the fn picks up at next restart. (Compare `:fn-execution.fn-version-id` which is a frozen snapshot.) Must point at a fn with no start-blocking free args (§ Guard). |
| `:enabled?`       | `:bool`           | Reconciler only starts enabled rows. Toggle this + reconcile to stop a service without deleting it. |
| `:restart-policy` | `:restart-policy` | `:always` / `:on-failure` / `:never` — see § Supervisor below.    |
| `:cardinality`    | `:cardinality`    | `:singleton` / `:per-pod` / `:pool` — how many pods run it at once; see § Cardinality. Nullable; nil ≡ `:singleton` (rows that pre-date the field). |
| `:pool-size`      | `:int`            | Pod count for `:cardinality :pool` (ignored otherwise). Nullable — a `:pool` row with nil/non-positive size degrades to a singleton. |
| `:branch-id`      | `:ref :branch`    | Per-branch scope. Reconciler routes the start through `branch-router/ctx-for branch-id`, so the same `:fn-id` can run with branch-specific bindings on dev + prod simultaneously. Nullable — nil is normalized to the router's default branch at reconcile time (`effective-branch-id`), so a legacy nil-branch row behaves exactly like an explicit default-branch row, including the post-merge `restart-services-on-branch!` pass. Without a router (tests) nil falls back to the reconciler's base ctx. The editor's ⚙ popover picker defaults to the editor's current branch on create. |

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

```
;; dev:  port 9001 (sticky-local, doesn't merge to main)
{:name :dev-server  :parent :http-server
 :args {:handler :app-handler  :port 9001}}

;; main: port 8080 (also sticky-local)
{:name :prod-server :parent :http-server
 :args {:handler :app-handler  :port 8080}}
```

Two `:service` rows — `{:fn-id :dev-server  :branch-id dev}` and
`{:fn-id :prod-server :branch-id main}` — both run.

`merge-branch!` calls `recon/restart-services-on-branch!` on the
target so cron loops pick up new fn-versions (HTTP servers re-
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

After give-up, the entry stays in `running` so the next reconcile
pass doesn't busy-loop retrying. Admin can poll for `:start-failed-at`
to see which services need attention. Manual recovery: disable the
row, reconcile, fix the underlying issue, enable, reconcile again.

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
| Next | `:service-schedule` 1-to-many for cron/interval triggers; UI Services panel (row-actions "Make service" + sidebar "Only services" filter) |
| Done | Advisory-lock connection-drop reconnect + re-acquire. The lock connection is held behind a reconnecting holder; every reconcile pass runs `advisory-lock/ensure-live!`, and on a reconnect `reassert-lock-ownership!` re-takes each `:singleton` this pod was running (stopping any a sibling stole during the outage). Closes the "two pods double-run one service until the next reconcile" window. |
| Done | Cross-pod cancel routing for `:fn-execution` — `execution:cancel:<id>` NOTIFY fan-out; see [EXECUTION.md](EXECUTION.md). |
| Future | Healthcheck-based runtime crash detection (lets `:always` honor "restart on clean exit"); pluggable supervisor strategies |

## Code locations

- Schema: `src/graphden/schema/services/schema.clj`
- Reconciler: `src/graphden/services/reconciler.clj`
- Integrant: `src/graphden/system/core.clj` → `:exec/service-reconciler`
- Form parser: graph-native — `resources/packages/app/execution/fns.edn`
- Free-args rejection (`:service` needs the `:process` effect): `resources/packages/web/crud/fns.edn` → `:_create-service-no-process-rej`
- Already-running / displacement check: graph-native — `resources/packages/app/execution/fns.edn`
- HTTP endpoint: `resources/packages/app/execution/{fns.edn,impls.clj}` → `:_reconcile-services`
- Route: `resources/packages/app/routes/fns.edn` → `:api-services-reconcile`
- Tests: `test/graphden/services/reconciler_test.clj`,
         `test/graphden/packages/app/execution_routes_test.clj`,
         `test/graphden/crud/fn_execution_test.clj` (already-running cases),
         `test/graphden/crud/entities_test.clj` (parser cases)

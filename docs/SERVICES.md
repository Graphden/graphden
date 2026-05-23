# Service Registry

Declarative long-running services backed by the `:service` entity. An
admin declares "keep this fn running" in the DB; the reconciler turns
the rows into actual futures, supervises startup failures, and stops
them on shutdown.

This is **Phase 1 single-pod**. Cron schedules and multi-pod
coordination come in later phases (see § Roadmap at the bottom).

## Why services?

Before this feature, exactly one long-running fn was supported via the
package `:startup-fn` field — typically `:web-server` baked into
`app/package.edn`. You couldn't:

- Run a second long-running fn alongside (e.g. metrics server on a
  different port)
- Declaratively manage what's alive — restart, disable, change config
- Get protection against accidentally running an already-running fn
  via the executor (Run ▶ on `:web-server` from the editor used to
  crash with `Address already in use`)

The `:service` registry fixes all three.

## The model: a service IS a no-arg fn

A `:service` row is just a marker on a fn that says "keep this alive".
The fn it points at MUST have **zero free arguments** — every slot
bound via fn-defs / bindings. The reconciler invokes the fn with
empty args.

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
| `:fn-id`          | `:ref :fn`        | **Logical** fn id — service tracks the current graph; editing the fn picks up at next restart. (Compare `:fn-execution.fn-version-id` which is a frozen snapshot.) Must point at a fn with zero free args. |
| `:enabled?`       | `:bool`           | Reconciler only starts enabled rows. Toggle this + reconcile to stop a service without deleting it. |
| `:restart-policy` | `:restart-policy` | `:always` / `:on-failure` / `:never` — see § Supervisor below.    |

### `:restart-policy` enum

`:always`, `:on-failure`, `:never`.

## Reconciler

Lives in `graphden.services.reconciler`. Diff-driven, idempotent.

### Lifecycle

1. **Init** (`:exec/service-reconciler` integrant key):
   - Reset the production singletons (`recon/running`,
     `recon/legacy-handle`).
   - Read enabled `:service` rows from DB.
   - If any exist → `reconcile-once!` starts each.
   - If none → fall back to `package.edn`'s `:startup-fn`
     (single-shot, no supervisor) and stash the
     `{:fn-id :stopper}` pair in `legacy-handle`.

2. **Reconcile** (`POST /api/services/reconcile` or programmatic
   `reconcile-once!`):
   - Read enabled rows.
   - **Displacement step**: if any enabled service's `:fn-id` matches
     the legacy fallback's `:fn-id`, stop the fallback first (frees
     its port) and clear `legacy-handle`. Reflected in the summary as
     `:legacy-displaced? true`.
   - Diff desired set (DB) vs running set (in-process atom). Stop
     extra entries, start missing ones with empty args (the fn is
     fully bound by definition).

3. **Halt** (`halt-key!`): drain `recon/running` via `stop-all!`,
   stop the legacy fallback if any, clear `legacy-handle`.

### Running-atom shape

```clojure
{service-uuid → {:fn-id            :uuid
                 :restart-policy   :always | :on-failure | :never
                 :stopper          (fn []) | nil
                 :started-at       Instant
                 :start-attempts   Int
                 :start-failed-at  Instant (set only when retries exhausted)}}
```

`:stopper` is whatever the fn returned (web-server-shape: a thunk
that stops the listener; other shapes are logged on stop but otherwise
ignored). `nil` means the start failed and retries were exhausted.

## Supervisor

`start-service!` wraps `start-service-once!` with a bounded
exponential-backoff retry loop per `:restart-policy`:

| Policy        | Behaviour                                                  |
|---------------|------------------------------------------------------------|
| `:always`     | Retry on start exception up to `max-retries` (default 3) with backoff 1s → 2s → 4s. Phase 1 has no runtime watcher, so this is currently equivalent to `:on-failure`. |
| `:on-failure` | Same as `:always` in Phase 1. Future phase distinguishes when "clean exit" vs "crash" can be detected. |
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

**Rejection: free args on target fn.** If `fn-id` points at a fn
that still has free arguments, the create is rejected with:

```html
<p class="error">Cannot make a :service for a fn that has free args:
  [:port :handler]. Create a derived fn-def that binds them, then
  declare a :service for the derived fn.</p>
```

### `POST /api/services/reconcile`

Trigger reconciliation. Without periodic poll (Phase 1), this is the
admin's "apply changes" button.

```jsonc
{"ok": true,
 "reconcile": {"started": ["uuid", …],
               "stopped": ["uuid", …],
               "legacy-displaced?": false}}
```

**Phase 1 caveat: never call this endpoint to displace the very
web-server that serves it.** http-kit's stop interrupts in-flight
request threads, including the one running the reconcile call — the
managed service start fails mid-query and the port ends up unbound.
Workarounds:

- Restart the container so init-key picks up the new rows directly
- Trigger reconcile from a non-HTTP path (REPL, CLI, future supervisor
  daemon)
- Wait for Phase 2's periodic poll

For services that DON'T displace the API-serving web-server (a
metrics server on a different port, say), the endpoint works fine
synchronously.

## Already-running rejection

When `validate-execute` resolves a Run request to a fn-id that the
service registry already considers alive, the request is rejected
upfront with `:status :rejected`:

```jsonc
// from :service row
{"ok": false, "status": "rejected",
 "error": "Function is already running as a managed service. …",
 "error-data": {"reason": "already-running-as-service",
                "source": "service",
                "service-id": "uuid"}}

// from legacy fallback (no row, only the init-key fallback)
{"ok": false, "status": "rejected",
 "error": "Function is already running as the boot fallback service. …",
 "error-data": {"reason": "already-running-as-service",
                "source": "legacy-fallback"}}
```

Prevents the foot-gun where clicking ▶ on `:web-server` in the editor
tries to re-bind its port.

## Legacy fallback (Phase 1 stopgap)

When no `:service` rows exist, the reconciler honors the package's
`:startup-fn` (typically `:web-server`) as a single-shot fallback so a
freshly-deployed pod still serves HTTP without admin setup.

The fallback is invisible to subsequent reconcile passes (lives in
`recon/legacy-handle`, NOT in the diff'd `running` atom). It IS
displaced when an enabled `:service` with a matching `:fn-id`
appears — see § Reconciler above.

The whole code path retires when Phase 2 introduces a packages-based
service-seeding mechanism. Until then, keep `:startup-fn` in
`package.edn`.

## Roadmap

| Phase | What |
|-------|------|
| 1 (this doc) | `:service` schema, reconciler, integrant, generic CRUD via /api/entities/service, supervisor for startup failures, legacy displacement, already-running rejection, validation that target fn has zero free args |
| 2 | `:service-schedule` 1-to-many for cron/interval triggers; periodic reconcile poll (picks up out-of-band DB edits); UI Services panel (row-actions "Make service" + sidebar "Only services" filter) |
| 3 | Multi-pod: `:owner-pod-id`, PG advisory-lock leader election for cron, cross-pod cancel routing |
| Future | Healthcheck-based runtime crash detection (lets `:always` honor "restart on clean exit"); pluggable supervisor strategies |

## Code locations

- Schema: `src/graphden/schema/services/schema.clj`
- Reconciler: `src/graphden/services/reconciler.clj`
- Integrant: `src/graphden/system/core.clj` → `:exec/service-reconciler`
- Form parser: `src/graphden/crud/entities.clj` → `parse-service-from-form`
- Free-args rejection: `src/graphden/crud/entities.clj` → `validate-create` (service branch)
- Already-running check: `src/graphden/crud/fn_execution.clj` → `already-running-as-service?`
- HTTP endpoint: `resources/packages/app/execution/{fns.edn,impls.clj}` → `:_reconcile-services`
- Route: `resources/packages/app/routes/fns.edn` → `:api-services-reconcile`
- Tests: `test/graphden/services/reconciler_test.clj`,
         `test/graphden/packages/app/execution_routes_test.clj`,
         `test/graphden/crud/fn_execution_test.clj` (already-running cases),
         `test/graphden/crud/entities_test.clj` (parser cases)

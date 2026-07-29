# Lesson 10 — Services: long-running fns supervised by graphden

**Goal**: by the end of this lesson you can mark a fn as a
service, see graphden start it automatically, run two versions
side-by-side on different branches, and reason about the
restart-policy + branch scoping.

**Concepts introduced**: `service`, `reconciler`, `restart-
policy`, `:enabled?`, `:process` effect, `:service.branch-id`,
`:service.cardinality`, service vs `execute`, and — on a
multi-tenant deployment — services as the **dedicated tier**
(own cgroup-limited pod, effect-sandboxed, `/api/orgs/services*`).

## Service vs execute

Two ways to call a fn in graphden:

| Action | Lifetime | Where it happens |
|---|---|---|
| **execute** (`▶` button, `/api/execute`) | One-shot. Returns a result and stops. | The HTTP request thread, with cancellation + TTL. |
| **service** (`⚙` button, `/api/entities/service`) | Forever. Restarted by graphden if it crashes. | A daemon thread managed by the reconciler. |

A service is a desired-state row that says "keep THIS fn
running". The fn must have zero free arguments (every slot
bound) — services don't pass per-call inputs. Anything you'd
normally pass as an argument has to be hard-coded as a binding
on a derived fn-def first.

## What makes a fn service-eligible

The fn must declare the `:process` effect somewhere in its
ancestor chain. `:process` means "spawns supervised background
work". The seeded base-fns that declare it:

| Base-fn | What `:process` work |
|---|---|
| `:http-server` | Owns a network listener until stopped |
| `:schedule` | Runs a cron loop until interrupted |
| `:future` | Spawns a daemon thread (used by both above) |

If you try to create a service for a fn whose ancestor chain
doesn't have `:process`, the editor's create-guard rejects:
"`:current-time-ms` is not service-eligible — neither it nor any
ancestor declares the `:process` effect."

## Try it: one service

1. Find the editor's own server `:web-server` (parented from
   `:http-server`, port 8080). The `⚙` button on its row-actions
   popover is enabled.
2. Click `⚙`. The popover shows:
   - **Branch** picker (default = your current branch)
   - **Enabled** checkbox (default = on)
   - **Restart policy**: `:always` / `:on-failure` / `:never`
3. Click `Create & reconcile`. The badge on the fn-card turns
   `running` once the reconciler starts it.

The reconciler runs on a NOTIFY callback — every `:service`
write fires `service:write:<id>` on the `graphden_events`
channel, the in-process callback diffs enabled-rows vs the
running-atom, starts the missing ones, stops the deleted ones.

## Building your own service-eligible fn-def

`:web-server` is a pre-built example; let's walk a from-scratch
recipe. We'll write the smallest possible service-eligible
fn-def — a future-parented thunk that just spawns a no-op
daemon thread. Useful as a sanity probe; the structure
generalises to real services (an HTTP server, a cron loop, a
pg-listen consumer) by swapping the bound body.

Two fn-defs, one parent each:

```edn
;; Step 1 — a thunk. :const returns its bound :value as-is;
;; with :value bound, the thunk has zero free args and
;; statically returns :text.
{:name :my-tick
 :parent :const
 :args  {:value "tick"}}

;; Step 2 — a service-eligible probe. :future's :body slot is
;; [:fn {} :any] — a 0-arg callable returning anything. Binding
;; it to :my-tick is accepted by the type-checker because the
;; ref's static signature [:fn {} :text] is a subtype of the
;; slot (covariant return: :text ⊆ :any). The runtime hof-wraps
;; :my-tick as the daemon's body.
{:name :my-probe
 :parent :future
 :args  {:body :my-tick}}
```

`:my-probe` now has zero free args (every slot in the chain is
bound) AND the `:process` effect (inherited from `:future`).
The `⚙` button in its row-actions popover is enabled. Click it → popover
says "Make service: :my-probe" + "Create & reconcile". The
reconciler starts a daemon thread that calls `:my-tick` once
and exits; with `:restart-policy :always`, it respawns. With
`:never`, it runs once and the badge flips to `disabled`.

This is the minimum reproducible service. Real services swap
the body for a long-lived loop — `:loop-until-interrupted`
binds `:body` to its own step fn that runs forever until the
parent `:future`'s stopper interrupts.

### Common bind-failures

The type-checker enforces the `[:fn {} :any]` slot shape on
`:body`. Two binds that look reasonable but get rejected:

- **Bind to a base-fn directly**:
  `{:args {:body :current-time-ms}}` — also accepted (`:int`
  ⊆ `:any` via covariant return), but `:current-time-ms` has
  the `:time` effect and bare `:future` doesn't expect to
  capture it. The probe becomes a one-shot clock read in a
  daemon — fine for a smoke test, surprising for a service.

- **Bind to a literal**:
  `{:args {:body "tick"}}` — rejected at sync time. A literal
  text isn't a callable; you can't invoke `"tick"` as a thunk.
  The hint suggests "bind a fn-ref or an inline `{:parent
  …}`". Use the `:my-tick` indirection or write an inline
  `{:parent :const :args {:value "tick"}}` directly inside
  `:body`.

### Restart policy

| Policy | When graphden restarts the fn |
|---|---|
| `:always` | Any exit — crash OR clean return |
| `:on-failure` | Only uncaught exceptions |
| `:never` | Single-shot. Log on exit, move on. |

In Phase 1 there's no runtime liveness check, so `:always`
≡ `:on-failure` in practice — both kick in only on startup
exception (e.g. port-in-use). A future phase will add a watchdog.

### Cardinality — how many pods run it

Restart policy answers *when* to start the fn again. Cardinality
answers a different question: **when several executor pods share
one database, how many of them run this service?**

| Value | What happens |
|---|---|
| `:singleton` | Exactly one pod, cluster-wide. Each pod tries `pg_try_advisory_lock` on the service id; the loser idles. |
| `:per-pod` | Every pod runs its own copy. No lock. |
| `:pool` (`:pool-size N`) | Up to **N** pods run it — exactly N when the fleet has ≥ N pods, one copy each when fewer. |

The two ship-today service shapes want opposite answers, and you
can't infer it from the fn:

- A `:schedule` cron loop must be `:singleton`. Run it everywhere
  and every tick fires once **per pod** — three pods, three
  emails.
- An `:http-server` must be `:per-pod`. Each pod has its own
  network namespace and its own port to bind. Make it a
  `:singleton` and only the lock-winner ever listens; the other
  pods answer nothing, fail their healthcheck, and your load
  balancer sees one backend no matter how many pods you started.

`:pool` covers the middle: a background worker you want **redundant
or parallel across a bounded number of pods** — not one, not all.
It generalises `:singleton` (a pool of 1): instead of racing for a
single lock, each pod races for the first free of **N** slots
(`pg_try_advisory_lock` on `service-id + 0 … N-1`) and holds it. If a
holder crashes, its slot frees and another pod takes it on the next
reconcile tick. The size is fixed — set `:pool-size` to the number
of pods you want; graphden does **not** grow or shrink it by load
(that's the request-serving path's job, not a service's).

That's why the editor's own `:web-server` is declared `:per-pod`
in `app/package.edn`:

```edn
:services [{:name :default
            :fn-name :web-server
            :enabled? true
            :restart-policy :always
            :cardinality :per-pod}]
```

A row written before this field existed has `cardinality = NULL`,
which reads as `:singleton` — the old behaviour, unchanged. On
boot the package seeder backfills NULL from the package
declaration, so upgrading moves `:web-server` to `:per-pod` for
you. It only touches NULL: a value you set on purpose survives,
same as `:enabled?`.

Single-pod deployments are unaffected either way — with no
contender, every lock attempt succeeds.

### Try it (cardinality edition)

The `⚙` popover has a **Cardinality** control — three radios,
`singleton` / `per-pod` / `pool`, right under Restart policy, plus a
`pool-size` number input (used only when you pick `pool`). It
pre-selects the row's current value (a new service starts at
`singleton`, since a nil column reads that way). Pick one and hit
`Save & reconcile`.

Changing cardinality is a **config drift**: the reconciler notices
the running entry no longer matches the row, stops the service, and
starts it again under the new rule. You can watch the advisory lock
appear and disappear:

```sql
select count(*) from pg_locks where locktype = 'advisory';
-- singleton → 1   (this pod owns the service)
-- per-pod   → 0   (nobody locks; every pod just runs it)
```

> ⚠️ One sharp edge: the editor is itself served by the
> `:web-server` service. Flipping *its* cardinality restarts *its*
> listener — the port drops for a moment and the page you're on
> briefly can't reach the server before it comes back. Expected, but
> don't do it to a production editor mid-session for fun. And flip it
> back to `per-pod` when you're done experimenting — a `:singleton`
> web-server is exactly the misconfiguration described above.

Prefer the API? The same fields go through generic CRUD:

```bash
curl -X PUT "$BASE/api/entities/service/$SERVICE_ID" \
  -H "Authorization: Bearer $AUTH_TOKEN" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data "fn-id=$FN_ID&enabled?=true&restart-policy=always&cardinality=singleton"
```

## Per-branch services

`:service.branch-id` is a ref to a branch row. The reconciler
groups services by branch, asks `branch-router/ctx-for` for each
branch's `ExecutionContext`, and starts the service against
THAT ctx. So **the same fn can run with branch-specific bindings
on dev and prod at the same time**.

Worked example:

```edn
;; on `main`:
{:name :prod-server :parent :http-server
 :args {:handler :app-handler :port 8080}}

;; on `dev` (after forking from main):
{:name :dev-server  :parent :http-server
 :args {:handler :app-handler :port 9001}}
```

Both `:http-server`, both branch-local (so they don't
cross-merge — see lesson 08). Two `:service` rows:

```
{:fn-id :prod-server  :branch-id main :enabled? true}
{:fn-id :dev-server   :branch-id dev  :enabled? true}
```

The reconciler starts both. Port 8080 is the production server
on `main`'s graph, port 9001 is the development server on
`dev`'s graph. Iterating on `:app-handler` on `dev` immediately
affects port 9001 without touching port 8080.

### Try it (per-branch edition)

1. Pre-req: complete the per-branch web-server walk-through in
   lesson 08. You should have `:dev-server` on `feat-dev-server`
   parented from `:http-server` with `:port 9001`.
2. Stay on `feat-dev-server`. Click `⚙` on `:dev-server`. The
   branch picker defaults to `feat-dev-server`. Hit
   `Create & reconcile`.
3. `curl http://localhost:9001/version` — runs against your dev
   graph.
4. `curl http://localhost:8080/version` — still runs against
   main (you didn't touch it).
5. On a service for the same fn but `branch-id = main`, the
   same fn-id with a different per-branch binding produces a
   different service instance. They co-exist.

Port conflicts (two branches binding 8080) surface as OS-level
`Address already in use` — the loser records `:start-failed-at`
and the editor shows the `failed` badge. Pick a different port
in your dev derivative.

## What happens when you merge or delete

- **Merge** into a target branch: graphden calls
  `recon/restart-services-on-branch!` so cron loops (which sit
  in closed-over fn-graphs) pick up the new versions. HTTP
  servers re-read the registry lazily on the next request.
- **Delete branch**: services scoped to that branch are
  soft-disabled (`:enabled? false`) BEFORE the branch row
  disappears, so the reconciler stops them on the next pass —
  releasing the advisory lock of any `:singleton` among them.
  (A `:per-pod` service never took one.)

## Inspecting state

```
GET /api/services
→ {:ok true :services [{:id ... :fn-id ... :fn-name "web-server"
                         :enabled? true :restart-policy "always"
                         :cardinality "per-pod"
                         :branch-id ... :running {...}}]}
```

The `:running` block carries the in-process atom snapshot:
`:stopper-set?` (true ⇒ running), `:started-at`,
`:start-failed-at`, `:start-attempts`, `:branch-id`.

## Services in the cloud (multi-tenant, dedicated tier)

Everything above assumes you own the deployment — your fns, your pods, one
graph. On a **multi-tenant** graphden (many orgs sharing one platform) services
work differently, because a persistent service runs *your* code continuously and
the platform can't let one tenant's runaway loop starve everyone else.

The rule that makes it safe: a persistent tenant service is only offered on a
**dedicated** runtime.

- **The free / network tiers are a full FaaS *without* services.** You compose
  fns, deploy a live app at `your-org.graphden.app`, and execute on demand — but
  a `:service` is off-limits. The `⚙` popover shows an *upgrade* note, and the
  API answers `403` with `:reason :service/tier-required`. (Under the hood
  `:service` is a platform-managed entity a shared tenant can't write directly.)
- **Services are the `dedicated` tier.** A dedicated org runs on its **own** pod
  set with its own CPU + memory limits, so a persistent service is bounded by
  that pod's cgroup. Two boundaries, not one: the **effect gate** limits *what*
  the service may do (its plan's effects), the **cgroup** limits *how much* CPU /
  memory it burns. That is the honest reason services are the paid line — they
  cost a dedicated runtime. The dedicated plan grants `:process` (a service
  spawns a supervised thread) and `:network` on top of the safe defaults;
  `:raw-sql` stays denied even here, because the dedicated pod shares the
  platform's Postgres.

### What you do (dedicated tier)

The `⚙` button works the same, but in tenant mode the popover is a **simpler
form** — just **Enabled** + **Restart policy**. There's no cardinality control:
your services run on your own single dedicated pod, so the "how many pods" and
advisory-lock questions above don't arise. Create / edit / delete route to your
org's own endpoints, the row is stamped with your org id, and the reconciler
starts it **only** on your dedicated pod — never on a shared one.

Your service runs **sandboxed to your plan's effects**, and that gate now
follows it into the background thread it spawns. The dedicated plan grants the
safe defaults plus `:process` and `:network`; a service that reaches for an
effect it *doesn't* grant — `:raw-sql` (the shared platform Postgres), or the
host-level `:io` / `:env` — throws `:execution/forbidden-effect` in its own
worker and fails to start, the same gate a one-shot execute runs under.

```bash
# create — dedicated tier only; 403 :service/tier-required otherwise
curl -X POST "$BASE/api/orgs/services/create" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data "fn-id=$FN_ID&enabled?=true&restart-policy=always"

# list YOUR org's services (only yours — never another tenant's or the platform's)
curl "$BASE/api/orgs/services" -H "Authorization: Bearer $TOKEN"

# update / delete carry the service id in the body
curl -X POST "$BASE/api/orgs/services/delete" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data "id=$SERVICE_ID"
```

### One current limitation

The tenant list shows a service's **desired** state (enabled?, restart-policy)
but not its live **run** status — the reconciler's running / failed signal lives
on your dedicated pod, not on the platform endpoint that serves the list, so the
editor badge reads *configured* / *disabled*, not *running* / *failed*, for now.

Operators provisioning a dedicated tenant (the pod set, the shard, the limits):
see [docs/FLEET_DEPLOY.md § Dedicated tenant shard](../FLEET_DEPLOY.md).

## What we glossed over

- The rest of the multi-pod story — how a fn edit on one pod
  invalidates the others' compiled registries, and how a
  `:singleton` survives the pod that owned it crashing — see
  [docs/SCALING.md](../SCALING.md).
- Closure-capture and why cron loops need an explicit restart
  after merge — see [docs/CLOSURE_CAPTURE.md](../CLOSURE_CAPTURE.md).
- The package-declared seed services (web-server is one) — see
  [docs/SERVICES.md § Packages-based seeding](../SERVICES.md).

## Next

Lesson 11 — Packages ([already written](11-packages.md))

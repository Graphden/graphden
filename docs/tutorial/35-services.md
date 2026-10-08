# Lesson 35 — HTTP handlers and persistent services

**Goal:** publish a typed response handler at a real public URL, read its
response over HTTP, and revoke the URL. Then distinguish this finite handler
from a persistent service on a self-hosted or dedicated executor.

The interactive lesson needs an authenticated owner and a configured public
HTTP origin. It is available to ordinary cloud users; it does not require a
service allowance. A self-hosted installation without authentication must
configure accounts or a static token before publishing.

## Three different lifetimes

| Action | Lifetime | Execution |
|---|---|---|
| **Run** | One call with temporary arguments | Returns one result. |
| **Publish HTTP** | Public URL for 30 minutes | Each request invokes the handler once. |
| **Service settings** | Desired state until disabled/deleted | The reconciler starts and supervises a persistent listener or job. |

Publication does not run the graph at creation time or start a background
thread. The platform serves its existing HTTPS origin, or an isolated apps
domain, so you choose no port and install no certificate for the lesson.

## Try it: publish, request, stop

1. Find `text-ok-response` in `web.response`. Use **⋯ → Extend** to create
   `tutorial-http-answer`.
2. On the child, bind `:body` to the text `hello HTTP`. The status and
   `text/plain` header are already inherited.
3. Choose **⋯ → HTTP**, then **Publish for 30 minutes**. The handler may have
   an unbound `request`; its other inputs must be bound. Publication checks
   the handler's types without invoking it.
4. Choose **Open public URL**. Read `hello HTTP` in the new browser tab. This
   is an actual HTTP request, and the URL is public: anyone you give it to may
   invoke this handler until it expires or you stop it.
5. Return to the editor and choose **⋯ → HTTP → Stop publication** on the same
   function. Reload the public tab; the publication is now unavailable.
6. Finish the lesson and remove its created items. Cleanup records the proposed
   publication UUID before sending the create request, so a lost response does
   not force it to guess a name. Deleting the published branch revokes its leases.

The finite host accepts plain text or JSON, not HTML or streaming responses.
It removes cookies and bearer credentials from the incoming request and controls
response headers. Default capacity is 64 publications per installation, two per
organization, and two per owner. A full capacity message asks you to stop an
existing publication or wait for a deadline; these are separate from service
quotas. Execution still follows plan effects, concurrency and network limits.
See [Temporary HTTP handlers](../TEMPORARY_HTTP.md) for the complete contract.

## Persistent services: self-hosted or dedicated executors

A persistent service is a stored desired-state row: keep this function running.
It requires the deployment's service capability. Shared cloud executors do not
acquire that capability merely by publishing a finite handler. **Service
settings** remains the existing control for deployments that support it.

For an actual listener on an executor you control:

1. Keep a fully bound response such as `tutorial-http-answer` above.
2. Extend `http-server` as `tutorial-daemon`. Bind its callable `:handler` to
   that response, and bind `:port` to an available port on your executor.
3. Choose **⋯ → ⚙ Service settings**, select its branch, keep **Enabled**
   checked, and create/reconcile the service. Confirm a running instance is
   reported. This starts a real listener; its address and exposure depend on
   your deployment. Configure its external routing and TLS when needed.
4. Delete the service row to stop the listener. Delete the lesson functions
   separately when no longer needed.

The corresponding ordinary function definitions are:

```edn
{:name :tutorial-http-answer :parent :text-ok-response
 :args {:body "hello HTTP"}}

{:name :tutorial-daemon :parent :http-server
 :args {:port 9101 :handler :tutorial-http-answer}}
```

Use a port that is free on **your** executor; this is not a way to open a port
on a shared cloud pod. `http-server` carries `:process` and the inherited
branch-local protection for listener configuration. Services need all inputs
bound. Other persistent templates include `schedule` and `interval`.
A `future` of a constant ends immediately; it is not a long-lived daemon.

The remaining sections describe persistent services, not temporary publications.

### Restart policy

| Policy | When graphden restarts the fn |
|---|---|
| `:always` | Any exit — crash OR clean return |
| `:on-failure` | Only uncaught exceptions |
| `:never` | Single-shot. Log on exit, move on. |

Two moments count. **Start-time** — a throw while starting (port in
use, a constructor error) is retried up to three times with a
1 s → 2 s → 4 s backoff under `:always` and `:on-failure`; `:never`
makes a single attempt and records `:start-failed-at` (badge
`failed`). **Runtime** — every reconciler pass checks that each
running copy is still alive (the listener is up, the daemon thread
has not ended). A copy that died in place is restarted under
`:always` after ANY exit, a clean stop included; under `:on-failure`
only after an uncaught throw — a clean exit is parked (badge
`exited`); under `:never` it is parked either way. Restarts of a copy
that lived under a minute back off 1 s → 2 s → 4 s → … → 60 s (badge
`backoff`), so a one-shot fn under `:always` is not a hot loop — use
`:interval` for that. Details:
[docs/SERVICES.md § Liveness](../SERVICES.md#liveness--a-copy-that-died-in-place).

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

## Many triggers, one service

A cron loop is one trigger. A real job set usually has several — a
nightly rebuild and a five-minute poll — and they belong to ONE service
row, started and stopped together. There is no "schedules" table for
that: a trigger list is a graph list, like the migrations list in the
appendix at the end of this lesson.

`:interval` is the fixed-period sibling of `:schedule` (bind
`:every-ms` and `:fn`; `:interval-now` is the same loop that also fires
once at start), and `:start-all` takes a list of either:

```edn
{:name :_nightly :parent :schedule :args {:cron "0 0 3 * * ?" :fn :rebuild-index}}
{:name :_poll    :parent :interval :args {:every-ms 300000 :fn :poll-inbox}}
{:name :jobs     :parent :start-all :args {:triggers [:_nightly :_poll]}}
```

`:jobs` has no free args and carries the `:process` effect, so it is
service-eligible — make it a `:singleton` service the way you did with
`:web-server`. Each trigger runs in its own daemon thread; the one
stopper the reconciler holds stops them all, and the service reads as
alive while any trigger runs. If one trigger's thread dies with a
throw, the whole set counts as failed for the restart policy: under
`:on-failure` the reconciler restarts every trigger, since a service
handle is one thing.

Adding a third trigger is an edit to the list, which restarts the
service like any other closure change — no row to create. Listing the
same trigger twice is refused; a job that should run on two cadences
gets two derived fn-defs. To see each
fire on the target's **Runs** tab, point the trigger at a
`:traced-call` wrapper instead of the target itself:

```edn
{:name :_poll-traced :parent :traced-call :args {:fn :poll-inbox}}
{:name :_poll :parent :interval :args {:every-ms 300000 :fn :_poll-traced}}
```

Every fire then lands as an execution of `:poll-inbox` with its own
trace id, the same way a queue consumer's handling does.

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
cross-merge — see lesson 23). Two `:service` rows:

```edn
{:fn-id :prod-server  :branch-id main :enabled? true}
{:fn-id :dev-server   :branch-id dev  :enabled? true}
```

The reconciler starts both. Port 8080 is the production server
on `main`'s graph, port 9001 is the development server on
`dev`'s graph. Iterating on `:app-handler` on `dev` immediately
affects port 9001 without touching port 8080.

### Try it (per-branch edition)

1. Pre-req: complete the per-branch web-server walk-through in
   lesson 23. You should have a copy of `:web-server` on
   `feat-dev-server` parented from `:http-server` with `:port 9001`
   (call it `:dev-server`).
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

```text
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
  fns, deploy a live app at `<label>.graphden.app` (lesson 30), and execute on demand — but
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
(A one-shot ▶ run also passes the TYPE-error gate — a fn with recorded type
diagnostics refuses to execute; see Lesson 15.)

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

## Appendix — migrations as a service

A separate recipe, for when a service owns a table. Because a service is just a no-arg fn, "run this before the listener
starts" is `:do` — sequencing, not a service setting. `storage/pg`
ships the classic migration shape as two templates you derive from:

```edn
{:name :m-001 :parent :migration
 :args {:id "001-notes"
        :ddl {:value {:create-table [:notes :if-not-exists]
                      :with-columns [[:id :bigserial [:primary-key]]
                                     [:body :text]]}}}}

{:name :m-002 :parent :migration
 :args {:id "002-notes-created-at"
        :ddl {:value {:alter-table :notes :add-column [:created_at :timestamptz]}}}}

{:name :notes-migrate :parent :migrate :args {:migrations [:m-001 :m-002]}}
```

Run `:notes-migrate` from the Run pane: the first run creates the
`schema_migrations` journal and the `notes` table, returns `1`, and
the second run returns nothing — both ids are journaled, so nothing is
pending. Add a third migration later with
`{:migrations {:append [:m-003]}}` on a derived fn-def: only the new id
runs.

Put the migrator first in the service's steps and the listener last —
`:do` returns the listener's stopper, and the fn inherits its
`:process` effect, so it is service-eligible:

```edn
{:name :notes-service :parent :do :args {:steps [:notes-migrate :web-server]}}
```

Editing the migration list restarts the service (its closure changed),
and a merge does the same on the target branch, so a migration added on
a feature branch applies when `main`'s service next starts. Mind that
branches version the graph, not the database: the journal table lives
in the one Postgres graphden itself uses. The full contract, including
the advisory lock that lets several pods start at once, is in
[docs/SERVICES.md § Startup steps](../SERVICES.md#startup-steps--schema-migrations).

## Next

[Lesson 36 — Signing up & signing in](36-signing-up-and-in.md): your
account on a graphden cloud. One service naming and calling another
over HTTP is [lesson 38](38-services-talking-to-services.md).

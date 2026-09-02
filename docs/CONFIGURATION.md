# Configuration Guide

Graphden uses [Integrant](https://github.com/weavejester/integrant) for component lifecycle management and [Aero](https://github.com/juxt/aero) for configuration loading.

## Configuration Files

Configuration files are located in `resources/`:

| File | Profile | Purpose |
|------|---------|---------|
| `system-prod.edn` | `:prod` | Production deployment |
| `system-dev.edn` | `:dev` | Local development |
| `system-test.edn` | `:test` | Automated tests |

## System Components

The storage backend is **plain PostgreSQL** — graph traversal is done
with recursive CTEs, not a graph-database extension. The components
below start in dependency order:

```text
:db/schema             → Schema builder (pure, no deps)
       ↓
:db/postgres           → PostgreSQL storage (jdbc-url / pool / schema)
       ↓
:app/storage           → Tenancy storage seam (identity passthrough by default)
       ↓
:db/versioned          → Versioned storage decorator (immutable history)
       ↓
:db/notify-listener    → Dedicated PG conn LISTENing on `graphden_events`
:db/service-locks      → Dedicated PG conn for advisory locks
:app/packages          → Loads resources/packages/ (core / storage / web / app-base / app / registry / mcp)
       ↓
:exec/base-fns         → Base-function registry (Clojure impls)
:exec/fn-entities      → Syncs fn-defs into storage
:vault/client          → OpenBao / Vault KV v2 client
:auth/provider         → Authentication seam (single-token by default)
       ↓
:exec/context          → Executor context
       ↓
:exec/compiled-registry   → Compile fn graphs into closures at startup
       ↓
:exec/branch-router       → Per-branch ExecutionContext + Ring dispatcher
:exec/api-url-drift-check  → Boot-time backend↔frontend URL drift guard
:exec/api-routes-js-cache  → Boot-cached `window.API` JS module
:exec/service-reconciler   → Seeds + supervises `:service` rows (runs the web server)
:exec/cleanup-scheduler    → Hourly :fn-execution TTL sweep
```

There is **no separate `:http/server` component**. The HTTP server is
a `:service` row (the `app` package seeds a `:web-server` service),
seeded and supervised by `:exec/service-reconciler`. The listen port
is fixed at the Docker layer (container `8080`), not via an integrant
`:port` key.

### Component Dependencies

Dependencies are expressed using Integrant references:

```clojure
:db/postgres
{:schema #ig/ref :db/schema   ; Reference to :db/schema component
 :jdbc-url "..."
 ...}
```

Available reference types:

- `#ig/ref :key` — Reference to a single component
- `#ig/refset :key` — Reference to all components matching a key

## Aero Reader Tags

Configuration files support these reader tags:

### `#env` - Environment Variables

Read value from environment variable:

```clojure
:jdbc-url #env JDBC_URL              ; Required
:pool-size #or [#env DB_POOL_SIZE "10"]   ; With default
```

### `#or` - Default Values

Provide fallback if value is nil:

```clojure
:pool-size #or [#env DB_POOL_SIZE "10"]
```

### `#long` - Parse as Long

Convert string to long integer:

```clojure
:pool-size #long #or [#env DB_POOL_SIZE "10"]
```

### `#ig/ref` - Integrant Reference

Reference another component:

```clojure
:schema #ig/ref :db/schema
```

### Package Names

Packages are loaded from `resources/packages/`. Configure which
packages to load:

```clojure
:app/packages {:package-names ["core" "storage" "web" "app-base" "app" "registry" "mcp"]}
```

Production loads `["core" "storage" "web" "app-base" "app" "registry" "mcp"]`
(`registry`/`mcp` are optional — drop either and the app still boots). Dev
additionally loads `"examples"` (pedagogical fn-defs that must never ship to
prod). The **test** system has no `:app/packages` key at all — it wires the
executor directly without loading the app packages.

## Component Configuration

### `:db/schema`

Schema builder component (stateless).

```clojure
:db/schema {}
```

### `:db/postgres`

PostgreSQL storage backend (HikariCP pool). Graph traversal uses
recursive CTEs. The executor auto-migrates its schema on startup.

| Key | Type | Description |
|-----|------|-------------|
| `:jdbc-url` | string | JDBC connection URL |
| `:username` | string | Database username |
| `:password` | string | Database password |
| `:pool-size` | long | HikariCP pool size |
| `:schema` | ref | Reference to `:db/schema` |

```clojure
:db/postgres
{:jdbc-url #or [#env JDBC_URL "jdbc:postgresql://localhost:5432/graphden"]
 :username #or [#env DB_USERNAME "graphden"]
 :password #or [#env DB_PASSWORD "graphden"]
 :pool-size #long #or [#env DB_POOL_SIZE "10"]
 :schema #ig/ref :db/schema}
```

### `:app/storage`

Tenancy storage seam. Identity passthrough by default; the tenancy
addon overrides this key with an org-scoped decorator. Sits *beneath*
versioning so the branch-router's `vs/unwrap` keeps the tenant filter.

| Key | Type | Description |
|-----|------|-------------|
| `:base` | ref | Reference to `:db/postgres` |

```clojure
:app/storage
{:base #ig/ref :db/postgres}
```

### `:db/versioned`

Versioned storage decorator providing immutable history.

| Key | Type | Description |
|-----|------|-------------|
| `:base-storage` | ref | Reference to `:app/storage` |

```clojure
:db/versioned
{:base-storage #ig/ref :app/storage}
```

### `:db/notify-listener`

Owns a dedicated PostgreSQL connection that `LISTEN`s on the
`graphden_events` channel. A mutation on one pod is observed by all
sibling pods within ~1s (cross-pod cache invalidation). A pool-bound
connection can't be used because the LISTEN session must stay alive
forever.

```clojure
:db/notify-listener
{:pg-opts
 {:jdbc-url #or [#env JDBC_URL "jdbc:postgresql://localhost:5432/graphden"]
  :username #or [#env DB_USERNAME "graphden"]
  :password #or [#env DB_PASSWORD "graphden"]}}
```

### `:db/service-locks`

Owns a dedicated PostgreSQL connection for session-scoped advisory
locks. The service reconciler acquires a per-service lock before
starting each service; only the pod that wins runs it. Advisory locks
are session-scoped, so a pool connection can't back them.

```clojure
:db/service-locks
{:pg-opts
 {:jdbc-url #or [#env JDBC_URL "jdbc:postgresql://localhost:5432/graphden"]
  :username #or [#env DB_USERNAME "graphden"]
  :password #or [#env DB_PASSWORD "graphden"]}}
```

### `:app/packages`

Loads base-fns and fn-defs from `resources/packages/`. fn-defs are NOT
a separate integrant component — they come from the packages listed
here.

```clojure
:app/packages
{:package-names ["core" "storage" "web" "app-base" "app" "registry" "mcp"]}
```

### `:exec/base-fns`

Registers base functions from the loaded packages' Clojure impls.

| Key | Type | Description |
|-----|------|-------------|
| `:storage` | ref | Reference to `:db/versioned` |
| `:packages` | ref | Reference to `:app/packages` |

```clojure
:exec/base-fns
{:storage #ig/ref :db/versioned
 :packages #ig/ref :app/packages}
```

### `:exec/fn-entities`

Syncs the packages' fn-def declarations into storage.

| Key | Type | Description |
|-----|------|-------------|
| `:storage` | ref | Reference to `:db/versioned` |
| `:packages` | ref | Reference to `:app/packages` |
| `:base-fns` | ref | Reference to `:exec/base-fns` |

```clojure
:exec/fn-entities
{:storage #ig/ref :db/versioned
 :packages #ig/ref :app/packages
 :base-fns #ig/ref :exec/base-fns}
```

### `:vault/client`

OpenBao / Vault KV v2 client. Address + token are infrastructure-level
— the user fn-graph never sees them; it calls `:vault-get`, which
pulls the client off the executor context. When `VAULT_ADDR` is unset
the client returns nil and `:vault-get` errors on use.

```clojure
:vault/client
{:address #or [#env VAULT_ADDR ""]
 :token #or [#env VAULT_TOKEN ""]}
```

### `:auth/provider`

Authentication seam. Default single-token provider (the token comes
from `AUTH_TOKEN`); the tenancy addon overrides this key with
session/JWT auth.

```clojure
:auth/provider
{:token #or [#env AUTH_TOKEN ""]}
```

### `:exec/context`

Executor context for running functions.

| Key | Type | Description |
|-----|------|-------------|
| `:storage` | ref | Reference to `:db/versioned` |
| `:vault-client` | ref | Reference to `:vault/client` |
| `:pg-storage` | ref | Raw PG storage for `NOTIFY` emission (`:db/postgres`) |
| `:base-fns` | ref | Ctx-scoped base-fns map (`:exec/base-fns`) |
| `:auth-provider` | ref | Reference to `:auth/provider` |

```clojure
:exec/context
{:storage #ig/ref :db/versioned
 :vault-client #ig/ref :vault/client
 :pg-storage #ig/ref :db/postgres
 :base-fns #ig/ref :exec/base-fns
 :auth-provider #ig/ref :auth/provider}
```

### `:exec/compiled-registry`

Compiles fn graphs into Clojure closures at startup. Depends on
`:exec/fn-entities` so every composed fn is already in storage before
traversal.

```clojure
:exec/compiled-registry
{:context #ig/ref :exec/context
 :_fn-entities #ig/ref :exec/fn-entities}
```

### `:exec/branch-router`

Per-branch `ExecutionContext` registry + Ring dispatcher, seeded with
the default branch (`main`) after the compiled registry is built. The
`:branch-routing-wrap` base-fn reads this router on each request to
pick which branch's compiled view serves the call.

```clojure
:exec/branch-router
{:context #ig/ref :exec/context
 :_compiled-registry #ig/ref :exec/compiled-registry}
```

### `:exec/api-url-drift-check`

Boot-time backend↔frontend URL drift guard. Walks the live router's
`/api/*` paths and scans editor JS for `/api/*` literals; throws on
drift so a route rename that forgets the JS fails boot rather than
404ing at runtime. Toggle off via `GRAPHDEN_SKIP_URL_DRIFT_CHECK=1`.

```clojure
:exec/api-url-drift-check
{:context #ig/ref :exec/context
 :_compiled-registry #ig/ref :exec/compiled-registry
 :skip? #or [#env GRAPHDEN_SKIP_URL_DRIFT_CHECK ""]}
```

### `:exec/api-routes-js-cache`

Boot-time-cached `window.API = {…}` JS module, computed once after the
router compiles. The editor addresses routes by name
(`API.api_branches`) instead of duplicating path literals.

```clojure
:exec/api-routes-js-cache
{:context #ig/ref :exec/context
 :_compiled-registry #ig/ref :exec/compiled-registry}
```

### `:exec/service-reconciler`

Seeds the packages' `:services` declarations into the `:service` table
(idempotent, deterministic ids), then starts the enabled rows under
the supervisor. **This is how the HTTP server runs** — the `app`
package declares a `:web-server` service. Uses the notify-listener and
service-locks for cross-pod coordination.

```clojure
:exec/service-reconciler
{:context #ig/ref :exec/context
 :packages #ig/ref :app/packages
 :notify-listener #ig/ref :db/notify-listener
 :service-locks #ig/ref :db/service-locks
 :_compiled-registry #ig/ref :exec/compiled-registry
 :_branch-router #ig/ref :exec/branch-router}
```

### `:exec/cleanup-scheduler`

Hourly sweep of `:fn-execution` rows past their per-status TTL.
Independent of HTTP — runs even when nobody hits `/api`. The period is
overridable via `CLEANUP_PERIOD_MS`.

```clojure
:exec/cleanup-scheduler
{:context #ig/ref :exec/context
 :period-ms #long #or [#env CLEANUP_PERIOD_MS "3600000"]}
```

### `:exec/demo-branches` (opt-in)

Seeds demo branches for the versioning UI. Only seeds when
`GRAPHDEN_DEMO_BRANCHES_ENABLED` is truthy (`1`, `true`, `yes`, `on`).
Idempotent — a branch whose `:name` already exists is left untouched.
Real prod ships with this off; dev/docker enables it so the branch
picker has content out of the box.

## Environment Variables

The production config reads these via `#env`:

| Variable | Default | Description |
|----------|---------|-------------|
| `JDBC_URL` | `jdbc:postgresql://localhost:5432/graphden` | PostgreSQL connection URL |
| `DB_USERNAME` | `graphden` | Database username |
| `DB_PASSWORD` | `graphden` | Database password |
| `DB_POOL_SIZE` | `10` | HikariCP pool size |
| `VAULT_ADDR` | *(empty)* | OpenBao / Vault address (unset → `:vault-get` errors) |
| `VAULT_TOKEN` | *(empty)* | OpenBao / Vault token |
| `AUTH_TOKEN` | *(empty)* | Single-token auth secret |
| `GRAPHDEN_SKIP_URL_DRIFT_CHECK` | *(empty)* | `1` to skip the boot URL-drift check |
| `CLEANUP_PERIOD_MS` | `3600000` | `:fn-execution` TTL sweep period (ms) |
| `GRAPHDEN_DEMO_BRANCHES_ENABLED` | *(empty)* | Truthy to seed demo branches |
| `GRAPHDEN_SSE_PORT` | *(unset ⇒ SSE relay off)* | Port for the cross-executor invalidation SSE relay (`:sse/relay`) |
| `GRAPHDEN_EXECUTOR_ORGS` | *(unset ⇒ all orgs)* | Org-shard predicate for this executor |
| `GRAPHDEN_BYO_EXECUTOR` | *(empty)* | Truthy marks this pod a BYO executor |
| `GRAPHDEN_EXECUTOR_ID` | *(empty)* | Fleet identity; set enables `:exec/fleet-controller` |
| `GRAPHDEN_FLEET_CONTROLLER_PERIOD_MS` | `30000` | Fleet placement-controller tick period (ms) |
| `GRAPHDEN_MAX_CACHED_BRANCHES` | `16` | LRU cap on warm per-branch ctx entries in the branch router |

(Deployment-specific knobs read directly via `System/getenv` —
`GRAPHDEN_MAX_CONCURRENT_EXECUTIONS`, `GRAPHDEN_FLEET_*`, the BYO vars, and
the `GRAPHDEN_DISABLE_ASSET_OVERRIDES` rescue hatch — are covered in
[DEPLOYMENT.md](DEPLOYMENT.md), [SCALING.md](SCALING.md), and
[FLEET_DEPLOY.md](FLEET_DEPLOY.md).)

### Accounts (opt-in identity module)

Enabled by naming the addon fragment in `GRAPHDEN_ADDON_CONFIGS`
(comma-separated list, read in `system/config.clj`; each fragment
deep-merges over `system-<profile>.edn`):

```bash
GRAPHDEN_ADDON_CONFIGS=graphden/accounts/addon.edn
```

The fragment (`resources/graphden/accounts/addon.edn`) reads via `#env`:

| Variable | Default | Description |
|----------|---------|-------------|
| `RESEND_API_KEY` | *(empty ⇒ LogMailer)* | Resend API key for transactional email; unset logs the links instead |
| `GRAPHDEN_MAIL_FROM` | *(built-in sender)* | Override the From address |
| `GRAPHDEN_APP_ORIGIN` | *(unset ⇒ request Host)* | Public origin for OAuth redirect URIs + emailed links |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | *(empty ⇒ GitHub login off)* | GitHub OAuth app — both required to enable |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | *(empty ⇒ Google login off)* | Google OIDC client — both required to enable |
| `TELEGRAM_BOT_TOKEN` / `TELEGRAM_BOT_USERNAME` | *(empty ⇒ Telegram login off)* | Telegram login-widget bot — both required to enable |

Each social provider is per-provider opt-in: it appears only when both of
its credentials are set. See [ACCOUNTS.md](ACCOUNTS.md) for the module
itself (data model, `/auth/*` surface, editor integration).

There is no env-configurable execution max-depth, but the per-execution
wall-clock deadline **is** tunable via `GRAPHDEN_MAX_EXECUTION_WALL_MS`
(default `300000` = 5 min; read in `crud/fn_execution/persist.clj`). The dev
config (`system-dev.edn`) reads DB settings from the `GRAPHDEN_`-prefixed
variants (`GRAPHDEN_JDBC_URL`, `GRAPHDEN_DB_USER`,
`GRAPHDEN_DB_PASSWORD`) and defaults to port `5434`.

## Profile-Specific Settings

### Development (`:dev`)

- Smaller connection pool (5)
- Local database URL (`GRAPHDEN_JDBC_URL`, default port `5434`)
- Loads the `examples` package
- Demo branches enabled by default

### Test (`:test`)

- Minimal pool
- `jdbc-url` injected by test fixtures / overrides

### Production (`:prod`)

- DB, auth, and vault settings from environment variables
- Larger default pool size (10)
- Full component stack; `examples` package excluded

## Programmatic Configuration

### Starting the System

```clojure
(require '[graphden.system.interface :as sys])

;; Start with profile
(def system (sys/start! :prod))

;; Start with overrides (merged per top-level key)
(def system (sys/start-with-overrides! :test
              {:db/postgres {:jdbc-url "jdbc:postgresql://localhost:5432/test"}}))

;; Start only a subset of components (and their deps)
(def system (sys/start! :prod [:db/postgres :db/versioned]))

;; Stop
(sys/stop! system)
```

### Reading Configuration

```clojure
(require '[graphden.system.interface :as sys])

(def config (sys/read-config :prod))
;; Returns raw Integrant config map
```

## Adding Custom Components

1. Define an `init-key` method in one of the `graphden.system.init.*`
   namespaces (`graphden.system.core` is now only a loader that `:require`s
   them for their `defmethod` side effects):

```clojure
(defmethod ig/init-key :my/component [_ config]
  ;; Initialize and return component
  )

(defmethod ig/halt-key! :my/component [_ component]
  ;; Clean up component
  )
```

1. Add to the configuration files:

```clojure
:my/component
{:some-setting "value"
 :dependency #ig/ref :other/component}
```

## Logging Configuration

Logging is configured via `resources/logback.xml`. See the file for
pattern and level configuration.

Key MDC fields:

- `correlation-id` — Request tracing ID
- Custom fields via `graphden.logging.interface/with-context`

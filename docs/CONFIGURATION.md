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

The system consists of interconnected components:

```
:db/schema        → Schema builder (pure, no deps)
       ↓
:db/age           → Apache AGE storage
       ↓
:db/versioned     → Versioned storage wrapper
       ↓
:exec/base-fns    → Base function registry
:exec/fn-entities → Function definitions
:exec/context     → Executor context
       ↓
:http/server      → HTTP server
```

### Component Dependencies

Dependencies are expressed using Integrant references:

```clojure
:db/age
{:schema #ig/ref :db/schema   ; Reference to :db/schema component
 :jdbc-url "..."
 ...}
```

Available reference types:

- `#ig/ref :key` - Reference to a single component
- `#ig/refset :key` - Reference to all components matching a key

## Aero Reader Tags

Configuration files support these reader tags:

### `#env` - Environment Variables

Read value from environment variable:

```clojure
:jdbc-url #env JDBC_URL           ; Required
:port #or [#env PORT "8080"]      ; With default
```

### `#or` - Default Values

Provide fallback if value is nil:

```clojure
:pool-size #or [#env DB_POOL_SIZE "10"]
```

### `#long` - Parse as Long

Convert string to long integer:

```clojure
:port #long #or [#env PORT "8080"]
```

### `#ig/ref` - Integrant Reference

Reference another component:

```clojure
:schema #ig/ref :db/schema
```

### Package Names

Packages are loaded from `resources/packages/` directory. Configure which packages to load:

```clojure
:app/packages {:package-names ["core" "web" "app"]}
```

## Component Configuration

### `:db/schema`

Schema builder component (stateless).

```clojure
:db/schema {}
```

### `:db/age`

Apache AGE storage backend.

| Key | Type | Description |
|-----|------|-------------|
| `:jdbc-url` | string | JDBC connection URL |
| `:username` | string | Database username |
| `:password` | string | Database password |
| `:pool-size` | long | HikariCP pool size |
| `:schema` | ref | Reference to `:db/schema` |

```clojure
:db/age
{:jdbc-url #or [#env JDBC_URL "jdbc:postgresql://localhost:5432/graphden"]
 :username #or [#env DB_USERNAME "graphden"]
 :password #or [#env DB_PASSWORD "graphden"]
 :pool-size #long #or [#env DB_POOL_SIZE "10"]
 :schema #ig/ref :db/schema}
```

### `:db/versioned`

Versioned storage wrapper providing immutable history.

| Key | Type | Description |
|-----|------|-------------|
| `:base-storage` | ref | Reference to `:db/age` |

```clojure
:db/versioned
{:base-storage #ig/ref :db/age}
```

### `:exec/base-fns`

Registers base functions from Clojure implementations.

| Key | Type | Description |
|-----|------|-------------|
| `:storage` | ref | Reference to `:db/versioned` |

```clojure
:exec/base-fns
{:storage #ig/ref :db/versioned}
```

### `:exec/fn-entities`

Syncs function definitions to storage.

| Key | Type | Description |
|-----|------|-------------|
| `:storage` | ref | Reference to `:db/versioned` |
| `:fn-defs` | refset | Reference to function definition components |

```clojure
:exec/fn-entities
{:storage #ig/ref :db/versioned
 :fn-defs #ig/refset :app/fn-defs}
```

### `:exec/context`

Executor context for running functions.

| Key | Type | Description |
|-----|------|-------------|
| `:storage` | ref | Reference to `:db/versioned` |
| `:max-depth` | long | Maximum call depth (prevents infinite recursion) |
| `:timeout-ms` | long | Execution timeout in milliseconds |

```clojure
:exec/context
{:storage #ig/ref :db/versioned
 :max-depth #long #or [#env EXEC_MAX_DEPTH "1000"]
 :timeout-ms #long #or [#env EXEC_TIMEOUT_MS "30000"]}
```

### `:http/server`

HTTP server component.

| Key | Type | Description |
|-----|------|-------------|
| `:context` | ref | Reference to `:exec/context` |
| `:startup-fn-name` | keyword | Name of startup function to execute |
| `:port` | long | HTTP port |

```clojure
:http/server
{:context #ig/ref :exec/context
 :startup-fn-name :web-server-fn
 :port #long #or [#env PORT "8080"]}
```

### `:app/fn-defs`

Application-specific function definitions (referenced by `:exec/fn-entities`).

```clojure
:app/fn-defs #var graphden.web.server.interface/fn-defs
```

## Profile-Specific Settings

### Development (`:dev`)

- Smaller connection pool (5)
- Local database URL
- No environment variable overrides

### Test (`:test`)

- Minimal pool size (2)
- Lower timeouts for faster test feedback
- `jdbc-url` is `nil` (injected by test fixtures)
- No HTTP server or fn-entities components

### Production (`:prod`)

- All settings from environment variables
- Larger default pool size (10)
- Full component stack including HTTP server

## Programmatic Configuration

### Starting the System

```clojure
(require '[graphden.system.interface :as sys])

;; Start with profile
(def system (sys/start! :prod))

;; Start with overrides
(def system (sys/start-with-overrides! :test
              {:db/age {:jdbc-url "jdbc:postgresql://localhost:5432/test"}}))

;; Stop
(sys/stop! system)
```

### Reading Configuration

```clojure
(require '[graphden.system.interface :as sys])

(def config (sys/read-config :prod))
;; Returns raw Integrant config map
```

### REPL Development

```clojure
(require '[integrant.repl :refer [go halt reset]])
(require '[integrant.repl.state :refer [system]])

(go)      ; Start system
(halt)    ; Stop system
(reset)   ; Stop, reload, restart
```

## Adding Custom Components

1. Define `init-key` method in `graphden.system.core`:

```clojure
(defmethod ig/init-key :my/component [_ config]
  ;; Initialize and return component
  )

(defmethod ig/halt-key! :my/component [_ component]
  ;; Clean up component
  )
```

1. Add to configuration files:

```clojure
:my/component
{:some-setting "value"
 :dependency #ig/ref :other/component}
```

## Logging Configuration

Logging is configured via `resources/logback.xml`. See the file for pattern and level configuration.

Key MDC fields:

- `correlation-id` - Request tracing ID
- Custom fields via `graphden.logging.interface/with-context`

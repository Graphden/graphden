# Packages System

> **Last updated:** 2026-03-11
>
> This document describes the packages system for organizing base functions and fn-defs.
> For architecture overview, see [ARCHITECTURE.md](ARCHITECTURE.md).

## Table of Contents

1. [Overview](#overview)
2. [Package Structure](#package-structure)
3. [Module Files](#module-files)
4. [Package Loading](#package-loading)
5. [Composition Best Practices](#composition-best-practices)
6. [Naming Guidelines](#naming-guidelines)
7. [Adding New Functions](#adding-new-functions)

---

## Overview

The packages system organizes base functions and fn-defs into modular, reusable units stored in `resources/packages/`. Each package contains:

- **package.edn** — metadata, dependencies, and module list
- **modules/** — directories containing `fns.edn` (definitions) and optionally `impls.clj` (Clojure implementations)

### Package Hierarchy

```
resources/packages/
├── core/                # Core primitives (no dependencies)
├── storage/             # Storage primitives over graphden's own datasource
├── web/                 # Web primitives (depends on core)
├── app-base/            # Route-building vocabulary shared by app + addons
├── registry/            # OPTIONAL — in-graph publish/install/fork/export
├── mcp/                 # OPTIONAL — the /mcp JSON-RPC AI endpoint
└── app/                 # Application server (editor UI + routes + server)
```

Each package directory holds its `package.edn` plus module
subdirectories (`core/arithmetic/`, `web/http/`, `app/editor/`, …).
The authoritative as-built map — including the external packages kept
out of the prod tree (`mathx`, `examples`) — is
[PACKAGE_DISTRIBUTION.md § 15.1](PACKAGE_DISTRIBUTION.md).

### Key Design Principles

1. **Separation of definitions and implementations** — `fns.edn` contains pure data, `impls.clj` contains Clojure code
2. **Dependency ordering** — packages declare dependencies; loader handles topological sort
3. **Base functions vs fn-defs** — same file format, distinguished by presence of `:parent` field
4. **Module organization** — related functions grouped into semantic modules

---

## Package Structure

### package.edn Format

```edn
{:name "app"
 :version "1.0.0"
 :description "Application server: editor UI + routes"
 :dependencies ["core" "web"]
 :modules ["editor" "routes" "server"]
 ;; Optional: desired-state services the package seeds (see SERVICES.md)
 :services [{:name :default
             :fn-name :web-server
             :restart-policy :always
             :cardinality :per-pod}]}
```

| Field | Required | Description |
|-------|----------|-------------|
| `:name` | Yes | Package identifier (matches directory name) |
| `:version` | Yes | Semantic version string |
| `:description` | No | Human-readable description |
| `:dependencies` | Yes | Packages to load first (see version constraints below) |
| `:modules` | Yes | List of module directory names |
| `:services` | No | Package-seeded services — desired-state fns to keep running (see [SERVICES.md](SERVICES.md)) |

**Dependency version constraints.** `:dependencies` accepts either a bare
name list (any version) or a map of `name → constraint`:

```edn
:dependencies ["core" "web"]                 ; any version (legacy, still valid)
:dependencies {"core" ">=1.5.0" "web" "~>2.1"}  ; version constraints
```

Constraint syntax (`graphden.packages.semver`): exact (`"1.2.0"` / `"=1.2.0"`),
comparison (`">=1.2.0"` `">1.2.0"` `"<=1.2.0"` `"<1.2.0"`), pessimistic
(`"~>1.2.3"` → `>=1.2.3 <1.3.0`; `"~>1.2"` → `>=1.2.0 <2.0.0`), caret
(`"^1.2.3"` → `>=1.2.3 <2.0.0`), or any (`"*"` / omitted). The loader validates
the version present on the classpath against every constraint at boot and
throws `:packages/version-conflict` on a mismatch. (Registry-driven version
*selection* is an install-time concern — see
[PACKAGE_DISTRIBUTION.md](PACKAGE_DISTRIBUTION.md) § 4.4.)

### Module Directory

Each module is a directory containing:

```
{module-name}/
├── fns.edn      # Required: function definitions
└── impls.clj    # Optional: Clojure implementations for base-fns
```

---

## Module Files

### fns.edn — Function Definitions

A namespace map: `:namespace` + `:description` metadata wrapping a
`:fns` vector of definitions. Each entry in `:fns` is either a base
function or a fn-def.

```edn
{:namespace "core.arithmetic"
 :description "Arithmetic and comparison primitives over numbers."
 :fns
 [;; Base Function (has a Clojure impl in impls.clj)
  {:name :add
   :args {:nums {:type [:list :numeric]}}
   :return-type :numeric}

  ;; Fn-def (composition, no impl)
  {:name :add-10
   :parent :add
   :args {:nums [10]}}]}
```

An arg spec may be the type keyword directly (`:nums :jsonb`) or the
expanded `{:type … :required … :description …}` map — the loader
normalizes the shorthand to the map form.

#### Base Function Fields

| Field | Required | Description |
|-------|----------|-------------|
| `:name` | Yes | Unique keyword identifier |
| `:args` | Yes | Map of arg-name → type keyword |
| `:return-type` | Yes | Return type keyword |
| `:ctx` | No | If `true`, impl receives execution context |
| `:lazy-args` | No | Set of arg names to NOT auto-deref |

**Arg types:**

- `:int`, `:numeric`, `:text`, `:bool` — primitive types
- `:jsonb` — JSON-compatible data (arrays, maps)
- `:any` — no type checking
- `:fn` — function reference (for HOF)
- `:uuid` — UUID value

**Optional args:**

```edn
{:name :get
 :args {:coll {:type :any}
        :key {:type :any}
        :default {:type :any :required false}}  ; Optional arg
 :return-type :any}
```

#### Fn-def Fields

| Field | Required | Description |
|-------|----------|-------------|
| `:name` | Yes | Unique keyword identifier |
| `:parent` | Yes | Parent function to inherit from |
| `:args` | Yes | Map of arg-name → value or fn-reference |

**Arg values:**

- Literal: `{:port 8080}` — direct value
- Reference: `{:handler :router-fn}` — reference to another function
- Qualified reference: `{:handler :web.reitit/router-fn}` — same
  reference, namespace-explicit. Validated at parse time (a wrong
  namespace fails loud instead of silently resolving to a same-named
  fn elsewhere) and normalized to the bare form. Works in every
  reference position: `:parent`/`:parents`, arg refs, `{:ref …}`,
  sequence items, inline-anon bodies, `:return-type`, type members.
  See [ADR-identity-model.md](adr/ADR-identity-model.md) stage 4.

### impls.clj — Clojure Implementations

Base-fn bodies are written with the `defbase` macro. Arg symbols
listed in the vector refer to the corresponding args declared in
`fns.edn` and arrive already-resolved inside the body. `ctx` is
automatically available for impls that need storage or the
execution context:

```clojure
(ns graphden.packages.core.arithmetic.impls
  (:require [graphden.executor.defbase :refer [defbase]]))

(defbase add
  "Sum a vector of numbers."
  [nums]
  (reduce + nums))

(defbase sub
  [nums]
  (reduce - nums))

;; Export map: fn-name → implementation
(def impls
  {:add add
   :sub sub})
```

**Context access** — any `defbase` impl can reference `ctx` directly
(no flag required; present for every impl):

```clojure
(defbase list-entities
  [entity-type]
  (sp/query-entities (:storage ctx) (keyword entity-type) {}))
```

---

## Package Loading

### API

```clojure
(require '[graphden.packages.loader :as pkg])

;; Load packages with dependencies
(def packages (pkg/load-packages ["core" "web" "app"]))

;; Result structure
{:base-fn-defs {fn-name -> base-fn-def, ...}
 :fn-defs [{:name :foo :parent :bar :args {...}}, ...]
 :packages [{:name "core" ...}, {:name "web" ...}, ...]}
;; (package-seeded :services are collected separately — see SERVICES.md)
```

### Loading Process

1. **Read package.edn** for each requested package
2. **Topological sort** by dependencies
3. **Load modules** in order from each package:
   - Read `fns.edn` — function definitions
   - Read `impls.clj` — Clojure implementations (if exists)
   - Match implementations to definitions
4. **Merge results** — base-fn-defs map + fn-defs vector

### Integration with System

```clojure
;; init-key defmethods live in system/init/packages.clj (Integrant)

(defmethod ig/init-key :app/packages [_ {:keys [package-names]}]
  (pkg/load-packages package-names))

(defmethod ig/init-key :exec/base-fns [_ {:keys [storage packages]}]
  (registry/register-base-fns! (:base-fn-defs packages))
  (registry/sync-defs-to-storage! storage (:base-fn-defs packages)))

(defmethod ig/init-key :exec/fn-entities [_ {:keys [storage packages]}]
  (fn-composition/sync-fns-to-storage! storage (:fn-defs packages)))
```

---

## Composition Best Practices

### 1. Use Inheritance to Eliminate Duplication (DRY)

When multiple fn-defs share structure, extract a common ancestor:

**Problem — Duplication:**

```edn
{:name :health-response
 :parent :ring-response
 :args {:status 200
        :headers {"Content-Type" "application/json"}
        :body :health-json}}

{:name :metrics-response
 :parent :ring-response
 :args {:status 200
        :headers {"Content-Type" "application/json"}
        :body :metrics-json}}
```

**Solution — Extract common ancestor:**

```edn
;; Common building block
{:name :json-ok-response
 :parent :ring-response
 :args {:status 200
        :headers {"Content-Type" "application/json"}}}

;; Specific responses
{:name :health-response
 :parent :json-ok-response
 :args {:body :health-json}}

{:name :metrics-response
 :parent :json-ok-response
 :args {:body :metrics-json}}
```

**When to extract:**

- Same ancestor (any level, not just immediate parent)
- One or more identical bound arguments
- Repeated structural pattern
- A meaningful name can describe the extracted function

### 2. Free Arguments Pattern (Argument Propagation)

When a fn-def references another fn-def with unbound arguments, those arguments "propagate up" and become part of the interface:

> The fn/slot names below (`assoc-any`, `pair`, `m`/`k`/`v`, `a`/`b`) are
> illustrative placeholders chosen to keep the propagation chain readable —
> they are **not** real base-fns. The real `:assoc` exposes `:map`/`:key`/`:value`
> and `:get` exposes `:coll`/`:key`/`:default`; substitute those when adapting
> the pattern.

```edn
;; Base: assoc-any has args {m, k, v}

;; Level 1: Fix m={}, expose {k, v}
{:name :assoc-empty
 :parent :assoc-any
 :args {:m {}}}

;; Level 2: Fix k="handler", expose {v}
{:name :assoc-handler
 :parent :assoc-empty
 :args {:k "handler"}}

;; Level 2: Reference :assoc-handler as v
;; :assoc-handler's free arg (v) propagates through
{:name :method-map
 :parent :assoc-empty
 :args {:v :assoc-handler}}

;; Level 3: Use method-map as b
;; Free args from method-map propagate: {k, v}
{:name :route
 :parent :pair
 :args {:b :method-map}}

;; Level 4: Fix k="get", expose {a, v}
{:name :get-route
 :parent :route
 :args {:k "get"}}

;; Usage: Provide a (path) and v (handler)
{:name :health-route
 :parent :get-route
 :args {:a "/health" :v :health-handler-fn}}
```

**Key insight:** This pattern enables building reusable "templates" where each level fixes some arguments while exposing others. The executor recursively resolves free arguments at runtime.

### 3. Named vs Anonymous (One-off) Functions

**Use named fn-def when:**

- Function is reused multiple times
- Function has clear semantic meaning
- Function represents a domain concept
- Function could be tested independently

**Use one-off composition when:**

- Combination is used exactly once
- No semantic meaning beyond "connect A to B"
- Specific path + specific handler (unlikely to reuse)

**Example:**

```edn
;; Named: reusable building block
{:name :json-ok-response
 :parent :ok-response
 :args {:headers {"Content-Type" "application/json"}}}

;; One-off: specific endpoint (no intermediate fn-def needed)
{:name :health-route
 :parent :get-route
 :args {:a "/health" :v :health-handler-fn}}
```

**Rule of thumb:** If you can't give it a meaningful name that describes WHAT it does (not just HOW it connects things), it's a one-off.

### 4. Hierarchy Depth Guidelines

| Levels | Use Case | Example |
|--------|----------|---------|
| 2-3 | Response types | `ring-response` → `ok-response` → `json-ok-response` |
| 4-5 | Complex composition | Route building blocks |
| 6+ | Review needed | Ensure each level has independent meaning |

Each level should:

1. Have a meaningful, descriptive name
2. Be potentially reusable elsewhere
3. Represent a cohesive concept

### 5. Base Function vs Fn-def Decision Matrix

| Question | Base Function | Fn-def |
|----------|---------------|--------|
| Has Clojure implementation? | Yes | No |
| Wraps external library? | Yes | No |
| Contains hardcoded values? | No (except defaults) | Yes |
| Can be composed from existing functions? | No | Yes |
| Implementation > 20 lines? | Reconsider | N/A |

---

## Naming Guidelines

A fn-def's name should be **short** and add only the **last bit of distinction**. Surrounding context — the namespace path, the parent fn-def, the arg names visible on incoming edges — already conveys most of the meaning. The name's job is to disambiguate within that context, not to repeat it.

### Drop affixes the context already conveys

| Avoid | Prefer | Why |
|---|---|---|
| `app.server.health-route` | `app.routes.health` | NS already says "this is a route" |
| `app.editor.editor-page` | `app.editor.page` | NS prefix duplicates |
| `web.reitit.router-with-text-404` | `web.reitit.text.r404` | Move to sub-NS, drop the path prefix |
| `app.server.post-auth-required-route` | `app.routes.auth.post` | NS says "auth-required template", parent says "route" |
| `web.crud.create-entity-api-handler` | `web.crud.create-entity-handler` | Parent `:html-action-response` already conveys "action API" |
| `app.server.all-routes` | `app.routes.groups.all` | NS already says "this is a routes bundle" |

### When affixes earn their keep

Some suffixes survive because they distinguish the fn-def from a sibling that would otherwise share the same name:

| Keep | Why |
|---|---|
| `*-handler` (web.crud) | Distinguishes the response builder from the route fn-def of the same logical endpoint. `app.routes.entity-details` (route) vs `web.crud.entity-details-handler` (Ring response builder). |
| `get-route`, `post-route` (templates) | Marks them as method templates, distinct from concrete routes that *drop* the suffix. The asymmetry — templates have `-route`, leaves don't — is the signal. |

### Per-namespace fn names — grep before you rename

Names are unique **per `(namespace, name)` pair** (ADR-identity-model
stage 5): two modules may each declare `:get-user`. The sync validator
rejects a duplicate pair (the pair IS the deterministic fn-id), and
BASE-FN names additionally stay globally unique (the Clojure impls
registry is name-keyed). A bare reference to a name that several
namespaces define must be qualified (`:other.ns/name`) or the sync
throws `:packages/ambiguous-ref`; your own module's name always wins
unqualified. Even **local** fn-defs (leading `_`, stored `name=nil`)
follow the per-namespace rule.

Before shortening a name, check:

```bash
grep -rE ":name :the-target-name\b|defbase the-target-name\b" resources/packages/
```

Common collision sources:

- Base fn-defs in `core.*` (e.g. `core.collections.get`, `core.collections.list`).
- Base fn-defs in `web.crud` (e.g. `get-entity`, `delete-entity` — both base-fns).
- Sibling namespaces touching the same domain (e.g. `app.routes.entity-details` vs a hypothetical `web.crud.handlers.entity-details`).

### Verb-at-end when the prefix form clashes

When the natural verb-prefix form clashes with a base-fn or sibling, swap to verb-at-end. The graph reads almost identically; the sidebar bonus is alphabetical clustering by domain:

| Was | Renamed | Reason |
|---|---|---|
| `get-entity-route` | `entity-get` | `get-entity` is a `web.crud` base-fn |
| `delete-entity-route` | `entity-delete` | `delete-entity` is a `web.crud` base-fn |

In the sidebar, `entity-create`, `entity-update`, `entity-delete` cluster together; `create-entity`, `update-entity`, `delete-entity` would split the domain in two.

### Extract a sub-namespace when a group shares a long prefix

If ~5 or more fn-defs share a long common prefix that just describes their group, that's the signal to extract a sub-namespace and drop the prefix.

Decision rule: if the group has a coherent semantic boundary AND ≥ ~5 members, extract a sub-NS; otherwise just drop the redundant in-place affix.

| Group | Members | Action |
|---|---|---|
| `*-route` leaves | 13 | New NS `app.routes`, drop suffix |
| `*-routes` bundles | 5 | New NS `app.routes.groups`, drop suffix |
| `router-with-text-*` presets | 6 | New NS `web.reitit.text`, drop prefix (`r404`/`r405`/`r500`) |
| `*-auth-required-route` templates | 2 | New NS `app.routes.auth`, drop both ends (`post`/`delete`) |
| `editor-*` in `app.editor` | 9 | Already in the right NS — drop prefix in place |

### Locals (`_*`) — same per-namespace rule

Local fn-defs are stored with `name=nil` and don't appear in the sidebar or graph navigation, but their **declared** name in `fns.edn` is validated like any other: unique within their namespace, qualified references required when the bare name is ambiguous across the loaded set. Keep their names descriptive — a same-named local in another module forces qualification on anyone referencing yours:

- ✅ `_health-handler`, `_metrics-handler`, `_favicon-handler` — each carries the endpoint context.
- ❌ `_handler`, `_body`, `_response` — would clash the moment a second module needs the same shape.

### Quick checklist before naming a new fn-def

1. Does the surrounding namespace already say what kind of thing this is? Drop that part of the name.
2. Does the parent fn-def already say what shape this is? Drop that part too.
3. After dropping, does the bare name still uniquely identify the fn-def? If not, restore the smallest disambiguating affix.
4. Will the fn-def be referenced unqualified (`:name`) from another module? Grep for collisions across `resources/packages/`.

---

## Adding New Functions

### Adding a Base Function

1. **Choose package and module** (or create new module)

2. **Add definition to `fns.edn`:**

```edn
{:name :my-new-fn
 :args {:input :jsonb
        :options {:type :jsonb :required false}}
 :return-type :jsonb}
```

1. **Add implementation to `impls.clj`:**

```clojure
(defbase my-new-fn
  [input options]                ; arg-syms match fns.edn; arrive resolved
  (let [opts (or options {})]    ; :options is :required false → nil when omitted
    ;; Implementation here
    (process input opts)))

;; Add to exports
(def impls
  {:existing-fn existing-fn
   :my-new-fn my-new-fn})  ; Add here
```

1. **Run tests:** `bb test`

### Adding a Fn-def

1. **Choose package and module**

2. **Add definition to `fns.edn`:**

```edn
{:name :my-composed-fn
 :parent :existing-fn
 :args {:input :data-source-fn
        :options {:format "json"}}}
```

1. **Run tests:** `bb test`

### Creating a New Module

1. **Create module directory:**

```bash
mkdir -p resources/packages/{package}/{module}
```

1. **Create `fns.edn`:**

```edn
{:namespace "{package}.{module}"
 :description "My new module functions."
 :fns [{:name :first-fn
        :args {:x :any}
        :return-type :any}]}
```

1. **Create `impls.clj`** (if has base functions):

```clojure
(ns graphden.packages.{package}.{module}.impls
  (:require [graphden.executor.defbase :refer [defbase]]))

(defbase first-fn [x] x)

(def impls {:first-fn first-fn})
```

1. **Add module to `package.edn`:**

```edn
{:name "{package}"
 :modules ["existing" "new-module"]}  ; Add here
```

### Creating a New Package

1. **Create package directory:**

```bash
mkdir -p resources/packages/{new-package}
```

1. **Create `package.edn`:**

```edn
{:name "{new-package}"
 :version "1.0.0"
 :description "Description here"
 :dependencies ["core"]  ; Required dependencies
 :modules ["first-module"]}
```

1. **Create modules** (see above)

2. **Update configuration** to include new package in load list

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
6. [Adding New Functions](#adding-new-functions)

---

## Overview

The packages system organizes base functions and fn-defs into modular, reusable units stored in `resources/packages/`. Each package contains:

- **package.edn** — metadata, dependencies, and module list
- **modules/** — directories containing `fns.edn` (definitions) and optionally `impls.clj` (Clojure implementations)

### Package Hierarchy

```
resources/packages/
├── core/                # Core primitives (no dependencies)
│   ├── package.edn
│   ├── arithmetic/
│   ├── logic/
│   ├── hof/
│   ├── collections/
│   ├── strings/
│   └── system/
├── web/                 # Web primitives (depends on core)
│   ├── package.edn
│   ├── http/
│   ├── reitit/
│   ├── html/
│   ├── crud/
│   └── graph/
└── app/                 # Application (depends on core, web)
    ├── package.edn
    ├── common/          # Shared building blocks
    ├── editor/          # Editor UI
    └── server/          # Server composition
```

### Key Design Principles

1. **Separation of definitions and implementations** — `fns.edn` contains pure data, `impls.clj` contains Clojure code
2. **Dependency ordering** — packages declare dependencies; loader handles topological sort
3. **Base functions vs fn-defs** — same file format, distinguished by presence of `:parent` field
4. **Module organization** — related functions grouped into semantic modules

---

## Package Structure

### package.edn Format

```edn
{:name "web"
 :version "1.0.0"
 :description "Web primitives: HTTP, routing, HTML"
 :dependencies ["core"]
 :modules ["http" "reitit" "html" "crud" "graph"]
 :startup-fn :web-server}  ; Optional
```

| Field | Required | Description |
|-------|----------|-------------|
| `:name` | Yes | Package identifier (matches directory name) |
| `:version` | Yes | Semantic version string |
| `:description` | No | Human-readable description |
| `:dependencies` | Yes | List of package names to load first |
| `:modules` | Yes | List of module directory names |
| `:startup-fn` | No | Function to execute when system starts |

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

Contains a vector of function definitions. Each definition is either:

**Base Function** (has Clojure implementation):
```edn
{:name :add
 :args {:nums :jsonb}
 :return-type :numeric}
```

**Fn-def** (composition, no implementation):
```edn
{:name :add-10
 :parent :add
 :args {:nums [10]}}
```

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
{:name :filter
 :args {:pred :fn
        :coll {:type :jsonb :required false}}  ; Optional arg
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

### impls.clj — Clojure Implementations

```clojure
(ns graphden.packages.core.arithmetic.impls)

(defn add
  "Adds numbers together."
  [{:keys [nums]}]
  (apply + nums))

(defn sub
  [{:keys [nums]}]
  (apply - nums))

;; Export map: fn-name → implementation
(def impls
  {:add add
   :sub sub})
```

**Implementation function signature:**
```clojure
(defn my-fn
  [{:keys [arg1 arg2 ...]}]  ; Destructure args (already dereferenced)
  ...)
```

**With context access:**
```edn
;; In fns.edn
{:name :list-entities
 :args {:entity-type :text}
 :return-type :jsonb
 :ctx true}
```
```clojure
;; In impls.clj
(defn list-entities
  [{:keys [entity-type]} ctx]  ; ctx is second argument
  (let [storage (:storage ctx)]
    (sp/query-entities storage (keyword entity-type) {})))
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
 :packages [{:name "core" ...}, {:name "web" ...}, ...]
 :startup-fn :web-server}
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
;; In system/core.clj (Integrant)

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

3. **Add implementation to `impls.clj`:**
```clojure
(defn my-new-fn
  [{:keys [input options]}]
  (let [opts (or options {})]
    ;; Implementation here
    (process input opts)))

;; Add to exports
(def impls
  {:existing-fn existing-fn
   :my-new-fn my-new-fn})  ; Add here
```

4. **Run tests:** `bb test`

### Adding a Fn-def

1. **Choose package and module**

2. **Add definition to `fns.edn`:**
```edn
{:name :my-composed-fn
 :parent :existing-fn
 :args {:input :data-source-fn
        :options {:format "json"}}}
```

3. **Run tests:** `bb test`

### Creating a New Module

1. **Create module directory:**
```bash
mkdir -p resources/packages/{package}/{module}
```

2. **Create `fns.edn`:**
```edn
;; My new module functions
[{:name :first-fn
  :args {:x :any}
  :return-type :any}]
```

3. **Create `impls.clj`** (if has base functions):
```clojure
(ns graphden.packages.{package}.{module}.impls)

(defn first-fn [{:keys [x]}] x)

(def impls {:first-fn first-fn})
```

4. **Add module to `package.edn`:**
```edn
{:name "{package}"
 :modules ["existing" "new-module"]}  ; Add here
```

### Creating a New Package

1. **Create package directory:**
```bash
mkdir -p resources/packages/{new-package}
```

2. **Create `package.edn`:**
```edn
{:name "{new-package}"
 :version "1.0.0"
 :description "Description here"
 :dependencies ["core"]  ; Required dependencies
 :modules ["first-module"]}
```

3. **Create modules** (see above)

4. **Update configuration** to include new package in load list

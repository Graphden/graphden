# Lesson 11 — Packages: namespaces, fns.edn, impls.clj, deps

**Goal**: by the end of this lesson you can write your own
package — namespaces, base-fns, and fn-defs — that loads
alongside graphden at startup.

**Concepts introduced**: `package`, `module`, `fns.edn`,
`impls.clj`, `package.edn`, `:depends-on`, `defbase`,
`namespace tree`.

## What's a package

A package is a directory under `resources/packages/` that
groups related fn-defs and base-fn impls. Graphden ships three:

| Package | What lives there |
|---|---|
| `core` | Arithmetic, logic, HOF primitives, collections, strings — the "standard library" |
| `web` | HTTP server, routing, hiccup HTML, CRUD primitives, Vault, http-client |
| `app` | The editor itself, /api routes, branches, services, secrets UI |

You can add a fourth — say `mycorp` — for fn-defs specific to
your application. Graphden's loader picks it up at startup
without any code change beyond writing the files.

## The shape on disk

```
resources/packages/mycorp/
├── package.edn          ; metadata + dependencies
├── auth/                ; a "module" — flat namespace
│   ├── fns.edn          ;   fn-defs declared in this module
│   └── impls.clj        ;   Clojure impls for any base-fns the
│                        ;   module declares
└── billing/
    ├── fns.edn
    └── impls.clj
```

Two levels of grouping:

- **Package** (`mycorp/`) — what gets loaded at startup. The
  unit of dependency.
- **Module** (`auth/`, `billing/`) — fns and their impls
  co-located. The unit of grouping fn-defs by topic. Maps to
  a graphden NAMESPACE the editor's sidebar shows.

## `package.edn`

```edn
{:description "MyCorp application code"
 :depends-on [core web app]
 :modules [auth billing]
 :startup-fn :start-app}
```

| Key | Meaning |
|---|---|
| `:description` | Sidebar tooltip for the top-level namespace |
| `:depends-on` | Packages this one needs. The loader topo-sorts so deps load first |
| `:modules` | Which subdirectories to load. Order doesn't matter |
| `:startup-fn` | Optional — when set, the integrant lifecycle invokes this fn after all packages load. The `app` package uses this to start `:web-server`. |

The loader fails fast on a missing dependency (you wrote
`:depends-on [storage]` but no `storage` package is on disk).

## `fns.edn` — declaration

A vector of fn-def maps:

```edn
{:ns auth
 :description "Authentication primitives — bearer tokens, password hashes."
 :fns
 [{:name :hash-password
   :description "Argon2 hash of the password. Slow on purpose."
   :args {:password {:type :text}
          :salt     {:type :text}}
   :return-type :text
   :effects #{}}

  {:name :verify-password
   :description "Constant-time compare of password against hash."
   :args {:password {:type :text}
          :hash     {:type :text}}
   :return-type :bool
   :effects #{}}

  ;; A fn-def using the base-fns above
  {:name :login-route
   :parent :json-handler
   :args  {:body :build-login-response}}]}
```

`:ns` declares this module's namespace key. The editor's
sidebar shows `mycorp.auth` as a tree node. `:description` at
the module level shows up on hover.

Inside `:fns`, the maps are either:

- **Base function declarations** — have `:args` describing the
  inputs and `:return-type` declaring the output. The impl
  lives in `impls.clj`. The loader links them by name.
- **Fn-defs** — have `:parent` (singular) or `:parents` (vector)
  plus `:args` binding the parent's slots. Pure composition,
  no Clojure.

## `impls.clj` — Clojure code

```clojure
(ns graphden.packages.mycorp.auth.impls
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [my.lib.argon2 :as argon2]))


(defbase hash-password
  "Argon2 hash. Wraps a single library call (per the §3.1
   one-call rule in CLAUDE.md)."
  [password salt]
  (argon2/hash password salt))


(defbase verify-password
  [password hash]
  (argon2/verify password hash))
```

`defbase` is the macro that turns a regular Clojure fn into a
base-fn impl. The body should be a SINGLE library call (see
the "Base Function Philosophy" rule in CLAUDE.md). Anything more
than a one-liner belongs in the graph as a composed fn-def.

The macro registers the impl under the fn-name keyword, so by
the time the loader runs the fn-def declarations from `fns.edn`,
the impl is ready.

### Side note: `:effects` on base-fns

Base-fns DECLARE the effect categories they touch. For
`:hash-password` it's `:effects #{}` — Argon2 is pure (slow,
but pure). For `:pg-query` it's `:effects #{:db}`. The type-
checker propagates these transitively through every fn-def that
refs the base-fn, so editor effect-strips show the full set.

## Sync at startup

When graphden boots:

1. **Schema migration** — DDL diff against the DB.
2. **Package loading** (`packages.loader/load-packages`) reads
   every `package.edn`, topo-sorts by `:depends-on`, loads each
   in order:
   - Read `fns.edn` from each module — produce fn-defs and base-
     fn declarations.
   - Read `impls.clj` — `defbase` macro registers each impl.
   - Wire each declaration to its impl by name.
3. **Storage sync** — write every fn-def to the DB (idempotent;
   deterministic UUIDs). Adds new rows, updates changed ones,
   leaves removed ones alone (sync is decl-only; truncate via
   `bb deploy` for full reset).
4. **Type-check sweep** — topological-order pass over every
   fn-def; populates the rich-types registry the editor reads
   for type chips, free-arg forms, effect strips.
5. **Service reconcile** — start anything package-declared as a
   `:service` (the `app` package's `:web-server` is the canonical
   case).

You don't write code for any of these steps. The loader runs
them when graphden's integrant lifecycle starts.

## Try it

1. Create `resources/packages/mycorp/package.edn`:

   ```edn
   {:description "MyCorp tutorial package"
    :depends-on [core]
    :modules [hello]}
   ```

2. Create `resources/packages/mycorp/hello/fns.edn`:

   ```edn
   {:ns hello
    :description "Tutorial hello fns."
    :fns
    [{:name :greet
      :parent :str-concat
      :args {:parts ["Hello, " {:as :name} "!"]}}]}
   ```

3. Run `bb rebuild`. After the JVM restarts, the editor's
   sidebar shows `mycorp.hello.greet`. Open `⋯` → `▶ Run`. Supply
   `:name = "world"`. Get `"Hello, world!"` back.

   No `impls.clj` needed here — `:greet` is a pure fn-def
   parented from `:str-concat` (a base-fn from `core`). The
   `:depends-on [core]` brought `:str-concat` into scope.

## What we glossed over

- **Cross-package references** — you can ref a fn-def from
  another package by NAME, as long as the package is in your
  `:depends-on`. The loader resolves names globally at sync
  time.
- **Naming guidelines** — the rules for picking fn-names that
  don't collide and read well. See [docs/PACKAGES.md § Naming](../PACKAGES.md#naming-guidelines).
- **The `:_admin-secret-create` flag and per-package secret
  shapes** — admin-only entry-points used by the `app.secrets`
  package. See [docs/PACKAGES.md](../PACKAGES.md).

## Next

Congratulations — you've reached the end of the tutorial
sequence. From here, follow the cross-references inside each
lesson to the reference docs in [docs/](..) for the topic
you're working on, and look at the existing packages
(`core`, `web`, `app`) as worked examples.

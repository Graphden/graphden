# Lesson 11 — Packages: namespaces, fns.edn, impls.clj, deps

**Goal**: by the end of this lesson you can write your own
package — namespaces, base-fns, and fn-defs — that loads
alongside graphden at startup, and you understand how the
first-party packages are layered.

**Concepts introduced**: `package`, `module`, `fns.edn`,
`impls.clj`, `package.edn`, `:dependencies`, `:package-names`,
`defbase`, the `impls` map, `namespace tree`, optional package.

## What's a package

A package is a directory under `resources/packages/` that groups
related fn-defs and base-fn impls. Graphden's first-party
packages are LAYERED — each depends only on the ones below it:

| Package | What lives there | Depends on |
|---|---|---|
| `core` | Arithmetic, logic, HOF, collections, strings — the "standard library" | — |
| `storage` | Postgres + versioned/branch storage primitives | `core` |
| `web` | HTTP server, reitit routing, hiccup HTML, CRUD, Vault | `core` `storage` |
| `app-base` | The reitit route-building vocabulary (`:route`, method/auth route templates) | `core` `web` |
| `app` | The editor UI, `/api` routes, branches, services, secrets | `core` `web` `storage` `app-base` |
| `registry` | In-graph package publish / install / fork / export — **optional** | `core` `web` `storage` `app-base` |
| `mcp` | The `/mcp` JSON-RPC AI endpoint — **optional** | `core` `web` `app` |

`tenancy-admin` is a further package loaded only when the
multi-tenant addon is active. You can add your own — say
`mycorp` — and the loader picks it up at startup, no code change
beyond writing the files.

## The shape on disk

```text
resources/packages/mycorp/
├── package.edn          ; metadata + dependencies
├── auth/                ; a "module"
│   ├── fns.edn          ;   fn-defs declared in this module
│   └── impls.clj        ;   Clojure impls for the module's base-fns
└── billing/
    ├── fns.edn
    └── impls.clj
```

Two levels of grouping:

- **Package** (`mycorp/`) — what loads at startup; the unit of
  dependency.
- **Module** (`auth/`, `billing/`) — fns and their impls
  co-located; the unit of grouping by topic. Maps to a graphden
  NAMESPACE the editor's explorer shows.

## `package.edn`

```edn
{:name "mycorp"
 :version "1.0.0"
 :description "MyCorp application code"
 :dependencies ["core" "web" "app"]
 :modules ["auth" "billing"]}
```

| Key | Meaning |
|---|---|
| `:name` | Package name — a string, matches the directory |
| `:version` | Package version (semver string) |
| `:description` | Sidebar tooltip for the top-level namespace |
| `:dependencies` | Package names (strings) this one needs. The loader topo-sorts so deps load first, and pulls them TRANSITIVELY |
| `:modules` | Which subdirectories to load (strings). Order matters only if one module's `:namespace` aliases another's types |
| `:services` | Optional — a package can declare a fn to keep running (the `app` package declares `:web-server` this way). See [Lesson 10](10-services.md) |

The loader fails fast on a missing dependency.

## `fns.edn` — declaration

Each module's `fns.edn` is a map with a `:namespace`, a
`:description`, and a `:fns` vector:

```edn
{:namespace "mycorp.auth"
 :description "Authentication primitives — bearer tokens, password hashes."
 :fns
 [{:name :hash-password
   :description "Argon2 hash of the password. Slow on purpose."
   :args {:password {:type :text} :salt {:type :text}}
   :return-type :text
   :effects #{}}

  ;; A fn-def composing the base-fn above
  {:name :login-route
   :parent :json-handler
   :args  {:body :build-login-response}}]}
```

`:namespace` is a STRING and is the FULL namespace path — it does
NOT have to match the package name. Identity is
`uuid-v5(:namespace, :name)`, so the namespace, not the directory,
is a fn's stable identity. (That's how `app-base` can hold fns
whose namespace is `app.routes.auth`: the routing vocabulary
moved packages without changing any fn's id.)

Inside `:fns`, each map is either:

- a **base-fn declaration** — `:args` + `:return-type`, impl in
  `impls.clj`, or
- a **fn-def** — `:parent` / `:parents` + `:args` binding the
  parent's slots. Pure composition, no Clojure. (Lessons
  [01](01-fn-defs.md)–[04](04-free-arguments.md).)

## `impls.clj` — Clojure code

Two parts: `defbase` fns, and an `impls` map that links each
fn-name keyword to its impl.

```clojure
(ns graphden.packages.mycorp.auth.impls
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [my.lib.argon2 :as argon2]))


(defbase hash-password-fn [password salt]
  (argon2/hash password salt))          ; ONE library call — see below


(def impls
  {:hash-password {:impl hash-password-fn}})
```

Two things are required, and forgetting the second is the most
common mistake:

1. `defbase` turns a Clojure fn into a base-fn impl. Its body
   should be a SINGLE library call (the "Base Function
   Philosophy" rule in [CLAUDE.md](../../CLAUDE.md) — anything
   longer belongs in the graph as a composed fn-def).
2. The `impls` map (read by the loader) links the fn-def's
   `:name` keyword to the impl. Without an entry, the fn-def in
   `fns.edn` loads with no impl and its consumers fail with a
   silent `unknown-parent` at sync.

### Side note: `:effects` on base-fns

Base-fns DECLARE the effect categories they touch — `:effects #{}`
for a pure fn like the hash, `:effects #{:db}` for `:pg-query`.
The type-checker propagates them transitively so editor effect
strips show the full set. See [Lesson 07](07-effects-and-secrets.md).

## Sync at startup

When graphden boots, `packages.loader/load-packages` reads every
`package.edn`, topo-sorts by `:dependencies`, and for each
package reads its modules' `fns.edn` + `impls.clj`, wiring each
declaration to its impl by name. Then `graphden.packages.sync`
writes every fn-def to the DB (idempotent, deterministic UUIDs),
runs the topological type-check sweep, and reconciles declared
`:services`. You write no code for any of this.

Which packages load is the `:package-names` list in
`resources/system-*.edn` (deps are pulled in transitively, so
listing `"app"` also loads `core`/`web`/`storage`/`app-base`).
Because `registry` and `mcp` are OPTIONAL, dropping either from
`:package-names` omits it — the app still boots, the editor hides
its packages affordances (the **packages** chip on the Build
surface, the per-namespace **⬆** publish action, and the
governance section on **Organization**), and `/mcp` 404s. (Full distribution flow —
publish / install / update / fork — is [Lesson 14](14-distributing-packages.md).)

## Try it

1. Create `resources/packages/mycorp/package.edn`:

   ```edn
   {:name "mycorp"
    :version "1.0.0"
    :description "MyCorp tutorial package"
    :dependencies ["core"]
    :modules ["hello"]}
   ```

2. Create `resources/packages/mycorp/hello/fns.edn`:

   ```edn
   {:namespace "mycorp.hello"
    :description "Tutorial hello fns."
    :fns
    [{:name :greet
      :parent :str-join
      :args {:coll [{:value "Hello, "} {:as :name} {:value "!"}]
             :separator {:value ""}}}]}
   ```

3. Add `"mycorp"` to `:package-names` in
   `resources/system-dev.edn`, then run `bb rebuild`. After the
   JVM restarts, the editor's explorer shows `mycorp.hello.greet`.
   Open `⋯` → `▶ Run`, supply `:name = "world"`, get
   `"Hello, world!"`.

   No `impls.clj` needed — `:greet` is a pure fn-def parented from
   `:str-join` (a `core` base-fn, brought into scope by
   `:dependencies ["core"]`). `{:as :name}` is a free arg
   (Lesson 04); the `{:value …}` items are literal strings joined
   with an empty separator.

## What we glossed over

- **Cross-package references** — ref a fn-def from another
  package by NAME as long as that package is in your
  `:dependencies`. Names are per-namespace: a bare ref resolves
  when unambiguous across the loaded set (your own module wins);
  a name defined in several namespaces must be qualified —
  `:other.ns/name` — or sync throws `:packages/ambiguous-ref`.
- **Naming guidelines** — [docs/PACKAGES.md § Naming](../PACKAGES.md#naming-guidelines).
- **Distributing a package as data** (not on disk) — publish it
  into the graph and install it on a branch: [Lesson 14](14-distributing-packages.md).

## Next

[Lesson 12 — Composing pages from components](12-components-and-pages.md).

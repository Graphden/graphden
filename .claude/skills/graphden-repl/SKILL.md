---
name: graphden-repl
description: REPL-driven workflow for Graphden via the clojure MCP server. Use when debugging or modifying base-fn impls, fn-defs, executor / storage / schema code — to verify behavior in the running nREPL before editing files and rebuilding. Triggers on phrases like "check", "why isn't it working", "fix the impl", "see what it returns", "execute fn", "go to the REPL", or any task that would otherwise require a `bb rebuild` cycle just to test a hypothesis. SKIP for: pure frontend (.js/.css) changes, package.edn dependency edits, Docker/CI config — those don't run inside the JVM REPL.
---

# graphden-repl — REPL-first for Graphden

The purpose of this skill: **first verify the hypothesis in a live nREPL, then edit files**. The `bb rebuild` cycle (jar + docker + restart, ~30–60 s) is only needed for the final deploy — not for debugging.

## 0. Sanity check (once at the start of a session)

```clojure
;; mcp__clojure__list_nrepl_ports → should show localhost:<port> (clj) for /root/projects/graphden
;; then:
(System/getProperty "user.dir")  ; => "/root/projects/graphden"
```

If there's no port — ask the user to run `bb nrepl-bg` (background headless nREPL, writes `.nrepl-port`).

## 1. Bring up the system

The `dev` namespace ([development/src/dev.clj](../../development/src/dev.clj)) is loaded in the REPL:

```clojure
(require 'dev :reload)         ; always :reload — otherwise you may work with a stale def
(dev/go)                       ; starts the Integrant system with the :dev profile (testcontainers Postgres)
@integrant.repl.state/system   ; runtime map; nil if not started
(dev/halt)                     ; stop
(dev/reset)                    ; halt + reload config + go (after editing ig/init-key)
```

Access to components without digging into the map:

```clojure
(dev/storage)   ; :db/versioned — VersionedStorage
(dev/context)   ; :exec/context — what the executor expects as its first argument
(dev/server)    ; :http/server  — for checking routes
```

## 2. Basic checks

### Execute a fn by name

```clojure
(require '[graphden.executor.interface :as exec])
(exec/execute-by-name (dev/context) "add-10" {:b 5})
;; => 15
```

### Find a fn-id and inspect its args

```clojure
(require '[graphden.storage.protocol.interface :as sp])
(let [s (dev/storage)
      [f] (sp/query-entities s :fn {:name "add-10"})]
  {:fn f
   :args (sp/query-entities s :arg {:fn-id (:id f)})})
```

### Get the execution-graph (what the compiler sees)

```clojure
(sp/resolve-execution-graph (dev/storage) fn-id)
```

## 3. Testing base-fn impls

The most common one: "does my `defbase` work?". You **don't need** `bb rebuild` — `:reload` loads fresh code:

```clojure
;; reload a specific impls.clj
(require 'graphden.packages.core.arithmetic.impls :reload)
;; or the whole loader (re-reads fns.edn while assembling the packages map)
(require '[graphden.packages.loader :as pkg] :reload)
(pkg/load-packages ["core" "web" "app"])
;; => {:base-fn-defs {...} :fn-defs [...] :packages [...] :startup-fn :web-server}
```

`load-packages` takes package names as **strings**, not keywords, and doesn't write anything to the DB itself — the sync is done by the Integrant init-key `:exec/base-fns`. The most reliable way to apply `fns.edn` / `defbase` edits to a running system is `(dev/reset)`: it re-runs the init-keys, including the package sync into storage.

## 4. Verifying hypotheses without editing files

```clojure
;; In the REPL, redefine an impl temporarily:
(in-ns 'graphden.packages.core.arithmetic.impls)
(graphden.executor.defbase/defbase add-10 [a b] (+ a b 10))
(in-ns 'user)
;; now exec/execute-by-name "add-10" will return a result with the new logic
```

If the redefinition **confirms** the hypothesis — port it into the file and do the final `bb rebuild` to deploy into Docker.

## 5. When the REPL doesn't help (you need `bb rebuild`)

- Final deploy into Docker (without it, prod won't see the changes).
- Changes to `.js`/`.css` (the frontend comes from the jar — needs a rebuild; verify via `window.BUILD_HASH` / `bb verify`).
- Changes to `deps.edn` / `bb.edn` / `package.edn` dependencies.
- Changes to `system.edn` / Aero / Integrant keys that aren't picked up by `dev/reset`.
- When testing something that depends on rebuilding the uberjar.

Related memory: **`bb rebuild` after backend changes** — that's about deploy, not debugging. Debugging happens in the REPL, `rebuild` is run **once** at the end.

## 6. Useful MCP tools paired with the REPL

- `mcp__clojure__clojure_eval` — the main workhorse.
- `mcp__clojure__code_critique` — sic it on a finished chunk before committing.
- `mcp__clojure__clojure_inspect_project` — a quick overview of deps/aliases without reading `deps.edn` by hand.
- `mcp__clojure__clojure_edit` / `clojure_edit_replace_sexp` — structural editing of forms; less chance of breaking parens than `Edit` line by line.
- `mcp__clojure__paren_repair` — if you did break them after all.

## 7. Anti-patterns

- ❌ Running `bb test --focus ...` to check a single function — the REPL is an order of magnitude faster.
- ❌ `bb rebuild` after every small edit — the REPL and `:reload` were made for exactly this.
- ❌ Forgetting `:reload` in `require` — you'll be chasing ghosts of a previous session (see the explicit reminder in the description of `clojure_eval` itself).
- ❌ Keeping `(dev/go)` running across a change of `:dev` profile / `system.edn` — do `dev/reset` or `dev/halt` + `dev/go`.

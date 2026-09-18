# Roadmap

What is planned, what is deliberately not, and a one-paragraph summary of
what already exists. Status of shipped work lives in the code and the
per-topic docs, not here.

## Implemented

The runtime: the slot/binding schema with graph constraints, PostgreSQL
storage over recursive CTEs, versioned storage with branches, merge and
review, the compile-at-startup executor with delta recompilation and
cross-process invalidation, the full base-fn set (arithmetic, logic, HOFs,
collections, strings, system, HTTP server and client, HTML, CRUD), the type
system with refinements, records, unions and rich types, `:fix` recursion
([RECURSION.md](RECURSION.md)), secrets as a taint type
([SECRETS.md](SECRETS.md)), services with a reconciler, a Postgres queue
and service-to-service endpoints ([SERVICES.md](SERVICES.md)), org
sharding, quotas and a BYO executor ([SCALING.md](SCALING.md)).

The surfaces: the visual editor with a server-computed layout, compare
mode as the diff surface, workspaces, lenses for failed runs, type errors
and graph lint, an inspector with run history, path traces and a
step-through call tree; the REST API; the `/mcp` endpoint that lets an AI
client author graphs ([MCP_CLIENTS.md](MCP_CLIENTS.md)); the package
registry and marketplace with themes and keymaps
([PACKAGE_DISTRIBUTION.md](PACKAGE_DISTRIBUTION.md),
[MARKETPLACE.md](MARKETPLACE.md)); in-graph tests with auto-run on write
([TESTS.md](TESTS.md)); the accounts module ([ACCOUNTS.md](ACCOUNTS.md));
the editor's own assets editable in place as versioned overrides; the
tutorial ([tutorial/](tutorial/)) and the developer tour
([devtour/](devtour/README.md)). The multi-tenant cloud policy ships from
the private `graphden-tenancy` repo over the seams in
[TENANCY_SEAM.md](TENANCY_SEAM.md).

## Roadmap by Blocks (current plan)

Numbers are rough effort for one developer. Order is by value to the
first external users; anything not listed under a block is not scheduled.

### Ecosystem

1. **Integration packages** — `telegram-bot`, `postgres-client`,
   `openai-client`, `discord` webhook, `bluesky`; a `social-post` fan-out
   fn-def over them so the project's own announcements run through a
   graph. ~1 week each.
2. **Starter templates in the marketplace** — API poller, webhook
   receiver, Telegram bot, scheduled report, queue with retries; a client
   project starts from a template, not an empty graph. ~1–2 weeks.
3. **Sidecar pattern** — `:python-call` / `:go-call` base-fns for
   cross-language reach at coarse granularity. Deferred until an
   integration needs it; the launch packages are pure HTTP. ~1.5 weeks.

### Block 9 — AI Integration

The `/mcp` server and the `graphden://ai-context` resource are shipped;
users co-edit through their own MCP client (Claude Code, Cursor) at zero
model cost to the project. What remains:

1. **Editor "Ask AI"** — a prompt plus a target branch (default a fresh
   `ai/<slug>`), an AI session against the user's own model and key
   through the same MCP tools, then the existing compare mode for accept /
   reject. Bring-your-own-model: per-user key stored through the vault
   surface on the cloud, client-side when self-hosted. ~2 weeks.
2. **Managed-model gateway** — a control-plane proxy for users who do not
   want to manage keys, priced per token. Closed source, lives with the
   cloud control plane. ~1.5 weeks, post-launch.
3. **Proposal panel** — per-fn-def diff cards with reject-with-feedback
   and conversational follow-up. ~2 weeks, post-launch.
4. **Persistent AI sessions** — transcripts and tool-call traces per
   branch, resume and share. ~1 week, post-launch.

### Scaling — what remains

Sharding, invalidation, quotas, service-to-service and the BYO executor
are shipped ([SCALING.md](SCALING.md)). Open: a load-balancer rule that
routes by subdomain so the `421` misdirect stays a backstop, and a BYO
executor proven on a second physical machine end to end.

### Hot-reload of impls (optional)

Changing a `defbase` body needs `bb rebuild`. An nREPL channel into each
executor plus a per-file re-sync would let the author push an impl into a
running executor. Risks: classloader hygiene, in-flight requests during
the swap, auth on the channel. ~2 weeks; blocks nothing.

### Deprioritized (do when there's a slot, no critical path)

- **Graph → Clojure export** — a credibility / REPL escape hatch, not on
  the daily path.
- **Clojure → graph import** — needs a real migration target first.
- **Canvas ergonomics borrowed from xyflow (React Flow)** — not adoptable
  as a library (React/Svelte hosts; user-dragged nodes where ours are
  server-laid trees, [LAYOUT.md](LAYOUT.md)), but three behaviours are
  worth reproducing when large graphs start to hurt: a **minimap** with
  the viewport rectangle; **snap-to-grid / align-to-neighbour** while
  hand-dragging a card over the computed grid; **edge clearance** — a taxi
  edge that detours around a card dragged into its path. Behaviour only,
  a day or two each in `editor-graph-view.js` / `editor-edges-svg.js`.
- **Package interface declaration** — an `:exports` fn-def per namespace
  whose list items name the fns the author promises to keep across
  versions: an update can warn "you depend on internals of P", and the
  install browser can show the public few. Enforced privacy is a
  permanent non-goal (install materialises every fn; inherit-override
  needs the internals), so any implementation stays visual-only. Trigger:
  the first real package update that breaks a ref into internals.

### Not planned

- **Datomic** storage backend.
- **Executor rewrite** in another host language — two impls of every
  base-fn forever ([PHILOSOPHY.md](PHILOSOPHY.md) § Trade-off Sovereignty).
- **Multi-language fine-grained execution** — the sidecar pattern covers
  it at the only granularity where it is practical.
- **UI Step 2** (the whole UI structure described as graph) — far future.
- **Transparent cross-pod RPC inside a lazy call chain** — services call
  each other through explicit HTTP in the graph instead
  ([SERVICES.md § Endpoints](SERVICES.md#endpoints--where-a-service-answers)).
- **Popularity-based fn-set distribution** — a routing layer dispatching
  by fn-id popularity pays back only at millions of fns; the pressure it
  addressed (a pod compiling every tenant) is answered by org sharding.

## Future Work

Research threads with a design but no schedule.

### Distributed Execution

Automatic parallelisation across executors. The graph already names
every dependency, so independent subgraphs are identifiable. Phases:
local parallelism of independent args in one JVM; a worker pool on one
machine; remote executors with network transport; cost-based
partitioning. Open decisions: data transfer between executors, granularity,
side-effect ordering, retry. See
[ARCHITECTURE.md § Distributed Execution](ARCHITECTURE.md#part-8-distributed-execution-future).

### Type System (Type Algebra)

Function types, parametric polymorphism (`List[T]`, `Map[K,V]`),
inference for compositions, HOF signatures such as
`map : (a -> b) -> List[a] -> List[b]`. A separate large project; the
decisions already taken are in
[TYPE_SYSTEM_DECISIONS.md](TYPE_SYSTEM_DECISIONS.md).

### Git-like Versioning

Shipped as VersionedStorage, branches, merge, review and per-branch
routing; the design and known gaps are in [VERSIONING.md](VERSIONING.md).

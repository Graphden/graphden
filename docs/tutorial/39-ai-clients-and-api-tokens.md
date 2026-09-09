# Lesson 39 — AI clients and API tokens: the graph over `/mcp`

**Goal**: by the end of this lesson an AI coding client (Claude Code,
Cursor, anything that speaks MCP) can read, extend and run your graph
on a branch of its own, and you know how to hand it exactly the rights
it needs and take them back.

**Concepts introduced**: the **`/mcp` endpoint** (JSON-RPC over HTTP),
the ten **tools** and the one **resource** an agent gets, **branch
scoping** for every call, the **agent cycle** (branch → upsert →
execute → test → diff → your review), **API tokens** with **scopes**
and an **expiry**, and what a token can never do.

Everything an agent does through `/mcp` is an ordinary graph write or
run under *your* rights — the same rights a browser session has, or a
narrower set if the token says so. There is no second permission
system to learn.

## The endpoint

One route: `POST /mcp`, JSON-RPC 2.0, stateless — every call carries an
`Authorization: Bearer <token>` header and stands alone. On a
self-hosted instance the bearer is the instance's `AUTH_TOKEN`; on the
cloud it is an **API token** you mint in Settings (below). The `mcp`
package is optional: an instance that does not load it answers 404.

Ask it what it offers:

```bash
curl -s https://<your-org>.graphden.dev/mcp \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Ten tools come back — `list-namespaces`, `search-fns`, `read-fn`,
`describe-fn`, `execute-fn`, `create-branch`, `upsert-fn-defs`,
`run-tests`, `list-branches`, `diff-branch` — and one resource,
`graphden://ai-context`: the authoring guide for AI clients (the entity
model, composition patterns, naming rules). Tell your agent to read it
before it writes anything.

## Connecting a client

Claude Code, per machine:

```bash
claude mcp add --transport http graphden https://<your-org>.graphden.dev/mcp \
  --header "Authorization: Bearer $TOKEN"
```

Cursor and other MCP clients: the same URL, the same header, transport
"streamable HTTP", no session state. A self-hosted instance is the
same with `http://localhost:9002/mcp`.

## Branch scoping — every call names its branch

Reads (`read-fn`, `describe-fn`, `search-fns`, `execute-fn`,
`list-namespaces`) run against the branch the request rides:
the `X-Graphden-Branch: <name>` header, `?branch=`, or — per call — the
optional `branch` argument, which sends that one call to the named
branch. Mutations name their branch explicitly: `upsert-fn-defs` takes
`branch` and never falls back to `main`. That is the safety property:
an agent cannot write to your trunk unless you ask it to.

## The agent cycle

The loop an agent runs, and what each turn gives you back:

1. **`create-branch`** `ai/<feature>` — its sandbox, an ordinary
   branch ([Lesson 20](20-branches.md)).
2. **`upsert-fn-defs`** with `fn-defs` as **EDN text** in exactly the
   `fns.edn` shape ([Lesson 28](28-packages.md)) — one string inside
   the JSON call, because only EDN keeps `:other-fn` (a reference) and
   `"other-fn"` (a string) apart. A refused def comes back as a
   `-32602` naming what was wrong.
3. **`execute-fn`** / **`run-tests`** on that branch — the same
   pipeline as the editor's Run pane and the tests runner
   ([Lesson 12](12-executing-a-fn.md), [Lesson 14](14-tests.md)).
   `execute-fn` is bounded (10 s, no trace, nothing persisted); long or
   effectful runs come back `pending`.
4. **`diff-branch`** — the proposal, as data.
5. **You** open the editor on `ai/<feature>`, press **Δ** against
   main, and land it through the review cycle you already know —
   propose, approve, merge ([Lesson 21](21-review.md)).

`read-fn` returns a subtree as `fns.edn` text — the same syntax
`upsert-fn-defs` takes, so an agent can read, edit and write back
without translating. `describe-fn` is the computed contract on the
branch: free args, effects, return type, whether the fn could be a
service.

**What the agent cannot do through this door.** `upsert-fn-defs`
refuses fn-defs whose identity is package-owned — the platform's own
fns, restored on every boot ([Lesson 29 § Fork](29-distributing-packages.md#fork--copy-on-write-when-you-want-to-edit)
tells the same story for packages) — unless `allow-platform-overwrite`
is passed on purpose. Base-fn implementations (`impls.clj`) are code,
not graph: they need a rebuild, so no MCP tool touches them.

## API tokens — the rights you hand out

On the cloud (any deployment with the tenancy addon) tokens live in
**Settings → Account → API tokens**. **Create token…** unfolds the form:

- a **label** (`laptop MCP`) — what the row will be called;
- **scopes** — tick what this token may do: *Edit graph & branches*,
  *Execute functions* (both on by default), *Merge branches*, *Manage
  services*, *Write secrets*, *Publish packages*;
- a **lifetime** — 7, 30 or 90 days (the default), a year, or never.

**Create** shows the value **once**; copy it into the client's
configuration and it is gone from the screen. Each row lists its
scopes and expiry and carries **Revoke** — revoking is immediate, and
the client's next call answers 401.

Scopes are a **ceiling**, not a grant: a token's rights are your
account's grants ([Lesson 25](25-grants.md)) *intersected* with its
scopes, so a token can narrow what you may do, never widen it. Two
rules hold regardless of scopes: an API token never reaches
organization management (members, invites, grants, roles) and never
mints or revokes tokens — a leaked key cannot make itself another key.

On a self-hosted instance without the addon there are no per-token
scopes: the bearer is the instance token, as powerful as the account.

## Try it

You need a token (cloud: mint one with the default scopes; self-host:
`AUTH_TOKEN`) and `curl`. Replace the host with yours.

1. Create the sandbox branch:

   ```bash
   curl -s $BASE/mcp -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
          "params":{"name":"create-branch","arguments":{"name":"ai/tutorial-39"}}}'
   # → {"ok":true,"branch":{"name":"ai/tutorial-39",…}}
   ```

2. Write one fn-def onto it — EDN text inside the JSON string:

   ```bash
   curl -s $BASE/mcp -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":2,"method":"tools/call",
          "params":{"name":"upsert-fn-defs","arguments":{"branch":"ai/tutorial-39",
          "fn-defs":"[{:name :shout :namespace \"ai-demo\" :parent :str-upper :args {:string {:value \"hello from mcp\"}}}]"}}}'
   # → {"ok":true,"branch":"ai/tutorial-39","fn-ids":["…"]}
   ```

3. Run it on that branch:

   ```bash
   curl -s $BASE/mcp -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -H "X-Graphden-Branch: ai/tutorial-39" \
     -d '{"jsonrpc":"2.0","id":3,"method":"tools/call",
          "params":{"name":"execute-fn","arguments":{"name":"shout","args":{}}}}'
   # → {"status":"succeeded","result":"HELLO FROM MCP",…}
   ```

4. Read it back as `fns.edn` (`read-fn` with `"branch":"ai/tutorial-39"`)
   — the text you get is the text you sent, plus its parent's row.
5. Open the editor, switch to `ai/tutorial-39` in the branch chip:
   `ai-demo.shout` sits in the Explorer like anything you clicked
   together. Press **Δ** on `main` — one added fn. That is the review
   surface; merging or deleting the branch is your call.
6. Clean up: delete `ai/tutorial-39` from the branch popover (it was
   never merged, so it goes), and — on the cloud — **Revoke** the token
   if it was only for this lesson.

## What we glossed over

- **The full workflow contract** for people developing graphden itself
  (worktree stacks, what the MCP cycle verifies vs. what only a real
  boot-sync catches): [docs/MCP_CLIENTS.md](../MCP_CLIENTS.md).
- **Traces over MCP** — `execute-fn` with `trace: true` returns the
  call tree (one row per fn invoked), the same data the canvas draws in
  [Lesson 15](15-debugging-traces.md).
- **Never point an agent at a shared demo** — branches isolate writes,
  not attention; give each agent its own instance or branch.

## Next

This is the last lesson. The written docs continue where the tutorial
stops: [docs/MCP_CLIENTS.md](../MCP_CLIENTS.md) for the agent workflow,
[docs/SECURITY_MODEL.md](../SECURITY_MODEL.md) for what each principal
may do.

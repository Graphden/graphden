# Connecting an AI client to `/mcp`

How to point Claude Code, Cursor, or any MCP-speaking client at a graphden
instance, and what the live MCP cycle honestly does and does not verify.
The endpoint itself (JSON-RPC shape, per-branch serving, the tool
implementations) lives in `resources/packages/mcp/` — this page is the
client-side setup and the workflow contract.

## The endpoint

One route: `POST /mcp` (JSON-RPC 2.0, stateless streamable-HTTP).
Auth-required — the bearer is a normal graphden token, so every AI tool
call carries exactly that user's rights (on the cloud: org scoping, RLS,
grants, effect gate; on a bare self-host: the single `AUTH_TOKEN`). The
`mcp` package is optional: absent from `:package-names` ⇒ `/mcp` 404s.

Tools: `list-namespaces`, `search-fns`, `read-fn`, `execute-fn`,
`create-branch`, `upsert-fn-defs`, `run-tests`, `list-branches`,
`diff-branch`. Resource: `graphden://ai-context` (the authoring guide —
tell your agent to read it first).

## Claude Code

Local scope (per-machine, not committed — right for worktree stacks whose
ports differ per claim):

```bash
claude mcp add --transport http graphden http://localhost:<port>/mcp \
  --header "Authorization: Bearer <token>"
```

Committed `.mcp.json` (only for an instance with a stable port):

```json
{
  "mcpServers": {
    "graphden": {
      "type": "http",
      "url": "http://localhost:9002/mcp",
      "headers": { "Authorization": "Bearer ${AUTH_TOKEN}" }
    }
  }
}
```

## Cursor / other MCP clients

Any client speaking streamable-HTTP MCP works the same way: URL
`http(s)://<host>/mcp`, an `Authorization: Bearer <token>` header, no
session state (every call is standalone).

## Branch scoping

Reads (`read-fn`, `search-fns`, `execute-fn`, `run-tests`) run against the
branch the REQUEST rides: `X-Graphden-Branch: <name>` or `?branch=`.
Mutations name their branch explicitly (`upsert-fn-defs` takes `branch`)
and never fall back to main. Static client configs can't vary headers
per call, so branch-scoped reads from a plain client go through `curl`:

```bash
curl -s http://localhost:<port>/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Graphden-Branch: ai/my-feature" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
       "params":{"name":"execute-fn","arguments":{"name":"my-fn","args":{}}}}'
```

## The agent cycle (and its honesty table)

The intended loop for AI-assisted fn-def work on this repo:

1. `bb wt claim <name>` → `bb wt up` (isolated stack; the printed port).
2. `claude mcp add --transport http graphden http://localhost:<port>/mcp`.
3. Inner loop, seconds per iteration: `create-branch ai/<feature>` →
   `upsert-fn-defs` (EDN exactly in fns.edn syntax) → `execute-fn` /
   `run-tests` → read `-32602`s and sync errors as actionable feedback.
4. Copy the VERIFIED EDN into `resources/packages/**/fns.edn` verbatim
   (the formats are identical — that is the point of the loop).
5. Final honest check: `bb wt up` again (the only step that runs the real
   package boot-sync) → `bb ci` → `bb wt merge`.

| The MCP cycle verifies | Only the real boot-sync (`bb wt up`) verifies |
|---|---|
| Composition wiring, arg binding, renames | Package load-order (`package.edn` deps) |
| Type-check of the synced bundle | Topological sort across the WHOLE package set |
| Name collisions, cycles within the bundle | `:packages/ambiguous-ref` across package boundaries |
| Real execution semantics (same pipeline as `/api/execute`) | `packages.owned` registration, `:branch-local?` seeds |
| Test results (`run-tests` = the standard runner) | Frontend assets, resource overrides |

Not for the MCP cycle: `impls.clj` edits (a new `defbase` needs a rebuild —
fast-check hypotheses over nREPL instead, see the `graphden-repl` skill),
frontend, docs, trivial constant/description edits.

Guard: `upsert-fn-defs` refuses fn-defs whose deterministic id is
package-owned (the platform set the boot sync restores) unless
`allow-platform-overwrite` is passed — build under your own namespace.

## Gotchas

- **Never point a client at the shared demo stack (`:9002` on the dev
  host)** — worktree stacks exist so agents don't fight over one graph.
- A worktree stack's port changes per claim: re-run `claude mcp add`
  after every `bb wt up`, and expect a stale config after `bb wt drop`.
- `fn-defs` travel as EDN TEXT inside the JSON call — only EDN keeps
  `:other-fn` (a reference) distinguishable from `"other-fn"` (a string).
- `execute-fn` is bounded (10s, no trace/value capture) and does not
  persist history; long or effectful runs come back `pending`.

# Lesson 27 — Debugging: traces, the call tree, and catching a request

**Goal**: by the end of this lesson you can record what a run
actually did — every fn it invoked, in call order, with per-node
results — step through that record, and capture a live HTTP request
to your app the same way.

**Concepts introduced**: the path trace (`Trace path` /
`+ capture values`), the **path** canvas highlight, the **tree**
step-through view, the Operate → Debug «catch next request» trap,
secret redaction in traces.

## Why a record, not breakpoints

Graphden executes lazily: a node runs when something *forces* its
value, cached calls skip their body entirely, and compile-time
values never run at all. A classical pause-at-a-breakpoint debugger
would step through that order — which rarely matches how you read
the graph. So graphden debugs by **recording**: run once with the
trace on, then walk the completed call tree as many times as you
like. Deterministic, shareable (it's stored on the run), and nothing
sits paused holding threads.

## Try it — trace a run

> Prefer to be shown? This lesson exists as a guided in-editor tour:
> [open the demo with the tour running](https://app.graphden.dev/?demo=1&tutorial=27)
> (no sign-up), or pick “Interactive tutorial” in the editor's
> account menu.

Take any composed fn of yours (something with a few refs — the
`slugify` test subject from lesson 26, or any page fn):

1. Open its ▶ **Run** popover.
2. Check **Trace path**. If you also want per-node return values,
   check **+ capture values** — it asks for an explicit confirm with
   a cost estimate (values are stored with the run, up to 4 KB per
   node).
3. Run. The result pane now offers **Show path on canvas** — every
   traversed fn card gets a timing badge (`3× 12ms`, `cache`,
   `secret`), and with values captured, an `= value` chip.
4. Open **History** in the popover header. The traced row carries
   two extra buttons:
   - **path** — the same aggregate canvas highlight;
   - **tree** — the step-through call tree.

## The call tree

The **tree** button opens a side panel: one row per invocation, in
call order, indented by who-called-whom. Each row shows the fn name,
a chip (`12ms` fresh call · `cache` memoised — the body didn't run ·
`secret` hidden), and, for capture-values runs, a collapsible
`value` viewer with that node's return.

- Click a row (or use **◀ ▶** / arrow keys) to step; the selected
  frame's card highlights on the canvas.
- `Esc` or ✕ closes the panel.
- A `trace truncated` note means the run had more frames than the
  caps keep (10 000 entries / 256 KB stored) — the newest frames
  survive.

## Secrets never leak into a trace

Traces obey the same `:secret` rules as results (lesson 07):

- a fn that touches secret-typed data records `secret` — its value
  is never read into the trace at all;
- fns that *consumed* a secret value show `derived from secret`
  instead of a value;
- a fn the type system knows nothing about is hidden too
  (`unknown type`) — no type information means no capture;
- stored traces are re-checked on every read, so making a fn secret
  *after* a run also hides its old recorded values.

## Try it — catch a request

Traces of manual runs cover fns you can call from the Run popover.
For a **web handler** you usually want the real thing: the actual
HTTP request, with its params and headers. That's the trap:

1. Open **Operate → Debug** (account menu → Organization, or the
   `@organization` hash).
2. Optionally type a path prefix (e.g. `/shop`) — empty catches the
   next request to any app path (the editor's own `/api/…` and
   `/partials/…` traffic is excluded so it can't eat the trap).
3. Click **Catch next request**. The panel shows an armed dot; the
   trap is one-shot and expires after 10 minutes.
4. Hit your app — open its page, or `curl` the route.
5. The panel flips to **Last captured request: open call tree** —
   the request ran with the trace on and landed in run history like
   any traced run: the (credential-stripped) request as its
   argument, the response as its result, the full call tree behind
   the button.

Cookies, `Authorization` headers and `Set-Cookie` are stripped
before anything is stored — a captured run never becomes a
credential store.

## Where to go deeper

- [EXECUTION.md § Path trace](../EXECUTION.md) — the stored trace
  shape, caps, and the catch API.
- [SECRETS.md § Path-trace capture](../SECRETS.md) — the redaction
  layers in detail.

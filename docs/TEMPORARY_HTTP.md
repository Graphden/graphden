# Temporary HTTP handlers

A temporary publication gives a graph handler a public URL for 30 minutes.
Each HTTP request invokes the handler once. Publication validates its types
without executing it; it does not create a persistent service, start a listener,
or grant permission to run background processes.

The handler must return `ring-response-shape` and have no unbound argument other
than an optional `request` accepting `ring-request-shape`. Bind its configuration
in the graph. Changes to the handler on the published branch affect subsequent
requests. The stored function and branch UUIDs stay fixed until the publication
is revoked or expires.

## API and cleanup

Authenticated callers use the current branch and these endpoints:

- `POST /api/http-host` with `{"create-id":"UUID","fn-id":"UUID"}` publishes
  a handler. A successful response contains `publication` with `id`, `fn-id`,
  `branch-id`, `expires-at`, and `url`.
- `GET /api/http-host` reports `available` and lists the caller's publications
  on the current branch. A deployment without an authenticated owner reports
  `available:false` and `reason:authentication-required`; configure accounts or
  a static token to publish. Ordinary unauthenticated editing is unchanged.
- `DELETE /api/http-host/UUID` revokes that exact caller-owned publication.
  Repeated cleanup succeeds, including after the branch has been deleted.

Generate and retain `create-id` before sending the create request. It is a
create-only identity; an existing UUID returns an opaque conflict. If a response
is lost, revoke the staged UUID. The delete operation does not rely on names,
and cannot delete another caller's publication or an authentication session.

The platform stores these leases as a non-authenticating kind of the existing
session schema. It registers that schema on installations without accounts,
without enabling sign-in or creating accounts. Account owners must remain active;
token-only installations retain a fingerprint of the configured token, never
the plaintext token. Each request rechecks the current owner, execute permission,
lease deadline, and branch existence. An absent branch never falls back to main.
An account API publication also retains the creating session's UUID: revoking or
expiring that API credential stops access. Current token scopes intersect the
original scope ceiling, so later narrowing cannot be undone by an older lease.
A browser-created publication follows the account and its current grants until
stop or expiry; signing out that particular browser does not alone revoke it.
Scoped API tokens need `execute` to publish or stop a publication. Read-only
tokens may list their owner's publications. A current browser session of the
owner can stop a publication after its creating API token or graph grant was
revoked; cleanup does not execute the handler.

## Public transport

Core serves `/__http/UUID/path` on its existing HTTP origin. Behind TLS this is
an HTTPS URL on that origin; it needs no additional port or certificate. The
tenancy adapter instead reserves an ordinary app route on the configured isolated
apps domain. The lease and app route commit together. Ordinary app management
cannot create or retarget the reserved temporary labels. Temporary routes are
excluded from ordinary Apps lists, counts, previews, and endpoint fallback; their
publication popover owns Stop. Trusted HTTP routing still resolves them.

The URL intentionally addresses a **public** handler. It is not an account
bearer token. Anyone given the URL may invoke it until revocation or expiry;
request URLs may appear in normal proxy/access logs. Do not place credentials in
its path, query string, or responses, and do not treat possession of its random
UUID as private authorization.

The handler receives its method, path, query string, and bounded text body.
Cookies, bearer headers, branch overrides, and live transport handles are removed.
Responses must be realized text or valid JSON, at most 64 KiB, with a non-redirect
HTTP status. Platform headers enforce `nosniff`, a sandboxed CSP, no framing,
no referrer, and no caching. Handler-provided cookies, redirects, CORS, and other
headers are discarded. HTML is only accepted as plain text, never as an HTML page
on the editor origin. Streaming responses are not supported by this finite host.

## Resource and storage contracts

The shared execution pool and organization execution reservations bound request
concurrency. The request deadline is ten seconds. A primitive that ignores an
interrupt keeps its reservation until it actually exits. This is cooperative
cancellation in a shared JVM, not memory or process isolation. Plan effects and
normal outbound network policy still apply; `process` is excluded from this host.
Persistent services retain their existing self-hosted/dedicated-executor policy.
A graph making an HTTP call to its own publication occupies a slot and worker
while the handler needs another. With one organization slot it receives 503;
a saturated shared pool may time out. Resolve the URL, finish Run, then open it
in a browser to exercise the handler sequentially with one slot.

Default publication capacity is 64 globally, two per organization, and two per
owner across organizations. Operators may supply `:http-host-limits` on
`:exec/context` as `{:global N :org N :owner N}` with positive integer values.
Admission and cleanup serialize on one short PostgreSQL advisory transaction
lock shared by all pods. No handler executes while this lock is held.

The lock order is graph writer, branch, global publication capacity, then rows.
Expiry cleanup takes only capacity and row locks; it must never acquire graph
or branch locks. Branch deletion removes matching leases in its transaction.
A platform timer reaps expired leases every minute and on startup. Serving checks
the deadline independently, so delayed cleanup cannot extend access. A process
restart preserves unexpired durable publications and clears expired ones; the
private adapter also removes orphaned reserved app routes.

Tests cover transport policy, non-authenticating session kinds, capacity races,
transaction rollback, graph changes, branch deletion, and current grants. See
`graphden.http-host.*-test` and the tenancy adapter's integration tests.

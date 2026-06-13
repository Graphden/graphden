# Tutorial: Periodic API Poll with User-Owned Storage

Build, end-to-end through the editor UI, a service that:

1. Calls an external HTTP API on a cron schedule
2. Stores each call's status + body in a **separate, user-owned**
   Postgres (not the graphden DB)
3. Exposes a **user-owned HTTP endpoint** that returns the history as JSON

You will write **no Clojure code**. Every piece of behaviour is a
fn-def created through the editor — no API calls by hand, no shell.

## What you'll use

| Piece | Role | Why this and not something else |
|---|---|---|
| `https://httpbin.org/bearer` | Mock external API | Accepts any `Authorization: Bearer <anything>`, echoes the token + `{"authenticated": true}`. Demonstrates the credentials flow without you needing to sign up for anything real. |
| **OpenBao** (KV v2 at `secret/`) | Secrets store | OSS fork of Vault, Linux Foundation. Lets the fn-graph fetch creds via `:secret-leaf` so you never have to use `:env` in user code. |
| **user-postgres** (port `5436`) | Tenant DB | Separate container so the tutorial graph can never touch graphden's own schema. |
| Tutorial port `8081` (exposed as host `9081`) | Where your history endpoint listens | Different port from graphden's `8080` so it's plainly a second service. |

Pre-seeded secrets in OpenBao (set up by `docker-compose`'s
`openbao-seed` one-shot job — see the seeder for the exact paths):

| Path under `secret/` | Value |
|---|---|
| `user-db/url` | `jdbc:postgresql://user-postgres:5432/userdb` |
| `user-db/user` | `userapp` |
| `user-db/password` | `userpass` |
| `api/token` | `fake-token-abc123` |
| `history-port` | `"8081"` |

## Prerequisites

Run once:

```bash
bb rebuild   # builds the uberjar, rebuilds the executor image, restarts
```

After it settles, `docker ps` should show four containers:
`graphden-postgres`, `graphden-user-postgres`, `graphden-openbao`,
`graphden-executor`. (`graphden-openbao-seed` is a one-shot that
exits right after writing the secrets — `docker ps -a` will show
it `Exited (0)`.) Confirm the seeder did its job:

```bash
curl -s -H 'X-Vault-Token: root' \
     http://localhost:8200/v1/secret/data/api/token | jq .data.data.value
# → "fake-token-abc123"
```

Open the editor at <http://localhost:9002> and sign in.

---

## Step 1 — Create the namespace

In the left sidebar's namespace tree, create a namespace
`demo.api-poll`. Every fn-def below lives in that namespace.
Private helpers (the ones starting with `_`) live in the same
namespace; the leading `_` just hides them from the default sidebar
listing.

## Step 2 — Credential lookups (5 fn-defs)

There are two ways to make a secret available to a fn-graph; both
result in a fn-def the rest of the tutorial can `:ref` to. Pick
one — they're interchangeable for everything below.

If you're signed in, the top-of-sidebar **Secrets** section is the
admin entry. The `+` button asks for `{name, path, value}`, writes
the value to OpenBao, and creates a fn-def in graphden whose
`parent` is the `:secret-leaf` base-fn (a pure passthrough — the
executor dereferences the vault path at arg-resolution time so the
impl just returns the value). The created fn-def's return-type is
`[:secret :text]` and the row shows up with a 🔒 in the sidebar.

Create one secret-leaf-shaped fn-def per path:

| Name | parent | Bind |
|---|---|---|
| `_db-url` | `:secret-leaf` | `:path` = `"user-db/url"` |
| `_db-user` | `:secret-leaf` | `:path` = `"user-db/user"` |
| `_db-password` | `:secret-leaf` | `:path` = `"user-db/password"` |
| `_api-token` | `:secret-leaf` | `:path` = `"api/token"` |
| `_history-port-text` | `:secret-leaf` | `:path` = `"history-port"` |

`_history-port-text` returns a string. Wrap it once to get an int:

| Name | parent | Bind |
|---|---|---|
| `_history-port` | `:parse-int` | `:s` = ref `:_history-port-text` |

Click `▶ Run` on each to confirm it returns the seeded value. If
`_db-url` returns the JDBC URL string, vault is working — but the
inline Run result panel will say **🔒 Result hidden** for any of the
`_db-*` / `_api-token` rows. That's not a bug. Those fn-defs inherit
from `:vault-get`, whose return type is `[:secret :text]`; the
executor refuses to surface the actual value to the browser. The
"hidden" status itself confirms vault is reachable — you'll see the
🔒 chip in the History panel for every successful run.

If you need to **see** a value while debugging (e.g. "is the seeded
URL the format I expect?"), temporarily swap the fn-def's parent to
`:const` and re-Run with the same literal you would have stored.
That's the auto-promote direction: plain literals can flow into the
SAME slots secrets do, but the OUTPUT type stays plain so the result
isn't hidden. Swap back to `:vault-get` once you're done.

To compose: any string-op fn-def whose ROOT base-fn carries the
`:secret-if-tainted` propagator (`:str-upper`, `:str-replace`,
`:substring`, …) preserves the secret marker through the result.
`▶ Run` on the composed fn-def is also hidden. That's how the type
system protects you against accidental leaks — `(upper (vault-get …))`
returns a `[:secret :text]`, refusing to flow into anything that
declares its slot as plain `:text` downstream.

## Step 3 — Shared "always pass these creds" helpers

Every SQL call needs `:url`, `:user`, `:password`. Wrap each base
fn once so the rest of the graph can bind only what differs:

| Name | parent | Bind |
|---|---|---|
| `_db-exec` | `:sql-exec` | `:url` = ref `:_db-url`, `:user` = ref `:_db-user`, `:password` = ref `:_db-password` |
| `_db-query` | `:sql-query` | same three refs |

After this, anything that runs SQL inherits the creds for free.
The only free args left on `_db-exec` are `:sql` and `:params` —
exactly what each call site needs to vary.

## Step 4 — Create the table (one-time "migration")

| Name | parent | Bind |
|---|---|---|
| `migrate-up` | `:_db-exec` | `:sql` = `"CREATE TABLE IF NOT EXISTS api_calls (id SERIAL PRIMARY KEY, ts TIMESTAMPTZ DEFAULT now(), status INT, body TEXT)"`, `:params` = `[]` |

Click `▶ Run` on `migrate-up`. The result pane should show `0`
(DDL returns no affected rows). The table now exists in
`userdb` — you've done a migration without touching `psql`.

Re-running `migrate-up` is safe (`IF NOT EXISTS`).

## Step 5 — One poll tick: HTTP call + insert

### 5a. The Authorization header

| Name | parent | Bind |
|---|---|---|
| `_auth-header-value` | `:str` | `:parts` = list `["Bearer " :_api-token]` |
| `_request-headers` | `:assoc-empty` | `:key` = `"Authorization"`, `:value` = ref `:_auth-header-value` |

`_request-headers` now evaluates to
`{"Authorization": "Bearer fake-token-abc123"}`. The `Bearer` is
capitalised because that's what `httpbin.org/bearer` checks for —
the mock API will reject lowercase `bearer` with a 401.

### 5b. The HTTP GET

| Name | parent | Bind |
|---|---|---|
| `_api-response` | `:http-get` | `:url` = `"https://httpbin.org/bearer"`, `:headers` = ref `:_request-headers` |

Run `▶` on it: you should see `{:status 200, :headers {...},
:body "{...\"authenticated\": true...}"}`.

### 5c. Pull the two fields we want to store

The response is a record with **keyword** keys (`:status`, `:body`).
In a fn-def's literal arg, a bare `:body` would be interpreted as
a fn-ref, so we wrap it as `{:value :body}` to mark it as a
literal-keyword value.

| Name | parent | Bind |
|---|---|---|
| `_resp-status` | `:get` | `:coll` = ref `:_api-response`, `:key` = `{:value :status}` |
| `_resp-body` | `:get` | `:coll` = ref `:_api-response`, `:key` = `{:value :body}` |

### 5d. INSERT one row per tick

| Name | parent | Bind |
|---|---|---|
| `poll-tick` | `:_db-exec` | `:sql` = `"INSERT INTO api_calls (status, body) VALUES (?, ?)"`, `:params` = list `[:_resp-status :_resp-body]` |

Hit `▶ Run`. Result should be `1` (one row inserted). Run it a
couple more times and verify with a quick query:

| Name | parent | Bind |
|---|---|---|
| `count-rows` | `:_db-query` | `:sql` = `"SELECT count(*) AS n FROM api_calls"`, `:params` = `[]` |

`▶ Run` `count-rows` → list with one row `{:n 3}` (or whatever you
fired).

## Step 6 — The cron loop

| Name | parent | Bind |
|---|---|---|
| `poll-cron` | `:schedule` | `:cron` = `"0 */1 * * * ?"` (every minute), `:fn` = ref `:poll-tick` |

`poll-cron` has **0 free args** (cron + fn both bound). The `⚙`
icon in its row-actions popover is now enabled — that's how
graphden tells you "this fn is service-eligible".

> While testing, use `"*/15 * * * * ?"` (every 15 seconds) so you
> don't sit around for a minute waiting on the first fire.

## Step 7 — The user-owned history endpoint

A second HTTP server, on port 8081, with one route.

### 7a. The query and its handler

| Name | parent | Bind |
|---|---|---|
| `list-history` | `:_db-query` | `:sql` = `"SELECT id, ts, status, body FROM api_calls ORDER BY ts DESC LIMIT 50"`, `:params` = `[]` |
| `history-handler` | `:json-handler` | `:data` = ref `:list-history` |

### 7b. The route + router

| Name | parent | Bind |
|---|---|---|
| `history-route` | `:get-route` | `:path` = `"/history"`, `:handler` = ref `:history-handler` |
| `history-router` | `:text-error-router` | `:routes` = list `[:history-route]` |

### 7c. The second HTTP server

| Name | parent | Bind |
|---|---|---|
| `history-server` | `:http-server` | `:handler` = ref `:history-router`, `:port` = ref `:_history-port` |

`history-server` is also 0-free-args, so `⚙` lights up here too.

## Step 8 — Declare both as services and restart

For **both** `poll-cron` and `history-server`:

1. Click the row's `⋯` → click `⚙ Service settings`.
2. In the popover: check **Enabled**, leave restart-policy `always`,
   click **Save**.
3. You'll see a confirmation; a small `●` service-badge appears on
   the fn's root row.

Now the `:service` rows exist in graphden's DB but they're not
running yet. One restart picks them up:

```bash
bb rebuild
```

(Or just `docker compose restart executor` — faster if you haven't
edited any Clojure code.)

The reconciler's init-key reads enabled service rows on startup
and launches each. Tail the logs:

```bash
docker logs -f graphden-executor 2>&1 | grep -iE "service|reconc|poll-cron|history-server"
```

Within seconds you should see both services starting and `poll-cron`
firing its first tick.

## Step 9 — Verify

### Hit the user-owned endpoint

```bash
curl -s http://localhost:9081/history | jq .
```

You should see an array of rows, latest first:

```json
[
  {"id": 4, "ts": "2026-05-27T...", "status": 200, "body": "{...}"},
  {"id": 3, "ts": "2026-05-27T...", "status": 200, "body": "{...}"},
  ...
]
```

### Wait one tick and refresh

Re-running the curl after a minute (or 15 seconds if you went with
the shorter cron) should show a new row at the top — proof that
the cron is firing and your INSERT is landing.

### Peek directly at the user DB (optional, for confidence)

```bash
docker exec -it graphden-user-postgres \
  psql -U userapp -d userdb -c \
  "SELECT id, ts, status, length(body) AS body_bytes FROM api_calls ORDER BY ts DESC LIMIT 5"
```

You're querying the **tenant** DB, not graphden's.

---

## What you did NOT do

- **No** call to `/api/executions`, `/api/entities/*`, or any
  graphden HTTP API. The history-view endpoint is one **you**
  built and own.
- **No** read from `:env`. Every secret travelled through
  `:vault-get`; the vault root token lives in graphden's
  infrastructure config (`system-*.edn` → `:vault/client`),
  invisible to your fn-graph.
- **No** edit to a Clojure file. Every piece of behaviour is a
  fn-def in `demo.api-poll`.

## Cleanup

Disable the two services (uncheck **Enabled** in the `⚙` popover)
and `bb rebuild` — the reconciler stops them. To wipe the table:

| Name | parent | Bind |
|---|---|---|
| `migrate-down` | `:_db-exec` | `:sql` = `"DROP TABLE IF EXISTS api_calls"`, `:params` = `[]` |

`▶ Run` once. Done.

## Where things live in the source

- New base-fns: `resources/packages/web/{vault,http-client,sql}/`
- `:parse-int`: `resources/packages/core/system/`
- Vault client wiring: `src/graphden/system/core.clj` →
  `:vault/client` init-key, `:exec/context` consumes it
- Docker setup: `docker-compose.yml` (`user-postgres`, `openbao`,
  `openbao-seed`)

When this pattern grows past tutorial scope (real creds, real API,
multi-pod), the cron-as-`:fn-execution` story in
`docs/SERVICES.md § Roadmap` is the next thing to read.

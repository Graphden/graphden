# Browser Test Tool

Automated browser testing for Graphden editor using Playwright. Captures screenshots and console output.

## Setup

```bash
cd tools/browser-test
npm install
```

## Usage

```bash
# Basic: view a function's graph
node check-editor.js web-server

# Expand root node to level 1
node check-editor.js web-server root:1

# Expand root to level 2
node check-editor.js web-server root:2

# Expand multiple nodes
node check-editor.js web-server root:1 router-fn:1

# View without selecting a function
node check-editor.js
```

## Expand Spec Format

`node-name:level` where:

- `node-name` - the name of the node (use `root` for the root/selected function)
- `level` - how many ancestor levels to expand (1, 2, 3, ...)

## Output

- Screenshot saved to: `/tmp/editor-screenshot.png`
- Console output printed to terminal
- Build timestamp shown for deployment verification
- Errors highlighted in output

## Requirements

- Node.js
- Playwright with Chromium
- Graphden server running on `http://localhost:9002`

## Editor-edit e2e suite

The `edit-*.test.js` files exercise the inline graph-editing affordances
(re-parent cascade, sequence add/remove, namespace-move). Each script
exits 0 on PASS, non-zero on FAIL — assertions are inline via the
helpers in `edit-test-helpers.js`.

Requires `AUTH_TOKEN=<token> bb rebuild` so the dev container's
admin password matches what the tests put into `localStorage`. With
the default `test123` token:

```bash
AUTH_TOKEN=test123 bb rebuild        # one-time, sets the container token
cd tools/browser-test
./run-edit-tests.sh                  # runs the whole suite, exits non-zero on fail
node edit-phase3-reparent.test.js    # or run one
```

Override `AUTH_TOKEN` / `GRAPHDEN_URL` via env vars to point at a
different deployment. Test fns are named `test-edit-phase*` and are
created/cleaned per-run, so it is safe to run against a non-pristine
graph.

## Manual smokes (not in any runner)

- `contact-demo-smoke.js` — end-to-end smoke for the `/demo/contact`
  page (runtime + built-in `submit-form` handler). Run by hand:
  `node contact-demo-smoke.js`.
Nothing else. Every `*.test.js` here is `edit-`-prefixed and runs in
`./run-edit-tests.sh` — a file outside that glob runs in no runner at all,
which is how `regression-*.test.js` and `type-system-ui-*.test.js` sat dead
until the 2026-08-22 test audit. If a new test cannot fit this suite, it
belongs in `tools/runtime-test/` under `bb test-js`.

## Queue lesson (needs a persistent worker)

Lesson 39 performs real retries, stops the worker before requeuing the exact
message UUID, repairs its handler, verifies the linked successful downstream
execution and ACK, then removes the exact service and scratch branch. Run the
required native proof on an isolated self-host or dedicated executor:

```bash
GRAPHDEN_URL=http://127.0.0.1:<candidate-port> \
  GRAPHDEN_REQUIRE_QUEUE_SERVICES=1 \
  node tools/browser-test/edit-tutorial-tour-queues.test.js
```

A dedicated tenancy run uses the existing account-session authentication
described below. With the required flag, unavailable controls or capabilities
fail. Without it, an actual shared-cloud quota plan may report SKIP because it
has no persistent executor; that result does not verify the worker loop.
Failed quota reads never produce SKIP. The ordinary suite discovers this
`edit-*.test.js` automatically.

## Organization lessons (needs a tenancy stack)

`edit-tutorial-tour-org.test.js` walks tutorial lessons 27 / 28 / 29 / 30 / 33 / 36 / 37
— Members, Grants, roles, Apps, cross-org, account Settings, plans. Those surfaces
exist only under the tenancy addon, so the file SKIPS (exit 0, loudly) unless
you point it at a stack that has one:

```bash
GRAPHDEN_URL=http://localhost:8080 \
GRAPHDEN_ORG_EMAIL=you@example.com GRAPHDEN_ORG_PASSWORD=… \
  node edit-tutorial-tour-org.test.js
```

The whole thing is one task from the monorepo: `bb test-e2e-org` boots the
local tenancy stack from the sibling `graphden-cloud` checkout (`bb gdcloud-up`
there — the release image, no rebuild), signs up + verifies a throwaway
account through the executor log (no mailer needed) and runs the guard signed
in as it. It is not part of `bb ci` (a second image); run it after a release
or a tour edit.

The account must be an org OWNER — a fresh signup is one, since its first login
creates the personal org it owns. The local cloud-shaped stack (boot
`graphden-cloud` against local checkouts) is the usual target; run this before a
cloud release, since the monorepo gate's e2e stack is single-tenant and cannot
reach any of it.

Every other `edit-tutorial-tour*.test.js` runs against a single-tenant stack and
takes `GRAPHDEN_VIEWPORT=390x844` to walk the same lessons at a phone size.

## Personal UI graphs and account tokens (lessons 25 / 42)

Run against the candidate instance built by the worktree/release workflow.
`bb gdcloud-up` uses the existing **release** image, so it does not prove an
unreleased candidate. The paired tenancy checkout's `../graphden` must point at
that same public source when building it. Do not use the legacy `bb rebuild`
examples above from a claimed worktree.

On an isolated single-tenant stack, run the real personal UI graph loop:

```bash
GRAPHDEN_URL=http://127.0.0.1:<self-host-port> \
  node tools/browser-test/edit-ui-components.test.js
```

It selects the writable `(root)` namespace. Lesson 42 requires the tenancy
addon and an account session; its optional self-host SKIP is an unsupported
capability result, **not** evidence of creating/revoking scoped tokens.

On the isolated tenancy candidate, use a verified, non-operator account with
its own organization. Existing `/auth/login` produces the genuine browser
session; an instance admin bearer or API token is not a substitute. Provision
and verify the throwaway account through the existing local account workflow
(`graphden-cloud/dev/gdcloud/gdcloud.sh account`) only when targeting its local
9900 stack, or through the candidate's normal signup/verification flow.

For lesson 25 select an existing, authorized personal namespace, for example
`users.<account UUID>` with the production personal-namespace grant store.
The grant does not itself create namespace rows. Create a missing fixture
through ordinary namespace CRUD/UI in the account's organization, retain its
exact UUIDs and delete only newly created empty fixture rows after the tests.
Set `GRAPHDEN_COMPONENT_NAMESPACE` to its exact displayed path. Creation still
passes the real server preview/apply authorization and quota checks; no grant
is broadened by the browser harness.

With `GRAPHDEN_URL`, `GRAPHDEN_ORG_EMAIL`, `GRAPHDEN_ORG_PASSWORD`, and
`GRAPHDEN_COMPONENT_NAMESPACE` already supplied securely, this runs both
native loops without writing or printing the session cookie:

```bash
cd tools/browser-test
node - <<'NODE'
const {request} = require('playwright');
const {spawnSync} = require('node:child_process');
(async () => {
  const base = process.env.GRAPHDEN_URL;
  if (!base || !process.env.GRAPHDEN_COMPONENT_NAMESPACE) throw new Error();
  const ctx = await request.newContext({baseURL: base});
  try {
    const login = await ctx.post('/auth/login', {data: {
      email: process.env.GRAPHDEN_ORG_EMAIL,
      password: process.env.GRAPHDEN_ORG_PASSWORD,
    }});
    if (!login.ok()) throw new Error();
    const me = await ctx.get('/auth/me');
    if (!me.ok() || !(await me.json()).account?.id) throw new Error();
    const cookie = (await ctx.storageState()).cookies.find(row => row.name === 'gd_session');
    if (!cookie) throw new Error();
    const env = {...process.env, GRAPHDEN_SESSION_COOKIE: cookie.value,
      GRAPHDEN_REQUIRE_TOKENS: '1'};
    delete env.AUTH_TOKEN;
    delete env.GRAPHDEN_JS_COVERAGE;
    for (const spec of ['edit-ui-components.test.js', 'edit-tutorial-token-lifecycle.test.js']) {
      const result = spawnSync(process.execPath, [spec], {env, stdio: 'inherit'});
      if (result.status !== 0) throw new Error();
    }
  } finally { await ctx.dispose(); }
})().catch(() => { console.error('Required account lesson verification failed'); process.exitCode = 1; });
NODE
```

Cookie mode authenticates browser and Node API/readiness requests with the
same `gd_session`, and removes the static bearer from browser storage.
`GRAPHDEN_REQUIRE_TOKENS=1` fails if the cookie, signed-in tenancy UI, or token
listing is missing; cloud verification cannot pass by SKIP. Only the lesson's
explicit scope/revocation probes use its newly minted bearer. The token runner
records no trace/HAR/video, omits exception details, and never exports the
one-time bearer to Node, screenshots, or Lessons state. Keep token verification
separate from runners that persist network traces or retry artifacts.

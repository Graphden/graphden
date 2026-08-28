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

## Organization lessons (needs a tenancy stack)

`edit-tutorial-tour-org.test.js` walks tutorial lessons 23 / 24 / 26 / 29 / 32 / 33
— Members, Grants, Apps, cross-org, account Settings, plans. Those surfaces
exist only under the tenancy addon, so the file SKIPS (exit 0, loudly) unless
you point it at a stack that has one:

```bash
GRAPHDEN_URL=http://localhost:8080 \
GRAPHDEN_ORG_EMAIL=you@example.com GRAPHDEN_ORG_PASSWORD=… \
  node edit-tutorial-tour-org.test.js
```

The account must be an org OWNER — a fresh signup is one, since its first login
creates the personal org it owns. The local cloud-shaped stack (boot
`graphden-cloud` against local checkouts) is the usual target; run this before a
cloud release, since the monorepo gate's e2e stack is single-tenant and cannot
reach any of it.

Every other `edit-tutorial-tour*.test.js` runs against a single-tenant stack and
takes `GRAPHDEN_VIEWPORT=390x844` to walk the same lessons at a phone size.

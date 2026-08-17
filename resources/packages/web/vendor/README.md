# Vendored third-party assets

## htmx.min.js

- Source: <https://unpkg.com/htmx.org@2.0.4/dist/htmx.min.js>
- Version: 2.0.4
- License: BSD Zero Clause (0BSD) — <https://github.com/bigskysoftware/htmx/blob/master/LICENSE>
- Served at `GET /assets/htmx.min.js` (1-year immutable cache,
  hash-busted via `?v=<frontend-build-hash>`); consumed by the editor
  page's `<head>` and by tenant pages via `:with-htmx` (app.page).
- To upgrade: replace the file, update the version here, and re-run
  the e2e suite (the editor's `/partials/*` fragments exercise it).

## htmx-ext-sse.min.js

- Source: <https://unpkg.com/htmx-ext-sse@2.2.3/dist/sse.min.js>
- Version: 2.2.3
- License: BSD Zero Clause (0BSD) — same repo as htmx.
- Served at `GET /assets/htmx-ext-sse.min.js`; consumed by tenant
  pages via `:with-htmx-sse` (app.page) for `:sse-connect-attrs`
  elements.

Vendored (not CDN) so deployments carry no third-party runtime
dependency: air-gapped installs work, the version is pinned by the
repo, and the supply chain ends at this checkout.

# Vendored third-party assets

## htmx.min.js

- Source: <https://unpkg.com/htmx.org@2.0.4/dist/htmx.min.js>
- Version: 2.0.4
- License: BSD Zero Clause (0BSD) — [the matching vendored license](htmx.LICENSE).
  The JavaScript and license come from the same `htmx.org@2.0.4` npm archive.
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

## preact.min.js

- Source: npm `preact@11.0.0`, bundled by the existing
  `tools/vendor-build/` tooling (`npm ci && npm run build:preact`).
- License: MIT; the upstream text is preserved in `preact.LICENSE`.
- Exposes `window.GraphdenPreact.h` and `.render`. Component definitions,
  state transitions and styles remain ordinary Graphden functions; the
  library only reconciles their computed element trees with the DOM.
- Included in the editor's existing hashed bundle. No CDN or new
  application compilation step is required.
- To upgrade: pin the new version in the vendor-build lockfile, rebuild
  this bundle, update the license and run the renderer/browser checks.

## codemirror.min.js

- Source: built from npm packages by `tools/vendor-build/`
  (`npm install && npm run build`); versions pinned in its
  `package.json` (@codemirror/* 6.x: state 6.7.6, view 6.43.14,
  merge 6.12.2; @nextjournal/lang-clojure 1.0.0; esbuild 0.28.2).
- License: MIT (@codemirror/*, @lezer/*) + ISC (@nextjournal/*).
- Exposes `window.CM` — EditorView/EditorState/MergeView, the standard
  keymaps/extensions, and `CM.langs` {javascript, css, json, clojure}.
- Served at `GET /assets/codemirror.min.js` (1-year immutable cache,
  `?v=` hash-bust); consumed by `editor-code.js` to upgrade the
  code-editing textareas (Assets panel, value-form js-source /
  css-source / edn / json) into real editors.
- To upgrade: bump versions in `tools/vendor-build/package.json`,
  rebuild, update this entry.

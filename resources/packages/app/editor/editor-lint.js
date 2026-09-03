// Editor — Lint diagnostics-drawer section.
//
// Server-rendered via GET /partials/lint: the CURRENT branch's graph-lint
// warnings (docs/GRAPH_LINT.md) — definitions that already exist
// elsewhere (exactly, or once private helpers are inlined) and private
// fn-defs nothing references. Graph-first: the markup, the "Not an issue"
// button (POST /partials/lint/suppress, swapping the refreshed body back
// in) and the restorable hidden list are all fn-defs returning hiccup;
// this module only builds the section shell and lazy-loads the panel via
// hx-get. Fn names are plain #hash links, so navigation rides the
// editor's native hashchange handling — no JS here.
//
// Shown to authenticated users (the partial route is auth-required).
// NOT tenancy-gated — the lint exists in single-tenant too. Mirrors
// editor-type-errors.js; the caller (editor-sidebar.js
// mountAdminSection) runs htmx.process after appending, so the hx-get
// on a CONNECTED node fires.
//
// Globals consumed: isAuthenticated, htmx.

function buildLintSection() {
  if (!isAuthenticated()) return null;
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-lint';
  wrap.innerHTML = ''
    + '<div class="ns-children" hx-get="/partials/lint" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  return wrap;
}

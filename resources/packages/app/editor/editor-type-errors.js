// Editor — Type errors sidebar section (error-tolerance Phase 3).
//
// Server-rendered via GET /partials/type-errors: the CURRENT branch's
// recorded type diagnostics (the per-branch in-memory store every
// post-write check keeps fresh), one row per diagnostic — fn name,
// arg, short message. Graph-first — the markup is a fn-def returning
// hiccup; this module only builds the collapsible section shell and
// lazy-loads the panel via hx-get. Fn names are plain #hash links, so
// navigation rides the editor's native hashchange handling — no JS
// here.
//
// Shown to authenticated users (the partial route is auth-required).
// NOT tenancy-gated — type diagnostics exist in single-tenant too.
// Mirrors editor-errors.js; the caller (editor-sidebar.js
// mountAdminSection) runs htmx.process after appending, so the hx-get
// on a CONNECTED node fires.
//
// Globals consumed: isAuthenticated, htmx.

function buildTypeErrorsSection() {
  if (!isAuthenticated()) return null;
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-type-errors';
  wrap.innerHTML = ''
    + '<div class="ns-children" hx-get="/partials/type-errors" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  return wrap;
}

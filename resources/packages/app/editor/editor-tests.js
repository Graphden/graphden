// Editor — Tests: the `tests` namespace convention (Roadmap Block 3.1).
//
// A test is a named, non-`_`-prefixed fn in a namespace whose dotted
// path contains the segment `tests` — the JS mirror of the server
// predicate `graphden.crud.test-runs/test-ns-path?` (keep the two in
// sync; segment match, never substring). This module owns:
//   - the client status cache primed from GET /api/tests/status
//     (accessors the sidebar reads sync'ly for the ✓/✗ dot + the
//     `tests` lens chip count), and
//   - the Operate → Tests section shell (server-rendered panel via
//     GET /partials/tests, the editor-type-errors.js pattern) plus
//     the Run-all action (POST /api/tests/run, then re-prime + swap
//     a fresh panel).
//
// Globals consumed: isAuthenticated, authFetch, window.API, htmx.

// {fn-id → {status, error, …}} from the last prime; null until loaded.
let _testStatuses = null;

function isTestNsPath(path) {
  if (!path) return false;
  return String(path).split('.').includes('tests');
}

// Membership predicate the sidebar's fnKindSet reads. Mirrors the
// server: named, not `_`-private, test namespace.
function isTestFn(fn) {
  if (!fn?.name || String(fn.name).startsWith('_')) return false;
  return typeof getFnNamespace === 'function' && isTestNsPath(getFnNamespace(fn));
}

function getTestStatusForFnId(fnId) {
  return _testStatuses ? (_testStatuses.get(fnId) || null) : null;
}

function getTestStatusCount() {
  return _testStatuses ? _testStatuses.size : null;
}

function loadTestStatuses() {
  if (!(window.API && API.api_tests_status)) return Promise.resolve(null);
  return authFetch(API.api_tests_status)
    .then((r) => (r.ok ? r.json() : []))
    .then((rows) => {
      _testStatuses = new Map((rows || []).map((row) => [row['fn-id'], row]));
      return _testStatuses;
    })
    .catch(() => null);
}

// Operate → Tests section shell (mounted by editor-sidebar.js's
// mountAdminSection; re-fetched on every Operate open via
// reloadDynamicOpsSections — statuses drift as tests run).
function buildTestsSection() {
  if (!isAuthenticated()) return null;
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-tests';
  wrap.innerHTML = ''
    + '<div class="ns-children" hx-get="/partials/tests" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  return wrap;
}

// Run-all lifecycle — the panel's markup is server hiccup; JS owns only
// the click → POST → refresh cycle (the JSON API stays the single run
// entry point for the editor, MCP and scripts alike).
document.addEventListener('click', (ev) => {
  const btn = ev.target.closest('#gd-tests-run-all');
  if (!btn || btn.disabled) return;
  if (!(window.API && API.api_tests_run)) return;
  btn.disabled = true;
  btn.textContent = 'Running…';
  authFetch(API.api_tests_run, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
    .catch(() => null)
    .then(() => loadTestStatuses())
    .then(() => {
      // Fresh dots in the tree + a fresh panel (counts, per-row status).
      if (typeof repaintAfterPrime === 'function') repaintAfterPrime();
      const host = btn.closest('.sidebar-tests');
      if (host && typeof buildTestsSection === 'function' && window.htmx) {
        const fresh = buildTestsSection();
        if (fresh) {
          const freshChild = fresh.querySelector('.ns-children');
          const old = host.querySelector('.ns-children');
          if (freshChild && old) {
            old.replaceWith(freshChild);
            window.htmx.process(freshChild);
          }
        }
      }
    });
});

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

// --- live panel transport: SSE ping + one-shot re-fetch -------------
// EventSource can't carry the Authorization / X-Graphden-Branch
// headers the editor's auth + branch model rides on (self-host auth
// is Bearer-only), so the subscription streams over fetch: the
// monkey-patched window.fetch (editor-branches.js) adds the branch
// header and authFetch stacks the Bearer. The stream carries only a
// PING (see :_tests-stream-handler) — on each ping the client
// re-fetches the always-fresh one-shot partial, coalescing while a
// fetch is in flight.
// graph-first-exception: transport plumbing only — every byte the
// panel shows is server-rendered hiccup.

let _testsRefreshInFlight = false;
function refreshTestsPanel(el) {
  if (_testsRefreshInFlight) return;
  _testsRefreshInFlight = true;
  authFetch('/partials/tests')
    .then((r) => (r.ok ? r.text() : null))
    .then((html) => {
      if (html && el.isConnected) {
        el.innerHTML = html;
        el.dataset.testsLive = '1';
      }
    })
    .catch(() => null)
    .then(() => { _testsRefreshInFlight = false; });
}

async function connectTestsStream(el) {
  const finish = (delayMs) => {
    // Detached panel (Operate re-open rebuilt the section) → stop; the
    // server's max-lifetime close bounds an orphaned reader anyway.
    if (el.isConnected) setTimeout(() => connectTestsStream(el), delayMs);
  };
  try {
    const r = await authFetch('/partials/tests-stream',
                              { headers: { Accept: 'text/event-stream' } });
    if (!r.ok || !r.body) { refreshTestsPanel(el); finish(15000); return; }
    const reader = r.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let idx = buf.indexOf('\n\n');
      while (idx >= 0) {
        const frame = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        idx = buf.indexOf('\n\n');
        if (frame.includes('event: close')) {
          reader.cancel().catch(() => {});
        } else if (frame.includes('data:')) {
          // Any ping = "something may have changed" — re-fetch the
          // always-fresh one-shot panel.
          refreshTestsPanel(el);
        }
      }
      if (!el.isConnected) { reader.cancel().catch(() => {}); return; }
    }
    // Clean end (server lifetime close) — reconnect promptly.
    finish(1000);
  } catch (_) {
    refreshTestsPanel(el);
    finish(15000);
  }
}

// Operate → Tests section shell (mounted by editor-sidebar.js's
// mountAdminSection; rebuilt on every Operate open via
// reloadDynamicOpsSections). LIVE: the panel body arrives over the
// SSE stream (/partials/tests-stream — write-wakes + 30 s keepalive,
// changed-only pushes; the stream ticks once at open, so the first
// paint is immediate). The rebuilt-on-reopen node detaches the old
// one, whose reader then aborts via the isConnected watch.
function buildTestsSection() {
  if (!isAuthenticated()) return null;
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-tests';
  const el = document.createElement('div');
  el.className = 'ns-children';
  el.innerHTML = '<div class="loading">Loading…</div>';
  wrap.appendChild(el);
  // Connect after mountAdminSection has appended the section.
  setTimeout(() => { if (el.isConnected) connectTestsStream(el); }, 0);
  return wrap;
}

// Run-all lifecycle — the panel's markup is server hiccup; JS owns only
// the click → POST → re-prime cycle (the JSON API stays the single run
// entry point for the editor, MCP and scripts alike). The PANEL itself
// refreshes via the SSE push run-tests! nudges (the pushed body carries
// a fresh, enabled button).
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
      // Fresh dots in the tree; the panel body lands via SSE.
      if (typeof repaintAfterPrime === 'function') repaintAfterPrime();
      // Belt-and-braces: if the stream is down (proxy without SSE),
      // the pushed replacement never arrives — restore the button so
      // the panel stays usable.
      setTimeout(() => {
        const b = document.querySelector('#gd-tests-run-all');
        if (b?.disabled) { b.disabled = false; b.textContent = 'Run all tests'; }
      }, 4000);
    });
});

// Editor — Tests: the `tests` namespace convention (Roadmap Block 3.1).
//
// A test is a named, non-`_`-prefixed fn in a namespace whose dotted
// path contains the segment `tests` — the JS mirror of the server
// predicate `graphden.crud.test-runs/test-ns-path?` (keep the two in
// sync; segment match, never substring). This module owns:
//   - the client status cache primed from GET /api/tests/status
//     (accessors the sidebar reads sync'ly for the ✓/✗ dot + the
//     `tests` lens chip count),
//   - the ✓ tests lens's LIVE signal: while the lens is active a ping
//     stream (GET /partials/tests-stream) stays open and every ping
//     re-primes the cache, so the Explorer's dots move as runs land,
//   - the lens's [Run all] action (POST /api/tests/run) and the
//     re-prime after the Inspector's [Run this test] (a server-rendered
//     htmx section — app/editor-provenance `_insp-test-*`).
// The drawer's Tests panel this module used to host was retired
// 2026-09-04: the lens + Inspector are the surface.
//
// Globals consumed: isAuthenticated, authFetch, window.API, lensKinds,
// repaintAfterPrime, loadProblemCaches.

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

// Re-prime + repaint: the dots in the tree, the chip count, the
// [Run all] visibility. One coalesced pass per trigger.
let _testsReprimeInFlight = false;
function reprimeTestStatuses() {
  if (_testsReprimeInFlight) return Promise.resolve(null);
  _testsReprimeInFlight = true;
  return loadTestStatuses()
    .then(() => {
      // A ping can land before the first graph load (the lens persisted
      // active across a reload) — nothing to repaint yet; the per-graph
      // prime paints the dots when the tree exists.
      if (typeof repaintAfterPrime === 'function' && typeof graphData !== 'undefined' && graphData) repaintAfterPrime();
    })
    .catch(() => null)
    .then(() => { _testsReprimeInFlight = false; });
}

// --- the lens's live signal: SSE ping over fetch ----------------------
// EventSource can't carry the Authorization / X-Graphden-Branch
// headers the editor's auth + branch model rides on (self-host auth
// is Bearer-only), so the subscription streams over fetch: the
// monkey-patched window.fetch (editor-branches.js) adds the branch
// header and authFetch stacks the Bearer. The stream carries only a
// PING (see :_tests-stream-handler) — each one means "a run may have
// landed": re-prime the status cache.
//
// Open only while the ✓ tests lens is active: an always-open request
// on every editor page would starve networkidle-style waiters (it
// broke unrelated e2e specs' page.goto). The loop ends by itself once
// the lens is off — checked at every frame and before each reconnect.
// graph-first-exception: transport plumbing only — every fact shown
// is the server's status row.

let _testsStreaming = false;
function testsLensActive() {
  return typeof lensKinds !== 'undefined' && lensKinds.has('tests');
}

async function connectTestsStream() {
  const finish = (delayMs) => {
    _testsStreaming = false;
    if (testsLensActive()) setTimeout(ensureTestsStream, delayMs);
  };
  try {
    const r = await authFetch('/partials/tests-stream',
                              { headers: { Accept: 'text/event-stream' } });
    if (!r.ok || !r.body) { finish(15000); return; }
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
          reprimeTestStatuses();
        }
      }
      if (!testsLensActive()) { reader.cancel().catch(() => {}); _testsStreaming = false; return; }
    }
    // Clean end (server lifetime close) — reconnect promptly.
    finish(1000);
  } catch (_) {
    finish(15000);
  }
}

// Called from syncKindFilterBar on every lens change: opens the stream
// once while the lens is active; a lens switched off lets the loop end.
function ensureTestsStream() {
  if (!testsLensActive() || _testsStreaming) return;
  if (typeof isAuthenticated === 'function' && !isAuthenticated()) return;
  _testsStreaming = true;
  reprimeTestStatuses();
  connectTestsStream();
}

// --- actions --------------------------------------------------------
// [Run all] — the ✓ lens's action, shown next to the chips while the
// lens is active (editor/fns.edn `#tests-run-all-btn`, visibility in
// syncKindFilterBar). JS owns only the click → POST → re-prime cycle;
// the JSON API stays the single run entry point for the editor, MCP
// and scripts alike. Per-test pings move the dots while the suite runs.
function gdRunAllTests(btn) {
  if (!btn || btn.disabled) return;
  if (!(window.API && API.api_tests_run)) return;
  const label = btn.querySelector('.kind-label');
  const idle = label ? label.textContent : '';
  btn.disabled = true;
  if (label) label.textContent = 'Running…';
  authFetch(API.api_tests_run, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
    .catch(() => null)
    .then(() => reprimeTestStatuses())
    .then(() => (typeof loadProblemCaches === 'function' ? loadProblemCaches() : null))
    .catch(() => null)
    .then(() => {
      btn.disabled = false;
      if (label) label.textContent = idle;
    });
}
window.gdRunAllTests = gdRunAllTests;

// [Run this test] in the Inspector is an htmx POST whose response is the
// refreshed section, rendered from the run's own result. The Explorer's
// dot reads the status JOIN, whose terminal row lands asynchronously —
// re-prime after the settle delay the runner's second nudge uses.
document.addEventListener('htmx:afterSwap', (ev) => {
  const target = ev.target;
  if (!target?.classList?.contains('gd-insp-test')) return;
  reprimeTestStatuses();
  setTimeout(reprimeTestStatuses, 2500);
});

// Editor — the «catch next request» trap (crud.debug-capture; POST
// /api/debug/catch family), on the Inspector's Runs tab of a SERVICE fn.
//
// Server owns the block markup (`GET /partials/debug-catch`: status
// line / arm form shell / «open last captured trace»), composed into
// the Runs-tab history partial as `.gd-run-trap` for a fn that is a
// service on the branch (app/editor-execute `_peh-trap-block`). This
// module owns the action lifecycle:
//   * Arm — read the form inputs, POST /api/debug/catch, refresh the block;
//   * Cancel — POST /api/debug/catch/cancel, refresh;
//   * open last captured trace — delegate to openTraceView
//     (editor-trace-view.js);
//   * a light status poll while the block is visible AND a trap is
//     armed, so it flips to «last captured» soon after the request
//     fires — and the Runs history re-mounts then, so the captured
//     request shows up as a run with its tree (no SSE stream — the
//     trap is short-lived by design, polling every few seconds for
//     its TTL is cheaper than a stream).
// (The drawer's Debug panel this module used to host was retired
// 2026-09-04 with the drawer.)
//
// Globals consumed: authFetch, openTraceView.

let _debugRefreshInFlight = false;
let _debugRefreshQueued = false;
let _debugPollTimer = null;


function _debugPanelEl() {
  return document.querySelector('#gd-insp-runs .gd-run-trap');
}


function refreshDebugPanel() {
  const el = _debugPanelEl();
  if (!el) return;
  if (_debugRefreshInFlight) {
    // Don't DROP a refresh that races an in-flight one (e.g. the arm
    // POST completing while a poll is still loading) — queue one
    // trailing re-fetch so the block always ends on current state.
    _debugRefreshQueued = true;
    return;
  }
  _debugRefreshInFlight = true;
  const wasArmed = !!el.querySelector('.debug-armed-line');
  authFetch('/partials/debug-catch')
    .then((r) => (r.ok ? r.text() : null))
    .then((html) => {
      if (html && el.isConnected) el.innerHTML = html;
      // Armed → captured: the request landed as a run — re-mount the
      // history so its row (with the tree button) is on the tab.
      if (wasArmed && el.isConnected && !el.querySelector('.debug-armed-line')
          && el.querySelector('.debug-last-capture')) {
        document.getElementById('gd-insp-runs')?.dispatchEvent(new Event('gd-refresh-runs'));
      }
    })
    .catch(() => null)
    .then(() => {
      _debugRefreshInFlight = false;
      if (_debugRefreshQueued) {
        _debugRefreshQueued = false;
        refreshDebugPanel();
        return;
      }
      _debugSchedulePoll();
    });
}


// Poll only while: block visible + a trap is armed (the block body
// carries the armed line). Stops by itself once disarmed/captured.
function _debugSchedulePoll() {
  if (_debugPollTimer) { clearTimeout(_debugPollTimer); _debugPollTimer = null; }
  const el = _debugPanelEl();
  if (!el?.isConnected || el.offsetParent === null) return;
  if (!el.querySelector('.debug-armed-line')) return;
  _debugPollTimer = setTimeout(refreshDebugPanel, 4000);
}


// The Runs tab just mounted its history panel — if it carries the trap
// block and a trap is armed, start polling (editor-execute-history.js).
function gdDebugTrapMounted() {
  setTimeout(_debugSchedulePoll, 0);
}
window.gdDebugTrapMounted = gdDebugTrapMounted;


// Delegated actions — the block body is re-swapped on every refresh,
// so handlers bind at the document level by id.
document.addEventListener('click', (ev) => {
  const armBtn = ev.target.closest('#gd-debug-arm');
  if (armBtn && !armBtn.disabled) {
    armBtn.disabled = true;
    const prefix = document.getElementById('gd-debug-prefix')?.value || null;
    const capture = !!document.getElementById('gd-debug-capture-values')?.checked;
    const arm = () => authFetch('/api/debug/catch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ 'path-prefix': prefix, 'capture-values': capture }),
    }).catch(() => null).then(refreshDebugPanel);
    // Value capture inherits the Run popover's explicit-confirm
    // doctrine (PHILOSOPHY § Debugging constraint 3).
    if (capture && !window.confirm(
      'Capture intermediate VALUES of the caught request?\n'
      + 'Every non-secret node\'s return value (up to 4 KB each) will be '
      + 'stored with the run.')) {
      armBtn.disabled = false;
      return;
    }
    arm();
    return;
  }
  const cancelBtn = ev.target.closest('#gd-debug-cancel');
  if (cancelBtn && !cancelBtn.disabled) {
    cancelBtn.disabled = true;
    authFetch('/api/debug/catch/cancel', { method: 'POST' })
      .catch(() => null)
      .then(refreshDebugPanel);
    return;
  }
  const openBtn = ev.target.closest('#gd-debug-open-trace');
  if (openBtn && typeof openTraceView === 'function') {
    openTraceView(openBtn.getAttribute('data-execution-id'));
  }
});

// Editor — diagnostics drawer → Debug: the «catch next request» trap
// (crud.debug-capture; POST /api/debug/catch family).
//
// Server owns the panel markup (`GET /partials/debug-catch`: status
// line / arm form shell / «open last captured trace»). This module
// owns the section shell + the action lifecycle:
//   * Arm — read the form inputs, POST /api/debug/catch, refresh;
//   * Cancel — POST /api/debug/catch/cancel, refresh;
//   * open last captured trace — delegate to openTraceView
//     (editor-trace-view.js);
//   * a light status poll while the section is visible AND a trap is
//     armed, so the panel flips to «last captured» soon after the
//     request fires (no SSE stream — the trap is short-lived by
//     design, polling every few seconds for its TTL is cheaper than
//     a stream).
//
// Globals consumed: isAuthenticated, authFetch, htmx, openTraceView.

let _debugRefreshInFlight = false;
let _debugRefreshQueued = false;
let _debugPollTimer = null;


function _debugPanelEl() {
  return document.querySelector('section[data-section="debug"] .ns-children');
}


function refreshDebugPanel() {
  const el = _debugPanelEl();
  if (!el) return;
  if (_debugRefreshInFlight) {
    // Don't DROP a refresh that races an in-flight one (e.g. the arm
    // POST completing while the section-activation refresh is still
    // loading) — queue one trailing re-fetch so the panel always ends
    // on current state.
    _debugRefreshQueued = true;
    return;
  }
  _debugRefreshInFlight = true;
  authFetch('/partials/debug-catch')
    .then((r) => (r.ok ? r.text() : null))
    .then((html) => {
      if (html && el.isConnected) el.innerHTML = html;
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


// Poll only while: section visible + a trap is armed (the panel body
// carries the armed line). Stops by itself once disarmed/captured.
function _debugSchedulePoll() {
  if (_debugPollTimer) { clearTimeout(_debugPollTimer); _debugPollTimer = null; }
  const el = _debugPanelEl();
  if (!el?.isConnected || el.offsetParent === null) return;
  if (!el.querySelector('.debug-armed-line')) return;
  _debugPollTimer = setTimeout(refreshDebugPanel, 4000);
}


function buildDebugSection() {
  if (!isAuthenticated()) return null;
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-debug';
  const el = document.createElement('div');
  el.className = 'ns-children';
  el.innerHTML = '<div class="loading">Loading…</div>';
  wrap.appendChild(el);
  setTimeout(() => {
    const live = _debugPanelEl();
    if (live?.isConnected) refreshDebugPanel();
  }, 0);
  return wrap;
}


// Delegated actions — the panel body is re-swapped on every refresh,
// so handlers bind at the document level by id.
document.addEventListener('click', (ev) => {
  if (ev.target.closest('.gd-op-nav-btn[data-section="debug"]')) {
    setTimeout(refreshDebugPanel, 0);
    return;
  }
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

// Editor Trace View — the step-through CALL TREE of a persisted run's
// `:path-trace` (the tree sibling of editor-path-view.js's aggregated
// canvas badges).
//
// Server owns the markup: `GET /partials/execute-trace?id=…` returns
// the panel body — header (title + ◀/▶/✕ chrome) + one `.trace-row`
// per frame in depth-first call-tree order, each carrying `data-seq`
// + `data-fn-id` and its (already server-redacted) value viewer.
// This module owns ONLY the client lifecycle:
//   * openTraceView(execId) — fetch the partial into a singleton
//     side panel;
//   * row selection — click or ◀/▶ stepping walks rows in entry
//     (`data-seq`) order, highlighting the selected row AND its fn
//     card on the canvas (same `.path-highlighted` class the path
//     view uses; several expansion copies may match — all highlight);
//   * close (✕ / Escape) — remove the panel + canvas highlight.
//
// graph-first-exception: panel positioning, stepping state and canvas
// highlight bind to in-page objects (the overlay layer) — the rendered
// rows themselves are server hiccup.

let _traceViewPanelEl = null;


function _traceViewRows() {
  if (!_traceViewPanelEl) return [];
  return [..._traceViewPanelEl.querySelectorAll('.trace-row')]
    .sort((a, b) => Number(a.dataset.seq || 0) - Number(b.dataset.seq || 0));
}


function _traceHighlightCanvas(fnId) {
  document.querySelectorAll('.node-overlay.path-highlighted').forEach((el) => {
    el.classList.remove('path-highlighted');
  });
  if (!fnId) return;
  document.querySelectorAll(
    '.node-overlay[data-original-fn-id="' + CSS.escape(fnId) + '"]',
  ).forEach((el) => el.classList.add('path-highlighted'));
}


function _traceSelectRow(row) {
  if (!_traceViewPanelEl || !row) return;
  _traceViewPanelEl.querySelectorAll('.trace-row.trace-row-selected')
    .forEach((el) => el.classList.remove('trace-row-selected'));
  row.classList.add('trace-row-selected');
  row.scrollIntoView({ block: 'nearest' });
  _traceHighlightCanvas(row.dataset.fnId);
}


function _traceStep(delta) {
  const rows = _traceViewRows();
  if (rows.length === 0) return;
  const cur = rows.findIndex((r) => r.classList.contains('trace-row-selected'));
  const next = cur < 0
    ? (delta > 0 ? 0 : rows.length - 1)
    : Math.min(rows.length - 1, Math.max(0, cur + delta));
  _traceSelectRow(rows[next]);
}


let _traceViewTrigger = null;

function closeTraceView() {
  if (_traceViewPanelEl) {
    const hadFocus = _traceViewPanelEl.contains(document.activeElement);
    _traceViewPanelEl.remove();
    _traceViewPanelEl = null;
    // Both entry points (the Debug panel and the run history) are buttons in
    // a list the user was working through — put them back where they were.
    if (hadFocus) returnFocusTo(_traceViewTrigger);
    _traceViewTrigger = null;
  }
  _traceHighlightCanvas(null);
  document.removeEventListener('keydown', _traceViewOnKey);
}


function _traceViewOnKey(e) {
  if (!_traceViewPanelEl) return;
  if (e.key === 'Escape') { e.preventDefault(); closeTraceView(); return; }
  // Arrow stepping only while focus is inside the panel — the canvas
  // and inputs keep their own arrow-key behaviour.
  if (!_traceViewPanelEl.contains(document.activeElement)) return;
  if (e.key === 'ArrowDown' || e.key === 'ArrowRight') {
    e.preventDefault();
    _traceStep(1);
  } else if (e.key === 'ArrowUp' || e.key === 'ArrowLeft') {
    e.preventDefault();
    _traceStep(-1);
  }
}


function _bindTraceViewActions(panel) {
  panel.querySelector('#gd-trace-close')
    ?.addEventListener('click', closeTraceView);
  panel.querySelector('#gd-trace-prev')
    ?.addEventListener('click', () => _traceStep(-1));
  panel.querySelector('#gd-trace-next')
    ?.addEventListener('click', () => _traceStep(1));
  panel.querySelectorAll('.trace-row').forEach((row) => {
    row.addEventListener('click', (e) => {
      // <details> value toggles select the row too, but keep their
      // native open/close behaviour.
      if (e.target.closest('summary')) return;
      _traceSelectRow(row);
    });
    row.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        _traceSelectRow(row);
      }
    });
  });
}


async function openTraceView(execId) {
  if (!execId) return;
  // Captured before the panel is built. Taken from the live focus rather
  // than an argument so both callers get it without a signature change.
  const trigger = document.activeElement;
  closeTraceView();
  _traceViewTrigger = trigger;
  const panel = document.createElement('aside');
  panel.className = 'trace-view-panel';
  panel.setAttribute('role', 'dialog');
  panel.setAttribute('aria-label', 'Execution call tree');
  panel.innerHTML = '<div class="loading">Loading…</div>';
  document.body.appendChild(panel);
  _traceViewPanelEl = panel;
  document.addEventListener('keydown', _traceViewOnKey);
  try {
    const r = await authFetch('/partials/execute-trace?id='
                              + encodeURIComponent(execId));
    if (!_traceViewPanelEl || _traceViewPanelEl !== panel) return;
    if (!r.ok) {
      panel.textContent = r.status === 401
        ? 'Sign in to view the trace.' : ('HTTP ' + r.status);
      return;
    }
    panel.innerHTML = await r.text();
    _bindTraceViewActions(panel);
    _traceStep(1);   // select the root frame right away
  } catch (e) {
    if (panel.isConnected) panel.textContent = 'Failed: ' + (e?.message || 'network error');
  }
}


window.openTraceView = openTraceView;
window.closeTraceView = closeTraceView;

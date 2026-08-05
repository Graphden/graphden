// Editor Path View (Debug P2) — read-only canvas rendering of an
// execution's captured `:path-trace` (Debug P1 data; see
// docs/PHILOSOPHY.md § Debugging and Observability).
//
// Entry points (all exported on `window` for the other execute
// modules + browser tests):
//   * showExecutionPathView(pathTrace) — highlight the traversed fn
//     cards, badge each with aggregated timing info, show the
//     bottom-centre summary panel with a clear (✕) action.
//   * clearExecutionPathView() — restore normal rendering.
//   * appendPathViewAffordance(hostEl, pathTrace) — small "Show path"
//     button appended to a result pane whose execution carried a trace
//     (inline run, poll completion, history row expand).
//
// AGGREGATION RULE: the trace records one entry per `:ref` invocation,
// so a fn re-entered in a loop / shared subtree appears many times.
// Display aggregates PER fn-id: `count` (total invocations), `fresh`
// (cache misses) with `totalMs` = Σ duration-ms and `maxMs`, `hits`
// (cache hits — recorded without duration by design: absent, not 0),
// and `hidden` (any `[hidden — secret]` entry poisons the whole node:
// no timing shown at all, matching the capture-time redaction).
// Badge shows the compact form ("3× 12ms" / "cache" / "secret"); the
// full breakdown lives in the badge's title tooltip.
//
// The view is TRANSIENT by design: any overlay rebuild (relayout,
// expansion change, branch switch reload) or fn navigation clears it —
// re-anchoring badges across a rebuild would claim a mapping the new
// canvas may no longer have. Several cards can share one fn-id
// (expansion copies); every copy highlights.
//
// graph-first-exception: the highlight binds to the in-page overlay /
// graph-layer objects — no server-side representation exists.

let _pathViewPanelEl = null;


function _pathViewLayer() {
  return document.getElementById('graph-layer');
}


// --- Aggregation ----------------------------------------------------------

// `pathTrace` arrives as the JSON shape of the row's :path-trace jsonb:
// {entries: [{('fn-id'): uuid, ('cache-hit?'): bool, ('duration-ms'): n}
//            | {('fn-id'): uuid, hidden: 'secret'}],
//  ('path-truncated?'): bool?}
function aggregatePathTrace(entries) {
  const byFn = new Map();
  for (const e of entries) {
    const fnId = e['fn-id'];
    if (!fnId) continue;
    let agg = byFn.get(fnId);
    if (!agg) {
      agg = { count: 0, fresh: 0, hits: 0, totalMs: 0, maxMs: 0, hidden: false };
      byFn.set(fnId, agg);
    }
    agg.count += 1;
    if ('hidden' in e) {
      agg.hidden = true;
    } else if (e['cache-hit?']) {
      agg.hits += 1;
    } else {
      agg.fresh += 1;
      const ms = typeof e['duration-ms'] === 'number' ? e['duration-ms'] : 0;
      agg.totalMs += ms;
      if (ms > agg.maxMs) agg.maxMs = ms;
    }
  }
  return byFn;
}


function pathBadgeText(agg) {
  const times = agg.count > 1 ? agg.count + '× ' : '';
  if (agg.hidden) return 'secret';
  if (agg.fresh === 0) return times + 'cache';
  return times + agg.totalMs + 'ms';
}


function pathBadgeTitle(agg) {
  if (agg.hidden) {
    return '[hidden — secret] — this fn touches :secret-typed data; '
           + 'timings are not captured for it.';
  }
  const parts = [agg.count + (agg.count === 1 ? ' invocation' : ' invocations')];
  if (agg.fresh > 0) {
    parts.push(agg.fresh + ' fresh (total ' + agg.totalMs + ' ms, max '
               + agg.maxMs + ' ms)');
  }
  if (agg.hits > 0) parts.push(agg.hits + (agg.hits === 1 ? ' cache hit' : ' cache hits'));
  return 'Execution path: ' + parts.join(' · ');
}


// --- Panel ----------------------------------------------------------------

function _pathViewOffCanvasLabel(fnId) {
  const fn = (typeof lookups === 'object' && lookups?.fnMap)
    ? lookups.fnMap.get(fnId) : null;
  return fn?.name || (String(fnId).slice(0, 8) + '…');
}


function _showPathViewPanel(highlightedCount, offCanvasIds, truncated) {
  if (_pathViewPanelEl) _pathViewPanelEl.remove();
  const panel = document.createElement('div');
  panel.className = 'path-view-panel';
  panel.setAttribute('role', 'status');

  const label = document.createElement('span');
  label.textContent = 'Execution path: ' + highlightedCount
    + (highlightedCount === 1 ? ' fn' : ' fns') + ' highlighted';
  panel.appendChild(label);

  if (offCanvasIds.length > 0) {
    const off = document.createElement('span');
    off.className = 'path-view-off-canvas';
    off.textContent = '· not on canvas: ' + offCanvasIds.length
      + (offCanvasIds.length === 1 ? ' fn' : ' fns');
    off.title = offCanvasIds.map(_pathViewOffCanvasLabel).join(', ');
    panel.appendChild(off);
  }

  if (truncated) {
    const trunc = document.createElement('span');
    trunc.className = 'path-view-truncated';
    trunc.textContent = '· trace truncated';
    trunc.title = 'The capture hit its size cap — oldest entries were dropped.';
    panel.appendChild(trunc);
  }

  const clearBtn = document.createElement('button');
  clearBtn.type = 'button';
  clearBtn.className = 'path-view-clear';
  clearBtn.textContent = '✕ clear';
  clearBtn.setAttribute('aria-label', 'Clear the execution-path highlight');
  clearBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    clearExecutionPathView();
  });
  panel.appendChild(clearBtn);

  document.body.appendChild(panel);
  _pathViewPanelEl = panel;
}


// --- Show / clear ---------------------------------------------------------

function clearExecutionPathView() {
  const layer = _pathViewLayer();
  if (layer) layer.classList.remove('path-view-active');
  document.querySelectorAll('.node-overlay.path-highlighted').forEach((el) => {
    el.classList.remove('path-highlighted');
  });
  document.querySelectorAll('.path-trace-badge').forEach((el) => el.remove());
  if (_pathViewPanelEl) {
    _pathViewPanelEl.remove();
    _pathViewPanelEl = null;
  }
}


function showExecutionPathView(pathTrace) {
  clearExecutionPathView();
  const entries = pathTrace?.entries;
  if (!Array.isArray(entries) || entries.length === 0) return;
  const layer = _pathViewLayer();
  if (!layer) return;

  const byFn = aggregatePathTrace(entries);
  const matched = new Set();
  layer.querySelectorAll('.node-overlay[data-original-fn-id]').forEach((overlay) => {
    const fnId = overlay.dataset.originalFnId;
    const agg = byFn.get(fnId);
    if (!agg) return;
    matched.add(fnId);
    overlay.classList.add('path-highlighted');
    const badge = document.createElement('span');
    badge.className = 'path-trace-badge'
      + (agg.hidden ? ' path-trace-badge-secret' : '');
    badge.textContent = pathBadgeText(agg);
    badge.title = pathBadgeTitle(agg);
    overlay.appendChild(badge);
  });

  const offCanvasIds = [...byFn.keys()].filter((id) => !matched.has(id));
  layer.classList.add('path-view-active');
  _showPathViewPanel(matched.size, offCanvasIds, !!pathTrace['path-truncated?']);
}


// --- Result-pane affordance -----------------------------------------------

function appendPathViewAffordance(hostEl, pathTrace) {
  if (!hostEl || !Array.isArray(pathTrace?.entries)
      || pathTrace.entries.length === 0) return;
  const row = document.createElement('div');
  row.className = 'execute-path-affordance';
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'execute-show-path-btn';
  btn.textContent = 'Show path on canvas';
  btn.title = 'Highlight the fns this run traversed, with per-fn timing badges';
  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    showExecutionPathView(pathTrace);
  });
  row.appendChild(btn);
  hostEl.appendChild(row);
}


window.showExecutionPathView = showExecutionPathView;
window.clearExecutionPathView = clearExecutionPathView;
window.appendPathViewAffordance = appendPathViewAffordance;

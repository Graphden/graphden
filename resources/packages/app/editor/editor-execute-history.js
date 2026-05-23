// Editor Execute — history panel.
//
// Persisted runs for the current fn (across all of its versions),
// fetched lazily when the user clicks the "History" toggle in the
// execute popover header. Rows are summary-only; clicking a row
// expands to the full result via GET /api/execute/:id (reusing the
// `renderResultBody` / `renderErrorPane` helpers from
// editor-execute-result.js), and "Repeat" re-fills the form widgets
// with that run's args.
//
// Reads the shared `argFormHosts` registry from editor-execute.js —
// the editor JS bundle concatenates these scripts into one scope so
// the `let` survives. No own state.


async function fetchHistory(fnId) {
  try {
    const r = await authFetch('/api/executions?fn-id=' + encodeURIComponent(fnId),
                              { method: 'GET' });
    if (!r.ok) return [];
    const body = await r.json();
    return Array.isArray(body?.executions) ? body.executions : [];
  } catch (_) {
    return [];
  }
}


function shortDuration(startedAt, finishedAt) {
  if (!startedAt || !finishedAt) return '';
  const s = new Date(startedAt).getTime();
  const f = new Date(finishedAt).getTime();
  const ms = Math.max(0, f - s);
  if (ms < 1000) return ms + ' ms';
  if (ms < 60000) return (ms / 1000).toFixed(1) + ' s';
  return Math.round(ms / 1000) + ' s';
}


function shortPreview(row) {
  if (row.status === 'succeeded' || row.status === ':succeeded') {
    const s = JSON.stringify(row.result);
    return s == null ? '' : (s.length > 60 ? s.slice(0, 60) + '…' : s);
  }
  if (row.status === 'failed' || row.status === ':failed') {
    return row.error || '';
  }
  if (row.status === 'cancelled' || row.status === ':cancelled') {
    return 'cancelled';
  }
  return '';
}


async function applyHistoryArgs(fnEntity, execId) {
  try {
    const r = await authFetch('/api/execute/' + encodeURIComponent(execId),
                              { method: 'GET' });
    if (!r.ok) return;
    const row = await r.json();
    const argsBySlot = {};
    for (const a of (row.args || [])) {
      // The row's slot-id matches the slot.id our form was opened
      // against — look up by slot-id, then refill via fillFormValue.
      if (a.value !== null && a.value !== undefined) {
        argsBySlot[a['slot-id']] = a.value;
      } else if (Array.isArray(a.items) && a.items.length > 0) {
        // List-typed arg: the arg row's :value is nil (XOR), the
        // sequence content lives in :items. Reconstruct the vector
        // in :position order — get-execution already sort-by-position.
        argsBySlot[a['slot-id']] = a.items.map(i => i.value);
      }
      // For ref args we'd need to look up the ref's logical fn-id
      // — skipped in this MVP. The form leaves the user to pick.
    }
    for (const ah of argFormHosts) {
      const v = argsBySlot[ah.slotId];
      if (v !== undefined) {
        const root = ah.hostEl.querySelector('[data-form-root]') || ah.hostEl;
        if (typeof fillFormValue === 'function') fillFormValue(root, v);
      }
    }
  } catch (_) {}
}


function buildHistoryRow(fnEntity, row, resultHostEl, onExpand) {
  const wrap = document.createElement('div');
  wrap.className = 'execute-history-row';
  const status = String(row.status || '').replace(/^:/, '');
  wrap.classList.add('execute-history-row-' + status);

  const head = document.createElement('div');
  head.className = 'execute-history-row-head';

  const statusChip = document.createElement('span');
  statusChip.className = 'execute-history-status execute-history-status-' + status;
  statusChip.textContent = status;
  head.appendChild(statusChip);

  const ts = document.createElement('span');
  ts.className = 'execute-history-ts';
  // ISO timestamps from postgres look like "2026-05-21T15:03:35Z".
  // For today's runs show time only ("15:03:35") for compactness;
  // for older runs include date ("05-20 15:03") so a yesterday run
  // doesn't masquerade as today.
  const tStr = row['started-at'] || '';
  const m = tStr.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}:\d{2}:\d{2})/);
  if (m) {
    const todayIso = new Date().toISOString().slice(0, 10);
    const rowDate = m[1] + '-' + m[2] + '-' + m[3];
    ts.textContent = (rowDate === todayIso)
      ? m[4]
      : (m[2] + '-' + m[3] + ' ' + m[4].slice(0, 5));
  } else {
    ts.textContent = tStr;
  }
  head.appendChild(ts);

  const dur = shortDuration(row['started-at'], row['finished-at']);
  if (dur) {
    const dspan = document.createElement('span');
    dspan.className = 'execute-history-duration';
    dspan.textContent = dur;
    head.appendChild(dspan);
  }

  const repeatBtn = document.createElement('button');
  repeatBtn.type = 'button';
  repeatBtn.className = 'execute-history-repeat-btn';
  repeatBtn.title = 'Re-fill the form with this run\'s args';
  repeatBtn.textContent = 'Repeat';
  repeatBtn.addEventListener('click', async (e) => {
    e.stopPropagation();
    await applyHistoryArgs(fnEntity, row.id);
  });
  head.appendChild(repeatBtn);

  wrap.appendChild(head);

  const preview = document.createElement('div');
  preview.className = 'execute-history-preview';
  preview.textContent = shortPreview(row);
  wrap.appendChild(preview);

  wrap.addEventListener('click', async (e) => {
    if (e.target.closest('button')) return;
    e.stopPropagation();
    onExpand(row.id);
  });

  return wrap;
}


async function buildHistoryPanel(fnEntity, resultHostEl) {
  const panel = document.createElement('div');
  panel.className = 'execute-history-panel';
  const rows = await fetchHistory(fnEntity.id);
  if (rows.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'execute-history-empty';
    empty.textContent = 'No saved runs yet. Tick "Save to history" before clicking Run to populate.';
    panel.appendChild(empty);
    return panel;
  }
  const onExpand = async (execId) => {
    resultHostEl.textContent = '';
    resultHostEl.appendChild(renderSubmitSpinner('Loading…'));
    try {
      const r = await authFetch('/api/execute/' + encodeURIComponent(execId),
                                { method: 'GET' });
      const body = await r.json();
      resultHostEl.textContent = '';
      const status = String(body.status || '').replace(/^:/, '');
      if (status === 'succeeded') {
        resultHostEl.appendChild(renderResultBody(body.result,
                                                  { truncated: body['result-truncated?'] }));
      } else if (status === 'failed') {
        resultHostEl.appendChild(renderErrorPane(body.error, body['error-data']));
      } else {
        const note = document.createElement('div');
        note.className = 'execute-cancelled';
        note.textContent = status;
        resultHostEl.appendChild(note);
      }
      const rtStrip = renderRuntimeEffectsStrip(body['runtime-effects'],
                                                body['declared-effects']);
      if (rtStrip) resultHostEl.appendChild(rtStrip);
    } catch (e) {
      resultHostEl.appendChild(renderErrorPane('Load error: ' + e.message));
    }
  };
  for (const row of rows) {
    panel.appendChild(buildHistoryRow(fnEntity, row, resultHostEl, onExpand));
  }
  return panel;
}

// Editor Execute — history panel.
//
// Persisted runs for the current fn (across all of its versions),
// fetched lazily when the user clicks the "History" toggle in the
// execute popover header. Server owns the panel hiccup
// (`/partials/execute-history?fn-id=…` returns the rows or empty
// state). This module owns: fetch glue, post-swap binding of the
// row-click expand handler + Repeat button, the `applyHistoryArgs`
// form-refill flow, and the per-row result expansion (which still
// reaches `renderResultBody` / `renderErrorPane` from
// editor-execute-result.js).
//
// Reads the shared `argFormHosts` registry from editor-execute.js —
// the editor JS bundle concatenates these scripts into one scope so
// the `let` survives. No own state.


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
    }
    // argFormHosts comes from editor-execute.js's closure — bundled
    // into the same scope. Each host carries `:hostEl` + `:slotId`;
    // we refill via the same `fillFormValue` helper the inline edit
    // popovers use, against the form's `[data-form-root]` mount.
    for (const ah of argFormHosts) {
      const v = argsBySlot[ah.slotId];
      if (v !== undefined) {
        const root = ah.hostEl.querySelector('[data-form-root]') || ah.hostEl;
        if (typeof fillFormValue === 'function') fillFormValue(root, v);
      }
    }
  } catch (_) {}
}


// Build the per-row expand handler. The handler closes over the
// popover's `resultHost` so clicking a row paints the full result /
// error / runtime-effects in the same pane the inline submit uses.
//
// Body hiccup comes from `/partials/execute-result?id=…` — the
// server-rendered replacement for the JS `renderResultBody` /
// `renderTaintedPane` / `renderErrorPane` chain. The runtime-effects
// strip stays a separate fetch (already-migrated `/partials/execute-
// result-effects?runtime=…&declared=…`); we still need one tiny
// `/api/execute/:id` hit to pull `runtime-effects` + `declared-
// effects` for the strip URL params.
function makeRowExpander(resultHostEl) {
  return async (execId) => {
    resultHostEl.textContent = '';
    resultHostEl.appendChild(renderSubmitSpinner('Loading…'));
    try {
      const [bodyResp, jsonResp] = await Promise.all([
        authFetch('/partials/execute-result?id=' + encodeURIComponent(execId)),
        authFetch('/api/execute/' + encodeURIComponent(execId),
                  { method: 'GET' }),
      ]);
      resultHostEl.textContent = '';
      if (bodyResp.ok) {
        resultHostEl.innerHTML = await bodyResp.text();
      } else {
        resultHostEl.appendChild(renderErrorPane('Load error: HTTP '
                                                 + bodyResp.status));
      }
      if (jsonResp.ok) {
        const body = await jsonResp.json();
        appendRuntimeEffectsStrip(resultHostEl,
                                  body['runtime-effects'],
                                  body['declared-effects']);
      }
    } catch (e) {
      resultHostEl.appendChild(renderErrorPane('Load error: ' + e.message));
    }
  };
}


// Post-swap action wiring — both selectors point at markers the
// server fragment carries (`data-execution-id` on the row + the
// Repeat button; `.execute-history-repeat-btn` for the click target).
function bindHistoryActions(panel, fnEntity, resultHostEl) {
  const onExpand = makeRowExpander(resultHostEl);
  panel.querySelectorAll('.execute-history-repeat-btn').forEach((btn) => {
    btn.addEventListener('click', async (e) => {
      e.stopPropagation();   // don't bubble to row-click expand
      await applyHistoryArgs(fnEntity, btn.getAttribute('data-execution-id'));
    });
  });
  panel.querySelectorAll('.execute-history-row[data-execution-id]').forEach((row) => {
    row.addEventListener('click', (e) => {
      if (e.target.closest('button')) return;
      e.stopPropagation();
      onExpand(row.getAttribute('data-execution-id'));
    });
  });
}


// Fetch the server-rendered panel hiccup, return a populated DOM
// element ready for the caller to append. Signature matches the
// legacy `buildHistoryPanel(fnEntity, resultHost) → element` so
// editor-execute.js's call-sites stay untouched.
async function buildHistoryPanel(fnEntity, resultHostEl) {
  const wrap = document.createElement('div');
  wrap.className = 'execute-history-host-wrap';
  try {
    const r = await authFetch('/partials/execute-history?fn-id='
                              + encodeURIComponent(fnEntity.id));
    if (!r.ok) {
      const err = document.createElement('div');
      err.className = 'execute-history-error';
      err.textContent = r.status === 401
        ? 'Sign in to view run history.'
        : ('HTTP ' + r.status);
      wrap.appendChild(err);
      return wrap;
    }
    wrap.innerHTML = await r.text();
    if (window.htmx?.process) window.htmx.process(wrap);
    bindHistoryActions(wrap, fnEntity, resultHostEl);
  } catch (e) {
    const err = document.createElement('div');
    err.className = 'execute-history-error';
    err.textContent = 'Failed: ' + (e?.message || 'network error');
    wrap.appendChild(err);
  }
  return wrap;
}

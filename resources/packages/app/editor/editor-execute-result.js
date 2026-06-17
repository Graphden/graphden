// Editor Execute — result rendering primitives.
//
// Pure DOM-building helpers shared by:
//   * inline submit response (editor-execute.js)
//   * polling tick (editor-execute.js)
//   * history-row expansion (editor-execute-history.js)
//
// No module-level state — render-only, safe to call anywhere.
//
// `result` is a Clojure value JSON-serialised by the backend. We render
// type-aware where straightforward (scalar → chip, list → bullets,
// record → table); fall back to `<pre>JSON</pre>` for compound shapes
// that don't fit those categories. Big payloads (>50 KB serialised) get
// truncated for display responsiveness independently of the backend's
// 5 MB persistence cap — `opts.truncated` reflects the persistence-cap
// hit specifically.


function renderScalarResult(value) {
  const v = document.createElement('div');
  v.className = 'execute-result-scalar';
  v.textContent = (value === null) ? 'nil' : String(value);
  return v;
}


function renderListResult(value) {
  const ul = document.createElement('ul');
  ul.className = 'execute-result-list';
  const max = 50;
  const items = value.slice(0, max);
  for (const item of items) {
    const li = document.createElement('li');
    if (item === null || typeof item !== 'object') {
      li.textContent = String(item);
    } else {
      const pre = document.createElement('pre');
      pre.textContent = JSON.stringify(item, null, 2);
      li.appendChild(pre);
    }
    ul.appendChild(li);
  }
  if (value.length > max) {
    const more = document.createElement('li');
    more.className = 'execute-result-list-more';
    more.textContent = '… ' + (value.length - max) + ' more';
    ul.appendChild(more);
  }
  return ul;
}


function renderRecordResult(value) {
  const tbl = document.createElement('table');
  tbl.className = 'execute-result-record';
  for (const [k, v] of Object.entries(value)) {
    const tr = document.createElement('tr');
    const th = document.createElement('th');
    th.textContent = k;
    const td = document.createElement('td');
    if (v === null || typeof v !== 'object') {
      td.textContent = String(v);
    } else {
      const pre = document.createElement('pre');
      pre.textContent = JSON.stringify(v, null, 2);
      td.appendChild(pre);
    }
    tr.appendChild(th);
    tr.appendChild(td);
    tbl.appendChild(tr);
  }
  return tbl;
}


// Detect a `:secret`-typed return — both the inline POST response
// (`{tainted?: true}`) and the persisted row read back via GET
// (`{result: null, error-data: {reason: "tainted"}}`) get the same
// treatment: no value is shown, regardless of what `result` happens
// to be. Mirrors the backend's `redact-outcome` so the editor can't
// accidentally surface a value the server tried to hide.
function isTaintedExecuteResponse(body) {
  if (!body) return false;
  if (body['tainted?'] === true) return true;
  const ed = body['error-data'];
  if (ed && (ed.reason === 'tainted' || ed.reason === ':tainted')) return true;
  return false;
}


function renderTaintedPane() {
  const pane = document.createElement('div');
  pane.className = 'execute-result-pane execute-result-tainted';
  const icon = document.createElement('span');
  icon.className = 'execute-tainted-icon';
  icon.textContent = '🔒'; // 🔒
  pane.appendChild(icon);
  const txt = document.createElement('div');
  txt.className = 'execute-tainted-text';
  const head = document.createElement('div');
  head.className = 'execute-tainted-head';
  head.textContent = 'Result hidden';
  txt.appendChild(head);
  const body = document.createElement('div');
  body.className = 'execute-tainted-body';
  body.textContent =
    "This fn's return type is :secret — the value never reaches the browser. "
    + 'Swap the secret-bound argument for a plain literal to debug visibly.';
  txt.appendChild(body);
  pane.appendChild(txt);
  return pane;
}


function renderResultBody(result, opts) {
  const pane = document.createElement('div');
  pane.className = 'execute-result-pane';
  if (opts?.truncated) {
    const note = document.createElement('div');
    note.className = 'execute-result-truncated';
    note.textContent = 'Result exceeded 5 MB cap — not stored.';
    pane.appendChild(note);
    return pane;
  }
  if (result === undefined || result === null) {
    pane.appendChild(renderScalarResult(result));
    return pane;
  }
  const asStr = JSON.stringify(result);
  if (asStr.length > 50 * 1024) {
    const note = document.createElement('div');
    note.className = 'execute-result-truncated';
    note.textContent = 'Result preview only — full payload '
      + Math.round(asStr.length / 1024) + ' KB. First 50 KB shown.';
    pane.appendChild(note);
    const pre = document.createElement('pre');
    pre.className = 'execute-result-json';
    pre.textContent = asStr.slice(0, 50 * 1024) + '…';
    pane.appendChild(pre);
    return pane;
  }
  if (Array.isArray(result)) {
    pane.appendChild(renderListResult(result));
  } else if (typeof result === 'object') {
    pane.appendChild(renderRecordResult(result));
  } else {
    pane.appendChild(renderScalarResult(result));
  }
  return pane;
}


// Inline busy indicator used by the execute orchestrator and the
// history panel — shown in `resultHostEl` / `historyHostEl` while a
// fetch is in flight. Distinct visually from `execute-pending-pane`
// below, which is the post-submit polling state for an :id-bound run.
function renderSubmitSpinner(text) {
  const spin = document.createElement('div');
  spin.className = 'execute-submit-spinner';
  spin.textContent = text;
  return spin;
}


function renderPendingPane(execId) {
  const pane = document.createElement('div');
  pane.className = 'execute-pending-pane';
  const spin = document.createElement('span');
  spin.className = 'execute-pending-spinner';
  spin.textContent = '…';
  pane.appendChild(spin);
  const txt = document.createElement('span');
  txt.className = 'execute-pending-text';
  txt.textContent = 'Running… (id: ' + execId.slice(0, 8) + ')';
  pane.appendChild(txt);
  return pane;
}


// Runtime-effects strip — content lives in the graph
// (`/partials/execute-result-effects?runtime=…&declared=…`). The graph
// fragment owns chip rendering + drift detection (`execute-effects-
// drift` for runtime ∉ declared, `execute-effects-unobserved` for
// declared ∉ runtime when runtime is non-empty). JS only fetches the
// hiccup and swaps it in.
//
// Async by necessity — callers used to receive a DOM node synchronously
// and append it; the partial fetch is fire-and-forget into the host
// element since the result body has already been painted and the strip
// is purely informational below it.
async function appendRuntimeEffectsStrip(hostEl, runtimeEffects, declaredEffects) {
  const runtime = runtimeEffects || [];
  const declared = declaredEffects || [];
  if (runtime.length === 0 && declared.length === 0) return;
  const url = '/partials/execute-result-effects'
    + '?runtime='  + encodeURIComponent(runtime.map(String).join(','))
    + '&declared=' + encodeURIComponent(declared.map(String).join(','));
  try {
    const r = await fetch(url);
    if (!r.ok) return;   // Strip is best-effort; the result body still renders.
    const wrap = document.createElement('div');
    wrap.innerHTML = await r.text();
    // The partial returns a single root <div> (populated or hidden);
    // skip the hidden form so the host doesn't gain an empty sibling.
    const child = wrap.firstElementChild;
    if (child && child.getAttribute('hidden') !== '1') hostEl.appendChild(child);
  } catch (_) { /* swallow — informational */ }
}


function renderErrorPane(error, errorData) {
  const pane = document.createElement('div');
  pane.className = 'execute-error-pane';
  const head = document.createElement('div');
  head.className = 'execute-error-head';
  head.textContent = error || 'Execution failed';
  pane.appendChild(head);
  if (errorData) {
    const pre = document.createElement('pre');
    pre.className = 'execute-error-data';
    pre.textContent = JSON.stringify(errorData, null, 2);
    pane.appendChild(pre);
  }
  return pane;
}

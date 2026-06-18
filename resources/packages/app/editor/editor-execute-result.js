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

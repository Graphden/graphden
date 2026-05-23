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


// Runtime-effects strip — shown below result/error pane when the
// fn-execution row carries non-empty `:runtime-effects` OR when
// `:declared-effects` set is non-empty (we still want to surface the
// "promised but unobserved" case). Two drift visuals:
//
//   * `execute-effects-drift` (runtime ∉ declared) — impl widens its
//     effect set vs the rich-type promise. Red dashed outline.
//   * `execute-effects-unobserved` (declared ∉ runtime) — impl
//     declared an effect that didn't fire on this run. Could be a
//     conditional branch that wasn't taken, OR an over-declaration.
//     Grey dashed outline + greyed-out chip.
function renderRuntimeEffectsStrip(runtimeEffects, declaredEffects) {
  const runtime = runtimeEffects || [];
  const declared = declaredEffects || [];
  if (runtime.length === 0 && declared.length === 0) return null;
  const strip = document.createElement('div');
  strip.className = 'execute-runtime-effects-strip';
  const lbl = document.createElement('span');
  lbl.className = 'execute-runtime-effects-label';
  lbl.textContent = 'ran: ';
  strip.appendChild(lbl);
  const declaredSet = new Set(declared.map(String));
  const runtimeSet = new Set(runtime.map(String));
  // First — observed effects, in execution order. Drift outline when
  // not in the declared set.
  for (const cat of runtime) {
    const chip = document.createElement('span');
    chip.className = 'effects-chip effects-chip-' + cat;
    chip.textContent = cat;
    if (declaredEffects && !declaredSet.has(String(cat))) {
      chip.classList.add('execute-effects-drift');
      chip.title = 'Runtime effect not in declared :effects set — '
        + 'impl widens what its rich-type promised.';
    }
    strip.appendChild(chip);
  }
  // Then — declared effects that did NOT fire on this run. Render as
  // muted unobserved chips. Skipped entirely when no runtime data
  // was recorded (would imply pre-instrumentation row OR fn that
  // wasn't actually run with tracing; either way "missing" doesn't
  // mean "unobserved", just "unknown").
  if (runtime.length > 0) {
    for (const cat of declared) {
      if (runtimeSet.has(String(cat))) continue;
      const chip = document.createElement('span');
      chip.className = 'effects-chip effects-chip-' + cat
        + ' execute-effects-unobserved';
      chip.textContent = cat;
      chip.title = 'Declared but not observed at runtime — either a '
        + 'conditional branch that didn\'t run, or an over-declaration '
        + 'in the rich-type :effects set.';
      strip.appendChild(chip);
    }
  }
  return strip;
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

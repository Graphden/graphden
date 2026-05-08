// Editor Type Explainer — click-driven popover that explains what
// a type means in plain English, with the structural form shown
// below for users who already know the language.
//
// Phase 5: tap any type-chip on an arg-overlay or edge-label and
// you get the human-readable description ("non-negative integer",
// "list of text values", "function: takes int x, returns text").
// Editable chips also get a "Change type" button so the explainer
// is a strict expansion of what the chip used to do — click still
// reaches the type-edit popover, just via one extra tap that costs
// you nothing if you already know.
//
// Globals consumed: formatTypeHint, formatTypeHumanReadable.

let typeExplainerEl = null;
let typeExplainerAnchor = null;

function ensureTypeExplainerEl() {
  if (typeExplainerEl) return typeExplainerEl;
  const el = document.createElement('div');
  el.className = 'type-explainer';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-label', 'Type explanation');
  document.body.appendChild(el);
  typeExplainerEl = el;
  return el;
}

function positionTypeExplainer(el, anchorEl) {
  const r = anchorEl.getBoundingClientRect();
  el.style.left = '0px';
  el.style.top = '-9999px';
  el.style.display = 'block';
  const w = el.offsetWidth || 280;
  const h = el.offsetHeight || 120;
  const margin = 8;
  let left = r.left;
  let top = r.bottom + margin;
  if (top + h + margin > window.innerHeight) {
    top = Math.max(margin, r.top - h - margin);
  }
  if (left + w + margin > window.innerWidth) left = Math.max(margin, window.innerWidth - w - margin);
  if (left < margin) left = margin;
  el.style.left = left + 'px';
  el.style.top = top + 'px';
}

function hideTypeExplainer() {
  if (!typeExplainerEl) return;
  typeExplainerEl.classList.remove('visible');
  typeExplainerEl.style.display = 'none';
  typeExplainerAnchor = null;
}

// Generic info-popover renderer — used by both the type-chip
// click flow (`showTypeExplainer`) and the effect-chip click flow
// (`showEffectExplainer`). Shared structure: title + description
// + optional structural-detail line + optional action button.
function renderInfoPopover({ title, description, structural, action }, anchorEl) {
  if (!anchorEl) return false;
  const el = ensureTypeExplainerEl();
  el.textContent = '';

  const head = document.createElement('div');
  head.className = 'type-explainer-header';
  const titleEl = document.createElement('span');
  titleEl.className = 'type-explainer-title';
  titleEl.textContent = title || 'Info';
  head.appendChild(titleEl);
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'type-explainer-close';
  close.setAttribute('aria-label', 'Close ' + (title ? title.toLowerCase() + ' ' : '') + 'explainer');
  close.textContent = '×';
  close.addEventListener('click', (e) => { e.stopPropagation(); hideTypeExplainer(); });
  head.appendChild(close);
  el.appendChild(head);

  if (description) {
    const humanRow = document.createElement('div');
    humanRow.className = 'type-explainer-human';
    humanRow.textContent = description.charAt(0).toUpperCase() + description.slice(1);
    el.appendChild(humanRow);
  }

  if (structural) {
    const struct = document.createElement('div');
    struct.className = 'type-explainer-structural';
    struct.textContent = structural;
    el.appendChild(struct);
  }

  if (action && typeof action.onClick === 'function') {
    const actions = document.createElement('div');
    actions.className = 'type-explainer-actions';
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'type-explainer-btn';
    btn.textContent = action.label || 'Action';
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      hideTypeExplainer();
      action.onClick();
    });
    actions.appendChild(btn);
    el.appendChild(actions);
  }

  el.classList.add('visible');
  positionTypeExplainer(el, anchorEl);
  typeExplainerAnchor = anchorEl;
  installTypeExplainerDismiss();
  return true;
}

function showTypeExplainer({ type, anchorEl, onEdit, bindingId, editable }) {
  if (type == null || !anchorEl) return;
  if (typeof formatTypeHint !== 'function'
      || typeof formatTypeHumanReadable !== 'function') return;
  renderInfoPopover({
    title: 'Type',
    description: formatTypeHumanReadable(type),
    structural: formatTypeHint(type),
    action: typeof onEdit === 'function'
            ? { label: 'Change type', onClick: onEdit }
            : null,
  }, anchorEl);
  // For fn-typed slots that have a backing binding (and are
  // editable), append an effect-narrowing row directly in the
  // popover. Phase 8's `[:fn args ret #{eff-set}]` form has
  // backend support but had no UI surface — `:handler` slots used
  // to be uneditable beyond replacing the entire type. The row
  // below lets the user say "this callable may only do :network"
  // and have sync-time `:expects-effects` checks enforce that.
  if (editable && bindingId
      && Array.isArray(type) && type[0] === 'fn'
      && typeof renderEffectTighteningRow === 'function') {
    renderEffectTighteningRow(typeExplainerEl, type, bindingId);
  }
}


// Tightening row — three-axis narrower for an fn-typed slot:
//   - per-arg type pickers (replace `:request :ring-request-shape`
//     with a NAMED narrower type)
//   - return-type picker (same shape)
//   - effect-set checkboxes (Phase 8 carve-out)
//
// All three submit through the same `tighten-fn-effects` endpoint
// — the backend accepts any subset of `{args, ret, effects}` as a
// delta and merges with the current constraint. The "Tighten"
// button collects non-default selections from each control and
// sends one combined request.
const EFFECT_CATEGORIES = ['db', 'env', 'io', 'network', 'time', 'random'];

function renderEffectTighteningRow(parent, fnType, bindingId) {
  if (!parent || !fnType) return;
  const currentEff = (Array.isArray(fnType) && fnType.length === 4)
                     ? new Set((fnType[3] || []).map(e =>
                                 typeof e === 'string' ? e.replace(/^:/, '') : String(e)))
                     : null;
  const cur3argsArr = Array.isArray(fnType) ? Object.entries(fnType[1] || {}) : [];
  const curRet = Array.isArray(fnType) ? fnType[2] : null;

  const wrap = document.createElement('div');
  wrap.className = 'type-explainer-tighten';
  const lbl = document.createElement('div');
  lbl.className = 'type-explainer-tighten-label';
  lbl.textContent = 'Tighten:';
  wrap.appendChild(lbl);

  // Per-arg pickers + ret picker. Each row carries a `<select>`
  // pre-selected with the current type, async-populated with
  // narrower compatible types via /api/types/compatible.
  const argSelects = {};
  for (const [argName, argType] of cur3argsArr) {
    const argRow = document.createElement('div');
    argRow.className = 'type-explainer-tighten-typerow';
    const lab = document.createElement('span');
    lab.className = 'type-explainer-tighten-typename';
    lab.textContent = argName + ':';
    argRow.appendChild(lab);
    const sel = document.createElement('select');
    sel.className = 'type-explainer-tighten-select';
    populateNarrowerOptions(sel, argType);
    argRow.appendChild(sel);
    argSelects[argName] = { sel, current: argType };
    wrap.appendChild(argRow);
  }
  let retSelect = null;
  if (curRet != null) {
    const retRow = document.createElement('div');
    retRow.className = 'type-explainer-tighten-typerow';
    const lab = document.createElement('span');
    lab.className = 'type-explainer-tighten-typename';
    lab.textContent = '→';
    retRow.appendChild(lab);
    retSelect = document.createElement('select');
    retSelect.className = 'type-explainer-tighten-select';
    populateNarrowerOptions(retSelect, curRet);
    retRow.appendChild(retSelect);
    wrap.appendChild(retRow);
  }

  // Effect checkboxes.
  const effLabel = document.createElement('div');
  effLabel.className = 'type-explainer-tighten-typename';
  effLabel.textContent = currentEff
    ? 'Effects (uncheck to forbid):'
    : 'Effects (none = unconstrained):';
  wrap.appendChild(effLabel);
  const row = document.createElement('div');
  row.className = 'type-explainer-tighten-row';
  const checkboxes = {};
  for (const cat of EFFECT_CATEGORIES) {
    const wrap1 = document.createElement('label');
    wrap1.className = 'type-explainer-tighten-chk';
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.value = cat;
    if (currentEff?.has(cat)) cb.checked = true;
    checkboxes[cat] = cb;
    wrap1.appendChild(cb);
    const sp = document.createElement('span');
    sp.textContent = cat;
    wrap1.appendChild(sp);
    row.appendChild(wrap1);
  }
  wrap.appendChild(row);

  const errEl = document.createElement('div');
  errEl.className = 'type-explainer-tighten-err';
  errEl.style.display = 'none';
  wrap.appendChild(errEl);

  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'type-explainer-btn';
  btn.textContent = 'Tighten';
  btn.addEventListener('click', async (e) => {
    e.stopPropagation();
    btn.disabled = true;
    errEl.style.display = 'none';
    // Collect deltas. Args / ret send only when the picker
    // diverged from the current value; otherwise omit so the
    // backend keeps the existing component. Effects always send
    // (a 3-arity slot newly tightened to #{} reads as "no effects
    // allowed"; that's a meaningful narrowing).
    const argDelta = {};
    let argsChanged = false;
    for (const [name, { sel, current }] of Object.entries(argSelects)) {
      if (sel.value && sel.value !== compactTypeAsValue(current)) {
        argDelta[name] = sel.value;
        argsChanged = true;
      }
    }
    const body = {};
    if (argsChanged) body.args = argDelta;
    if (retSelect?.value
        && retSelect.value !== compactTypeAsValue(curRet)) {
      body.ret = retSelect.value;
    }
    // Effects: only send when the user actually wants a 4-arity
    // constraint. For a 3-arity slot this is the only way to ADD
    // a 4-arity constraint, so we always send when ANY checkbox
    // is checked. If all are unchecked AND the slot is currently
    // 3-arity, skip — sending `[]` would commit "no effects
    // allowed" which is rarely what an empty checklist means.
    const effects = EFFECT_CATEGORIES.filter(c => checkboxes[c].checked);
    if (effects.length > 0 || currentEff) {
      body.effects = effects;
    }
    if (Object.keys(body).length === 0) {
      errEl.textContent = 'No changes — pick a narrower type or check an effect.';
      errEl.style.display = 'block';
      btn.disabled = false;
      return;
    }
    try {
      const fetchFn = (typeof authFetch === 'function') ? authFetch : fetch;
      const resp = await fetchFn(
        '/api/bindings/' + encodeURIComponent(bindingId) + '/tighten-fn-effects',
        { method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body) });
      if (!resp.ok) {
        const text = await resp.text();
        const m = text?.match(/<p class="error">([^<]+)<\/p>/);
        errEl.textContent = m ? m[1] : ('HTTP ' + resp.status);
        errEl.style.display = 'block';
        btn.disabled = false;
        return;
      }
      hideTypeExplainer();
      if (typeof initGraph === 'function') initGraph();
    } catch (err) {
      errEl.textContent = String(err?.message ? err.message : err);
      errEl.style.display = 'block';
      btn.disabled = false;
    }
  });
  wrap.appendChild(btn);
  parent.appendChild(wrap);
  // Re-position the popover; adding the row may have changed its
  // height enough to push it off-screen.
  positionTypeExplainer(parent, typeExplainerAnchor);
}


// Render a structural type as the SAME wire-shape `/api/types/
// compatible` accepts — keyword names stripped of leading `:`,
// structural arrays kept as-is. The arg/ret pickers compare the
// user's selection against this so a no-op selection (current
// type) doesn't get sent to the backend.
function compactTypeAsValue(t) {
  if (typeof t === 'string') return t.replace(/^:/, '');
  return JSON.stringify(t);  // structural — Edit by typing isn't supported
                             // yet; the picker only offers named alternates.
}


// Populate `<select>` with the current type + every named alias
// that's a subtype of it. Async; the picker shows the current
// option immediately so the user has an answer even before the
// fetches complete.
async function populateNarrowerOptions(select, currentType) {
  const curVal = compactTypeAsValue(currentType);
  const curLabel = (typeof currentType === 'string')
                   ? currentType.replace(/^:/, '')
                   : JSON.stringify(currentType);
  const curOpt = document.createElement('option');
  curOpt.value = curVal;
  curOpt.textContent = curLabel + ' (current)';
  curOpt.selected = true;
  select.appendChild(curOpt);
  if (typeof richTypes !== 'object' || !richTypes) return;
  // Candidates: every type-row entry. Filter via /api/types/
  // compatible. Same approach as the main type-edit picker.
  const aliases = Object.keys(richTypes)
    .filter(k => richTypes[k] && richTypes[k]['type-row?'] === true);
  if (aliases.length === 0) return;
  try {
    const results = await Promise.all(aliases.map(async name => {
      try {
        const r = await fetch('/api/types/compatible', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ expected: currentType, candidate: name })
        }).then(r => r.json());
        return { name, ok: !!r.ok };
      } catch (_) { return { name, ok: false }; }
    }));
    for (const r of results) {
      if (r.ok && r.name !== curVal) {
        const o = document.createElement('option');
        o.value = r.name;
        o.textContent = r.name;
        select.appendChild(o);
      }
    }
  } catch (_) { /* leave just the current option */ }
}

// Effect categories have stable meanings — see TYPES.md Phase 6.
// The popover surfaces the natural-language description + the
// canonical effect tag in the structural row (matching the chip's
// label so users can see the link).
const EFFECT_DESCRIPTIONS = {
  db:      'Reads or writes storage (database / persistent state).',
  env:     'Reads environment variables.',
  io:      'Reads or writes files / classpath resources.',
  network: 'Makes outbound HTTP / network calls.',
  time:    'Uses wall-clock time — call returns a different value over time.',
  random:  'Generates random or otherwise non-deterministic values.',
  effect:  'Has unspecified side effects (legacy generic tag).',
};

function showEffectExplainer({ effect, anchorEl }) {
  if (!effect || !anchorEl) return;
  const key = String(effect).toLowerCase();
  const description = EFFECT_DESCRIPTIONS[key]
    || 'Side effect that the type-checker tracks but doesn\'t name yet.';
  renderInfoPopover({
    title: 'Effect',
    description: description,
    structural: ':' + key,
  }, anchorEl);
}

let typeExplainerDismissInstalled = false;

function installTypeExplainerDismiss() {
  if (typeExplainerDismissInstalled) return;
  typeExplainerDismissInstalled = true;
  document.addEventListener('click', (e) => {
    if (!typeExplainerEl || !typeExplainerEl.classList.contains('visible')) return;
    const t = e.target;
    if (t && (typeExplainerEl.contains(t)
              || (typeExplainerAnchor?.contains(t)))) return;
    hideTypeExplainer();
  }, true);
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && typeExplainerEl
        && typeExplainerEl.classList.contains('visible')) {
      hideTypeExplainer();
    }
  });
}

window.showTypeExplainer = showTypeExplainer;
window.showEffectExplainer = showEffectExplainer;
window.hideTypeExplainer = hideTypeExplainer;

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

// `showTypeExplainer` and `renderEffectTighteningRow` (which used to
// live here) were removed when the chip-click flow moved into
// `editor-overlay-type-expand.js`'s inline expansion panel. Both
// surfaced the same structural + tightening UI; the inline panel
// supersedes them and avoids the popover-vs-canvas context switch.
// `populateNarrowerOptions`, `compactTypeAsValue`, `EFFECT_CATEGORIES`,
// `EFFECT_DESCRIPTIONS`, and the generic `renderInfoPopover` +
// `showEffectExplainer` stay — they're consumed by either the
// inline-expand renderer or the effect-chip click flow on fn-cards.


// Effect categories — referenced by the tightening UI in
// `editor-overlay-type-expand.js`.
const EFFECT_CATEGORIES = ['db', 'env', 'io', 'network', 'time', 'random'];


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
  // No "(current)" suffix — selected=true plus the option being the
  // first item already conveys it, and the parent structural row
  // shows the same type as a chip immediately above.
  curOpt.textContent = curLabel;
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

window.showEffectExplainer = showEffectExplainer;
window.hideTypeExplainer = hideTypeExplainer;

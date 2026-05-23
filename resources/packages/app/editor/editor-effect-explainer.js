// Editor Effect Explainer — click-driven popover that explains what a
// tracked side-effect (db / env / io / network / time / random /
// process) means in plain English. Triggered by tapping an
// effect-chip on a fn-card.
//
// Formerly editor-type-explainer.js: the type-chip explainer flow
// moved into editor-overlay-type-expand.js's inline expansion panel,
// and the type-narrowing helpers (compactTypeAsValue,
// populateNarrowerOptions, EFFECT_CATEGORIES) moved to that module
// alongside their only consumer. What remains is the effect-chip
// explainer plus the generic info-popover renderer it uses.
//
// Globals consumed: anchorBelowClamped, installPopoverDismiss
// (editor-popover-base.js).

let effectExplainerEl = null;
let effectExplainerAnchor = null;

function ensureEffectExplainerEl() {
  if (effectExplainerEl) return effectExplainerEl;
  const el = document.createElement('div');
  el.className = 'type-explainer';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-label', 'Effect explanation');
  document.body.appendChild(el);
  effectExplainerEl = el;
  return el;
}

function effectExplainerVisible() {
  return !!effectExplainerEl && effectExplainerEl.classList.contains('visible');
}

function hideEffectExplainer() {
  if (!effectExplainerEl) return;
  effectExplainerEl.classList.remove('visible');
  effectExplainerEl.style.display = 'none';
  effectExplainerAnchor = null;
}

// Generic info-popover renderer — title + description + optional
// structural-detail line + optional action button.
function renderInfoPopover({ title, description, structural, action }, anchorEl) {
  if (!anchorEl) return false;
  const el = ensureEffectExplainerEl();
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
  close.addEventListener('click', (e) => { e.stopPropagation(); hideEffectExplainer(); });
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
      hideEffectExplainer();
      action.onClick();
    });
    actions.appendChild(btn);
    el.appendChild(actions);
  }

  el.classList.add('visible');
  anchorBelowClamped(el, anchorEl);
  effectExplainerAnchor = anchorEl;
  return true;
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
  process: 'Spawns supervised background work (thread / loop / listener) that lives past the call and needs explicit stopping. Required for a fn to become a :service.',
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

installPopoverDismiss({
  getEl: () => effectExplainerEl,
  getAnchor: () => effectExplainerAnchor,
  isVisible: effectExplainerVisible,
  onDismiss: hideEffectExplainer,
});

window.showEffectExplainer = showEffectExplainer;

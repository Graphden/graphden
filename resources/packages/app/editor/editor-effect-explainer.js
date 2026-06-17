// Editor Effect Explainer — click-driven popover that explains what a
// tracked side-effect (db / env / io / network / time / random /
// process) means in plain English. Triggered by tapping an
// effect-chip on a fn-card.
//
// Content lives in the graph: `app.editor` fn-defs render the hiccup
// fragment, `GET /partials/effect?effect=<tag>` serves it as
// `text/html`. This module only owns the popover MOUNT-POINT (a
// single shared `<div>`), the fetch glue, anchored positioning, and
// outside-click / Esc dismissal. Adding a new tracked effect or
// editing a description is a graph-side change, no JS edit.
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

// Bind the close × inside the swapped fragment. Server marks the
// element with `data-explainer-close="1"` so we don't need to know
// the class name — the contract is a data attribute, not a selector
// shape the server might tweak later.
function bindFragmentDismiss(rootEl) {
  const closer = rootEl.querySelector('[data-explainer-close]');
  if (closer) {
    closer.addEventListener('click', (e) => {
      e.stopPropagation();
      hideEffectExplainer();
    });
  }
}

async function showEffectExplainer({ effect, anchorEl }) {
  if (!effect || !anchorEl) return;
  const el = ensureEffectExplainerEl();
  const url = '/partials/effect?effect=' + encodeURIComponent(effect);
  let html;
  try {
    const resp = await fetch(url);
    if (!resp.ok) {
      el.innerHTML = '<div class="type-explainer-error">HTTP ' + resp.status + '</div>';
    } else {
      html = await resp.text();
      el.innerHTML = html;
      bindFragmentDismiss(el);
    }
  } catch (err) {
    el.innerHTML = '<div class="type-explainer-error">Failed: '
      + (err?.message || 'network error') + '</div>';
  }
  el.classList.add('visible');
  el.style.display = '';
  anchorBelowClamped(el, anchorEl);
  effectExplainerAnchor = anchorEl;
}

installPopoverDismiss({
  getEl: () => effectExplainerEl,
  getAnchor: () => effectExplainerAnchor,
  isVisible: effectExplainerVisible,
  onDismiss: hideEffectExplainer,
});

window.showEffectExplainer = showEffectExplainer;

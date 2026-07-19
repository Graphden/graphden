// Editor Provenance Popover — click-driven popover anchored to the `↳`
// provenance badge on an arg-overlay's type-chip. Surfaces the FULL
// narrowing chain (declaration → ancestor overrides → ref-return →
// effective slot type) as a clickable breadcrumb so the reader can
// answer "where did THIS type constraint come from?" without opening
// the inline `▸/▾` type-expand panel and scrolling past structural
// detail.
//
// Reuses `slotTypeProvenance` (editor-literal-types.js) for data and
// `appendResolutionSection` (editor-overlay-type-expand.js) for
// rendering — passing `onNavigate: selectFn` so each ancestor /
// source-fn name renders as a clickable link.
//
// Globals consumed: anchorBelowClamped, installPopoverDismiss
// (editor-popover-base.js), slotTypeProvenance, appendResolutionSection,
// selectFn.

let provenancePopoverEl = null;
let provenancePopoverAnchor = null;

function ensureProvenancePopoverEl() {
  if (provenancePopoverEl) return provenancePopoverEl;
  const el = document.createElement('div');
  el.className = 'provenance-popover';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-label', 'Type narrowing provenance');
  document.body.appendChild(el);
  provenancePopoverEl = el;
  return el;
}

function provenancePopoverVisible() {
  return !!provenancePopoverEl
         && provenancePopoverEl.classList.contains('visible');
}

// Re-locate a provenance trigger in the CURRENT DOM by its stable
// identifiers. Overlay rebuilds replace badge nodes wholesale, so any
// aria write must target the live node, not the one we captured.
function locateLiveProvenanceAnchor(anchorEl) {
  if (anchorEl && document.body.contains(anchorEl)) return anchorEl;
  const bid = anchorEl?.getAttribute?.('data-binding-id');
  const iid = anchorEl?.getAttribute?.('data-item-id');
  if (!bid) return anchorEl;
  const sel = iid
    ? `.arg-type-provenance[data-binding-id="${bid}"][data-item-id="${iid}"]`
    : `.arg-type-provenance[data-binding-id="${bid}"]:not([data-item-id])`;
  return document.querySelector(sel) || anchorEl;
}

// Badge factories (editor-overlay-arg.js, edge-label) call this when
// (re)creating a trigger: an overlay rebuild AFTER the popover opened
// replaces the badge with a fresh node whose factory default is
// aria-expanded="false" — the popover is still open, so the fresh node
// must be born "true" or the disclosure state silently desyncs (caught
// by edit-type-chip-expand.test.js under host load, where the rebuild
// reliably lands inside the open window).
function isProvenanceOpenFor(bindingId, itemId) {
  if (!provenancePopoverVisible() || !provenancePopoverAnchor) return false;
  const bid = provenancePopoverAnchor.getAttribute?.('data-binding-id');
  if (!bid || String(bindingId) !== bid) return false;
  const iid = provenancePopoverAnchor.getAttribute?.('data-item-id') || null;
  return (itemId ? String(itemId) : null) === iid;
}

function hideProvenancePopover() {
  if (!provenancePopoverEl) return;
  provenancePopoverEl.classList.remove('visible');
  provenancePopoverEl.style.display = 'none';
  // Sync `aria-expanded` on the trigger that opened the popover so
  // screen readers see the disclosure flip back to closed. Both
  // provenance triggers (arg-type-provenance, return-type-strip-
  // provenance) start at "false" when rendered and we set "true"
  // when opening from them — undo that here. Re-locate first: the
  // overlay may have rebuilt since open, and flipping the attribute
  // on a detached node would leave the LIVE badge claiming "open".
  if (provenancePopoverAnchor) {
    const live = locateLiveProvenanceAnchor(provenancePopoverAnchor);
    try {
      live.setAttribute('aria-expanded', 'false');
    } catch (_) {}
  }
  provenancePopoverAnchor = null;
}


// Reposition + state-sync helper shared by both show* entry points.
// Before swapping the tracked anchor:
//   - the PREVIOUS anchor (if any) needs aria-expanded="false" so a
//     stale trigger doesn't keep claiming "I'm open"
//   - the NEW anchor gets aria-expanded="true"
// Then we anchor-clamp + reveal.
//
// Re-locate the anchor in the current DOM by `data-binding-id`
// (+ optional `data-item-id`) before flipping aria-expanded — the
// async `/partials/provenance` fetch in `showProvenancePopover` is
// slow enough that the overlay manager can rebuild and replace the
// originally-clicked badge with an equivalent one carrying the same
// binding identifier. Setting the attribute on the detached badge is
// a no-op; finding the live one keeps the disclosure state correct.
function attachAndShow(anchorEl) {
  const liveAnchor = locateLiveProvenanceAnchor(anchorEl);
  if (provenancePopoverAnchor && provenancePopoverAnchor !== liveAnchor) {
    try {
      provenancePopoverAnchor.setAttribute('aria-expanded', 'false');
    } catch (_) {}
  }
  try {
    liveAnchor.setAttribute('aria-expanded', 'true');
  } catch (_) {}
  provenancePopoverEl.classList.add('visible');
  anchorBelowClamped(provenancePopoverEl, liveAnchor);
  provenancePopoverAnchor = liveAnchor;
}

// Post-swap binding for nav-links + close button. Same three-data-attr
// pattern as mismatch-explainer: `[data-explainer-close]` for close,
// `a[data-fn-id]` for provenance source-link navigation.
function bindProvPopoverPostSwap(el) {
  const close = el.querySelector('[data-explainer-close]');
  if (close) {
    close.addEventListener('click', (e) => {
      e.stopPropagation();
      hideProvenancePopover();
    });
  }
  el.querySelectorAll('a[data-fn-id]').forEach((link) => {
    const fnId = link.getAttribute('data-fn-id');
    if (!fnId) return;
    link.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      if (typeof selectFn === 'function') {
        hideProvenancePopover();
        selectFn(fnId);
      }
    });
  });
}


// Fetch server-rendered popover and bind interactive surfaces. Steps
// 1-3 of the migration complete — header / Resolved-via / Allowed-
// values / Slot-effect-bound sections all ship from the partial.
// JS only attaches click handlers to `[data-explainer-close]` +
// `a[data-fn-id]`.
async function showProvenancePopover(arg, anchorEl) {
  if (!arg || !anchorEl) return;
  const bindingId = arg['binding-id'];
  const itemId = arg['item-id'];
  // Server partial requires a binding to read from DB. Skip when
  // there's no binding-id (matches the original `!prov?.winner` bail).
  if (!bindingId) return;
  const params = new URLSearchParams({ 'binding-id': bindingId });
  if (itemId) params.set('item-id', itemId);
  let html;
  try {
    const r = await authFetch('/partials/provenance?' + params.toString());
    if (!r.ok) return;
    html = await r.text();
  } catch (_) {
    return;
  }
  // Server returns the popover shell unconditionally. When there's no
  // narrowing chain to show (`:_provenance-some?` false), the resolved-
  // via section degrades to a hidden span — the popover would be just
  // a header. Skip showing in that case (same UX the original "bail
  // on `!prov?.winner`" gave).
  const probe = document.createElement('div');
  probe.innerHTML = html;
  const anyTier = probe.querySelector('.type-inline-resolution-tier');
  if (!anyTier) return;

  const el = ensureProvenancePopoverEl();
  el.innerHTML = html;
  bindProvPopoverPostSwap(el);
  attachAndShow(anchorEl);
}


// NOTE: the section-builder helpers that used to live here
// (appendPopoverSection / appendEffectConstraintSection /
// appendClosedEnumSection) are gone — every section of both
// provenance popovers ships pre-rendered from the server partials.

// Return-type variant — anchored to the `↳` glyph on a fn-card's
// return-type strip when an ancestor base-fn's :return-type-rule
// computed this fn's return type. Server-rendered at
// `GET /partials/return-type-rule?fn=<name>` — the rule-owner walk,
// the per-rule narrative prose, and the Inputs table all ship from
// the partial (the former ~185-line JS `ruleNarrators` mirror of the
// `types/check.clj` rule semantics lives in the graph now, as
// `:_rtr-narratives`). JS mounts + anchors only, reusing the same
// singleton element and dismiss handler as the slot-narrowing
// popover above.
async function showReturnTypeRulePopover(fnName, anchorEl) {
  if (!fnName || !anchorEl) return;
  let html;
  try {
    const r = await authFetch('/partials/return-type-rule?fn='
                              + encodeURIComponent(fnName));
    if (!r.ok) return;
    html = await r.text();
  } catch (_) {
    return;
  }
  // The partial renders the intro only when a rule-owning ancestor
  // exists — a header-only response means nothing to show (same UX
  // the old client-side rule-owner bail gave).
  const probe = document.createElement('div');
  probe.innerHTML = html;
  if (!probe.querySelector('.provenance-popover-intro')) return;

  const el = ensureProvenancePopoverEl();
  el.innerHTML = html;
  bindProvPopoverPostSwap(el);
  attachAndShow(anchorEl);
}

installPopoverDismiss({
  getEl: () => provenancePopoverEl,
  getAnchor: () => provenancePopoverAnchor,
  isVisible: provenancePopoverVisible,
  onDismiss: hideProvenancePopover,
});

window.showProvenancePopover = showProvenancePopover;
window.showReturnTypeRulePopover = showReturnTypeRulePopover;
window.hideProvenancePopover = hideProvenancePopover;

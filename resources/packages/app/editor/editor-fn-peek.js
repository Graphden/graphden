// Editor Fn Peek — read a fn's bindings IN PLACE, without re-rooting
// the canvas. Named fns render as leaf nodes (the layout's abstraction
// boundary), so before this the only way to read one was to navigate
// to it and lose the canvas you were on — deep compositions became a
// chain of jumps with browser-back as the only thread. The 👁 action
// in a row's ⋯ menu opens this floating read-only panel instead: the
// same server-rendered bindings/provenance the inspector's Bindings
// tab shows (`GET /partials/inspector-detail`), plus an Open button
// for when the reader DOES want to jump.
//
// Globals consumed: installPopoverDismiss + focusIntoDialog
// (editor-a11y.js), anchorBelowClamped, formatServerTypeTexts,
// lookups, gdNavigateToFn (editor-ui.js).

let fnPeekEl = null;
let fnPeekAnchor = null;
let fnPeekFnId = null;

function fnPeekVisible() {
  return !!fnPeekEl;
}

function hideFnPeek() {
  if (fnPeekEl) {
    // Focus return must cover EVERY close path (× button, Open), not
    // only the Escape the dismiss handler sees — the contract in
    // docs/ACCESSIBILITY.md.
    const hadFocus = fnPeekEl.contains(document.activeElement);
    fnPeekEl.remove();
    fnPeekEl = null;
    fnPeekFnId = null;
    if (hadFocus && typeof returnFocusTo === 'function') {
      returnFocusTo(fnPeekAnchor);
    }
  }
}

async function openFnPeek(fnId, anchorEl) {
  if (!fnId) return;
  // Second 👁 on the same row toggles the panel off.
  if (fnPeekFnId === fnId) { hideFnPeek(); return; }
  hideFnPeek();

  const fn = (typeof lookups !== 'undefined') ? lookups?.fnMap?.get(fnId) : null;
  const name = fn?.name || 'fn';
  const nsPath = (fn?.['namespace-id'] && lookups?.nsPathMap)
    ? (lookups.nsPathMap.get(fn['namespace-id']) || '') : '';

  const el = document.createElement('div');
  el.className = 'fn-peek-panel';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'false');
  el.setAttribute('aria-label', 'Peek: ' + name);

  const head = document.createElement('div');
  head.className = 'fn-peek-head';
  const title = document.createElement('span');
  title.className = 'fn-peek-title';
  title.textContent = name;
  head.appendChild(title);
  if (nsPath) {
    const ns = document.createElement('span');
    ns.className = 'fn-peek-ns';
    ns.textContent = nsPath;
    head.appendChild(ns);
  }
  const openBtn = document.createElement('button');
  openBtn.type = 'button';
  openBtn.className = 'fn-peek-open';
  openBtn.textContent = 'Open';
  openBtn.setAttribute('aria-label', 'Open ' + name + ' on the canvas');
  openBtn.addEventListener('click', () => {
    const qname = nsPath ? nsPath + '.' + name : name;
    hideFnPeek();
    if (typeof gdNavigateToFn === 'function') gdNavigateToFn(fnId, qname);
  });
  const closeBtn = document.createElement('button');
  closeBtn.type = 'button';
  closeBtn.className = 'fn-peek-close';
  closeBtn.textContent = '×';
  closeBtn.setAttribute('aria-label', 'Close peek');
  closeBtn.addEventListener('click', hideFnPeek);
  head.appendChild(openBtn);
  head.appendChild(closeBtn);

  const body = document.createElement('div');
  body.className = 'fn-peek-body';
  body.innerHTML = '<div class="gd-insp-runs-loading">Loading bindings…</div>';

  el.appendChild(head);
  el.appendChild(body);
  document.body.appendChild(el);
  fnPeekEl = el;
  fnPeekAnchor = anchorEl || null;
  fnPeekFnId = fnId;
  if (typeof anchorBelowClamped === 'function' && anchorEl) {
    anchorBelowClamped(el, anchorEl);
  }
  if (typeof focusIntoDialog === 'function') focusIntoDialog(el);

  try {
    const r = await fetch('/partials/inspector-detail?fn-id=' + encodeURIComponent(fnId));
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const txt = await r.text();
    // The panel may have been dismissed (or re-targeted) mid-fetch.
    if (fnPeekEl !== el) return;
    body.innerHTML = txt;
    if (typeof formatServerTypeTexts === 'function') formatServerTypeTexts(body);
  } catch (_) {
    if (fnPeekEl !== el) return;
    body.innerHTML = '<div class="gd-insp-sec-empty">Could not load bindings.</div>';
  }
}

installPopoverDismiss({
  getEl: () => fnPeekEl,
  getAnchor: () => fnPeekAnchor,
  isVisible: fnPeekVisible,
  onDismiss: hideFnPeek,
  trapFocus: true,
  getReturnFocus: () => fnPeekAnchor,
});

window.openFnPeek = openFnPeek;
window.hideFnPeek = hideFnPeek;

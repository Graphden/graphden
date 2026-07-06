// Graphden runtime — generic dispatcher + partial-loader
// primitives shared between the editor's row-actions popover,
// the contact-demo page, and any future fn-def-built page that
// needs `data-action="X"` click routing.
//
// The dispatch + fetch-and-swap pattern was extracted from
// editor-row-actions.js during the HTMX Phase-A migration so
// component fn-defs / partials register their `data-action`
// handlers via `registerActionHandler`, then any partial fetched
// through `loadPartial` automatically dispatches clicks to them.
//
// Public surface:
//   - registerActionHandler(action, fn)
//   - bindActionDispatch(host)
//   - loadPartial(host, url, opts)
//   - getActionHandler(action)            // for tests
//   - clearActionHandlers()                // for tests
//
// No external dependencies. Two consumers:
//   - editor bundle (/assets/editor.js) — loaded first in
//     :_editor-script-paths so editor-row-actions.js sees
//     these primitives.
//   - user-page runtime bundle (/assets/graphden-runtime.js) —
//     concat'd with graphden-actions-builtin.js by
//     :_graphden-runtime-js-body (in app/editor/fns.edn).


// =============================================================================
// ACTION-HANDLER REGISTRY
// =============================================================================
//
// The dispatcher routes `data-action="X"` clicks to the registered
// handler for `X`. Handlers are functions `(btn, event, host) =>
// void`; they read `btn.dataset.*` / `host.dataset.*` for the
// inputs they need. Registration is append-only at file-load
// time — siblings (editor-row-actions.js, future component JS)
// register their handlers once; the dispatcher resolves the
// right one at click time.

const _actionHandlers = new Map();


function registerActionHandler(action, fn) {
  if (typeof action !== 'string' || !action) {
    throw new Error('registerActionHandler: action must be a non-empty string');
  }
  if (typeof fn !== 'function') {
    throw new Error('registerActionHandler: fn must be a function');
  }
  _actionHandlers.set(action, fn);
}


function getActionHandler(action) {
  return _actionHandlers.get(action);
}


function clearActionHandlers() {
  _actionHandlers.clear();
}


// =============================================================================
// DELEGATED DISPATCH
// =============================================================================
//
// One delegated click listener per host element handles every
// `data-action` button inside. The runtime's contract:
//   - aria-disabled="true" → surface btn.title via
//     showIconReasonPopover (if loaded), stop propagation, no
//     handler invoked.
//   - otherwise → look up dataset.action in the registry; if
//     found, invoke handler(btn, event, host).
//
// Handlers themselves decide whether to preventDefault /
// stopPropagation — the runtime doesn't, because some actions
// (`open` for `<a target="_blank">`) want the default behaviour.

function bindActionDispatch(host) {
  if (!host) return;
  // Bind ONCE per host. `loadPartial` calls this after every swap, and
  // many hosts (the row-actions / execute popovers) are process-lifetime
  // SINGLETONS reused across opens — without this guard each open stacks
  // another delegated listener, so one click on a `data-action` button
  // fires the handler N times (N = opens). Destructive actions
  // (delete-fn / extend-fn / namespace-move) would execute repeatedly.
  // Binding once is safe: the dispatcher reads the CURRENT button off the
  // event and looks the handler up dynamically in `_actionHandlers`, so a
  // single listener serves all future swapped content.
  if (host.dataset.gdDispatchBound) return;
  host.dataset.gdDispatchBound = '1';
  host.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-action]');
    if (!btn || !host.contains(btn)) return;
    if (btn.getAttribute('aria-disabled') === 'true') {
      e.preventDefault();
      e.stopPropagation();
      if (typeof showIconReasonPopover === 'function' && btn.title) {
        showIconReasonPopover(btn, btn.title);
      }
      return;
    }
    const action = btn.dataset.action;
    const handler = _actionHandlers.get(action);
    if (handler) handler(btn, e, host);
  });
}


// =============================================================================
// PARTIAL FETCH + SWAP
// =============================================================================
//
// Fetch an HTML partial and swap it into `host`. After the swap:
//   - `opts.onSwap(host)` (if provided) runs first — for partial-
//     specific hover handlers / post-swap state hookup.
//   - `bindActionDispatch(host)` runs second — the generic
//     `data-action` dispatcher kicks in for every button in the
//     swapped content.
//
// Error states render a small placeholder so the popover doesn't
// stay empty / blow up on transient network failures.

async function loadPartial(host, url, opts) {
  opts = opts || {};
  if (!host || !url) return;
  host.textContent = '';
  const loading = document.createElement('span');
  loading.className = opts.loadingClass || 'partial-loading';
  loading.style.opacity = '0.55';
  loading.style.fontSize = '11px';
  loading.textContent = opts.loadingText || '…';
  host.appendChild(loading);
  try {
    const r = await fetch(url, opts.fetchOpts || {});
    if (!r.ok) {
      _renderPartialError(host, opts, 'Failed');
      return;
    }
    host.innerHTML = await r.text();
    if (typeof opts.onSwap === 'function') opts.onSwap(host);
    bindActionDispatch(host);
  } catch (_) {
    _renderPartialError(host, opts, 'Network');
  }
}


function _renderPartialError(host, opts, label) {
  host.textContent = '';
  const err = document.createElement('span');
  err.className = opts.errorClass || 'partial-error';
  err.style.color = 'var(--error-fg)';
  err.textContent = label;
  host.appendChild(err);
}

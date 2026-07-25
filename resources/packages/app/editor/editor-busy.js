// Editor Busy — visible feedback for multi-step user actions.
//
// Inline single-PUT edits (rename, value-set, type-set) finish in a
// single round-trip and don't need this — the popover Save button
// already disables itself for the duration. This module is for
// cascades that span multiple HTTP calls + an initGraph refetch:
//
//   - Reparent          (Phase 3 cascade — N orphan-binding deletes
//                        + parent-ids PUT + initGraph)
//   - Extend            (POST fn + initGraph + selectFnByName)
//   - Delete fn         (DELETE fn + initGraph)
//   - Any future        (use withBusy from the call site)
//
// User-visible result during a cascade:
//   - Cursor flips to `cursor: progress` (CSS `body.editor-busy`)
//   - Bottom-centre banner with a small spinner + the action's
//     label ("Re-parenting web-server…", "Creating my-handler…")
//   - role="status" + aria-live="polite" so screen readers
//     announce the in-flight work
//   - isOpInflight(opKey) lets call sites no-op a redundant
//     trigger (double-click on Save, etc.)

const inflightOps = new Map(); // opKey → { label, startedAt }
let bannerEl = null;

function ensureBanner() {
  if (bannerEl) return bannerEl;
  bannerEl = document.createElement('div');
  bannerEl.className = 'editor-busy-banner';
  bannerEl.setAttribute('role', 'status');
  bannerEl.setAttribute('aria-live', 'polite');
  bannerEl.setAttribute('aria-label', 'Editor activity status');
  document.body.appendChild(bannerEl);
  return bannerEl;
}

function renderBanner() {
  const el = ensureBanner();
  if (inflightOps.size === 0) {
    el.classList.remove('visible');
    el.textContent = '';
    return;
  }
  // Show the most-recently-started op's label. With reentrancy
  // (rare but possible when an action triggers another) the user
  // sees the inner action; outer continues silently and the banner
  // updates once it finishes.
  let latestLabel = '';
  let latestAt = 0;
  inflightOps.forEach(({ label, startedAt }) => {
    if (startedAt >= latestAt) {
      latestLabel = label;
      latestAt = startedAt;
    }
  });
  // Plain text — the spinner glyph is a CSS ::before pseudo so
  // textContent stays clean for screen readers.
  el.textContent = latestLabel || 'Working…';
  el.classList.add('visible');
}

// Wrap an async operation. The opKey lets call sites detect
// re-entry (`isOpInflight('reparent:' + fnId)`) and skip
// duplicates; the label is the user-visible message. Errors
// propagate after the inflight slot is cleared, so a try/catch
// around `withBusy(…)` works as expected.
async function withBusy(opKey, label, fn) {
  inflightOps.set(opKey, { label, startedAt: Date.now() });
  document.body.classList.add('editor-busy');
  renderBanner();
  try {
    return await fn();
  } finally {
    inflightOps.delete(opKey);
    if (inflightOps.size === 0) {
      document.body.classList.remove('editor-busy');
    }
    renderBanner();
  }
}

function isOpInflight(opKey) {
  return inflightOps.has(opKey);
}

window.withBusy = withBusy;

// One-shot visible warning on the same bottom-centre banner — for
// conditions the user must SEE (ambiguous name resolution picking a
// first-match), where a console.warn is DevTools-only. Auto-hides;
// an in-flight busy op repaints over it naturally.
function showTransientWarning(text, ms) {
  const el = ensureBanner();
  el.textContent = text;
  el.classList.add('visible', 'warning');
  setTimeout(() => {
    if (inflightOps.size === 0) {
      el.classList.remove('visible');
      el.textContent = '';
    }
    el.classList.remove('warning');
  }, ms || 6000);
}
window.showTransientWarning = showTransientWarning;
window.isOpInflight = isOpInflight;

// editor-assets.js — the Operate "Assets" section (UI Step 1): frontend
// bundle files with their override status, and an inline editor that
// saves per-branch `:resource-override` rows shadowing the shipped
// JS/CSS. Server-rendered partial (/partials/assets-panel); htmx owns
// the whole edit/save/revert flow — this module only mounts the shell.
//
// Globals consumed: isAuthenticated, graphdenTenancyActive.

function buildAssetsSection() {
  if (!isAuthenticated()) return null;
  // Cloud: `:resource-override` writes are system-only (tenant-forbidden
  // even for a platform-admin through the generic path — stored-XSS
  // surface), so a panel that can't save is noise. Self-host /
  // single-tenant only.
  if (typeof window.graphdenTenancyActive === 'function' && window.graphdenTenancyActive()) {
    return null;
  }
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-assets';
  wrap.innerHTML = ''
    + '<div class="ns-children" hx-get="/partials/assets-panel" hx-trigger="load" hx-swap="innerHTML">'
    +   '<div class="loading">Loading…</div>'
    + '</div>';
  // Same lazy-load contract as the other admin sections: the CALLER runs
  // htmx.process after appending to the connected DOM (mountAdminSection).
  return wrap;
}

// ---------------------------------------------------------------------
// Save gate: a syntax-broken JS override would kill the WHOLE
// concatenated bundle on the next load (the panel included), so block
// the save client-side. `new Function` compiles without executing.
// CSS is deliberately not gated — browsers skip broken rules, nothing
// bricks. The env rescue hatch (GRAPHDEN_DISABLE_ASSET_OVERRIDES=1)
// remains the belt behind this suspender.
// ---------------------------------------------------------------------
function gdAssetShowError(form, msg) {
  let err = form.querySelector('.gd-asset-error');
  if (!err) {
    err = document.createElement('div');
    err.className = 'gd-asset-error';
    form.insertBefore(err, form.firstChild);
  }
  err.textContent = msg;
}

document.addEventListener('htmx:configRequest', (e) => {
  const form = e.detail?.elt;
  if (!form?.classList?.contains('gd-asset-edit-form')) return;
  // Clear any stale client-side error each attempt — otherwise a fixed
  // body whose save then fails server-side (no swap) would still show
  // the old "JS syntax error" next to correct code.
  form.querySelector('.gd-asset-error')?.remove();
  const ta = form.querySelector('textarea[name="content"]');
  // Only JS is gated (data-lang is js|css|json|text; a non-JS override
  // can't brick the concatenated bundle). ta.value is kept in sync by
  // the CodeMirror updateListener, so it's the live content.
  if (ta?.dataset.lang !== 'js') return;
  try {
    new Function(ta.value);
  } catch (err) {
    e.preventDefault();
    gdAssetShowError(form, 'Not saved — JS syntax error: ' + err.message);
  }
});

// ---------------------------------------------------------------------
// Diff vs baseline — a read-only CodeMirror MergeView of the shipped
// file against the CURRENT editor content (unsaved edits included).
// Toggles in place of the edit form.
// ---------------------------------------------------------------------
document.addEventListener('click', async (e) => {
  const btn = e.target?.closest?.('.gd-asset-diff-btn');
  if (!btn) return;
  const slot = document.getElementById('gd-asset-editor');
  const form = slot?.querySelector('form.gd-asset-edit-form');
  const ta = form?.querySelector('textarea[name="content"]');
  if (!slot || !form || !ta) return;
  const open = slot.querySelector('.gd-asset-diff');
  if (open) {
    open.remove();
    form.style.display = '';
    btn.textContent = 'Diff vs baseline';
    return;
  }
  if (!window.CM?.MergeView) return;
  let baseline = '';
  try {
    const url = window.API.api_assets_baseline + '?path=' + encodeURIComponent(btn.dataset.path || '');
    const r = await authFetch(url);
    baseline = await r.text();
  } catch (_) { return; }
  const current = window.gdCode ? window.gdCode.get(ta) : ta.value;
  const holder = document.createElement('div');
  holder.className = 'gd-asset-diff';
  const ro = window.CM.EditorView.editable.of(false);
  new window.CM.MergeView({
    a: { doc: baseline, extensions: [ro] },
    b: { doc: current, extensions: [ro] },
    parent: holder,
  });
  form.style.display = 'none';
  form.insertAdjacentElement('afterend', holder);
  btn.textContent = 'Back to edit';
});

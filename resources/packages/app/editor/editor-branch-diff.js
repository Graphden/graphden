// Editor branch-diff modal — opened from the Δ button in the branch
// popover. Fills its body from `/partials/branch-diff?target=…
// &source=…`; the server-rendered hiccup carries the 3-section
// grouping, the branch-local annotations, and the per-row
// `data-diff-*` markers the navigation handler binds against.
//
// This module owns: modal chrome (overlay + card + header + close
// button), fetch glue, dismissal (X / Esc / overlay-click), and
// post-swap navigation binding (row click → either switchToBranch
// for added-in-source rows or selectFn for the others). The body
// hiccup itself lives in `app.editor` fn-defs.

let _branchDiffModal = null;

function ensureBranchDiffModal() {
  if (_branchDiffModal) return _branchDiffModal;
  const el = document.createElement('div');
  el.id = 'branch-diff-modal';
  el.className = 'branch-diff-modal hidden';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'true');
  el.setAttribute('aria-label', 'Branch diff');
  document.body.appendChild(el);
  _branchDiffModal = el;
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !el.classList.contains('hidden')) {
      closeBranchDiffModal();
    }
  });
  return el;
}

function closeBranchDiffModal() {
  if (_branchDiffModal) _branchDiffModal.classList.add('hidden');
}

function escapeText(s) {
  const d = document.createElement('div');
  d.textContent = s === undefined || s === null ? '' : String(s);
  return d.innerHTML;
}

async function showBranchDiff(targetName, sourceName) {
  if (!targetName || !sourceName) return;
  const modal = ensureBranchDiffModal();
  modal.classList.remove('hidden');
  modal.innerHTML =
    '<div class="branch-diff-overlay"></div>'
    + '<div class="branch-diff-card">'
    +   '<div class="branch-diff-header">'
    +     'Diff: <strong>' + escapeText(sourceName)
    +     '</strong> → <strong>' + escapeText(targetName) + '</strong>'
    +     '<button class="branch-diff-close" aria-label="Close">×</button>'
    +   '</div>'
    +   '<div class="branch-diff-body branch-diff-loading">Loading diff…</div>'
    + '</div>';
  modal.querySelector('.branch-diff-overlay')
    .addEventListener('click', closeBranchDiffModal);
  modal.querySelector('.branch-diff-close')
    .addEventListener('click', closeBranchDiffModal);

  const body = modal.querySelector('.branch-diff-body');
  try {
    const resp = await window.authFetch(
      '/partials/branch-diff?target=' + encodeURIComponent(targetName)
      + '&source=' + encodeURIComponent(sourceName));
    if (resp.status === 401) {
      body.classList.remove('branch-diff-loading');
      body.innerHTML = '<div class="branch-diff-error">Sign in to view branch diffs.</div>';
      return;
    }
    if (!resp.ok) {
      body.classList.remove('branch-diff-loading');
      body.innerHTML = '<div class="branch-diff-error">HTTP ' + resp.status + '</div>';
      return;
    }
    body.classList.remove('branch-diff-loading');
    body.innerHTML = await resp.text();
    if (window.htmx?.process) window.htmx.process(body);
    bindDiffRowNavigation(body, sourceName);
  } catch (err) {
    body.classList.remove('branch-diff-loading');
    body.innerHTML = '<div class="branch-diff-error">Failed: '
      + escapeText(err?.message || 'network error') + '</div>';
  }
}

// Post-swap row-click navigation. Each `.branch-diff-row[data-diff-fn-id]`
// either:
//   - `added-in-source` → switch to source branch first (the fn
//     doesn't exist on the current branch), push the hash so the
//     post-reload resolver finds it by name
//   - else → selectFn directly
function bindDiffRowNavigation(rootEl, sourceName) {
  rootEl.querySelectorAll('[data-diff-fn-id]').forEach((row) => {
    row.addEventListener('click', () => {
      const id = row.getAttribute('data-diff-fn-id');
      if (!id) return;
      const change = row.getAttribute('data-diff-change');
      const fnName = row.getAttribute('data-diff-fn-name');
      if (change === 'added-in-source' && fnName
          && typeof switchToBranch === 'function') {
        const proceed = confirm(
          'This fn lives only on "' + sourceName + '". '
          + 'Switch to that branch to view :' + fnName + '?');
        if (!proceed) return;
        closeBranchDiffModal();
        try { window.history.pushState(null, '', '#' + fnName); } catch (_) {}
        switchToBranch(sourceName);
        return;
      }
      if (typeof selectFn === 'function') {
        closeBranchDiffModal();
        selectFn(id);
      }
    });
  });
}

window.showBranchDiff = showBranchDiff;

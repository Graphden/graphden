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
      e.preventDefault();   // consumed — see graphden-popover.js
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
    appendCommentsThread(body, sourceName);
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

// Review-comment thread for the SOURCE branch (the proposal under
// review), appended below the diff — GitHub-style conversation next to
// the change. All user content lands via textContent (no innerHTML —
// comment bodies and branch names are user-controlled).
async function appendCommentsThread(body, sourceName) {
  const wrap = document.createElement('div');
  wrap.className = 'branch-comments';
  const h = document.createElement('h4');
  h.textContent = 'Comments';
  wrap.appendChild(h);
  const list = document.createElement('div');
  list.className = 'branch-comments-list';
  wrap.appendChild(list);

  // Inline error slot — server rejections (a non-author's delete → 403,
  // a network drop, a too-long body) surface here instead of silently
  // no-op'ing. Cleared on the next successful action.
  const errSlot = document.createElement('div');
  errSlot.className = 'branch-comment-error hidden';
  wrap.appendChild(errSlot);
  const showErr = (msg) => {
    errSlot.textContent = msg;
    errSlot.classList.remove('hidden');
  };
  const clearErr = () => {
    errSlot.textContent = '';
    errSlot.classList.add('hidden');
  };

  const render = (comments) => {
    list.textContent = '';
    if (!comments.length) {
      const empty = document.createElement('div');
      empty.className = 'branch-comment-empty';
      empty.textContent = 'No comments yet.';
      list.appendChild(empty);
      return;
    }
    for (const c of comments) {
      const row = document.createElement('div');
      row.className = 'branch-comment';
      const meta = document.createElement('span');
      meta.className = 'branch-comment-meta';
      meta.textContent = (c['author-id'] || 'anonymous') + ' · '
        + String(c['created-at'] || '').slice(0, 16);
      const text = document.createElement('span');
      text.className = 'branch-comment-body';
      text.textContent = c.body || '';
      const del = document.createElement('button');
      del.className = 'branch-comment-del';
      del.textContent = '×';
      del.title = 'Delete (author only)';
      del.addEventListener('click', async () => {
        if (del.disabled) return;
        del.disabled = true;
        clearErr();
        try {
          const r = await window.authFetch(API.api_branches_ref_comments(sourceName), {
            method: 'DELETE',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id: c.id }),
          });
          const d = await r.json().catch(() => ({}));
          if (d.ok) { reload(); return; }
          showErr(d.error || ('HTTP ' + r.status));
        } catch (e) {
          showErr('Could not delete comment: ' + (e?.message || 'network error'));
        }
        del.disabled = false;
      });
      row.appendChild(meta);
      row.appendChild(text);
      row.appendChild(del);
      list.appendChild(row);
    }
  };

  const reload = async () => {
    try {
      const r = await window.authFetch(API.api_branches_ref_comments(sourceName));
      const d = await r.json();
      if (d.ok) render(d.comments || []);
    } catch (_) { /* best-effort */ }
  };

  const form = document.createElement('div');
  form.className = 'branch-comment-form';
  const input = document.createElement('textarea');
  input.className = 'branch-comment-input';
  input.rows = 2;
  input.placeholder = 'Leave a review comment…';
  const send = document.createElement('button');
  send.className = 'branch-comment-send';
  send.textContent = 'Comment';
  send.addEventListener('click', async () => {
    if (send.disabled) return;          // no double-post on a double-click
    const text = input.value.trim();
    if (!text) return;
    send.disabled = true;
    clearErr();
    try {
      const r = await window.authFetch(API.api_branches_ref_comments(sourceName), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ body: text }),
      });
      const d = await r.json().catch(() => ({}));
      if (d.ok) { input.value = ''; reload(); }
      else showErr(d.error || ('HTTP ' + r.status));
    } catch (e) {
      showErr('Could not post comment: ' + (e?.message || 'network error'));
    }
    send.disabled = false;
  });
  form.appendChild(input);
  form.appendChild(send);
  wrap.appendChild(form);

  body.appendChild(wrap);
  reload();
}

window.showBranchDiff = showBranchDiff;

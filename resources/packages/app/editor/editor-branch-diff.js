// Editor branch-diff modal (diff v2) — opened from the Δ button in the
// branch popover. Fills its body from `/partials/branch-diff?target=…
// &source=…`; the server-rendered hiccup carries the per-owning-fn
// GROUPED rows (field-level before/after under each), the branch-local
// annotations, the per-row `data-diff-*` navigation markers, the
// `data-anchor-*` anchored-comment hooks and the suggestions mount.
//
// This module owns: modal chrome (overlay + card + header + close
// button), fetch glue, dismissal (X / Esc / overlay-click), post-swap
// navigation binding (row click → switchToBranch / selectFn, with the
// canvas diff-focus hand-off via sessionStorage), the review
// CONVERSATION (anchored threads on rows/entries + the general thread
// below the diff) and the SUGGESTIONS section (reviewer-authored child
// branches of the proposal the author can Δ-view and apply with one
// merge). The body hiccup itself lives in `app.editor` fn-defs.

let _branchDiffModal = null;
// The control that opened the modal, so Escape / × can hand the keyboard
// back to it instead of dropping focus on the document.
let _branchDiffTrigger = null;

// Canvas hand-off: clicking a diff row stashes the fn's changed slots
// here; `editor-overlay-arg.js` rings the matching arg overlays after
// the navigation lands. One-shot per stash, cleared on branch switch.
const DIFF_FOCUS_KEY = 'graphden.diffFocus';

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
  // It declares aria-modal="true"; make that true — Tab stays inside and the
  // rest of the page is hidden from assistive tech while it is up.
  installTabTrap({
    getEl: () => _branchDiffModal,
    isVisible: () => !!_branchDiffModal && !_branchDiffModal.classList.contains('hidden'),
  });
  return el;
}

function closeBranchDiffModal() {
  if (!_branchDiffModal) return;
  const hadFocus = _branchDiffModal.contains(document.activeElement);
  _branchDiffModal.classList.add('hidden');
  setSiblingsInert(_branchDiffModal, false);
  if (hadFocus) returnFocusTo(_branchDiffTrigger);
  _branchDiffTrigger = null;
}

function escapeText(s) {
  const d = document.createElement('div');
  d.textContent = s === undefined || s === null ? '' : String(s);
  return d.innerHTML;
}

async function showBranchDiff(targetName, sourceName, sourceRef) {
  if (!targetName || !sourceName) return;
  // `sourceRef` — a stable /api/branches/:ref/* path ref (branch id when
  // the caller has the row; names with "/" can't ride a path segment).
  sourceRef = sourceRef || sourceName;
  const modal = ensureBranchDiffModal();
  // Remember where the keyboard was before the modal took over —
  // unless the modal is already up (a suggestion's Δ-view swaps the
  // content in place; the original trigger stays the return target).
  if (modal.classList.contains('hidden')) {
    _branchDiffTrigger = document.activeElement;
  }
  modal.classList.remove('hidden');
  setSiblingsInert(modal, true);
  modal.innerHTML =
    '<div class="branch-diff-overlay"></div>'
    + '<div class="branch-diff-card">'
    +   '<div class="branch-diff-header">'
    +     'Diff: <strong>' + escapeText(sourceName)
    +     '</strong> → <strong>' + escapeText(targetName) + '</strong>'
    +     '<button class="branch-diff-compare" title="Stay in the diff while you work: '
    +       'badge the Explorer, ring changed args on the canvas">◐ Compare mode</button>'
    +     '<button class="branch-diff-close" aria-label="Close">×</button>'
    +   '</div>'
    +   '<div class="branch-diff-body branch-diff-loading">Loading diff…</div>'
    + '</div>';
  modal.querySelector('.branch-diff-overlay')
    .addEventListener('click', closeBranchDiffModal);
  modal.querySelector('.branch-diff-close')
    .addEventListener('click', closeBranchDiffModal);
  // The bridge from the one-shot review surface to the persistent lens:
  // close the modal, enter compare mode vs the branch being diffed.
  modal.querySelector('.branch-diff-compare')
    .addEventListener('click', () => {
      closeBranchDiffModal();
      if (typeof gdEnterDiffMode === 'function') gdEnterDiffMode(sourceName);
    });
  // Focus lands on Close: the diff body is still loading, and Close is the
  // one control that exists whichever way the fetch turns out.
  focusIntoDialog(modal);

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
    bindDiffRowNavigation(body, sourceName, targetName);
    initDiffConversation(body, sourceName, sourceRef);
    renderDiffSuggestions(body, sourceName, sourceRef, targetName);
    annotateDiffEffects(body);
  } catch (err) {
    body.classList.remove('branch-diff-loading');
    body.innerHTML = '<div class="branch-diff-error">Failed: '
      + escapeText(err?.message || 'network error') + '</div>';
  }
}

// ============================================================================
// Navigation — row click → canvas (with the diff-focus hand-off)
// ============================================================================

// Stash this row's changed slots so the canvas can ring the matching
// arg overlays after navigation. Summary text comes straight from the
// rendered entry (fields "value: 8080 → 9090" or the one-sided preview).
function stashDiffFocus(row, otherBranch) {
  try {
    const slots = {};
    row.querySelectorAll('.branch-diff-entry[data-slot-name]').forEach((e) => {
      const slot = e.getAttribute('data-slot-name');
      if (!slot) return;
      const detail = e.querySelector('.branch-diff-fields, .branch-diff-entry-preview');
      const text = (detail?.textContent || '').trim().replace(/\s+/g, ' ');
      if (!slots[slot]) slots[slot] = text;
    });
    if (!Object.keys(slots).length) {
      sessionStorage.removeItem(DIFF_FOCUS_KEY);
      return;
    }
    sessionStorage.setItem(DIFF_FOCUS_KEY, JSON.stringify({
      fnId: row.getAttribute('data-diff-fn-id'),
      // The branch the highlighted values DIFFER AGAINST — i.e. the
      // side of the diff the user is NOT about to look at.
      branch: otherBranch,
      slots,
    }));
  } catch (_) { /* focus hand-off is best-effort */ }
}

// Post-swap row-click navigation. Each `.branch-diff-row[data-diff-fn-id]`
// either:
//   - `added-in-source` → switch to source branch first (the fn
//     doesn't exist on the current branch), push the hash so the
//     post-reload resolver finds it by name
//   - else → selectFn directly
// Clicks on the conversation / suggestion controls inside a row never
// count as navigation.
function bindDiffRowNavigation(rootEl, sourceName, targetName) {
  rootEl.querySelectorAll('[data-diff-fn-id]').forEach((row) => {
    row.addEventListener('click', (e) => {
      if (e.target.closest('.branch-diff-comment-btn, .branch-diff-anchor-thread, '
                           + '.branch-comments, .branch-diff-suggestions, button, textarea')) return;
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
        // Heading to the SOURCE branch — the values there differ vs the
        // TARGET the user is leaving.
        stashDiffFocus(row, targetName);
        closeBranchDiffModal();
        try { window.history.pushState(null, '', '#' + fnName); } catch (_) {}
        switchToBranch(sourceName);
        return;
      }
      if (typeof selectFn === 'function') {
        stashDiffFocus(row, sourceName);
        closeBranchDiffModal();
        selectFn(id);
      }
    });
  });
}

// ============================================================================
// Effect-set deltas — "did the behaviour's footprint change"
// ============================================================================

// Which effect-carrying fns does each group's change wire in or out?
// Derived from the rendered rows themselves (changed refs appear as
// `:name`), with the targets' effect sets from the local registry —
// see editor-diff-mode.js for why full per-branch effect closures
// can't be compared honestly today. Modal reading order is
// was(target) → becomes(source): "+" = the source side gains it.
function annotateDiffEffects(body) {
  if (typeof gdDiffEffectsOfName !== 'function') return;
  body.querySelectorAll('.branch-diff-row[data-diff-fn-id]').forEach((row) => {
    const plus = new Set();
    const minus = new Set();
    const grab = (set, text) => {
      const m = typeof text === 'string' && text.trim().match(/^:(\S+)$/);
      if (m) gdDiffEffectsOfName(m[1]).forEach((x) => set.add(x));
    };
    row.querySelectorAll('.branch-diff-field').forEach((f) => {
      const name = f.querySelector('.branch-diff-field-name')?.textContent || '';
      if (!/ref-fn-id|type-override-fn-id/.test(name)) return;
      grab(plus, f.querySelector('.bd-new')?.textContent);
      grab(minus, f.querySelector('.bd-old')?.textContent);
    });
    row.querySelectorAll('.branch-diff-entry').forEach((e) => {
      const prev = e.querySelector('.branch-diff-entry-preview')?.textContent || '';
      const m = prev.match(/(?:ref\s*)?→\s*:(\S+)/);
      if (!m) return;
      const set = e.classList.contains('bd-removed') ? minus : plus;
      gdDiffEffectsOfName(m[1]).forEach((x) => set.add(x));
    });
    for (const x of [...plus]) {
      if (minus.has(x)) { plus.delete(x); minus.delete(x); }
    }
    if (!plus.size && !minus.size) return;
    const head = row.querySelector('.branch-diff-row-head');
    if (!head || head.querySelector('.bd-effects-chip')) return;
    const chip = document.createElement('span');
    chip.className = 'bd-effects-chip';
    const parts = [];
    if (plus.size) parts.push('+' + [...plus].sort().join(',+'));
    if (minus.size) parts.push('−' + [...minus].sort().join(',−'));
    chip.textContent = 'effects touched: ' + parts.join(' ');
    chip.title = 'This change wires effect-carrying fns '
      + (plus.size ? 'IN (' + [...plus].join(', ') + ') ' : '')
      + (minus.size ? 'OUT (' + [...minus].join(', ') + ')' : '');
    const badge = head.querySelector('.branch-diff-comment-btn');
    head.insertBefore(chip, badge || null);
  });
}

// ============================================================================
// Review conversation — anchored threads + the general thread
// ============================================================================

// One fetch of the SOURCE branch's comments feeds both surfaces:
// comments with an `entity-name`/`entity-id` anchor render as inline
// threads under their diff row/entry (orphans — anchors no longer in
// the diff — fall back to the general thread with a context chip);
// unanchored comments are the branch-level conversation below the
// diff. All user content lands via textContent (no innerHTML —
// comment bodies and branch names are user-controlled).
function initDiffConversation(body, sourceName, sourceRef) {
  sourceRef = sourceRef || sourceName;
  const state = { comments: [] };
  let reload;

  const commentRow = (c, chipText) => {
    const row = document.createElement('div');
    row.className = 'branch-comment';
    const meta = document.createElement('span');
    meta.className = 'branch-comment-meta';
    meta.textContent = (c['author-id'] || 'anonymous') + ' · '
      + String(c['created-at'] || '').slice(0, 16);
    row.appendChild(meta);
    if (chipText) {
      const chip = document.createElement('span');
      chip.className = 'branch-comment-meta';
      chip.textContent = '[' + chipText + ']';
      row.appendChild(chip);
    }
    const text = document.createElement('span');
    text.className = 'branch-comment-body';
    text.textContent = c.body || '';
    row.appendChild(text);
    const del = document.createElement('button');
    del.className = 'branch-comment-del';
    del.textContent = '×';
    del.title = 'Delete (author only)';
    del.addEventListener('click', async () => {
      if (del.disabled) return;
      del.disabled = true;
      try {
        const r = await window.authFetch(API.api_branches_ref_comments(sourceRef), {
          method: 'DELETE',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ id: c.id }),
        });
        const d = await r.json().catch(() => ({}));
        if (d.ok) { await reload(); return; }
        alert(d.error || ('Could not delete: HTTP ' + r.status));
      } catch (e2) {
        alert('Could not delete comment: ' + (e2?.message || 'network error'));
      }
      del.disabled = false;
    });
    row.appendChild(del);
    return row;
  };

  const composer = (anchorName, anchorId) => {
    const form = document.createElement('div');
    form.className = 'branch-diff-anchor-compose';
    const input = document.createElement('textarea');
    input.className = 'branch-comment-input';
    input.rows = 1;
    input.placeholder = 'Comment…';
    const send = document.createElement('button');
    send.className = 'branch-comment-send';
    send.textContent = 'Comment';
    send.addEventListener('click', async () => {
      if (send.disabled) return;         // no double-post on a double-click
      const text = input.value.trim();
      if (!text) return;
      send.disabled = true;
      try {
        const payload = { body: text };
        if (anchorName && anchorId) {
          payload['entity-name'] = anchorName;
          payload['entity-id'] = anchorId;
        }
        const r = await window.authFetch(API.api_branches_ref_comments(sourceRef), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload),
        });
        const d = await r.json().catch(() => ({}));
        if (d.ok) { input.value = ''; await reload(); }
        else alert(d.error || ('Could not post: HTTP ' + r.status));
      } catch (e2) {
        alert('Could not post comment: ' + (e2?.message || 'network error'));
      }
      send.disabled = false;
    });
    form.appendChild(input);
    form.appendChild(send);
    return form;
  };

  // Insert an anchored thread element right under its row-head / entry.
  const mountThread = (anchorEl, comments, withComposer) => {
    const thread = document.createElement('div');
    thread.className = 'branch-diff-anchor-thread';
    comments.forEach((c) => thread.appendChild(commentRow(c)));
    if (withComposer) {
      thread.appendChild(composer(anchorEl.getAttribute('data-anchor-name'),
                                  anchorEl.getAttribute('data-anchor-id')));
    }
    if (anchorEl.classList.contains('branch-diff-entry')) {
      anchorEl.insertAdjacentElement('afterend', thread);
    } else {
      const head = anchorEl.querySelector('.branch-diff-row-head');
      (head || anchorEl).insertAdjacentElement('afterend', thread);
    }
    return thread;
  };

  const render = () => {
    // Wipe the previous render (threads + general wrap + count badges).
    body.querySelectorAll('.branch-diff-anchor-thread, .branch-comments')
      .forEach((n) => n.remove());
    body.querySelectorAll('.branch-diff-comment-btn .bd-comment-count')
      .forEach((n) => n.remove());
    body.querySelectorAll('.branch-diff-comment-btn.has-comments')
      .forEach((n) => n.classList.remove('has-comments'));

    const anchored = new Map();   // "name:id" → [comments]
    const general = [];
    for (const c of state.comments) {
      if (c['entity-name'] && c['entity-id']) {
        const k = c['entity-name'] + ':' + c['entity-id'];
        if (!anchored.has(k)) anchored.set(k, []);
        anchored.get(k).push(c);
      } else {
        general.push(c);
      }
    }

    const orphans = [];
    for (const [k, cs] of anchored) {
      const id = k.slice(k.indexOf(':') + 1);
      const el = body.querySelector('[data-anchor-id="' + CSS.escape(id) + '"]');
      if (!el) { orphans.push([k, cs]); continue; }
      mountThread(el, cs, false);
      const btn = el.classList.contains('branch-diff-entry')
        ? el.querySelector('.branch-diff-comment-btn')
        : el.querySelector('.branch-diff-row-head .branch-diff-comment-btn');
      if (btn) {
        btn.classList.add('has-comments');
        const count = document.createElement('span');
        count.className = 'bd-comment-count';
        count.textContent = String(cs.length);
        btn.appendChild(count);
      }
    }

    // General thread (+ orphaned anchored comments with context chips).
    const wrap = document.createElement('div');
    wrap.className = 'branch-comments';
    const h = document.createElement('h4');
    h.textContent = 'Comments';
    wrap.appendChild(h);
    const list = document.createElement('div');
    list.className = 'branch-comments-list';
    wrap.appendChild(list);
    for (const [k, cs] of orphans) {
      const kind = k.slice(0, k.indexOf(':'));
      const id = k.slice(k.indexOf(':') + 1);
      cs.forEach((c) => list.appendChild(
        commentRow(c, 'on ' + kind + ' ' + id.slice(0, 8))));
    }
    general.forEach((c) => list.appendChild(commentRow(c)));
    if (!list.childElementCount) {
      const empty = document.createElement('div');
      empty.className = 'branch-comment-empty';
      empty.textContent = 'No comments yet.';
      list.appendChild(empty);
    }
    const form = composer(null, null);
    form.classList.add('branch-comment-form');
    wrap.appendChild(form);
    body.appendChild(wrap);
  };

  reload = async () => {
    try {
      const r = await window.authFetch(API.api_branches_ref_comments(sourceRef));
      const d = await r.json();
      if (d.ok) { state.comments = d.comments || []; render(); }
    } catch (_) { /* best-effort */ }
  };

  // 💬 → open (or focus) an inline composer under the clicked row/entry.
  body.querySelectorAll('.branch-diff-comment-btn').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const anchorEl = btn.closest('[data-anchor-id]');
      if (!anchorEl) return;
      const next = anchorEl.classList.contains('branch-diff-entry')
        ? anchorEl.nextElementSibling
        : anchorEl.querySelector('.branch-diff-anchor-thread');
      const existing = next?.classList?.contains('branch-diff-anchor-thread')
        ? next : null;
      if (existing) {
        let form = existing.querySelector('.branch-diff-anchor-compose');
        if (!form) {
          form = composer(anchorEl.getAttribute('data-anchor-name'),
                          anchorEl.getAttribute('data-anchor-id'));
          existing.appendChild(form);
        }
        form.querySelector('textarea').focus();
        return;
      }
      const thread = mountThread(anchorEl, [], true);
      thread.querySelector('textarea').focus();
    });
  });

  reload();
}

// ============================================================================
// Suggestions — reviewer-authored child branches of the proposal
// ============================================================================

// A "suggestion" needs NO new entity: it is a branch forked off the
// proposal (base = source) and itself proposed for review — the same
// propose machinery, one level down. Here we surface them: list the
// proposed children of `sourceName`, let the author Δ-view each
// against the proposal and APPLY it (an ordinary merge INTO the
// proposal — content-aware approval dismissal then works unchanged),
// and give reviewers the "Suggest a change" fork-and-switch shortcut.
async function renderDiffSuggestions(body, sourceName, sourceRef, _targetName) {
  sourceRef = sourceRef || sourceName;
  const mount = body.querySelector('.branch-diff-suggestions');
  if (!mount) return;
  let branches = [];
  try {
    const r = await window.authFetch(API.api_branches);
    const d = await r.json();
    branches = d.branches || [];
  } catch (_) { /* section stays empty on fetch failure */ }
  const src = branches.find((b) => b.name === sourceName);
  if (!src) { mount.remove(); return; }
  const suggestions = branches.filter(
    (b) => b['base-branch-id'] === src.id && b['review-state'] === 'proposed');

  mount.textContent = '';
  const h = document.createElement('h4');
  h.textContent = 'Suggestions';
  mount.appendChild(h);

  if (!suggestions.length) {
    const empty = document.createElement('div');
    empty.className = 'branch-diff-suggestions-empty';
    empty.textContent = 'No suggested changes for "' + sourceName + '" yet.';
    mount.appendChild(empty);
  }

  for (const sugg of suggestions) {
    const row = document.createElement('div');
    row.className = 'branch-diff-suggestion-row';
    const name = document.createElement('span');
    name.className = 'branch-diff-suggestion-name';
    name.textContent = sugg.name;
    row.appendChild(name);

    const view = document.createElement('button');
    view.textContent = 'Δ view';
    view.title = 'Show what this suggestion changes on "' + sourceName + '"';
    view.addEventListener('click', () => showBranchDiff(sourceName, sugg.name, sugg.id));
    row.appendChild(view);

    const apply = document.createElement('button');
    apply.textContent = '⇢ apply';
    apply.title = 'Merge "' + sugg.name + '" into "' + sourceName
      + '" (the proposal picks up the suggestion)';
    apply.addEventListener('click', async () => {
      if (!confirm('Apply suggestion "' + sugg.name + '" — merge it into "'
                   + sourceName + '"?')) return;
      apply.disabled = true;
      try {
        const r = await window.authFetch(API.api_branches_ref_merge(sourceRef), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ source: sugg.name }),
        });
        const d = await r.json().catch(() => ({}));
        if (r.ok && d.ok !== false) { location.reload(); return; }
        alert(d.error || (d.reason === 'merge-conflict'
          ? 'The suggestion conflicts with the proposal — merge it from the branch popover to resolve.'
          : 'Could not apply: HTTP ' + r.status));
      } catch (_e) {
        // Fetch severed = the merge committed and the affected services
        // restarted (same split as mergeBranchInto) — reload to the
        // post-merge state.
        location.reload();
        return;
      }
      apply.disabled = false;
    });
    row.appendChild(apply);
    mount.appendChild(row);
  }

  const suggest = document.createElement('button');
  suggest.className = 'branch-diff-suggest-new';
  suggest.textContent = '+ Suggest a change';
  suggest.title = 'Fork a branch off "' + sourceName
    + '", make your edits there, then propose it — the author applies it with one click';
  suggest.addEventListener('click', async () => {
    // No '/' in the default: the /api/branches/:ref/* ops read the ref
    // as ONE path segment, so a slash-named branch can't be proposed /
    // commented / deleted by ref.
    const dflt = 'suggest-' + sourceName + '-'
      + Math.random().toString(36).slice(2, 6);
    const name = prompt('Name for your suggestion branch (forks off "'
                        + sourceName + '"):', dflt);
    if (!name) return;
    suggest.disabled = true;
    try {
      const r = await window.authFetch(API.api_branches, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: name.trim(), 'base-branch-id': sourceName }),
      });
      const d = await r.json().catch(() => ({}));
      if (r.ok && d.ok !== false) {
        // Work happens on the new branch: switch (reloads the page).
        switchToBranch(name.trim());
        return;
      }
      alert(d.error || ('Could not create the branch: HTTP ' + r.status));
    } catch (e2) {
      alert('Could not create the branch: ' + (e2?.message || 'network error'));
    }
    suggest.disabled = false;
  });
  mount.appendChild(suggest);
}

window.showBranchDiff = showBranchDiff;

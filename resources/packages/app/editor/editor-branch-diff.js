// Editor REVIEW dialog — the merge-request conversation surface.
//
// UX-v3 (2026-08-31): the old full-diff modal is gone. Δ on a branch
// row now toggles COMPARE MODE (editor-diff-mode.js) — the diff is
// read in the Explorer (lenses + badges + ghosts), on the canvas
// (rings) and in the inspector (per-fn details + anchored threads).
// What remains HERE is the branch-level review conversation, opened
// from a row's ⋯ menu ("💬 Review & comments") or the Δ chip's
// cockpit:
//   - the proposal framing: source → its BASE branch (a merge
//     request reviews a branch INTO its base, not into wherever the
//     reader happens to stand);
//   - a collapsible "What changed" list (client-rendered from the
//     same diff-view JSON compare mode uses — the at-a-glance list
//     survives, it just stopped being the primary surface);
//   - anchored 💬 threads on those rows + the general comment thread;
//   - suggestions (proposed child branches) with a per-suggestion
//     collapsible Δ preview and one-click ⇢ apply.
//
// All user content lands via textContent (no innerHTML — comment
// bodies and branch names are user-controlled).

let _branchDiffModal = null;
// The control that opened the dialog, so Escape / × can hand the
// keyboard back to it instead of dropping focus on the document.
let _branchDiffTrigger = null;

function ensureBranchDiffModal() {
  if (_branchDiffModal) return _branchDiffModal;
  const el = document.createElement('div');
  el.id = 'branch-diff-modal';
  el.className = 'branch-diff-modal hidden';
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'true');
  el.setAttribute('aria-label', 'Branch review');
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

// ============================================================================
// The review dialog
// ============================================================================

async function showReviewDialog(sourceName, sourceRef) {
  if (!sourceName) return;
  sourceRef = sourceRef || sourceName;
  const modal = ensureBranchDiffModal();
  if (modal.classList.contains('hidden')) {
    _branchDiffTrigger = document.activeElement;
  }
  modal.classList.remove('hidden');
  setSiblingsInert(modal, true);
  modal.innerHTML =
    '<div class="branch-diff-overlay"></div>'
    + '<div class="branch-diff-card">'
    +   '<div class="branch-diff-header">'
    +     'Review: <strong>' + escapeText(sourceName) + '</strong>'
    +     '<span class="branch-diff-header-into"></span>'
    +     '<button class="branch-diff-close" aria-label="Close">×</button>'
    +   '</div>'
    +   '<div class="branch-diff-body branch-diff-loading">Loading…</div>'
    + '</div>';
  modal.querySelector('.branch-diff-overlay')
    .addEventListener('click', closeBranchDiffModal);
  modal.querySelector('.branch-diff-close')
    .addEventListener('click', closeBranchDiffModal);
  focusIntoDialog(modal);

  const body = modal.querySelector('.branch-diff-body');
  try {
    // The proposal frame: source → its BASE. One list fetch resolves
    // the base row + the id-safe refs.
    const rows = (await (await window.authFetch(API.api_branches)).json())
      ?.branches || [];
    const srcRow = rows.find((b) => b.name === sourceName)
      || rows.find((b) => b.id === sourceRef);
    const baseRow = srcRow
      && rows.find((b) => b.id === srcRow['base-branch-id']);
    const baseName = baseRow?.name || 'main';
    sourceRef = srcRow?.id || sourceRef;
    modal.querySelector('.branch-diff-header-into').textContent =
      ' → ' + baseName;

    body.classList.remove('branch-diff-loading');
    body.textContent = '';

    // --- "What changed" — collapsible, client-rendered from the same
    // grouped JSON compare mode uses. Fetched eagerly (the count in
    // the summary IS the review's headline).
    const view = await (await window.authFetch(
      API.api_branches_ref_diff_view(baseRow?.id || baseName)
      + '?against=' + encodeURIComponent(sourceRef))).json();
    const details = document.createElement('details');
    details.className = 'bd-review-changes';
    details.open = (view.count || 0) > 0 && (view.count || 0) <= 20;
    const summary = document.createElement('summary');
    summary.textContent = 'What changed — ' + (view.count || 0)
      + ' difference(s) across ' + (view.groups?.length || 0) + ' function(s)';
    details.appendChild(summary);
    const listMount = document.createElement('div');
    details.appendChild(listMount);
    body.appendChild(details);
    if (typeof gdDiffRenderGroups === 'function') {
      gdDiffRenderGroups(listMount, view.groups || []);
    }
    if (!view.ok) {
      summary.textContent = 'What changed — unavailable ('
        + (view.error || 'error') + ')';
    }

    // Effect-set chips on the rendered rows (base vs source).
    annotateDiffEffects(body, sourceName, baseName);
    // Anchored threads on the rendered rows + the general thread below.
    initDiffConversation(body, sourceName, sourceRef, {});
    // Suggestions with their Δ previews.
    renderDiffSuggestions(body, sourceName, sourceRef);
  } catch (err) {
    body.classList.remove('branch-diff-loading');
    body.innerHTML = '<div class="branch-diff-error">Failed: '
      + escapeText(err?.message || 'network error') + '</div>';
  }
}

// ============================================================================
// Effect-set deltas — "did the behaviour's footprint change"
// ============================================================================

// Per-row effect chips: the registry is branch-scoped, so the primary
// source is a FULL effect-set comparison between the two branches'
// `/api/types`. Rows without a name on both sides fall back to the
// structural signal — which effect-carrying fns the rendered refs
// wire in/out. Reading order is was(base/target) → becomes(source).
async function annotateDiffEffects(body, sourceName, targetName) {
  if (typeof gdDiffEffectsOfName !== 'function') return;
  let tgtTypes = null;
  let srcTypes = null;
  try {
    [tgtTypes, srcTypes] = await Promise.all([
      gdDiffFetchTypes(targetName),
      gdDiffFetchTypes(sourceName),
    ]);
  } catch (_) { /* structural fallback only */ }
  if (!body.isConnected) return;
  body.querySelectorAll('.branch-diff-row[data-diff-fn-id]').forEach((row) => {
    const fnName = row.getAttribute('data-diff-fn-name');
    if (fnName && tgtTypes && srcTypes
        && typeof gdDiffEffectSetDelta === 'function') {
      const d = gdDiffEffectSetDelta(tgtTypes, srcTypes, fnName);
      if (d) {
        const head = row.querySelector('.branch-diff-row-head');
        if (head && !head.querySelector('.bd-effects-chip')) {
          const chip = document.createElement('span');
          chip.className = 'bd-effects-chip';
          chip.textContent = 'effects: ' + gdDiffShowEffects(d.here)
            + ' → ' + gdDiffShowEffects(d.there);
          chip.title = 'The effect set differs between "' + targetName
            + '" and "' + sourceName + '" — behaviour footprint changed';
          head.insertBefore(chip, head.querySelector('.branch-diff-comment-btn') || null);
        }
        return;
      }
      // Named on both sides and EQUAL → no chip, and no structural
      // guess either (the closure truth beats the ref heuristic).
      if (tgtTypes[fnName] && srcTypes[fnName]) return;
    }
    const plus = new Set();
    const minus = new Set();
    const grab = (set, text) => {
      const m = typeof text === 'string' && text.trim().match(/^:(\S+)$/);
      if (m) gdDiffEffectsOfName(m[1]).forEach((x) => { set.add(x); });
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
      gdDiffEffectsOfName(m[1]).forEach((x) => { set.add(x); });
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
    head.insertBefore(chip, head.querySelector('.branch-diff-comment-btn') || null);
  });
}

// ============================================================================
// Review conversation — anchored threads + the general thread
// ============================================================================

// One fetch of the SOURCE branch's comments feeds both surfaces:
// comments with an `entity-name`/`entity-id` anchor render as inline
// threads under whatever `[data-anchor-id]` elements exist inside
// `container` (the dialog's change list, or the inspector's diff
// panel); unanchored comments are the branch-level conversation.
// `opts.anchoredOnly` — the inspector case: only threads whose anchor
// is PRESENT here; no general thread, no orphans (they belong to the
// review dialog).
function initDiffConversation(container, sourceName, sourceRef, opts) {
  sourceRef = sourceRef || sourceName;
  const anchoredOnly = !!opts?.anchoredOnly;
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
        alert(d.message || d.error || ('Could not delete: HTTP ' + r.status));
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
        else alert(d.message || d.error || ('Could not post: HTTP ' + r.status));
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
    comments.forEach((c) => { thread.appendChild(commentRow(c)); });
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
    if (!container.isConnected) return;
    // Wipe the previous render (threads + general wrap + count badges).
    container.querySelectorAll('.branch-diff-anchor-thread, .branch-comments')
      .forEach((n) => { n.remove(); });
    container.querySelectorAll('.branch-diff-comment-btn .bd-comment-count')
      .forEach((n) => { n.remove(); });
    container.querySelectorAll('.branch-diff-comment-btn.has-comments')
      .forEach((n) => { n.classList.remove('has-comments'); });

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
      const el = container.querySelector('[data-anchor-id="' + CSS.escape(id) + '"]');
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

    if (anchoredOnly) return;

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
      cs.forEach((c) => { list.appendChild(
        commentRow(c, 'on ' + kind + ' ' + id.slice(0, 8))); });
    }
    general.forEach((c) => { list.appendChild(commentRow(c)); });
    if (!list.childElementCount) {
      const empty = document.createElement('div');
      empty.className = 'branch-comment-empty';
      empty.textContent = 'No comments yet.';
      list.appendChild(empty);
    }
    const form = composer(null, null);
    form.classList.add('branch-comment-form');
    wrap.appendChild(form);
    container.appendChild(wrap);
  };

  reload = async () => {
    try {
      const r = await window.authFetch(API.api_branches_ref_comments(sourceRef));
      const d = await r.json();
      if (d.ok) { state.comments = d.comments || []; render(); }
    } catch (_) { /* best-effort */ }
  };

  // 💬 → open (or focus) an inline composer under the clicked row/entry.
  container.querySelectorAll('.branch-diff-comment-btn').forEach((btn) => {
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
// propose machinery, one level down. List the proposed children of
// `sourceName`, each with a collapsible Δ preview (client-rendered
// from diff-view: suggestion vs the proposal) and one-click APPLY (an
// ordinary merge INTO the proposal — content-aware approval dismissal
// then works unchanged), plus the "Suggest a change" fork-and-switch.
async function renderDiffSuggestions(body, sourceName, sourceRef) {
  sourceRef = sourceRef || sourceName;
  let mount = body.querySelector('.branch-diff-suggestions');
  if (!mount) {
    mount = document.createElement('div');
    mount.className = 'branch-diff-suggestions';
    mount.id = 'branch-diff-suggestions';
    body.appendChild(mount);
  }
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
        alert(d.message || d.error || (d.reason === 'merge-conflict'
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

    // Collapsible Δ preview — fetched on first expand.
    const details = document.createElement('details');
    details.className = 'bd-sugg-preview';
    const summary = document.createElement('summary');
    summary.textContent = 'Δ what it changes';
    details.appendChild(summary);
    const pv = document.createElement('div');
    details.appendChild(pv);
    let loaded = false;
    details.addEventListener('toggle', async () => {
      if (!details.open || loaded) return;
      loaded = true;
      pv.textContent = 'Loading…';
      try {
        const v = await (await window.authFetch(
          API.api_branches_ref_diff_view(sourceRef)
          + '?against=' + encodeURIComponent(sugg.id))).json();
        pv.textContent = '';
        if (typeof gdDiffRenderGroups === 'function') {
          gdDiffRenderGroups(pv, v.groups || [], { comments: false });
        }
        if (!(v.groups || []).length) pv.textContent = 'No differences.';
      } catch (_) { pv.textContent = 'Preview unavailable.'; }
    });
    mount.appendChild(details);
  }

  const suggest = document.createElement('button');
  suggest.className = 'branch-diff-suggest-new';
  suggest.textContent = '+ Suggest a change';
  suggest.title = 'Fork a branch off "' + sourceName
    + '", make your edits there, then propose it — the author applies it with one click';
  suggest.addEventListener('click', async () => {
    // No '/' in the default: the /api/branches/:ref/* ops read the ref
    // as ONE path segment (slash-safe ops go by id, but names travel
    // too — keep them simple).
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
      alert(d.message || d.error || ('Could not create the branch: HTTP ' + r.status));
    } catch (e2) {
      alert('Could not create the branch: ' + (e2?.message || 'network error'));
    }
    suggest.disabled = false;
  });
  mount.appendChild(suggest);
}

window.showReviewDialog = showReviewDialog;
window.gdDiffAttachThreads = initDiffConversation;

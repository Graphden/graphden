// Editor branch-diff modal — opened from the Δ button in the branch
// popover. Calls GET /api/branches/:target/diff?against=:source and
// renders the entry list grouped by :change tag, each with a brief
// preview of the affected entity. Read-only; merge / delete live in
// the popover.
//
// Backed by `graphden.crud.branches/diff-branches` →
// `graphden.versioning.storage.merge/diff-branches`. Closes via the
// X button, Esc, or click on the overlay backdrop.

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

  let body;
  try {
    const resp = await window.authFetch(
      '/api/branches/' + encodeURIComponent(targetName)
      + '/diff?against=' + encodeURIComponent(sourceName));
    if (resp.status === 401) {
      replaceDiffBody(modal,
        '<div class="branch-diff-error">Sign in to view branch diffs.</div>');
      return;
    }
    if (!resp.ok) {
      replaceDiffBody(modal,
        '<div class="branch-diff-error">HTTP ' + resp.status + '</div>');
      return;
    }
    body = await resp.json();
  } catch (err) {
    replaceDiffBody(modal,
      '<div class="branch-diff-error">Failed: '
      + escapeText(err?.message || 'network error') + '</div>');
    return;
  }

  if (body?.ok === false) {
    replaceDiffBody(modal,
      '<div class="branch-diff-error">'
      + escapeText(body.error || 'Diff failed') + '</div>');
    return;
  }

  renderDiffBody(modal, body, targetName, sourceName);
}

function replaceDiffBody(modal, innerHtml) {
  const body = modal.querySelector('.branch-diff-body');
  if (body) {
    body.classList.remove('branch-diff-loading');
    body.innerHTML = innerHtml;
  }
}

function renderDiffBody(modal, body, targetName, sourceName) {
  const diffs = body.diffs || [];
  if (diffs.length === 0) {
    replaceDiffBody(modal,
      '<div class="branch-diff-empty">No differences — '
      + escapeText(sourceName) + ' and ' + escapeText(targetName)
      + ' resolve to the same view.</div>');
    return;
  }
  // Group by change tag so the user reads "what's new vs modified vs gone"
  // in stable order.
  const grouped = {
    'added-in-source': [],
    'added-in-target': [],
    modified: [],
  };
  for (const d of diffs) {
    if (!grouped[d.change]) grouped[d.change] = [];
    grouped[d.change].push(d);
  }

  const sectionHtml = (label, key, hint) => {
    const rows = grouped[key];
    if (!rows || rows.length === 0) return '';
    return ''
      + '<div class="branch-diff-section">'
      +   '<div class="branch-diff-section-head">'
      +     escapeText(label) + ' <span class="branch-diff-count">'
      +     rows.length + '</span>'
      +     '<span class="branch-diff-section-hint">' + escapeText(hint) + '</span>'
      +   '</div>'
      +   '<div class="branch-diff-rows">'
      +     rows.map(diffRowHtml).join('')
      +   '</div>'
      + '</div>';
  };

  replaceDiffBody(modal, ''
    + '<div class="branch-diff-summary">'
    +   diffs.length + ' difference' + (diffs.length === 1 ? '' : 's')
    + '</div>'
    + sectionHtml('Added in ' + sourceName, 'added-in-source',
                  'present on ' + sourceName + ', missing on ' + targetName)
    + sectionHtml('Added in ' + targetName, 'added-in-target',
                  'present on ' + targetName + ', missing on ' + sourceName)
    + sectionHtml('Modified', 'modified',
                  'resolves differently on the two branches'));

  modal.querySelectorAll('[data-diff-fn-id]').forEach((row) => {
    row.addEventListener('click', () => {
      const id = row.getAttribute('data-diff-fn-id');
      if (id && typeof selectFn === 'function') {
        closeBranchDiffModal();
        // Switching off the popover/modal and selecting a fn is the
        // most natural action; the user will see the fn-card on the
        // CURRENT branch (the one they clicked from).
        selectFn(id);
      }
    });
  });
}

function diffRowHtml(d) {
  const entityName = d['entity-name'];
  const entityId = d['entity-id'];
  const sv = d['source-version'];
  const tv = d['target-version'];
  const summary = previewSummary(entityName, sv, tv);
  // Only :fn entities are clickable — they're the things the editor
  // knows how to render. :binding / :binding-list-item navigate via
  // their owning fn-id, but we don't always have that on the wire;
  // skip the data attr in that case to disable navigation.
  const fnNav = entityName === 'fn' ? (' data-diff-fn-id="' + escapeAttr(entityId) + '"')
                                    : '';
  const klass = 'branch-diff-row'
                + (entityName === 'fn' ? ' branch-diff-row-clickable' : '');
  return ''
    + '<div class="' + klass + '"' + fnNav + '>'
    +   '<div class="branch-diff-row-head">'
    +     '<span class="branch-diff-entity">' + escapeText(entityName) + '</span>'
    +     '<span class="branch-diff-id">' + escapeText(entityId) + '</span>'
    +   '</div>'
    +   '<div class="branch-diff-row-summary">' + summary + '</div>'
    + '</div>';
}

function previewSummary(entityName, sv, tv) {
  if (entityName === 'fn') return fnPreview(sv, tv);
  if (entityName === 'binding') return bindingPreview(sv, tv);
  if (entityName === 'binding-list-item') return listItemPreview(sv, tv);
  if (entityName === 'fn-slot') return fnSlotPreview(sv, tv);
  // Fallback for anything new — just show field names that differ.
  return diffKeysPreview(sv, tv);
}

function fnPreview(sv, tv) {
  const present = sv || tv || {};
  const lines = [];
  if (present.name) lines.push('<strong>' + escapeText(present.name) + '</strong>');
  const s = sv?.description, t = tv?.description;
  if (s !== t) {
    lines.push('description: <em>' + previewField(s)
               + '</em> vs <em>' + previewField(t) + '</em>');
  }
  if (sv?.['return-type-fn-id'] !== tv?.['return-type-fn-id']) {
    lines.push('return-type changed');
  }
  if (sv?.constraint !== tv?.constraint) {
    lines.push('constraint changed');
  }
  if (sv?.['impl-hash'] !== tv?.['impl-hash']) {
    lines.push('impl-hash changed');
  }
  if (sv?.['deleted-at'] && !tv?.['deleted-at']) lines.push('DELETED on source');
  if (tv?.['deleted-at'] && !sv?.['deleted-at']) lines.push('DELETED on target');
  if (lines.length === 0) lines.push('(no field-level details)');
  return lines.join('<br>');
}

function bindingPreview(sv, tv) {
  if (sv?.value !== tv?.value) {
    return 'value: <em>' + previewField(sv?.value) + '</em> vs <em>'
           + previewField(tv?.value) + '</em>';
  }
  if (sv?.['ref-fn-id'] !== tv?.['ref-fn-id']) {
    return 'ref-fn-id changed';
  }
  return diffKeysPreview(sv, tv);
}

function listItemPreview(sv, tv) {
  if (sv?.position !== tv?.position) {
    return 'position: ' + previewField(sv?.position) + ' vs '
           + previewField(tv?.position);
  }
  if (sv?.value !== tv?.value) {
    return 'value: <em>' + previewField(sv?.value) + '</em> vs <em>'
           + previewField(tv?.value) + '</em>';
  }
  return diffKeysPreview(sv, tv);
}

function fnSlotPreview(sv, tv) {
  if (sv?.position !== tv?.position) {
    return 'position: ' + previewField(sv?.position) + ' vs '
           + previewField(tv?.position);
  }
  return diffKeysPreview(sv, tv);
}

function diffKeysPreview(sv, tv) {
  const sKeys = sv ? Object.keys(sv) : [];
  const tKeys = tv ? Object.keys(tv) : [];
  const all = Array.from(new Set([...sKeys, ...tKeys]));
  const changed = all.filter((k) =>
    JSON.stringify(sv?.[k]) !== JSON.stringify(tv?.[k]));
  if (changed.length === 0) return '(no diff data)';
  return 'fields changed: ' + changed.map(escapeText).join(', ');
}

function previewField(v) {
  if (v === undefined || v === null) return '(absent)';
  if (typeof v === 'string') return escapeText(truncateText(v, 60));
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  try { return escapeText(truncateText(JSON.stringify(v), 60)); }
  catch (_) { return escapeText(String(v)); }
}

function truncateText(s, n) {
  s = String(s);
  return s.length > n ? s.slice(0, n - 1) + '…' : s;
}

function escapeText(s) {
  const d = document.createElement('div');
  d.textContent = s === undefined || s === null ? '' : String(s);
  return d.innerHTML;
}

function escapeAttr(s) {
  return String(s || '').replace(/&/g, '&amp;').replace(/"/g, '&quot;')
    .replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

window.showBranchDiff = showBranchDiff;

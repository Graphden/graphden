// Editor Secrets — admin Secrets-panel CRUD.
//
// A secret in graphden is a normal fn-def with
// `parent-ids = [:vault-get]` and one binding for `:path`. The
// secret VALUE never touches graphden's DB — it lives in OpenBao and
// is read by `:vault-get` at execution-time.
//
// This module:
//   • fetches GET /api/secrets and renders a sidebar section,
//   • opens a "New secret" form (name + path + value + description)
//     that POSTs /api/secrets,
//   • offers Rotate (PUT /api/secrets/:fn-id/value) and Delete
//     (DELETE /api/secrets/:fn-id) actions per row,
//   • exposes `isSecretFn(fn)` so the rest of the editor can detect
//     secret-shaped fn-defs and render a 🔒 affordance.
//
// Globals consumed: graphData, lookups, authFetch, isAuthenticated,
// selectFn, anchorBelowClamped, installPopoverDismiss.

// ============================================================================
// SHAPE DETECTION
// ============================================================================

// Cache both base-fn ids once per graph load — UUIDs are
// content-addressed and stable across branches.
let _cachedSecretBaseIds = null;
let _cachedSecretBaseIdsGraph = null;

function getSecretBaseFnIds() {
  if (_cachedSecretBaseIdsGraph === graphData && _cachedSecretBaseIds !== null) {
    return _cachedSecretBaseIds;
  }
  _cachedSecretBaseIdsGraph = graphData;
  _cachedSecretBaseIds = { vaultGetId: null, secretLeafId: null };
  if (!graphData?.fns) return _cachedSecretBaseIds;
  for (const fn of graphData.fns) {
    const parents = fn['parent-ids'] || [];
    if (parents.length !== 0) continue;
    if (fn.name === 'vault-get')   _cachedSecretBaseIds.vaultGetId   = fn.id;
    if (fn.name === 'secret-leaf') _cachedSecretBaseIds.secretLeafId = fn.id;
  }
  return _cachedSecretBaseIds;
}

// `fn` is a secret-shaped fn-def iff its parents are exactly
// `[:vault-get]` (legacy) OR `[:secret-leaf]` (Followup-4).
// Used by the sidebar (🔒 badge), row-actions (override Delete to
// call /api/secrets), and the fn-overlay (Rotate affordance).
function isSecretFn(fn) {
  if (!fn) return false;
  const { vaultGetId, secretLeafId } = getSecretBaseFnIds();
  if (!vaultGetId && !secretLeafId) return false;
  const parents = fn['parent-ids'] || [];
  if (parents.length !== 1) return false;
  return parents[0] === vaultGetId || parents[0] === secretLeafId;
}


// ============================================================================
// LIST STATE + FETCH
// ============================================================================

let _secretsList = [];
let _secretsLoaded = false;
const secretsExpandedKey = '__secrets__';

async function loadSecrets() {
  if (!isAuthenticated()) {
    _secretsList = [];
    _secretsLoaded = true;
    return _secretsList;
  }
  try {
    const r = await authFetch('/api/secrets');
    if (!r.ok) {
      _secretsList = [];
      _secretsLoaded = true;
      return _secretsList;
    }
    const data = await r.json();
    _secretsList = data.ok ? (data.secrets || []) : [];
  } catch (_) {
    _secretsList = [];
  }
  _secretsLoaded = true;
  return _secretsList;
}


// ============================================================================
// SIDEBAR SECTION
// ============================================================================

// Build the "Secrets" sidebar section — a collapsible block above
// the namespace tree. Each row is a secret with path subtitle and a
// `⋯` actions menu (rotate / delete). The `+` next to the header
// opens the "New secret" form.
function buildSecretsSection() {
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-secrets';

  const isOpen = expandedNamespaces.has(secretsExpandedKey);

  const header = document.createElement('div');
  header.className = 'ns-header ns-header-pseudo';
  const arrow = document.createElement('span');
  arrow.className = 'ns-arrow' + (isOpen ? '' : ' collapsed');
  arrow.textContent = isOpen ? '▼' : '▶';
  header.appendChild(arrow);
  const label = document.createElement('span');
  label.className = 'ns-label';
  label.textContent = 'Secrets';
  header.appendChild(label);
  const count = document.createElement('span');
  count.className = 'ns-count';
  count.textContent = _secretsList.length;
  header.appendChild(count);

  if (isAuthenticated()) {
    const actions = document.createElement('span');
    actions.className = 'ns-row-actions';
    const addBtn = document.createElement('button');
    addBtn.type = 'button';
    addBtn.className = 'sidebar-action sidebar-action-add';
    addBtn.textContent = '+';
    addBtn.title = 'New secret';
    addBtn.onclick = (e) => {
      e.stopPropagation();
      openCreateSecretForm(addBtn);
    };
    actions.appendChild(addBtn);
    header.appendChild(actions);
  }

  header.onclick = (e) => {
    e.stopPropagation();
    if (isOpen) expandedNamespaces.delete(secretsExpandedKey);
    else expandedNamespaces.add(secretsExpandedKey);
    updateEntityList(graphData);
  };
  wrap.appendChild(header);

  if (!isOpen) return wrap;

  const childGroup = document.createElement('div');
  childGroup.className = 'ns-children';

  if (!_secretsLoaded) {
    const loading = document.createElement('div');
    loading.className = 'loading';
    loading.textContent = 'Loading…';
    childGroup.appendChild(loading);
    // Kick off the load — re-render once it lands.
    loadSecrets().then(() => updateEntityList(graphData));
  } else if (_secretsList.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'loading';
    empty.textContent = isAuthenticated()
      ? 'No secrets — click + to add one'
      : 'Sign in to manage secrets';
    childGroup.appendChild(empty);
  } else {
    const sorted = [..._secretsList].sort((a, b) =>
      (a.name || '').localeCompare(b.name || ''));
    for (const s of sorted) childGroup.appendChild(buildSecretItem(s));
  }

  wrap.appendChild(childGroup);
  return wrap;
}

function buildSecretItem(secret) {
  const item = document.createElement('div');
  item.className = 'entity-item entity-secret';
  item.dataset.fnId = secret.id;

  const lock = document.createElement('span');
  lock.className = 'secret-lock-icon';
  lock.textContent = '🔒'; // 🔒
  item.appendChild(lock);

  const nameSpan = document.createElement('span');
  nameSpan.className = 'name';
  nameSpan.textContent = secret.name || '(unnamed)';
  item.appendChild(nameSpan);

  const pathSpan = document.createElement('span');
  pathSpan.className = 'secret-path';
  pathSpan.textContent = secret.path || '';
  item.appendChild(pathSpan);

  if (isAuthenticated()) {
    const actions = document.createElement('span');
    actions.className = 'ns-row-actions';

    const rotateBtn = document.createElement('button');
    rotateBtn.type = 'button';
    rotateBtn.className = 'sidebar-action';
    rotateBtn.textContent = '↻'; // ↻
    rotateBtn.title = 'Rotate value';
    rotateBtn.onclick = (e) => {
      e.stopPropagation();
      openRotateSecretForm(rotateBtn, secret);
    };
    actions.appendChild(rotateBtn);

    const delBtn = document.createElement('button');
    delBtn.type = 'button';
    delBtn.className = 'sidebar-action sidebar-action-delete';
    delBtn.textContent = '×'; // ×
    delBtn.title = 'Delete secret';
    delBtn.onclick = (e) => {
      e.stopPropagation();
      deleteSecretConfirm(secret);
    };
    actions.appendChild(delBtn);

    item.appendChild(actions);
  }

  // Click on row → navigate to the secret's fn-def in the graph
  // (the same fn-def appears in the namespace tree too, lock-badged
  // by `isSecretFn`).
  item.onclick = () => {
    if (typeof selectFn === 'function') selectFn(secret.id);
  };

  return item;
}


// ============================================================================
// CREATE FORM
// ============================================================================

let _activePopover = null;

function closeActivePopover() {
  if (_activePopover) {
    try { _activePopover.remove(); } catch (_) {}
    _activePopover = null;
  }
}

function openCreateSecretForm(anchor) {
  closeActivePopover();
  const pop = document.createElement('div');
  pop.className = 'popover secrets-popover';
  pop.dataset.popover = 'create-secret';

  pop.innerHTML = `
    <div class="popover-title">New secret</div>
    <label class="popover-label">Name
      <input type="text" name="name" autocomplete="off" />
    </label>
    <label class="popover-label">Namespace
      <button type="button" class="secrets-ns-chip" data-act="pick-ns">(root)</button>
    </label>
    <label class="popover-label">Path
      <input type="text" name="path" autocomplete="off"
             placeholder="e.g. user-db/password" />
    </label>
    <label class="popover-label">Value
      <input type="password" name="value" autocomplete="new-password" />
    </label>
    <label class="popover-label">Description <span class="muted">(optional)</span>
      <input type="text" name="description" autocomplete="off" />
    </label>
    <div class="popover-error" hidden></div>
    <div class="popover-buttons">
      <button type="button" data-act="cancel">Cancel</button>
      <button type="button" data-act="submit" class="primary">Create</button>
    </div>
  `;
  document.body.appendChild(pop);
  _activePopover = pop;
  anchorBelowClamped(pop, anchor);
  installPopoverDismiss(pop, closeActivePopover);

  const nameInput = pop.querySelector('input[name="name"]');
  const pathInput = pop.querySelector('input[name="path"]');
  const valueInput = pop.querySelector('input[name="value"]');
  const descInput = pop.querySelector('input[name="description"]');
  const nsChip = pop.querySelector('[data-act="pick-ns"]');
  const errEl = pop.querySelector('.popover-error');

  let selectedNsId = null;
  nsChip.onclick = (e) => {
    e.stopPropagation();
    if (typeof openNamespacePicker !== 'function') return;
    openNamespacePicker({
      anchorEl: nsChip,
      onPick: (ns) => {
        selectedNsId = ns ? ns.id : null;
        nsChip.textContent = ns ? (ns.path || ns.name) : '(root)';
      }
    });
  };

  // Auto-fill path from name (slashes preserved as-is for now).
  let pathTouched = false;
  pathInput.addEventListener('input', () => { pathTouched = true; });
  nameInput.addEventListener('input', () => {
    if (!pathTouched && nameInput.value) {
      pathInput.value = nameInput.value.replace(/^_+/, '').replace(/-/g, '/');
    }
  });
  nameInput.focus();

  pop.querySelector('[data-act="cancel"]').onclick = closeActivePopover;
  pop.querySelector('[data-act="submit"]').onclick = async () => {
    errEl.hidden = true;
    const name = nameInput.value.trim();
    const path = pathInput.value.trim();
    const value = valueInput.value;
    const description = descInput.value.trim();
    if (!name || !path || !value) {
      errEl.textContent = 'name, path, and value are all required';
      errEl.hidden = false;
      return;
    }
    try {
      const r = await authFetch('/api/secrets', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name, path, value, description,
          'namespace-id': selectedNsId
        })
      });
      const data = await r.json().catch(() => ({}));
      if (!r.ok || data.ok === false) {
        errEl.textContent = data.error || ('HTTP ' + r.status);
        errEl.hidden = false;
        return;
      }
    } catch (e) {
      errEl.textContent = String(e);
      errEl.hidden = false;
      return;
    }
    // Wipe value from memory before closing — keep it off any
    // hidden form state cheshire / autocomplete might grab.
    valueInput.value = '';
    closeActivePopover();
    await loadSecrets();
    // Refresh sidebar + graph so the new fn-def appears in the
    // namespace tree too.
    if (typeof loadGraphData === 'function') {
      await loadGraphData();
    } else {
      updateEntityList(graphData);
    }
  };
}


// ============================================================================
// ROTATE FORM
// ============================================================================

function openRotateSecretForm(anchor, secret) {
  closeActivePopover();
  const pop = document.createElement('div');
  pop.className = 'popover secrets-popover';
  pop.dataset.popover = 'rotate-secret';

  pop.innerHTML = `
    <div class="popover-title">Rotate ${escapeHTML(secret.name)}</div>
    <div class="muted">Path: ${escapeHTML(secret.path || '')}</div>
    <label class="popover-label">New value
      <input type="password" name="value" autocomplete="new-password" />
    </label>
    <div class="popover-error" hidden></div>
    <div class="popover-buttons">
      <button type="button" data-act="cancel">Cancel</button>
      <button type="button" data-act="submit" class="primary">Rotate</button>
    </div>
  `;
  document.body.appendChild(pop);
  _activePopover = pop;
  anchorBelowClamped(pop, anchor);
  installPopoverDismiss(pop, closeActivePopover);

  const valueInput = pop.querySelector('input[name="value"]');
  const errEl = pop.querySelector('.popover-error');
  valueInput.focus();

  pop.querySelector('[data-act="cancel"]').onclick = closeActivePopover;
  pop.querySelector('[data-act="submit"]').onclick = async () => {
    errEl.hidden = true;
    const value = valueInput.value;
    if (!value) {
      errEl.textContent = 'value required';
      errEl.hidden = false;
      return;
    }
    try {
      const r = await authFetch('/api/secrets/' + encodeURIComponent(secret.id) + '/value', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ value })
      });
      const data = await r.json().catch(() => ({}));
      if (!r.ok || data.ok === false) {
        errEl.textContent = data.error || ('HTTP ' + r.status);
        errEl.hidden = false;
        return;
      }
    } catch (e) {
      errEl.textContent = String(e);
      errEl.hidden = false;
      return;
    }
    valueInput.value = '';
    closeActivePopover();
    await loadSecrets();
    updateEntityList(graphData);
  };
}


// ============================================================================
// DELETE
// ============================================================================

async function deleteSecretConfirm(secret) {
  if (!confirm('Delete secret "' + (secret.name || '?') + '"?\n\nThis removes the OpenBao value AND the fn-def. Any fn referencing it will be flagged.')) {
    return;
  }
  try {
    const r = await authFetch('/api/secrets/' + encodeURIComponent(secret.id), {
      method: 'DELETE'
    });
    const data = await r.json().catch(() => ({}));
    if (!r.ok || data.ok === false) {
      if (data.reason === 'secret-in-use' && Array.isArray(data.usages)) {
        const usedBy = data.usages.map(u => '- ' + (u.name || u['fn-id']) + ' (' + u.reason + ')').join('\n');
        alert('Cannot delete — secret is referenced by:\n\n' + usedBy);
        return;
      }
      alert(data.error || ('HTTP ' + r.status));
      return;
    }
  } catch (e) {
    alert(String(e));
    return;
  }
  await loadSecrets();
  if (typeof loadGraphData === 'function') {
    await loadGraphData();
  } else {
    updateEntityList(graphData);
  }
}


// ============================================================================
// UTIL
// ============================================================================

function escapeHTML(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

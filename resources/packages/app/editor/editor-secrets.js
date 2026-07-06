// Editor Secrets — admin Secrets-panel CRUD.
//
// A secret in graphden is a normal fn-def with
// `parent-ids = [:secret-leaf]` and one binding for `:in` carrying
// `:override-kind :secret-path`. The secret VALUE never touches
// graphden's DB — it lives in OpenBao and the executor auto-derefs
// the path at arg-resolution time.
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

// Cache the secret-leaf base-fn id once per graph load — UUIDs are
// content-addressed and stable across branches.
let _cachedSecretLeafId = null;
let _cachedSecretLeafIdGraph = null;

function getSecretLeafFnId() {
  if (_cachedSecretLeafIdGraph === graphData && _cachedSecretLeafId !== null) {
    return _cachedSecretLeafId;
  }
  _cachedSecretLeafIdGraph = graphData;
  _cachedSecretLeafId = null;
  if (!graphData?.fns) return null;
  for (const fn of graphData.fns) {
    const parents = fn['parent-ids'] || [];
    if (parents.length !== 0) continue;
    if (fn.name === 'secret-leaf') {
      _cachedSecretLeafId = fn.id;
      break;
    }
  }
  return _cachedSecretLeafId;
}

// `fn` is a secret-shaped fn-def iff its parents are exactly
// `[:secret-leaf]`. Used by the sidebar (🔒 badge), row-actions
// (override Delete to call /api/secrets), and the fn-overlay
// (Rotate affordance).
function isSecretFn(fn) {
  if (!fn) return false;
  const secretLeafId = getSecretLeafFnId();
  if (!secretLeafId) return false;
  const parents = fn['parent-ids'] || [];
  return parents.length === 1 && parents[0] === secretLeafId;
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
    const r = await authFetch(API.api_secrets);
    if (!r.ok) {
      // 401 is the common case (anonymous visitor); keep that quiet.
      // Other statuses indicate a real backend problem we should
      // surface in DevTools for triage.
      if (r.status !== 401) {
        // eslint-disable-next-line no-console
        console.error('list-secrets HTTP', r.status, r.statusText);
      }
      _secretsList = [];
      _secretsLoaded = true;
      return _secretsList;
    }
    const data = await r.json();
    _secretsList = data.ok ? (data.secrets || []) : [];
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error('list-secrets fetch threw', err);
    _secretsList = [];
  }
  _secretsLoaded = true;
  return _secretsList;
}


// ============================================================================
// SIDEBAR SECTION
// ============================================================================

// Build the "Secrets" sidebar section — a collapsible block above the
// namespace tree. Rows are rendered SYNCHRONOUSLY from the `_secretsList`
// cache (populated by `loadSecrets`), so a row appears the instant the
// data is in hand — no second fetch in the render path. This list is
// query-backed + latency-sensitive (the version-resolution scan behind
// `GET /api/secrets` is O(fn-slots)); a server-rendered partial would
// need a fetch on the render critical path, which is the graph-ui §6.1
// exception. The create / rotate FORM popovers ARE graph partials
// (static markup) — see `openCreateSecretForm` / `openRotateSecretForm`.
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
  lock.textContent = '🔒';
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
    rotateBtn.textContent = '↻';
    rotateBtn.title = 'Rotate value';
    rotateBtn.onclick = (e) => {
      e.stopPropagation();
      openRotateSecretForm(rotateBtn, secret);
    };
    actions.appendChild(rotateBtn);

    const delBtn = document.createElement('button');
    delBtn.type = 'button';
    delBtn.className = 'sidebar-action sidebar-action-delete';
    delBtn.textContent = '×';
    delBtn.title = 'Delete secret';
    delBtn.onclick = (e) => {
      e.stopPropagation();
      deleteSecretConfirm(secret);
    };
    actions.appendChild(delBtn);

    item.appendChild(actions);
  }

  // Click on row → navigate to the secret's fn-def in the graph (the
  // same fn-def appears in the namespace tree too, lock-badged by
  // `isSecretFn`).
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

// One document-level dismiss handler for whichever secrets popover is
// currently active — installed ONCE at module load (the handlers are
// inert while `_activePopover` is null). The popover element is
// recreated per open, so `getEl` reads the live `_activePopover`
// rather than closing over a single element.
installPopoverDismiss({
  getEl: () => _activePopover,
  // The create-secret form's ns-chip opens the namespace picker, which
  // appends its element (class `fn-picker-popover`) to document.body — a
  // SIBLING of `_activePopover`, not a child. Without this allowance a
  // pointerdown on a namespace row counts as "outside" and dismisses the
  // whole form (so a secret could only ever be created at root). Treat the
  // open picker as part of the popover for dismissal.
  getAnchor: () => document.querySelector('.fn-picker-popover'),
  isVisible: () => _activePopover != null,
  onDismiss: closeActivePopover
});

async function openCreateSecretForm(anchor) {
  closeActivePopover();
  const pop = document.createElement('div');
  pop.className = 'popover secrets-popover';
  pop.dataset.popover = 'create-secret';
  // Claim active BEFORE the await so a rapid re-open supersedes us.
  _activePopover = pop;

  // Form markup lives in the graph (GET /partials/secret-create-form); the
  // client owns only the lifecycle below — ns-picker, keystroke path auto-fill,
  // submit + error + multi-refresh (graph-ui §6-#2 / §6.3-§6.4 exceptions).
  // Fetch BEFORE mounting so the popover enters the DOM fully-formed —
  // never an empty shell that outside observers (and tests) can read
  // before the content lands.
  let failed = false;
  try {
    const r = await authFetch('/partials/secret-create-form');
    if (pop !== _activePopover) return; // dismissed / superseded while loading
    pop.innerHTML = await r.text();
  } catch (_) {
    if (pop !== _activePopover) return;
    pop.innerHTML = '<div class="popover-error">Failed to load form.</div>';
    failed = true;
  }
  document.body.appendChild(pop);
  anchorBelowClamped(pop, anchor);
  if (failed) return;

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
      const r = await authFetch(API.api_secrets, {
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
    // Reload the secret list + the graph so the new fn-def also
    // appears in the namespace tree.
    await loadSecrets();
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

async function openRotateSecretForm(anchor, secret) {
  closeActivePopover();
  const pop = document.createElement('div');
  pop.className = 'popover secrets-popover';
  pop.dataset.popover = 'rotate-secret';
  // Claim active BEFORE the await so a rapid re-open supersedes us.
  _activePopover = pop;

  // Form markup (incl. the name/path title, escaped by render-hiccup) lives in
  // the graph; the secret VALUE is entered client-side and only submitted
  // (§6.3). JS owns submit + refresh below. Fetch BEFORE mounting so the
  // popover enters the DOM fully-formed (never an empty shell).
  const q = new URLSearchParams({ name: secret.name || '', path: secret.path || '' });
  let failed = false;
  try {
    const r = await authFetch('/partials/secret-rotate-form?' + q.toString());
    if (pop !== _activePopover) return; // dismissed / superseded while loading
    pop.innerHTML = await r.text();
  } catch (_) {
    if (pop !== _activePopover) return;
    pop.innerHTML = '<div class="popover-error">Failed to load form.</div>';
    failed = true;
  }
  document.body.appendChild(pop);
  anchorBelowClamped(pop, anchor);
  if (failed) return;

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
      const r = await authFetch(API.api_secrets_fn_id_value(secret.id), {
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
    const r = await authFetch(API.api_secrets_fn_id(secret.id), {
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



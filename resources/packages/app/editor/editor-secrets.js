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
// SIDEBAR SECTION — server-rendered via /partials/secrets-panel
// ============================================================================

// Synchronous wrapper: returns a placeholder div + kicks off an
// async fetch of the server-rendered panel. Once the response lands,
// the placeholder's innerHTML gets replaced and click handlers are
// wired via event-delegation on the [data-act] attrs the partial
// emits.
//
// Anonymous visitors don't fetch — they get a "Sign in to manage
// secrets" stub rendered client-side (the partial is auth-gated).
//
// The `_secretsList` cache stays JS-side so:
//   • `isSecretFn(fn)` and the secret-leaf lookup keep working
//     from other modules without an extra round-trip,
//   • rotate/delete handlers can read the row's `data-fn-id` and
//     resolve back to a known `{:name :path}` via the cache for
//     prompt prefill / confirm-dialog copy.
function buildSecretsSection() {
  const wrap = document.createElement('div');
  wrap.className = 'sidebar-secrets';
  const isOpen = expandedNamespaces.has(secretsExpandedKey);
  if (!isOpen) wrap.classList.add('collapsed');

  if (!isAuthenticated()) {
    renderSignedOutPanel(wrap, isOpen);
    return wrap;
  }

  // Fetch the partial UNCONDITIONALLY (open or collapsed). The full
  // section — including row count and per-row actions — comes from
  // the server; CSS hides `.sidebar-secrets.collapsed .ns-children`
  // so the children group is invisible without two code paths.
  //
  // Scaffolding below is the placeholder visible until the fetch
  // lands (~30-150 ms typical). It deliberately mirrors the
  // partial's header structure — including the `+ New secret`
  // button — so synchronous tests that read `.sidebar-action-add`
  // immediately after the section appears don't race the fetch.
  wrap.innerHTML = ''
    + '<div class="ns-header ns-header-pseudo" data-act="toggle">'
    +   '<span class="ns-arrow">▼</span>'
    +   '<span class="ns-label">Secrets</span>'
    +   '<span class="ns-count"></span>'
    +   '<span class="ns-row-actions">'
    +     '<button type="button" class="sidebar-action sidebar-action-add" '
    +              'data-act="create" title="New secret">+</button>'
    +   '</span>'
    + '</div>'
    + '<div class="ns-children"><div class="loading">Loading…</div></div>';
  wireSecretsPanel(wrap);
  refreshSecretsPanel(wrap);
  return wrap;
}

function renderSignedOutPanel(wrap, isOpen) {
  wrap.innerHTML = ''
    + '<div class="ns-header ns-header-pseudo" data-act="toggle">'
    +   '<span class="ns-arrow">' + (isOpen ? '▼' : '▶') + '</span>'
    +   '<span class="ns-label">Secrets</span>'
    +   '<span class="ns-count">0</span>'
    + '</div>'
    + (isOpen
       ? '<div class="ns-children">'
         + '<div class="loading">Sign in to manage secrets</div>'
         + '</div>'
       : '');
  wireSecretsPanel(wrap);
}

async function refreshSecretsPanel(wrap) {
  try {
    const r = await authFetch('/partials/secrets-panel');
    if (!r.ok) {
      const child = wrap.querySelector('.ns-children');
      if (child) child.innerHTML =
        '<div class="loading">Secrets unavailable (HTTP ' + r.status + ')</div>';
      return;
    }
    const html = await r.text();
    wrap.innerHTML = html;
    // Re-apply collapsed state — the partial always emits the full
    // section markup; CSS hides `.sidebar-secrets.collapsed .ns-children`.
    const isOpen = expandedNamespaces.has(secretsExpandedKey);
    if (!isOpen) wrap.classList.add('collapsed');
    wireSecretsPanel(wrap);
    // Keep the JS-side cache in sync so isSecretFn() and rotate/
    // delete prompts can resolve fn-id → {name, path} without a
    // second fetch. Pull from /api/secrets (cheap JSON; partial
    // doesn't expose path back to JS).
    loadSecrets();
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error('refreshSecretsPanel fetch threw', err);
  }
}

// Event delegation: a SINGLE click listener on the sidebar-secrets
// container handles every [data-act] the partial emits. Cheaper than
// per-button addEventListener + survives innerHTML replacement.
function wireSecretsPanel(wrap) {
  wrap.onclick = (e) => {
    const target = e.target;
    const actEl = target?.closest?.('[data-act]');
    if (!actEl || !wrap.contains(actEl)) return;
    const act = actEl.getAttribute('data-act');
    if (act === 'toggle') {
      e.stopPropagation();
      const isOpen = expandedNamespaces.has(secretsExpandedKey);
      if (isOpen) expandedNamespaces.delete(secretsExpandedKey);
      else expandedNamespaces.add(secretsExpandedKey);
      updateEntityList(graphData);
      return;
    }
    if (act === 'create') {
      e.stopPropagation();
      openCreateSecretForm(actEl);
      return;
    }
    if (act === 'rotate') {
      e.stopPropagation();
      const fnId = actEl.getAttribute('data-fn-id');
      const secret = _secretsList.find((s) => s.id === fnId) || {id: fnId};
      openRotateSecretForm(actEl, secret);
      return;
    }
    if (act === 'delete') {
      e.stopPropagation();
      const fnId = actEl.getAttribute('data-fn-id');
      const secret = _secretsList.find((s) => s.id === fnId) || {id: fnId};
      deleteSecretConfirm(secret);
      return;
    }
    if (act === 'navigate') {
      // Only navigate when the click was NOT on a button (those
      // already had their stopPropagation; this branch covers the
      // row body).
      if (target.tagName === 'BUTTON' || target.closest('button')) return;
      const fnId = actEl.getAttribute('data-fn-id');
      if (fnId && typeof selectFn === 'function') selectFn(fnId);
    }
  };
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

async function openCreateSecretForm(anchor) {
  closeActivePopover();
  const pop = document.createElement('div');
  pop.className = 'popover secrets-popover';
  pop.dataset.popover = 'create-secret';
  document.body.appendChild(pop);
  _activePopover = pop;
  installPopoverDismiss(pop, closeActivePopover);

  // Form markup lives in the graph (GET /partials/secret-create-form); the
  // client owns only the lifecycle below — ns-picker, keystroke path auto-fill,
  // submit + error + multi-refresh (graph-ui §6-#2 / §6.3-§6.4 exceptions).
  try {
    const r = await authFetch('/partials/secret-create-form');
    if (pop !== _activePopover) return; // dismissed while loading
    pop.innerHTML = await r.text();
  } catch (_) {
    pop.innerHTML = '<div class="popover-error">Failed to load form.</div>';
    anchorBelowClamped(pop, anchor);
    return;
  }
  anchorBelowClamped(pop, anchor);

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

async function openRotateSecretForm(anchor, secret) {
  closeActivePopover();
  const pop = document.createElement('div');
  pop.className = 'popover secrets-popover';
  pop.dataset.popover = 'rotate-secret';
  document.body.appendChild(pop);
  _activePopover = pop;
  installPopoverDismiss(pop, closeActivePopover);

  // Form markup (incl. the name/path title, escaped by render-hiccup) lives in
  // the graph; the secret VALUE is entered client-side and only submitted
  // (§6.3). JS owns submit + refresh below.
  const q = new URLSearchParams({ name: secret.name || '', path: secret.path || '' });
  try {
    const r = await authFetch('/partials/secret-rotate-form?' + q.toString());
    if (pop !== _activePopover) return; // dismissed while loading
    pop.innerHTML = await r.text();
  } catch (_) {
    pop.innerHTML = '<div class="popover-error">Failed to load form.</div>';
    anchorBelowClamped(pop, anchor);
    return;
  }
  anchorBelowClamped(pop, anchor);

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



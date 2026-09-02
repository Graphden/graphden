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

// The secret-leaf base-fn id. UUIDs are content-addressed and stable across
// branches, so once resolved it's cached for the page. The sidebar holds
// no full-fns mirror to scan, so it's resolved by name via the server
// (primeSecretLeafId, called from initGraph / loadGraphData) — keeping
// isSecretFn() synchronous for per-row classification.
let _primedSecretLeafId = null;

async function primeSecretLeafId() {
  if (_primedSecretLeafId) return _primedSecretLeafId;
  if (typeof resolveFnByName !== 'function') return null;
  try {
    // Qualified: a user fn named `secret-leaf` in another namespace
    // must not shadow the platform one (per-namespace names).
    const fn = await resolveFnByName('web.vault/secret-leaf');
    if (fn?.id) {
      _primedSecretLeafId = fn.id;
      // Repaint so 🔒 badges / secret classification appear now that the
      // id is known (the first paint may have run before this resolved).
      if (typeof updateEntityList === 'function' && graphData) updateEntityList(graphData);
    }
    return _primedSecretLeafId;
  } catch (err) {
    console.error('primeSecretLeafId failed', err);
    return null;
  }
}
window.primeSecretLeafId = primeSecretLeafId;

function getSecretLeafFnId() {
  // Purely id-based. The secret-leaf id is resolved ONCE at boot
  // (primeSecretLeafId, by the seed's stable identity) and cached here; a fn is
  // never classified as a secret by matching a literal name. Until the prime
  // resolves, this is null and classification simply waits for the repaint the
  // prime triggers — no name scan.
  return _primedSecretLeafId;
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
        console.error('list-secrets HTTP', r.status, r.statusText);
      }
      _secretsList = [];
      _secretsLoaded = true;
      return _secretsList;
    }
    const data = await r.json();
    _secretsList = data.ok ? (data.secrets || []) : [];
  } catch (err) {
    console.error('list-secrets fetch threw', err);
    _secretsList = [];
  }
  _secretsLoaded = true;
  return _secretsList;
}


// ============================================================================
// TREE ROW INTEGRATION
// ============================================================================
// Secrets render inside the namespace tree (lock-badged by
// `isSecretFn`, filtered by the "secrets" eye toggle) — no separate
// sidebar section. These helpers give a tree secret-row its vault
// path + Rotate / Delete actions. "+ New secret" is the
// `#secret-add-btn` in the filter bar, wired straight to
// `openCreateSecretForm`.
//
// `_secretsList` (from `loadSecrets`, primed by the sidebar) is
// query-backed + latency-sensitive (the version-resolution scan behind
// `GET /api/secrets` is O(fn-slots)); the create / rotate FORM popovers
// ARE graph partials (static markup) — see `openCreateSecretForm` /
// `openRotateSecretForm`.

// Namespace-ids holding at least one secret — from the /api/secrets
// rows' `namespace-id`. The secrets LENS uses this to keep a
// not-yet-loaded namespace visible: the secret's fn row only reaches
// fnMap after an expand, but its namespace is knowable from the list
// alone. Cheap enough to rebuild per call (secrets lists are small).
function secretNsIds() {
  const ids = new Set();
  for (const s of _secretsList) {
    if (s['namespace-id']) ids.add(s['namespace-id']);
  }
  return ids;
}


// Map a secret fn-id to its /api/secrets record (name + path). Falls back
// to a path-less stub when the list isn't primed yet.
function secretRecordForFn(fnId) {
  const rec = _secretsList.find((s) => s.id === fnId);
  if (rec) return rec;
  const fn = (typeof lookups !== 'undefined') ? lookups?.fnMap?.get(fnId) : null;
  return { id: fnId, name: fn?.name || '', path: '' };
}

// Append Rotate (↻) + Delete (×) buttons for a secret row into an
// existing `.ns-row-actions` group. Auth-gated — anonymous visitors get
// no mutating affordances.
function buildSecretRowActions(actionsEl, fn) {
  if (!isAuthenticated()) return;
  const secret = secretRecordForFn(fn.id);

  const rotateBtn = document.createElement('button');
  rotateBtn.type = 'button';
  rotateBtn.className = 'sidebar-action';
  rotateBtn.textContent = '↻';
  rotateBtn.title = 'Rotate value';
  rotateBtn.onclick = (e) => {
    e.stopPropagation();
    openRotateSecretForm(rotateBtn, secret);
  };
  actionsEl.appendChild(rotateBtn);

  const delBtn = document.createElement('button');
  delBtn.type = 'button';
  delBtn.className = 'sidebar-action sidebar-action-delete';
  delBtn.textContent = '×';
  delBtn.title = 'Delete secret';
  delBtn.onclick = (e) => {
    e.stopPropagation();
    deleteSecretConfirm(secret);
  };
  actionsEl.appendChild(delBtn);
}


// ============================================================================
// CREATE FORM
// ============================================================================

let _activePopover = null;
// The element that opened the current popover. Tracked separately from
// `getAnchor` below, which deliberately points at the namespace PICKER
// rather than the trigger — returning focus there would land the user in
// a different popover.
let _activePopoverTrigger = null;

function closeActivePopover() {
  if (_activePopover) {
    const inside = _activePopover.contains(document.activeElement);
    try { _activePopover.remove(); } catch (_) {}
    _activePopover = null;
    // Only reclaim focus if it was inside the form we just removed;
    // otherwise the user has already clicked elsewhere.
    if (inside) returnFocusTo(_activePopoverTrigger);
    _activePopoverTrigger = null;
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
  onDismiss: closeActivePopover,
  // Secret name / value entry — keep Tab in the form. Focus return is
  // handled by closeActivePopover (which also covers the Save path), not
  // by getReturnFocus, since getAnchor here is not the trigger.
  trapFocus: true
});

async function openCreateSecretForm(anchor) {
  closeActivePopover();
  _activePopoverTrigger = anchor || null;
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
    let createdId = null;
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
      createdId = data?.secret?.id || null;
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
    // Take the user TO what they just created — before this, the new
    // secret landed silently in its namespace (root → the very bottom
    // of the tree) and looked like nothing had happened.
    if (createdId && typeof selectFn === 'function') {
      selectFn(createdId, true);
      if (typeof revealFnInTree === 'function') revealFnInTree(createdId);
    }
  };
}


// ============================================================================
// ROTATE FORM
// ============================================================================

async function openRotateSecretForm(anchor, secret) {
  closeActivePopover();
  _activePopoverTrigger = anchor || null;
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



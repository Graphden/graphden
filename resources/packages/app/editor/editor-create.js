// Editor Create / Edit — inline UI for namespace + fn creation and
// namespace renaming, all driven from the sidebar tree itself.
//
// Surfaces:
//   1. Per-namespace row buttons (shown on hover):
//        ✎  rename namespace inline (input replaces the label)
//        +  open a small menu — "New namespace…" / "New graph…"
//      Choosing either spawns an inline input row indented under the
//      namespace; submitting it POSTs the create and refreshes.
//   2. A full-width "+ New namespace" button at the bottom of the
//      sidebar that creates a ROOT namespace inline.
//      No "New graph…" option there — fns must live inside a namespace,
//      and a root-level graph would have no namespace-id to attach to.
//
// All mutating fetches go through `authFetch`; without a stored token
// the lock popover opens automatically (1) on click of any of these
// affordances, (2) on a 401 from `authFetch`.

const PLUS_SVG = '<svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>';
const PENCIL_SVG = '<svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4z"/></svg>';
const TRASH_SVG = '<svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M6 6l1 14a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-14"/></svg>';

// =============================================================================
// API HELPERS
// =============================================================================

async function postEntity(type, fields) {
  return authMutate('POST', API.api_entities_type(type), fields);
}


async function deleteEntity(type, id) {
  return authMutate('DELETE', API.api_entities_type_id(type, id));
}


async function putEntity(type, id, fields) {
  return authMutate('PUT', API.api_entities_type_id(type, id), fields);
}

// =============================================================================
// PER-NAMESPACE EDIT BUTTONS
// =============================================================================

// Append the right-side hover buttons (✎ rename, + create-child) into
// `actionsEl` — caller is responsible for placing the actions group
// in the row. `nsId` is the entity uuid; `nsPath` is the dotted path.
// The header element to swap into edit mode is `actionsEl.parentNode`.
function buildNsRowButtons(actionsEl, nsId, nsPath) {
  const headerEl = actionsEl.parentNode || actionsEl;

  const editBtn = document.createElement('button');
  editBtn.className = 'create-btn create-btn-inline ns-edit-btn';
  editBtn.title = 'Rename namespace';
  editBtn.innerHTML = PENCIL_SVG;
  editBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    if (!ensureAuth()) return;
    startNsRename(headerEl, nsId, nsPath);
  });

  const plusBtn = document.createElement('button');
  plusBtn.className = 'create-btn create-btn-inline ns-plus-btn';
  plusBtn.title = 'Add inside this namespace';
  plusBtn.innerHTML = PLUS_SVG;
  plusBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    if (!ensureAuth()) return;
    openChildCreateMenu(plusBtn, nsId, nsPath);
  });

  // Publish (packages spec §3): publishing a namespace as an immutable package
  // version is an authoring act on the thing you built, so it lives on the
  // namespace — not on the Build packages chip (that is install/browse) and not
  // on the Organization page. Shown only when the OPTIONAL registry package is
  // present (window.API probe, never a name) AND the principal may publish:
  // default-SHOW (single-tenant / operator has no capability system), hidden
  // only when the tenancy addon is active and withholds the `publish-packages`
  // org capability — mirroring the server guard's platform-tier short-circuit.
  let publishBtn = null;
  if (registryPresent() && canPublishPackages()) {
    publishBtn = document.createElement('button');
    publishBtn.className = 'create-btn create-btn-inline ns-publish-btn';
    publishBtn.title = 'Publish this namespace as a package';
    publishBtn.setAttribute('aria-label', 'Publish ' + nsPath + ' as a package');
    publishBtn.textContent = '⬆'; // release this subtree to the registry
    publishBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      if (!ensureAuth()) return;
      openNsPublishPopover(publishBtn, nsPath);
    });
  }

  // Personal "hide from my view" (⊘) — a FILTER (the `exclude` axis of
  // editor-explorer-filters.js), NOT a graph edit: removes this namespace
  // from THIS browser's explorer only; the chip it adds is the way back.
  // No auth needed — it's a view preference, works signed-out.
  const hideBtn = document.createElement('button');
  hideBtn.className = 'create-btn create-btn-inline ns-hide-btn';
  hideBtn.title = 'Hide from my view — a "not " filter chip appears; remove it to restore';
  hideBtn.setAttribute('aria-label', 'Hide ' + nsPath + ' from my view');
  hideBtn.textContent = '⊘';
  hideBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    if (typeof window.gdToggleExclude === 'function') window.gdToggleExclude(nsPath);
  });

  actionsEl.appendChild(editBtn);
  actionsEl.appendChild(buildDeleteButton({
    type: 'ns', id: nsId, displayName: nsPath.split('.').pop(),
    blockReason: nsDeleteBlockReason(nsId)
  }));
  actionsEl.appendChild(plusBtn);
  if (publishBtn) actionsEl.appendChild(publishBtn);
  actionsEl.appendChild(hideBtn);
}

// True iff the optional `registry` package contributed its routes at boot —
// its `/api/packages/*` endpoints appear in window.API only then. Never a name.
function registryPresent() {
  return typeof window.API === 'object' && window.API !== null
    && typeof window.API.api_packages_installed !== 'undefined';
}

// May the current principal publish packages? Default-SHOW: single-tenant and
// operator instances have no capability system (graphdenTenancyActive() false),
// so publishing is open. When the tenancy addon IS active, require the
// `publish-packages` org capability — matching the server guard, which
// short-circuits on platform-tier and otherwise demands the same capability.
function canPublishPackages() {
  if (typeof window.graphdenTenancyActive !== 'function' || !window.graphdenTenancyActive()) return true;
  return typeof window.graphdenHasCap === 'function' && window.graphdenHasCap('publish-packages');
}

let activeNsPublishPop = null;
function closeNsPublishPopover() {
  if (activeNsPublishPop) { activeNsPublishPop.remove(); activeNsPublishPop = null; }
  const s = document.getElementById('gd-nspub-scrim');
  if (s) s.remove();
  document.removeEventListener('keydown', _nsPublishOnKey);
}

// Escape closes the publish dialog. Every other dialog in the editor answers
// to that key; without it here the dialog stayed open and the keystroke fell
// through to whatever else listens — the tutorial overlay, which ended the
// lesson mid-step.
function _nsPublishOnKey(e) {
  if (e.key === 'Escape' && activeNsPublishPop) {
    e.stopPropagation();
    closeNsPublishPopover();
  }
}

// Per-namespace publish popover (packages spec §3). Pre-fills `ns-root` = the
// clicked namespace and POSTs JSON {name, version, ns-root} to the EXISTING
// JSON publish route — no new server code, so it adds no boot-time type-check
// load (an earlier server-side variant did, and hung boot). The result notice
// is rendered client-side from the {ok, fn-count, …} JSON. Values are set via
// DOM properties (never interpolated into innerHTML) so a namespace path can't
// inject markup.
function openNsPublishPopover(anchorEl, nsPath) {
  closeNsPublishPopover();
  const scrim = document.createElement('div');
  scrim.id = 'gd-nspub-scrim';
  scrim.className = 'gd-pop-scrim';
  scrim.addEventListener('click', closeNsPublishPopover);
  document.body.appendChild(scrim);

  const pop = document.createElement('div');
  pop.id = 'gd-nspub-pop';
  pop.className = 'gd-pop';
  pop.innerHTML = ''
    + '<h5>Publish namespace</h5>'
    + '<div class="gd-nspub-sub"></div>'
    + '<div class="gd-nspub-field"><label>Package name</label>'
    +   '<input type="text" class="packages-publish-input" id="gd-nspub-name"></div>'
    + '<div class="gd-nspub-field"><label>Version</label>'
    +   '<input type="text" class="packages-publish-input" id="gd-nspub-version" placeholder="1.0.0"></div>'
    // Marketplace listing (docs/MARKETPLACE.md): what the card shows.
    // Categories come from the graph's vocabulary (editor-marketplace.js
    // fetched it at boot); tags are a comma list.
    + '<div class="gd-nspub-field"><label>Description</label>'
    +   '<textarea class="packages-publish-input" id="gd-nspub-desc" rows="2" maxlength="2000" placeholder="What it does, for the marketplace card"></textarea></div>'
    + '<div class="gd-nspub-field"><label>Category</label>'
    +   '<select class="packages-publish-input" id="gd-nspub-category"><option value="">— none —</option></select></div>'
    + '<div class="gd-nspub-field"><label>Tags</label>'
    +   '<input type="text" class="packages-publish-input" id="gd-nspub-tags" placeholder="comma, separated, tags"></div>'
    // Public opt-in (spec §5): only meaningful under the tenancy addon — a
    // tenant publish is private to its org unless this is checked. Single-
    // tenant / operator publishes are always platform-visible, so the
    // checkbox is omitted there rather than shown pre-checked and disabled.
    + (typeof window.graphdenTenancyActive === 'function' && window.graphdenTenancyActive()
      ? '<label class="gd-nspub-public"><input type="checkbox" id="gd-nspub-public"> '
        + 'Public — visible outside your organization</label>'
      : '')
    + '<div class="gd-nspub-actions">'
    +   '<button type="button" class="packages-install-btn" id="gd-nspub-go">Publish</button></div>'
    + '<div id="gd-nspub-result" class="gd-nspub-result"></div>';
  // A form with a Publish button and nothing else: no Cancel, and the scrim
  // behind it is transparent, so backing out was an unguessable click on
  // whatever the panel happened to cover.
  if (typeof ensurePopoverClose === 'function') {
    ensurePopoverClose(pop, closeNsPublishPopover, 'Close publish form', {prepend: true});
  }
  pop.querySelector('.gd-nspub-sub').textContent = nsPath;
  const nameInput = pop.querySelector('#gd-nspub-name');
  const versionInput = pop.querySelector('#gd-nspub-version');
  nameInput.value = nsPath.split('.').pop() || nsPath;
  versionInput.value = '1.0.0';
  if (typeof window.gdMarketCategoriesInto === 'function') {
    window.gdMarketCategoriesInto(pop.querySelector('#gd-nspub-category'), 'fns');
  }

  const r = anchorEl.getBoundingClientRect();
  pop.style.left = Math.max(8, Math.min(r.left, window.innerWidth - 320)) + 'px';
  pop.style.top = (r.bottom + 6) + 'px';
  document.body.appendChild(pop);
  activeNsPublishPop = pop;
  document.addEventListener('keydown', _nsPublishOnKey);

  const resultEl = pop.querySelector('#gd-nspub-result');
  const goBtn = pop.querySelector('#gd-nspub-go');
  const setResult = (msg, ok) => {
    resultEl.textContent = msg;
    resultEl.className = 'gd-nspub-result ' + (ok ? 'packages-fork-ok' : 'packages-fork-err');
  };
  const doPublish = async () => {
    // Enter on the version field reaches here too — it must honour the
    // button's state (in flight, or already published) like a click does.
    if (goBtn.disabled) return;
    const name = nameInput.value.trim();
    const version = versionInput.value.trim();
    if (!name || !version) { setResult('Name and version are required.', false); return; }
    goBtn.disabled = true;
    setResult('Publishing…', true);
    try {
      const publicInput = pop.querySelector('#gd-nspub-public');
      const resp = await authFetch(API.api_packages_publish, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name, version, 'ns-root': nsPath,
          public: !!publicInput?.checked,
          description: pop.querySelector('#gd-nspub-desc')?.value || '',
          category: pop.querySelector('#gd-nspub-category')?.value || '',
          tags: pop.querySelector('#gd-nspub-tags')?.value || '',
        }),
      });
      if (resp.ok) {
        let fnCount = null;
        try { fnCount = (await resp.json())['fn-count']; } catch (_) { /* body optional */ }
        setResult('Published ' + name + '@' + version
          + (fnCount != null ? ' (' + fnCount + ' fns)' : '')
          + ' — install it from the packages chip.', true);
      } else {
        setResult(authFetchErrorMessage(resp, { fallback: 'Publish failed (HTTP ' + resp.status + ').' }), false);
        goBtn.disabled = false;
      }
    } catch (e) {
      setResult(e?.message || 'Publish failed.', false);
      goBtn.disabled = false;
    }
  };
  goBtn.addEventListener('click', doPublish);
  versionInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); doPublish(); } });
  nameInput.focus();
  nameInput.select();
}

function ensureAuth() {
  if (isAuthenticated()) return true;
  openAuthPopover('Sign in to edit the graph.');
  return false;
}

// Compute "why can't this fn be deleted?" — backend enforces the
// same rules but the UI can show the disabled state up-front.
// Returns a string reason or null when deletion is safe.
function fnDeleteBlockReason(fnId) {
  if (!lookups) return null;
  const reasons = [];
  const asParent = (lookups.fnUsedAsParent?.get(fnId)) || 0;
  const asRef    = (lookups.fnUsedAsRef?.get(fnId))    || 0;
  if (asParent) reasons.push('parent of ' + asParent + ' graph' + (asParent > 1 ? 's' : ''));
  if (asRef)    reasons.push('referenced by ' + asRef + ' arg' + (asRef > 1 ? 's' : ''));
  if (!reasons.length) return null;
  return 'Cannot delete: ' + reasons.join(' + ') + '. Remove dependents first.';
}

function nsDeleteBlockReason(nsId) {
  if (!lookups) return null;
  const reasons = [];
  const subNs = (lookups.nsHasChildNs?.get(nsId)) || 0;
  const subFn = (lookups.nsHasChildFn?.get(nsId)) || 0;
  if (subNs) reasons.push('contains ' + subNs + ' nested namespace' + (subNs > 1 ? 's' : ''));
  if (subFn) reasons.push('contains ' + subFn + ' graph' + (subFn > 1 ? 's' : ''));
  if (!reasons.length) return null;
  return 'Cannot delete: ' + reasons.join(' + ') + '. Remove the contents first.';
}

// Build a delete (✕) button. When `blockReason` is null, the button
// is active and clicking it confirms + deletes; when set, the button
// is greyed-out, non-interactive, and the reason is shown via the
// `title` tooltip. The backend enforces the same constraints, so an
// active click that races with a concurrent change still gets a 409.
function buildDeleteButton({ type, id, displayName, blockReason }) {
  const btn = document.createElement('button');
  btn.className = 'create-btn create-btn-inline ns-delete-btn';
  btn.innerHTML = TRASH_SVG;
  if (blockReason) {
    btn.classList.add('create-btn-disabled');
    btn.disabled = true;
    btn.title = blockReason;
  } else {
    btn.title = 'Delete ' + (type === 'ns' ? 'namespace' : 'graph');
    btn.addEventListener('click', async (e) => {
      e.stopPropagation();
      if (!ensureAuth()) return;
      if (!confirm('Delete ' + (type === 'ns' ? 'namespace' : 'graph')
                   + ' "' + displayName + '"?')) return;
      // What a 30-second Undo needs to put it back: a namespace's name +
      // parent (it is re-created), a graph's qualified name (it is revived).
      const nsRow = (type === 'ns' && lookups?.nsMap) ? lookups.nsMap.get(id) : null;
      const fnRow = (type === 'fn' && lookups?.fnMap) ? lookups.fnMap.get(id) : null;
      const fnQName = fnRow && typeof getQualifiedFnName === 'function' ? getQualifiedFnName(fnRow) : displayName;
      try {
        const response = await deleteEntity(type, id);
        if (response.status >= 200 && response.status < 300) {
          if (type === 'ns' && typeof gdUndoRecordDeletedNs === 'function') {
            gdUndoRecordDeletedNs(nsRow?.name || displayName, nsRow?.['parent-id'] || null);
          } else if (type === 'fn' && typeof gdUndoRecordDeletedFn === 'function') {
            gdUndoRecordDeletedFn(id, fnQName);
            if (selectedFnId === id && typeof gdClearSelection === 'function') gdClearSelection();
          }
          await initGraph();
        } else {
          const text = (typeof extractResponseError === 'function')
            ? await extractResponseError(response) : ('HTTP ' + response.status);
          alert('Delete failed (' + response.status + '): ' + String(text).slice(0, 200));
        }
      } catch (err) {
        alert('Network error: ' + err.message);
      }
    });
  }
  return btn;
}

// Append the rename + delete buttons into `actionsEl` for a fn row.
// fns have no children to add inline, so there's no `+` here.
function buildFnRowButtons(actionsEl, fnId, fnName) {
  const itemEl = actionsEl.parentNode || actionsEl;
  const editBtn = document.createElement('button');
  editBtn.className = 'create-btn create-btn-inline ns-edit-btn';
  editBtn.title = 'Rename graph';
  editBtn.innerHTML = PENCIL_SVG;
  editBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    if (!ensureAuth()) return;
    startFnRename(itemEl, fnId, fnName);
  });
  actionsEl.appendChild(editBtn);
  actionsEl.appendChild(buildDeleteButton({
    type: 'fn', id: fnId, displayName: fnName,
    blockReason: fnDeleteBlockReason(fnId)
  }));
}


// =============================================================================
// CREATE-CHILD MENU (ns / fn)
// =============================================================================

let activeChildMenu = null;

function openChildCreateMenu(anchorEl, parentNsId, parentNsPath) {
  closeChildCreateMenu();
  const menu = document.createElement('div');
  menu.className = 'create-menu';
  menu.innerHTML =
    '<button class="create-menu-item" data-type="ns">New namespace…</button>' +
    '<button class="create-menu-item" data-type="fn">New graph…</button>' +
    '<button class="create-menu-item" data-type="type">New type…</button>';
  // Position fixed under the anchor.
  const rect = anchorEl.getBoundingClientRect();
  menu.style.position = 'fixed';
  menu.style.top = (rect.bottom + 4) + 'px';
  menu.style.left = rect.left + 'px';
  menu.style.right = 'auto';
  menu.style.zIndex = '300';
  menu.setAttribute('role', 'menu');
  menu.setAttribute('aria-label', 'Create in ' + (parentNsPath || 'this namespace'));
  document.body.appendChild(menu);
  activeChildMenu = menu;
  // A menu the keyboard can leave: focus lands on the first item, Escape
  // closes it and returns focus to the `+` that opened it (consumed, so a
  // running tour does not end). ↑ / ↓ walk the items.
  const items = Array.from(menu.querySelectorAll('.create-menu-item'));
  for (const b of items) b.setAttribute('role', 'menuitem');
  menu.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      closeChildCreateMenu();
      if (anchorEl && typeof anchorEl.focus === 'function') anchorEl.focus();
      return;
    }
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const i = items.indexOf(document.activeElement);
      const n = items.length;
      items[((i < 0 ? 0 : i) + (e.key === 'ArrowDown' ? 1 : n - 1)) % n].focus();
    }
  });
  setTimeout(() => { if (items[0] && activeChildMenu === menu) items[0].focus(); }, 0);

  menu.querySelectorAll('.create-menu-item').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const type = btn.dataset.type;
      closeChildCreateMenu();
      if (type === 'type') {
        // Type-row creation has multi-field forms — handled by the
        // dedicated popover in editor-create-type.js.
        if (typeof openTypeCreatePicker === 'function') {
          openTypeCreatePicker(parentNsId, parentNsPath, anchorEl);
        }
      } else {
        startInlineCreate(type, parentNsId, parentNsPath);
      }
    });
  });

  // Outside-click closes the menu — except a click in the tutorial popover
  // (a copy-chip mid-step must not throw the menu away), which keeps the
  // listener armed for the next click instead of consuming its one shot.
  setTimeout(() => {
    const onDocClick = (e) => {
      if (pointerEventInTour(e)) return;
      document.removeEventListener('click', onDocClick);
      closeChildCreateMenu();
    };
    document.addEventListener('click', onDocClick);
  }, 0);
}

function closeChildCreateMenu() {
  if (activeChildMenu) {
    activeChildMenu.remove();
    activeChildMenu = null;
  }
}

// =============================================================================
// INLINE CREATE
// =============================================================================

// State: which slot is currently in inline-create mode. Cleared on
// cancel/commit. We don't persist across refreshes — initGraph()
// rebuilds the sidebar fresh each time.
let activeCreate = null;  // { type, parentNsId, parentNsPath } | null

function startInlineCreate(type, parentNsId, parentNsPath) {
  activeCreate = { type, parentNsId, parentNsPath };
  // Re-render so renderNsNode picks up the active-create marker and
  // injects the inline input row at the right spot.
  if (typeof updateEntityList === 'function' && graphData) {
    updateEntityList(graphData);
  }
}

function startRootCreate() {
  if (!ensureAuth()) return;
  activeCreate = { type: 'ns', parentNsId: null, parentNsPath: null };
  if (typeof updateEntityList === 'function' && graphData) {
    updateEntityList(graphData);
  }
}

function clearActiveCreate() {
  activeCreate = null;
  if (typeof updateEntityList === 'function' && graphData) {
    updateEntityList(graphData);
  }
}

// Called from renderNsNode when its `nsId` matches the active create
// context — caller appends the returned row inside the children
// container of that namespace.
function buildActiveCreateRow(nsId, indent) {
  if (!activeCreate) return null;
  if (activeCreate.parentNsId !== nsId) return null;
  return buildCreateRow(indent);
}

function buildRootCreateRow() {
  if (!activeCreate) return null;
  if (activeCreate.parentNsId !== null) return null;
  return buildCreateRow(0);
}

function buildCreateRow(indent) {
  const placeholder = activeCreate.type === 'ns' ? 'New namespace name'
                                                 : 'New graph name';
  return buildInlineInputRow({
    placeholder,
    indent,
    onSubmit: async (name) => {
      const createType = activeCreate.type;
      const fields = createType === 'ns'
        ? { name, 'parent-id': activeCreate.parentNsId || '' }
        : { name, 'namespace-id': activeCreate.parentNsId || '' };
      const response = await postEntity(createType, fields);
      if (response.status >= 200 && response.status < 300) {
        const parentNsId = activeCreate.parentNsId || null;
        if (createType !== 'ns' && typeof gdRememberLastNs === 'function') {
          gdRememberLastNs(parentNsId);
        }
        if (createType === 'ns' && typeof gdUndoRecordCreatedNs === 'function') {
          gdUndoRecordCreatedNs(name, parentNsId);
        } else if (createType === 'fn' && typeof gdUndoRecordCreatedFn === 'function') {
          gdUndoRecordCreatedFn(name, parentNsId);
        }
        activeCreate = null;
        await initGraph();
        // For new fns, auto-select so the user lands on the empty
        // graph card immediately. Without this, only the sidebar
        // refreshes and the canvas keeps showing whatever was there
        // (or nothing) — reads as a hang.
        if (createType === 'fn' && typeof selectJustCreatedFn === 'function') {
          selectJustCreatedFn(name);
        }
      } else {
        throw new Error(await extractResponseError(response));
      }
    },
    onCancel: clearActiveCreate
  });
}

// =============================================================================
// INLINE RENAME
// =============================================================================

// Close an inline rename: drop the row (so the rebuild does not keep it —
// `gdKeepInlineRow`), then repaint to un-hide the label it replaced.
function closeRenameRow(row) {
  row.remove();
  if (typeof updateEntityList === 'function' && graphData) updateEntityList(graphData);
}

// `v` as a double-quoted CSS attribute value.
function attrValue(v) {
  return String(v).replace(/["\\]/g, '\\$&');
}

function startNsRename(headerEl, nsId, nsPath) {
  // Replace label + actions with input row inline.
  const segments = nsPath.split('.');
  const currentName = segments[segments.length - 1];
  const hides = '.ns-label, .description-badge, .ns-row-actions';
  // Hide the original label/actions; the input row takes their place.
  headerEl.querySelectorAll(hides).forEach((el) => { el.style.display = 'none'; });

  const row = buildInlineInputRow({
    placeholder: 'Namespace name — or a.dotted.path to move it',
    indent: 0,
    initialValue: currentName,
    onSubmit: async (newName) => {
      if (newName === currentName) {
        // No-op — just close.
        row.remove();
        await initGraph();
        return;
      }
      // A dotted path MOVES the namespace: `tools.legacy` puts it under
      // `tools` as `legacy`. The server takes `parent-id` on the same PUT
      // the rename uses (`parse-ns-from-form`); the editor only has to
      // resolve the path — fns already move this way (⋯ → ns badge).
      const fields = { name: newName };
      let undoRename = true;
      if (newName.includes('.')) {
        const segs = newName.split('.').map((x) => x.trim());
        const leaf = segs.pop();
        const parentPath = segs.join('.');
        if (!leaf || segs.some((x) => !x)) throw new Error('Empty segment in ' + newName);
        if (parentPath === nsPath || parentPath.startsWith(nsPath + '.')) {
          throw new Error('A namespace cannot move under itself');
        }
        let parentId = null;
        for (const [id, path] of (lookups?.nsPathMap || new Map())) {
          if (path === parentPath) { parentId = id; break; }
        }
        if (!parentId) throw new Error('No namespace ' + parentPath + ' — create it first');
        fields.name = leaf;
        fields['parent-id'] = parentId;
        undoRename = leaf !== currentName && parentId === (lookups?.nsMap?.get(nsId)?.['parent-id'] || null);
      }
      const response = await putEntity('ns', nsId, fields);
      if (response.status >= 200 && response.status < 300) {
        if (undoRename && typeof gdUndoRecordNsRenamed === 'function') {
          gdUndoRecordNsRenamed(nsId, currentName, fields.name);
        }
        row.remove();
        await initGraph();
      } else {
        throw new Error(await extractResponseError(response));
      }
    },
    onCancel: () => closeRenameRow(row)
  });
  gdMarkRenameRow(row, '.ns-header[data-ns-path="' + attrValue(nsPath) + '"]', hides);
  headerEl.appendChild(row);
}

function startFnRename(itemEl, fnId, currentName) {
  const hides = '.name, .ns-row-actions';
  // Hide name + actions; the input row takes their place.
  itemEl.querySelectorAll(hides).forEach((el) => { el.style.display = 'none'; });

  const row = buildInlineInputRow({
    placeholder: 'Graph name',
    indent: 0,
    initialValue: currentName,
    onSubmit: async (newName) => {
      if (newName === currentName) {
        row.remove();
        await initGraph();
        return;
      }
      const response = await putEntity('fn', fnId, { name: newName });
      if (response.status >= 200 && response.status < 300) {
        row.remove();
        await initGraph();
      } else {
        throw new Error(await extractResponseError(response));
      }
    },
    onCancel: () => closeRenameRow(row)
  });
  gdMarkRenameRow(row, '.entity-item[data-fn-id="' + attrValue(fnId) + '"]', hides);
  itemEl.appendChild(row);
}


// =============================================================================
// ROOT-LEVEL "+ New namespace" BUTTON
// =============================================================================

function buildRootCreateButton() {
  const btn = document.createElement('button');
  btn.id = 'create-root-ns-btn';
  btn.className = 'create-root-ns-btn';
  btn.innerHTML = '<span class="create-root-ns-plus">' + PLUS_SVG + '</span>'
                + '<span class="create-root-ns-text">New namespace</span>';
  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    startRootCreate();
  });
  return btn;
}

// =============================================================================
// EXPORTS
// =============================================================================

window.buildNsRowButtons = buildNsRowButtons;
window.buildFnRowButtons = buildFnRowButtons;
window.buildActiveCreateRow = buildActiveCreateRow;
window.buildRootCreateRow = buildRootCreateRow;
window.buildRootCreateButton = buildRootCreateButton;

// True when an inline-create input is currently rooted at `nsId`. The
// sidebar's "hide empty namespaces" rule keeps such a namespace visible
// so the in-progress create row isn't hidden out from under the user.
window.hasActiveCreateIn = (nsId) => !!activeCreate && activeCreate.parentNsId === nsId;

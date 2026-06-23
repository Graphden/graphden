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
const CHECK_SVG = '<svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5L20 7"/></svg>';
const X_SVG = '<svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M6 6l12 12M18 6L6 18"/></svg>';
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
// INLINE-INPUT ROW BUILDER
// =============================================================================

// Build a row `[<input> <save> <cancel>]` indented by `indent` and
// styled to match the sidebar. `placeholder` shows in the empty input.
// `onSubmit(value)` is called when user hits Enter or save; it should
// return a Promise — while pending the row is disabled. `onCancel()`
// is called when user hits Escape, clicks cancel, or blurs (without
// committing). `initialValue` pre-fills the input.
function buildInlineInputRow({ placeholder, indent, initialValue, onSubmit, onCancel }) {
  const row = document.createElement('div');
  row.className = 'inline-input-row';
  row.style.paddingLeft = (indent || 0) + 'px';

  const input = document.createElement('input');
  input.type = 'text';
  input.className = 'inline-input';
  input.placeholder = placeholder || '';
  input.value = initialValue || '';
  input.autocomplete = 'off';

  const saveBtn = document.createElement('button');
  saveBtn.className = 'inline-btn inline-btn-save';
  saveBtn.title = 'Save';
  saveBtn.innerHTML = CHECK_SVG;

  const cancelBtn = document.createElement('button');
  cancelBtn.className = 'inline-btn inline-btn-cancel';
  cancelBtn.title = 'Cancel';
  cancelBtn.innerHTML = X_SVG;

  const errorEl = document.createElement('span');
  errorEl.className = 'inline-error';

  row.appendChild(input);
  row.appendChild(saveBtn);
  row.appendChild(cancelBtn);
  row.appendChild(errorEl);

  let pending = false;

  const setPending = (p) => {
    pending = p;
    input.disabled = p;
    saveBtn.disabled = p;
    cancelBtn.disabled = p;
  };

  const showError = (msg) => {
    errorEl.textContent = msg || '';
    errorEl.style.display = msg ? 'inline' : 'none';
  };

  const tryCommit = async () => {
    if (pending) return;
    const value = input.value.trim();
    if (!value) {
      showError('Name required');
      input.focus();
      return;
    }
    setPending(true);
    showError('');
    try {
      await onSubmit(value);
    } catch (e) {
      showError(e.message || 'Failed');
      setPending(false);
      input.focus();
    }
  };

  saveBtn.addEventListener('click', (e) => { e.stopPropagation(); tryCommit(); });
  cancelBtn.addEventListener('click', (e) => { e.stopPropagation(); if (!pending) onCancel(); });
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); tryCommit(); }
    else if (e.key === 'Escape') { e.preventDefault(); if (!pending) onCancel(); }
  });
  input.addEventListener('click', (e) => e.stopPropagation());

  // Focus on next tick so the row is in the DOM first.
  setTimeout(() => input.focus(), 0);

  return row;
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

  actionsEl.appendChild(editBtn);
  actionsEl.appendChild(buildDeleteButton({
    type: 'ns', id: nsId, displayName: nsPath.split('.').pop(),
    blockReason: nsDeleteBlockReason(nsId)
  }));
  actionsEl.appendChild(plusBtn);
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
      try {
        const response = await deleteEntity(type, id);
        if (response.status >= 200 && response.status < 300) {
          await initGraph();
        } else {
          const text = await response.text().catch(() => '');
          alert('Delete failed (' + response.status + '): '
                + text.replace(/<[^>]+>/g, '').trim().slice(0, 200));
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
  document.body.appendChild(menu);
  activeChildMenu = menu;

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

  // Outside-click closes the menu.
  setTimeout(() => {
    document.addEventListener('click', closeChildCreateMenu, { once: true });
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
        activeCreate = null;
        await initGraph();
        // For new fns, auto-select so the user lands on the empty
        // graph card immediately. Without this, only the sidebar
        // refreshes and the canvas keeps showing whatever was there
        // (or nothing) — reads as a hang.
        if (createType === 'fn' && typeof selectFnByName === 'function') {
          selectFnByName(name);
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

const activeRename = null;  // { nsId, nsPath } | null

function startNsRename(headerEl, nsId, nsPath) {
  // Replace label + actions with input row inline.
  const segments = nsPath.split('.');
  const currentName = segments[segments.length - 1];
  const arrow = headerEl.querySelector('.ns-arrow');
  // Hide the original label/actions; we insert the input row after the arrow.
  headerEl.querySelectorAll('.ns-label, .description-badge, .ns-row-actions')
    .forEach((el) => { el.style.display = 'none'; });

  const row = buildInlineInputRow({
    placeholder: 'Namespace name',
    indent: 0,
    initialValue: currentName,
    onSubmit: async (newName) => {
      if (newName === currentName) {
        // No-op — just close.
        await initGraph();
        return;
      }
      const response = await putEntity('ns', nsId, { name: newName });
      if (response.status >= 200 && response.status < 300) {
        await initGraph();
      } else {
        throw new Error(await extractResponseError(response));
      }
    },
    onCancel: () => {
      // Restore visibility — but updateEntityList rebuilds anyway when
      // graphData hasn't changed. Quickest: just trigger re-render.
      if (typeof updateEntityList === 'function' && graphData) {
        updateEntityList(graphData);
      }
    }
  });
  // Drop into the header replacing where label was.
  headerEl.appendChild(row);
}

function startFnRename(itemEl, fnId, currentName) {
  // Hide name + actions; insert input row in their place.
  itemEl.querySelectorAll('.name, .ns-row-actions')
    .forEach((el) => { el.style.display = 'none'; });

  const row = buildInlineInputRow({
    placeholder: 'Graph name',
    indent: 0,
    initialValue: currentName,
    onSubmit: async (newName) => {
      if (newName === currentName) {
        await initGraph();
        return;
      }
      const response = await putEntity('fn', fnId, { name: newName });
      if (response.status >= 200 && response.status < 300) {
        await initGraph();
      } else {
        throw new Error(await extractResponseError(response));
      }
    },
    onCancel: () => {
      if (typeof updateEntityList === 'function' && graphData) {
        updateEntityList(graphData);
      }
    }
  });
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

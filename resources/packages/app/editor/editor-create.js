// Editor Create — `+` buttons for adding namespaces and fns to the graph.
//
// Two surfaces:
//   1. Top-level `+` in the sidebar header (next to the auth lock).
//      Click → menu with "New namespace…" / "New graph…" — both create
//      at the root (no parent).
//   2. Per-namespace `+` icon, shown on hover next to each namespace
//      row in the sidebar tree. Click → same menu but creates INSIDE
//      that namespace (parent-id = the row's ns-id).
//
// Both surfaces open the same modal form, prefilled with the parent
// context. On submit, we POST to `/api/entities/<type>` via authFetch
// (admin-only), then reload graph entities so the sidebar reflects the
// new node.

const PLUS_SVG = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>';

function initCreateMount() {
  const mount = document.getElementById('create-mount');
  if (!mount) return;
  mount.innerHTML =
    '<button id="create-root-btn" class="create-btn create-btn-header" title="Create…"></button>' +
    '<div id="create-menu" class="create-menu hidden">' +
      '<button class="create-menu-item" data-type="ns">New namespace…</button>' +
      '<button class="create-menu-item" data-type="fn">New graph…</button>' +
    '</div>';
  document.getElementById('create-root-btn').innerHTML = PLUS_SVG;
  document.getElementById('create-root-btn').addEventListener('click', (e) => {
    e.stopPropagation();
    toggleCreateMenu(null);
  });
  document.querySelectorAll('#create-menu .create-menu-item').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      closeCreateMenu();
      openCreateForm(btn.dataset.type, getCreateMenuParentId());
    });
  });
  document.addEventListener('click', (e) => {
    const menu = document.getElementById('create-menu');
    if (!menu || menu.classList.contains('hidden')) return;
    if (menu.contains(e.target)) return;
    closeCreateMenu();
  });
  buildCreateModal();
}

// The currently-selected parent-namespace-id for the next create
// action. Set by the openers (`null` = root, `<uuid>` = inside that ns).
let pendingCreateParentId = null;

function getCreateMenuParentId() {
  return pendingCreateParentId;
}

function toggleCreateMenu(parentNsId) {
  pendingCreateParentId = parentNsId;
  const menu = document.getElementById('create-menu');
  if (!menu) return;
  if (menu.classList.contains('hidden')) {
    menu.classList.remove('hidden');
  } else {
    closeCreateMenu();
  }
}

function closeCreateMenu() {
  const menu = document.getElementById('create-menu');
  if (menu) menu.classList.add('hidden');
}

// Build the inline-`+` button used inside renderNsNode for per-namespace
// creation. Appends to `containerEl`. `nsId` is the ns-entity uuid.
function buildNsPlusButton(containerEl, nsId) {
  const btn = document.createElement('button');
  btn.className = 'create-btn create-btn-inline';
  btn.title = 'Create inside this namespace';
  btn.innerHTML = PLUS_SVG;
  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    // Open the same menu but parented to this ns. Reposition it under
    // the inline button so the menu lands where the click was.
    const menu = document.getElementById('create-menu');
    if (!menu) return;
    const rect = btn.getBoundingClientRect();
    menu.style.position = 'fixed';
    menu.style.top = (rect.bottom + 4) + 'px';
    menu.style.left = rect.left + 'px';
    menu.style.right = 'auto';
    toggleCreateMenu(nsId);
  });
  containerEl.appendChild(btn);
}

// =============================================================================
// MODAL FORM — single shared input for namespace or fn name.
// =============================================================================

function buildCreateModal() {
  let modal = document.getElementById('create-modal');
  if (modal) return;
  modal = document.createElement('div');
  modal.id = 'create-modal';
  modal.className = 'create-modal hidden';
  modal.innerHTML =
    '<div class="create-modal-backdrop"></div>' +
    '<div class="create-modal-panel">' +
      '<div class="create-modal-header" id="create-modal-title">New entity</div>' +
      '<div class="create-modal-body">' +
        '<label for="create-name">Name</label>' +
        '<input id="create-name" type="text" autocomplete="off">' +
        '<div id="create-modal-error" class="create-modal-error hidden"></div>' +
      '</div>' +
      '<div class="create-modal-footer">' +
        '<button id="create-modal-cancel" class="create-modal-btn create-modal-btn-secondary">Cancel</button>' +
        '<button id="create-modal-submit" class="create-modal-btn">Create</button>' +
      '</div>' +
    '</div>';
  document.body.appendChild(modal);

  document.getElementById('create-modal-cancel').addEventListener('click', closeCreateForm);
  modal.querySelector('.create-modal-backdrop').addEventListener('click', closeCreateForm);
  document.getElementById('create-modal-submit').addEventListener('click', submitCreateForm);
  document.getElementById('create-name').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') submitCreateForm();
    if (e.key === 'Escape') closeCreateForm();
  });
}

let activeCreateType = null;
let activeCreateParentId = null;

function openCreateForm(type, parentNsId) {
  if (!isAuthenticated()) {
    // Not signed in — bounce the user to the auth popover instead of
    // letting them type a name only to get a 401 on submit.
    openAuthPopover('Sign in to create entities.');
    return;
  }
  activeCreateType = type;
  activeCreateParentId = parentNsId || null;
  const titleEl = document.getElementById('create-modal-title');
  const nameInput = document.getElementById('create-name');
  const err = document.getElementById('create-modal-error');
  if (titleEl) {
    const what = type === 'ns' ? 'namespace' : 'graph';
    const where = parentNsId ? ' (inside selected namespace)' : '';
    titleEl.textContent = 'New ' + what + where;
  }
  if (nameInput) {
    nameInput.value = '';
    nameInput.focus();
  }
  if (err) { err.textContent = ''; err.classList.add('hidden'); }
  document.getElementById('create-modal').classList.remove('hidden');
}

function closeCreateForm() {
  const modal = document.getElementById('create-modal');
  if (modal) modal.classList.add('hidden');
  activeCreateType = null;
  activeCreateParentId = null;
}

async function submitCreateForm() {
  const nameInput = document.getElementById('create-name');
  const err = document.getElementById('create-modal-error');
  const name = (nameInput && nameInput.value || '').trim();
  if (!name) {
    if (err) { err.textContent = 'Name required.'; err.classList.remove('hidden'); }
    return;
  }
  const body = new URLSearchParams();
  body.set('name', name);
  if (activeCreateParentId) body.set('parent-id', activeCreateParentId);

  try {
    const response = await authFetch('/api/entities/' + activeCreateType, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString()
    });
    if (response.status >= 200 && response.status < 300) {
      closeCreateForm();
      // Reload the sidebar — initGraph fetches /api/graph/entities and
      // calls updateEntityList. Defined in editor-main.js, hoisted to
      // bundle scope.
      await initGraph();
    } else {
      const text = await response.text().catch(() => '');
      if (err) {
        err.textContent = 'Create failed (status ' + response.status + ')'
                        + (text ? ': ' + text.slice(0, 120) : '.');
        err.classList.remove('hidden');
      }
    }
  } catch (e) {
    if (err) {
      err.textContent = 'Network error: ' + e.message;
      err.classList.remove('hidden');
    }
  }
}

window.initCreateMount = initCreateMount;
window.buildNsPlusButton = buildNsPlusButton;

// Editor Namespace-Picker - Lightweight popup for picking a namespace
// from graphData.namespaces. Used by Phase 5's namespace-move strip.
// Mirrors editor-fn-picker but smaller — no return-type column,
// extra "(root)" entry to clear the namespace.
//
// Public API:
//   openNamespacePicker({anchorEl, onPick(ns | null), onCancel?})

let nsPickerEl = null;
let nsPickerOutsideHandler = null;
let nsPickerEscHandler = null;
let nsPickerAnchor = null;

installTabTrap({
  getEl: () => nsPickerEl,
  isVisible: () => !!nsPickerEl,
});

function closeNamespacePicker() {
  if (nsPickerEl) {
    const hadFocus = nsPickerEl.contains(document.activeElement);
    nsPickerEl.remove();
    nsPickerEl = null;
    if (hadFocus) returnFocusTo(nsPickerAnchor);
    nsPickerAnchor = null;
  }
  if (nsPickerOutsideHandler) {
    document.removeEventListener('pointerdown', nsPickerOutsideHandler);
    nsPickerOutsideHandler = null;
  }
  if (nsPickerEscHandler) {
    document.removeEventListener('keydown', nsPickerEscHandler);
    nsPickerEscHandler = null;
  }
}

// Candidate identities and namespace paths come from the authorized in-memory
// tree. The selected authoring graph filters and presents those choices; the
// host keeps focus, popup geometry and the exact callback identity.
function openNamespacePicker(opts) {
  closeNamespacePicker();
  if (!opts?.anchorEl) return;
  nsPickerAnchor = opts.anchorEl;
  if (!graphData || !Array.isArray(graphData.namespaces)) return;

  // Build path strings for every namespace (walk parent-id chain).
  const nsById = new Map();
  graphData.namespaces.forEach(ns => { nsById.set(ns.id, ns); });
  const pathFor = (ns) => {
    const parts = [];
    let cur = ns;
    for (let i = 0; i < 20 && cur; i++) {
      parts.unshift(cur.name);
      cur = cur['parent-id'] ? nsById.get(cur['parent-id']) : null;
    }
    return parts.join('.');
  };
  // "(root)" sentinel = no namespace; null id propagates to clear.
  const candidates = [{ id: null, key: 'root', path: '(root)' }]
    .concat(graphData.namespaces.map(ns => ({ id: ns.id, key: ns.id, path: pathFor(ns) })))
    .sort((a, b) => a.path === '(root)' ? -1 : b.path === '(root)' ? 1
                  : a.path.localeCompare(b.path));
  const byKey = new Map(candidates.map(candidate => [candidate.key, candidate]));

  const el = document.createElement('div');
  el.className = 'fn-picker-popover';  // reuse fn-picker styling
  // Named + announced like the other overlays. It had neither, so a screen
  // reader met an unlabelled box and a sighted reader met a bare filter
  // field with no title and no way out (see the close button below).
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'false');
  el.setAttribute('aria-label', 'Pick a namespace');
  const rect = opts.anchorEl.getBoundingClientRect();
  el.style.top  = (rect.bottom + 6) + 'px';
  el.style.left = Math.max(8, Math.min(rect.left, window.innerWidth - 320)) + 'px';

  const head = document.createElement('div');
  head.className = 'ns-picker-head';
  head.textContent = 'Pick a namespace';
  el.appendChild(head);

  const search = document.createElement('input');
  search.type = 'text';
  search.className = 'fn-picker-search';
  search.placeholder = 'Filter namespaces…';
  // Same combobox shape as the fn-picker: focus stays in the field, ↑↓
  // move a highlight in the list, and aria-activedescendant is what makes
  // a screen reader read the highlighted row out. This picker carried no
  // roles at all, so the arrows were silent.
  search.setAttribute('aria-label', 'Filter namespaces');
  search.setAttribute('role', 'combobox');
  search.setAttribute('aria-expanded', 'true');
  search.setAttribute('aria-autocomplete', 'list');
  search.setAttribute('aria-controls', 'ns-picker-list');
  el.appendChild(search);

  const list = document.createElement('div');
  list.className = 'fn-picker-list';
  list.id = 'ns-picker-list';
  list.setAttribute('role', 'listbox');
  list.setAttribute('aria-label', 'Namespaces');
  el.appendChild(list);

  let activeIdx = 0;
  let filtered = [];
  function render() {
    const activeKey = filtered[activeIdx]?.key || '';
    const body = window.gdAuthoringNamespaceRows(candidates, search.value, activeKey);
    list.replaceChildren(...body.childNodes);
    const rows = [...list.querySelectorAll('.fn-picker-row')];
    filtered = rows.map(row => byKey.get(row.dataset.nsKey));
    activeIdx = Math.max(0, rows.findIndex(row => row.getAttribute('aria-selected') === 'true'));
    if (rows[activeIdx] && rows[activeIdx].getAttribute('aria-selected') !== 'true') {
      rows[activeIdx].classList.add('fn-picker-row-active');
      rows[activeIdx].setAttribute('aria-selected', 'true');
    }
    if (rows[activeIdx]) search.setAttribute('aria-activedescendant', rows[activeIdx].id);
    else search.removeAttribute('aria-activedescendant');
    rows.forEach((row, i) => {
      const c = filtered[i];
      row.addEventListener('mouseenter', () => {
        activeIdx = i;
        list.querySelectorAll('.fn-picker-row-active')
            .forEach(r => {
              r.classList.remove('fn-picker-row-active');
              r.setAttribute('aria-selected', 'false');
            });
        row.classList.add('fn-picker-row-active');
        row.setAttribute('aria-selected', 'true');
        search.setAttribute('aria-activedescendant', row.id);
      });
      row.addEventListener('click', () => {
        closeNamespacePicker();
        if (typeof opts.onPick === 'function') opts.onPick(c);
      });
    });
  }
  render();

  search.addEventListener('input', () => { activeIdx = 0; filtered = []; render(); });
  search.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (activeIdx < filtered.length - 1) { activeIdx++; render(); }
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (activeIdx > 0) { activeIdx--; render(); }
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const c = filtered[activeIdx];
      if (c) {
        closeNamespacePicker();
        if (typeof opts.onPick === 'function') opts.onPick(c);
      }
    } else if (e.key === 'Escape') {
      e.preventDefault();
      closeNamespacePicker();
      if (typeof opts.onCancel === 'function') opts.onCancel();
    }
  });

  // Visible way out. The picker traps Tab and its whole body is a filter
  // field + a listbox — without this the only exits were Escape and a click
  // on whatever the popover was covering. (The fn-picker's counterpart is
  // its Cancel row; this one had neither.) Appended last so it lands at the
  // end of the trapped tab cycle rather than ahead of the filter field.
  if (typeof ensurePopoverClose === 'function') {
    ensurePopoverClose(el, () => {
      closeNamespacePicker();
      if (typeof opts.onCancel === 'function') opts.onCancel();
    }, 'Close namespace picker');
  }

  document.body.appendChild(el);
  nsPickerEl = el;
  setTimeout(() => search.focus(), 0);

  nsPickerOutsideHandler = (e) => {
    if (!el.contains(e.target)) {
      if (pointerEventInTour(e)) return;
      closeNamespacePicker();
      if (typeof opts.onCancel === 'function') opts.onCancel();
    }
  };
  setTimeout(() => document.addEventListener('pointerdown', nsPickerOutsideHandler), 0);
  nsPickerEscHandler = (e) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      closeNamespacePicker();
      if (typeof opts.onCancel === 'function') opts.onCancel();
    }
  };
  document.addEventListener('keydown', nsPickerEscHandler);
}

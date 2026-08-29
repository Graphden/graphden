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

// graph-first-exception: the namespace tree is walked + rendered from the
// in-memory `graphData.namespaces` cache and must appear instantly on click; a
// server partial would add a round-trip per open for data the client already
// holds (§6.1 perf).
function openNamespacePicker(opts) {
  closeNamespacePicker();
  if (!opts?.anchorEl) return;
  nsPickerAnchor = opts.anchorEl;
  if (!graphData || !Array.isArray(graphData.namespaces)) return;

  // Build path strings for every namespace (walk parent-id chain).
  const nsById = new Map();
  graphData.namespaces.forEach(ns => nsById.set(ns.id, ns));
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
  const candidates = [{ id: null, path: '(root)' }]
    .concat(graphData.namespaces.map(ns => ({ id: ns.id, path: pathFor(ns) })))
    .sort((a, b) => a.path === '(root)' ? -1 : b.path === '(root)' ? 1
                  : a.path.localeCompare(b.path));

  const el = document.createElement('div');
  el.className = 'fn-picker-popover';  // reuse fn-picker styling
  const rect = opts.anchorEl.getBoundingClientRect();
  el.style.top  = (rect.bottom + 6) + 'px';
  el.style.left = Math.max(8, Math.min(rect.left, window.innerWidth - 320)) + 'px';

  const search = document.createElement('input');
  search.type = 'text';
  search.className = 'fn-picker-search';
  search.placeholder = 'Filter namespaces…';
  el.appendChild(search);

  const list = document.createElement('div');
  list.className = 'fn-picker-list';
  el.appendChild(list);

  let activeIdx = 0;
  let filtered = [];
  function render() {
    const q = search.value.trim().toLowerCase();
    filtered = candidates
      .filter(c => !q || c.path.toLowerCase().includes(q))
      .slice(0, 80);
    list.innerHTML = '';
    if (filtered.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'fn-picker-empty';
      empty.textContent = 'No matches';
      list.appendChild(empty);
      return;
    }
    if (activeIdx >= filtered.length) activeIdx = 0;
    filtered.forEach((c, i) => {
      const row = document.createElement('div');
      row.className = 'fn-picker-row' + (i === activeIdx ? ' fn-picker-row-active' : '');
      const nm = document.createElement('span');
      nm.className = 'fn-picker-row-name';
      nm.textContent = c.path;
      row.appendChild(nm);
      row.addEventListener('mouseenter', () => {
        activeIdx = i;
        list.querySelectorAll('.fn-picker-row-active')
            .forEach(r => r.classList.remove('fn-picker-row-active'));
        row.classList.add('fn-picker-row-active');
      });
      row.addEventListener('click', () => {
        closeNamespacePicker();
        if (typeof opts.onPick === 'function') opts.onPick(c);
      });
      list.appendChild(row);
    });
  }
  render();

  search.addEventListener('input', () => { activeIdx = 0; render(); });
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

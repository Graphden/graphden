// Editor Fn-Picker - Lightweight popup for picking a fn from graphData.fns.
// Used by Phase 2's arg-type flip (literal → :fn) and reused by Phase 3-4
// for re-parent / MI-add. Mounts as an absolutely-positioned overlay
// anchored to a caller-supplied DOM element.
//
// Public API:
//   openFnPicker({anchorEl, excludeIds, fnNamespaceId, onPick(fn), onCancel?})
//   closeFnPicker()
//
// `excludeIds` is a Set/Array of fn-ids to omit (e.g. self + descendants
// when re-parenting to avoid cycles). `fnNamespaceId` boosts fns sharing
// that namespace to the top of the list.

let fnPickerEl = null;
let fnPickerOutsideHandler = null;
let fnPickerEscHandler = null;

function closeFnPicker() {
  if (fnPickerEl) {
    fnPickerEl.remove();
    fnPickerEl = null;
  }
  if (fnPickerOutsideHandler) {
    document.removeEventListener('pointerdown', fnPickerOutsideHandler);
    fnPickerOutsideHandler = null;
  }
  if (fnPickerEscHandler) {
    document.removeEventListener('keydown', fnPickerEscHandler);
    fnPickerEscHandler = null;
  }
}

function openFnPicker(opts) {
  closeFnPicker();
  if (!opts || !opts.anchorEl) return;
  if (!graphData || !Array.isArray(graphData.fns)) return;

  const excludeSet = new Set(opts.excludeIds || []);
  const wantNs = opts.fnNamespaceId || null;

  // Only globally-named fns are eligible — anonymous locals can't be
  // referenced by id from a different fn's binding-graph anyway.
  const candidates = graphData.fns
    .filter(f => f && f.name && !excludeSet.has(f.id))
    .map(f => ({
      id: f.id,
      name: f.name,
      qualified: (typeof getQualifiedFnName === 'function')
                 ? getQualifiedFnName(f) : f.name,
      sameNs: wantNs && f['namespace-id'] === wantNs,
      returnType: f['return-type'] || null
    }));

  // Build the popup.
  const el = document.createElement('div');
  el.className = 'fn-picker-popover';
  const rect = opts.anchorEl.getBoundingClientRect();
  el.style.top  = (rect.bottom + 6) + 'px';
  el.style.left = Math.max(8, Math.min(rect.left, window.innerWidth - 360)) + 'px';

  const search = document.createElement('input');
  search.type = 'text';
  search.className = 'fn-picker-search';
  search.placeholder = 'Filter fns…';
  el.appendChild(search);

  const list = document.createElement('div');
  list.className = 'fn-picker-list';
  el.appendChild(list);

  // Close-on-cancel button. (Outside-click and Esc also close.)
  const cancelRow = document.createElement('div');
  cancelRow.className = 'fn-picker-cancel-row';
  const cancelBtn = document.createElement('button');
  cancelBtn.type = 'button';
  cancelBtn.className = 'arg-value-edit-btn arg-value-edit-btn-secondary';
  cancelBtn.textContent = 'Cancel';
  cancelBtn.addEventListener('click', () => {
    closeFnPicker();
    if (typeof opts.onCancel === 'function') opts.onCancel();
  });
  cancelRow.appendChild(cancelBtn);
  el.appendChild(cancelRow);

  document.body.appendChild(el);
  fnPickerEl = el;

  // Render filtered list. Same-namespace matches float to the top.
  let activeIdx = 0;
  let filtered = [];
  function render() {
    const q = search.value.trim().toLowerCase();
    filtered = candidates
      .filter(c => !q || c.qualified.toLowerCase().includes(q)
                       || c.name.toLowerCase().includes(q))
      .sort((a, b) => {
        if (a.sameNs !== b.sameNs) return a.sameNs ? -1 : 1;
        return a.qualified.localeCompare(b.qualified);
      })
      .slice(0, 50);
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
      nm.textContent = c.qualified;
      row.appendChild(nm);
      if (c.returnType) {
        const rt = document.createElement('span');
        rt.className = 'fn-picker-row-rt';
        rt.textContent = '→ ' + c.returnType;
        row.appendChild(rt);
      }
      row.addEventListener('mouseenter', () => {
        activeIdx = i;
        list.querySelectorAll('.fn-picker-row-active')
            .forEach(r => r.classList.remove('fn-picker-row-active'));
        row.classList.add('fn-picker-row-active');
      });
      row.addEventListener('click', () => {
        const fn = (graphData.fns || []).find(f => f.id === c.id);
        closeFnPicker();
        if (typeof opts.onPick === 'function') opts.onPick(fn || { id: c.id, name: c.name });
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
        const fn = (graphData.fns || []).find(f => f.id === c.id);
        closeFnPicker();
        if (typeof opts.onPick === 'function') opts.onPick(fn || { id: c.id, name: c.name });
      }
    } else if (e.key === 'Escape') {
      e.preventDefault();
      closeFnPicker();
      if (typeof opts.onCancel === 'function') opts.onCancel();
    }
  });

  setTimeout(() => search.focus(), 0);

  fnPickerOutsideHandler = (e) => {
    if (!el.contains(e.target)) {
      closeFnPicker();
      if (typeof opts.onCancel === 'function') opts.onCancel();
    }
  };
  setTimeout(() => document.addEventListener('pointerdown', fnPickerOutsideHandler), 0);

  // ESC anywhere on the page (input doesn't always receive it after blur).
  fnPickerEscHandler = (e) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      closeFnPicker();
      if (typeof opts.onCancel === 'function') opts.onCancel();
    }
  };
  document.addEventListener('keydown', fnPickerEscHandler);
}

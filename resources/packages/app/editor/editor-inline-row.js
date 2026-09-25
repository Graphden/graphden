// Editor inline-input row — the transient `[<input> ✓ ×]` row the Explorer
// tree mounts for an inline create (new namespace / new graph) and an inline
// rename (namespace ✎ / graph ✎), and its survival across tree rebuilds.
//
// The tree is rebuilt on the NETWORK's schedule as well as the user's: a
// namespace's fn leaves landing after an expand (`refreshLoadedNamespace`),
// a cache prime, the auth probe, a filter evaluation — each tears the rows
// down and builds fresh ones (`updateEntityList`). An open inline row is
// user-owned state: the text being typed, the caret, focus, the server's
// rejection message, a pending submit. A rebuild used to wipe it — a rename
// row vanished mid-typing; a create row came back EMPTY (its state marker
// re-injected a fresh one), so the name typed so far was gone and Enter said
// "Name required". `gdKeepInlineRow` / `gdRestoreInlineRow` bracket every
// rebuild and move the SAME row node into the rebuilt tree.

const CHECK_SVG = '<svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5L20 7"/></svg>';
const X_SVG = '<svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M6 6l12 12M18 6L6 18"/></svg>';

// =============================================================================
// INLINE-INPUT ROW BUILDER
// =============================================================================

// Build a row `[<input> <save> <cancel>]` indented by `indent` and
// styled to match the sidebar. `placeholder` shows in the empty input.
// `onSubmit(value)` is called when user hits Enter or save; it should
// return a Promise — while pending the row is disabled. `onCancel()`
// is called when user hits Escape, clicks cancel, or blurs (without
// committing). `initialValue` pre-fills the input.
// graph-first-exception: an inline single text input that must appear the
// instant the user clicks "+" (§6.4 speed) — a partial fetch to render one
// field would make the create gesture feel laggy. The SUBMIT already POSTs to
// the server (POST /api/entities); only the transient input row is client-built.
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
// SURVIVING A TREE REBUILD
// =============================================================================

// A rename row sits inside the row it renames and hides that row's label;
// record where, so a rebuild can find the NEW element for the same fn / ns
// and hide the same parts of it. `hostSelector` must match exactly one
// element of the rebuilt tree; `hideSelector` names the parts the row
// replaces. A create row carries neither — its marker (`activeCreate`) makes
// the rebuild inject a fresh one, which `gdRestoreInlineRow` swaps out.
function gdMarkRenameRow(row, hostSelector, hideSelector) {
  row.dataset.renameHost = hostSelector;
  row.dataset.renameHides = hideSelector;
}

// Call BEFORE tearing down `scope`: the open inline row inside it, with its
// focus + caret. Null when there is none. A row the flow itself is closing
// (cancel / a committed submit) removes itself first, so it is not kept.
function gdKeepInlineRow(scope) {
  const row = scope?.querySelector('.inline-input-row');
  if (!row) return null;
  const input = row.querySelector('.inline-input');
  const focused = !!input && document.activeElement === input;
  return {
    row,
    focused,
    selStart: focused ? input.selectionStart : null,
    selEnd: focused ? input.selectionEnd : null,
  };
}

// Call AFTER `scope` was rebuilt: put the kept row back. A rename row goes
// into the rebuilt element for the same fn / ns (dropped when the rebuild
// no longer shows it — collapsed, filtered out); a create row replaces the
// fresh one the rebuild injected (dropped when none was — the create ended).
function gdRestoreInlineRow(scope, kept) {
  if (!kept || !scope) return;
  const { row } = kept;
  const host = row.dataset.renameHost;
  if (host) {
    const el = scope.querySelector(host);
    if (!el) return;
    if (row.dataset.renameHides) {
      el.querySelectorAll(row.dataset.renameHides)
        .forEach((part) => { part.style.display = 'none'; });
    }
    el.appendChild(row);
  } else {
    const fresh = scope.querySelector('.inline-input-row');
    if (!fresh || fresh === row) return;
    fresh.parentNode.insertBefore(row, fresh);
    fresh.remove();
  }
  if (kept.focused) {
    const input = row.querySelector('.inline-input');
    input.focus();
    if (kept.selStart !== null) input.setSelectionRange(kept.selStart, kept.selEnd);
  }
}

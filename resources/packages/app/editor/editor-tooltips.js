// Editor Tooltips - Description tooltip + full-name popover singletons.
// Depends on: editor-state.js (no direct module access, but fns are
// shared with the overlay layer via global scope).

// ============================================================================
// DESCRIPTION TOOLTIP
// ============================================================================
//
// Native `title` attribute is unreliable inside Cytoscape overlays —
// the cy canvas + parent mouseenter/leave handlers swallow the hover
// before the browser's tooltip delay fires. We render our own tooltip
// element on mouseenter/leave instead.

let descriptionTooltipEl = null;
// Last-shown tooltip content — kept around so the Edit button (which
// only appears in sticky mode) can read entityType/entityId without
// having to thread them through every render call.
let descriptionTooltipContent = null;
// While editing, neither hover-out NOR document-level outside-click
// should dismiss the tooltip — the user is mid-typing.
let descriptionTooltipEditing = false;

function ensureDescriptionTooltip() {
  if (descriptionTooltipEl) return descriptionTooltipEl;
  const el = document.createElement('div');
  el.className = 'description-tooltip';
  Object.assign(el.style, {
    position: 'fixed',
    zIndex: '10000',
    background: 'var(--tooltip-bg)',
    color: 'var(--tooltip-fg)',
    fontFamily: 'system-ui, sans-serif',
    fontSize: '12px',
    lineHeight: '1.4',
    padding: '6px 10px',
    borderRadius: '4px',
    maxWidth: '360px',
    pointerEvents: 'none',
    boxShadow: '0 2px 8px rgba(0,0,0,0.25)',
    display: 'none',
    whiteSpace: 'pre-wrap'
  });
  document.body.appendChild(el);
  descriptionTooltipEl = el;
  return el;
}

// "sticky" mode keeps the description tooltip visible after a click on
// the i-badge (so iPad / touch users can read the full name + ns +
// description even after the touch ends and mouseleave fires from the
// browser's emulated mouse events). Sticky mode is also the prerequisite
// for the Edit affordance — clicking the badge first pins, then the
// user can click Edit to start typing.
let descriptionTooltipSticky = false;

function showDescriptionTooltip(content, evt) {
  // Don't ever rebuild the tooltip while the user is mid-typing —
  // a stray hover would otherwise wipe out the textarea.
  if (descriptionTooltipEditing) return;
  descriptionTooltipContent = content;
  const el = ensureDescriptionTooltip();
  // Read mode: tooltip is purely informational, so it shouldn't
  // intercept clicks. Edit mode flips this so the textarea is usable.
  el.style.pointerEvents = 'none';
  renderDescriptionTooltip();
  positionDescriptionTooltipAt(el, evt.clientX, evt.clientY);
}

// Render the tooltip body in READ mode using `descriptionTooltipContent`.
// Pulled out of showDescriptionTooltip so the Edit / Save / Cancel
// buttons can re-render after a save without needing a fresh evt.
function renderDescriptionTooltip() {
  const el = descriptionTooltipEl;
  const content = descriptionTooltipContent;
  if (!el || !content) return;
  el.textContent = '';
  const isObj = content && typeof content === 'object';
  const name = isObj ? content.name : null;
  const ns = isObj ? content.namespace : null;
  const text = isObj ? (content.description || '') : (content || '');
  // When pinned, the tooltip needs to capture clicks (for the Edit
  // button and the close ×). Hover-mode keeps pointer-events:none so
  // the floating bubble doesn't intercept anything.
  if (descriptionTooltipSticky) {
    el.style.pointerEvents = 'auto';
    el.appendChild(buildCloseButton());
  } else {
    el.style.pointerEvents = 'none';
  }
  if (name) {
    const nameRow = document.createElement('div');
    nameRow.textContent = name;
    nameRow.style.fontWeight = '600';
    nameRow.style.fontSize = '13px';
    // Reserve space on the right so the close × doesn't overlap
    // long names. 18px = 12px button + 6px gap.
    nameRow.style.paddingRight = descriptionTooltipSticky ? '18px' : '0';
    nameRow.style.marginBottom = '4px';
    el.appendChild(nameRow);
  }
  if (ns) {
    const nsRow = document.createElement('div');
    nsRow.textContent = ns;
    nsRow.style.fontStyle = 'italic';
    nsRow.style.opacity = '0.7';
    nsRow.style.fontSize = '11px';
    nsRow.style.marginBottom = '4px';
    el.appendChild(nsRow);
  }
  const body = document.createElement('div');
  body.className = 'description-tooltip-body';
  if (text) {
    body.textContent = text;
  } else {
    body.textContent = '(no description)';
    body.style.opacity = '0.55';
    body.style.fontStyle = 'italic';
  }
  el.appendChild(body);
  // Edit affordance only when (a) the tooltip is pinned (sticky) and
  // (b) we know which entity this is. Hover-mode tooltips skip the
  // button entirely so the read-only floating tip stays compact.
  if (descriptionTooltipSticky
      && content && content.entityType && content.entityId) {
    const editRow = document.createElement('div');
    editRow.style.marginTop = '6px';
    editRow.style.textAlign = 'right';
    const editBtn = document.createElement('button');
    editBtn.type = 'button';
    editBtn.className = 'description-tooltip-btn';
    editBtn.textContent = '✎ Edit';
    editBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      e.preventDefault();
      enterDescriptionEditMode();
    });
    editRow.appendChild(editBtn);
    el.appendChild(editRow);
  }
  el.style.display = 'block';
}

// Close button (×) sits absolute in the tooltip's top-right corner.
// Touch users need an explicit dismiss target — outside-tap is a
// fallback, but a visible × is the obvious affordance.
function buildCloseButton() {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'description-tooltip-close';
  btn.setAttribute('aria-label', 'Close');
  btn.title = 'Close';
  btn.textContent = '×';
  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    e.preventDefault();
    descriptionTooltipSticky = false;
    descriptionTooltipEditing = false;
    hideDescriptionTooltip(true);
  });
  return btn;
}

function positionDescriptionTooltipAt(el, clientX, clientY) {
  const margin = 12;
  const x = Math.min(clientX + margin, window.innerWidth - el.offsetWidth - margin);
  const y = Math.min(clientY + margin, window.innerHeight - el.offsetHeight - margin);
  el.style.left = Math.max(margin, x) + 'px';
  el.style.top = Math.max(margin, y) + 'px';
}

// EDIT MODE — body becomes a textarea + Save/Cancel. PUT to
// /api/entities/<type>/<id> on save (auth-required), then patch the
// in-memory copy of the entity so subsequent tooltip opens reflect the
// new description without a graph refetch.
function enterDescriptionEditMode() {
  const el = descriptionTooltipEl;
  const content = descriptionTooltipContent;
  if (!el || !content || !content.entityType || !content.entityId) return;
  descriptionTooltipEditing = true;
  el.textContent = '';
  el.style.pointerEvents = 'auto';

  if (content.name) {
    const nameRow = document.createElement('div');
    nameRow.textContent = content.name;
    nameRow.style.fontWeight = '600';
    nameRow.style.fontSize = '13px';
    nameRow.style.marginBottom = '4px';
    el.appendChild(nameRow);
  }

  const ta = document.createElement('textarea');
  ta.className = 'description-tooltip-textarea';
  ta.value = content.description || '';
  ta.rows = Math.max(3, Math.min(8, (ta.value.match(/\n/g) || []).length + 2));
  el.appendChild(ta);

  const errEl = document.createElement('div');
  errEl.className = 'description-tooltip-error';
  errEl.style.display = 'none';
  el.appendChild(errEl);

  const row = document.createElement('div');
  row.style.marginTop = '6px';
  row.style.display = 'flex';
  row.style.gap = '6px';
  row.style.justifyContent = 'flex-end';
  const cancel = document.createElement('button');
  cancel.type = 'button';
  cancel.className = 'description-tooltip-btn description-tooltip-btn-secondary';
  cancel.textContent = 'Cancel';
  cancel.addEventListener('click', (e) => {
    e.stopPropagation();
    e.preventDefault();
    descriptionTooltipEditing = false;
    renderDescriptionTooltip();
  });
  const save = document.createElement('button');
  save.type = 'button';
  save.className = 'description-tooltip-btn';
  save.textContent = 'Save';
  save.addEventListener('click', async (e) => {
    e.stopPropagation();
    e.preventDefault();
    save.disabled = true;
    cancel.disabled = true;
    errEl.style.display = 'none';
    const newDesc = ta.value;
    const ok = await saveEntityDescription(content.entityType, content.entityId, newDesc);
    if (ok) {
      content.description = newDesc;
      patchEntityDescriptionInState(content.entityType, content.entityId, newDesc);
      descriptionTooltipEditing = false;
      renderDescriptionTooltip();
    } else {
      save.disabled = false;
      cancel.disabled = false;
      errEl.textContent = 'Save failed — check that you\'re signed in.';
      errEl.style.display = 'block';
    }
  });
  row.appendChild(cancel);
  row.appendChild(save);
  el.appendChild(row);

  // Focus the textarea so the user can start typing immediately.
  // preventScroll keeps the page from jumping if the tooltip is near
  // the edge.
  if (typeof ta.focus === 'function') {
    try { ta.focus({ preventScroll: true }); } catch (_) { ta.focus(); }
  }
  // Position the tooltip might need to grow vertically — re-measure
  // and clamp so it doesn't fall off the bottom of the viewport.
  const rect = el.getBoundingClientRect();
  if (rect.bottom > window.innerHeight - 8) {
    el.style.top = Math.max(8, window.innerHeight - rect.height - 8) + 'px';
  }
}

// Posts the new description as a form-encoded PUT. The backend's
// permissive `parse-*-from-form` impls only update fields actually
// present in the body, so we don't have to send name/parent/etc.
async function saveEntityDescription(entityType, entityId, description) {
  try {
    const body = 'description=' + encodeURIComponent(description);
    const r = await authFetch('/api/entities/' + encodeURIComponent(entityType)
                              + '/' + encodeURIComponent(entityId), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body
    });
    return r && r.ok;
  } catch (_) {
    return false;
  }
}

// Patch the local in-memory record so subsequent tooltip opens (and
// any other readers) see the new description without a graph refetch.
function patchEntityDescriptionInState(entityType, entityId, description) {
  if (!graphData) return;
  const collKey = entityType === 'fn' ? 'fns'
                : entityType === 'arg' ? 'args'
                : entityType === 'ns'  ? 'ns'
                : null;
  if (!collKey) return;
  const coll = graphData[collKey];
  if (!Array.isArray(coll)) return;
  for (const e of coll) {
    if (e && e.id === entityId) { e.description = description; break; }
  }
  // Lookups (fnMap, argMap) may hold the same object references the
  // arrays do (common after rebuild), but rebuild defensively.
  if (typeof buildLookups === 'function') {
    lookups = buildLookups(graphData);
  }
}

function hideDescriptionTooltip(force) {
  if (descriptionTooltipEditing) return;
  if (descriptionTooltipSticky && !force) return;
  if (descriptionTooltipEl) descriptionTooltipEl.style.display = 'none';
}

// Document-level dismiss for any pinned tooltip. Listens for
// `pointerdown` (not `click`) so iPad taps on non-interactive page
// background — where Safari often skips the click event — still
// close the tooltip reliably. Installed once, idempotent.
function ensureDescriptionTooltipDismissHandler() {
  if (ensureDescriptionTooltipDismissHandler._installed) return;
  ensureDescriptionTooltipDismissHandler._installed = true;
  document.addEventListener('pointerdown', (e) => {
    // While editing, NEVER auto-close — a stray tap outside the
    // tooltip would otherwise destroy in-progress text. (User has
    // the explicit × close button if they want to abandon.)
    if (descriptionTooltipEditing) return;
    if (!descriptionTooltipSticky) return;
    if (e.target.closest && (e.target.closest('.description-badge')
                             || e.target.closest('.description-tooltip'))) {
      return;
    }
    descriptionTooltipSticky = false;
    hideDescriptionTooltip(true);
  });
}

// ============================================================================
// FULL-NAME POPOVER
// ============================================================================
//
// Ancestor rows truncate long names with an ellipsis. Hovering a row
// whose name doesn't fit the row width pops a small bubble above the
// node showing the full name. Singleton element, fixed position,
// fades in/out via opacity + translateY transition.

let fullNameTooltipEl = null;

function ensureFullNameTooltip() {
  if (fullNameTooltipEl) return fullNameTooltipEl;
  const el = document.createElement('div');
  el.className = 'full-name-tooltip';
  Object.assign(el.style, {
    position: 'fixed',
    zIndex: '9999',
    background: 'var(--bg)',
    color: 'var(--fg)',
    border: '1px solid var(--input-border)',
    boxShadow: '0 4px 12px rgba(0,0,0,0.18)',
    padding: '4px 10px',
    borderRadius: '4px',
    fontFamily: 'inherit',
    fontSize: '12px',
    fontWeight: '600',
    whiteSpace: 'nowrap',
    pointerEvents: 'none',
    opacity: '0',
    transform: 'translateY(4px)',
    transition: 'opacity 110ms ease-out, transform 110ms ease-out',
    display: 'none'
  });
  document.body.appendChild(el);
  fullNameTooltipEl = el;
  return el;
}

function showFullNameTooltip(name, anchorEl) {
  const el = ensureFullNameTooltip();
  el.textContent = name;
  el.style.display = 'block';
  // Reset to entry state so re-show animates again even if previously
  // shown without leaving in between.
  el.style.opacity = '0';
  el.style.transform = 'translateY(4px)';
  // Force layout so offsetWidth/Height reflect the new text.
  void el.offsetWidth;
  const anchorRect = anchorEl.getBoundingClientRect();
  const gap = 6;
  let top = anchorRect.top - el.offsetHeight - gap;
  // Flip below when there's no room above.
  if (top < 8) top = anchorRect.bottom + gap;
  let left = anchorRect.left;
  if (left + el.offsetWidth > window.innerWidth - 8) {
    left = window.innerWidth - el.offsetWidth - 8;
  }
  if (left < 8) left = 8;
  el.style.left = left + 'px';
  el.style.top = top + 'px';
  requestAnimationFrame(() => {
    el.style.opacity = '1';
    el.style.transform = 'translateY(0)';
  });
}

function hideFullNameTooltip() {
  if (!fullNameTooltipEl) return;
  fullNameTooltipEl.style.opacity = '0';
  fullNameTooltipEl.style.transform = 'translateY(4px)';
  // Remove from layout once the fade has played out so it doesn't
  // catch hit-tests on stale geometry.
  setTimeout(() => {
    if (fullNameTooltipEl && fullNameTooltipEl.style.opacity === '0') {
      fullNameTooltipEl.style.display = 'none';
    }
  }, 130);
}

// Binds hover handlers that pop the full name when the visible text is
// truncated. `hoverEl` is the element whose mouseenter/leave we listen
// to; `measureEl` is where we measure scrollWidth vs clientWidth (often
// the same element, but for column-below-MI rows the measurement target
// is the floating textOverlay while the hover target is the line).
function bindFullNameHover(hoverEl, measureEl, fullName) {
  if (!fullName) return;
  hoverEl.addEventListener('mouseenter', () => {
    if (measureEl.scrollWidth > measureEl.clientWidth + 1) {
      showFullNameTooltip(fullName, measureEl);
    }
  });
  hoverEl.addEventListener('mouseleave', hideFullNameTooltip);
}


// ============================================================================
// INLINE EDIT POPOVERS — arg-value, arg-rename, fn-rename, fn-return-type
// ============================================================================
//
// All four edits share the same singleton-popover skeleton: a small
// floating panel anchored below the click target with a single
// control (input or select) + Save/Cancel. Closed by Save (success),
// Cancel, Esc, or pointerdown outside.
//
// `openInlineEditPopover` builds the skeleton; the four `enter*EditMode`
// functions just feed it a control factory + save handler.

let inlineEditEl = null;
let inlineEditOutsideHandler = null;

function closeInlineEdit() {
  if (inlineEditEl) {
    inlineEditEl.remove();
    inlineEditEl = null;
  }
  if (inlineEditOutsideHandler) {
    document.removeEventListener('pointerdown', inlineEditOutsideHandler);
    inlineEditOutsideHandler = null;
  }
}

// `opts`: {anchorEl, makeControl(rootEl) → control, doSave(control) → bool|Promise<bool>, onSaved? → void}
// `makeControl` builds the focusable element (input/select) and appends
// it inside rootEl. It must return that element so the skeleton can
// focus it and listen for Enter/Escape.
function openInlineEditPopover(opts) {
  closeInlineEdit();

  const el = document.createElement('div');
  el.className = 'arg-value-edit-popover';
  const rect = opts.anchorEl.getBoundingClientRect();
  el.style.top  = (rect.bottom + 6) + 'px';
  el.style.left = Math.max(8, Math.min(rect.left, window.innerWidth - 280)) + 'px';

  const control = opts.makeControl(el);

  const errorEl = document.createElement('div');
  errorEl.className = 'arg-value-edit-error';
  errorEl.style.display = 'none';

  const buttons = document.createElement('div');
  buttons.className = 'arg-value-edit-buttons';

  const cancel = document.createElement('button');
  cancel.type = 'button';
  cancel.className = 'arg-value-edit-btn arg-value-edit-btn-secondary';
  cancel.textContent = 'Cancel';
  cancel.addEventListener('click', closeInlineEdit);

  const save = document.createElement('button');
  save.type = 'button';
  save.className = 'arg-value-edit-btn';
  save.textContent = 'Save';
  const doSave = async () => {
    save.disabled = true;
    cancel.disabled = true;
    errorEl.style.display = 'none';
    const ok = await opts.doSave(control);
    if (ok) {
      closeInlineEdit();
      if (typeof opts.onSaved === 'function') opts.onSaved();
    } else {
      save.disabled = false;
      cancel.disabled = false;
      errorEl.textContent = 'Save failed — check that you’re signed in.';
      errorEl.style.display = 'block';
    }
  };
  save.addEventListener('click', doSave);
  buttons.appendChild(cancel);
  buttons.appendChild(save);

  el.appendChild(errorEl);
  el.appendChild(buttons);
  document.body.appendChild(el);
  inlineEditEl = el;

  setTimeout(() => {
    if (control && typeof control.focus === 'function') control.focus();
    if (control && typeof control.select === 'function') {
      try { control.select(); } catch (_) {}
    }
  }, 0);

  if (control) {
    control.addEventListener('keydown', (e) => {
      if (e.key === 'Enter')  { e.preventDefault(); doSave(); }
      if (e.key === 'Escape') { e.preventDefault(); closeInlineEdit(); }
    });
  }

  inlineEditOutsideHandler = (e) => {
    if (!el.contains(e.target)) closeInlineEdit();
  };
  setTimeout(() => document.addEventListener('pointerdown', inlineEditOutsideHandler), 0);
}

// --- arg-value edit (Phase 0) ---

function enterArgValueEditMode(arg, anchorEl) {
  if (!arg) return;
  openInlineEditPopover({
    anchorEl,
    makeControl(root) {
      const input = document.createElement('input');
      input.type = 'text';
      input.className = 'arg-value-edit-input';
      const v = arg.value;
      input.value = (typeof v === 'string') ? v
                  : (v === null || v === undefined) ? ''
                  : JSON.stringify(v);
      root.insertBefore(input, root.firstChild);
      return input;
    },
    async doSave(input) { return saveArgValue(arg.id, input.value); },
    onSaved()           { if (typeof renderGraph === 'function') renderGraph(false); }
  });
}

async function saveArgValue(argId, rawInput) {
  // Smart parse: trim, try JSON, fall back to raw string. Empty input
  // is rejected here because the backend's permissive parse skips
  // blank `value=` (so a clear would be a no-op). Phase 2 type-change
  // will be the path to clear values.
  const trimmed = (rawInput || '').trim();
  if (trimmed === '') return false;
  let parsed;
  try { parsed = JSON.parse(trimmed); }
  catch (_) { parsed = rawInput; }
  const jsonStr = JSON.stringify(parsed);
  try {
    const r = await authFetch('/api/entities/arg/' + encodeURIComponent(argId), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'value=' + encodeURIComponent(jsonStr)
    });
    if (r && r.ok) {
      patchArgValueInState(argId, parsed);
      return true;
    }
  } catch (_) {}
  return false;
}

function patchArgValueInState(argId, value) {
  if (!graphData || !Array.isArray(graphData.args)) return;
  for (const a of graphData.args) {
    if (a && a.id === argId) { a.value = value; break; }
  }
  if (typeof buildLookups === 'function') {
    lookups = buildLookups(graphData);
  }
}

// --- arg rename (Phase 1) ---
//
// Edge-label click → rename the arg row whose `fn-id` is the root.
// The backend already enforces unique `(fn-id, name)` so a clash will
// fail server-side; we just propagate the failure as "Save failed".

// `displayLabel` is the name the user currently SEES on the edge —
// either arg.name itself or a propagated label from the source-id
// chain. Pre-filling with it is the least-surprising default; if the
// user just hits Save, the arg gets an explicit `name` equal to the
// inherited label (renamed-forwarding becomes explicit).
function enterArgRenameEditMode(arg, anchorEl, displayLabel) {
  if (!arg) return;
  openInlineEditPopover({
    anchorEl,
    makeControl(root) {
      const input = document.createElement('input');
      input.type = 'text';
      input.className = 'arg-value-edit-input';
      input.value = arg.name || displayLabel || '';
      root.insertBefore(input, root.firstChild);
      return input;
    },
    async doSave(input) {
      const newName = (input.value || '').trim();
      if (!newName) return false;
      try {
        const r = await authFetch('/api/entities/arg/' + encodeURIComponent(arg.id), {
          method: 'PUT',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: 'name=' + encodeURIComponent(newName)
        });
        if (r && r.ok) {
          patchArgFieldInState(arg.id, 'name', newName);
          return true;
        }
      } catch (_) {}
      return false;
    },
    onSaved() { if (typeof renderGraph === 'function') renderGraph(false); }
  });
}

// --- fn rename (Phase 1) ---
//
// Click ✎ pencil on the root fn name → rename popover. After save,
// the sidebar tree needs a refresh too, so we go through the heavy
// `initGraph()` path rather than patch-in-place.

function enterFnRenameEditMode(fn, anchorEl) {
  if (!fn) return;
  openInlineEditPopover({
    anchorEl,
    makeControl(root) {
      const input = document.createElement('input');
      input.type = 'text';
      input.className = 'arg-value-edit-input';
      input.value = fn.name || '';
      root.insertBefore(input, root.firstChild);
      return input;
    },
    async doSave(input) {
      const newName = (input.value || '').trim();
      if (!newName) return false;
      try {
        const r = await authFetch('/api/entities/fn/' + encodeURIComponent(fn.id), {
          method: 'PUT',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: 'name=' + encodeURIComponent(newName)
        });
        if (r && r.ok) return true;
      } catch (_) {}
      return false;
    },
    onSaved() { if (typeof initGraph === 'function') initGraph(); }
  });
}

// --- fn return-type select (Phase 1) ---
//
// Click the `→ <type>` strip on the root fn card → small `<select>`
// dropdown of `value_kind` enum entries → save.

function enterFnReturnTypeEditMode(fn, anchorEl) {
  if (!fn) return;
  openInlineEditPopover({
    anchorEl,
    makeControl(root) {
      const select = document.createElement('select');
      select.className = 'arg-value-edit-input';
      const kinds = (typeof VALUE_KINDS !== 'undefined') ? VALUE_KINDS
                  : ['null','uuid','text','int','bool','numeric','timestamptz','jsonb','bytes','any','fn','sequence'];
      // First option is "(none)" so the user can clear return-type.
      const noneOpt = document.createElement('option');
      noneOpt.value = '';
      noneOpt.textContent = '(none)';
      select.appendChild(noneOpt);
      kinds.forEach(k => {
        const o = document.createElement('option');
        o.value = k;
        o.textContent = k;
        if (fn['return-type'] === k) o.selected = true;
        select.appendChild(o);
      });
      root.insertBefore(select, root.firstChild);
      return select;
    },
    async doSave(select) {
      try {
        const r = await authFetch('/api/entities/fn/' + encodeURIComponent(fn.id), {
          method: 'PUT',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: 'return-type=' + encodeURIComponent(select.value)
        });
        if (r && r.ok) {
          patchFnFieldInState(fn.id, 'return-type', select.value || null);
          return true;
        }
      } catch (_) {}
      return false;
    },
    onSaved() { if (typeof renderGraph === 'function') renderGraph(false); }
  });
}

// --- arg type flip (Phase 2) ---
//
// Type-chip click → small select of `value_kind` enum. On save we
// orchestrate two PUTs to /api/entities/arg/:id:
//
//   1. type=<new>&value=&ref-id=  — backend doesn't cascade type
//      changes, so we have to wipe both bindings explicitly. The
//      permissive parse honours empty form values now (Phase 2
//      backend change).
//   2. (only when new type is `:fn`) ref-id=<picked-fn-id> — opens
//      the fn-picker and writes the chosen ref-id, then refetches.
//
// Refetch is `initGraph()` because flipping type→fn restructures
// the canvas (arg-value node disappears, ref-edge appears).

function enterArgTypeEditMode(arg, anchorEl) {
  if (!arg) return;
  openInlineEditPopover({
    anchorEl,
    makeControl(root) {
      const select = document.createElement('select');
      select.className = 'arg-value-edit-input';
      const kinds = (typeof VALUE_KINDS !== 'undefined') ? VALUE_KINDS
                  : ['null','uuid','text','int','bool','numeric','timestamptz','jsonb','bytes','any','fn','sequence'];
      const cur = arg.type ? String(arg.type).replace(/^:/, '') : '';
      kinds.forEach(k => {
        const o = document.createElement('option');
        o.value = k;
        o.textContent = k;
        if (cur === k) o.selected = true;
        select.appendChild(o);
      });
      root.insertBefore(select, root.firstChild);
      return select;
    },
    async doSave(select) {
      const newType = select.value;
      const curType = arg.type ? String(arg.type).replace(/^:/, '') : null;
      if (newType === curType) return true;  // no-op
      // Step 1: change type, wipe value + ref-id.
      try {
        const r = await authFetch('/api/entities/arg/' + encodeURIComponent(arg.id), {
          method: 'PUT',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: 'type=' + encodeURIComponent(newType)
              + '&value=&ref-id='
        });
        if (!r || !r.ok) return false;
      } catch (_) { return false; }
      // Step 2: if flipped to :fn, prompt for the ref via fn-picker.
      // Picker is fired AFTER the popover closes (onSaved); we just
      // patch local state for now and let onSaved drive the rest.
      patchArgFieldInState(arg.id, 'type', newType);
      patchArgFieldInState(arg.id, 'value', null);
      patchArgFieldInState(arg.id, 'ref-id', null);
      return true;
    },
    onSaved() {
      const newType = arg.type ? String(arg.type).replace(/^:/, '') : null;
      // Re-read local state after patch — `arg` is the SAME object as
      // the one we just patched so its `type` is current.
      if (newType === 'fn' && typeof openFnPicker === 'function') {
        // Allow self-reference but exclude only the fn the arg is
        // attached to to keep things sane. Re-parent (Phase 3) will
        // need the descendants exclusion.
        const fnId = arg['fn-id'];
        openFnPicker({
          anchorEl: document.getElementById('cy') || document.body,
          excludeIds: fnId ? [fnId] : [],
          onPick: async (fn) => { await saveArgRef(arg.id, fn.id); },
          onCancel: () => {
            if (typeof initGraph === 'function') initGraph();
          }
        });
      } else if (typeof initGraph === 'function') {
        initGraph();
      }
    }
  });
}

async function saveArgRef(argId, refFnId) {
  try {
    const r = await authFetch('/api/entities/arg/' + encodeURIComponent(argId), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'ref-id=' + encodeURIComponent(refFnId)
    });
    if (r && r.ok) {
      patchArgFieldInState(argId, 'ref-id', refFnId);
      if (typeof initGraph === 'function') initGraph();
      return true;
    }
  } catch (_) {}
  return false;
}

function patchArgFieldInState(argId, field, value) {
  if (!graphData || !Array.isArray(graphData.args)) return;
  for (const a of graphData.args) {
    if (a && a.id === argId) { a[field] = value; break; }
  }
  if (typeof buildLookups === 'function') lookups = buildLookups(graphData);
}

function patchFnFieldInState(fnId, field, value) {
  if (!graphData || !Array.isArray(graphData.fns)) return;
  for (const f of graphData.fns) {
    if (f && f.id === fnId) { f[field] = value; break; }
  }
  if (typeof buildLookups === 'function') lookups = buildLookups(graphData);
}

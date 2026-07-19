// Editor Tooltips - Description tooltip + full-name popover singletons.
// Depends on: editor-state.js (no direct module access, but fns are
// shared with the overlay layer via global scope).

// ============================================================================
// DESCRIPTION TOOLTIP
// ============================================================================
//
// Native `title` attribute is unreliable inside the graph overlays —
// the graph surface + parent mouseenter/leave handlers swallow the hover
// before the browser's tooltip delay fires. We render our own tooltip
// element on mouseenter/leave instead.

// graph-first-exception: hover-latency singletons (description tooltip,
// full-name popover) — content is already client-resident graph data; a
// round-trip per hover would lag the pointer.
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
  el.className = 'description-tooltip';   // static looks live in editor-styles.css
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
  // Description-only body — name and namespace are surfaced
  // separately on the card (fn-name row + the inline `ns` badge),
  // so duplicating them in the i-tooltip turned it into a wall of
  // redundant labels. Keep this popover focused on the description
  // text alone, which is what the i-glyph promises.
  const body = document.createElement('div');
  body.className = 'description-tooltip-body';
  if (text) {
    body.textContent = text;
  } else {
    body.textContent = '(no description)';
    body.style.opacity = '0.55';
    body.style.fontStyle = 'italic';
  }
  // Reserve space on the right of the body so the close × doesn't
  // overlap the first line of the description.
  if (descriptionTooltipSticky) body.style.paddingRight = '18px';
  el.appendChild(body);
  // Edit affordance only when (a) the tooltip is pinned (sticky) and
  // (b) we know which entity this is. Hover-mode tooltips skip the
  // button entirely so the read-only floating tip stays compact.
  if (descriptionTooltipSticky&& content?.entityType && content.entityId) {
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
  if (!el || !content?.entityType || !content.entityId) return;
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
    const r = await authMutate('PUT',
                               API.api_entities_type_id(entityType, entityId),
                               { description });
    return r?.ok;
  } catch (_) {
    return false;
  }
}

// Patch the local in-memory record so subsequent tooltip opens (and
// any other readers) see the new description without a graph refetch.
function patchEntityDescriptionInState(entityType, entityId, description) {
  if (!graphData) return;
  const collKey = entityType === 'fn'      ? 'fns'
                : entityType === 'binding' ? 'bindings'
                : entityType === 'slot'    ? 'slots'
                : entityType === 'ns'      ? 'namespaces'
                : null;
  if (!collKey) return;
  const coll = graphData[collKey];
  if (!Array.isArray(coll)) return;
  for (const e of coll) {
    if (e && e.id === entityId) { e.description = description; break; }
  }
  // Lookups hold the same object references as the arrays do (common
  // after rebuild), but rebuild defensively.
  if (typeof buildLookups === 'function') {
    lookups = buildLookups(graphData);
  }
  if (typeof rebuildImplementationFnIds === 'function') rebuildImplementationFnIds();
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
  el.className = 'full-name-tooltip';   // static looks live in editor-styles.css
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

// Click-to-reveal reason popover for row-action icons that are visible
// but disabled (e.g. ✎ rename on a fn that's referenced by other fns).
// One singleton — same lifecycle as full-name-tooltip but multi-line
// and dismissed by outside click / Escape / a second activation.
let iconReasonPopoverEl = null;
let iconReasonAnchor = null;

function ensureIconReasonPopover() {
  if (iconReasonPopoverEl) return iconReasonPopoverEl;
  const el = document.createElement('div');
  el.className = 'icon-reason-popover';   // static looks live in editor-styles.css
  el.setAttribute('role', 'status');
  document.body.appendChild(el);
  iconReasonPopoverEl = el;
  // Outside-click / Escape dismissal. Same handler is safe to bind once.
  document.addEventListener('mousedown', (e) => {
    if (!iconReasonPopoverEl || iconReasonPopoverEl.style.display === 'none') return;
    if (iconReasonPopoverEl.contains(e.target)) return;
    if (iconReasonAnchor?.contains(e.target)) return;
    hideIconReasonPopover();
  }, true);
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (!iconReasonPopoverEl || iconReasonPopoverEl.style.display === 'none') return;
    hideIconReasonPopover();
  });
  return el;
}

function showIconReasonPopover(anchorEl, text) {
  if (!anchorEl || !text) return;
  // Toggle off if we're re-clicking the same anchor.
  if (iconReasonAnchor === anchorEl
      && iconReasonPopoverEl
      && iconReasonPopoverEl.style.display !== 'none') {
    hideIconReasonPopover();
    return;
  }
  const el = ensureIconReasonPopover();
  el.textContent = text;
  el.style.display = 'block';
  el.style.opacity = '0';
  el.style.transform = 'translateY(4px)';
  void el.offsetWidth;
  const ar = anchorEl.getBoundingClientRect();
  const gap = 6;
  let top = ar.bottom + gap;
  if (top + el.offsetHeight > window.innerHeight - 8) {
    top = Math.max(8, ar.top - el.offsetHeight - gap);
  }
  let left = ar.left;
  if (left + el.offsetWidth > window.innerWidth - 8) {
    left = window.innerWidth - el.offsetWidth - 8;
  }
  if (left < 8) left = 8;
  el.style.left = left + 'px';
  el.style.top = top + 'px';
  iconReasonAnchor = anchorEl;
  requestAnimationFrame(() => {
    el.style.opacity = '1';
    el.style.transform = 'translateY(0)';
  });
}

function hideIconReasonPopover() {
  if (!iconReasonPopoverEl) return;
  iconReasonPopoverEl.style.opacity = '0';
  iconReasonPopoverEl.style.transform = 'translateY(4px)';
  iconReasonAnchor = null;
  setTimeout(() => {
    if (iconReasonPopoverEl && iconReasonPopoverEl.style.opacity === '0') {
      iconReasonPopoverEl.style.display = 'none';
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
// RELATED MODULES — where the editing UI lives
// ============================================================================
//
// Inline edit popovers (arg-value / arg-rename / fn-rename /
// fn-return-type / arg-type / free-arg-bind / namespace-move /
// sequence add-remove) and their shared `openInlineEditPopover`
// skeleton live in editor-edit-modes.js.
//
// Re-parent (Phase 3) lives in editor-edit-reparent.js.
//
// Type-validation helpers used by those modes live in
// editor-literal-types.js.

// Editor Edge-Label Overlay — render the inheritance-edge labels
// that show arg-name + type-chip + description + rename / sequence
// add-remove affordances when the arg is in the immediate
// implementation.
//
// Globals consumed: lookups, implementationFnIds, isAuthenticated,
// enterArgRenameEditMode, createTypeChip, removeSequenceItem,
// appendSequenceItem, createDescriptionBadge.

// nothing to share with createFnOverlay / createEdgeLabelOverlay.

/**
 * Create overlay for an edge label (multi-line aware).
 * Positioned just to the left of the target node, vertically centered
 * on the target. Uses pre-line white-space so \n in the label produces
 * actual line breaks.
 */
function createEdgeLabelOverlay(edge, container) {
  const label = edge.data('argName');
  if (!label) return;

  // Description lives either on a `:binding` (per-fn override) or on
  // the `:slot` row itself (canonical, when no fn has carved out an
  // override yet). Walk the fn's inheritance chain looking for the
  // closest binding that carries a non-empty `:description`; fall
  // back to the slot's own description.
  let description = null;
  let descriptionTarget = null;       // {entityType, entityId} for Edit
  const sourceArgId = edge.data('sourceArgId');
  const sourceFnId = edge.data('fnId');
  const sourceSlotId = edge.data('slotId');
  if (sourceFnId && sourceSlotId && lookups) {
    const visited = new Set();
    const queue = [sourceFnId];
    let chosenBinding = null;
    while (queue.length) {
      const fid = queue.shift();
      if (visited.has(fid)) continue;
      visited.add(fid);
      const b = (typeof getBindingForFnSlot === 'function')
                  ? getBindingForFnSlot(fid, sourceSlotId) : null;
      if (b?.description) { description = b.description; chosenBinding = b; break; }
      const fn = lookups.fnMap.get(fid);
      if (fn && Array.isArray(fn['parent-ids'])) {
        for (const pid of fn['parent-ids']) queue.push(pid);
      }
    }
    if (chosenBinding) {
      descriptionTarget = { entityType: 'binding', entityId: chosenBinding.id };
    } else {
      const slot = lookups.slotMap?.get(sourceSlotId);
      if (slot) {
        if (slot.description) description = slot.description;
        descriptionTarget = { entityType: 'slot', entityId: slot.id };
      }
    }
  }

  const overlay = document.createElement('div');
  overlay.className = 'edge-label-overlay';
  overlay.dataset.edgeId = edge.id();
  Object.assign(overlay.style, {
    position: 'absolute',
    pointerEvents: 'auto',
    zIndex: '5',
    background: 'var(--bg)',
    color: 'var(--muted-fg)',
    fontFamily: 'SF Mono, Monaco, monospace',
    fontSize: '10px',
    lineHeight: '1.2',
    padding: '2px 4px',
    whiteSpace: 'pre',
    textAlign: 'left',
    transformOrigin: 'top left',
    userSelect: 'none',
    WebkitUserSelect: 'none'
  });

  const labelSpan = document.createElement('span');
  labelSpan.textContent = label;
  overlay.appendChild(labelSpan);

  // Type-chip — show on EVERY edge (not just editable ones) so the
  // viewer can see what type each arg expects without poking around.
  // For non-implementation edges (parents / inherited) the chip is
  // read-only; on editable edges it's still the click-target for
  // changing the arg's type.
  const editArg = (typeof argRowFromNode === 'function')
                  ? argRowFromNode(edge.data())
                  : null;
  const argEditable = editArg
                   && implementationFnIds && implementationFnIds.has(editArg['fn-id'])
                   && (typeof isAuthenticated === 'function' && isAuthenticated());
  if (editArg) {
    const chip = createTypeChip(editArg, { readOnly: !argEditable });
    if (chip) overlay.appendChild(chip);
  }

  if (argEditable) {
    labelSpan.style.cursor = 'pointer';
    labelSpan.title = 'Click to rename arg';
    labelSpan.addEventListener('click', (e) => {
      e.stopPropagation();
      enterArgRenameEditMode(editArg, labelSpan, label);
    });

    // The "change value" affordance lives on the value-fn card's
    // grey use-site header (see `appendUseSiteHeader`) — putting it
    // there scopes the click to "this use-site" instead of cluttering
    // the edge label with a third action.

    // Sequence-item controls (Phase 5) — render `×` on every item
    // edge, plus `+` on the chain tail. Detected from the layout-
    // emitted prev/next-arg-id sibling pointers.
    const prevArgId = edge.data('sourcePrevArgId');
    const nextArgId = edge.data('sourceNextArgId');
    if (prevArgId) {
      const removeBtn = document.createElement('button');
      removeBtn.type = 'button';
      removeBtn.className = 'arg-seq-btn arg-seq-btn-remove';
      removeBtn.textContent = '×';
      removeBtn.title = 'Remove this sequence item';
      removeBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        if (typeof removeSequenceItem === 'function') removeSequenceItem(editArg.id);
      });
      overlay.appendChild(removeBtn);
      // Tail of the chain: also render `+` to append.
      if (!nextArgId) {
        const addBtn = document.createElement('button');
        addBtn.type = 'button';
        addBtn.className = 'arg-seq-btn arg-seq-btn-add';
        addBtn.textContent = '+';
        addBtn.title = 'Append a new item';
        addBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          if (typeof appendSequenceItem === 'function') {
            appendSequenceItem(editArg['fn-id'], addBtn);
          }
        });
        overlay.appendChild(addBtn);
      }
    }

    // The λ/() is-fn chip retired in #15b — `type=:fn` IS the HOF
    // marker now. Flipping HOF behaviour means flipping the type
    // itself, which the type-chip already does. One affordance for
    // one concept.
  }

  if (descriptionTarget) {
    const desc = createDescriptionBadge(description, {
      name: label,
      entityType: descriptionTarget.entityType,
      entityId: descriptionTarget.entityId
    });
    if (desc) overlay.appendChild(desc);
  }

  container.appendChild(overlay);
}

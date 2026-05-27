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
  // Sequence-item edges encode an element/container relationship:
  // the leaf chip is the element type (from the slot's `:of`), and the
  // immediate parent in the source-chain is the sequence anchor. The
  // backend's typeChain surfaces those as TWO stacked chips after the
  // user expands an ancestor, which read as "two unrelated types of
  // one arg" instead of "element of a sequence". Wrap the chip in
  // bracket chrome (CSS ::before/::after on the bracket span — the
  // chip itself stays as the click target for inline-expand and
  // type-edit) so the relationship reads as `[any]` at a glance. The
  // chain block below is suppressed for the same reason — its only
  // entry would be the immediate sequence anchor.
  const isSequenceItem = !!edge.data('sourcePrevArgId');
  let leafChip = null;
  if (editArg) {
    leafChip = createTypeChip(editArg, { readOnly: !argEditable });
    if (leafChip) {
      if (isSequenceItem) {
        const bracket = document.createElement('span');
        bracket.className = 'arg-type-chip-list-bracket';
        bracket.appendChild(leafChip);
        overlay.appendChild(bracket);
        // Override the chip's hover title so the brackets carry meaning
        // explicitly (screen readers / touch users don't get the visual
        // affordance otherwise).
        leafChip.title = 'Element of a sequence — tap to expand or change the element type';
        leafChip.setAttribute('aria-label', leafChip.title);
      } else {
        overlay.appendChild(leafChip);
      }
      // T9 — provenance ↳ badge when this edge's binding narrows the
      // slot's inherited type. Lives on the edge-label overlay (the
      // arg's primary surface) so the user sees the narrowing source
      // inline without expanding the chip.
      if (typeof getTypeNarrowingInfo === 'function'
          && typeof createProvenanceBadge === 'function') {
        const badge = createProvenanceBadge(getTypeNarrowingInfo(editArg), editArg);
        if (badge) {
          if (isSequenceItem) {
            // Drop the badge AFTER the bracket-wrapped chip so the
            // brackets still read as "[type]" and the ↳ sits to the
            // right of the closing bracket.
            overlay.appendChild(badge);
          } else {
            overlay.appendChild(badge);
          }
        }
      }
    }
  }

  // Inline type expansion — the chip IS the trigger. Click reveals
  // the type's constituents (refine→base+constraint, list→element,
  // union→branches, record→fields); for editable primitives the
  // chip opens enterArgTypeEditMode directly. State persists across
  // rebuilds via `expandedTypePaths`.
  const leafRich = (editArg && typeof expectedSlotType === 'function')
                   ? expectedSlotType(editArg) : null;
  const leafFlat = (editArg && typeof resolveArgType === 'function')
                   ? resolveArgType(editArg) : null;
  const leafType = (leafRich != null) ? leafRich : leafFlat;
  if (leafChip && leafType != null
      && typeof attachInlineExpand === 'function') {
    attachInlineExpand(leafChip, leafType, edge.id() + '/leaf', {
      typeName: (typeof leafRich === 'string') ? leafRich
                 : (typeof leafType === 'string' ? leafType : null),
      editable: argEditable,
      onEdit: argEditable ? () => enterArgTypeEditMode(editArg, leafChip) : null,
      bindingId: editArg?.['binding-id'],
      anonymousFnId: (typeof findAnonymousTypeFnId === 'function')
                     ? findAnonymousTypeFnId(editArg) : null,
    });
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
      // Tail of the chain: also render `+` to append — UNLESS this is
      // a nav-typed sequence (`:update-in` `:path`) whose live path
      // already ends at a scalar: there's no valid further segment,
      // so the `+` is suppressed entirely.
      if (!nextArgId) {
        const appendT = (typeof appendNavType === 'function')
                        ? appendNavType(editArg['fn-id'], editArg['slot-id'])
                        : undefined;
        if (appendT !== null) {
          const addBtn = document.createElement('button');
          addBtn.type = 'button';
          addBtn.className = 'arg-seq-btn arg-seq-btn-add';
          addBtn.textContent = '+';
          addBtn.title = 'Append a new item';
          addBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            if (typeof appendSequenceItem === 'function') {
              appendSequenceItem(editArg['fn-id'], addBtn, appendT);
            }
          });
          overlay.appendChild(addBtn);
        }
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

  // Stacked type-narrowing — same idea as the multi-line `name (parent)`
  // rename stacking. Backend emits `:typeChain` only when the source-
  // chain visible at the current expansion crosses a narrowing boundary,
  // so the default single-chip view stays unchanged for non-expanded
  // graphs. The leaf chip is already rendered above; this block adds the
  // historical entries below it.
  //
  // For sequence-item edges the FIRST chain entry (i=1) is the
  // immediate sequence anchor, which is what the bracket chrome above
  // already conveys — skip that entry. Deeper entries (real cross-fn
  // narrowing of a nested sequence's element type, hypothetical) still
  // render normally.
  const typeChain = edge.data('typeChain');
  if (Array.isArray(typeChain) && typeChain.length > 1) {
    const chainStart = isSequenceItem ? 2 : 1;
    if (typeChain.length > chainStart) {
      const block = document.createElement('div');
      block.className = 'edge-type-chain';
      for (let i = chainStart; i < typeChain.length; i++) {
        const entry = typeChain[i];
        const row = document.createElement('div');
        row.className = 'edge-type-chain-row';

        const arrow = document.createElement('span');
        arrow.className = 'edge-type-chain-arrow';
        arrow.textContent = '↑';
        row.appendChild(arrow);

        const chip = document.createElement('span');
        chip.className = 'arg-type-chip arg-type-chip-readonly';
        chip.textContent = entry.type || 'any';
        chip.title = 'Inherited type at ' + (entry.fns?.join(', ') || 'ancestor');
        chip.setAttribute('aria-label', chip.title);
        row.appendChild(chip);

        const src = document.createElement('span');
        src.className = 'edge-type-chain-source';
        src.textContent = '(' + (entry.fns?.join(', ') || '') + ')';
        row.appendChild(src);

        // Attribution — WHY this entry has its type: a binding
        // type-override narrowed it, or it's the slot's own declared
        // type. Backend tags each chain group with `:source`.
        if (entry.source) {
          const kind = document.createElement('span');
          kind.className = 'edge-type-chain-kind';
          if (entry.source === 'binding-override') {
            kind.textContent = 'override';
            kind.title = 'Narrowed by a binding type-override';
          } else {
            kind.textContent = 'slot';
            kind.title = 'The slot’s own declared type';
          }
          kind.setAttribute('aria-label', kind.title);
          row.appendChild(kind);
        }

        block.appendChild(row);
      }
      overlay.appendChild(block);
    }
  }

  registerEdgeOverlay(overlay);
  container.appendChild(overlay);
}

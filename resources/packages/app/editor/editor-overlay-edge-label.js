// Editor Edge-Label Overlay — render the inheritance-edge labels
// that show arg-name + type-chip + description + rename affordances
// when the arg is in the immediate implementation; a SEQUENCE slot
// gets one such label for the whole list (`createSeqGroupLabelOverlay`)
// and a small `×` per item (`createSeqItemOverlay`) — appending is the
// list's trailing placeholder, not a button here.
//
// graph-first-exception: the label / type-chip / typeChain rendering
// stays client-side — every input is either layout-emitted edge data
// (argName, typeChain, descSource, sibling pointers) rendered
// verbatim, or the client type formatter shared with the arg-chips
// (sub-100ms canvas path, §6.1/6.2); the one server-logic mirror this
// file carried (the description-precedence BFS) now ships from the
// layout as `:descSource`.
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
  // λ-params read as 'λname' — the per-call argument the enclosing
  // HOF supplies, visually distinct from the caller's own signature.
  const label = (edge.data('lambdaArg') ? 'λ' : '') + (edge.data('argName') || '');
  if (!label) return;
  buildEdgeLabelOverlay(edge, container, label, edge.id());
}


/**
 * The ONE label of a sequence group — the slot's name and its element
 * type — built from the group's head member and registered under the
 * trunk's id, so the geometry pass parks it at the end of the trunk
 * (centred on the items) and fans the branches out from its right edge.
 */
function createSeqGroupLabelOverlay(edge, container) {
  const label = edge.data('seqLabel') || edge.data('argName') || '';
  if (!label) return;
  const overlay = buildEdgeLabelOverlay(edge, container, label, seqTrunkId(edge.data('seqGroup')));
  if (!overlay) return;
  overlay.classList.add('edge-label-seq');
  overlay.dataset.seqGroup = edge.data('seqGroup');
  overlay.dataset.sourceId = edge.source()?.id() || '';
}


/**
 * A list item's own overlay: the `×` that removes it, hugging the item
 * on its branch. Only for an editable item (the tail placeholder is the
 * append affordance, and an inherited item is the ancestor's to edit).
 */
function createSeqItemOverlay(edge, container) {
  if (edge.data('seqTail') || !edge.data('sourcePrevArgId')) return;
  const editArg = (typeof argRowFromNode === 'function') ? argRowFromNode(edge.data()) : null;
  const argEditable = editArg && implementationFnIds?.has(editArg['fn-id'])
                   && (typeof isAuthenticated === 'function' && isAuthenticated());
  if (!argEditable) return;

  const overlay = document.createElement('div');
  overlay.className = 'edge-label-overlay edge-seq-item';
  overlay.dataset.edgeId = edge.id();
  overlay.dataset.itemId = editArg.id || '';
  if (typeof editArg.position === 'number') overlay.dataset.position = String(editArg.position);
  // `position` is the stored ordinal — after swaps it need not be dense
  // (0, 2, 3). `index` is the item's RANK in its list, what a reader means
  // by "the second item" and what a tour step can point at.
  const siblings = lookups?.itemsByBinding?.get(editArg['binding-id']) || [];
  const rank = siblings.findIndex((it) => it.id === editArg.id);
  if (rank >= 0) overlay.dataset.index = String(rank);
  const btn = (cls, glyph, title, onClick) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'arg-seq-btn ' + cls;
    b.textContent = glyph;
    b.title = title;
    b.setAttribute('aria-label', title);
    b.addEventListener('click', (e) => { e.stopPropagation(); onClick(b); });
    overlay.appendChild(b);
    return b;
  };
  // The order verbs a fn-card item gets from its row-actions (↑ / ↓ /
  // + Insert-before) — a LITERAL item has no ⋯, so they live here, next
  // to its ×. Moving past either end is a server no-op; the buttons stay
  // so the strip keeps one shape whichever item it hugs.
  btn('arg-seq-btn-up', '↑', 'Move this item up', () => {
    if (typeof moveSequenceItem === 'function') moveSequenceItem(editArg.id, 'up');
  });
  btn('arg-seq-btn-down', '↓', 'Move this item down', () => {
    if (typeof moveSequenceItem === 'function') moveSequenceItem(editArg.id, 'down');
  });
  if (typeof editArg.position === 'number' && editArg['fn-id']) {
    btn('arg-seq-btn-insert', '+', 'Insert a new item before this one', (b) => {
      if (typeof appendSequenceItem !== 'function') return;
      appendSequenceItem(editArg['fn-id'], b, undefined,
                         { position: editArg.position,
                           elemType: (typeof seqElemType === 'function') ? seqElemType(editArg) : null });
    });
  }
  btn('arg-seq-btn-remove', '×', 'Remove this item from the list', () => {
    if (typeof removeSequenceItem === 'function') removeSequenceItem(editArg.id);
  });
  registerEdgeOverlay(overlay);
  container.appendChild(overlay);
  return overlay;
}


function buildEdgeLabelOverlay(edge, container, label, overlayId) {

  // Description precedence (closest binding with a non-empty
  // `:description` in the owning fn's parent-ids closure, else the
  // slot row) is resolved SERVER-side now — the layout emits
  // `:descSource {entityType, entityId}` per edge
  // (`edge-description-fields` in layout/builder_helpers.clj). The
  // TEXT is read from client lookups by id so an in-page description
  // edit shows fresh on the next overlay rebuild without a layout
  // refetch.
  let description = null;
  let descriptionTarget = null;       // {entityType, entityId} for Edit
  const descSource = edge.data('descSource');
  if (descSource?.entityId && lookups) {
    descriptionTarget = { entityType: descSource.entityType,
                          entityId: descSource.entityId };
    const row = descSource.entityType === 'binding'
      ? lookups.bindingMap?.get(descSource.entityId)
      : lookups.slotMap?.get(descSource.entityId);
    description = row?.description || null;
  }

  const overlay = document.createElement('div');
  overlay.className = 'edge-label-overlay';   // static looks in editor-styles.css
  overlay.dataset.edgeId = overlayId;
  // The arg's NAME, so a tour step can ring one label among several by the
  // name its text uses (`.edge-label-overlay[data-arg-name="method"] …`) —
  // the same convention as `data-fn-name` on a card.
  overlay.dataset.argName = label;

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
  // Compare mode: a ref / list arg whose binding differs on the compared
  // branch marks its edge label (the value nodes carry their own marks;
  // a ref bound here has only this label and the ghost beside the card).
  if (editArg?.['fn-id'] && editArg.name && typeof gdDiffSlotDetails === 'function'
      && gdDiffSlotDetails(editArg['fn-id'])?.[editArg.name]) {
    overlay.classList.add('edge-label-diff');
    overlay.title = 'Differs vs the compared branch — see the digest in the Explorer';
  }
  const argEditable = editArg&& implementationFnIds?.has(editArg['fn-id'])
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
    // the edge label with a third action. A list's `×` per item and
    // its append tail live on the items themselves, not on this label.

    // There's no separate λ/() is-fn chip — `type=:fn` IS the HOF
    // marker. Flipping HOF behaviour means flipping the type itself,
    // which the type-chip already does. One affordance for one
    // concept.
  }

  if (descriptionTarget) {
    const desc = createDescriptionBadge(description, {
      name: label,
      entityType: descriptionTarget.entityType,
      entityId: descriptionTarget.entityId
    });
    if (desc) overlay.appendChild(desc);
  }

  // The seals — 🔒 when this slot is sealed / its list closed / it was made
  // required (here or above), a dim 🔓 on an edge the reader may seal; the
  // popover behind it is where descendants' rights are decided
  // (editor-overlay-seal.js). Nothing on a read-only edge with no seal.
  if (editArg && typeof createSealBadge === 'function') {
    const seal = createSealBadge(editArg, edge.data());
    if (seal) overlay.appendChild(seal);
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
  return overlay;
}

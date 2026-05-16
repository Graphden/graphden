// Editor Arg Overlays — render the arg-value overlays on the canvas,
// including the in-place-editable click target, type-chip, and
// persistent type-mismatch indicator.
//
// Globals consumed: lookups, implementationFnIds, isAuthenticated,
// expectedSlotType, validateLiteralAgainstType, formatTypeHint,
// enterArgValueEditMode, enterArgTypeEditMode, createOverlay,
// createDragHandle, truncateLabel.

/**
 * Create overlay for arg value node
 */
function createArgOverlay(node, container) {
  const overlay = createOverlay(node.id(), { borderRadius: '4px', fontSize: '10px' });

  // Column-flex outer: the inline content (value text + chip +
  // trigger + badges) sits in one row, the drag handle docks BELOW
  // the row (same convention as fn-overlay cards). The previous
  // single-row layout pushed the drag handle out to the right of
  // the chip, looking like another inline affordance instead of
  // the "grab here to move the node" plate.
  overlay.style.display = 'flex';
  overlay.style.flexDirection = 'column';
  overlay.style.alignItems = 'stretch';

  const row = document.createElement('div');
  row.className = 'arg-overlay-row';
  row.style.display = 'flex';
  row.style.alignItems = 'center';
  row.style.flex = '1';
  overlay.appendChild(row);

  const content = document.createElement('div');
  content.style.padding = '4px 8px';
  content.style.flex = '1';
  content.textContent = truncateLabel(node.data('label') || '', 30);
  row.appendChild(content);

  // Persistent mismatch indicator. If this arg's literal value would
  // fail the live type-check (same logic as the value-edit popover),
  // mark the overlay so the user can spot the broken binding without
  // opening the popover. Pure-literal check — non-literal bindings
  // (refs, rename-only bindings) skip and stay unmarked.
  //
  // The mismatch ring is paired with a `!` badge that opens the
  // mismatch-explainer popover on click. The native `title=` was
  // hover-only and invisible on touch — the badge gives every
  // device an obvious tap target whose role is "explain why I'm red".
  (function annotateMismatch() {
    const argLocal = (typeof argRowFromNode === 'function')
                     ? argRowFromNode(node.data())
                     : null;
    if (!argLocal || argLocal['ref-id']) return;
    if (typeof expectedSlotType !== 'function'
        || typeof validateLiteralAgainstType !== 'function'
        || typeof formatTypeHint !== 'function') return;
    const expected = expectedSlotType(argLocal);
    if (!expected) return;
    const v = argLocal.value;
    if (v === null || v === undefined) return;
    const r = validateLiteralAgainstType(v, expected);
    if (r && r.ok === false) {
      overlay.classList.add('arg-overlay-mismatch');
      // The badge reads "!" — a recognised warning glyph that's
      // narrower than ⚠ so it fits the same 15/28-px frame as the
      // sibling i / ↗ icons. ARIA-label carries the long form for
      // screen readers; sighted users tap to read the explainer.
      const badge = document.createElement('button');
      badge.type = 'button';
      badge.className = 'arg-mismatch-badge';
      badge.textContent = '!';
      badge.setAttribute('aria-label',
        'Type mismatch — tap for details. Expected: '
        + formatTypeHint(expected));
      badge.title = 'Type mismatch — tap for details';
      badge.addEventListener('click', (e) => {
        e.stopPropagation();
        if (typeof showMismatchExplainer === 'function') {
          showMismatchExplainer(argLocal, overlay);
        }
      });
      // Stop propagation on touchstart/mousedown so a tap on the
      // badge doesn't also drag the overlay (createDragHandle
      // listens on the overlay itself).
      badge.addEventListener('mousedown', (e) => e.stopPropagation());
      badge.addEventListener('touchstart', (e) => e.stopPropagation(),
                             { passive: true });
      row.appendChild(badge);
    }
  })();

  // Editability: this arg-value is in-place editable iff
  //   - the arg's owning fn is in the IMMEDIATE IMPLEMENTATION of the
  //     navigated root (root + transitive ref-id closure). Anything
  //     revealed by an ancestor expansion stays read-only — the user
  //     navigates to that fn's own page to edit it;
  //   - the user is signed in (authFetch will surface 401 either way,
  //     but we suppress the affordance pre-flight for clarity).
  const arg = (typeof argRowFromNode === 'function')
              ? argRowFromNode(node.data())
              : null;
  const inImpl = arg && implementationFnIds && implementationFnIds.has(arg['fn-id']);
  const signedIn = typeof isAuthenticated === 'function' && isAuthenticated();
  const editable = inImpl && signedIn;
  if (editable) {
    content.style.cursor = 'pointer';
    content.title = 'Click to edit value';
    content.addEventListener('click', (e) => {
      e.stopPropagation();
      enterArgValueEditMode(arg, content);
    });
    // Type-chip — synth rows carry the slot's resolved `:type`,
    // so this is just a presence check.
    if (arg.type) {
      const chip = createTypeChip(arg);
      if (chip) {
        row.appendChild(chip);
        attachArgChipExpand(chip, arg, node.id(), { editable: true });
      }
    }
  } else if (inImpl && !signedIn) {
    // Read-only because the user is unauthenticated, NOT because
    // the overlay is structurally read-only (ancestor-expansion
    // overlays always stay read-only — those get a different
    // hint below). Click → open the auth lock popover so the path
    // back to "edit" is one tap, not "find the lock icon yourself".
    content.style.cursor = 'help';
    content.title = 'Sign in to edit this value (tap to open login)';
    content.addEventListener('click', (e) => {
      e.stopPropagation();
      const lockBtn = document.getElementById('auth-lock-btn');
      if (lockBtn) lockBtn.click();
    });
    if (arg.type) {
      const chip = createTypeChip(arg, { readOnly: true });
      if (chip) {
        row.appendChild(chip);
        attachArgChipExpand(chip, arg, node.id(), { editable: false });
      }
    }
  } else if (arg && !inImpl) {
    // Structurally read-only — this row was surfaced via an
    // ancestor expansion. To edit it the user has to navigate to
    // the owning fn's page. Show a hint pointing at the ↗ open-
    // in-new-tab icon (which already lives next to the row when
    // available) and provide a fallback click that does the
    // navigation for them.
    content.style.cursor = 'help';
    content.title = 'Read-only here — tap to open this fn\'s page';
    content.addEventListener('click', (e) => {
      e.stopPropagation();
      // Prefer the ↗ link if the row already has one — it carries
      // the qualified-name → hash routing logic. Otherwise fall
      // back to setting location.hash directly when we can resolve
      // the fn-id to a name.
      const openLink = overlay.querySelector('.open-in-new-tab');
      if (openLink?.click) { openLink.click(); return; }
      if (lookups?.fnMap && typeof getQualifiedFnName === 'function') {
        const owning = lookups.fnMap.get(arg['fn-id']);
        const qn = owning && getQualifiedFnName(owning);
        if (qn && qn !== '(anonymous)') {
          window.location.hash = encodeURIComponent(qn);
        }
      }
    });
    if (arg.type) {
      const chip = createTypeChip(arg, { readOnly: true });
      if (chip) {
        row.appendChild(chip);
        attachArgChipExpand(chip, arg, node.id(), { editable: false });
      }
    }
  }

  createDragHandle(overlay, node);
  container.appendChild(overlay);
}

// Wire the inline-expand click handler onto a chip rendered on an
// arg-value-overlay (mirrors the edge-label hookup). Stable path is
// keyed by the cy node-id so the open/closed state survives layout
// rebuilds and hover-preview redraws.
function attachArgChipExpand(chipEl, arg, nodeId, opts) {
  if (typeof attachInlineExpand !== 'function') return;
  const rich = (typeof expectedSlotType === 'function')
               ? expectedSlotType(arg) : null;
  const effective = (rich != null) ? rich : resolveArgType(arg);
  if (effective == null) return;
  const editable = !!opts?.editable;
  attachInlineExpand(chipEl, effective, nodeId + '/arg-chip', {
    typeName: (typeof rich === 'string') ? rich
               : (typeof effective === 'string' ? effective : null),
    editable,
    onEdit: editable ? () => enterArgTypeEditMode(arg, chipEl) : null,
    bindingId: arg?.['binding-id'],
    anonymousFnId: findAnonymousTypeFnId(arg),
  });
}


// Return the fn-id of the type-row backing this arg's slot WHEN that
// type-row is anonymous (no `:name`). Used by the inline-expand
// panel's promote-anonymous affordance. Returns null when the type-
// row has a name (already promoted) or when we can't resolve it.
function findAnonymousTypeFnId(arg) {
  if (!arg || !lookups) return null;
  const binding = arg['binding-id']
    ? lookups.bindingMap?.get(arg['binding-id']) : null;
  const slot = arg['slot-id'] ? lookups.slotMap?.get(arg['slot-id']) : null;
  const typeFnId = binding?.['type-override-fn-id']
                || slot?.['type-fn-id'];
  if (!typeFnId) return null;
  const typeFn = lookups.fnMap?.get(typeFnId);
  return (typeFn && !typeFn.name) ? typeFnId : null;
}


// Effective type for an arg row — the layout-emitted row already
// carries the slot's resolved type-kw (binding's type-override-fn-id
// or slot's type-fn-id), so a single field read is enough.
function resolveArgType(arg) {
  if (!arg || !arg.type) return null;
  return String(arg.type).replace(/^:/, '');
}

// Compact button styled like the description-i and ↗ glyphs but
// wider (text label fits "timestamptz" at ~9px). Click →
// enterArgTypeEditMode when editable; pure label when readOnly.
function createTypeChip(arg, options) {
  const readOnly = !!(options?.readOnly)
                || typeof enterArgTypeEditMode !== 'function';
  const flatType = resolveArgType(arg) || 'any';
  // Rich-types is indexed by (fn-name, slot-name) and doesn't yet honor
  // binding-level `type-override-fn-id`. When a binding narrows the slot
  // type, `flatType` (computed from the binding's anchor row, see
  // `build-anchor-row`) is more specific. Prefer it over `any` from
  // rich-types so the chip reflects the actual effective type at this fn.
  const effectiveRich = (typeof expectedSlotType === 'function')
                        ? expectedSlotType(arg) : null;
  const richType = (effectiveRich === 'any' && flatType !== 'any')
                   ? null
                   : effectiveRich;
  const display = compactTypeChipText(richType, flatType);
  const chip = document.createElement('span');
  chip.className = 'arg-type-chip' + (readOnly ? ' arg-type-chip-readonly' : '');
  chip.textContent = display;
  // Hover-title shows the FULL rich type if any. The visible text
  // already prefers the rich form so this is just for cases where
  // the compact rendering elided detail.
  let hint = display;
  if (richType && typeof formatTypeHint === 'function') {
    hint = formatTypeHint(richType);
  }
  // Chip click behaviour is wired by attachInlineExpand (called from
  // the chip's owner — edge-label overlay or arg overlay). For
  // composite types it toggles the inline expansion panel; for
  // editable primitives it drops straight into enterArgTypeEditMode.
  // Read-only primitives are inert. Keeping the click logic OUT of
  // createTypeChip avoids two competing handlers on the same chip.
  chip.title = 'Type: ' + hint
             + (readOnly ? '' : ' — tap to expand or change');
  chip.setAttribute('aria-label', chip.title);
  return chip;
}

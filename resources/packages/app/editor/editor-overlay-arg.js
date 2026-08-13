// Editor Arg Overlays — render the arg-value overlays on the canvas,
// including the in-place-editable click target, type-chip, and
// persistent type-mismatch indicator.
//
// Globals consumed: lookups, implementationFnIds, isAuthenticated,
// expectedSlotType, validateLiteralAgainstType, formatTypeHint,
// enterArgValueEditMode, enterArgTypeEditMode, createOverlay,
// createDragHandle, truncateLabel.

// graph-first-exception: canvas overlay — in-place edit click target,
// type chip and mismatch ring live in graph coordinates and re-anchor
// per frame; data (types, provenance) is server-fed, DOM is client.
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
  // The canvas label is hard-truncated at 30 chars. When it IS
  // truncated the native `title=` becomes the only way to read the
  // full value (the action hints below are dropped in that case) —
  // critical for read-only rows that have no edit popover at all.
  const rawLabel = node.data('label') || '';
  const isTruncated = rawLabel.length > 30;
  content.textContent = truncateLabel(rawLabel, 30);
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
  const inImpl = arg && implementationFnIds?.has(arg['fn-id']);
  const signedIn = typeof isAuthenticated === 'function' && isAuthenticated();
  // Ownership (tenancy): you can only edit a value on a fn your org OWNS.
  // A public / other-org fn is read-only even when signed-in + reachable.
  const argFn = arg ? lookups?.fnMap?.get(arg['fn-id']) : null;
  const owned = (typeof graphdenIsFnOwned !== 'function') || graphdenIsFnOwned(argFn);
  const editable = inImpl && signedIn && owned;
  if (editable) {
    content.style.cursor = 'pointer';
    content.title = isTruncated ? rawLabel : 'Click to edit value';
    // Visible affordance on hover (CSS ::after ✎) — click-to-edit was
    // pure cursor+title before, i.e. invisible until stumbled upon.
    content.classList.add('arg-value-editable');
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
        const badge = createProvenanceBadge(getTypeNarrowingInfo(arg), arg);
        if (badge) row.appendChild(badge);
      }
    }
  } else if (inImpl && signedIn && !owned) {
    // Read-only because the fn belongs to another org / the platform (tenancy)
    // — not a sign-in or navigation issue; you can't edit what you don't own.
    // Click shows the value in the read-only viewer (like the structural case).
    content.style.cursor = 'pointer';
    content.title = isTruncated ? rawLabel
                  : 'Read-only — this function belongs to another owner';
    content.addEventListener('click', (e) => {
      e.stopPropagation();
      if (typeof openValueViewer === 'function') openValueViewer(arg, content);
    });
    if (arg.type) {
      const chip = createTypeChip(arg, { readOnly: true });
      if (chip) {
        row.appendChild(chip);
        attachArgChipExpand(chip, arg, node.id(), { editable: false });
        const badge = createProvenanceBadge(getTypeNarrowingInfo(arg), arg);
        if (badge) row.appendChild(badge);
      }
    }
  } else if (inImpl && !signedIn) {
    // Read-only because the user is unauthenticated, NOT because
    // the overlay is structurally read-only (ancestor-expansion
    // overlays always stay read-only — those get a different
    // hint below). Click → open the auth lock popover so the path
    // back to "edit" is one tap, not "find the lock icon yourself".
    content.style.cursor = 'help';
    content.title = isTruncated ? rawLabel
                  : 'Sign in to edit this value (tap to open login)';
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
        const badge = createProvenanceBadge(getTypeNarrowingInfo(arg), arg);
        if (badge) row.appendChild(badge);
      }
    }
  } else if (arg && !inImpl) {
    // Structurally read-only — this row was surfaced via an
    // ancestor expansion. To edit it the user has to navigate to
    // the owning fn's page. Show a hint pointing at the ↗ open-
    // in-new-tab icon (which already lives next to the row when
    // available) and provide a fallback click that does the
    // navigation for them.
    content.style.cursor = 'pointer';
    content.title = isTruncated ? rawLabel
                  : 'Read-only here — tap to view the full value';
    content.addEventListener('click', (e) => {
      e.stopPropagation();
      // Structurally read-only — no edit popover. Show the value in
      // the read-only viewer (full, type-aware, scrollable). Navigation
      // to the owning fn's page stays on the ↗ open-in-new-tab icon.
      if (typeof openValueViewer === 'function') openValueViewer(arg, content);
    });
    if (arg.type) {
      const chip = createTypeChip(arg, { readOnly: true });
      if (chip) {
        row.appendChild(chip);
        attachArgChipExpand(chip, arg, node.id(), { editable: false });
        const badge = createProvenanceBadge(getTypeNarrowingInfo(arg), arg);
        if (badge) row.appendChild(badge);
      }
    }
  }

  // Inheritance provenance — when this row's slot was inherited from
  // a parent fn, append a compact ↖ affordance. Shown regardless of
  // editability: knowing where a slot came from is useful even on a
  // read-only ancestor-expansion row.
  const sourceLink = createArgSourceLink(node);
  if (sourceLink) row.appendChild(sourceLink);

  createDragHandle(overlay, node);
  container.appendChild(overlay);
}

// Provenance affordance for an inherited-slot arg row. Returns a
// compact ↖ link when the layout marked this node with a `sourceChain`
// (the full ancestor chain the slot was inherited through, leaf→root).
// Hover reveals the whole chain; click opens the immediate parent in a
// new tab. Returns null for an own (non-inherited) slot.
function createArgSourceLink(node) {
  const chain = node.data('sourceChain');
  if (!Array.isArray(chain) || chain.length === 0) return null;
  const names = chain.map(c => c.fnName || '(anonymous)');
  const link = document.createElement('a');
  link.className = 'arg-source-link';
  link.textContent = '↖';
  // leaf→root chain reads as "inherited from X, which got it from Y…".
  const label = 'Inherited from ' + names.join(' ← ');
  link.title = label;
  link.setAttribute('aria-label', label);
  // Navigate to the immediate parent the slot came from, when it
  // resolves to a globally addressable name (anonymous → tooltip only).
  const immediate = chain[0];
  const fn = (immediate?.fnId && lookups?.fnMap)
             ? lookups.fnMap.get(immediate.fnId) : null;
  const qualified = (fn && typeof getQualifiedFnName === 'function')
                    ? getQualifiedFnName(fn) : null;
  if (qualified && qualified !== '(anonymous)') {
    // Same-tab hash navigation — a provenance hop is ordinary
    // navigation, not a "keep both open" action; middle-click /
    // ctrl-click still opens a new tab the standard way.
    link.href = '#' + encodeURIComponent(qualified);
  }
  // Don't let a tap on the affordance also drag the overlay.
  link.addEventListener('mousedown', (e) => e.stopPropagation());
  link.addEventListener('touchend', (e) => e.stopPropagation());
  link.addEventListener('click', (e) => e.stopPropagation());
  return link;
}

// Wire the inline-expand click handler onto a chip rendered on an
// arg-value-overlay (mirrors the edge-label hookup). Stable path is
// keyed by the graph node-id so the open/closed state survives layout
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
    // Threaded so the panel can render the "Resolved via" provenance
    // section. Unset on recursive structural sub-panels — the section
    // shows once, on the arg's own top-level panel.
    arg,
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
  if (!arg?.type) return null;
  return String(arg.type).replace(/^:/, '');
}

// Inspect the binding behind an arg row to see whether the slot's
// declared type was narrowed at this binding. Two ways narrowing can
// happen, both flagged the same way to a reader:
//
//   1. EXPLICIT — the binding carries `:type-override-fn-id` that
//      differs from the slot's own `:type-fn-id`. Author wrote
//      `{:ref X :type T}` or `{:type T}` in fns.edn and the parser
//      persisted the override.
//   2. REF-RETURN — the binding is a ref-binding (no override), but
//      the bound fn declares a `:return-type-fn-id` more specific
//      than the slot's `:type-fn-id`. The type-checker accepts it
//      under subtype rules; the chip surfaces the ref's return-type;
//      the badge attributes the narrowing to the ref.
//
// Returns {narrowed: true, kind, sourceFnName, baseTypeName} or null.
function getTypeNarrowingInfo(arg) {
  if (!arg || !lookups) return null;
  const binding = arg['binding-id']
    ? lookups.bindingMap?.get(arg['binding-id']) : null;
  if (!binding) return null;
  const slot = arg['slot-id'] ? lookups.slotMap?.get(arg['slot-id']) : null;
  const slotTypeId = slot?.['type-fn-id'];
  const baseTypeFn = slotTypeId ? lookups.fnMap?.get(slotTypeId) : null;

  // 1. Explicit type-override.
  const overrideId = binding['type-override-fn-id'];
  if (overrideId && overrideId !== slotTypeId) {
    const ownerFn = binding['fn-id'] ? lookups.fnMap?.get(binding['fn-id']) : null;
    return {
      narrowed: true,
      kind: 'override',
      sourceFnName: ownerFn?.name || '(anonymous)',
      baseTypeName: baseTypeFn?.name || null,
    };
  }

  // 2. Ref-binding whose return-type is more specific than the slot.
  const refFnId = binding['ref-fn-id'];
  if (refFnId && !overrideId) {
    const refFn = lookups.fnMap?.get(refFnId);
    const refRetId = refFn?.['return-type-fn-id'];
    if (refRetId && slotTypeId && refRetId !== slotTypeId) {
      return {
        narrowed: true,
        kind: 'ref-return',
        sourceFnName: refFn?.name || '(anonymous)',
        baseTypeName: baseTypeFn?.name || null,
      };
    }
  }

  // 3. Transitive: ancestor in the inheritance chain narrowed via a
  //    type-override, and this descendant didn't re-narrow. The chip
  //    still shows the narrowed type (resolved via expectedSlotType's
  //    priority-2 backward-unification or priority-3 ref-return path)
  //    but the binding-LEVEL narrowing source is an ancestor, not the
  //    immediate row. Surfaces "inherited from <ancestor>" so a reader
  //    can trace the constraint without opening the inline-expand
  //    panel's "Resolved via" chain.
  if (typeof findBindingOverrideChain === 'function' && arg['fn-id'] && arg['slot-id']) {
    const chain = findBindingOverrideChain(arg['fn-id'], arg['slot-id']);
    const inherited = chain.find((entry) => entry.fnId !== arg['fn-id']);
    if (inherited && inherited.overrideFnId !== slotTypeId) {
      return {
        narrowed: true,
        kind: 'inherited',
        sourceFnName: inherited.fnName || '(anonymous)',
        baseTypeName: baseTypeFn?.name || null,
      };
    }
  }
  return null;
}


// Small `↳` badge appended after a type-chip when the slot's type was
// narrowed at this binding. Answers "where did this constraint come
// from?" — hover shows the immediate narrowing source as a tooltip;
// click opens the provenance popover with the FULL narrowing chain
// (every ancestor that contributed an override + the 4-tier resolution
// + clickable navigation to each source fn).
function createProvenanceBadge(narrowingInfo, arg) {
  if (!narrowingInfo?.narrowed) return null;
  const badge = document.createElement('button');
  badge.type = 'button';
  badge.className = 'arg-type-provenance';
  badge.textContent = '↳';
  const baseHint = narrowingInfo.baseTypeName
    ? ' (narrowed from :' + narrowingInfo.baseTypeName + ')'
    : '';
  // The verb differs by narrowing kind: an explicit override is
  // "narrowed AT" the fn that pinned it; a ref-binding is "narrowed BY"
  // the fn whose typed return surfaced through; an inherited override
  // is "inherited from" the ancestor that holds it. All three attribute
  // the source by name.
  const verb = narrowingInfo.kind === 'ref-return'
    ? 'Narrowed by ref :'
    : narrowingInfo.kind === 'inherited'
      ? 'Inherited narrowing from :'
      : 'Narrowed at :';
  badge.title = verb + narrowingInfo.sourceFnName + baseHint
              + ' — click for full chain';
  badge.setAttribute('aria-label', badge.title);
  // Disclosure button — the provenance popover is anchored to and
  // controlled by this trigger. Normally born closed and flipped by
  // editor-provenance-popover.js — but an overlay rebuild can recreate
  // this badge WHILE its popover is open, so ask the singleton: a
  // fresh node for an open popover must be born "true" or the
  // disclosure state desyncs until the next toggle.
  const bornOpen = typeof isProvenanceOpenFor === 'function'
    && arg?.['binding-id']
    && isProvenanceOpenFor(arg['binding-id'], arg['item-id'] || null);
  badge.setAttribute('aria-expanded', bornOpen ? 'true' : 'false');
  badge.setAttribute('aria-haspopup', 'dialog');
  // Stable identifier so `attachAndShow` can re-locate the current DOM
  // badge after the async fetch — overlays can rebuild during the
  // fetch, replacing the originally-clicked badge with an equivalent
  // one carrying the same binding-id.
  if (arg?.['binding-id']) {
    badge.setAttribute('data-binding-id', arg['binding-id']);
  }
  if (arg?.['item-id']) {
    badge.setAttribute('data-item-id', arg['item-id']);
  }
  badge.addEventListener('click', (e) => {
    e.preventDefault();
    e.stopPropagation();
    if (typeof showProvenancePopover === 'function' && arg) {
      showProvenancePopover(arg, badge);
    }
  });
  return badge;
}


// Compact button styled like the description-i and ↗ glyphs but
// wider (text label fits "timestamptz" at ~9px). Click →
// enterArgTypeEditMode when editable; pure label when readOnly.
//
// Refinements render as a stacked two-line chip — base type on top,
// constraint below at smaller weight — so the visual answers "this is
// a SUBTYPE of <base>, narrowed by <constraint>" without forcing the
// user to read the constraint syntax `int (> 0)` as a single phrase.
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
  // Refinement stacking — two paths reach here:
  //   1. richType is the structural form ['refine', base, constraint]
  //      (anonymous refinement on the slot, no alias).
  //   2. richType is a string alias ('positive-int', 'non-negative-int')
  //      whose rich-types entry has a refine-shaped :return. Looking
  //      up the alias gives us the constraint to surface; the chip's
  //      top line stays the alias name (more informative than the
  //      base type).
  const refineStruct = Array.isArray(richType) && richType[0] === 'refine'
    ? richType
    : resolveRefinementAlias(richType);
  const refineConstraint = refinementConstraintText(refineStruct);
  if (refineConstraint) {
    chip.classList.add('arg-type-chip-refine');
    const base = document.createElement('span');
    base.className = 'arg-type-chip-refine-base';
    base.textContent = display;
    chip.appendChild(base);
    const constraint = document.createElement('span');
    constraint.className = 'arg-type-chip-refine-constraint';
    constraint.textContent = refineConstraint;
    // Hover-title on the constraint span — natural-language form of
    // the refinement (e.g. "positive integer" / "integer where >= 1024
    // and <= 65535"). Lets the reader translate `(≥ 1024) (≤ 65535)`
    // without leaving the chip. The chip's outer title still carries
    // the compact rich-type form (`:int (> 0)`) for the rest of the
    // hover surface.
    if (refineStruct && typeof formatTypeHumanReadable === 'function') {
      constraint.title = formatTypeHumanReadable(refineStruct);
    }
    chip.appendChild(constraint);
  } else {
    chip.textContent = display;
  }
  // Always surface the FULL type on hover — `compactTypeChipText`
  // truncates fn-shape types aggressively (`(request)→ring-res…`),
  // and before this fix there was no way to discover what got cut.
  // `formatTypeHumanReadable` spells out the rich structure verbosely
  // (`(request: ring-request-shape) → ring-response-shape`); falls
  // back to the flat type name when no rich type is available.
  if (!chip.title) {
    const tip = (richType && typeof formatTypeHumanReadable === 'function')
      ? formatTypeHumanReadable(richType)
      : flatType;
    if (tip && tip !== display) chip.title = tip;
  }
  // Inline subtype-of line for binding-level narrowing — when the
  // slot's declared base differs from what's displayed (because an
  // override or an inherited binding narrowed it), append a small
  // `< :base` line so the relationship is visible without opening
  // the inline-expand panel or hovering the `↳` badge. Skipped for
  // refinement-stacked chips (the refinement chain panel already
  // exposes the base via `:positive-int ⊂ :int`), for chips where
  // the base equals the displayed text, and when the narrowing
  // info isn't available (read-only arg-overlay without binding).
  if (!refineConstraint && typeof getTypeNarrowingInfo === 'function') {
    const narrow = getTypeNarrowingInfo(arg);
    if (narrow?.baseTypeName && narrow.baseTypeName !== display) {
      chip.classList.add('arg-type-chip-narrowed');
      const subOf = document.createElement('span');
      subOf.className = 'arg-type-chip-narrowed-base';
      subOf.textContent = '< :' + narrow.baseTypeName;
      // Verb mirrors the `↳` badge tooltip so the inline line and the
      // badge tell the same story — useful when only one is visible at
      // a given zoom (overlay clip on narrow cards).
      const verb = narrow.kind === 'ref-return'
        ? 'narrowed by ref :'
        : narrow.kind === 'inherited'
          ? 'inherited narrowing from :'
          : 'narrowed at :';
      subOf.title = 'subtype of :' + narrow.baseTypeName
                  + ' — ' + verb + (narrow.sourceFnName || '(anonymous)');
      chip.appendChild(subOf);
    }
  }
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

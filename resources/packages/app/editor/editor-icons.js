// Editor Icons - Right-edge action icons (i and ↗) for ancestor rows.
// Depends on: editor-tooltips.js, editor-data.js (getQualifiedFnName).

// Shared box styling for the right-edge action icons (description ⓘ
// and open-in-new-tab ↗). A 1-px border around each glyph turns them
// into clearly-clickable buttons instead of bare characters that the
// user can easily miss when aiming.
//
// Size + font-size flow through CSS vars (--icon-size,
// --icon-font-size) so the touch media query can grow the glyphs
// without touching this JS. See the @media (hover:none) block at
// the end of editor-styles.css.
function applyActionIconBox(el) {
  el.style.display = 'inline-flex';
  el.style.alignItems = 'center';
  el.style.justifyContent = 'center';
  el.style.boxSizing = 'border-box';
  el.style.width = 'var(--icon-size)';
  el.style.height = 'var(--icon-size)';
  el.style.lineHeight = '1';
  el.style.border = '1px solid currentColor';
  el.style.borderRadius = '3px';
  el.style.fontSize = 'var(--icon-font-size)';
}

function createDescriptionBadge(description, opts) {
  opts = opts || {};
  const badge = document.createElement('span');
  badge.className = 'description-badge';
  badge.textContent = 'i';
  // Inherit color from the parent row — default text is dark → black
  // badge, root-block rows that flip to ROOT_FG (white) get a white
  // badge automatically without per-site logic.
  badge.style.color = 'currentColor';
  badge.style.cursor = 'help';
  badge.style.fontWeight = 'normal';
  badge.style.fontStyle = 'italic';
  // opacity comes from CSS so the :hover override can boost it to 1.
  applyActionIconBox(badge);
  // Override pointer-events: some overlay containers opt out (e.g.
  // the floating column-below-MI text overlay) but we need the
  // hover events here.
  badge.style.pointerEvents = 'auto';
  if (opts.pinRight) {
    // Pinned to the right edge so the centered fn name stays centered
    // and the badge never overlaps the click-target text.
    // Parent must be position:relative.
    badge.style.position = 'absolute';
    badge.style.right = 'var(--icon-pin-r-1)';
    badge.style.top = '50%';
    badge.style.transform = 'translateY(-50%)';
  } else {
    badge.style.marginLeft = '4px';
    badge.style.verticalAlign = 'middle';
  }
  // Tooltip payload — full name (bold), namespace (italic dim),
  // description, plus entity (type, id) so the tooltip can offer an
  // Edit affordance when pinned. The badge ALWAYS renders now (even
  // for empty descriptions), so users have a click-target to ADD a
  // description to entities that don't have one yet.
  const tooltipContent = {
    name: opts.name || null,
    namespace: opts.namespace || null,
    description: description || '',
    entityType: opts.entityType || null,
    entityId: opts.entityId || null
  };
  ensureDescriptionTooltipDismissHandler();
  badge.addEventListener('mouseenter', (e) => {
    // Hover on the badge must NOT trigger a row-level expansion preview.
    // The parent's mouseenter still fires on first entry — the optional
    // onEnter callback is the row's preview-clear, undoing that preview.
    if (opts.onEnter) opts.onEnter();
    hideFullNameTooltip();
    showDescriptionTooltip(tooltipContent, e);
  });
  badge.addEventListener('mousemove', (e) => {
    // mousemove bubbles, so without stopPropagation the row's mousemove
    // would re-fire its preview while the cursor sits on the badge.
    e.stopPropagation();
    showDescriptionTooltip(tooltipContent, e);
  });
  badge.addEventListener('mouseleave', () => hideDescriptionTooltip());
  // Click on the badge must not commit an expansion either — swallow
  // mousedown/touchend so the row's click handler doesn't fire.
  badge.addEventListener('mousedown', (e) => e.stopPropagation());
  badge.addEventListener('touchend', (e) => e.stopPropagation());
  // Click toggles "sticky" display so touch users can read the tooltip
  // after their finger leaves. On desktop this is a no-op for hovering
  // users (tooltip is already visible from mouseenter); a deliberate
  // click pins it. The document-level click handler dismisses it on
  // tap-elsewhere.
  badge.addEventListener('click', (e) => {
    e.stopPropagation();
    e.preventDefault();
    descriptionTooltipSticky = !descriptionTooltipSticky;
    if (descriptionTooltipSticky) {
      if (opts.onEnter) opts.onEnter();
      hideFullNameTooltip();
      showDescriptionTooltip(tooltipContent, e);
    } else {
      hideDescriptionTooltip(true);
    }
  });
  return badge;
}

// "Open in new tab" link for an ancestor row. Returns null when the fn
// has no globally-resolvable name (anonymous local fns can't be linked
// to via the URL hash). Pinned to the right edge alongside the
// description badge.
function createOpenInNewTabButton(fn, opts) {
  if (!fn) return null;
  const qualified = getQualifiedFnName(fn);
  if (!qualified || qualified === '(anonymous)') return null;
  opts = opts || {};
  const link = document.createElement('a');
  link.className = 'open-in-new-tab';
  link.href = '#' + encodeURIComponent(qualified);
  link.target = '_blank';
  link.rel = 'noopener';
  link.title = 'Open ' + qualified + ' in a new tab';
  link.textContent = '↗';
  link.style.color = 'currentColor';
  link.style.cursor = 'pointer';
  link.style.fontWeight = 'normal';
  link.style.textDecoration = 'none';
  // opacity comes from CSS so the :hover override can boost it to 1.
  applyActionIconBox(link);
  // ↗ glyph reads better a hair larger than the i; we bump font-size
  // by a fixed delta from the shared --icon-font-size so touch and
  // desktop both inherit the proportional bump.
  link.style.fontSize = 'calc(var(--icon-font-size) + 1px)';
  link.style.pointerEvents = 'auto';
  if (opts.pinRight) {
    // Sits just left of the description badge.
    link.style.position = 'absolute';
    link.style.right = 'var(--icon-pin-r-2)';
    link.style.top = '50%';
    link.style.transform = 'translateY(-50%)';
  } else {
    link.style.marginLeft = '4px';
    link.style.verticalAlign = 'middle';
  }
  link.addEventListener('mouseenter', () => {
    if (opts.onEnter) opts.onEnter();
    hideFullNameTooltip();
  });
  link.addEventListener('mousemove', (e) => { e.stopPropagation(); });
  // Anchor handles navigation natively; just make sure mousedown/touchend
  // don't bubble into the row's expansion handler.
  link.addEventListener('mousedown', (e) => e.stopPropagation());
  link.addEventListener('touchend', (e) => e.stopPropagation());
  return link;
}

// Edit pencil button — right-pinned, shape-matches the description-i
// badge and open-in-new-tab ↗. Used on the root fn-card row to open
// fn-rename inline. Caller supplies an `onClick(anchor)` callback;
// the button itself is a plain element (no nav like ↗).
function createEditPencilButton(opts) {
  opts = opts || {};
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'edit-pencil';
  btn.title = 'Edit';
  btn.textContent = '✎';
  btn.style.color = 'currentColor';
  btn.style.background = 'transparent';
  btn.style.cursor = 'pointer';
  btn.style.fontWeight = 'normal';
  applyActionIconBox(btn);
  btn.style.fontSize = 'calc(var(--icon-font-size) + 1px)';
  btn.style.padding = '0';
  btn.style.pointerEvents = 'auto';
  if (opts.pinRight) {
    btn.style.position = 'absolute';
    // Default slot is one in from the right edge (where the
    // description badge sits). Caller passes `pinSlot: 3` to stack
    // next to a ↗ that already occupies slot 2; the legacy
    // `rightPx` override remains supported for callers that pass
    // a literal '42px' / '60px' value.
    if (opts.rightPx) {
      btn.style.right = opts.rightPx;
    } else if (opts.pinSlot === 3) {
      btn.style.right = 'var(--icon-pin-r-3)';
    } else {
      btn.style.right = 'var(--icon-pin-r-2)';
    }
    btn.style.top = '50%';
    btn.style.transform = 'translateY(-50%)';
  } else {
    btn.style.marginLeft = '4px';
    btn.style.verticalAlign = 'middle';
  }
  btn.addEventListener('mouseenter', () => {
    if (opts.onEnter) opts.onEnter();
    hideFullNameTooltip();
  });
  btn.addEventListener('mousemove', (e) => e.stopPropagation());
  btn.addEventListener('mousedown', (e) => e.stopPropagation());
  btn.addEventListener('touchend', (e) => e.stopPropagation());
  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    e.preventDefault();
    if (opts.onClick) opts.onClick(btn);
  });
  return btn;
}

// Generic right-pinned glyph button. Same chrome as the description
// `i` / open-in-new ↗ / edit ✎ icons, with a free-form glyph + slot
// number. Used by the root fn-name row to pin Extend (+) and Delete
// (🗑) where the prominent fn-action toolbar used to live — the
// toolbar made rare actions read like primary CTAs, the icons make
// them read like every other right-edge affordance on the card.
function createPinnedIconButton(opts) {
  opts = opts || {};
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'pinned-icon-btn' + (opts.danger ? ' pinned-icon-btn-danger' : '');
  if (opts.title) {
    btn.title = opts.title;
    btn.setAttribute('aria-label', opts.title);
  }
  btn.textContent = opts.glyph || '';
  btn.style.color = 'currentColor';
  btn.style.background = 'transparent';
  btn.style.cursor = 'pointer';
  btn.style.fontWeight = 'normal';
  applyActionIconBox(btn);
  btn.style.padding = '0';
  btn.style.pointerEvents = 'auto';
  btn.style.position = 'absolute';
  btn.style.top = '50%';
  btn.style.transform = 'translateY(-50%)';
  // Slot 1 = closest to the right edge. Higher numbers stack
  // leftward on 18px steps. Touch overrides live in CSS.
  const slot = opts.pinSlot || 1;
  btn.style.right = 'var(--icon-pin-r-' + slot + ')';
  btn.addEventListener('mouseenter', () => {
    if (opts.onEnter) opts.onEnter();
    hideFullNameTooltip();
  });
  btn.addEventListener('mousemove', (e) => e.stopPropagation());
  btn.addEventListener('mousedown', (e) => e.stopPropagation());
  btn.addEventListener('touchend', (e) => e.stopPropagation());
  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    e.preventDefault();
    if (opts.onClick) opts.onClick(btn);
  });
  return btn;
}

// Left-pinned namespace badge for the root fn-name row. Reads "ns",
// hover-title shows the qualified path (e.g. `app.server`), click
// opens the namespace-picker (or runs whatever the caller wires up).
// Mirrors the right-edge i / ✎ chrome — same 1px frame, same size —
// so the row's left and right edges read as a balanced pair instead
// of a single right-stuffed cluster. Pinned absolute so the
// centred name doesn't shift.
function createNamespaceBadge(nsPath, opts) {
  opts = opts || {};
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'namespace-badge';
  btn.textContent = 'ns';
  btn.style.color = 'currentColor';
  btn.style.background = 'transparent';
  btn.style.cursor = opts.onClick ? 'pointer' : 'help';
  btn.style.fontWeight = 'normal';
  btn.style.fontStyle = 'italic';
  applyActionIconBox(btn);
  // 'ns' is two chars in the same 15×15 frame, so a hair smaller than
  // the single-char glyphs reads better.
  btn.style.fontSize = 'calc(var(--icon-font-size) - 1px)';
  btn.style.padding = '0';
  btn.style.pointerEvents = 'auto';
  btn.style.position = 'absolute';
  btn.style.left = 'var(--icon-pin-r-1)';
  btn.style.top = '50%';
  btn.style.transform = 'translateY(-50%)';
  // The visible tooltip is the bare path — the `ns` glyph itself
  // already says "this is a namespace", and `showFullNameTooltip`
  // appears instantly (vs. the OS-level ~1 s delay on `title=`).
  // aria-label keeps the long form for screen readers.
  const path = nsPath || '(root)';
  const editable = !!opts.onClick;
  btn.setAttribute('aria-label',
    'Namespace: ' + path + (editable ? ' — click to change' : ''));
  btn.addEventListener('mouseenter', () => {
    if (opts.onEnter) opts.onEnter();
    hideDescriptionTooltip();
    showFullNameTooltip(path, btn);
    // Affordance hint — flip the glyph to ✎ so users can see at a
    // glance that clicking will let them edit. Read-only badges
    // (no onClick) keep `ns` because there's nothing to click.
    if (editable) btn.textContent = '✎';
  });
  btn.addEventListener('mouseleave', () => {
    hideFullNameTooltip();
    if (editable) btn.textContent = 'ns';
  });
  btn.addEventListener('mousemove', (e) => e.stopPropagation());
  btn.addEventListener('mousedown', (e) => e.stopPropagation());
  btn.addEventListener('touchend', (e) => e.stopPropagation());
  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    e.preventDefault();
    if (opts.onClick) opts.onClick(btn);
  });
  return btn;
}

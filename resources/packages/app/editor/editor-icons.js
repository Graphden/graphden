// Editor Icons - Right-edge action icons (i and ↗) for ancestor rows.
// Depends on: editor-tooltips.js, editor-data.js (getQualifiedFnName).

// Shared box styling for the right-edge action icons (description ⓘ
// and open-in-new-tab ↗). A 1-px border around each glyph turns them
// into clearly-clickable buttons instead of bare characters that the
// user can easily miss when aiming.
function applyActionIconBox(el) {
  el.style.display = 'inline-flex';
  el.style.alignItems = 'center';
  el.style.justifyContent = 'center';
  el.style.boxSizing = 'border-box';
  el.style.width = '15px';
  el.style.height = '15px';
  el.style.lineHeight = '1';
  el.style.border = '1px solid currentColor';
  el.style.borderRadius = '3px';
  el.style.fontSize = '10px';
}

function createDescriptionBadge(description, opts) {
  if (!description) return null;
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
  badge.style.opacity = '0.85';
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
    badge.style.right = '6px';
    badge.style.top = '50%';
    badge.style.transform = 'translateY(-50%)';
  } else {
    badge.style.marginLeft = '4px';
    badge.style.verticalAlign = 'middle';
  }
  // Tooltip payload — full name (bold), namespace (italic dim), description.
  // The full name lets touch users read truncated names from the i-tooltip
  // since on iPad there's no row-hover.
  const tooltipContent = {
    name: opts.name || null,
    namespace: opts.namespace || null,
    description
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
  link.style.opacity = '0.85';
  applyActionIconBox(link);
  link.style.fontSize = '11px';  // ↗ glyph reads better a hair larger
  link.style.pointerEvents = 'auto';
  if (opts.pinRight) {
    // Sits just left of the description badge (which is at right:6px).
    link.style.position = 'absolute';
    link.style.right = '24px';
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

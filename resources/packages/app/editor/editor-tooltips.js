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

function ensureDescriptionTooltip() {
  if (descriptionTooltipEl) return descriptionTooltipEl;
  const el = document.createElement('div');
  el.className = 'description-tooltip';
  Object.assign(el.style, {
    position: 'fixed',
    zIndex: '10000',
    background: 'rgba(0,0,0,0.88)',
    color: '#fff',
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
// browser's emulated mouse events).
let descriptionTooltipSticky = false;

function showDescriptionTooltip(content, evt) {
  const el = ensureDescriptionTooltip();
  el.textContent = '';
  // Accept either a plain string (legacy callers) or
  // {name, namespace, description}.
  const isObj = content && typeof content === 'object';
  const name = isObj ? content.name : null;
  const ns = isObj ? content.namespace : null;
  const text = isObj ? content.description : content;
  if (name) {
    const nameRow = document.createElement('div');
    nameRow.textContent = name;
    nameRow.style.fontWeight = '600';
    nameRow.style.fontSize = '13px';
    nameRow.style.marginBottom = (ns || text) ? '4px' : '0';
    el.appendChild(nameRow);
  }
  if (ns) {
    const nsRow = document.createElement('div');
    nsRow.textContent = ns;
    nsRow.style.fontStyle = 'italic';
    nsRow.style.opacity = '0.7';
    nsRow.style.fontSize = '11px';
    nsRow.style.marginBottom = text ? '4px' : '0';
    el.appendChild(nsRow);
  }
  if (text) {
    const body = document.createElement('div');
    body.textContent = text;
    el.appendChild(body);
  }
  el.style.display = 'block';
  // Position next to the cursor; clamp to viewport.
  const margin = 12;
  const x = Math.min(evt.clientX + margin, window.innerWidth - el.offsetWidth - margin);
  const y = Math.min(evt.clientY + margin, window.innerHeight - el.offsetHeight - margin);
  el.style.left = x + 'px';
  el.style.top = y + 'px';
}

function hideDescriptionTooltip(force) {
  if (descriptionTooltipSticky && !force) return;
  if (descriptionTooltipEl) descriptionTooltipEl.style.display = 'none';
}

// Document-level click closes any sticky tooltip. Installed once on
// first use (idempotent guard via the function itself).
function ensureDescriptionTooltipDismissHandler() {
  if (ensureDescriptionTooltipDismissHandler._installed) return;
  ensureDescriptionTooltipDismissHandler._installed = true;
  document.addEventListener('click', (e) => {
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
    background: '#ffffff',
    color: '#000000',
    border: '1px solid #ccc',
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

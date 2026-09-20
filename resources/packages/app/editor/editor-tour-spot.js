// editor-tour-spot.js — where the spotlight and the step popover GO.
//
// The geometry. `_tourEnsureEls`
// builds the three overlay elements once (`#gd-tour-dim` scrim at z-index 290,
// `#gd-tour-spot` ring at 10050, the popover); `_tourSpotRect` / `_tourDimHoles`
// cut the target out of the scrim; `_tourPosition` places the popover clear of
// the PANEL its target lives in (not merely of the target — 35 steps anchor
// into the Explorer, and 16px right of a 250px filter box is over the rows the
// step says to click), docking to the bottom edge as a sheet under 700px
// (`_tourNarrow`, `_tourReserveForSheet`); `_tourPickSpot` + the `*Rects`
// helpers find the free spot with the least overlap over canvas nodes, the
// entity list and floating chrome. Reads `_tourEls` / `_tourState` from
// editor-tour.js at call time only — no load-time code here.

// The spotlight is TWO stacked elements: `dim` is a full-viewport SVG scrim
// whose mask punches out one hole PER bright region — a multi-action step
// ("click ⋯, then Extend, then name it") involves several elements at once,
// and the old single box-shadow hole left every element but the current
// stage in the dark. It sits BELOW every transient surface (a menu the step
// opens must float bright over it); `spot` carries the accent ring and sits
// ABOVE them (a `:targets` chain rings items INSIDE an open menu, and a
// ring under the menu's own panel would be invisible).
const TOUR_SVG_NS = 'http://www.w3.org/2000/svg';
function _tourEnsureEls() {
  if (_tourEls) return _tourEls;
  const dim = document.createElementNS(TOUR_SVG_NS, 'svg');
  dim.id = 'gd-tour-dim';
  dim.setAttribute('aria-hidden', 'true');
  // Luminance mask: white keeps the scrim, black cuts a hole. Literal
  // white/black here are mask coordinates, not theme colors — the visible
  // scrim color is the CSS-tokened fill on .gd-tour-dim-fill.
  const defs = document.createElementNS(TOUR_SVG_NS, 'defs');
  const mask = document.createElementNS(TOUR_SVG_NS, 'mask');
  mask.id = 'gd-tour-dim-mask';
  const keep = document.createElementNS(TOUR_SVG_NS, 'rect');
  keep.setAttribute('width', '100%');
  keep.setAttribute('height', '100%');
  keep.setAttribute('fill', '#fff');
  mask.appendChild(keep);
  defs.appendChild(mask);
  const fill = document.createElementNS(TOUR_SVG_NS, 'rect');
  fill.setAttribute('width', '100%');
  fill.setAttribute('height', '100%');
  fill.setAttribute('class', 'gd-tour-dim-fill');
  fill.setAttribute('mask', 'url(#gd-tour-dim-mask)');
  dim.appendChild(defs);
  dim.appendChild(fill);
  const spot = document.createElement('div');
  spot.id = 'gd-tour-spot';
  spot.setAttribute('aria-hidden', 'true');
  const pop = document.createElement('div');
  pop.id = 'gd-tour-pop';
  pop.setAttribute('role', 'dialog');
  pop.setAttribute('aria-modal', 'false');
  pop.setAttribute('aria-label', 'Interactive tutorial');
  document.body.appendChild(dim);
  document.body.appendChild(spot);
  document.body.appendChild(pop);
  _tourEls = { dim, dimMask: mask, spot, pop };
  return _tourEls;
}

// The accent ring follows the CURRENT stage only; the scrim's holes
// (_tourDimHoles) keep every other element of the step readable.
function _tourSpotRect(left, top, width, height) {
  const el = _tourEls.spot;
  el.style.left = left + 'px';
  el.style.top = top + 'px';
  el.style.width = width + 'px';
  el.style.height = height + 'px';
  el.classList.add('gd-tour-visible');
}

// Rebuild the mask's hole rects. Overlapping holes are fine — black over
// black — which is exactly what an evenodd path could not do.
function _tourDimHoles(rects) {
  const { dim, dimMask } = _tourEls;
  while (dimMask.children.length > 1) dimMask.lastChild.remove();
  for (const r of rects) {
    const hole = document.createElementNS(TOUR_SVG_NS, 'rect');
    hole.setAttribute('x', Math.round(r.left));
    hole.setAttribute('y', Math.round(r.top));
    hole.setAttribute('width', Math.max(0, Math.round(r.width)));
    hole.setAttribute('height', Math.max(0, Math.round(r.height)));
    hole.setAttribute('rx', 8);
    hole.setAttribute('fill', '#000');
    dimMask.appendChild(hole);
  }
  dim.classList.add('gd-tour-visible');
}

function _tourSpotHide() {
  _tourEls.spot.classList.remove('gd-tour-visible');
  _tourEls.dim.classList.remove('gd-tour-visible');
}

// A phone has no room BESIDE anything. The 360px popover on a 390px screen
// lands on top of the panel the step is pointing at, and the reader cannot
// reach the control the text just named — lesson 01 dead-ends at "click +",
// because + is under the popover. Below this width the popover docks to the
// bottom edge as a sheet (CSS owns that geometry) and the spotlight keeps
// pointing at the target above it.
function _tourNarrow() {
  return window.innerWidth <= 700;
}

// The sheet covers the bottom of a fixed-height scroll panel, and a row under
// it cannot be scrolled up because the panel has nothing below to scroll to.
// Reserve the sheet's height at the bottom of the panels a lesson points at,
// so `scrollIntoView` has somewhere to go. Reset to 0 when the sheet is gone.
function _tourReserveForSheet(px) {
  const root = document.documentElement;
  if (px > 0) {
    root.style.setProperty('--gd-tour-sheet-h', px + 'px');
    document.body.classList.add('gd-tour-sheet-open');
  } else {
    root.style.removeProperty('--gd-tour-sheet-h');
    document.body.classList.remove('gd-tour-sheet-open');
  }
}

// Is the target hidden UNDER the sheet? On a phone that is as unreachable as
// below the fold, and the fix is the same one: scroll it into view.
function _tourUnderSheet(selector) {
  const el = selector ? document.querySelector(selector) : null;
  if (!el || !_tourEls?.pop.classList.contains('gd-tour-visible')) return false;
  const r = el.getBoundingClientRect();
  const sheet = _tourEls.pop.getBoundingClientRect();
  return r.bottom > sheet.top && r.top < sheet.bottom
    && r.right > sheet.left && r.left < sheet.right;
}

// The panel a target sits in — the region the popover must not cover, since
// the reader has to keep using it. Today: the Explorer sidebar, the
// operations surface, and the right inspector (whose Runs tab hosts the run
// form + result the step is about); each is a scrolling region whose
// contents ARE the step's subject. Returns a DOMRect or null.
function _tourPanelOf(target) {
  if (!target) return null;
  const panel = target.closest('#side-menu, #gd-operate-panels, #gd-shell-surface, #gd-inspector');
  if (!panel) return null;
  const r = panel.getBoundingClientRect();
  // Only worth avoiding if it is actually a panel-sized region on screen.
  return (r.width > 160 && r.height > 160) ? r : null;
}


function _tourPosition() {
  if (!_tourEls || !_tourState) return;
  const step = _tourStep();
  const { pop } = _tourEls;
  const narrow = _tourNarrow();
  pop.classList.toggle('gd-tour-sheet', narrow);
  _tourReserveForSheet(narrow && pop.classList.contains('gd-tour-visible')
                       ? pop.offsetHeight : 0);
  const effSel = _tourEffTarget(step);
  const target = effSel ? document.querySelector(effSel) : null;
  const rect = target ? target.getBoundingClientRect() : null;
  const visible = rect && rect.width > 0 && rect.height > 0
    && rect.bottom > 0 && rect.top < window.innerHeight;
  // Second half of the search-step fix (see _tourWantedRowSel): while the
  // ring still sits on the filter input — the wanted row not rendered yet —
  // the lit hole covers the input TOGETHER with the result list below it.
  // The step's next instruction is to read that list; a scrim over it made
  // the reader search in the dark. The popover keeps avoiding the whole
  // panel either way (_tourPanelOf), so nothing else moves.
  const spotRect = (visible && target.id === 'search-input')
    ? _tourWithEntityList(rect) : rect;

  if (visible && narrow) {
    // Spotlight still anchors; the sheet's own geometry is in the stylesheet.
    const pad = 6;
    _tourSpotRect(spotRect.left - pad, spotRect.top - pad,
                  spotRect.width + pad * 2, spotRect.height + pad * 2);
    _tourDimHoles(_tourHoleRects(step, effSel));
    pop.classList.remove('gd-tour-centered');
    pop.style.left = '';
    pop.style.top = '';
  } else if (visible) {
    const pad = 6;
    _tourSpotRect(spotRect.left - pad, spotRect.top - pad,
                  spotRect.width + pad * 2, spotRect.height + pad * 2);
    _tourDimHoles(_tourHoleRects(step, effSel));

    const pw = pop.offsetWidth || 360;
    const ph = pop.offsetHeight || 180;
    // Clear of the PANEL the target lives in, not merely of the target. A
    // step that rings the Explorer's filter box put the popover 16px to the
    // right of a ~250px-wide input — i.e. straight over the list of results
    // the same step tells the reader to click. Thirty-five steps across the
    // tutorial anchor into that sidebar, and every guard walked them anyway
    // because a guard clicks by selector; a person cannot. Where there is
    // room, start after the panel.
    const panel = _tourPanelOf(target);
    // Candidate positions, best first: past the panel/target (AFTER a
    // left-side panel like the Explorer, BEFORE a right-side one like
    // the inspector — the old right-only rule clamped candidates back
    // ONTO the inspector when a step targeted the Run pane), below,
    // above, left, then the bottom corners as last resorts. Each is
    // scored against the target, every visible floating surface
    // (menus, popovers a step just told the reader to open), the
    // canvas cards AND the panel itself — the winner is the first
    // that covers nothing, else the least-covering one. Re-run every
    // tick, so a menu opening mid-step pushes the popover away within
    // ~600ms.
    const panelOnRight = panel && panel.left > window.innerWidth / 2;
    const primary = panelOnRight
      ? { left: panel.left - pw - 16, top: spotRect.top }
      : { left: Math.max(spotRect.right + 16, panel ? panel.right + 16 : 0),
          top: spotRect.top };
    const cands = [
      primary,
      { left: spotRect.left, top: spotRect.bottom + 14 },
      { left: spotRect.left, top: spotRect.top - ph - 14 },
      { left: spotRect.left - pw - 16, top: spotRect.top },
      { left: window.innerWidth - pw - 12, top: window.innerHeight - ph - 12 },
      { left: 12, top: window.innerHeight - ph - 12 },
    ];
    const avoid = _tourFloatingRects().concat(_tourNodeRects());
    if (panel) avoid.push(panel);
    const best = _tourPickSpot(cands, pw, ph, spotRect, avoid);
    pop.style.left = best.left + 'px';
    pop.style.top = best.top + 'px';
    pop.classList.remove('gd-tour-centered');
  } else if (step?.target) {
    // The step names a target that is not on screen (not rendered yet, or
    // scrolled away). A CENTERED modal here sat exactly on top of the
    // canvas area the step talks about — dock to a corner instead, scored
    // against the open floating surfaces, and keep re-checking each tick
    // until the target appears.
    _tourSpotHide();
    const pw = pop.offsetWidth || 360;
    const ph = pop.offsetHeight || 180;
    const cands = [
      { left: window.innerWidth - pw - 12, top: window.innerHeight - ph - 12 },
      { left: window.innerWidth - pw - 12, top: 12 },
      { left: 12, top: window.innerHeight - ph - 12 },
    ];
    const best = _tourPickSpot(cands, pw, ph, null,
                               _tourFloatingRects().concat(_tourNodeRects()));
    pop.style.left = best.left + 'px';
    pop.style.top = best.top + 'px';
    pop.classList.remove('gd-tour-centered');
  } else {
    _tourSpotHide();
    _tourCenterPop(pop);
  }
}

// Centre the popover as a dialog. A STEP places the popover with inline
// `left` / `top`; those must be cleared here, or `.gd-tour-centered`'s
// `left: 50%` loses to the inline value and its `translateX(-50%)` then
// shifts the box half its width from wherever the last step left it —
// the end-of-lesson "Clean up?" card half off the left edge when the last
// step pointed at the Explorer.
function _tourCenterPop(pop) {
  pop.classList.add('gd-tour-centered');
  pop.style.left = '';
  pop.style.top = '';
}

// Every bright region of the current step: each VISIBLE stage of the
// :targets chain, plus :target and the search-upgraded row. A step that
// says "click ⋯, choose Extend, name it" involves them all at once, and
// a single hole over the current stage left the rest — the card being
// extended, the row being named — in the dark. Canvas targets widen to
// their whole neighbourhood (_tourWithCanvasNodes): a lit ⋯ button on a
// blacked-out card is guidance without context.
function _tourHoleRects(step, effSel) {
  const pad = 6;
  const sels = new Set();
  if (step?.target) sels.add(step.target);
  for (const s of (Array.isArray(step?.targets) ? step.targets : [])) sels.add(s);
  if (effSel) sels.add(effSel);
  const rects = [];
  for (const sel of sels) {
    if (!_tourTargetVisible(sel)) continue;
    const el = document.querySelector(sel);
    let r = el.getBoundingClientRect();
    if (el.id === 'search-input') r = _tourWithEntityList(r);
    else if (el.closest('#graph-container')) r = _tourWithCanvasNodes(r);
    else {
      // A control inside a form pane (the Run button in the inspector's
      // Run pane) is unusable with the fields around it blacked out —
      // light the whole pane, not the button.
      const pane = el.closest('.execute-popover');
      if (pane) r = pane.getBoundingClientRect();
    }
    rects.push({ left: r.left - pad, top: r.top - pad,
                 width: r.width + pad * 2, height: r.height + pad * 2 });
  }
  return rects;
}

// A canvas target is one element OF the graph the step narrates — a [[+]]
// placeholder makes no sense with its card and edges blacked out. Extend
// the hole over the bounding box of the on-screen node cards, clamped to
// the canvas container so it never bleeds into the panels.
function _tourWithCanvasNodes(rect) {
  const nodes = _tourNodeRects();
  if (!nodes.length) return rect;
  let left = rect.left;
  let top = rect.top;
  let right = rect.right;
  let bottom = rect.bottom;
  for (const r of nodes) {
    left = Math.min(left, r.left);
    top = Math.min(top, r.top);
    right = Math.max(right, r.right);
    bottom = Math.max(bottom, r.bottom);
  }
  const host = document.getElementById('graph-container');
  if (host) {
    const h = host.getBoundingClientRect();
    left = Math.max(left, h.left);
    top = Math.max(top, h.top);
    right = Math.min(right, h.right);
    bottom = Math.min(bottom, h.bottom);
  }
  return { left, top, right, bottom,
           width: right - left, height: bottom - top };
}

// The filter input's rect extended over the result list under it. Falls back
// to the input alone if the list isn't measurable (collapsed rail mid-toggle).
function _tourWithEntityList(rect) {
  const list = document.getElementById('entity-list');
  const lr = list ? list.getBoundingClientRect() : null;
  if (!lr || lr.width <= 0 || lr.height <= 0) return rect;
  const left = Math.min(rect.left, lr.left);
  const top = Math.min(rect.top, lr.top);
  const right = Math.max(rect.right, lr.right);
  const bottom = Math.max(rect.bottom, lr.bottom);
  return { left, top, right, bottom, width: right - left, height: bottom - top };
}

// Overlap area between a candidate popover box and a DOMRect, with an
// 8px margin around the rect. 0 = clear.
function _tourOverlapArea(left, top, pw, ph, r) {
  const m = 8;
  const w = Math.min(left + pw, r.right + m) - Math.max(left, r.left - m);
  const h = Math.min(top + ph, r.bottom + m) - Math.max(top, r.top - m);
  return (w > 0 && h > 0) ? w * h : 0;
}

// Fixed/absolute, visible, body-level floating UI — the editor's menus and
// popovers. Regions the tour popover must not cover: a step routinely opens
// one ("click ⋯, then ▶ Run") and the reader has to reach it. The tour's own
// elements are excluded; so are full-screen overlays (nothing avoids those).
// Body has a few dozen direct children — a per-tick scan is cheap.
function _tourFloatingRects() {
  const out = [];
  if (!document.body) return out;
  for (const el of document.body.children) {
    if (!(el instanceof HTMLElement)) continue;
    if (_tourEls && (el === _tourEls.pop || el === _tourEls.spot
                     || el === _tourEls.dim)) continue;
    const cs = getComputedStyle(el);
    if (cs.position !== 'fixed' && cs.position !== 'absolute') continue;
    if (cs.display === 'none' || cs.visibility === 'hidden') continue;
    if (!(Number.parseInt(cs.zIndex, 10) >= 300)) continue;
    const r = el.getBoundingClientRect();
    if (r.width < 40 || r.height < 24) continue;
    if (r.width > window.innerWidth * 0.9
        && r.height > window.innerHeight * 0.9) continue;
    out.push(r);
  }
  return out;
}

// Viewport rects of the canvas cards (`.node-overlay` — fn cards, value
// nodes, [[+]] placeholder binders). On canvas lessons these ARE the step's
// subject: without them in the avoid list the popover repeatedly parked on
// the selected fn's card, covering the ⋯ / [[+]] the step asks to press
// (lessons 08/18/30/32 in the walkthrough). Scored SOFT, like
// the floating surfaces — a crowded canvas still yields the least-covering
// corner instead of no position at all.
function _tourNodeRects() {
  const out = [];
  for (const el of document.querySelectorAll('.node-overlay')) {
    const r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) continue;
    if (r.bottom < 0 || r.top > window.innerHeight
        || r.right < 0 || r.left > window.innerWidth) continue;
    out.push(r);
  }
  return out;
}

// Pick the popover spot: clamp each candidate into the viewport, hard-weight
// covering the TARGET, soft-weight covering any floating surface; first
// zero-score candidate wins, else the least-covering one.
function _tourPickSpot(cands, pw, ph, targetRect, avoidRects) {
  let best = null;
  for (const c of cands) {
    const left = Math.min(Math.max(12, c.left), Math.max(12, window.innerWidth - pw - 12));
    const top = Math.min(Math.max(12, c.top), Math.max(12, window.innerHeight - ph - 12));
    let score = 0;
    if (targetRect) score += _tourOverlapArea(left, top, pw, ph, targetRect) * 1000;
    for (const r of avoidRects) score += _tourOverlapArea(left, top, pw, ph, r);
    if (score === 0) return { left, top };
    if (!best || score < best.score) best = { left, top, score };
  }
  return best || { left: 12, top: 12 };
}

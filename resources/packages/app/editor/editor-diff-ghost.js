// Compare mode — GHOST SUBTREES (UX-v4). When an arg is bound to a
// DIFFERENT fn on the compared branch (a replaced ref: `a → foo` here,
// `a → bar` there), the canvas shows the other side too: the compared
// branch's `bar`, with what it composes, drawn beside the card as a
// dashed, dimmed cluster — so "this branch of the graph was swapped for
// that one" reads as a picture, not as a label in a tooltip.
//
// A ghost is a diff artifact, not a card: it is read-only, built from
// the compared branch's `?scope=subtree` entities (fetched with an
// explicit `X-Graphden-Branch`) and laid out by this module as a
// column — root first, then the refs it binds, breadth-first, capped —
// rather than through the layout API, so it never competes with the
// real layout for space and never needs the full overlay machinery.
// Positioned in GRAPH coordinates inside the transformed graph layer,
// so it pans and zooms with everything else; re-anchored on every
// geometry sync (`gdDiffGhostsSync`) so a tween or a drag carries it.
//
// Exposes `gdDiffGhostsRender(container)` (called at the end of
// `createNodeOverlays`) and `gdDiffGhostsSync()`.

const GD_GHOST_MAX_CARDS = 8;
const GD_GHOST_MAX_ROWS = 6;
const GD_GHOST_GAP_Y = 36;
const GD_GHOST_CARD_W = 200;
const GD_GHOST_CARD_GAP = 26;
// subtree fetches, keyed `branch|fn-id` → Promise<lookups-like|null>
const _gdGhostCache = new Map();
// live clusters: {el, link, anchorId, rootId}
let _gdGhosts = [];
let _gdGhostEpoch = 0;

function _gdGhostBranchHeaders(branch) {
  const h = { 'X-Graphden-Branch': branch };
  try {
    const tok = (typeof getStoredToken === 'function') ? getStoredToken() : null;
    if (tok) h.Authorization = 'Bearer ' + tok;
  } catch (_) { /* the fetch wrap attaches the bearer anyway */ }
  return h;
}

// The compared branch's subtree under `fnId`, indexed like `lookups`.
function gdDiffGhostSubtree(branch, fnId) {
  const key = branch + '|' + fnId;
  if (_gdGhostCache.has(key)) return _gdGhostCache.get(key);
  const p = (async () => {
    try {
      const r = await fetch(
        API.api_graph_entities + '?scope=subtree&root-id=' + encodeURIComponent(fnId),
        { headers: _gdGhostBranchHeaders(branch) });
      if (!r.ok) return null;
      const sub = await r.json();
      if (!sub || !Array.isArray(sub.fns)) return null;
      return (typeof buildLookups === 'function') ? buildLookups(sub) : null;
    } catch (_) { return null; }
  })();
  _gdGhostCache.set(key, p);
  return p;
}

function _gdGhostValue(lk, b) {
  if (b['ref-fn-id']) {
    const t = lk.fnMap.get(b['ref-fn-id']);
    return { ref: b['ref-fn-id'], text: '→ ' + (t?.name ? ':' + t.name : '#' + String(b['ref-fn-id']).slice(0, 8)) };
  }
  const items = lk.itemsByBinding?.get(b.id);
  if (items?.length) {
    return { text: '[' + items.length + ' item' + (items.length === 1 ? '' : 's') + ']' };
  }
  if (b.value !== undefined && b.value !== null) {
    let v;
    try { v = typeof b.value === 'string' ? b.value : JSON.stringify(b.value); }
    catch (_) { v = String(b.value); }
    return { text: v.length > 28 ? v.slice(0, 27) + '…' : v };
  }
  return { text: '∅' };
}

// Cards of the ghost: root + the fns it refs, breadth-first, capped.
function _gdGhostPlan(lk, rootId) {
  const cards = [];
  const seen = new Set([rootId]);
  const queue = [rootId];
  while (queue.length && cards.length < GD_GHOST_MAX_CARDS) {
    const id = queue.shift();
    const fn = lk.fnMap.get(id);
    if (!fn) continue;
    const rows = [];
    const refs = [];
    for (const b of (lk.bindingsByFn.get(id) || [])) {
      const slot = lk.slotMap.get(b['slot-id']);
      const v = _gdGhostValue(lk, b);
      rows.push({ name: slot?.name || '?', text: v.text, ref: v.ref || null });
      if (v.ref && !seen.has(v.ref)) { seen.add(v.ref); refs.push(v.ref); }
      for (const it of (lk.itemsByBinding?.get(b.id) || [])) {
        if (it['ref-fn-id'] && !seen.has(it['ref-fn-id'])) {
          seen.add(it['ref-fn-id']); refs.push(it['ref-fn-id']);
        }
      }
    }
    const parents = (fn['parent-ids'] || [])
      .map((pid) => lk.fnMap.get(pid)?.name).filter(Boolean);
    cards.push({ id, name: fn.name || '(anonymous)', parents, rows, refs });
    queue.push(...refs);
  }
  return cards;
}

function _gdGhostCardEl(card, branch, isRoot) {
  const el = document.createElement('div');
  el.className = 'gd-ghost-card' + (isRoot ? ' gd-ghost-root' : '');
  el.dataset.ghostFnId = card.id;
  const head = document.createElement('div');
  head.className = 'gd-ghost-card-head';
  head.textContent = ':' + card.name;
  head.title = 'On "' + branch + '": :' + card.name
    + (card.parents.length ? ' ← ' + card.parents.map((n) => ':' + n).join(', ') : '');
  el.appendChild(head);
  if (card.parents.length) {
    const p = document.createElement('div');
    p.className = 'gd-ghost-card-parents';
    p.textContent = '← ' + card.parents.map((n) => ':' + n).join(', ');
    el.appendChild(p);
  }
  card.rows.slice(0, GD_GHOST_MAX_ROWS).forEach((r) => {
    const row = document.createElement('div');
    row.className = 'gd-ghost-row' + (r.ref ? ' gd-ghost-row-ref' : '');
    const k = document.createElement('span');
    k.className = 'gd-ghost-row-k';
    k.textContent = r.name;
    const v = document.createElement('span');
    v.className = 'gd-ghost-row-v';
    v.textContent = r.text;
    row.appendChild(k);
    row.appendChild(v);
    el.appendChild(row);
  });
  if (card.rows.length > GD_GHOST_MAX_ROWS) {
    const more = document.createElement('div');
    more.className = 'gd-ghost-row gd-ghost-more';
    more.textContent = '+' + (card.rows.length - GD_GHOST_MAX_ROWS) + ' more';
    el.appendChild(more);
  }
  return el;
}

// Build one cluster element for `rootId` on `branch`; `slotName` is the
// arg whose "there" side this is.
function _gdGhostClusterEl(lk, rootId, branch, slotName, anchorId) {
  const cards = _gdGhostPlan(lk, rootId);
  if (!cards.length) return null;
  const el = document.createElement('div');
  el.className = 'gd-ghost-cluster';
  el.dataset.anchorId = anchorId;
  el.dataset.ghostRoot = rootId;
  el.style.width = GD_GHOST_CARD_W + 'px';
  const head = document.createElement('button');
  head.type = 'button';
  head.className = 'gd-ghost-head';
  head.textContent = '◌ ' + slotName + ' there → :' + cards[0].name;
  head.title = 'On "' + branch + '" this arg points at :' + cards[0].name
    + ' — the compared branch’s subtree, read-only. Click to fold / unfold.';
  head.setAttribute('aria-expanded', 'true');
  head.addEventListener('mousedown', (e) => e.stopPropagation());
  head.addEventListener('click', (e) => {
    e.stopPropagation();
    const folded = el.classList.toggle('gd-ghost-folded');
    head.setAttribute('aria-expanded', String(!folded));
    gdDiffGhostsSync();
  });
  el.appendChild(head);
  const body = document.createElement('div');
  body.className = 'gd-ghost-body';
  cards.forEach((c, i) => { body.appendChild(_gdGhostCardEl(c, branch, i === 0)); });
  if (cards.length === GD_GHOST_MAX_CARDS) {
    const more = document.createElement('div');
    more.className = 'gd-ghost-row gd-ghost-more';
    more.textContent = '… deeper refs not drawn';
    body.appendChild(more);
  }
  el.appendChild(body);
  // Big clusters start folded: the chip says what it is, one click opens it.
  if (cards.length > 4) {
    el.classList.add('gd-ghost-folded');
    head.setAttribute('aria-expanded', 'false');
  }
  return el;
}

function _gdGhostLinkEl() {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', 'gd-ghost-link');
  svg.style.position = 'absolute';
  svg.style.overflow = 'visible';
  svg.style.pointerEvents = 'none';
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  svg.appendChild(path);
  return svg;
}

// Anchor geometry in graph coords.
function _gdGhostAnchorBox(anchorId) {
  const n = gv.node(anchorId);
  if (!n) return null;
  const pos = n.position();
  const w = n.width();
  const h = n.height();
  return { x: pos.x - w / 2, y: pos.y - h / 2, w, h,
           right: pos.x + w / 2, bottom: pos.y + h / 2, cx: pos.x, cy: pos.y };
}

// ABOVE the anchor, left-aligned: a card's own args always hang to its
// RIGHT and the grid's next row sits BELOW, so above is the side that is
// free of the real graph's own wiring. The cluster's height is measured
// from the DOM (inside the scaled layer `offsetHeight` is graph units).
function _gdGhostPlace(g) {
  const box = _gdGhostAnchorBox(g.anchorId);
  if (!box) { g.el.hidden = true; g.link.hidden = true; return; }
  g.el.hidden = false;
  g.link.hidden = false;
  const h = g.el.offsetHeight || 40;
  const x = box.x;
  const y = box.y - GD_GHOST_GAP_Y - h;
  g.el.style.left = x + 'px';
  g.el.style.top = y + 'px';
  // dashed riser from the anchor's top edge up to the cluster's bottom
  const x0 = box.x + Math.min(box.w, GD_GHOST_CARD_W) / 2;
  g.link.style.left = '0px';
  g.link.style.top = '0px';
  g.link.firstChild.setAttribute('d',
    'M' + x0 + ',' + box.y + ' L' + x0 + ',' + (y + h));
}

function gdDiffGhostsSync() {
  for (const g of _gdGhosts) _gdGhostPlace(g);
}

function gdDiffGhostsClear() {
  for (const g of _gdGhosts) { g.el.remove(); g.link.remove(); }
  _gdGhosts = [];
}

// Which (anchor node, there-ref, slot) triples the current graph asks
// for: value / placeholder arg nodes carry the owning fn + slot; a ref
// bound HERE is an edge to a card, anchored on that card.
function _gdGhostWants() {
  if (typeof gdDiffModeActive !== 'function' || !gdDiffModeActive()) return [];
  if (typeof gdDiffSlotDetails !== 'function' || typeof argRowFromNode !== 'function') return [];
  const wants = [];
  const seen = new Set();
  const consider = (fnId, slot, anchorId) => {
    if (!fnId || !slot || !anchorId) return;
    const d = gdDiffSlotDetails(fnId)?.[slot];
    if (!d?.sourceRef || d.sourceRef === d.targetRef) return;
    const key = fnId + '|' + slot;
    if (seen.has(key)) return;
    seen.add(key);
    wants.push({ fnId, slot, anchorId, ref: d.sourceRef });
  };
  for (const n of gv.nodes()) {
    if (n.data('type') === 'fn' && !n.data('isPlaceholder')) continue;
    const arg = argRowFromNode(n.data());
    if (arg?.['fn-id'] && arg.name) consider(arg['fn-id'], arg.name, n.id());
  }
  for (const e of gv.edges()) {
    const name = e.data('argName');
    const t = e.target();
    if (!name || !t || t.data('type') !== 'fn' || t.data('isPlaceholder')) continue;
    const owner = e.data('fnId') || e.source()?.data('originalFnId');
    consider(owner, name, t.id());
  }
  return wants;
}

function gdDiffGhostsRender(container) {
  gdDiffGhostsClear();
  const wants = _gdGhostWants();
  if (!wants.length) return;
  const branch = gdDiffModeBranch();
  const epoch = ++_gdGhostEpoch;
  const host = container || (typeof getGraphLayer === 'function' ? getGraphLayer() : null);
  if (!host) return;
  for (const w of wants) {
    gdDiffGhostSubtree(branch, w.ref).then((lk) => {
      if (epoch !== _gdGhostEpoch || !lk) return;
      if (!gv.node(w.anchorId)) return;
      const el = _gdGhostClusterEl(lk, w.ref, branch, w.slot, w.anchorId);
      if (!el) return;
      const link = _gdGhostLinkEl();
      host.appendChild(link);
      host.appendChild(el);
      const g = { el, link, anchorId: w.anchorId, rootId: w.ref };
      _gdGhosts.push(g);
      _gdGhostPlace(g);
    });
  }
}

window.gdDiffGhostsRender = gdDiffGhostsRender;
window.gdDiffGhostsClear = gdDiffGhostsClear;
window.gdDiffGhostsSync = gdDiffGhostsSync;
window.gdDiffGhostSubtree = gdDiffGhostSubtree;

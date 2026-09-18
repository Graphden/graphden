// Editor COMPARE MODE — the NODE half: the inspector diff panel and the marks
// drawn on a card.
//
// `gdDiffRenderInspectorSection`
// (called by the inspector after every render) shows old → new entries, the
// effects delta and anchored 💬 threads; `gdDiffModeCardInfo` /
// `gdDiffInsideBadgeEl` / `gdDiffRevealVia` drive the card ring and the ∿
// changed-inside badge (editor-overlay-fn.js consults them); `gdDiffWasEl` and
// `gdDiffAppendFnStrip` draw the "there" values on the node itself
// (`.arg-diff-was`, `.fn-diff-was`) — the diff as a GRAPH (UX-v4).

// --- the inspector diff panel ----------------------------------------------

// Injected by editor-shell.js right after the inspector head whenever
// a fn renders while compare mode is on and the fn differs under the
// current lens: the per-fn detail surface (entries old→new, effects,
// branch-local marker) + the ANCHORED comment threads, exactly where
// the user is already looking at the selected node.
function gdDiffRenderInspectorSection(inspectorEl, fnId) {
  inspectorEl.querySelector('#gd-diff-insp')?.remove();
  const g = fnId ? gdDiffVisibleGroup(fnId) : null;
  if (!g || !_gdDiffMode) return;
  const panel = document.createElement('div');
  panel.id = 'gd-diff-insp';
  panel.className = 'gd-diff-insp';
  const head = document.createElement('div');
  head.className = 'gd-diff-insp-head';
  head.appendChild(gdDiffMarkerEl(g.change));
  const title = document.createElement('span');
  title.textContent = 'vs ' + _gdDiffMode.branch;
  head.appendChild(title);
  if (g.__effects) {
    const fx = document.createElement('span');
    fx.className = 'bd-effects-chip';
    fx.textContent = g.__effects;
    head.appendChild(fx);
  }
  if (g['branch-local?']) {
    const badge = document.createElement('span');
    badge.className = 'branch-diff-row-local-badge';
    badge.title = "Won't propagate on merge — branch-local fn";
    badge.textContent = '📍 branch-local';
    head.appendChild(badge);
  }
  head.appendChild(gdDiffCommentBtnEl('fn', g['fn-id']));
  // The fn-level anchor target (threads mount after this head).
  head.setAttribute('data-anchor-name', 'fn');
  head.setAttribute('data-anchor-id', g['fn-id']);
  panel.appendChild(head);
  panel.appendChild(gdDiffRenderGroup(g, { entriesOnly: true }));
  const inspHead = inspectorEl.querySelector('.gd-insp-head');
  (inspHead || inspectorEl).insertAdjacentElement('afterend', panel);
  // Anchored threads (fn + entries) with composers; no general thread
  // here — that lives in the review dialog.
  // Threads read/post the PAIR's one review thread (proposalRef) —
  // not the compared branch's: an author on their feature comparing
  // vs main anchors notes on the FEATURE's thread. No review context
  // (two roots) → no threads here.
  if (typeof gdDiffAttachThreads === 'function' && _gdDiffMode.proposalRef) {
    gdDiffAttachThreads(panel, _gdDiffMode.branch, _gdDiffMode.proposalRef,
                        { anchoredOnly: true });
  }
}

// Card-level mark for `editor-overlay-fn.js` — ring the whole fn card
// when the fn differs under the current lens.
function gdDiffModeCardInfo(fnId) {
  const g = fnId ? gdDiffVisibleGroup(fnId) : null;
  if (g) return { kind: g.__kind, cls: GD_DIFF_CLS[g.__kind], title: g.__title };
  const a = fnId ? gdDiffAffectedInfo(fnId) : null;
  if (a) {
    return { kind: 'inside', cls: GD_DIFF_CLS.inside, title: a.title,
             via: a.via, viaLabel: a.viaLabel };
  }
  return null;
}

// Take the reader to the change a "changed inside" card inherits: an
// ANCESTOR is revealed in place (the card expands to the level that
// holds it, so its Δ rows show in this graph's context); anything else
// (a ref target off-canvas) is opened as the root.
function gdDiffRevealVia(nodeId, fnId, via) {
  if (!via) return;
  const levels = (typeof getInheritanceLevels === 'function')
    ? getInheritanceLevels(fnId) : [];
  const depth = levels.findIndex((lvl) => lvl.includes(via));
  if (depth > 0 && typeof renderGraph === 'function'
      && typeof expansionState !== 'undefined') {
    const cur = expansionState.get(nodeId);
    if (!cur || (cur.fullDepth || 0) < depth) {
      expansionState.set(nodeId, { fullDepth: depth, partialFns: new Set() });
      if (typeof savedUserPositions !== 'undefined') savedUserPositions.clear();
      renderGraph(false);
      return;
    }
  }
  if (typeof selectFn === 'function') selectFn(via);
}

// The ∿ badge on a "changed inside" card.
function gdDiffInsideBadgeEl(nodeId, fnId, dm) {
  const badge = document.createElement('button');
  badge.type = 'button';
  badge.className = 'fn-diff-inside-badge';
  badge.textContent = '∿';
  badge.title = dm.title + ' — click to reveal ' + dm.viaLabel;
  badge.setAttribute('aria-label', badge.title);
  badge.addEventListener('mousedown', (e) => e.stopPropagation());
  badge.addEventListener('click', (e) => {
    e.stopPropagation();
    gdDiffRevealVia(nodeId, fnId, dm.via);
  });
  return badge;
}

// The "there: …" block under an arg (or on an unbound placeholder) —
// the node-level data change drawn ON the node: value, type, position,
// description; a replaced ref says where it points there (the ghost
// module draws that subtree beside the card).
function gdDiffWasEl(d) {
  if (!d) return null;
  const lines = [];
  if (d.change === 'added-in-target' && !d.fields.length) {
    lines.push({ k: '', v: 'unbound there' });
  } else if (d.change === 'added-in-source' && !d.fields.length) {
    lines.push({ k: 'there', v: d.preview || 'bound' });
  }
  for (const f of d.fields) {
    const pos = (f.position !== undefined && f.position !== null) ? '[' + f.position + '] ' : '';
    if (f.field === 'ref-fn-id') lines.push({ k: pos + '→ there', v: f.source });
    else if (f.field === 'value') lines.push({ k: pos + 'there', v: f.source });
    else if (f.field === 'item') lines.push({ k: pos + 'there', v: f.source });
    else if (f.field === 'type-override-fn-id') lines.push({ k: pos + 'type there', v: f.source });
    else if (f.field === 'description') lines.push({ k: pos + 'description', v: '~' });
    else lines.push({ k: pos + f.field + ' there', v: f.source });
  }
  if (d.slotRow === 'added-in-source') lines.push({ k: '', v: 'slot only there' });
  if (d.slotRow === 'added-in-target') lines.push({ k: '', v: 'slot only here' });
  if (!lines.length) return null;
  const el = document.createElement('div');
  el.className = 'arg-diff-was';
  el.title = 'vs "' + (_gdDiffMode?.branch || '') + '"';
  for (const ln of lines) {
    const row = document.createElement('div');
    row.className = 'arg-diff-was-line';
    if (ln.k) {
      const k = document.createElement('span');
      k.className = 'arg-diff-was-k';
      k.textContent = ln.k + ': ';
      row.appendChild(k);
    }
    const v = document.createElement('s');
    v.className = 'arg-diff-was-v';
    v.textContent = gdDiffShort(ln.v, 60);
    row.appendChild(v);
    el.appendChild(row);
  }
  return el;
}

// The fn-card strip for the fn's OWN row change — a rename shows the
// other name; a description edit says so.
function gdDiffAppendFnStrip(overlay, fnId) {
  const own = gdDiffFnOwnFields(fnId);
  if (own?.change !== 'modified' || !own.fields.length) return;
  const el = document.createElement('div');
  el.className = 'fn-diff-was';
  const parts = [];
  for (const f of own.fields) {
    if (f.field === 'name') parts.push('name there: ' + gdDiffShort(f.source, 40));
    else if (f.field === 'description') parts.push('description differs there');
    else parts.push(f.field + ' there: ' + gdDiffShort(f.source, 40));
  }
  el.textContent = parts.join(' · ');
  el.title = 'vs "' + (_gdDiffMode?.branch || '') + '"';
  overlay.appendChild(el);
}

// Public API for sibling modules (editor-overlay-fn.js rings a changed card
// from it). Exported HERE, with its declaration — editor-diff-mode.js used to
// export it and that is a load-time reference across files.
window.gdDiffModeCardInfo = gdDiffModeCardInfo;

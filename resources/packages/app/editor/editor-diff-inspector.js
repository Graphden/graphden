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

// The conversation belongs to a branch pair and selected function, rather
// than the replaceable annotation DOM. Auth changes must not reuse its drafts.
const _gdDiffInspectorConversations = new WeakMap();
const _gdDiffInspectorSheets = new WeakMap();
const _gdDiffInspectorMounts = new WeakMap();
const _gdDiffInspectorKeys = new WeakMap();
let _gdDiffInspectorSubscribed = false;
function gdDiffInspectorUnmount(host) {
  const mounted = _gdDiffInspectorMounts.get(host);
  if (mounted) { mounted.dispose(); _gdDiffInspectorMounts.delete(host); }
  if (host) delete host.dataset.gdUiStyle;
}
function gdDiffDisposeInspectorPanel(panel) {
  if (!panel) return;
  for (const host of panel.querySelectorAll('.gd-diff-graph-host')) gdDiffInspectorUnmount(host);
  window.GraphdenStyles?.release(_gdDiffInspectorSheets.get(panel)?.sheet);
  _gdDiffInspectorSheets.delete(panel);
  _gdDiffInspectorKeys.delete(panel);
  panel.remove();
}
function gdDiffInspectorNativeBody(host, surface, diff, nativeEntry) {
  host.replaceChildren();
  if (surface === 'compare-entry') {
    const row = gdDiffRenderEntry(nativeEntry, {comments: false});
    for (const child of [...row.children]) host.appendChild(child);
    return;
  }
  host.appendChild(gdDiffMarkerEl(diff.change));
  const title = document.createElement('span');
  title.textContent = 'vs ' + diff.branch;
  host.appendChild(title);
  if (diff.effects) {
    const fx = document.createElement('span');
    fx.className = 'bd-effects-chip';
    fx.textContent = diff.effects;
    host.appendChild(fx);
  }
  if (diff.local) {
    const badge = document.createElement('span');
    badge.className = 'branch-diff-row-local-badge';
    badge.title = "Won't propagate on merge — branch-local fn";
    badge.textContent = '📍 branch-local';
    host.appendChild(badge);
  }
}
function gdDiffInspectorPaint(host, surface, diff, rules, nativeEntry = null) {
  if (!window.gdInspectorGraph?.compareView || !window.GraphdenRenderer?.mount) {
    gdDiffInspectorUnmount(host);
    gdDiffInspectorNativeBody(host, surface, diff, nativeEntry);
    return;
  }
  try {
    const view = window.gdInspectorGraph.compareView(surface, diff);
    const nextRules = new Map(rules);
    for (const rule of view.styles) nextRules.set(JSON.stringify(rule), rule);
    if (nextRules.size > 128) throw new Error('Inspector compare style budget exceeded');
    let mounted = _gdDiffInspectorMounts.get(host);
    if (!mounted) {
      host.replaceChildren();
      mounted = window.GraphdenRenderer.mount(host, {idPrefix: 'gd-inspdiff', nodeLimit: 10000});
      _gdDiffInspectorMounts.set(host, mounted);
    }
    mounted.render(view.tree, []);
    for (const [key, rule] of nextRules) rules.set(key, rule);
  } catch (_) {
    window.gdUIComponentFailed?.('inspector');
    gdDiffInspectorUnmount(host);
    gdDiffInspectorNativeBody(host, surface, diff, nativeEntry);
  }
}
function gdDiffInspectorHost() {
  const host = document.createElement('span');
  host.className = 'gd-diff-graph-host';
  return host;
}
function gdDiffInspectorSheet(panel, rules) {
  const nextRules = [...rules.values()];
  const signature = JSON.stringify(nextRules);
  const current = _gdDiffInspectorSheets.get(panel);
  const same = current?.signature === signature;
  const next = same ? current.sheet : (nextRules.length ? window.GraphdenStyles?.acquire(nextRules) : null);
  for (const host of panel.querySelectorAll('.gd-diff-graph-host')) {
    if (next && _gdDiffInspectorMounts.has(host)) host.dataset.gdUiStyle = next.id;
    else delete host.dataset.gdUiStyle;
  }
  if (!same) {
    window.GraphdenStyles?.release(current?.sheet);
    _gdDiffInspectorSheets.set(panel, {sheet: next, signature});
  }
}
function gdDiffInspectorSubscribeRuntime() {
  if (_gdDiffInspectorSubscribed || !window.gdInspectorGraph?.subscribeRuntime) return;
  _gdDiffInspectorSubscribed = true;
  let first = true;
  window.gdInspectorGraph.subscribeRuntime(() => {
    if (first) { first = false; return; }
    const inspector = document.getElementById('gd-inspector');
    if (inspector && _gdDiffMode) gdDiffRenderInspectorSection(inspector, inspector.dataset.fnId || null);
  });
}
function gdDiffCaptureInspectorFocus(inspectorEl, nextFnId) {
  const previous = _gdDiffInspectorConversations.get(inspectorEl);
  if (nextFnId !== undefined && previous?.fnId !== nextFnId) _gdDiffInspectorConversations.delete(inspectorEl);
  else previous?.controller?.captureFocus();
}

function gdDiffInspectorUpdateHead(head, group, rules) {
  const host = [...head.children].find(node => node.classList.contains('gd-diff-graph-host'));
  gdDiffInspectorPaint(host, 'compare-head', {change: group.change,
    branch: _gdDiffMode.branch, effects: group.__effects || '', local: !!group['branch-local?'],
    entity: '', slot: '', position: '', preview: '', fields: []}, rules);
}
function gdDiffInspectorUpdateEntries(panel, group, rules) {
  const entries = panel.querySelector('.branch-diff-entries');
  const keep = (group.entries || []).filter(e =>
    e['entity-name'] !== 'fn' || e.fields?.length || e.preview);
  const entryKey = row => JSON.stringify([row.getAttribute('data-anchor-name'), row.getAttribute('data-anchor-id')]);
  const rows = () => [...entries.children].filter(node => node.classList.contains('branch-diff-entry'));
  const existing = new Map(rows().map(row => [entryKey(row), row]));
  const desired = [];
  for (const entry of keep) {
    const key = JSON.stringify([entry['entity-name'], String(entry['entity-id'])]);
    let row = existing.get(key);
    if (row) existing.delete(key);
    else {
      const host = gdDiffInspectorHost();
      row = gdDiffRenderEntry(entry, {body: () => host});
      entries.appendChild(row);
    }
    row.className = 'branch-diff-entry ' + gdDiffChangeClass(entry.change);
    if (entry['slot-id']) row.setAttribute('data-slot-id', entry['slot-id']);
    else row.removeAttribute('data-slot-id');
    if (entry['slot-name']) row.setAttribute('data-slot-name', entry['slot-name']);
    else row.removeAttribute('data-slot-name');
    const host = [...row.children].find(node => node.classList.contains('gd-diff-graph-host'));
    gdDiffInspectorPaint(host, 'compare-entry', {change: entry.change,
      branch: _gdDiffMode.branch, effects: '', local: false,
      entity: entry['entity-name'], slot: entry['slot-name'] || '',
      position: Number.isSafeInteger(entry.position) ? String(entry.position) : '',
      preview: entry.preview || '',
      fields: (entry.fields || []).map(field => ({name: field.field,
        old: String(field.target ?? '∅'), new: String(field.source ?? '∅'),
        'show-name': field.field !== 'value'}))}, rules, entry);
    desired.push(row);
  }
  for (const row of existing.values()) {
    const after = entries.children[[...entries.children].indexOf(row) + 1];
    if (after?.classList.contains('branch-diff-anchor-thread')) {
      if (after.querySelector('.branch-diff-anchor-compose')) panel.appendChild(after);
      else after.remove();
    }
    gdDiffInspectorUnmount(row.querySelector('.gd-diff-graph-host'));
    row.remove();
  }
  for (let index = 0; index < desired.length; index++) {
    const current = rows()[index];
    const row = desired[index];
    if (current === row) continue;
    const after = entries.children[[...entries.children].indexOf(row) + 1];
    const thread = after?.classList.contains('branch-diff-anchor-thread') ? after : null;
    entries.insertBefore(row, current || null);
    if (thread) entries.insertBefore(thread, current || null);
  }
}

// --- the inspector diff panel ----------------------------------------------

// Injected by editor-shell.js right after the inspector head whenever
// a fn renders while compare mode is on and the fn differs under the
// current lens: the per-fn detail surface (entries old→new, effects,
// branch-local marker) + the ANCHORED comment threads, exactly where
// the user is already looking at the selected node.
function gdDiffRenderInspectorSection(inspectorEl, fnId) {
  gdDiffCaptureInspectorFocus(inspectorEl);
  let panel = inspectorEl.querySelector('#gd-diff-insp');
  const g = fnId ? gdDiffVisibleGroup(fnId) : null;
  if (!g || !_gdDiffMode) {
    gdDiffDisposeInspectorPanel(panel);
    _gdDiffInspectorConversations.delete(inspectorEl);
    return;
  }
  gdDiffInspectorSubscribeRuntime();
  const identity = JSON.stringify([_gdDiffMode.currentId, _gdDiffMode.proposalRef,
    _gdDiffMode.branch, fnId, window.gdPrefOwner || null]);
  if (panel && _gdDiffInspectorKeys.get(panel) !== identity) {
    gdDiffDisposeInspectorPanel(panel);
    panel = null;
  }
  if (!panel) {
    panel = document.createElement('div');
    panel.id = 'gd-diff-insp';
    panel.className = 'gd-diff-insp';
    const head = document.createElement('div');
    head.className = 'gd-diff-insp-head';
    head.appendChild(gdDiffInspectorHost());
    head.appendChild(gdDiffCommentBtnEl('fn', g['fn-id']));
    head.setAttribute('data-anchor-name', 'fn');
    head.setAttribute('data-anchor-id', g['fn-id']);
    panel.appendChild(head);
    const entries = document.createElement('div');
    entries.className = 'branch-diff-entries';
    panel.appendChild(entries);
    const nativeHost = inspectorEl.querySelector('[data-gd-inspector-diff-host]');
    if (nativeHost) nativeHost.appendChild(panel);
    else inspectorEl.appendChild(panel);
    _gdDiffInspectorKeys.set(panel, identity);
  }
  const rules = new Map();
  gdDiffInspectorUpdateHead(panel.querySelector('.gd-diff-insp-head'), g, rules);
  gdDiffInspectorUpdateEntries(panel, g, rules);
  gdDiffInspectorSheet(panel, rules);
  // Anchored threads (fn + entries) with composers; no general thread
  // here — that lives in the review dialog.
  // Threads read/post the PAIR's one review thread (proposalRef) —
  // not the compared branch's: an author on their feature comparing
  // vs main anchors notes on the FEATURE's thread. No review context
  // (two roots) → no threads here.
  if (typeof gdDiffAttachThreads === 'function' && _gdDiffMode.proposalRef) {
    const key = JSON.stringify([_gdDiffMode.currentId, _gdDiffMode.proposalRef, fnId, window.gdPrefOwner || null]);
    const previous = _gdDiffInspectorConversations.get(inspectorEl);
    if (previous?.key === key) previous.controller.mount(panel);
    else _gdDiffInspectorConversations.set(inspectorEl, {key, fnId,
      controller: gdDiffAttachThreads(panel, _gdDiffMode.branch, _gdDiffMode.proposalRef, {anchoredOnly: true})});
  } else _gdDiffInspectorConversations.delete(inspectorEl);
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

// An expanded ancestor is a row inside the child's card, rather than a new
// card with its own ring. Keep its own changes reachable on that exact row.
function gdDiffAncestorBadgeEl(fnId) {
  const group = gdDiffVisibleGroup(fnId);
  if (!group) return null;
  const badge = document.createElement('button');
  badge.type = 'button';
  badge.className = 'arg-diff-badge fn-diff-ancestor-badge';
  badge.dataset.fnId = fnId;
  badge.textContent = 'Δ';
  badge.title = 'Differs vs "' + gdDiffModeBranch() + '": '
    + gdDiffSummaryParts(group).join('; ') + ' — inspect this ancestor';
  badge.setAttribute('aria-label', badge.title);
  badge.addEventListener('mousedown', (e) => e.stopPropagation());
  badge.addEventListener('click', (e) => {
    e.stopPropagation();
    if (typeof window.gdInspectorRender === 'function') window.gdInspectorRender(fnId);
  });
  return badge;
}

// Type, position and other field changes remain on the current node. Literal
// replacements have a separate read-only source node and replacement link;
// ref replacements have their source subtree in the same ghost layer.
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
    else if (f.field === 'value') {
      if (d.change !== 'modified' || !d.entityId) lines.push({ k: pos + 'there', v: f.source });
    }
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

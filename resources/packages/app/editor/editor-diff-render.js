// Native group/anchor owner for the review dialog and suggestion previews.
// Passive entry content uses the Inspector compare graph. The native fallback
// remains for an unavailable or invalid personal view. Loads before the
// Inspector graph owner; look it up only when a list is rendered.
//
// `opts` on the render fns (row CLICK behaviour is the caller's job —
// the renderer only builds DOM):
//   comments:    render 💬 anchors (default true; suggestion previews
//                pass false)
//   entriesOnly: skip the group head (the inspector panel — the fn is
//                already the panel's subject)
//   body:        optional passive Inspector-only renderer; native row anchors
//                and comment controls stay owned here (other callers omit it)

function gdDiffMarkerEl(change) {
  const m = document.createElement('span');
  m.setAttribute('aria-hidden', 'true');
  const cls = change === 'added-in-source' ? 'bd-added'
    : change === 'added-in-target' ? 'bd-removed' : 'bd-modified';
  m.className = 'branch-diff-marker ' + cls;
  m.textContent = change === 'added-in-source' ? '+'
    : change === 'added-in-target' ? '−' : '±';
  return m;
}

function gdDiffChangeClass(change) {
  return change === 'added-in-source' ? 'bd-added'
    : change === 'added-in-target' ? 'bd-removed' : 'bd-modified';
}

function gdDiffEntryLabel(e) {
  const slot = e['slot-name'] || '?';
  switch (e['entity-name']) {
    case 'fn': return 'fn';
    case 'fn-slot': return 'slot ' + slot;
    case 'binding': return 'arg ' + slot;
    case 'resource-override': return 'asset';
    default: return 'item ' + (e.position ?? '?') + ' of ' + slot;
  }
}

function gdDiffRenderEntry(e, opts) {
  const row = document.createElement('div');
  row.className = 'branch-diff-entry ' + gdDiffChangeClass(e.change);
  row.setAttribute('data-anchor-name', e['entity-name']);
  row.setAttribute('data-anchor-id', e['entity-id']);
  if (e['slot-id']) row.setAttribute('data-slot-id', e['slot-id']);
  if (e['slot-name']) row.setAttribute('data-slot-name', e['slot-name']);
  const body = opts.body?.(e);
  if (body) {
    row.appendChild(body);
    if (opts.comments !== false) row.appendChild(gdDiffCommentBtnEl(e['entity-name'], e['entity-id']));
    return row;
  }
  row.appendChild(gdDiffMarkerEl(e.change));
  const label = document.createElement('span');
  label.className = 'branch-diff-entry-label';
  label.textContent = gdDiffEntryLabel(e);
  row.appendChild(label);
  if (Array.isArray(e.fields) && e.fields.length) {
    const fields = document.createElement('div');
    fields.className = 'branch-diff-fields';
    for (const f of e.fields) {
      const fr = document.createElement('div');
      fr.className = 'branch-diff-field';
      if (f.field !== 'value') {   // "arg value — value:" read doubled
        const fname = document.createElement('span');
        fname.className = 'branch-diff-field-name';
        fname.textContent = f.field;
        fr.appendChild(fname);
      }
      const oldV = document.createElement('span');
      oldV.className = 'bd-old';
      oldV.textContent = f.target ?? '∅';
      fr.appendChild(oldV);
      const arrow = document.createElement('span');
      arrow.className = 'bd-arrow';
      arrow.setAttribute('aria-hidden', 'true');
      arrow.textContent = '→';
      fr.appendChild(arrow);
      const newV = document.createElement('span');
      newV.className = 'bd-new';
      newV.textContent = f.source ?? '∅';
      fr.appendChild(newV);
      fields.appendChild(fr);
    }
    row.appendChild(fields);
  } else {
    const pv = document.createElement('span');
    pv.className = 'branch-diff-entry-preview';
    pv.textContent = e.preview || '';
    row.appendChild(pv);
  }
  if (opts.comments !== false) {
    row.appendChild(gdDiffCommentBtnEl(e['entity-name'], e['entity-id']));
  }
  return row;
}

function gdDiffCommentBtnEl(anchorName, anchorId) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'branch-diff-comment-btn';
  b.title = 'Comment on this element';
  b.setAttribute('aria-label', 'Comment on this element');
  b.setAttribute('data-anchor-name', anchorName);
  b.setAttribute('data-anchor-id', anchorId);
  b.textContent = '💬';
  return b;
}

// One group → one `.branch-diff-row`. `opts`:
//   comments:    render 💬 anchors (default true; suggestion previews
//                pass false)
//   entriesOnly: skip the group head (the inspector panel — the fn is
//                already the panel's subject)
function gdDiffRenderGroup(g, opts) {
  opts = opts || {};
  const keep = (g.entries || []).filter((e) =>
    e['entity-name'] !== 'fn' || e.fields?.length || e.preview);
  const entries = document.createElement('div');
  entries.className = 'branch-diff-entries';
  keep.forEach((e) => {
    const host = opts.graph ? gdDiffInspectorHost() : null;
    const row = gdDiffRenderEntry(e, host ? {...opts, body: () => host} : opts);
    if (host) _gdReviewDiffEntries.set(row, e);
    entries.appendChild(row);
  });
  if (opts.entriesOnly) return entries;

  const row = document.createElement('div');
  row.className = 'branch-diff-row ' + gdDiffChangeClass(g.change)
    + (g['branch-local?'] ? ' branch-diff-row-local' : '');
  if (g['fn-id']) {
    row.setAttribute('data-diff-fn-id', g['fn-id']);
    row.setAttribute('data-diff-change', g.change);
    row.setAttribute('data-diff-fn-name', g['fn-name'] || '');
    row.setAttribute('data-anchor-name', 'fn');
    row.setAttribute('data-anchor-id', g['fn-id']);
  }
  const head = document.createElement('div');
  head.className = 'branch-diff-row-head';
  if (opts.graph) {
    head.appendChild(gdDiffInspectorHost());
    _gdReviewDiffGroups.set(row, g);
  } else {
    head.appendChild(gdDiffMarkerEl(g.change));
    const name = document.createElement('strong');
    name.className = 'branch-diff-fn-name';
    name.textContent = g['fn-label'] || '?';
    head.appendChild(name);
    if (g['branch-local?']) {
      const badge = document.createElement('span');
      badge.className = 'branch-diff-row-local-badge';
      badge.title = "Won't propagate on merge — branch-local fn";
      badge.textContent = '📍 branch-local';
      head.appendChild(badge);
    }
  }
  if (opts.comments !== false && g['fn-id']) {
    head.appendChild(gdDiffCommentBtnEl('fn', g['fn-id']));
  }
  row.appendChild(head);
  row.appendChild(entries);
  return row;
}

const _gdReviewDiffEntries = new WeakMap();
const _gdReviewDiffGroups = new WeakMap();
const _gdReviewDiffLists = new Set();
let _gdReviewDiffSubscription = null;

function gdDiffReviewEntryInput(entry) {
  return {change: entry.change, branch: '', effects: '', local: false,
    entity: entry['entity-name'], slot: entry['slot-name'] || '',
    position: Number.isSafeInteger(entry.position) ? String(entry.position) : '',
    preview: entry.preview || '',
    fields: (entry.fields || []).map(field => ({name: field.field,
      old: String(field.target ?? '∅'), new: String(field.source ?? '∅'),
      'show-name': field.field !== 'value'}))};
}

function gdDiffPaintReviewList(container) {
  const rules = new Map();
  for (const row of container.querySelectorAll('.branch-diff-row')) {
    const group = _gdReviewDiffGroups.get(row);
    const host = row.querySelector('.branch-diff-row-head .gd-diff-graph-host');
    if (group && host) gdDiffInspectorPaint(host, 'compare-review-head',
      {change: group.change, branch: group['fn-label'] || '?', effects: '',
        local: !!group['branch-local?'], entity: '', slot: '', position: '',
        preview: '', fields: []}, rules);
  }
  for (const row of container.querySelectorAll('.branch-diff-entry')) {
    const entry = _gdReviewDiffEntries.get(row);
    const host = row.querySelector('.gd-diff-graph-host');
    if (entry && host) gdDiffInspectorPaint(host, 'compare-entry',
      gdDiffReviewEntryInput(entry), rules, entry);
  }
  gdDiffInspectorSheet(container, rules);
}

function gdDiffDisposeReviewGroups(container) {
  if (!container || !_gdReviewDiffLists.delete(container)) return;
  for (const host of container.querySelectorAll('.gd-diff-graph-host')) gdDiffInspectorUnmount(host);
  window.GraphdenStyles?.release(_gdDiffInspectorSheets.get(container)?.sheet);
  _gdDiffInspectorSheets.delete(container);
  if (!_gdReviewDiffLists.size && _gdReviewDiffSubscription) {
    _gdReviewDiffSubscription();
    _gdReviewDiffSubscription = null;
  }
}

function gdDiffDisposeReviewWithin(root) {
  for (const list of _gdReviewDiffLists) {
    if (root.contains(list)) gdDiffDisposeReviewGroups(list);
  }
}

function gdDiffRenderGroups(container, groups, opts) {
  gdDiffDisposeReviewGroups(container);
  container.replaceChildren();
  const graph = !!(window.gdInspectorGraph?.compareView && window.GraphdenRenderer?.mount)
    && !container.closest('.branch-diff-modal.hidden');
  const frag = document.createDocumentFragment();
  (groups || []).forEach((g) => { frag.appendChild(gdDiffRenderGroup(g, {...opts, graph})); });
  container.appendChild(frag);
  if (!graph) return;
  _gdReviewDiffLists.add(container);
  gdDiffPaintReviewList(container);
  if (!_gdReviewDiffSubscription) {
    let first = true;
    _gdReviewDiffSubscription = window.gdInspectorGraph.subscribeRuntime(() => {
      if (first) { first = false; return; }
      for (const list of _gdReviewDiffLists) gdDiffPaintReviewList(list);
    });
  }
}

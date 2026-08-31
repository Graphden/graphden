// Client renderer of DIFF-VIEW GROUPS — the DOM the retired server
// partial used to emit, extracted from editor-diff-mode.js so the one
// renderer three surfaces share (the review dialog's "What changed"
// list, per-suggestion Δ previews, the inspector's diff panel) is its
// own module rather than a compare-mode internal. Same classes → same
// CSS; rows/entries carry the data-anchor-* hooks the thread attacher
// (editor-branch-diff.js) binds against. Loads BEFORE branch-diff and
// diff-mode in _editor-script-paths.
//
// `opts` on the render fns:
//   interactive: rows navigate on click (default true — the dialog
//                passes false; navigation belongs to compare mode)
//   comments:    render 💬 anchors (default true; suggestion previews
//                pass false)
//   entriesOnly: skip the group head (the inspector panel — the fn is
//                already the panel's subject)

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
  if (e['slot-name']) row.setAttribute('data-slot-name', e['slot-name']);
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
//   interactive: rows navigate on click (default true — dialog passes
//                false; navigation belongs to compare mode)
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
  keep.forEach((e) => entries.appendChild(gdDiffRenderEntry(e, opts)));
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
  if (opts.comments !== false && g['fn-id']) {
    head.appendChild(gdDiffCommentBtnEl('fn', g['fn-id']));
  }
  row.appendChild(head);
  row.appendChild(entries);
  return row;
}

function gdDiffRenderGroups(container, groups, opts) {
  const frag = document.createDocumentFragment();
  (groups || []).forEach((g) => frag.appendChild(gdDiffRenderGroup(g, opts)));
  container.appendChild(frag);
}

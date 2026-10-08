'use strict';

// Compare annotations keep slot/item identity, refresh the open surfaces and
// discard stale async refreshes. Run: node tools/runtime-test/compare-identity-refresh.test.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const EDITOR = path.join(__dirname, '../../resources/packages/app/editor');
const doc = createDocument();
const inspector = doc.createElement('div');
inspector.id = 'gd-inspector';
inspector.dataset.fnId = 'selected-child';
doc.body.appendChild(inspector);
const draws = [];
const inspected = [];
let resets = 0;
const ctx = vm.createContext({
  console, document: doc, localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
  selectedFnId: 'navigation-root',
  selectFn: () => { throw new Error('Comparison refresh must preserve the selected child'); },
  createNodeOverlays: () => draws.push(ctx.gdDiffModeGroup('owner')),
  gdDiffRenderInspectorSection: (_el, id) => inspected.push(id),
  gdDiffGhostsReset: () => { resets += 1; },
  gdDiffModeDecorateSidebar() {}, gdDiffModeRenderChip() {},
});
ctx.window = ctx;
vm.runInContext(fs.readFileSync(path.join(EDITOR, 'editor-diff-mode.js'), 'utf8'), ctx);
const binding = (slot, source) => ({ 'entity-name': 'binding', 'entity-id': 'b-' + slot,
  'slot-id': slot, 'slot-name': 'same-label', change: 'modified',
  fields: [{ field: 'value', source, target: 'old' }] });
const item = (id, position, sourceRef, targetRef) => ({ 'entity-name': 'binding-list-item',
  'entity-id': id, 'item-id': id, 'slot-id': 'list-slot', 'slot-name': 'items',
  change: 'modified', position, 'source-ref': sourceRef, 'target-ref': targetRef,
  fields: [{ field: 'ref-fn-id', source: sourceRef, target: targetRef }] });
const group = { 'fn-id': 'owner', __kind: 'modified', entries: [
  binding('first-slot', 'first-new'), binding('second-slot', 'second-new'),
  item('item-zero', 0, 'new-zero', 'old-zero'), item('item-one', 1, 'new-one', 'old-one'),
] };
const mode = { branch: 'feature', byFnId: new Map([['owner', group]]), affected: new Map() };
ctx.fixture = mode;
vm.runInContext('_gdDiffMode = fixture;', ctx);

(async () => {
  const details = ctx.gdDiffSlotDetails('owner');
  assert.equal(details['first-slot'].fields[0].source, 'first-new');
  assert.equal(details['second-slot'].fields[0].source, 'second-new');
  assert.equal(details['same-label'], undefined, 'Labels are never comparison keys');
  assert.equal(details['list-slot'].sourceRef, null, 'An aggregate list has no first-item replacement');
  const arg = (id) => ({ 'fn-id': 'owner', 'slot-id': 'list-slot', 'item-id': id });
  assert.equal(ctx.gdDiffArgDetails(arg('item-zero')).sourceRef, 'new-zero');
  assert.equal(ctx.gdDiffArgDetails(arg('item-one')).sourceRef, 'new-one');
  assert.equal(ctx.gdDiffArgDetails(arg('unchanged-item')), null, 'Unchanged items have no false badge');

  const fresh = { ...mode, byFnId: new Map() };
  ctx.gdDiffModeFetch = async () => fresh;
  ctx.gdDiffModeLoadEffects = () => {};
  await ctx.gdDiffModeRefresh();
  assert.deepEqual(draws, [null], 'Fresh overlays remove a change that no longer exists');
  assert.deepEqual(inspected, ['selected-child'], 'The Inspector keeps its selected child');
  assert.equal(resets, 1, 'Old drawn and pending ghosts are invalidated');

  let finish;
  ctx.gdDiffModeFetch = () => new Promise((resolve) => { finish = resolve; });
  const pending = ctx.gdDiffModeRefresh();
  vm.runInContext('_gdDiffMode = null;', ctx);
  finish(mode);
  await pending;
  assert.equal(ctx.gdDiffModeActive(), false, 'An exited comparison is never resurrected');
  assert.equal(draws.length, 1, 'A stale refresh never rebuilds the current graph');
  // Production ghost selection preserves item identity and each visual use.
  vm.runInContext('_gdDiffMode = fixture;', ctx);
  ctx.argRowFromNode = (data) => data.arg;
  const edge = (id, itemId) => ({
    data: (key) => key ? (key === 'argName' ? 'renamed-items' : null)
      : { arg: { ...arg(itemId), name: 'renamed-items' } },
    target: () => ({ id: () => id, data: (key) => key === 'type' ? 'fn' : false }),
  });
  ctx.gv = { nodes: () => [], edges: () => [
    edge('first-use', 'item-zero'), edge('second-use', 'item-zero'),
    edge('other-item', 'item-one'), edge('unchanged', 'unchanged-item'),
  ] };
  vm.runInContext(fs.readFileSync(path.join(EDITOR, 'editor-diff-ghost.js'), 'utf8'), ctx);
  const wants = ctx._gdGhostWants();
  assert.equal(wants.length, 3, 'Two uses and a different item each keep their own ghost');
  assert.deepEqual(Array.from(wants, (want) => want.ref), ['new-zero', 'new-zero', 'new-one']);
  assert.equal(wants[2].slot, 'renamed-items[1]', 'A rename changes the label, never item matching');
  assert.match(ctx.gdDiffDetailSummary(ctx.gdDiffArgDetails(arg('item-one'))), /new-one/);
  assert.doesNotMatch(ctx.gdDiffDetailSummary(ctx.gdDiffArgDetails(arg('item-one'))), /new-zero/);

  // Reveal a changed middle ancestor without selecting a different graph root.
  const expanded = new Map([['independent-root', { fullDepth: 1 }]]);
  ctx.expansionState = expanded;
  ctx.savedUserPositions = new Map();
  ctx.getInheritanceLevels = () => [['leaf'], ['middle'], ['owner'], ['above'], ['base']];
  let rendered = 0;
  ctx.renderGraph = () => { rendered += 1; };
  vm.runInContext(fs.readFileSync(path.join(EDITOR, 'editor-diff-inspector.js'), 'utf8'), ctx);
  ctx.gdDiffRevealVia('leaf-card', 'leaf', 'owner');
  assert.equal(expanded.get('leaf-card').fullDepth, 2, 'A middle change expands only as far as needed');
  assert.equal(expanded.get('independent-root').fullDepth, 1, 'The independent graph keeps its expansion');
  assert.equal(rendered, 1);
  const selectedAncestors = [];
  ctx.gdInspectorRender = (id) => selectedAncestors.push(id);
  const ancestorBadge = ctx.gdDiffAncestorBadgeEl('owner');
  ancestorBadge.dispatchEvent({ type: 'click', stopPropagation() {} });
  assert.deepEqual(selectedAncestors, ['owner'], 'The exact expanded ancestor opens its own diff');
  assert.equal(ctx.gdDiffAncestorBadgeEl('unchanged'), null);
  console.log('compare identity and refresh: PASS');
})().catch((error) => { console.error(error); process.exitCode = 1; });

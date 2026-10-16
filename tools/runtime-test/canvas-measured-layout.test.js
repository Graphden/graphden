'use strict';

// Exercise the actual shared grid correction with independently supplied DOM
// measurements. Browser tests prove the measurements themselves and hit zones.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const editor = path.join(__dirname, '../../resources/packages/app/editor');
const nodes = new Map();
const edges = new Map();
const grid = {source: {col: 0, row: 0}, first: {col: 1, row: 0}, second: {col: 1, row: 1}, third: {col: 1, row: 2}};
const layout = new Map();
const userMovedNodes = new Set();
const ctx = vm.createContext({console, window: {}, GRID_GAP_X: 80, GRID_GAP_Y: 40,
  SEQ_FAN_GAP: 14, graph: {nodes, edges}, userMovedNodes,
  isSeqTrunkId: id => id.startsWith('seq:'),
  gv: {ready: () => true, node: id => nodes.get(id)},
});
for (const file of ['editor-layout.js', 'editor-overlay-manager.js']) {
  vm.runInContext(fs.readFileSync(path.join(editor, file), 'utf8'), ctx, {filename: file});
}
ctx.grid = grid;
vm.runInContext('_lastGridPos = grid; typographyScale = () => 1;', ctx);
const cards = [];
function card(id, type, intrinsic, height) {
  const data = {type, layoutWidth: 80, layoutHeight: 200};
  const node = {data: key => key === undefined ? data : data[key],
    width: () => data.layoutWidth, height: () => data.layoutHeight,
    setWidth: width => { data.layoutWidth = width; },
    setHeight: value => { data.layoutHeight = value; },
  };
  const overlay = {dataset: {nodeId: id}, style: {width: '80px', minHeight: '200px'}, intrinsic, naturalHeight: height};
  Object.defineProperties(overlay, {
    offsetWidth: {get() {
      assert(cards.every(item => item.style.minHeight === ''), 'all stale height constraints cleared before reads');
      return overlay.style.width === 'max-content' ? overlay.intrinsic : parseFloat(overlay.style.width);
    }},
    offsetHeight: {get() {
      assert(cards.every(item => item.style.width !== 'max-content'), 'all final widths restored before height reads');
      return overlay.naturalHeight;
    }},
  });
  cards.push(overlay);
  nodes.set(id, node);
  layout.set(id, {x: 40, y: 100, width: 80, height: 200});
  ctx.registerNodeOverlay(overlay);
  return overlay;
}
const source = card('source', 'fn', 900, 30);
const first = card('first', 'arg', 240, 45);
const second = card('second', 'arg', 70, 25);
card('third', 'arg', 70, 25);
edges.set('ordinary', {sourceId: 'source', targetId: 'first', data: {}});
const label = {dataset: {edgeId: 'ordinary'}, offsetWidth: 310, offsetHeight: 120};
ctx.registerEdgeOverlay(label);
assert(ctx.reflowFromMeasuredHeights(layout));
assert.equal(nodes.get('source').width(), 80, 'fn width contract stays fixed');
assert.equal(nodes.get('first').width(), 240, 'arg width uses intrinsic DOM size');
assert.equal(nodes.get('first').height(), 45, 'actual card height excludes label envelope');
assert.equal(layout.get('first').y, 60, 'incoming history reserves row envelope');
assert.equal(layout.get('second').y, 172.5, 'next row clears full history height plus native gap');
assert.equal(layout.get('first').x - 120 - nodes.get('source').data().colRightX, 354,
  'column gap contains bend/post-bend/label/target with exact native clearances');
assert.equal(ctx.reflowFromMeasuredHeights(layout), false, 'unchanged measurements converge');
first.intrinsic = 60;
first.naturalHeight = 20;
label.offsetWidth = 40;
label.offsetHeight = 18;
assert(ctx.reflowFromMeasuredHeights(layout));
assert.equal(nodes.get('first').width(), 60, 'custom to builtin shrinks width');
assert.equal(nodes.get('first').height(), 20, 'custom to builtin shrinks natural height');
assert.equal(layout.get('second').y, 82.5, 'history envelope shrinks too');
// A sequence label is centred over members. Conservatively reserve its height
// in each target row, without changing actual node footprints.
ctx.registerEdgeOverlay({dataset: {edgeId: 'seq:group', seqGroup: 'group'}, offsetWidth: 220, offsetHeight: 90});
edges.set('member1', {id: 'member1', sourceId: 'source', targetId: 'first', data: {seqGroup: 'group'}});
edges.set('member2', {id: 'member2', sourceId: 'source', targetId: 'second', data: {seqGroup: 'group'}});
edges.set('member3', {id: 'member3', sourceId: 'source', targetId: 'third', data: {seqGroup: 'group'}});
assert(ctx.reflowFromMeasuredHeights(layout));
assert.equal(layout.get('first').y, 45);
assert.equal(layout.get('second').y, 175);
assert.equal(nodes.get('second').height(), 25);
assert.equal(ctx.reflowFromMeasuredHeights(layout), false);
const controls = {dataset: {edgeId: 'member2'}, offsetWidth: 62, offsetHeight: 14};
ctx.registerEdgeOverlay(controls);
for (const size of [62, 118]) {
  controls.offsetWidth = size;
  ctx.reflowFromMeasuredHeights(layout);
  const sourceRight = nodes.get('source').data().colRightX;
  const trunkRight = sourceRight + 20 + 18 + 220;
  const targetLeft = layout.get('second').x - nodes.get('second').width() / 2;
  assert.equal((layout.get('first').y + layout.get('third').y) / 2, layout.get('second').y,
    'trunk centre shares the middle member controls row');
  const controlsLeft = targetLeft - 6 - size;
  assert(controlsLeft - trunkRight >= 14, 'sequence trunk and actual member controls clear native fan gap');
  assert.equal(ctx.reflowFromMeasuredHeights(layout), false, 'measured control sizes converge');
}
userMovedNodes.add('first');
layout.get('first').x = 777;
layout.get('first').y = 888;
assert.equal(ctx.reflowFromMeasuredHeights(layout), false, 'dragged position is not a perpetual geometry invalidation');
vm.runInContext(fs.readFileSync(path.join(editor, 'editor-render.js'), 'utf8'), ctx);
nodes.get('first').x = 777;
nodes.get('first').y = 888;
ctx.settleNodes(layout);
assert.equal(nodes.get('first').x, 777, 'actual settle preserves user-dragged x');
assert.equal(nodes.get('first').y, 888, 'actual settle preserves user-dragged y');
// A placeholder has no measured card, but retains its native footprint even
// when a personal edge label is shorter than the fixed placeholder target.
grid.tail = {col: 1, row: 3};
const tailData = {isPlaceholder: true};
nodes.set('tail', {data: key => key === undefined ? tailData : tailData[key],
  width: () => 20, height: () => 20, setWidth() {}, setHeight() {}});
layout.set('tail', {x: 0, y: 0, width: 20, height: 20});
edges.set('tail-edge', {id: 'tail-edge', sourceId: 'source', targetId: 'tail', data: {}});
ctx.registerEdgeOverlay({dataset: {edgeId: 'tail-edge'}, offsetWidth: 8, offsetHeight: 5});
ctx.reflowFromMeasuredHeights(layout);
assert.equal(layout.get('tail').y, 400, 'placeholder row reserves native height floor');
assert.equal(source.style.width, '80px');
assert.equal(second.style.width, '70px');
console.log('PASS canvas measured grid: widths, gaps, row envelopes, shrink, sequence and convergence');

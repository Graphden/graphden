'use strict';

// Deterministic frame scheduling proves old navigation cannot overwrite a newer
// navigation, a manual pan/zoom, or an interrupt issued by a viewport listener.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-viewport.js'), 'utf8');

function boot(reduced = false) {
  let now = 0;
  let sequence = 0;
  const frames = new Map();
  const context = vm.createContext({
    document: {getElementById: () => ({clientWidth: 800, clientHeight: 600})},
    performance: {now: () => now},
    matchMedia: () => ({matches: reduced}),
    requestAnimationFrame: fn => {frames.set(++sequence, fn); return sequence;},
    cancelAnimationFrame: id => frames.delete(id),
  });
  vm.runInContext(source, context);
  return {
    run: code => vm.runInContext(code, context),
    pan: () => JSON.parse(vm.runInContext('JSON.stringify(viewport.pan)', context)),
    frames,
    advance(time) {
      now = time;
      const pending = [...frames.values()];
      frames.clear();
      pending.forEach(fn => fn(time));
    },
  };
}

{
  const view = boot();
  view.run('animateViewportTo({x: 100, y: 100}, 100)');
  const stale = [...view.frames.values()][0];
  view.advance(40);
  view.run('animateViewportTo({x: 250, y: 200}, 100)');
  stale(80); // Even a cancelled callback already queued by the browser is inert.
  view.advance(140);
  assert.deepEqual(view.pan(), {x: 150, y: 100});
  assert.equal(view.frames.size, 0);
}
for (const interrupt of ['setViewportPan(12, 34)', 'setViewportZoom(2)', 'setViewportTransform(1.5, 45, 67)']) {
  const view = boot();
  view.run('animateViewportTo({x: 100, y: 100}, 100)');
  view.advance(20);
  const stale = [...view.frames.values()][0];
  view.run(interrupt);
  const stopped = view.pan();
  stale(100);
  view.advance(200);
  assert.deepEqual(view.pan(), stopped);
  assert.equal(view.frames.size, 0);
}
{
  const view = boot();
  view.run('onViewportChanged(() => setViewportTransformOnce()); let interrupted = false; function setViewportTransformOnce() { if (!interrupted) { interrupted = true; setViewportPan(7, 9); } }');
  view.run('animateViewportTo({x: 100, y: 100}, 100)');
  view.advance(20);
  assert.deepEqual(view.pan(), {x: 7, y: 9});
  assert.equal(view.frames.size, 0);
}
for (const duration of ['0', '-1', 'NaN', 'Infinity', 'undefined']) {
  const view = boot();
  view.run(`animateViewportTo({x: 100, y: 100}, ${duration})`);
  assert.deepEqual(view.pan(), {x: 300, y: 200});
  assert.equal(view.frames.size, 0);
}
{
  const view = boot(true);
  view.run('animateViewportTo({x: 100, y: 100}, 1000)');
  assert.deepEqual(view.pan(), {x: 300, y: 200});
  assert.equal(view.frames.size, 0);
}
console.log('PASS viewport motion: replacement, interruption, reduced motion and immediate durations');

// A bottom-sheet Inspector covers the canvas; its animation must not make
// the fitted area transiently larger. Desktop grid panels are already excluded.
{
  const classes = new Set(['gd-insp-open']);
  const surface = {clientWidth: 744, clientHeight: 724,
    getBoundingClientRect: () => ({top: 44})};
  const inspector = {offsetHeight: 314};
  const context = vm.createContext({window: {innerHeight: 768},
    document: {body: {classList: {contains: name => classes.has(name)}},
      getElementById: id => id === 'graph-container' ? surface
        : id === 'gd-inspector' ? inspector
        : id === 'app' ? {classList: {contains: () => true}} : null},
    getComputedStyle: () => ({position: 'fixed'})});
  vm.runInContext(source, context);
  context.viewportContainer = () => surface;
  assert.equal(context.visibleGraphRect().bottom, 410, 'sheet consumes its final height even during entrance');
  classes.clear();
  assert.equal(context.visibleGraphRect().bottom, 724, 'closed sheet does not consume canvas');
  classes.add('gd-insp-open');
  context.getComputedStyle = () => ({position: 'static'});
  assert.equal(context.visibleGraphRect().bottom, 724, 'desktop Inspector is already outside the canvas');
  context.getComputedStyle = () => ({position: 'fixed'});
  context.graphBoundingBox = () => ({x1: 0, y1: 0, x2: 400, y2: 200, w: 400, h: 200});
  context.clampZoom = z => z;
  let fitted;
  context.setViewportTransform = (zoom, x, y) => { fitted = {zoom, x, y}; };
  vm.runInContext(fs.readFileSync(path.join(__dirname,
    '../../resources/packages/app/editor/editor-render.js'), 'utf8'), context);
  context.fitInVisibleArea(50);
  assert(fitted.y + 200 * fitted.zoom <= 410 - 50, 'fitted graph ends above the sheet with padding');
  inspector.offsetHeight = 768;
  fitted = null;
  context.fitInVisibleArea(50);
  assert.equal(fitted, null, 'a fully covered canvas does not fit into fabricated space');
  inspector.offsetHeight = 314;
  const originalLookup = context.document.getElementById;
  context.document.getElementById = id => id === 'side-menu'
    ? {getBoundingClientRect: () => ({width: 280})}
    : id === 'app' ? {classList: {contains: () => false}} : originalLookup(id);
  assert.equal(context.visibleGraphRect().left, 280, 'legacy sidebar is counted exactly once');
  context.document.getElementById = originalLookup;
  assert.equal(context.visibleGraphRect().left, 0, 'redesign grid already excludes sidebar width');
}

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

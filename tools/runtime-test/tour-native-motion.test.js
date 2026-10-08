// Cancellable native motion follows only the tutorial's relevant controls;
// no interval scans, idle frames, or subscriptions survive disposal.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.join(__dirname, '../../resources/packages');

function harness() {
  const events = new EventTarget();
  const frames = new Map();
  let nextFrame = 0;
  let positions = 0;
  const target = {isConnected: true, closest: () => null};
  const ancestor = {isConnected: true, contains: el => el === target};
  const layer = () => ({closest: () => null});
  const ctx = vm.createContext({
    AbortController, performance: {now: () => 0},
    CustomEvent: class extends Event { constructor(type, options) { super(type, options); this.detail = options.detail; } },
    document: {body: {}, querySelector: () => target, querySelectorAll: () => [target],
      addEventListener: events.addEventListener.bind(events)},
    window: {addEventListener: events.addEventListener.bind(events)},
    MutationObserver: class {observe() {} disconnect() {}},
    requestAnimationFrame: cb => {frames.set(++nextFrame, cb); return nextFrame;},
    cancelAnimationFrame: id => frames.delete(id),
    _tourEls: {dim: layer(), spot: layer(), pop: layer()}, _tourState: {},
    _tourStep: () => ({target: '#target', targets: ['#target']}), _tourEffTarget: () => '#target',
  });
  vm.runInContext(fs.readFileSync(path.join(root, 'web/runtime/graphden-popover.js'), 'utf8'), ctx);
  vm.runInContext(fs.readFileSync(path.join(root, 'app/editor/editor-tour-spot.js'), 'utf8'), ctx);
  ctx._tourPosition = () => positions++;
  const frame = () => {const queued = [...frames.values()]; frames.clear(); queued.forEach(cb => cb());};
  function motion(el = ancestor, duration = 100) {
    let finish, cancel;
    const animation = {pending: false, playState: 'running',
      effect: {target: el, getComputedTiming: () => ({endTime: duration})},
      finished: new Promise((resolve, reject) => {finish = resolve; cancel = reject;})};
    el.animate = () => animation;
    el.dispatchEvent = events.dispatchEvent.bind(events);
    ctx.animateWithGeometry(el, [], {duration});
    return {animation, finish: () => {animation.playState = 'finished'; finish();},
      cancel: () => {animation.playState = 'idle'; cancel();}};
  }
  ctx._tourStartPositioning(); frame();
  return {ctx, frame, frames, motion, target, ancestor, positions: () => positions};
}

(async () => {
  const h = harness();
  assert.equal(h.frames.size, 0, 'idle observer schedules no frames');
  const moving = h.motion();
  h.frame(); h.frame();
  assert.equal(h.positions(), 3, 'native ancestor motion follows each frame without CSS events');
  moving.finish(); await Promise.resolve(); h.frame();
  assert.equal(h.frames.size, 0, 'finished animation stops geometry work');
  const long = h.motion(h.ancestor, 4000); h.frame(); h.frame();
  assert.equal(h.frames.size, 1, 'long finite motion remains active beyond the CSS duration eligibility');
  long.animation.playState = 'paused'; h.frame();
  assert.equal(h.frames.size, 0, 'paused native motion stops requesting frames');
  const cancelled = h.motion(); h.frame();
  cancelled.cancel(); await Promise.resolve(); h.frame();
  assert.equal(h.frames.size, 0, 'cancel follows final geometry and stops');
  h.motion({isConnected: true, contains: () => false});
  assert.equal(h.frames.size, 0, 'unrelated animation does not wake spotlight');
  h.motion(h.ancestor, Infinity);
  assert.equal(h.frames.size, 0, 'infinite decorative motion does not schedule frames');
  const zero = h.motion(h.ancestor, 0); zero.finish(); await Promise.resolve(); h.frame();
  assert.equal(h.frames.size, 0, 'zero-duration reduced motion gets final placement without a loop');
  h.motion(); h.ancestor.isConnected = false; h.frame();
  assert.equal(h.frames.size, 0, 'detached animation owner stops following');
  h.ancestor.isConnected = true;
  const disposed = h.motion();
  h.ctx._tourStopFollowing();
  assert.equal(h.frames.size, 0, 'dispose cancels queued frame');
  const before = h.positions();
  disposed.finish(); await Promise.resolve(); h.frame(); h.motion();
  assert.equal(h.positions(), before, 'late completion and new motion cannot revive disposed observer');
  assert.equal(h.frames.size, 0);
  console.log('✓ native spotlight motion: relevant ancestor, finish, cancel, zero, infinite, detach, dispose');
})().catch(error => {console.error(error); process.exitCode = 1;});

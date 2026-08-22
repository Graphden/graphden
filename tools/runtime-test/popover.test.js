// `graphden-popover.js` — placement and dismissal, the two pieces every
// popover in the editor AND in a graph-composed user page is built from.
//
// Both are pure decision logic wearing DOM clothes: `anchorBelowClamped` is
// arithmetic over a rect and a viewport, `installPopoverDismiss` is a set of
// rules about which pointerdown counts as "outside". Neither had a test —
// the first was covered only incidentally by whatever e2e specs happened to
// open a popover near an edge (none reliably do), and the second's rules are
// the kind that regress silently: the popover simply stops closing, or
// closes when you click its own trigger.
//
// Run:  node tools/runtime-test/popover.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const RUNTIME = path.join(__dirname, '..', '..', 'resources', 'packages', 'web', 'runtime');

let failures = 0;
let passes = 0;

function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  failures += 1;
  console.error('  ✗ ' + msg);
}

function test(name, fn) {
  console.log(' ' + name);
  try { fn(); } catch (e) { failures += 1; console.error('  ✗ threw: ' + e.message); }
}

// A viewport plus a document whose listeners we can fire by hand.
function makeCtx(viewport) {
  const document = createDocument();
  const listeners = {};
  const doc = Object.assign(document, {
    addEventListener(type, fn) { (listeners[type] = listeners[type] || []).push(fn); },
  });
  const ctx = vm.createContext({
    console,
    document: doc,
    window: { innerWidth: viewport.w, innerHeight: viewport.h },
  });
  vm.runInContext(fs.readFileSync(path.join(RUNTIME, 'graphden-popover.js'), 'utf8'),
                  ctx, { filename: 'graphden-popover.js' });
  return { ctx, document, fire: (type, event) => {
    for (const fn of listeners[type] || []) fn(event);
  } };
}

// A popover of a fixed size, and an anchor at a fixed place.
function sized(document, w, h) {
  const el = document.createElement('div');
  el.offsetWidth = w;
  el.offsetHeight = h;
  return el;
}

function anchoredAt(document, rect) {
  const el = document.createElement('button');
  el.getBoundingClientRect = () => rect;
  return el;
}

const px = (s) => parseInt(String(s).replace('px', ''), 10);


test('a popover with room below is placed below, aligned to the anchor', () => {
  const { ctx, document } = makeCtx({ w: 1000, h: 800 });
  const el = sized(document, 280, 120);
  ctx.anchorBelowClamped(el, anchoredAt(document, { left: 100, top: 40, bottom: 60 }));
  assert(px(el.style.left) === 100, 'left edge follows the anchor, got ' + el.style.left);
  assert(px(el.style.top) === 68, 'top is anchor bottom + 8px margin, got ' + el.style.top);
  assert(el.style.display === 'block', 'the popover is shown');
});


test('no room below → it flips above the anchor', () => {
  const { ctx, document } = makeCtx({ w: 1000, h: 400 });
  const el = sized(document, 280, 120);
  // Anchor near the bottom: 380 + 8 + 120 > 400, so below does not fit.
  ctx.anchorBelowClamped(el, anchoredAt(document, { left: 100, top: 360, bottom: 380 }));
  assert(px(el.style.top) === 232,
         'flipped to anchor top − height − margin (360-120-8), got ' + el.style.top);
});


test('no room either way → it clamps to the top margin rather than off-screen', () => {
  const { ctx, document } = makeCtx({ w: 1000, h: 200 });
  const el = sized(document, 280, 300);
  ctx.anchorBelowClamped(el, anchoredAt(document, { left: 100, top: 150, bottom: 170 }));
  assert(px(el.style.top) === 8,
         'a popover taller than the viewport starts at the margin, not at a '
         + 'negative offset — got ' + el.style.top);
});


test('an anchor near the right edge pulls the popover back on-screen', () => {
  const { ctx, document } = makeCtx({ w: 1000, h: 800 });
  const el = sized(document, 280, 120);
  ctx.anchorBelowClamped(el, anchoredAt(document, { left: 900, top: 40, bottom: 60 }));
  assert(px(el.style.left) === 712,
         'clamped to viewport width − width − margin (1000-280-8), got ' + el.style.left);
});


test('a viewport narrower than the popover clamps to the left margin', () => {
  const { ctx, document } = makeCtx({ w: 200, h: 800 });
  const el = sized(document, 280, 120);
  ctx.anchorBelowClamped(el, anchoredAt(document, { left: 150, top: 40, bottom: 60 }));
  assert(px(el.style.left) === 8,
         'a phone-width viewport still puts the popover on screen, got ' + el.style.left);
});


test('an unmeasurable popover falls back to the caller\'s guess', () => {
  // offsetWidth/Height are 0 before layout. Without the fallback the clamp
  // maths would treat the popover as a point and let it hang off-screen.
  const { ctx, document } = makeCtx({ w: 1000, h: 800 });
  const el = document.createElement('div');
  el.offsetWidth = 0;
  el.offsetHeight = 0;
  ctx.anchorBelowClamped(el, anchoredAt(document, { left: 900, top: 40, bottom: 60 }),
                         { fallbackW: 400, fallbackH: 100 });
  assert(px(el.style.left) === 592,
         'clamped using fallbackW (1000-400-8), got ' + el.style.left);
});


// --- dismissal ---------------------------------------------------------------

function popoverUnderTest(viewport) {
  const { ctx, document, fire } = makeCtx(viewport || { w: 1000, h: 800 });
  const el = document.createElement('div');
  const inside = document.createElement('span');
  el.appendChild(inside);
  const anchor = document.createElement('button');
  const anchorChild = document.createElement('span');
  anchor.appendChild(anchorChild);
  const outside = document.createElement('div');
  const state = { visible: true, dismissed: 0 };
  ctx.installPopoverDismiss({
    getEl: () => el,
    getAnchor: () => anchor,
    isVisible: () => state.visible,
    onDismiss: () => { state.dismissed += 1; },
  });
  return { fire, el, inside, anchor, anchorChild, outside, state };
}


test('a pointerdown outside closes the popover', () => {
  const p = popoverUnderTest();
  p.fire('pointerdown', { target: p.outside });
  assert(p.state.dismissed === 1, 'dismissed once, got ' + p.state.dismissed);
});


test('a pointerdown inside does not', () => {
  const p = popoverUnderTest();
  p.fire('pointerdown', { target: p.inside });
  assert(p.state.dismissed === 0, 'clicking your own content must not close you');
});


test('a pointerdown on the trigger does not — it re-opens instead', () => {
  // Without the anchor exemption, clicking the trigger of an open popover
  // dismisses it here and re-opens it in the trigger's own handler, so the
  // popover flickers instead of toggling.
  const p = popoverUnderTest();
  p.fire('pointerdown', { target: p.anchorChild });
  assert(p.state.dismissed === 0, 'the anchor subtree is exempt');
});


test('a hidden popover ignores everything', () => {
  const p = popoverUnderTest();
  p.state.visible = false;
  p.fire('pointerdown', { target: p.outside });
  p.fire('keydown', { key: 'Escape', preventDefault() {} });
  assert(p.state.dismissed === 0,
         'handlers are inert while hidden — they are installed once at module '
         + 'load and must cost nothing until the popover opens');
});


test('Escape closes it, and is marked as consumed', () => {
  const p = popoverUnderTest();
  let prevented = 0;
  p.fire('keydown', { key: 'Escape', preventDefault() { prevented += 1; } });
  assert(p.state.dismissed === 1, 'Escape dismisses');
  assert(prevented === 1,
         'preventDefault marks the key consumed — the interactive tutorial reads '
         + 'an unconsumed Escape as "the reader quit"');
});


test('other keys are left alone', () => {
  const p = popoverUnderTest();
  let prevented = 0;
  for (const key of ['Enter', 'Tab', 'a', 'ArrowDown']) {
    p.fire('keydown', { key, preventDefault() { prevented += 1; } });
  }
  assert(p.state.dismissed === 0, 'no other key dismisses');
  assert(prevented === 0, 'and no other key is swallowed');
});


console.log(failures === 0
  ? '✓ popover — ' + passes + ' assertions'
  : '✗ popover — ' + failures + ' failed of ' + (passes + failures));
process.exit(failures === 0 ? 0 : 1);

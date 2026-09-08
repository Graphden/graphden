// `editor-shortcuts.js` — keyboard LAYOUTS: a `{id: {keys, leader}}`
// override map re-resolves every registered binding, a late registration
// lands on the user's keys, the defaults are remembered for reset, and the
// raw entry list Settings → Keyboard renders from carries both.
//
// Run:  node tools/runtime-test/keymap.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');

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

function makeCtx() {
  const base = createDocument();
  const winListeners = {};
  const nativeCreate = base.createElement;
  const create = (tag) => {
    const node = nativeCreate(tag);
    node.replaceChildren = function replaceChildren() { this.children.length = 0; };
    return node;
  };
  const doc = Object.assign(base, {
    addEventListener() {},
    activeElement: null,
    getElementById: () => null,
    querySelector: () => null,
    querySelectorAll: () => [],
    readyState: 'complete',
    createElement: create,
    body: create('body'),
  });
  const win = { addEventListener(type, fn) { (winListeners[type] = winListeners[type] || []).push(fn); } };
  const ctx = vm.createContext({
    console, document: doc, window: win, requestAnimationFrame: (fn) => fn(),
    focusSafely: () => true, navZoom: () => {}, navResetZoom: () => {}, navGoToRoot: () => {},
    navResetPositions: () => {}, installTabTrap: () => {}, setSiblingsInert: () => {},
    focusIntoDialog: () => {}, returnFocusTo: () => {},
  });
  vm.runInContext(fs.readFileSync(path.join(EDITOR, 'editor-shortcuts.js'), 'utf8'), ctx, { filename: 'editor-shortcuts.js' });
  const press = (key) => {
    const e = { key, shiftKey: false, metaKey: false, ctrlKey: false, altKey: false, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
    for (const fn of winListeners.keydown || []) fn(e);
    return e;
  };
  return { ctx, press };
}

test('an override re-keys a registered binding and the old sequence goes dead', () => {
  const { ctx, press } = makeCtx();
  let fit = 0;
  ctx.window.registerShortcut({ id: 'probe-fit', keys: 'p f', group: 'T', description: 'fit', run: () => { fit += 1; } });
  press(' '); press('p'); press('f');
  assert(fit === 1, 'default sequence fires, fit=' + fit);
  ctx.window.gdApplyKeymap({ 'probe-fit': { keys: 'z z', leader: true } });
  press(' '); press('p'); press('f');
  assert(fit === 1, 'old sequence no longer fires, fit=' + fit);
  press(' '); press('z'); press('z');
  assert(fit === 2, 'new sequence fires, fit=' + fit);
});

test('an override may move a binding between bare and leader', () => {
  const { ctx, press } = makeCtx();
  let n = 0;
  ctx.window.registerShortcut({ id: 'probe-bare', keys: 'q', leader: false, group: 'T', description: 'bare', run: () => { n += 1; } });
  press('q');
  assert(n === 1, 'bare fires');
  ctx.window.gdApplyKeymap({ 'probe-bare': { keys: 'q', leader: true } });
  press('q');
  assert(n === 1, 'bare no longer fires once behind the leader');
  press(' '); press('q');
  assert(n === 2, 'leader + q fires');
});

test('a registration AFTER the layout is applied lands on the user keys', () => {
  const { ctx, press } = makeCtx();
  ctx.window.gdApplyKeymap({ late: { keys: 'l l', leader: true } });
  let n = 0;
  ctx.window.registerShortcut({ id: 'late', keys: 'g g', group: 'T', description: 'late', run: () => { n += 1; } });
  press(' '); press('l'); press('l');
  assert(n === 1, 'late registration honours the override, n=' + n);
});

test('the entry list carries defaults; reset (null) restores them', () => {
  const { ctx, press } = makeCtx();
  let n = 0;
  ctx.window.registerShortcut({ id: 'probe-e', keys: 'p e', group: 'T', description: 'entry', run: () => { n += 1; } });
  ctx.window.gdApplyKeymap({ 'probe-e': { keys: 'x', leader: false } });
  const e = ctx.window.gdShortcutEntries().find((s) => s.id === 'probe-e');
  assert(e && e.keys === 'x' && e.leader === false, 'entry shows the override');
  assert(e.defaultKeys === 'p e' && e.defaultLeader === true, 'entry remembers the default');
  ctx.window.gdApplyKeymap(null);
  press(' '); press('p'); press('e');
  assert(n === 1, 'default sequence fires again after reset');
  const back = ctx.window.gdShortcutEntries().find((s) => s.id === 'probe-e');
  assert(back.keys === 'p e' && back.leader === true, 'entry back to the default');
});

test('the which-key footer names the bare keys off the registry', () => {
  const { ctx, press } = makeCtx();
  ctx.window.gdApplyKeymap({ search: { keys: ';', leader: false } });
  press(' ');
  const foot = ctx.document.body.children.flatMap((c) => c.children || []).find((c) => c.className === 'gd-which-key-foot')
    || ctx.document.body.children.find((c) => c.id === 'gd-which-key')?.children?.find((c) => c.className === 'gd-which-key-foot');
  assert(foot && /; search/.test(foot.textContent), 'footer shows the rebound search key: ' + (foot && foot.textContent));
});

console.log(failures ? `\n${failures} failed, ${passes} passed` : `\nall ${passes} passed`);
process.exit(failures ? 1 : 0);

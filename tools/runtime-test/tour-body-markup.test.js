// `_tourRenderBody` / `_tourEffTarget` (editor-tour.js) — the step-prose
// inline marks and the multi-stage spotlight chain.
//
// [[Label]] must become a keycap chip, `text` a click-to-copy chip whose
// click lands the exact payload in the clipboard, and everything else must
// pass through verbatim — a body with no marks renders exactly as before.
// `_tourEffTarget` must ring the DEEPEST visible stage of a `:targets`
// chain and fall back to `:target` when nothing deeper is on screen.
//
// Run:  node tools/runtime-test/tour-body-markup.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const TOUR = path.join(__dirname, '..', '..', 'resources', 'packages',
                       'app', 'editor', 'editor-tour.js');

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

function tourCtx() {
  const document = createDocument();
  const copied = [];
  const ctx = vm.createContext({
    console,
    document,
    window: { innerWidth: 1200, innerHeight: 800 },
    navigator: { clipboard: { writeText: (t) => { copied.push(t); return Promise.resolve(); } } },
    setTimeout: () => 0,
    clearInterval: () => {},
    setInterval: () => 0,
    localStorage: { getItem: () => null, setItem: () => {}, removeItem: () => {} },
  });
  vm.runInContext(fs.readFileSync(TOUR, 'utf8'), ctx, { filename: 'editor-tour.js' });
  return { ctx, document, copied };
}

const flatten = (el) => el.children.map((c) => {
  if (c.tagName === undefined) return { text: c.textContent };
  return { tag: c.tagName, cls: c.className, text: c.textContent };
});

test('[[Label]] renders a keycap chip, prose stays text nodes', () => {
  const { ctx, document } = tourCtx();
  const host = document.createElement('div');
  ctx._tourRenderBody(host, 'Click the [[+]] on :value — then [[Save]].');
  const parts = flatten(host);
  assert(parts.length === 5, 'five parts (2 chips, 3 text runs), got ' + parts.length);
  assert(parts[1].cls === 'gd-tour-ui' && parts[1].text === '+', '[[+]] chip');
  assert(parts[3].cls === 'gd-tour-ui' && parts[3].text === 'Save', '[[Save]] chip');
  assert(host.textContent === 'Click the + on :value — then Save.',
         'visible text drops only the markers');
});

test('`text` renders a copy chip that copies the exact payload', async () => {
  const { ctx, document, copied } = tourCtx();
  const host = document.createElement('div');
  const payload = '{"status": 200, "body": "Hello!"}';
  ctx._tourRenderBody(host, 'enter this JSON: `' + payload + '` — then [[Save]].');
  const btn = host.children.find((c) => c.className === 'gd-tour-copy');
  assert(btn && btn.tagName === 'BUTTON', 'copy chip is a button');
  assert(btn.children[0].textContent === payload, 'chip shows the payload verbatim');
  btn.click();
  await Promise.resolve();
  await Promise.resolve();
  assert(copied.length === 1 && copied[0] === payload, 'click copies the exact payload');
  assert(btn.children[1].textContent === '✓', 'icon flips to ✓ after the copy');
});

test('a body with no marks renders verbatim (kept “quotes” included)', () => {
  const { ctx, document } = tourCtx();
  const host = document.createElement('div');
  const body = 'The popover states “side effects: env” — and the Run button is DISABLED.';
  ctx._tourRenderBody(host, body);
  assert(host.textContent === body, 'verbatim pass-through');
  assert(host.children.every((c) => c.tagName === '#text'), 'no chips created');
});

test('_tourEffTarget follows the deepest visible stage, else :target', () => {
  const { ctx, document } = tourCtx();
  const visible = new Set(['#a']);
  const el = { getBoundingClientRect: () => ({ width: 10, height: 10, top: 5, bottom: 15, left: 5, right: 15 }) };
  document.querySelector = (sel) => (visible.has(sel) ? el : null);
  const step = { target: '#a', targets: ['#a', '#menu-item', '#deep'] };
  assert(ctx._tourEffTarget(step) === '#a', 'only the base stage visible → base');
  visible.add('#menu-item');
  assert(ctx._tourEffTarget(step) === '#menu-item', 'menu opened → its stage wins');
  visible.add('#deep');
  assert(ctx._tourEffTarget(step) === '#deep', 'deepest visible stage wins');
  visible.clear();
  assert(ctx._tourEffTarget(step) === '#a', 'nothing visible → falls back to :target');
  assert(ctx._tourEffTarget({ target: '#t' }) === '#t', 'no chain → :target as before');
});

(async () => {
  // test() bodies are sync except the copy one — give its microtasks a turn.
  await new Promise((r) => process.nextTick(r));
  console.log(failures ? `FAIL: ${failures} failed, ${passes} passed`
                       : `PASS: ${passes} assertions`);
  process.exit(failures ? 1 : 0);
})();

'use strict';

// editor-keymap.js — Settings → Keyboard's "Change" recorder. Pinned:
//   * the keys typed show up in the row being recorded (the recorder used to
//     hold the row from BEFORE its own re-render, a detached node);
//   * recording ends when the user leaves the Settings surface — the next key
//     is not swallowed, and a stray Enter saves nothing;
//   * a pointerdown outside the row ends it too.
// Runs under node's vm over mini-dom; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument, MiniElement } = require('./mini-dom');

const SRC = fs.readFileSync(path.join(__dirname, '..', '..', 'resources', 'packages',
  'app', 'editor', 'editor-keymap.js'), 'utf8');

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}

MiniElement.prototype.focus = function focus() {};

function boot() {
  const doc = createDocument();
  doc.body.setAttribute('data-surface', 'settings');
  const root = doc.createElement('div');
  root.id = 'gd-keymap-root';
  doc.body.appendChild(root);
  const winListeners = {};
  const writes = [];
  const win = {
    addEventListener(t, fn) { (winListeners[t] = winListeners[t] || []).push(fn); },
    removeEventListener(t, fn) { winListeners[t] = (winListeners[t] || []).filter((f) => f !== fn); },
    gdShortcutEntries: () => [{ id: 'find', group: 'Go', description: 'Find', keys: 'f', leader: true,
                                defaultKeys: 'f', defaultLeader: true, active: true }],
    gdPrefRead: () => null,
    gdPrefWrite: (k, v) => writes.push([k, v]),
    gdApplyKeymap() {},
  };
  const ctx = vm.createContext({ console, document: doc, window: win });
  vm.runInContext(SRC, ctx);
  win.gdRenderKeymapPane();
  const fire = (type, ev) => {
    const e = Object.assign({ defaultPrevented: false, preventDefault() { this.defaultPrevented = true; },
                              stopPropagation() {} }, ev);
    for (const fn of [...(winListeners[type] || [])]) fn(e);
    return e;
  };
  const change = () => doc.querySelector('.gd-km-change').click();
  const recording = () => doc.querySelector('.gd-km-recording');
  return { doc, root, writes, fire, change, recording };
}

console.log(' typed keys show in the recorded row');
{
  const t = boot();
  t.change();
  t.fire('keydown', { key: 'g' });
  assert(t.recording()?.textContent.startsWith('g'), 'row shows "g", got ' + t.recording()?.textContent);
}

console.log(' leaving Settings ends recording; the next keys are not swallowed');
{
  const t = boot();
  t.change();
  t.doc.body.setAttribute('data-surface', 'build');
  const k = t.fire('keydown', { key: 'x' });
  assert(!k.defaultPrevented, 'key reaches the editor');
  t.fire('keydown', { key: 'Enter' });
  assert(t.writes.length === 0, 'a stray Enter saved nothing, got ' + JSON.stringify(t.writes));
  assert(!t.recording(), 'recorder gone');
}

console.log(' a pointerdown outside the row ends recording');
{
  const t = boot();
  t.change();
  t.fire('pointerdown', { target: t.doc.body });
  assert(!t.recording(), 'recorder gone');
  const k = t.fire('keydown', { key: 'x' });
  assert(!k.defaultPrevented, 'key not swallowed afterwards');
}

if (fails) { console.error(`✗ ${fails} failed, ${passes} passed`); process.exit(1); }
console.log(`✓ ${passes} passed`);

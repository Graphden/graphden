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
const PREVIEW = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'ui-preview');

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}

MiniElement.prototype.focus = function focus() {};
Object.defineProperties(MiniElement.prototype, {
  firstElementChild: {configurable: true, get() { return this.children[0] || null; }},
  childElementCount: {configurable: true, get() { return this.children.length; }},
});

function installGraph(ctx, doc, win) {
  win.GraphdenPreact = {
    h(type, props, ...children) { return {type, props: {...props, children}}; },
    render(tree, host) {
      function append(value, parent) {
        if (value == null || value === false) return;
        if (Array.isArray(value)) { value.forEach(child => append(child, parent)); return; }
        if (typeof value !== 'object') {
          parent.appendChild(doc.createTextNode(String(value)));
          return;
        }
        const element = doc.createElement(value.type);
        for (const [name, item] of Object.entries(value.props)) {
          if (name === 'children' || name === 'key') continue;
          if (name === 'className') element.className = String(item);
          else element.setAttribute(name, String(item));
        }
        append(value.props.children, element);
        parent.appendChild(element);
      }
      host.replaceChildren();
      append(tree, host);
    },
  };
  win.GraphdenStyles = {};
  for (const file of ['browser-runtime.js', 'builtin-plans.js', 'graph-renderer.js']) {
    vm.runInContext(fs.readFileSync(path.join(PREVIEW, file), 'utf8'), ctx, {filename: file});
  }
}

function boot() {
  const doc = createDocument();
  doc.body.setAttribute('data-surface', 'settings');
  const root = doc.createElement('div');
  root.id = 'gd-keymap-root';
  doc.body.appendChild(root);
  const winListeners = {};
  const writes = [];
  const win = {
    gdPrefOwner: 'owner-a',
    addEventListener(t, fn) { (winListeners[t] = winListeners[t] || []).push(fn); },
    removeEventListener(t, fn) { winListeners[t] = (winListeners[t] || []).filter((f) => f !== fn); },
    gdShortcutEntries: () => [{ id: 'find', group: 'Go', description: 'Find', keys: 'f', leader: true,
                                defaultKeys: 'f', defaultLeader: true, active: true }],
    gdPrefRead: () => null,
    gdPrefWrite: (k, v) => writes.push([k, v]),
    gdApplyKeymap() {},
  };
  const ctx = vm.createContext({ console, document: doc, window: win, Map, Set, WeakSet, WeakMap, Symbol });
  installGraph(ctx, doc, win);
  vm.runInContext(SRC, ctx, {filename: 'editor-keymap.js'});
  win.gdRenderKeymapPane();
  const fire = (type, ev) => {
    const e = Object.assign({ defaultPrevented: false, preventDefault() { this.defaultPrevented = true; },
                              stopPropagation() {} }, ev);
    for (const fn of [...(winListeners[type] || [])]) fn(e);
    return e;
  };
  const change = () => root.dispatch('click', {target: doc.querySelector('.gd-km-change')});
  const recording = () => doc.querySelector('.gd-km-recording');
  return { doc, root, win, writes, fire, change, recording };
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

console.log(' a same-owner graph root refresh keeps the current key gesture');
{
  const t = boot();
  t.change();
  t.fire('gd-editor-root-changing');
  assert(!!t.recording(), 'the recorder remains visible during a root refresh');
  t.fire('gd-editor-root-ready');
  t.fire('keydown', {key: 'p'});
  t.fire('keydown', {key: 'Enter'});
  assert(t.writes.length === 1 && t.writes[0][1].payload.bindings.find.keys === 'p',
    'Enter commits the keys after the root refresh');
}

console.log(' keys typed while the graph row is detached survive the same-owner refresh');
{
  const t = boot();
  t.change();
  t.fire('keydown', {key: 'p'});
  t.fire('gd-editor-root-changing');
  t.root.replaceChildren();
  const key = t.fire('keydown', {key: 'q'});
  assert(key.defaultPrevented, 'the recorder owns the key during row replacement');
  t.fire('gd-editor-root-ready');
  assert(t.recording()?.textContent.startsWith('p q'), 'replacement row shows both keys');
  t.fire('keydown', {key: 'Enter'});
  assert(t.writes.length === 1 && t.writes[0][1].payload.bindings.find.keys === 'p q',
    'Enter commits the gesture spanning the row replacement');
}

console.log(' a changed preference owner cancels the key gesture');
{
  const t = boot();
  t.change();
  t.win.gdPrefOwner = 'owner-b';
  t.fire('gd-editor-root-changing');
  assert(!t.recording(), 'the old owner recorder is gone');
  const key = t.fire('keydown', {key: 'Enter'});
  assert(!key.defaultPrevented && t.writes.length === 0, 'the new owner receives no stale override');
}

if (fails) { console.error(`✗ ${fails} failed, ${passes} passed`); process.exit(1); }
console.log(`✓ ${passes} passed`);

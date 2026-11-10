'use strict';

// Real create-menu host + shared placement/observation helpers. Geometry is
// supplied by the fixture; actual browser layout/actionability remains E2E.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument} = require('./mini-dom');
const packages = path.join(__dirname, '../../resources/packages');
const document = createDocument();
const events = new EventTarget();
const clickListeners = new Set();
document.addEventListener = (type, listener, options) => {
  if (type === 'click') clickListeners.add(listener);
  events.addEventListener(type, listener, options);
};
document.removeEventListener = (type, listener, options) => {
  if (type === 'click') clickListeners.delete(listener);
  events.removeEventListener(type, listener, options);
};
const window = Object.assign(new EventTarget(), {innerWidth: 400, innerHeight: 300});
const frames = new Map();
const timers = [];
let nextFrame = 0;
let rect = {left: 380, right: 395, top: 275, bottom: 290};
const anchor = document.createElement('button');
anchor.getBoundingClientRect = () => rect;
document.body.appendChild(anchor);
const focus = el => { el.focus = opts => {
  el.focusOptions = opts;
  document.activeElement = el;
}; };
focus(anchor);
window.gdAuthoringCreateMenu = () => {
  const menu = document.createElement('div');
  menu.className = 'create-menu';
  Object.defineProperty(menu, 'offsetWidth', {get: () => Math.min(180, parseFloat(menu.style.maxWidth) || 180)});
  Object.defineProperty(menu, 'offsetHeight', {get: () => Math.min(100, parseFloat(menu.style.maxHeight) || 100)});
  for (const type of ['ns', 'fn', 'type']) {
    const item = document.createElement('button');
    item.className = 'create-menu-item'; item.dataset.type = type;
    focus(item); menu.appendChild(item);
  }
  return menu;
};
const ctx = vm.createContext({window, document, console, AbortController,
  requestAnimationFrame: fn => { frames.set(++nextFrame, fn); return nextFrame; },
  cancelAnimationFrame: id => frames.delete(id), setTimeout: fn => timers.push(fn)});
for (const file of ['web/runtime/graphden-popover.js', 'app/editor/editor-create.js']) {
  vm.runInContext(fs.readFileSync(path.join(packages, file), 'utf8'), ctx, {filename: file});
}
const flush = () => { for (const [id, fn] of [...frames]) { frames.delete(id); fn(); } };
const key = (menu, value) => menu.dispatch('keydown', {key: value});
const open = () => {
  ctx.openChildCreateMenu(anchor, 'ns', 'tests');
  while (timers.length) timers.shift()();
  return document.querySelector('.create-menu');
};
const menu = open();
assert.equal(menu.style.top, '171px', 'bottom-edge menu flips above its anchor');
assert.equal(menu.style.left, '216px', 'right-edge menu fits inside viewport');
assert.equal(menu.style.display, 'flex');
assert.equal(document.activeElement, menu.children[0]);
assert.equal(document.activeElement.focusOptions?.preventScroll, true, 'initial focus preserves scroll');
key(menu, 'ArrowDown'); assert.equal(document.activeElement, menu.children[1]);
assert.equal(document.activeElement.focusOptions?.preventScroll, true, 'arrow navigation preserves scroll');
key(menu, 'ArrowUp'); assert.equal(document.activeElement, menu.children[0]);

rect = {left: 12, right: 27, top: 10, bottom: 25};
events.dispatchEvent(new Event('scroll')); flush();
assert.equal(menu.style.top, '29px', 'Explorer scroll reanchors the open menu');
assert.equal(menu.style.left, '12px');

window.visualViewport = Object.assign(new EventTarget(), {offsetLeft: 100, offsetTop: 50, width: 220, height: 150});
ctx.closeChildCreateMenu();
rect = {left: 300, right: 315, top: 175, bottom: 190};
const zoomed = open();
assert.equal(zoomed.style.left, '136px', 'pinch-zoom viewport bounds constrain menu');
assert.equal(zoomed.style.top, '71px');
window.visualViewport.offsetTop = 100;
window.visualViewport.height = 100;
window.visualViewport.dispatchEvent(new Event('scroll')); flush();
assert.equal(zoomed.style.maxHeight, '67px', 'short visible viewport caps menu height');
assert.equal(zoomed.style.top, '104px');

key(zoomed, 'Escape');
assert.equal(document.querySelector('.create-menu'), null);
assert.equal(document.activeElement, anchor, 'Escape restores trigger focus');
assert.equal(anchor.focusOptions?.preventScroll, true, 'returning focus preserves scroll');
events.dispatchEvent(new Event('scroll'));
assert.equal(frames.size, 0, 'closing removes geometry listeners');

open();
rect = {left: 300, right: 315, top: 220, bottom: 235};
events.dispatchEvent(new Event('scroll')); flush();
assert.equal(document.querySelector('.create-menu'), null, 'scrolling anchor out of view dismisses menu');
rect = {left: 300, right: 315, top: 175, bottom: 190};
const clickable = open();
let created;
ctx.startInlineCreate = (...args) => { created = args; };
clickable.children[1].click();
assert.deepEqual(created, ['fn', 'ns', 'tests'], 'choosing New graph preserves creation target');
assert.equal(document.querySelector('.create-menu'), null);

open();
const tour = document.createElement('div'); tour.id = 'gd-tour-pop';
document.body.appendChild(tour);
const clickDocument = target => {
  const event = new Event('click');
  Object.defineProperty(event, 'target', {value: target});
  events.dispatchEvent(event);
};
clickDocument(tour);
assert.ok(document.querySelector('.create-menu'), 'tutorial interaction keeps the menu open');
clickDocument(document.body);
assert.equal(document.querySelector('.create-menu'), null, 'outside click still dismisses');
rect = {left: 300, right: 315, top: 220, bottom: 235};
assert.equal(open(), null, 'initial offscreen anchor dismisses immediately');
assert.equal(clickListeners.size, 0, 'initial dismissal never installs a deferred listener');
rect = {left: 300, right: 315, top: 175, bottom: 190};
anchor.remove();
assert.equal(open(), null, 'detached anchor dismisses immediately');
assert.equal(clickListeners.size, 0, 'detached anchor leaves no listener');
document.body.appendChild(anchor);
ctx.openChildCreateMenu(anchor, 'ns', 'tests');
ctx.closeChildCreateMenu();
while (timers.length) timers.shift()();
assert.equal(clickListeners.size, 0, 'closing before deferred installation leaves no listener');
const escaped = open();
assert.equal(clickListeners.size, 1);
key(escaped, 'Escape');
assert.equal(clickListeners.size, 0, 'Escape removes the installed outside listener');
ctx.openChildCreateMenu(anchor, 'ns', 'tests');
const replacement = open();
assert.equal(clickListeners.size, 1, 'replacing before timeout installs only the current listener');
assert.ok(replacement);
ctx.closeChildCreateMenu();
assert.equal(clickListeners.size, 0, 'programmatic close removes outside listener');
console.log('PASS create menu bounds, scroll, visible viewport and keyboard lifecycle');

'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createDocument} = require('./mini-dom');
const packages = path.join(__dirname, '../../resources/packages');
const doc = createDocument();
let stopped = 0;
let place;
let mutation;
const ctx = vm.createContext({console, document: doc,
  window: {innerWidth: 390, innerHeight: 800,
    visualViewport: {width: 390, height: 240, offsetLeft: 0, offsetTop: 40}},
  installPopoverDismiss() {},
  observePopoverAnchor(_el, _anchor, callback) { place = callback; return () => { stopped++; }; },
  MutationObserver: class { constructor(callback) { mutation = callback; } observe() {} disconnect() {} },
  ensurePopoverClose(el, close) {
    const button = doc.createElement('button');
    button.className = 'gd-pop-x';
    button.addEventListener('click', close);
    el.appendChild(button);
  },
  focusIntoDialog(el) { doc.activeElement = el.querySelector('button'); },
  returnFocusTo(el) { if (el.isConnected) doc.activeElement = el; },
});
const common = fs.readFileSync(path.join(packages, 'web/runtime/graphden-popover.js'), 'utf8');
vm.runInContext(common.slice(common.indexOf('function anchorBelowClamped'), common.indexOf('// Native animations')), ctx);
vm.runInContext(fs.readFileSync(path.join(packages, 'app/editor/editor-path-view.js'), 'utf8'), ctx);
const entries = [
  {'fn-id': 'leaf', 'duration-ms': 1, value: 'old complete value'},
  {'fn-id': 'leaf', 'duration-ms': 2, 'value-truncated?': true},
];
let agg = ctx.aggregatePathTrace(entries).get('leaf');
assert.equal(agg.hasValue, false, 'a later unavailable return clears the earlier captured value');
assert.equal(agg.lastValue, undefined);
assert.equal(ctx.pathValueChipText(agg), '= unavailable');
entries.push({'fn-id': 'leaf', 'duration-ms': 3, value: null});
agg = ctx.aggregatePathTrace(entries).get('leaf');
assert.equal(agg.hasValue, true, 'a later captured null is an available value');
assert.equal(agg.valueTruncated, false);
const anchor = doc.createElement('button');
doc.body.appendChild(anchor);
let rect = {left: 200, right: 220, top: 140, bottom: 160, width: 20, height: 20};
anchor.getBoundingClientRect = () => rect;
ctx._showPathValuePopover(anchor, 'leaf', agg);
const popup = doc.querySelector('.path-value-popover');
Object.defineProperty(popup, 'offsetWidth', {get: () => Math.min(320, parseFloat(popup.style.maxWidth))});
Object.defineProperty(popup, 'offsetHeight', {get: () => Math.min(500, parseFloat(popup.style.maxHeight) || 500)});
place();
assert.equal(popup.querySelector('pre').textContent, 'null');
assert.equal(doc.activeElement, popup.querySelector('button'), 'focus enters the owned popup');
assert(parseFloat(popup.style.top) >= 40 && parseFloat(popup.style.top) + popup.offsetHeight <= 280,
  'long popup stays inside the keyboard-reduced visual viewport');
popup.querySelector('button').click();
assert.equal(doc.activeElement, anchor, 'Close returns focus to the live value badge');
assert.equal(stopped, 1, 'Close stops anchor tracking');
ctx._showPathValuePopover(anchor, 'leaf', ctx.aggregatePathTrace(entries.slice(0, 2)).get('leaf'));
assert(!popup.querySelector('pre'), 'unavailable latest value never exposes stale content');
assert(popup.textContent.includes('not captured'));
anchor.remove();
mutation();
assert.equal(popup.style.display, 'none', 'anchor disappearance dismisses the owned popup');
assert.equal(stopped, 2);
doc.body.appendChild(anchor);
ctx._showPathValuePopover(anchor, 'leaf', agg);
rect = {...rect, top: 300, bottom: 320};
place();
assert.equal(popup.style.display, 'none', 'viewport changes dismiss a badge hidden behind the keyboard');
assert.equal(stopped, 3);
rect = {...rect, top: 140, bottom: 160};
ctx._showPathValuePopover(anchor, 'leaf', agg);
ctx.clearExecutionPathView();
assert.equal(popup.style.display, 'none');
assert.equal(stopped, 4, 'clearing the path disposes popup tracking');
console.log('✓ latest trace value, capture absence/null, focus, visual viewport, anchor disposal');

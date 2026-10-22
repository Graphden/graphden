'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const graph = require('../../resources/packages/app/ui-preview/browser-runtime.js');
const source = fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-fn-picker-graph.js'), 'utf8');
const listeners = new Map(), requests = [], painted = [];
let current, accepted = null, closed = 0, disposed = 0, popupOpen = false;
const window = {
  GraphdenBrowser: graph,
  gdEditorRoot: {
    componentNow: () => null,
    component(name) { assert.equal(name, 'fn-picker'); return new Promise(resolve => requests.push(resolve)); },
    acceptComponent(value) { if (value !== current) return false; accepted = value; return true; },
    releaseComponent(value) { if (accepted === value) accepted = null; },
  },
  GraphdenRenderer: {mount: () => ({render(tree) { painted.push(tree); }, dispose() { disposed++; }})},
  addEventListener(name, fn) { listeners.set(name, fn); },
  closeFnPicker() { if (popupOpen) { closed++; popupOpen = false; } },
  gdLoadUIComponentRuntime() { throw new Error('Picker must not dispatch through the legacy catalogue'); },
};
vm.runInNewContext(source, {window, Map, URLSearchParams, location: {search: ''}, AbortController, DOMException,
  crypto: require('node:crypto').webcrypto, document: {}, compactTypeChipText() {}});
const callback = label => ({run(entry, input) {
  assert.equal(entry, 'view');
  assert(input.model instanceof Map);
  return new Map([[graph.keyword('tree'), label], [graph.keyword('styles'), []]]);
}});
const tick = () => new Promise(resolve => setImmediate(resolve));
(async () => {
  listeners.get('gd-editor-root-ready')();
  current = callback('first graph');
  requests.shift()(current);
  await tick();
  const host = {querySelectorAll: () => []};
  const arranged = {exact: [], groups: [], total: 0, hiddenOther: 0};
  popupOpen = true;
  const first = window.gdFnPickerGraph.mount(host, () => { throw new Error('No candidates'); });
  first.render(arranged, {});
  assert.equal(painted.at(-1), 'first graph');
  assert.equal(accepted, current);
  listeners.get('gd-editor-root-changing')();
  assert.equal(accepted, null);
  assert.equal(window.gdFnPickerGraph.ready, false);
  assert.equal(closed, 1);
  assert.equal(disposed, 1);
  assert.throws(() => first.render(arranged, {}), /disposed/);
  assert.equal(window.gdFnPickerGraph.mount(host), null, 'revocation cannot reuse the old runtime');
  listeners.get('gd-editor-root-ready')();
  const stale = requests.shift();
  listeners.get('gd-editor-root-changing')();
  stale(callback('stale session'));
  await tick();
  assert.equal(window.gdFnPickerGraph.ready, false, 'late old-session callback cannot reactivate the picker');
  listeners.get('gd-editor-root-ready')();
  current = callback('changed graph binding');
  requests.shift()(current);
  await tick();
  const fallback = window.gdFnPickerGraph.mount(host);
  fallback.render(arranged, {});
  assert.equal(painted.at(-1), 'changed graph binding');
  assert.equal(accepted, current);
  // An oversized list releases its graph owner but retains the native popup.
  fallback.dispose();
  popupOpen = true;
  const beforeFallback = closed;
  listeners.get('gd-editor-root-changing')();
  assert.equal(closed, beforeFallback + 1, 'root replacement closes the native fallback popup');
  assert.equal(accepted, null);
  // A picker opened before the next root is ready has no graph owner at all.
  popupOpen = true;
  assert.equal(window.gdFnPickerGraph.mount(host), null);
  const beforeLoading = closed;
  listeners.get('gd-editor-root-changing')();
  assert.equal(closed, beforeLoading + 1, 'root replacement closes a popup opened while loading');
  assert.equal(accepted, null, 'a native fallback cannot certify a rendered root selection');
  console.log('Picker root: binding behavior, render receipt, revocation and stale session OK');
})().catch(error => { console.error(error); process.exitCode = 1; });

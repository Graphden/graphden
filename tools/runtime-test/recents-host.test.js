'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const graph = require('../../resources/packages/app/ui-preview/browser-runtime.js');
const artifact = JSON.parse(fs.readFileSync('resources/packages/app/ui-preview/builtin-plans.json', 'utf8'));
const saved = new Map();
let mounted = 0;
let disposed = 0;
let renderCount = 0;
let clicked = null;
const callbacks = new Map();
const host = {hidden: true, contains: () => true,
  addEventListener: (kind, fn) => callbacks.set(kind, fn),
  removeEventListener: (kind, fn) => { if (callbacks.get(kind) === fn) callbacks.delete(kind); }};
const listeners = new Map();
const ctx = vm.createContext({Map, Set, Promise, console,
  localStorage: {getItem: key => saved.get(key), setItem: (key, value) => saved.set(key, value)},
  document: {getElementById: () => host},
  selectedFnId: 'a', searchFilter: '',
  lookups: {fnMap: new Map([['a', {name: 'add'}], ['b', {name: 'map'}]]), nsPathMap: new Map()},
  gdNavigateToFn: (...args) => { clicked = args; },
});
ctx.window = ctx;
ctx.GraphdenBrowser = graph;
ctx.GraphdenBuiltinPlans = artifact;
ctx.addEventListener = (kind, fn) => listeners.set(kind, fn);
ctx.GraphdenRenderer = {mount() { mounted++; return {render() { renderCount++; }, dispose() { disposed++; }}; }};
vm.runInContext(fs.readFileSync('resources/packages/app/editor/editor-recents.js', 'utf8'), ctx);
(async () => {
  saved.set('graphden.pinnedFns', JSON.stringify([null, {id: 'b', name: 'map', qname: 'map'}, {id: 'b', name: 'duplicate', qname: 'dup'}, {id: 42, name: 'bad', qname: 'bad'}]));
  assert.equal(ctx.gdReadPinnedFns().length, 1, 'storage boundary rejects malformed entries and duplicate identities');
  saved.delete('graphden.pinnedFns');
  ctx.gdLoadRecents();
  ctx.renderRecentFns();
  ctx.gdPushRecentFn('a');
  ctx.gdPushRecentFn('b');
  ctx.gdPushRecentFn('a');
  assert.deepEqual(JSON.parse(saved.get('graphden.recentFns')).map(row => row.id), ['a', 'b']);
  ctx.renderRecentFns();
  assert.equal(host.hidden, false);
  assert.equal(mounted, 1, 'tree paints retain renderer ownership');
  const button = {dataset: {action: 'navigate', fnId: 'b', qname: 'map'}};
  callbacks.get('click')({target: {closest: () => button}});
  assert.deepEqual(clicked, ['b', 'map']);
  button.dataset = {action: 'toggle-pin', fnId: 'b', qname: 'map', name: 'map'};
  callbacks.get('click')({target: {closest: () => button}, stopPropagation() {}});
  assert.deepEqual(JSON.parse(saved.get('graphden.pinnedFns')), [{id: 'b', qname: 'map', name: 'map'}]);
  ctx.searchFilter = 'add';
  ctx.renderRecentFns();
  assert.equal(host.hidden, true);
  listeners.get('pagehide')();
  assert.equal(disposed, 1);
  assert.equal(callbacks.size, 0, 'dispose releases delegated events');
  assert(renderCount > 2);
  console.log('PASS recents persistence, navigation, visibility and cleanup');
})().catch(error => { console.error(error); process.exitCode = 1; });

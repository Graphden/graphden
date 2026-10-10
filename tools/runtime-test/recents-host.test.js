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
const ctx = vm.createContext({Map, Set, Promise, AbortController, console,
  localStorage: {getItem: key => saved.get(key), setItem: (key, value) => saved.set(key, value)},
  document: {getElementById: () => host},
  selectedFnId: '11111111-1111-4111-8111-111111111111', searchFilter: '',
  lookups: {fnMap: new Map([['11111111-1111-4111-8111-111111111111', {name: 'add'}], ['22222222-2222-4222-8222-222222222222', {name: 'map'}]]), nsPathMap: new Map()},
  gdNavigateToFn: (...args) => { clicked = args; },
});
ctx.window = ctx;
ctx.GraphdenBrowser = graph;
ctx.GraphdenBuiltinPlans = artifact;
ctx.addEventListener = (kind, fn) => listeners.set(kind, fn);
ctx.GraphdenStyles = {normalize() {}};
ctx.GraphdenRenderer = {vnode() {}, mount() { mounted++; return {render() { renderCount++; }, dispose() { disposed++; }}; }};
vm.runInContext(fs.readFileSync('resources/packages/app/ui-preview/graph-component.js', 'utf8'), ctx);
vm.runInContext(fs.readFileSync('resources/packages/app/editor/editor-recents.js', 'utf8'), ctx);
(async () => {
  saved.set('graphden.pinnedFns', JSON.stringify([null, {id: '22222222-2222-4222-8222-222222222222', name: 'map', qname: 'map'}, {id: '22222222-2222-4222-8222-222222222222', name: 'duplicate', qname: 'dup'}, {id: 42, name: 'bad', qname: 'bad'}]));
  assert.equal(ctx.gdReadPinnedFns().length, 1, 'storage boundary rejects malformed entries and duplicate identities');
  saved.delete('graphden.pinnedFns');
  ctx.gdLoadRecents();
  ctx.renderRecentFns();
  ctx.gdPushRecentFn('11111111-1111-4111-8111-111111111111');
  ctx.gdPushRecentFn('22222222-2222-4222-8222-222222222222');
  ctx.gdPushRecentFn('11111111-1111-4111-8111-111111111111');
  assert.deepEqual(JSON.parse(saved.get('graphden.recentFns')).map(row => row.id), ['11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222']);
  ctx.renderRecentFns();
  assert.equal(host.hidden, false);
  assert.equal(mounted, 1, 'tree paints retain renderer ownership');
  const button = {dataset: {action: 'navigate', fnId: '22222222-2222-4222-8222-222222222222', qname: 'map'}};
  callbacks.get('click')({target: {closest: () => button}});
  assert.deepEqual(clicked, ['22222222-2222-4222-8222-222222222222', 'map']);
  button.dataset = {action: 'toggle-pin', fnId: '22222222-2222-4222-8222-222222222222', qname: 'map', name: 'map'};
  callbacks.get('click')({target: {closest: () => button}, stopPropagation() {}});
  assert.deepEqual(JSON.parse(saved.get('graphden.pinnedFns')), [{id: '22222222-2222-4222-8222-222222222222', qname: 'map', name: 'map'}]);
  ctx.searchFilter = 'add';
  ctx.renderRecentFns();
  assert.equal(host.hidden, true);
  const originalSet = ctx.localStorage.setItem;
  const notices = [];
  ctx.gdToast = message => notices.push(message);
  ctx.localStorage.setItem = () => { throw new Error('quota'); };
  ctx.gdPushRecentFn('22222222-2222-4222-8222-222222222222');
  ctx.localStorage.setItem = originalSet;
  assert.equal(notices.length, 1);
  assert.match(notices[0], /changed, but could not be saved/);
  ctx.renderRecentFns();
  assert.equal(vm.runInContext("gdRecentsField(gdRecentsController.getState(), 'trail')[0].get(GraphdenBrowser.keyword('id'))", ctx), '22222222-2222-4222-8222-222222222222');
  await Promise.resolve();
  let resolveLoad;
  let loadingSignal;
  let rejected = 0;
  ctx.gdUIComponentFailed = () => { rejected++; };
  ctx.gdLoadUIComponentRuntime = (_component, _builtin, _options, signal) => {
    loadingSignal = signal;
    return new Promise(resolve => { resolveLoad = resolve; });
  };
  const badLoad = ctx.gdRecentsGraph.reload();
  resolveLoad({run: () => new Map()});
  await badLoad;
  assert.equal(rejected, 1, 'incompatible personal runtime falls back with current state');
  const late = ctx.gdRecentsGraph.reload();
  listeners.get('pagehide')();
  assert.equal(loadingSignal.aborted, true);
  resolveLoad({run() { throw new Error('late candidate must not execute'); }});
  await late;
  assert.equal(disposed, 1);
  assert.equal(callbacks.size, 0, 'dispose releases delegated events');
  assert(renderCount > 2);
  console.log('PASS recents persistence, navigation, visibility and cleanup');
})().catch(error => { console.error(error); process.exitCode = 1; });

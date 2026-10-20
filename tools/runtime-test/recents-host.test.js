'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const graph = require('../../resources/packages/app/ui-preview/browser-runtime.js');
const artifact = JSON.parse(fs.readFileSync(path.join(__dirname, '../../resources/packages/app/ui-preview/builtin-plans.json'), 'utf8'));
const saved = new Map();
let mounted = 0;
let disposed = 0;
let renderCount = 0;
let accepted = null;
let failRender = false;
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
const issued = new WeakSet();
const builtin = () => { const candidate = graph.createRuntime(artifact.plans.recents, {operationLimit: 1000000}); issued.add(candidate); return candidate; };
ctx.gdEditorRoot = {componentNow: builtin, component: async () => builtin(),
  acceptComponent(candidate) { if (issued.has(candidate)) accepted = candidate; },
  releaseComponent(candidate) { if (accepted === candidate) accepted = null; }};
ctx.addEventListener = (kind, fn) => listeners.set(kind, fn);
ctx.GraphdenStyles = {normalize() {}};
ctx.GraphdenRenderer = {vnode() {}, mount() { mounted++; return {render() { if (failRender) throw new Error('invalid view'); renderCount++; }, dispose() { disposed++; }}; }};
vm.runInContext(fs.readFileSync(path.join(__dirname, '../../resources/packages/app/ui-preview/graph-component.js'), 'utf8'), ctx);
vm.runInContext(fs.readFileSync(path.join(__dirname, '../../resources/packages/app/editor/editor-recents.js'), 'utf8'), ctx);
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
  assert(accepted, 'only a successfully rendered root facade is accepted');
  failRender = true;
  assert.throws(() => ctx.renderRecentFns(), /invalid view/);
  assert.equal(accepted, null, 'controller existence cannot advertise readiness after a render failure');
  failRender = false;
  ctx.renderRecentFns();
  assert(accepted, 'a repaired render can accept its current facade');
  ctx.gdRecentsGraph.replaceRuntime(graph.createRuntime(artifact.plans.recents, {operationLimit: 1000000}));
  ctx.renderRecentFns();
  assert.equal(accepted, null, 'preview replacement cannot retain the old root facade receipt');
  ctx.gdRecentsGraph.replaceRuntime(builtin());
  assert(accepted, 'a current root facade can regain readiness after successful replacement');
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
  ctx.gdEditorRoot.component = () => new Promise(resolve => { resolveLoad = resolve; });
  const badLoad = ctx.gdRecentsGraph.reload();
  resolveLoad({run: () => new Map()});
  await badLoad;
  assert.equal(notices.length, 2, 'an invalid root callback is diagnosed');
  const previousIdentityLoad = ctx.gdRecentsGraph.reload();
  const resolvePreviousIdentity = resolveLoad;
  listeners.get('gd-editor-root-changing')();
  ctx.gdEditorRoot.component = async () => builtin();
  listeners.get('gd-editor-root-ready')();
  await ctx.gdRecentsGraph.reload();
  resolvePreviousIdentity({run() { throw new Error('previous identity candidate must not execute'); }});
  await previousIdentityLoad;
  assert.equal(mounted, 2, 'root identity change remounts only a fresh component');
  ctx.gdEditorRoot.component = () => new Promise(resolve => { resolveLoad = resolve; });
  const late = ctx.gdRecentsGraph.reload();
  listeners.get('pagehide')();
  resolveLoad({run() { throw new Error('late candidate must not execute'); }});
  await late;
  assert.equal(disposed, 2);
  assert.equal(accepted, null, 'root disposal retires the host receipt');
  assert.equal(callbacks.size, 0, 'dispose releases delegated events');
  assert(renderCount > 2);
  console.log('PASS recents persistence, navigation, visibility and cleanup');
})().catch(error => { console.error(error); process.exitCode = 1; });

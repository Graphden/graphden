'use strict';

// Resolver completions must not take selection back from a newer navigation.
// Layout can fetch early, but cannot calculate card geometry before metadata.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const editor = path.join(__dirname, '../../resources/packages/app/editor');
const source = name => fs.readFileSync(path.join(editor, name), 'utf8');
const flush = async () => { for (let i = 0; i < 15; i++) await Promise.resolve(); };

function navigation() {
  const pending = new Map();
  const renders = [];
  const history = [];
  const ctx = vm.createContext({
    console, Promise, setTimeout,
    selectedFnId: null,
    graphData: {fns: [{id: 'B', name: 'beta'}]},
    lookups: {fnMap: new Map([['B', {id: 'B', name: 'beta'}]])},
    expansionState: new Map(), previewState: new Map(), userMovedNodes: new Set(), expandedNamespaces: new Set(),
    document: {querySelector: () => null},
    getQualifiedFnName: row => row.name,
    updateEntityList() {},
    renderGraph: async () => { renders.push(ctx.selectedFnId); },
    resolveFnByName: name => new Promise(resolve => pending.set(name, resolve)),
    ensureSubtreeFor: id => new Promise(resolve => pending.set(id, resolve)),
    history: {pushState: (_, __, hash) => history.push(hash)},
  });
  ctx.window = ctx;
  vm.runInContext(source('editor-ui.js'), ctx);
  return {ctx, pending, renders, history};
}

(async () => {
  {
    const n = navigation();
    const old = n.ctx.selectFnByName('alpha');
    await n.ctx.selectFn('B');
    n.pending.get('alpha')({id: 'A', name: 'alpha'});
    await old;
    assert.equal(n.ctx.selectedFnId, 'B');
    assert.deepEqual(n.renders, ['B']);
    assert.deepEqual(n.history, ['#beta']);
  }
  {
    const n = navigation();
    const old = n.ctx.selectFnByName('alpha');
    n.ctx.gdInvalidateNavigation();
    n.pending.get('alpha')({id: 'A', name: 'alpha'});
    await old;
    assert.equal(n.ctx.selectedFnId, null);
    assert.deepEqual(n.renders, []);
  }
  {
    const n = navigation();
    const id = '00000000-0000-0000-0000-000000000001';
    n.ctx.graphData.fns.push({id, name: 'alpha'});
    const old = n.ctx.selectFnByName('fn:' + id);
    await n.ctx.selectFn('B');
    n.pending.get(id)(true);
    await old;
    assert.equal(n.ctx.selectedFnId, 'B');
    assert.deepEqual(n.renders, ['B']);
  }
  {
    const n = navigation();
    const releases = [];
    n.ctx.renderGraph = () => new Promise(resolve => releases.push(resolve));
    const first = n.ctx.selectFn('B');
    let complete = false;
    const waiting = n.ctx.gdWaitForSelectionRender().then(() => { complete = true; });
    const second = n.ctx.selectFn('B');
    releases[0]();
    await first;
    await flush();
    assert.equal(complete, false);
    releases[1]();
    await second;
    await waiting;
    assert.equal(complete, true);
  }
  for (const obsolete of [false, true]) {
    let release;
    let requests = 0;
    const metadata = new Promise(resolve => { release = resolve; });
    const ctx = vm.createContext({
      console, selectedFnId: 'A', expansionState: new Map(), previewState: new Map(),
      API: {api_graph_layout: '/layout'}, graphTypeGeneration: () => 1,
      gdWaitForGraphMetadata: () => metadata,
      fetch: async () => { requests++; return {ok: true, json: async () => ({nodes: [], edges: []})}; },
    });
    vm.runInContext(source('editor-layout.js'), ctx);
    let complete = false;
    const layout = ctx.fetchBackendLayout().then(value => { complete = true; return value; });
    await flush();
    assert.equal(requests, 1);
    assert.equal(complete, false);
    if (obsolete) ctx.selectedFnId = 'B';
    release(true);
    const result = await layout;
    assert.equal(result === null, obsolete);
    assert.equal(requests, 1);
  }
  {
    let writes = 0;
    const ctx = vm.createContext({
      console, document: {addEventListener() {}},
      isAuthenticated: () => true, gdGraphEditsReady: () => false,
      authMutate: async () => { writes++; },
    });
    ctx.window = ctx;
    vm.runInContext(source('editor-create.js'), ctx);
    assert.equal(ctx.ensureAuth(), false);
    ctx.startInlineCreate('fn', 'ns', 'app');
    ctx.startNsRename(null, 'ns', 'app');
    ctx.startFnRename(null, 'fn', 'old');
    await assert.rejects(ctx.postEntity('fn', {}), /metadata is loading/);
    await assert.rejects(ctx.putEntity('fn', 'id', {}), /metadata is loading/);
    await assert.rejects(ctx.deleteEntity('fn', 'id'), /metadata is loading/);
    assert.equal(writes, 0);
  }
  console.log('PASS progressive navigation, layout readiness and mutation guards');
})().catch(error => { console.error(error); process.exitCode = 1; });

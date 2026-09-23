'use strict';

// Last response wins — the two async loaders behind every canvas render.
//   * editor-main.js `ensureSubtreeFor`: a subtree fetch superseded by a later
//     one (another root) or by the reload phase (`resetGraphCaches`) drops its
//     rows instead of installing them. A pre-mutation subtree landing after
//     the reload used to reinstall the OLD bindings and pin them via
//     `_subtreeRootId`.
//   * editor-render.js `renderGraph`: selecting A then B fast, with A's
//     layout answering last, drew A's graph under B's sidebar / Inspector.
// Runs under node's vm; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');
const MAIN = fs.readFileSync(path.join(EDITOR, 'editor-main.js'), 'utf8');
const RENDER = fs.readFileSync(path.join(EDITOR, 'editor-render.js'), 'utf8');

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}

const json = (body) => ({ ok: true, status: 200, json: () => Promise.resolve(body) });
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

// A deferred fetch per URL: the test decides when (and in which order) each
// subtree answers.
function bootMain() {
  const pending = [];
  const ctx = vm.createContext({
    console,
    API: { api_graph_entities: '/api/graph/entities', api_types: '/api/types' },
    location: { pathname: '/', search: '', hash: '' },
    document: { addEventListener() {}, body: { classList: { contains: () => false } } },
    buildLookups: (g) => ({ fnMap: new Map(g.fns.map((f) => [f.id, f])) }),
    updateEntityList() {},
    graphData: null,
    lookups: null,
    richTypes: {},
    _rowActionsUseSiteArgs: new Map(),
  });
  ctx.window = ctx;
  ctx.window.addEventListener = () => {};
  ctx.fetch = (url) => new Promise((resolve) => pending.push({ url, resolve }));
  vm.runInContext(MAIN, ctx, { filename: 'editor-main.js' });
  vm.runInContext('graphData = graphShellFromTree({namespaces: [], counts: []});', ctx);
  const answer = (rootId) => {
    const i = pending.findIndex((p) => p.url.includes('root-id=' + rootId));
    if (i < 0) return;   // nothing asked for it
    const [p] = pending.splice(i, 1);
    p.resolve(json({ fns: [{ id: rootId }], slots: [], 'fn-slots': [],
                     bindings: [{ id: 'b-' + rootId, 'fn-id': rootId }], 'list-items': [] }));
  };
  const state = () => vm.runInContext('({root: _subtreeRootId, bindings: graphData.bindings.map((b) => b.id)})', ctx);
  return { ctx, answer, state };
}

function bootRender() {
  const layouts = [];
  const drawn = [];
  const ctx = vm.createContext({
    console,
    selectedFnId: null,
    ensureSubtreeFor: async () => true,
    rebuildImplementationFnIds() {},
    anchorNodeId: null,
    previewState: new Map(),
    graph: { nodes: new Map(), edges: new Map() },
    fetchBackendLayout: () => new Promise((resolve) => layouts.push(resolve)),
  });
  ctx.window = ctx;
  vm.runInContext(RENDER, ctx, { filename: 'editor-render.js' });
  // First-render path only: record what would be drawn.
  ctx.createGraph = (nodes) => { drawn.push(nodes.map((n) => n.data.id).join(',')); };
  const layoutFor = (id) => ({ nodes: [{ data: { id } }], edges: [], layout: new Map() });
  return { ctx, layouts, drawn, layoutFor };
}

(async () => {
  console.log(' ensureSubtreeFor: A then B, A answers last → B\'s rows stay');
  {
    const m = bootMain();
    const pa = m.ctx.ensureSubtreeFor('A');
    const pb = m.ctx.ensureSubtreeFor('B');
    await flush();
    m.answer('B');
    assert(await pb === true, 'B installed');
    m.answer('A');
    assert(await pa === false, 'A reports superseded');
    const s = m.state();
    assert(s.root === 'B' && s.bindings.join() === 'b-B', 'B pinned with B\'s rows, got ' + JSON.stringify(s));
  }

  console.log(' ensureSubtreeFor: a fetch started before the reload phase is dropped');
  {
    const m = bootMain();
    const pa = m.ctx.ensureSubtreeFor('A');
    await flush();
    vm.runInContext('resetGraphCaches(); graphData = graphShellFromTree({namespaces: [], counts: []});', m.ctx);
    m.answer('A');
    assert(await pa === false, 'the pre-reload fetch is superseded');
    const s = m.state();
    assert(s.root === null && s.bindings.length === 0, 'the fresh shell keeps no stale rows, got ' + JSON.stringify(s));
    const again = m.ctx.ensureSubtreeFor('A');
    await flush();
    m.answer('A');
    assert(await again === true && m.state().root === 'A', 'a fresh request installs');
  }

  console.log(' renderGraph: select A then B, A\'s layout answers last → B is drawn');
  {
    const r = bootRender();
    r.ctx.selectedFnId = 'A';
    const ra = r.ctx.renderGraph(true);
    await flush();   // A's layout request is out
    r.ctx.selectedFnId = 'B';
    const rb = r.ctx.renderGraph(true);
    await flush();
    assert(r.layouts.length === 2, 'two layout requests');
    r.layouts[1](r.layoutFor('B'));
    await rb;
    r.layouts[0](r.layoutFor('A'));
    await ra;
    assert(r.drawn.join('|') === 'B', 'only B drawn, got ' + r.drawn.join('|'));
  }

  console.log(' renderGraph: a superseded subtree is asked for again');
  {
    const r = bootRender();
    let calls = 0;
    r.ctx.ensureSubtreeFor = async () => { calls += 1; return calls > 1; };
    r.ctx.selectedFnId = 'A';
    const ra = r.ctx.renderGraph(true);
    await flush();
    r.layouts[0](r.layoutFor('A'));
    await ra;
    assert(calls === 2, 'retried once, got ' + calls);
    assert(r.drawn.join() === 'A', 'then drawn');
  }

  if (fails) { console.error(`✗ ${fails} failed, ${passes} passed`); process.exit(1); }
  console.log(`✓ ${passes} passed`);
})();

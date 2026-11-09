'use strict';

// editor-main.js — the reload phase `initGraph` (boot / structural
// refresh) and `loadGraphData` (post-mutation refresh) share. Pinned:
//   * a namespace load that lands WHILE the tree is in flight survives
//     into the fresh shell (the 2026-09-19 edit-sidebar-filter race), on
//     BOTH paths;
//   * both paths install /api/types and prune `_rowActionsUseSiteArgs`;
//     only initGraph blanks the registry on an unparseable body;
//   * a 401 at boot sends an accounts deployment to /login?next=… and
//     anything else to the admin-password popover — decided by the
//     accounts probe, not by the `gd-tenancy` capability class.
// Runs under node's vm; no browser, no stack.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');
const SRC = fs.readFileSync(path.join(EDITOR, 'editor-main.js'), 'utf8');

let fails = 0;
let passes = 0;
function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  fails += 1;
  console.error('  ✗ ' + msg);
}

const json = (body, status = 200) => ({
  ok: status >= 200 && status < 300, status, statusText: '',
  json: () => (body instanceof Error ? Promise.reject(body) : Promise.resolve(body)),
});

// A fresh editor-main per case. `routes(url)` answers fetches; a route may
// return a function (called at fetch time) to act mid-flight.
function boot({ routes, accounts = false, tenancyClass = false, renderGraph = () => {} }) {
  const calls = { popover: [], entityList: 0, href: null, requery: 0, viewInvalidations: 0, events: [], inlineClosed: 0 };
  const listeners = {};
  const windowListeners = new Map();
  const actions = {inert: false};
  const oldChip = {attrs: {}, setAttribute(key, value) { this.attrs[key] = value; }};
  const surfaces = Object.fromEntries(['graph-container', 'gd-inspector', 'entity-list'].map(id => [id, {
    id, inert: false, attrs: {},
    getAttribute(key) { return this.attrs[key] ?? null; },
    setAttribute(key, value) { this.attrs[key] = value; },
    removeAttribute(key) { delete this.attrs[key]; },
  }]));
  const rowActions = new Map([['b1', {}]]);
  const ctx = vm.createContext({
    console, Event,
    API: { api_graph_entities: '/api/graph/entities', api_types: '/api/types', api_value_kinds: '/api/value-kinds' },
    location: { pathname: '/', search: '', hash: '#app.x',
                set href(v) { calls.href = v; }, get href() { return calls.href; } },
    document: { addEventListener: (name, fn) => { listeners[name] = fn; }, getElementById: id => surfaces[id] || null,
                querySelectorAll: selector => selector.includes('ns-row-actions') ? [actions] : [oldChip], body: { classList: { contains: (c) => tenancyClass && c === 'gd-tenancy' } } },
    hideAllInlineHosts: () => { calls.inlineClosed += 1; },
    buildLookups: (g) => ({ fnMap: new Map(g.fns.map((f) => [f.id, f])) }),
    updateEntityList: () => { calls.entityList += 1; },
    requerySearch: () => { calls.requery += 1; },
    gdInvalidateSharedViews: () => { calls.viewInvalidations += 1; },
    clearAuthPassword() {},
    openAuthPopover: (msg) => { calls.popover.push(msg); },
    graphdenAccountsMode: () => accounts,
    renderGraph,
    graphData: null,
    lookups: null,
    richTypes: { stale: true },
    VALUE_KINDS: [],
    _rowActionsUseSiteArgs: rowActions,
    _rowActionsHtmlCache: new Map([['/partials/row-actions?fn-id=f1', '<b>old name</b>']]),
    typeUsagesCache: new Map([['t-port', [{ kind: 'slot-of' }]]]),
  });
  ctx.window = ctx;
  ctx.window.addEventListener = (name, fn, opts) => {
    const entries = windowListeners.get(name) || [];
    entries.push({fn, once: opts?.once});
    windowListeners.set(name, entries);
  };
  ctx.window.dispatchEvent = event => {
    calls.events.push(event.type);
    const entries = windowListeners.get(event.type) || [];
    windowListeners.set(event.type, entries.filter(entry => !entry.once));
    for (const {fn} of entries) fn(event);
  };
  ctx.window.gdAccountsReady = Promise.resolve(accounts);
  ctx.window.location = ctx.location;
  ctx.fetch = (url) => {
    const r = routes(url);
    return Promise.resolve(typeof r === 'function' ? r() : r);
  };
  vm.runInContext(SRC, ctx, { filename: 'editor-main.js' });
  return { ctx, calls, rowActions, oldChip, surfaces, listeners, actions };
}

const TREE = { namespaces: [{ id: 'ns1', name: 'app' }], counts: [] };

// The tree answer lands only after a namespace load has completed —
// the namespace fetch is started from inside the tree fetch.
function racingRoutes(ctxRef, typesBody) {
  return (url) => {
    if (url.includes('scope=tree')) {
      return async () => {
        await ctxRef.ctx.loadNamespaceFns('ns1');
        return json(TREE);
      };
    }
    if (url.includes('scope=namespace')) return json({ fns: [{ id: 'f1', name: 'a', 'namespace-id': 'ns1' }] });
    if (url === '/api/types') return json(typesBody);
    if (url === '/api/value-kinds') return json(['int']);
    return json({}, 404);
  };
}

(async () => {
  for (const which of ['initGraph', 'loadGraphData']) {
    console.log(` ${which}: a namespace load landing mid-fetch survives; types installed`);
    const ref = {};
    ref.ctx = null;
    const b = boot({ routes: (u) => racingRoutes(ref, { int: {} })(u) });
    ref.ctx = b.ctx;
    b.ctx.location.hash = '';
    await b.ctx[which]();
    const fns = vm.runInContext('graphData.fns', b.ctx);
    assert(fns.length === 1 && fns[0].id === 'f1', which + ': shell carries the mid-flight namespace rows: ' + JSON.stringify(fns));
    assert(b.ctx.isNamespaceLoaded('ns1'), which + ': namespace still marked loaded');
    assert(vm.runInContext('lookups.fnMap.has("f1")', b.ctx), which + ': lookups rebuilt from the cache');
    assert(vm.runInContext('richTypes.int !== undefined && !richTypes.stale', b.ctx), which + ': richTypes installed');
    assert(b.rowActions.size === 0, which + ': _rowActionsUseSiteArgs pruned');
    assert(b.ctx._rowActionsHtmlCache.size === 0, which + ': row-actions HTML cache cleared');
    assert(b.ctx.typeUsagesCache.size === 0, which + ': type Used-by cache cleared');
    assert(b.calls.entityList >= 1, which + ': Explorer repainted');
    assert(b.calls.requery >= 1, which + ': an active search is asked again');
    assert(b.calls.events.includes('gd-graph-changed') === (which === 'loadGraphData'),
      which + ': only a completed mutation refresh announces changed graph data');
  }

  {
    let finishTree;
    const b = boot({routes: url => url.includes('scope=tree')
      ? new Promise(resolve => { finishTree = resolve; }) : json({})});
    b.ctx.location.hash = '';
    const refresh = b.ctx.loadGraphData();
    assert(Object.values(b.surfaces).every(el => el.inert && el.attrs['aria-busy'] === 'true'),
      'all graph interaction surfaces lock before caches are invalidated');
    assert(b.oldChip.attrs['aria-disabled'] === 'true', 'refresh immediately disables existing type actions');
    assert(b.oldChip.attrs.tabindex === '-1', 'stale actions leave the keyboard tab order');
    assert(b.calls.inlineClosed === 1, 'refresh closes stale editable type panels before fetching');
    assert(b.ctx.graphTypeGeneration() === 1 && !b.ctx.graphTypesReady(), 'type generation is pending during refresh');
    finishTree(json(TREE));
    await refresh;
    assert(Object.values(b.surfaces).every(el => !el.inert && !('aria-busy' in el.attrs)),
      'graph interaction surfaces unlock after refreshed data and render');
    assert(b.calls.events.includes('gd-graph-ready'), 'refresh publishes graph readiness after unlock');
    assert(b.oldChip.attrs['aria-disabled'] === 'true', 'old actions are never reenabled after refresh');
  }

  {
    let finishRender;
    const b = boot({routes: url => json(url.includes('scope=tree') ? TREE : {}),
      renderGraph: () => new Promise(resolve => { finishRender = resolve; })});
    b.ctx.location.hash = '';
    const refresh = b.ctx.loadGraphData();
    for (let i = 0; i < 10 && !finishRender; i++) {
      await new Promise(resolve => setImmediate(resolve));
    }
    assert(typeof finishRender === 'function', 'post-mutation graph render started');
    assert(Object.values(b.surfaces).every(el => el.inert),
      'replacement graph must render before stale controls become interactive');
    if (finishRender) finishRender();
    await refresh;
    assert(Object.values(b.surfaces).every(el => !el.inert),
      'render completion releases the shared interaction fence');
  }

  {
    let finishTypes;
    const sub = {fns: [{id: 'f1'}], bindings: [], slots: [], 'fn-slots': [], 'list-items': []};
    const b = boot({routes: url => url === '/api/types'
      ? new Promise(resolve => { finishTypes = resolve; })
      : json(url.includes('scope=subtree') ? sub : TREE)});
    b.ctx.location.hash = '';
    vm.runInContext('graphData = {fns: [], bindings: []}', b.ctx);
    const refresh = b.ctx.loadGraphData();
    await b.ctx.ensureSubtreeFor('f1');
    assert(!b.ctx.graphTypesReady(), 'selection/repaint cannot certify stale rich types while /api/types is held');
    finishTypes(json({fresh: true}));
    await refresh;
    await b.ctx.ensureSubtreeFor('f1');
    assert(b.ctx.graphTypesReady(), 'fresh registry and subtree certify the same generation');
  }
  {
    const pending = [];
    const b = boot({routes: url => url === '/api/types'
      ? new Promise(resolve => pending.push(resolve)) : json(TREE)});
    b.ctx.location.hash = '';
    const old = b.ctx.loadGraphData();
    const latest = b.ctx.loadGraphData();
    pending[1](json({latest: true}));
    await latest;
    assert(Object.values(b.surfaces).every(el => el.inert),
      'overlapping stale refresh keeps the shared interaction fence closed');
    pending[0](json({obsolete: true}));
    await old;
    assert(Object.values(b.surfaces).every(el => !el.inert),
      'last overlapping refresh releases the shared interaction fence');
    assert(vm.runInContext('richTypes.latest && !richTypes.obsolete', b.ctx),
      'late older refresh cannot replace the current type registry at the same auth epoch');
    assert(vm.runInContext('_typesReadyEpoch === _subtreeResetEpoch', b.ctx),
      'older response does not certify a different type generation');
  }

  console.log(' unparseable /api/types: initGraph blanks, loadGraphData keeps the prior registry');
  const badTypes = (u) => (u === '/api/types' ? json(new Error('bad json'))
    : u.includes('scope=tree') ? json(TREE) : json(['int']));
  const origError = console.error;
  console.error = () => {};
  const i = boot({ routes: badTypes });
  i.ctx.location.hash = '';
  await i.ctx.initGraph();
  const l = boot({ routes: badTypes });
  l.ctx.location.hash = '';
  await l.ctx.loadGraphData();
  console.error = origError;
  assert(vm.runInContext('Object.keys(richTypes).length === 0', i.ctx), 'initGraph: {} on a broken body');
  assert(vm.runInContext('richTypes.stale === true', l.ctx), 'loadGraphData: prior registry kept');
  assert(!l.ctx.graphTypesReady(), 'unparseable types fail closed for edit actions');

  console.log(' 401 at boot: accounts → /login?next=…, otherwise the admin popover');
  const unauthorized = () => json({}, 401);
  const acc = boot({ routes: unauthorized, accounts: true });
  await acc.ctx.initGraph();
  assert(acc.calls.href === '/login?next=' + encodeURIComponent('/#app.x'), 'accounts: redirected to /login: ' + acc.calls.href);
  assert(acc.calls.popover.length === 0, 'accounts: no popover');
  const ten = boot({ routes: unauthorized, accounts: false, tenancyClass: true });
  await ten.ctx.initGraph();
  assert(ten.calls.href === null, 'tenancy class without accounts: no redirect to a /login that does not exist');
  assert(ten.calls.popover.length === 1, 'tenancy class without accounts: admin popover');

  console.log(' cookie boot waits for identity and makes one graph/type request');
  {
    const requested = [];
    const b = boot({ routes: url => {
      requested.push(url);
      return json(url.includes('scope=tree') ? TREE : {});
    }});
    b.ctx.location.hash = '';
    b.ctx.initPrefsLate = () => {};
    let finishAuth;
    b.ctx.initAuthLock = () => {
      b.ctx.gdAccountsReady = new Promise(resolve => { finishAuth = resolve; });
    };
    let tutorialReady;
    const ready = new Promise(resolve => { tutorialReady = resolve; });
    b.ctx.maybeStartTutorial = tutorialReady;
    const booting = b.listeners.DOMContentLoaded();
    assert(requested.length === 0, 'no graph requests before cookie identity is known');
    finishAuth(true);
    await booting;
    await ready;
    assert(requested.filter(url => url.includes('scope=tree')).length === 1, 'one initial tree request');
    assert(requested.filter(url => url === '/api/types').length === 1, 'one initial types request');
    assert(b.calls.entityList === 2, 'initial Explorer paints tree, then fresh metadata annotations');
  }

  console.log(' progressive boot paints navigation before metadata and services');
  {
    let finishTypes, finishServices;
    const b = boot({routes: url => url === '/api/types'
      ? new Promise(resolve => { finishTypes = resolve; })
      : json(url.includes('scope=subtree') ? {fns: [{id: 'f1'}], bindings: [], slots: [], 'fn-slots': [], 'list-items': []}
        : url.includes('scope=namespace') ? {fns: [{id: 'f1', name: 'one'}]}
        : url.includes('scope=tree') ? TREE : ['int'])});
    b.ctx.location.hash = '';
    b.ctx.loadServicesEager = () => new Promise(resolve => { finishServices = resolve; });
    const pending = b.ctx.initGraph({progressive: true});
    for (let i = 0; i < 12 && !b.calls.events.includes('gd-explorer-ready'); i++) await new Promise(resolve => setImmediate(resolve));
    assert(b.calls.events.includes('gd-explorer-ready'), 'tree publishes separate navigation readiness');
    assert(!b.surfaces['entity-list'].inert, 'Explorer navigation is available with types/services held');
    assert(b.surfaces['graph-container'].inert && b.surfaces['gd-inspector'].inert, 'editing surfaces remain fenced');
    assert(!b.ctx.gdGraphEditsReady() && b.actions.inert, 'tree action groups cannot mutate early');
    let ready = false;
    const full = b.ctx.gdWaitForGraphReady().then(() => { ready = true; });
    await b.ctx.loadNamespaceFns('ns1');
    await b.ctx.ensureSubtreeFor('f1');
    assert(vm.runInContext('lookups.fnMap.has("f1")', b.ctx), 'namespace and selected subtree load while metadata is pending');
    assert(!ready, 'strong readiness stays pending');
    finishTypes(json({fresh: true}));
    await pending;
    await full;
    assert(b.ctx.graphTypesReady(), 'late types preserve the already installed subtree');
    assert(b.ctx.gdGraphEditsReady() && !b.actions.inert, 'fresh metadata releases mutation actions');
    assert(ready, 'full readiness does not wait for service badges');
    assert(b.calls.requery === 1, 'metadata annotations do not repeat the initial search refresh');
    assert(b.calls.viewInvalidations === 1, 'metadata annotations do not invalidate unchanged shared views');
    finishServices();
  }

  console.log(' superseded progressive metadata cannot overwrite the next session');
  {
    let finishOld;
    let old = true;
    const b = boot({routes: url => url === '/api/types' && old
      ? new Promise(resolve => { finishOld = resolve; })
      : json(url.includes('scope=tree') ? TREE : url === '/api/types' ? {newSession: true} : ['int'])});
    b.ctx.location.hash = '';
    const pending = b.ctx.initGraph({progressive: true});
    for (let i = 0; i < 12 && !finishOld; i++) await new Promise(resolve => setImmediate(resolve));
    b.ctx.window.dispatchEvent(new Event('gd-auth-changed'));
    old = false;
    await b.ctx.initGraph({progressive: true});
    await pending;
    assert(b.ctx.gdGraphEditsReady(), 'superseded boot no longer holds the new session fence');
    finishOld(json({oldSession: true}));
    await new Promise(resolve => setImmediate(resolve));
    assert(vm.runInContext('richTypes.newSession && !richTypes.oldSession', b.ctx), 'late old-session types are discarded');
  }

  console.log(' failed progressive metadata keeps editing closed, then a retry recovers');
  {
    let broken = true;
    const b = boot({routes: url => json(url.includes('scope=tree') ? TREE
      : url === '/api/types' ? (broken ? new Error('bad body') : {fresh: true}) : ['int'])});
    b.ctx.location.hash = '';
    console.error = () => {};
    let rejected = false;
    try { await b.ctx.initGraph({progressive: true}); } catch (_) { rejected = true; }
    console.error = origError;
    assert(rejected && !b.ctx.gdGraphEditsReady(), 'metadata failure is explicit and cannot authorize edits');
    assert(!b.surfaces['entity-list'].inert && b.surfaces['gd-inspector'].inert, 'failed metadata retains read navigation only');
    assert(!b.calls.events.includes('gd-graph-ready'), 'failure does not publish strong readiness');
    broken = false;
    await b.ctx.initGraph({progressive: true});
    assert(Object.values(b.surfaces).every(el => !el.inert), 'retry restores original surface state');
  }

  console.log(' stale tree failures are silent and current failures settle metadata waiters');
  {
    let rejectOld;
    let old = true;
    const b = boot({routes: url => url.includes('scope=tree') && old
      ? new Promise((_, reject) => { rejectOld = reject; })
      : json(url.includes('scope=tree') ? TREE : {})});
    b.ctx.location.hash = '';
    const pending = b.ctx.initGraph();
    b.ctx.window.dispatchEvent(new Event('gd-auth-changed'));
    old = false;
    await b.ctx.initGraph();
    rejectOld(new Error('obsolete tree offline'));
    let rejected = false;
    try { await pending; } catch (_) { rejected = true; }
    assert(!rejected, 'old tree rejection cannot create a fatal banner in the new session');
  }
  for (const reply of [json({}, 503), json(new Error('bad tree JSON'))]) {
    const b = boot({routes: url => url.includes('scope=tree') ? reply : json({})});
    b.ctx.location.hash = '';
    const pending = b.ctx.initGraph({progressive: true});
    const metadata = b.ctx.gdWaitForGraphMetadata();
    let rejected = false;
    try { await pending; } catch (_) { rejected = true; }
    assert(rejected && await metadata === false, 'tree failure settles local metadata waiter false');
    assert(!b.ctx.gdGraphEditsReady(), 'tree failure keeps mutations closed');
  }
  {
    const b = boot({routes: unauthorized, accounts: true});
    b.ctx.clearAuthPassword = () => b.ctx.window.dispatchEvent(new Event('gd-auth-changed'));
    await b.ctx.initGraph({progressive: true});
    assert(b.calls.href?.startsWith('/login?next='), 'clearing our rejected credential still opens the auth wall');
  }

  console.log(' a description save drops the row-actions HTML it was baked into');
  {
    const tctx = vm.createContext({
      console,
      document: { addEventListener() {} },
      graphData: { fns: [{ id: 'f1', description: 'old' }] },
      lookups: null,
      buildLookups: () => ({}),
      _rowActionsHtmlCache: new Map([['u', '<button data-description="old">']]),
    });
    tctx.window = tctx;
    vm.runInContext(fs.readFileSync(path.join(EDITOR, 'editor-tooltips.js'), 'utf8'), tctx);
    tctx.patchEntityDescriptionInState('fn', 'f1', 'new');
    assert(tctx._rowActionsHtmlCache.size === 0, 'row-actions HTML cache cleared on description save');
  }

  console.log(`\n${passes} passed, ${fails} failed`);
  process.exit(fails ? 1 : 0);
})();

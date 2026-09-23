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
function boot({ routes, accounts = false, tenancyClass = false }) {
  const calls = { popover: [], entityList: 0, href: null };
  const rowActions = new Map([['b1', {}]]);
  const ctx = vm.createContext({
    console,
    API: { api_graph_entities: '/api/graph/entities', api_types: '/api/types', api_value_kinds: '/api/value-kinds' },
    location: { pathname: '/', search: '', hash: '#app.x',
                set href(v) { calls.href = v; }, get href() { return calls.href; } },
    document: { addEventListener() {}, body: { classList: { contains: (c) => tenancyClass && c === 'gd-tenancy' } } },
    buildLookups: (g) => ({ fnMap: new Map(g.fns.map((f) => [f.id, f])) }),
    updateEntityList: () => { calls.entityList += 1; },
    clearAuthPassword() {},
    openAuthPopover: (msg) => { calls.popover.push(msg); },
    graphdenAccountsMode: () => accounts,
    renderGraph() {},
    graphData: null,
    lookups: null,
    richTypes: { stale: true },
    VALUE_KINDS: [],
    _rowActionsUseSiteArgs: rowActions,
    _rowActionsHtmlCache: new Map([['/partials/row-actions?fn-id=f1', '<b>old name</b>']]),
  });
  ctx.window = ctx;
  ctx.window.addEventListener = () => {};
  ctx.window.gdAccountsReady = Promise.resolve(accounts);
  ctx.window.location = ctx.location;
  ctx.fetch = (url) => {
    const r = routes(url);
    return Promise.resolve(typeof r === 'function' ? r() : r);
  };
  vm.runInContext(SRC, ctx, { filename: 'editor-main.js' });
  return { ctx, calls, rowActions };
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
    assert(b.calls.entityList >= 1, which + ': Explorer repainted');
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

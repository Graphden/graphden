// editor-explorer-filters.js (+ editor-explorer-views-ui.js) — the Explorer's
// FILTER model under mini-dom:
// the one-time migration of the three old stores (lens / workspace /
// smart views), the kinds mirror into `lensKinds`, chip rendering, the
// view chip label, saving / applying / deleting a view, and the
// announces. The members fetch is stubbed to a canned payload, so this
// pins the module's OWN contract (state, storage, announce), not the
// server's.
//
// Run:  node tools/runtime-test/explorer-filters-store.test.js
// Exit: 0 on pass, 1 on failure.

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createDocument } = require('./mini-dom');

const EDITOR = path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor');

let failures = 0;
let passes = 0;

function assert(cond, msg) {
  if (cond) { passes += 1; return; }
  failures += 1;
  console.error('  ✗ ' + msg);
}

async function test(name, fn) {
  console.log(' ' + name);
  try { await fn(); } catch (e) { failures += 1; console.error('  ✗ threw: ' + e.message); }
}

function filtersCtx(seed) {
  const document = createDocument();
  const store = new Map(Object.entries(seed || {}));
  const announced = [];
  const updates = [];
  const fetches = [];
  const chips = document.createElement('div'); chips.id = 'gd-filter-chips';
  const add = document.createElement('button'); add.id = 'gd-filter-add';
  const chip = document.createElement('button'); chip.id = 'gd-ws-chip';
  const b = document.createElement('b'); b.textContent = 'All functions'; chip.appendChild(b);
  const k = document.createElement('span'); k.className = 'gd-ctx-k'; chip.appendChild(k);
  for (const el of [chips, add, chip]) document.body.appendChild(el);
  const lensKinds = new Set();
  const ctx = vm.createContext({
    console,
    document,
    window: { gdAnnounce: (m) => announced.push(m),
              API: { api_graph_entities: '/api/graph/entities', api_views_members: '/api/views/members', api_views: '/api/views' } },
    localStorage: {
      getItem: (kk) => (store.has(kk) ? store.get(kk) : null),
      setItem: (kk, v) => store.set(kk, String(v)),
      removeItem: (kk) => store.delete(kk),
    },
    lensKinds,
    installPopoverDismiss: () => {},
    focusIntoDialog: () => {},
    returnFocusTo: () => {},
    anchorBelowClamped: () => {},
    syncKindFilterBar: () => {},
    applyKindFilters: () => updates.push('kinds'),
    updateEntityList: () => updates.push('tree'),
    graphData: { namespaces: [] },
    API: { api_graph_entities: '/api/graph/entities', api_views_members: '/api/views/members', api_views: '/api/views' },
    authFetch: (url, opts) => {
      const body = opts?.body ? JSON.parse(opts.body) : null;
      fetches.push({ url, body });
      // A `uses` id starting with "gone" is one the graph no longer holds.
      const goneUses = (body?.uses || []).filter((id) => /^gone/.test(id));
      return Promise.resolve({
        ok: true,
        json: () => Promise.resolve(url === '/api/views' ? []
          : Object.assign({ fns: goneUses.length ? [] : [{ id: 'x', name: 'member' }], total: goneUses.length ? 0 : 1, 'truncated?': false },
                          goneUses.length ? { missing: { uses: goneUses } } : {})),
      });
    },
  });
  // querySelector on the mini-dom body for the ids the module uses.
  // The model, then the UI half (chips, the view chip) — the pair the
  // bundle loads in this order.
  for (const f of ['editor-explorer-filters.js', 'editor-explorer-views-ui.js']) {
    vm.runInContext(fs.readFileSync(path.join(EDITOR, f), 'utf8'), ctx, { filename: f });
  }
  return { ctx, document, store, announced, updates, fetches, lensKinds, chips, b };
}

const tick = () => new Promise((r) => setTimeout(r, 5));

(async () => {
  await test('migration: lens + workspace roots/hidden + smart views become the new stores, old keys go', async () => {
    const { ctx, store, lensKinds } = filtersCtx({
      'graphden.sidebarLens': JSON.stringify(['types', 'failed']),
      'graphden.workspace.roots': JSON.stringify(['core']),
      'graphden.workspace.hidden': JSON.stringify(['core.tests']),
      'graphden.smartViews': JSON.stringify([{ name: 'io-stuff', rule: 'effect:io ns:web' }]),
    });
    const f = ctx.gdFilters();
    assert(f.kinds.join() === 'types' && f.problems.join() === 'failed', 'lens split into kinds + problems: ' + JSON.stringify(f));
    assert(f.namespaces.join() === 'core' && f.exclude.join() === 'core.tests', 'workspace roots/hidden → namespaces/exclude');
    assert(lensKinds.has('types') && lensKinds.has('failed'), 'lensKinds mirrors kinds + problems on load');
    const views = ctx.gdReadViews();
    assert(views.length === 1 && views[0].filters.effects.join() === 'io' && views[0].filters.namespaces.join() === 'web',
      'a smart view rule became a filter set: ' + JSON.stringify(views));
    for (const k of ['graphden.sidebarLens', 'graphden.workspace.roots', 'graphden.workspace.hidden', 'graphden.smartViews']) {
      assert(!store.has(k), 'old key removed: ' + k);
    }
    assert(store.has('graphden.explorer.filters'), 'new key written');
  });

  await test('kinds toggle: lensKinds mirror, the cheap kinds pass, the chip label', async () => {
    const { ctx, lensKinds, updates, b } = filtersCtx();
    ctx.gdToggleKind('types');
    assert(lensKinds.has('types'), 'lensKinds gets the kind');
    assert(updates[updates.length - 1] === 'kinds', 'a kinds-only change takes the in-place pass, not a rebuild');
    assert(b.textContent === '1 filter', 'chip reads the count: ' + b.textContent);
    ctx.gdToggleKind('types');
    assert(!lensKinds.has('types') && b.textContent === 'All functions', 'toggling back clears it');
  });

  await test('namespace + exclude: chips render, the tree rebuilds, announces say what changed', async () => {
    const { ctx, chips, updates, announced } = filtersCtx();
    ctx.gdToggleNamespace('core');
    ctx.gdToggleExclude('core.tests');
    const labels = chips.children.map((c) => c.children.find((x) => String(x.className).includes('kind-label')).textContent);
    assert(labels.join('|') === 'in core|not core.tests', 'one chip per filter: ' + labels.join('|'));
    assert(updates.includes('tree'), 'structural axes rebuild the tree');
    assert(announced.some((m) => /Only core/.test(m)) && announced.some((m) => /hidden/.test(m)), 'announced: ' + announced.join(' / '));
    assert(ctx.gdNsIncluded('core.strings') && !ctx.gdNsIncluded('web') && ctx.gdNsExcluded('core.tests.x'), 'the predicates the tree reads');
  });

  await test('uses: a server axis — POST /api/views/members with ids, members exposed, announced', async () => {
    const { ctx, fetches, announced } = filtersCtx();
    ctx.gdAddUses({ id: 'abc', name: 'const' });
    assert(ctx.gdServerAxesActive(), 'server axes active');
    assert(ctx.gdViewMembers() === null, 'members null while loading');
    await tick(); await tick();
    const call = fetches.find((f) => f.url === '/api/views/members');
    assert(call && call.body.uses.join() === 'abc', 'posted the fn id: ' + JSON.stringify(call?.body));
    assert(Array.isArray(ctx.gdViewMembers()) && ctx.gdViewMembers().length === 1, 'members landed');
    assert(announced.some((m) => /1 functions match/.test(m)), 'announced the count');
  });

  await test('views: save names the set, apply restores it, delete forgets it, clear detaches', async () => {
    const { ctx, b, store } = filtersCtx();
    ctx.gdToggleKind('services');
    ctx.gdToggleNamespace('web');
    ctx.gdSaveView('web-services');
    assert(b.textContent === 'web-services', 'chip carries the view name');
    assert(ctx.gdReadViews()[0].name === 'web-services', 'persisted');
    ctx.gdClearFilters();
    assert(b.textContent === 'All functions' && ctx.gdFilterCount() === 0, 'clear empties everything');
    await ctx.gdApplyView(ctx.gdReadViews()[0]);
    const f = ctx.gdFilters();
    assert(f.kinds.join() === 'services' && f.namespaces.join() === 'web' && b.textContent === 'web-services', 'apply restores the set');
    ctx.gdToggleKind('types');
    assert(b.textContent === '3 filters', 'editing detaches from the view: ' + b.textContent);
    ctx.gdDeleteView('web-services');
    assert(ctx.gdReadViews().length === 0, 'deleted');
    assert(JSON.parse(store.get('graphden.explorer.filters')).view === null, 'no dangling view name');
  });

  await test('name + views axes: chips, server body, a graph view\'s `also` becomes a "view" chip', async () => {
    const { ctx, chips, fetches } = filtersCtx();
    ctx.gdSetName('handler');
    ctx.gdAddView({ id: 'v1', name: 'api-surface' });
    ctx.gdToggleKind('apps');
    await tick(); await tick();
    const labels = chips.children.map((c) => c.children.find((x) => String(x.className).includes('kind-label')).textContent);
    assert(labels.join('|') === 'name handler|view api-surface', 'chips for the two axes: ' + labels.join('|'));
    const call = fetches.filter((f) => f.url === '/api/views/members').pop();
    assert(call && call.body.name === 'handler' && call.body.views.join() === 'v1', 'posted name + view ids: ' + JSON.stringify(call?.body));
    assert(call && !call.body.kinds.includes('apps'), 'apps stays a client overlay — never sent to the server');
    assert(ctx.gdFilterCount() === 3, 'counted: ' + ctx.gdFilterCount());
  });

  await test('a chip naming a deleted fn is marked ⚠ and its accessible name says so', async () => {
    const { ctx, chips } = filtersCtx();
    ctx.gdAddUses({ id: 'gone-1', name: 'old-fn' });
    await tick(); await tick();
    const chip = chips.children[0];
    assert(String(chip.className).includes('gd-filter-chip-missing'), 'the chip carries the missing class: ' + chip.className);
    assert(/no longer exists/.test(chip.getAttribute('aria-label') || ''), 'the accessible name says it: ' + chip.getAttribute('aria-label'));
    assert(ctx.gdViewMembers().length === 0, 'the set is empty');
    ctx.gdRemoveUses('gone-1');
    assert(chips.children.length === 0, 'removing the chip clears it');
  });

  console.log(failures === 0 ? 'PASS: ' + passes + ' assertions' : 'FAIL: ' + failures + ' of ' + (passes + failures));
  process.exit(failures === 0 ? 0 : 1);
})();

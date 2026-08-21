// Unit tests for editor-tour-cleanup.js — undoing what a tutorial lesson made.
//
// This pass is the only thing standing between a reader who took the tour and
// a graph full of `tutorial-*` leftovers, and every one of its rules was
// learned from a leftover that actually shipped:
//
//   * `authFetch` / `authMutate` RESOLVE on 4xx. A `try/catch` around them
//     sees only network errors, so a 409 ("this fn is still someone's parent")
//     counted as a successful delete and the reader was told "deleted".
//   * fns delete NEWEST first, because the server refuses to delete a fn that
//     something still references — creation order left the first fn of every
//     chain behind.
//   * a package's PIN must go before the namespace holding its materialised
//     copy, or the next install answers 404 for a package the registry lists
//     as fine.
//
// None of that is observable from a lesson walk: the browser guards assert the
// leftovers are gone at the END, which a swallowed failure and a real delete
// look identical from — until the delete stops working.
//
// Run:  node tools/runtime-test/tour-cleanup.test.js
// Exit: 0 on pass, 1 on failure.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(
  path.join(__dirname, '..', '..', 'resources', 'packages', 'app', 'editor',
            'editor-tour-cleanup.js'),
  'utf8');

let failures = 0;
let passes = 0;

function assert(cond, msg) {
  if (cond) { passes++; return; }
  failures++;
  console.error('  ✗ ' + msg);
}

function test(name, fn) {
  console.log(' ' + name);
  return fn().catch((e) => { failures++; console.error('  ✗ threw: ' + e.message); });
}

// --- a stand-in for the editor's fetch layer --------------------------------
//
// `world` describes the server: which fns exist by name, which namespaces
// exist, what the registry lists, and which URLs refuse. Every request is
// recorded in `calls`, so ORDER is assertable.

function makeCtx(world) {
  const w = Object.assign({fns: [], namespaces: [], packages: [], refuse: () => false}, world);
  const calls = [];
  const respond = (method, url) => {
    calls.push(method + ' ' + url);
    const refusal = w.refuse(method, url, calls);
    if (refusal === 'throw') throw new Error('network down');
    const status = refusal ? (refusal === true ? 409 : refusal) : 200;
    let payload = {};
    if (url.includes('scope=search')) {
      const q = decodeURIComponent(url.split('q=')[1] || '');
      payload = {fns: w.fns.filter((f) => f.name === q)};
    } else if (url.includes('scope=namespace')) {
      const id = url.split('namespace-id=')[1];
      payload = {fns: w.fns.filter((f) => f['namespace-id'] === id)};
    } else if (url.includes('scope=tree')) {
      payload = {namespaces: w.namespaces};
    } else if (url === '/api/packages') {
      payload = w.packages;
    }
    return Promise.resolve({
      ok: status < 400,
      status,
      json: () => Promise.resolve(payload),
      text: () => Promise.resolve(''),
    });
  };
  const ctx = vm.createContext({
    console,
    graphData: {namespaces: w.namespaces},
    initGraph: () => Promise.resolve(),
    _tourFindFn: (name) => w.fns.find((f) => f.name === name) || null,
    authFetch: (url, opts) => respond((opts && opts.method) || 'GET', url),
    authMutate: (method, url) => respond(method, url),
    API: {
      api_packages: '/api/packages',
      api_packages_withdraw: '/api/packages/withdraw',
      api_packages_uninstall: '/api/packages/uninstall',
      api_graph_entities: '/api/graph/entities',
      api_branches_ref: (n) => '/api/branches/' + n,
      api_entities_type_id: (t, id) => '/api/entities/' + t + '/' + id,
    },
  });
  vm.runInContext(source, ctx);
  return {ctx, calls};
}

const FN = (name, ns) => ({id: 'id-' + name, name, 'namespace-id': ns || null});

// --- cases ------------------------------------------------------------------

const tests = [

  test('a refused delete is REPORTED, not swallowed', async () => {
    const {ctx} = makeCtx({
      fns: [FN('tutorial-a')],
      refuse: (m, u) => m === 'DELETE' && u.includes('/fn/'),
    });
    const failed = await ctx._tourDeleteFns([{type: 'fn', name: 'tutorial-a'}]);
    assert(failed.length === 1 && failed[0].name === 'tutorial-a',
           'the 409 lands in the failure list (got: ' + JSON.stringify(failed) + ')');
  }),

  test('a network error is reported too', async () => {
    const {ctx} = makeCtx({
      fns: [FN('tutorial-a')],
      refuse: (m, u) => (m === 'DELETE' && u.includes('/fn/') ? 'throw' : false),
    });
    const failed = await ctx._tourDeleteFns([{type: 'fn', name: 'tutorial-a'}]);
    assert(failed.length === 1, 'a thrown fetch is a failure, not a silent pass');
  }),

  test('the retry pass clears what the first pass unblocked', async () => {
    // A parent refuses while its child is still there; the second attempt
    // succeeds. That must NOT be reported — it is the normal chain case.
    let seen = 0;
    const {ctx, calls} = makeCtx({
      fns: [FN('tutorial-a')],
      refuse: (m, u) => (m === 'DELETE' && u.includes('/fn/') ? (++seen === 1) : false),
    });
    const failed = await ctx._tourDeleteFns([{type: 'fn', name: 'tutorial-a'}]);
    assert(failed.length === 0, 'the second attempt succeeded, so nothing is reported');
    assert(calls.filter((c) => c.startsWith('DELETE /api/entities/fn/')).length === 2,
           'and it really was attempted twice');
  }),

  test('fns delete NEWEST first — the server refuses a fn still referenced', async () => {
    const {ctx, calls} = makeCtx({fns: [FN('tutorial-cell'), FN('tutorial-bump')]});
    await ctx._tourDeleteFns([{type: 'fn', name: 'tutorial-cell'},
                              {type: 'fn', name: 'tutorial-bump'}]);
    const order = calls.filter((c) => c.startsWith('DELETE /api/entities/fn/'));
    assert(order[0].endsWith('id-tutorial-bump') && order[1].endsWith('id-tutorial-cell'),
           'the LAST-created fn goes first (got: ' + order.join(' | ') + ')');
  }),

  test('a fn absent from the client is resolved through the server', async () => {
    // `_tourFindFn` is lexical — the client holds only the selected subtree.
    const {ctx, calls} = makeCtx({fns: [FN('tutorial-a')]});
    ctx._tourFindFn = () => null;
    await ctx._tourDeleteFns([{type: 'fn', name: 'tutorial-a'}]);
    assert(calls.some((c) => c.includes('scope=search&q=tutorial-a')),
           'the search endpoint decides whether it exists');
    assert(calls.some((c) => c === 'DELETE /api/entities/fn/id-tutorial-a'),
           'and it is deleted even though the client never held it');
  }),

  test('the PIN goes before the namespace holding the materialised copy', async () => {
    const created = [{type: 'ns', name: 'mycorp'},
                     {type: 'package-version', name: 'mycorp-hello'},
                     {type: 'ns', name: 'mycorp@1-0-0'}];
    const {ctx, calls} = makeCtx({
      namespaces: [{id: 'ns-1', name: 'mycorp'}, {id: 'ns-2', name: 'mycorp@1-0-0'}],
      packages: [{name: 'mycorp-hello', version: '1.0.0'}],
    });
    const {failed} = await ctx._tourDeleteCreated(created);
    assert(failed.length === 0, 'a clean pass reports nothing');
    const unpin = calls.findIndex((c) => c.includes('/packages/uninstall'));
    const withdraw = calls.findIndex((c) => c.includes('/packages/withdraw'));
    const nsDelete = calls.findIndex((c) => c.startsWith('DELETE /api/entities/ns/'));
    assert(unpin >= 0 && withdraw > unpin,
           'unpin, THEN withdraw (got unpin=' + unpin + ' withdraw=' + withdraw + ')');
    assert(nsDelete > withdraw,
           'and both before the namespace delete (ns=' + nsDelete + ')');
  }),

  test('a namespace is emptied before it is deleted', async () => {
    const created = [{type: 'ns', name: 'mycorp'}];
    const {ctx, calls} = makeCtx({
      namespaces: [{id: 'ns-1', name: 'mycorp'}],
      fns: [FN('installed-copy', 'ns-1')],
    });
    await ctx._tourDeleteCreated(created);
    const child = calls.indexOf('DELETE /api/entities/fn/id-installed-copy');
    const parent = calls.indexOf('DELETE /api/entities/ns/ns-1');
    assert(child >= 0, 'the contents the install materialised are removed');
    assert(parent > child, 'the namespace goes after (a non-empty one 409s)');
  }),

  test('a refused namespace delete is reported', async () => {
    const created = [{type: 'ns', name: 'mycorp'}];
    const {ctx} = makeCtx({
      namespaces: [{id: 'ns-1', name: 'mycorp'}],
      refuse: (m, u) => m === 'DELETE' && u.includes('/entities/ns/'),
    });
    const {failed} = await ctx._tourDeleteCreated(created);
    assert(failed.length === 1 && failed[0].name === 'mycorp',
           'the reader is told it stayed (got: ' + JSON.stringify(failed) + ')');
  }),

  test('one row that refuses twice is named once', async () => {
    const created = [{type: 'package-version', name: 'mycorp-hello'}];
    const {ctx} = makeCtx({
      packages: [{name: 'mycorp-hello', version: '1.0.0'}],
      refuse: (m, u) => u.includes('/packages/'),
    });
    const {failed} = await ctx._tourDeleteCreated(created);
    assert(failed.length === 1,
           'the unpin AND the withdraw both refused, but it is one package'
           + ' (got: ' + JSON.stringify(failed) + ')');
  }),

  test('survivors: every created type the deleter knows is reported', async () => {
    const {ctx} = makeCtx({
      namespaces: [{id: 'ns-1', name: 'mycorp'}],
      fns: [FN('greet')],
      packages: [{name: 'mycorp-hello', version: '1.0.0'}],
    });
    const out = await ctx._tourSurvivors([
      {type: 'branch', name: 'tutorial-14'},
      {type: 'fn', name: 'greet'},
      {type: 'ns', name: 'mycorp'},
      {type: 'package-version', name: 'mycorp-hello'},
    ]);
    assert(out.length === 4,
           'all four kinds are offered (got: ' + out.map((c) => c.type).join(',') + ')');
  }),

  test('survivors: what is already gone is not offered', async () => {
    const {ctx} = makeCtx({});
    const out = await ctx._tourSurvivors([
      {type: 'fn', name: 'greet'},
      {type: 'ns', name: 'mycorp'},
      {type: 'package-version', name: 'mycorp-hello'},
    ]);
    assert(out.length === 0,
           'nothing exists, so nothing is listed (got: ' + JSON.stringify(out) + ')');
  }),

  test('survivors: an unknown type is offered rather than dropped', async () => {
    const {ctx} = makeCtx({});
    const out = await ctx._tourSurvivors([{type: 'future-thing', name: 'x'}]);
    assert(out.length === 1, 'the delete pass reports what happens to it');
  }),


  test('the pass deletes what it is GIVEN, with no tour state at all', async () => {
    // The end-of-tour dialog runs long after the tour stopped: the last step
    // moves the index past the end, the very next poll tick tore the tour
    // down, and the dialog — still awaiting its survivors read — then
    // rendered over a null state. Reading `_tourState` here meant deleting
    // nothing and reporting "Tutorial items deleted". Reproduced on the stack
    // (600ms poll vs a ~1.5s read); the engine now stops the poll first AND
    // hands the list over, so this module never reads tour state at all.
    const created = [{type: 'fn', name: 'tutorial-a'}];
    const {ctx, calls} = makeCtx({fns: [FN('tutorial-a')]});
    assert(typeof ctx._tourState === 'undefined',
           'the module does not read tour state (it is not even defined here)');
    const {failed} = await ctx._tourDeleteCreated(created);
    assert(failed.length === 0, 'nothing refused');
    assert(calls.includes('DELETE /api/entities/fn/id-tutorial-a'),
           'the row the lesson made was actually deleted (got: ' + calls.join(' | ') + ')');
  }),

];

Promise.all(tests).then(() => {
  console.log(failures ? `\n✗ tour-cleanup: ${failures} failed, ${passes} passed`
                       : `\n✓ tour-cleanup: ${passes} assertions`);
  process.exit(failures ? 1 : 0);
});

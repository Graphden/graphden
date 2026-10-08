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
  const w = Object.assign({fns: [], namespaces: [], packages: [], installed: [], branches: [], refuse: () => false}, world);
  const calls = [];
  const respond = (method, url) => {
    calls.push(method + ' ' + url);
    const refusal = w.refuse(method, url, calls);
    if (refusal === 'throw') throw new Error('network down');
    const status = refusal ? (refusal === true ? 409 : refusal) : 200;
    let payload = {};
    if (url.startsWith('/api/http-host/')) {
      payload = {ok: !w.publicationFailure};
    } else if (url.startsWith('/api/branches/')) {
      payload = w.branchMissing ? {ok: false, reason: 'not-found'} : {ok: !w.branchFailure};
    } else if (url === '/api/branches') {
      payload = w.branches;
    } else if (url === '/api/packages/installed') {
      payload = w.installed;
    } else if (url.includes('scope=search')) {
      const q = decodeURIComponent(url.split('q=')[1] || '');
      payload = {fns: w.fns.filter((f) => f.name === q)};
    } else if (url.includes('scope=subtree')) {
      const id = decodeURIComponent(url.split('root-id=')[1]);
      payload = {fns: w.fns.filter(fn => fn.id === id)};
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
    HTTP_HOST_API: '/api/http-host',
    _tourPrincipalMatches: ({principal}) => principal === 'current-owner',
    graphData: {namespaces: w.namespaces},
    initGraph: () => Promise.resolve(),
    _tourFindFn: (name) => w.fns.find((f) => f.name === name) || null,
    authFetch: (url, opts) => respond((opts && opts.method) || 'GET', url),
    authMutate: (method, url) => respond(method, url),
    API: {
      api_packages: '/api/packages',
      api_packages_installed: '/api/packages/installed',
      api_branches: '/api/branches',
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

const NS = (id, name) => ({type: 'ns', id, name, 'parent-id': null, receipt: 'created'});
const PV = (version = '1.0.0') => ({type: 'package-version', name: 'mycorp-hello',
  id: 'release-' + version, version, 'content-hash': 'hash-' + version, receipt: 'created'});
const PIN = {type: 'package-install', id: 'pin-id', name: 'mycorp-hello', version: '1.0.0',
  'branch-id': 'site-id', 'branch-name': 'site', receipt: 'created'};

// --- cases ------------------------------------------------------------------

const tests = [

  test('an unresolved worker blocks branch and graph deletion', async () => {
    const {ctx, calls} = makeCtx({});
    const service = {type: 'service', id: 'exact-service', name: 'worker', receipt: 'pending'};
    const branch = {type: 'branch', id: 'branch-id', name: 'lesson', 'base-branch-id': 'main-id'};
    const fn = {type: 'fn', id: 'worker-id', name: 'worker'};
    ctx._tourCleanupServices = async () => [service];
    const result = await ctx._tourDeleteCreated([service, branch, fn]);
    assert(result.failed.length === 3, 'all dependent graph receipts retained');
    assert(calls.length === 0, 'no branch or graph mutation before worker stop');
  }),

  test('publication cleanup uses exact staged UUIDs and the confirmed principal', async () => {
    const good = {type: 'http-publication', id: 'exact-id', name: 'same-name', principal: 'current-owner'};
    const other = {...good, id: 'other-id', principal: 'different-owner'};
    const missing = {...good, id: null};
    const {ctx, calls} = makeCtx({});
    const failed = await ctx._tourDeleteHttpPublications([good, other, missing]);
    assert(failed.length === 2, 'unconfirmed principal and absent identity stay in the ledger');
    assert(calls.join() === 'DELETE /api/http-host/exact-id', 'cleanup never searches by name or uses another owner');
  }),

  test('failed publication cleanup retains every exact UUID, including same-name leases', async () => {
    const created = ['first-id', 'second-id'].map(id => ({
      type: 'http-publication', id, name: 'same-name', principal: 'current-owner',
    }));
    for (const world of [{publicationFailure: true}, {refuse: () => 403}, {refuse: () => 'throw'}]) {
      const {ctx} = makeCtx(world);
      const result = await ctx._tourDeleteCreated(created);
      assert(result.failed.length === 2, 'each refused identity survives independently');
      assert(result.failed[0].id !== result.failed[1].id, 'same-name leases are not collapsed');
    }
  }),

  test('preference recovery sees exact failures before dedupe and thrown recovery stays resumable', async () => {
    const left = {type: 'fn', name: 'ui', id: 'left', 'namespace-id': 'left-ns', receipt: 'created'};
    const right = {...left, id: 'right', 'namespace-id': 'right-ns'};
    const fixture = makeCtx({fns: [left, right], refuse: method => method === 'DELETE'});
    fixture.ctx.window = fixture.ctx;
    fixture.ctx.gdTourRestoreUIComponentPreferences = (_created, rawFailed) => {
      assert(rawFailed.some(row => row.id === 'left') && rawFailed.some(row => row.id === 'right'),
        'same-named configurations keep both exact failed UUIDs at restoration boundary');
      return [];
    };
    await fixture.ctx._tourDeleteCreated([left, right]);
    const empty = makeCtx({});
    empty.ctx.window = empty.ctx;
    let saved = 0;
    empty.ctx._tourSaveState = () => { saved++; };
    empty.ctx.gdTourRestoreUIComponentPreferences = () => { throw new Error('preference write rejected'); };
    const ledger = [];
    const result = await empty.ctx._tourDeleteCreated(ledger);
    assert(result.failed.length === 1 && result.failed[0].type === 'preference', 'failed preference write is not cleanup success');
    assert(ledger.length === 1 && saved > 0, 'empty graph cleanup retains a persisted recovery marker');
    assert((await empty.ctx._tourSurvivors(ledger)).length === 1, 'Lessons retry keeps the recovery state visible');
  }),

  test('fixed create-only manifest reconciles lost replies by exact fn/ns tuples', async () => {
    const proof = {receipt: 'pending', creation: 'create-only-manifest',
      'manifest-root-id': 'root-id', 'branch-id': 'sandbox-id', 'branch-name': 'sandbox'};
    const fn = {...proof, type: 'fn', id: 'new-fn', name: 'config', 'namespace-id': 'new-ns'};
    const ns = {...proof, type: 'ns', id: 'new-ns', name: 'components', 'parent-id': null};
    const created = makeCtx({fns: [{id: fn.id, name: fn.name, 'namespace-id': ns.id}],
      namespaces: [{id: ns.id, name: ns.name, 'parent-id': null}]});
    assert((await created.ctx._tourDeleteFns([fn])).length === 0, 'exact new fn is recoverable after lost apply reply');
    assert((await created.ctx._tourDeleteNamespaces([ns])).length === 0, 'exact new namespace is recoverable');
    assert(created.calls.includes('DELETE /api/entities/fn/new-fn')
      && created.calls.includes('DELETE /api/entities/ns/new-ns'), 'only staged UUIDs are deleted');
    const mismatch = makeCtx({fns: [{id: fn.id, name: fn.name, 'namespace-id': 'another-ns'}],
      namespaces: [{id: ns.id, name: ns.name, 'parent-id': 'different-parent'}]});
    assert((await mismatch.ctx._tourDeleteFns([fn])).length === 1, 'changed fn identity retains pending receipt');
    assert((await mismatch.ctx._tourDeleteNamespaces([ns])).length === 1, 'changed namespace identity retains pending receipt');
    assert(!mismatch.calls.some(call => call.startsWith('DELETE')), 'never mutate a mismatched manifest identity');
    const absent = makeCtx({});
    assert((await absent.ctx._tourDeleteFns([fn])).length === 0
      && (await absent.ctx._tourDeleteNamespaces([ns])).length === 0, 'a rolled back manifest has nothing to delete');
    assert(!absent.calls.some(call => call.startsWith('DELETE')), 'absent IDs never trigger name adoption');
    const incomplete = makeCtx({});
    assert((await incomplete.ctx._tourDeleteNamespaces([{...ns, 'branch-id': null}])).length === 1,
      'incomplete create-only provenance fails closed');
    assert(!incomplete.calls.length, 'unproven pending receipt is not reconciled');
  }),

  test('branch cleanup deletes children first and reports HTTP 200 refusals', async () => {
    const created = [{type: 'branch', name: 'parent'}, {type: 'branch', name: 'child'}];
    const success = makeCtx({});
    assert((await success.ctx._tourDeleteCreatedBranches(created)).length === 0,
      'successful cleanup reports no survivors');
    assert(success.calls.join(',') === 'DELETE /api/branches/child,DELETE /api/branches/parent',
      'children are deleted before their parent');
    const failure = makeCtx({branchFailure: true});
    const failed = await failure.ctx._tourDeleteCreatedBranches(created);
    assert(failed.length === 2, 'HTTP 200 with ok:false is a failure after retry');
    assert(failure.calls.length === 4, 'both failed deletions are retried once');
  }),

  test('retry accepts a child removed by the previous rollback attempt', async () => {
    const {ctx} = makeCtx({branchMissing: true});
    assert(await ctx._tourDeleteBranch('already-removed'),
      'the explicit not-found response means there is nothing left to delete');
  }),

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

  test('a reverse dependency chain longer than two passes is completely removed', async () => {
    const names = ['caller', 'middle', 'leaf'];
    const alive = new Set(names);
    const {ctx, calls} = makeCtx({
      fns: names.map((name) => FN(name)),
      refuse: (method, url) => {
        if (method !== 'DELETE') return false;
        const name = url.split('/id-')[1];
        const index = names.indexOf(name);
        if (index > 0 && alive.has(names[index - 1])) return true;
        alive.delete(name);
        return false;
      },
    });
    const failed = await ctx._tourDeleteFns(names.map((name) => ({type: 'fn', name})));
    assert(failed.length === 0 && alive.size === 0, 'all three dependency levels are deleted');
    assert(calls.filter((c) => c.startsWith('DELETE')).length === 6,
      'cleanup continues only while previous removals unblock another level');
  }),

  test('failed lookups stay in the cleanup offer and failure ledger', async () => {
    for (const refusal of [503, 'throw']) {
      const {ctx} = makeCtx({refuse: () => refusal});
      ctx._tourFindFn = () => null;
      const created = [{type: 'fn', name: 'unresolved'}];
      assert((await ctx._tourSurvivors(created)).length === 1,
        'an unavailable search does not hide the function');
      assert((await ctx._tourDeleteFns(created)).length === 1,
        'an unavailable search is not evidence of deletion');
    }
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

  test('successful deletion clears only the selected created UUID', async () => {
    const {ctx} = makeCtx({fns: [FN('selected')]});
    ctx.selectedFnId = 'id-selected';
    let clears = 0;
    ctx.gdClearSelection = () => {clears++; ctx.selectedFnId = null;};
    const result = await ctx._tourDeleteCreated([{type: 'fn', name: 'selected'}]);
    assert(result.failed.length === 0 && clears === 1 && ctx.selectedFnId === null,
      'authoritative success uses the shared empty-canvas/inspector mechanism');
    for (const refusal of [true, 403, 'throw']) {
      const rejected = makeCtx({fns: [FN('selected')], refuse: (method) => method === 'DELETE' && refusal});
      rejected.ctx.selectedFnId = 'id-selected';
      let rejectedClears = 0;
      rejected.ctx.gdClearSelection = () => {rejectedClears++;};
      const failed = await rejected.ctx._tourDeleteCreated([{type: 'fn', name: 'selected'}]);
      assert(failed.failed.length === 1 && rejected.ctx.selectedFnId === 'id-selected' && rejectedClears === 0,
        'failed/inaccessible deletion preserves selection: ' + refusal);
    }
    const surviving = makeCtx({fns: [FN('created'), FN('survivor')]});
    surviving.ctx.selectedFnId = 'id-survivor';
    let survivingClears = 0;
    surviving.ctx.gdClearSelection = () => {survivingClears++;};
    assert((await surviving.ctx._tourDeleteCreated([{type: 'fn', name: 'created'}])).failed.length === 0
      && surviving.ctx.selectedFnId === 'id-survivor' && survivingClears === 0, 'other surviving selection stays selected');
    const missing = makeCtx({refuse: () => 403});
    missing.ctx.selectedFnId = 'id-selected';
    let missingClears = 0;
    missing.ctx.gdClearSelection = () => {missingClears++;};
    await missing.ctx._tourDeleteCreated([{type: 'fn', name: 'selected'}]);
    assert(missing.ctx.selectedFnId === 'id-selected' && missingClears === 0, 'inaccessible lookup cannot clear selection');
  }),

  test('exact PIN goes before branch, exact release and owned empty namespace', async () => {
    const created = [NS('ns-1', 'mycorp'), PV(), PIN,
      {type: 'branch', id: 'site-id', name: 'site', 'base-branch-id': 'main-id'},
      NS('ns-2', 'mycorp@1-0-0')];
    const {ctx, calls} = makeCtx({
      namespaces: [{id: 'ns-1', name: 'mycorp'}, {id: 'ns-2', name: 'mycorp@1-0-0'}],
      packages: [PV(), {...PV('1.0.1'), id: 'someone-elses-release'}],
      installed: [{id: PIN.id, 'package-name': PIN.name, version: PIN.version, 'branch-id': PIN['branch-id']}],
      branches: [{id: 'site-id', name: 'site', 'base-branch-id': 'main-id'}],
    });
    const {failed} = await ctx._tourDeleteCreated(created);
    assert(failed.length === 0, 'a clean pass reports nothing');
    const unpin = calls.findIndex(c => c.includes('/packages/uninstall'));
    const branch = calls.findIndex(c => c === 'DELETE /api/branches/site-id');
    const withdraw = calls.findIndex(c => c.includes('/packages/withdraw'));
    const namespace = calls.findIndex(c => c.startsWith('DELETE /api/entities/ns/'));
    assert(unpin >= 0 && branch > unpin && withdraw > branch && namespace > withdraw,
      'remove pin before branch and release, then delete its namespace');
    assert(calls.filter(c => c.includes('/packages/withdraw')).length === 1
      && calls[withdraw].includes('expected-id=release-1.0.0'), 'never withdraw another visible version with the same name');
    assert(calls[unpin].includes('expected-id=pin-id'), 'conditional deletion targets the actual pin UUID');
  }),

  test('lost branch reply uses the staged UUID and exact base, never its name replacement', async () => {
    const pending = {type: 'branch', name: 'site', id: 'proposed-site',
      'base-branch-id': 'main-id', receipt: 'pending'};
    const t = makeCtx({branches: [{id: 'proposed-site', name: 'site', 'base-branch-id': 'main-id'}]});
    assert((await t.ctx._tourDeleteCreatedBranches([pending])).length === 0, 'exact committed branch is recoverable');
    assert(t.calls.includes('DELETE /api/branches/proposed-site'), 'remove the pre-staged UUID');
    const replacement = makeCtx({branches: [{id: 'other-site', name: 'site', 'base-branch-id': 'main-id'}]});
    assert((await replacement.ctx._tourDeleteCreatedBranches([pending])).length === 0, 'the staged UUID is already absent');
    assert(!replacement.calls.some(call => call.startsWith('DELETE')), 'never remove a same-name replacement');
    const moved = makeCtx({branches: [{id: 'proposed-site', name: 'site', 'base-branch-id': 'different'}]});
    assert((await moved.ctx._tourDeleteCreatedBranches([pending])).length === 1, 'changed base retains the receipt');
    assert(!moved.calls.some(call => call.startsWith('DELETE')), 'changed identity metadata refuses cleanup');
  }),

  test('a pending/lost pin receipt blocks deletion of its owned branch', async () => {
    const created = [{...PIN, id: null, receipt: 'pending'},
      {type: 'branch', id: 'site-id', name: 'site', 'base-branch-id': 'main-id'}];
    const {ctx, calls} = makeCtx({});
    const {failed} = await ctx._tourDeleteCreated(created);
    assert(failed.length === 2, 'both the unresolved pin and retained branch are reported');
    assert(!calls.some(c => c.startsWith('DELETE ')), 'unknown ownership cannot authorize deletion or lose its routing context');
    assert((await ctx._tourSurvivors(created)).length === 2, 'pending item stays visible after reload');
  }),

  test('namespace cleanup refuses unknown receipts and never sweeps added contents', async () => {
    const {ctx, calls} = makeCtx({namespaces: [{id: 'ns-1', name: 'mycorp'}],
      fns: [FN('someone-added', 'ns-1')], refuse: (m, u) => m === 'DELETE' && u === '/api/entities/ns/ns-1'});
    const failed = await ctx._tourDeleteNamespaces([NS('ns-1', 'mycorp'), {type: 'ns', name: 'existing'}]);
    assert(failed.length === 2, 'nonempty and unproven namespaces are retained');
    assert(!calls.some(c => c.includes('DELETE /api/entities/fn/')), 'never delete another edit merely because it is inside a created namespace');
  }),

  test('a refused namespace delete is reported', async () => {
    const created = [NS('ns-1', 'mycorp')];
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
      {type: 'branch', name: 'tutorial-17'},
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
      PV(),
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

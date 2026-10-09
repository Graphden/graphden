'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const dir = path.join(__dirname, '../../resources/packages/app/editor');
const read = file => fs.readFileSync(path.join(dir, file), 'utf8');
const saved = [];
const calls = [];
let rows = [];
let responseStatus = 200;
let malformed = false;
const ctx = vm.createContext({
  console, window: {}, JSON,
  localStorage: { setItem(key, value) { saved.push(JSON.parse(value)); } },
  _tourSessionBranch: () => 'review-inheritance',
  authFetch: async url => {
    calls.push(url);
    const identity = url.startsWith('/api/entities/fn/');
    const row = rows.find(row => row.id === url.split('/').at(-1));
    const status = identity && !row && responseStatus === 200 ? 404 : responseStatus;
    return {ok: status < 400, status,
      json: async () => malformed ? {} : identity
        ? (status === 404 && responseStatus === 200 ? {error: 'function-not-found'} : row)
        : {fns: rows}};
  },
  API: {api_graph_entities: '/api/graph/entities', api_entities_type_id: (type, id) => '/api/entities/' + type + '/' + id},
});
vm.runInContext(read('editor-tour.js'), ctx);
vm.runInContext(read('editor-tour-checks.js'), ctx);
vm.runInContext(read('editor-tour-cleanup.js'), ctx);
vm.runInContext(`
  _tourLessons = {lessons: [{id: '07', steps: [{creates: {type: 'fn', name: '_tutorial-cut-local'}}]}]};
  _tourState = {lessonId: '07', step: 0, created: []};
`, ctx);
const preview = {
  allowed: true, request: { action: 'variation', 'source-fn-id': 'P' },
  source: {id: 'P', name: 'source', 'namespace-id': 'own-ns'},
  proposed: { id: 'new-identity', name: '_tutorial-cut-local', 'namespace-id': 'own-ns' },
};
ctx._tourTrackInheritanceVariation(preview);
ctx._tourTrackInheritanceVariation(preview);
assert.equal(saved.length, 1, 'staging survives a lost APPLY response without duplicate entries');
const created = saved[0].created[0];
assert.equal(created.id, 'new-identity');
assert.equal(saved[0].activeBranch, 'review-inheritance');

(async () => {
  rows = [{ id: 'different-identity', name: created.name, 'namespace-id': 'own-ns' }];
  assert.equal(await ctx._tourFnIdForCreation(created), null, 'same name never substitutes for missing UUID');
  rows = [{ ...created, 'namespace-id': 'moved-ns' }];
  await assert.rejects(ctx._tourFnIdForCreation(created), /identity changed/);
  rows = [{ ...created, name: 'renamed' }];
  await assert.rejects(ctx._tourFnIdForCreation(created), /identity changed/);
  rows = [{ ...created }];
  assert.equal(await ctx._tourFnIdForCreation(created), created.id);
  assert(calls.every(url => url === '/api/entities/fn/new-identity'));
  responseStatus = 403;
  await assert.rejects(ctx._tourFnIdForCreation(created), /lookup failed/);
  assert.equal((await ctx._tourSurvivors([created])).length, 1, 'an inaccessible identity stays in manual review');
  responseStatus = 404;
  await assert.rejects(ctx._tourFnIdForCreation(created), /lookup failed/, 'missing branch is not missing function');
  responseStatus = 200;
  malformed = true;
  await assert.rejects(ctx._tourFnIdForCreation(created), /Invalid function identity/);
  malformed = false;

  ctx.lookups = { fnMap: new Map([
    ['F', { id: 'F', name: 'child', 'namespace-id': 'own-ns', 'parent-ids': ['new-identity'] }],
    ['P', { id: 'P', name: 'source', 'parent-ids': ['base'] }],
    ['new-identity', { ...created, 'parent-ids': ['base'] }],
  ]) };
  const check = { kind: 'fn-sibling-variation', name: 'child', source: 'source', variation: created.name };
  assert.equal(ctx._tourCheckPasses(check), true);
  vm.runInContext("_tourStep().check = {kind: 'fn-sibling-variation', name: 'child', source: 'source', variation: '_tutorial-cut-local'}", ctx);
  ctx.lookups.fnMap.delete('P');
  assert.equal(ctx._tourCheckPasses(check), false, 'an absent source is not inferred from copy/name');
  rows = [{id: 'P', name: 'source', 'namespace-id': 'own-ns', 'parent-ids': ['base']}];
  await ctx._tourLoadInheritanceCheckSource(preview);
  assert(calls.at(-1).endsWith('scope=subtree&root-id=P'), 'original source is read by canonical UUID');
  assert(!ctx.lookups.fnMap.has('P'), 'verification does not inject source into the graph cache');
  assert.equal(ctx._tourCheckPasses(check), true, 'authoritative source proof survives the new closure dropping P');
  ctx._tourSessionBranch = () => 'other-branch';
  assert.equal(ctx._tourCheckPasses(check), false, 'proof cannot cross branch scope');
  ctx._tourSessionBranch = () => 'review-inheritance';
  vm.runInContext("_tourState.created[0]['verified-source'].id = 'different-source'", ctx);
  assert.equal(ctx._tourCheckPasses(check), false, 'proof must match the canonical original source UUID');
  vm.runInContext("delete _tourState.created[0]['verified-source']", ctx);
  responseStatus = 403;
  await assert.rejects(ctx._tourLoadInheritanceCheckSource(preview), /unavailable/);
  assert.equal(ctx._tourCheckPasses(check), false, 'failed read cannot pass the gate');
  responseStatus = 200;
  rows = [{id: 'wrong-id', name: 'source', 'namespace-id': 'own-ns', 'parent-ids': ['base']}];
  await assert.rejects(ctx._tourLoadInheritanceCheckSource(preview), /unexpected identity/);
  rows = [{id: 'P', name: 'source', 'namespace-id': 'own-ns', 'parent-ids': 'base'}];
  await assert.rejects(ctx._tourLoadInheritanceCheckSource(preview), /unexpected identity/);
  rows = [{id: 'P', name: 'source', 'namespace-id': 'own-ns', 'parent-ids': ['base']}];
  const originalFetch = ctx.authFetch;
  let resolveSource;
  ctx.authFetch = async () => ({ok: true, json: () => new Promise(resolve => { resolveSource = resolve; })});
  const sourceRead = ctx._tourLoadInheritanceCheckSource(preview);
  await new Promise(resolve => setImmediate(resolve));
  ctx._tourSessionBranch = () => 'other-branch';
  resolveSource({fns: rows});
  await sourceRead;
  assert.equal(ctx._tourCheckPasses(check), false, 'a read completed after branch change cannot publish proof');
  ctx._tourSessionBranch = () => 'review-inheritance';
  assert.equal(ctx._tourCheckPasses(check), false, 'returning to the branch cannot revive discarded proof');
  const endedRead = ctx._tourLoadInheritanceCheckSource(preview);
  await new Promise(resolve => setImmediate(resolve));
  const activeState = vm.runInContext('_tourState', ctx);
  vm.runInContext('_tourState = null', ctx);
  resolveSource({fns: rows});
  await endedRead;
  assert.equal(activeState.created[0]['verified-source'], undefined, 'tour disposal discards the in-flight proof');
  ctx._tourState = activeState;
  ctx.authFetch = originalFetch;
  await ctx._tourLoadInheritanceCheckSource(preview);
  ctx.lookups.fnMap.get('new-identity')['namespace-id'] = 'different-ns';
  assert.equal(ctx._tourCheckPasses(check), false);
  ctx.lookups.fnMap.get('new-identity')['namespace-id'] = 'own-ns';
  ctx.lookups.fnMap.get('new-identity')['parent-ids'] = ['P'];
  assert.equal(ctx._tourCheckPasses(check), false, 'an extending child is not a sibling copy');
  const effects = [];
  const intentCtx = vm.createContext({
    console, document: {activeElement: null}, installPopoverDismiss() {}, registerActionHandler() {},
    applyInheritancePreview: async () => ({ok: true, committed: true, 'created-fn-id': 'new-identity'}),
    _tourLoadInheritanceCheckSource: async () => { throw new Error('Source verification is unavailable.'); },
    gdToast: message => effects.push(['warning', message]),
    initGraph: async () => effects.push(['refresh']),
  });
  vm.runInContext(read('editor-inheritance-intent.js'), intentCtx);
  intentCtx.navigateInheritanceSource = async id => effects.push(['navigate', id]);
  await intentCtx.applyIntentPreview(preview);
  assert.deepEqual(effects, [
    ['warning', 'Change saved. Source verification is unavailable.'],
    ['refresh'], ['navigate', 'new-identity'],
  ], 'failed lesson proof is a committed warning and preserves refresh plus UUID navigation');
  console.log('✓ variation tutorial: UUID staging, branch persistence, safe cleanup, sibling identity check');
})().catch(error => { console.error(error); process.exitCode = 1; });

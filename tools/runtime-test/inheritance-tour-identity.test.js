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
    return { ok: responseStatus < 400, status: responseStatus,
      json: async () => malformed ? {} : ({ fns: rows }) };
  },
  API: { api_graph_entities: '/api/graph/entities' },
});
vm.runInContext(read('editor-tour.js'), ctx);
vm.runInContext(read('editor-tour-checks.js'), ctx);
vm.runInContext(read('editor-tour-cleanup.js'), ctx);
vm.runInContext(`
  _tourLessons = {lessons: [{id: '07', steps: [{creates: {type: 'fn', name: '_tutorial-cut-local'}}]}]};
  _tourState = {lessonId: '07', step: 0, created: []};
`, ctx);
const preview = {
  allowed: true, request: { action: 'variation' },
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
  assert(calls.every(url => url.endsWith('scope=subtree&root-id=new-identity')));
  responseStatus = 403;
  await assert.rejects(ctx._tourFnIdForCreation(created), /lookup failed/);
  assert.equal((await ctx._tourSurvivors([created])).length, 1, 'an inaccessible identity stays in manual review');
  responseStatus = 404;
  await assert.rejects(ctx._tourFnIdForCreation(created), /lookup failed/, 'missing branch is not missing function');
  responseStatus = 200;
  malformed = true;
  await assert.rejects(ctx._tourFnIdForCreation(created), /Invalid function lookup/);
  malformed = false;

  ctx.lookups = { fnMap: new Map([
    ['F', { id: 'F', name: 'child', 'namespace-id': 'own-ns', 'parent-ids': ['new-identity'] }],
    ['P', { id: 'P', name: 'source', 'parent-ids': ['base'] }],
    ['new-identity', { ...created, 'parent-ids': ['base'] }],
  ]) };
  const check = { kind: 'fn-sibling-variation', name: 'child', source: 'source', variation: created.name };
  assert.equal(ctx._tourCheckPasses(check), true);
  ctx.lookups.fnMap.get('new-identity')['namespace-id'] = 'different-ns';
  assert.equal(ctx._tourCheckPasses(check), false);
  ctx.lookups.fnMap.get('new-identity')['namespace-id'] = 'own-ns';
  ctx.lookups.fnMap.get('new-identity')['parent-ids'] = ['P'];
  assert.equal(ctx._tourCheckPasses(check), false, 'an extending child is not a sibling copy');
  console.log('✓ variation tutorial: UUID staging, branch persistence, safe cleanup, sibling identity check');
})().catch(error => { console.error(error); process.exitCode = 1; });

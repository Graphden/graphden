'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../../resources/packages/app/editor/editor-tour-structured-input.js'), 'utf8');
const template = 'abd1ff8f-3ae3-57c4-ad64-412ef0689f78';
const lesson = {id: '43', 'template-id': template};
const legacy = {id: '15'};
const payload = {lessons: [legacy, lesson]};
const field = name => ({name, kind: 'input-field', type: 'text'});
const available = fields => ({status: 'available', 'run-available': true,
  fields: [{name: 'profile', fields}]});
const templateInput = {...available(['id', 'name', 'email'].map(field)),
  'extend-available': true, 'extend-namespace-id': null};
const childInput = available([field('id'), {...field('name'), 'bind-path': ['profile', 'name']}, field('email')]);
const childReceipt = {type: 'fn', name: 'tutorial-structured-profile', id: 'own-child', 'parent-id': template,
  'namespace-id': null, 'branch-id': 'sandbox-id', receipt: 'created'};
const sandbox = {type: 'branch', name: 'tutorial-43-test', id: 'sandbox-id', 'base-branch-id': 'main-id', receipt: 'created'};
const saved = {lessonId: '43', sandboxBranch: sandbox.name, sandboxBranchId: sandbox.id,
  sandboxBaseBranchId: sandbox['base-branch-id'], created: [sandbox, childReceipt]};

function boot() {
  let branch = 'main';
  let principalMatches = true;
  let exactBranch = true;
  let response = {id: template, 'structured-input': templateInput};
  const reads = [];
  const ctx = vm.createContext({
    API: {api_graph_entities: '/api/graph/entities', api_branches: '/api/branches'},
    isAuthenticated: () => true,
    _tourStartTicket: () => ({branch}),
    _tourStartCurrent: ticket => ticket?.branch === branch,
    _tourPrincipalMatches: () => principalMatches,
    _tourSessionBranch: () => branch,
    _tourOwnedBranch: state => state?.sandboxBranch,
    _tourBranchStatus: async () => exactBranch ? 'available' : 'missing',
    _tourState: null, _tourLoadState: () => null,
    _tourStep: () => ({creates: {type: 'fn', name: childReceipt.name, 'parent-id': template}}),
    switchToBranch() {},
    authFetch: async url => { reads.push(url); return {ok: true, json: async () => ({fns: [response]})}; },
  });
  ctx.window = ctx;
  vm.runInContext(source, ctx);
  return {ctx, reads, setBranch: name => {branch = name;}, setResponse: value => {response = value;},
    setPrincipal: value => {principalMatches = value;}, setExactBranch: value => {exactBranch = value;}};
}

async function run() {
  const t = boot();
  const scope = current => t.ctx._tourScopeStructuredLesson(payload, current || (() => true));
  assert.strictEqual(await scope(), payload);
  assert.equal(t.ctx._tourStructuredLessonProved(lesson), true);
  assert.equal(t.reads.at(-1), '/api/graph/entities?scope=subtree&root-id=' + template);

  const missingNamespace = {...templateInput};
  delete missingNamespace['extend-namespace-id'];
  for (const bad of [
    {id: 'same-name-impostor', 'structured-input': templateInput},
    {id: template, 'structured-input': missingNamespace},
    {id: template, 'structured-input': {...templateInput, 'run-available': false}},
    {id: template, 'structured-input': {...templateInput, 'extend-available': false}},
    {id: template, 'structured-input': available([field('id'), field('email')])},
  ]) {
    t.setResponse(bad);
    assert.deepEqual(Array.from((await scope()).lessons), [legacy]);
    assert.equal(t.ctx._tourStructuredLessonProved(lesson), false);
  }

  t.ctx._tourState = saved;
  t.setBranch(sandbox.name);
  t.setResponse({id: childReceipt.id, 'parent-ids': [template], 'structured-input': childInput});
  assert.strictEqual(await scope(), payload);
  assert.equal(t.reads.at(-1), '/api/graph/entities?scope=subtree&root-id=' + childReceipt.id);
  assert.equal(t.ctx._tourStructuredExtend({id: 'impostor'}), null);
  assert.equal(t.ctx._tourStructuredExtend({id: template}).name, childReceipt.name);

  const boundInput = {...available([field('id'), field('email')]),
    occupied: [{type: 'text', path: ['profile', 'name'], 'binding-id': 'binding', 'version-id': 'version'}]};
  t.setResponse({id: childReceipt.id, 'parent-ids': [template], 'structured-input': boundInput});
  assert.strictEqual(await scope(), payload, 'a fresh bound child can resume');
  t.setResponse({id: childReceipt.id, 'parent-ids': ['other'], 'structured-input': childInput});
  assert.deepEqual(Array.from((await scope()).lessons), [legacy], 'external reparent hides the sample');
  t.setResponse({id: childReceipt.id, 'parent-ids': [template], 'structured-input': childInput});
  t.setExactBranch(false);
  assert.deepEqual(Array.from((await scope()).lessons), [legacy], 'same branch name cannot replace the receipt UUID');
  t.setExactBranch(true);
  t.setPrincipal(false);
  assert.deepEqual(Array.from((await scope()).lessons), [legacy]);
  t.setPrincipal(true);
  assert.deepEqual(Array.from((await scope(() => false)).lessons), [legacy], 'a stale catalogue load cannot advertise the lesson');
  console.log('✓ tutorial 43: proof, exact sandbox/child, resumed binding and fail-closed catalogue');
}

run().catch(error => { console.error(error); process.exitCode = 1; });

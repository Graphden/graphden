'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../../resources/packages/app/editor/editor-tour-structured-input.js'), 'utf8');
const template = 'profile-template';
const lesson = {id: '43', 'template-id': template};
const legacy = {id: '15'};
const payload = {lessons: [legacy, lesson], 'structured-templates': {'43': template}};
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
    _tourLessons: payload, _tourState: null, _tourLoadState: () => null,
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
  t.ctx._tourState = {...saved, created: {}};
  assert.deepEqual(Array.from((await scope()).lessons), [legacy], 'a damaged receipt leaves other lessons available');
  t.ctx._tourState = saved;
  t.setExactBranch(false);
  assert.deepEqual(Array.from((await scope()).lessons), [legacy], 'same branch name cannot replace the receipt UUID');
  t.setExactBranch(true);
  t.setPrincipal(false);
  assert.deepEqual(Array.from((await scope()).lessons), [legacy]);
  t.setPrincipal(true);
  assert.deepEqual(Array.from((await scope(() => false)).lessons), [legacy], 'a stale catalogue load cannot advertise the lesson');

  const contactTemplate = 'contact-template';
  const contactLesson = {id: '44', 'template-id': contactTemplate};
  const contactFields = ['sender', 'recipient'].map(name => ({
    name, fields: [field('name'), {name: 'address', fields: [field('city')]}],
  }));
  const contactInput = {...templateInput, fields: contactFields};
  t.ctx._tourState = null;
  t.setBranch('main');
  t.setResponse({id: contactTemplate, 'structured-input': contactInput});
  const pairedPayload = {lessons: [legacy, contactLesson, lesson],
    'structured-templates': {'43': template, '44': contactTemplate}};
  assert.deepEqual(Array.from((await t.ctx._tourScopeStructuredLesson(pairedPayload, () => true)).lessons),
    [legacy, contactLesson], 'one template offer cannot authorize the other lesson');
  const impostor = {...contactLesson, 'template-id': template};
  t.setResponse({id: template, 'structured-input': contactInput});
  assert.deepEqual(Array.from((await t.ctx._tourScopeStructuredLesson({...pairedPayload, lessons: [legacy, impostor]}, () => true)).lessons),
    [legacy], 'a mismatched template cannot substitute for the known package owner');
  const occupied = side => ({type: 'text', path: [side, 'address', 'city'], 'binding-id': side, 'version-id': side});
  const boundContacts = {...contactInput, fields: contactFields.map(({name}) => ({name, fields: [field('name')]})),
    occupied: ['sender', 'recipient'].map(occupied)};
  assert.equal(t.ctx._tourStructuredContacts(boundContacts, true), true);
  assert.equal(t.ctx._tourStructuredContacts({...boundContacts, occupied: [occupied('sender')]}, true), false);
  const optionalTemplate = 'optional-template';
  const optionalLesson = {id: '45', 'template-id': optionalTemplate};
  const optionalLabel = {...field('label'), type: ['union', 'null', 'text'],
    required: false, 'required-now?': false};
  const optionalInput = {...templateInput, fields: [optionalLabel]};
  const optionalPayload = {lessons: [contactLesson, optionalLesson], 'structured-templates': {'45': optionalTemplate}};
  const scopeOptional = data => t.ctx._tourScopeStructuredLesson(data, () => true);
  t.setResponse({id: optionalTemplate, 'structured-input': optionalInput});
  assert.deepEqual(Array.from((await scopeOptional(optionalPayload)).lessons), [optionalLesson]);
  const reads = t.reads.length;
  assert.deepEqual(Array.from((await scopeOptional({lessons: optionalPayload.lessons})).lessons), []);
  assert.deepEqual(Array.from((await scopeOptional({...optionalPayload, 'structured-templates': {'45': contactTemplate}})).lessons), []);
  assert.equal(t.reads.length, reads, 'missing and mismatched catalogue proofs fail before a subtree read');
  assert.equal(t.ctx._tourStructuredOptional({...optionalInput, fields: [{...optionalLabel, required: true}]}, false), false);
  assert.equal(t.ctx._tourStructuredOptional(optionalInput, true), false, 'free label must retain its Bind offer');
  const childLabel = {...optionalLabel, 'bind-path': ['label']};
  assert.equal(t.ctx._tourStructuredOptional({...optionalInput, fields: [childLabel]}, true), true);
  const boundLabel = {...optionalInput, fields: [], occupied: [
    {type: optionalLabel.type, path: ['label'], 'binding-id': 'binding', 'version-id': 'version'},
  ]};
  assert.equal(t.ctx._tourStructuredOptional(boundLabel, true), true, 'saved nil has an occupied field and can resume');
  assert.equal(t.ctx._tourStructuredOptional(boundLabel, false), false, 'the source template cannot start with a bound label');
  const optionalBranch = {...sandbox, name: 'tutorial-45-test'};
  const optionalChild = {...childReceipt, id: 'optional-child', name: 'tutorial-optional-label',
    'parent-id': optionalTemplate};
  t.ctx._tourState = {...saved, lessonId: '45', step: 7, sandboxBranch: optionalBranch.name,
    created: [optionalBranch, optionalChild]};
  t.ctx._tourLessons = optionalPayload;
  t.setBranch(optionalBranch.name);
  t.setResponse({id: optionalChild.id, 'parent-ids': [optionalTemplate], 'structured-input': boundLabel});
  assert.deepEqual(Array.from((await scopeOptional(optionalPayload)).lessons), [optionalLesson]);
  assert.equal(t.reads.at(-1), '/api/graph/entities?scope=subtree&root-id=' + optionalChild.id);
  assert.equal(t.ctx._tourStructuredChild(t.ctx._tourState, optionalTemplate), optionalChild);
  t.ctx._tourState.created[1] = {...optionalChild, 'parent-id': contactTemplate};
  assert.equal(t.ctx._tourStructuredChild(t.ctx._tourState, optionalTemplate), undefined);
  assert.deepEqual(Array.from((await scopeOptional(optionalPayload)).lessons), []);
  console.log('✓ structured tutorials: exact templates, sandbox ownership, independent and optional fields, and resume');
}

run().catch(error => { console.error(error); process.exitCode = 1; });

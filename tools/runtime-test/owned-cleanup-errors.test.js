'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {unexpectedCleanupErrors} = require('../browser-test/owned-cleanup-errors');
const {componentCleanupTimeout} = require('../browser-test/tutorial-ui-components-helpers');
const url = 'http://localhost/api/entities/fn/owned';
const error = {kind: 'console', url, text: 'Failed to load resource: the server responded with a status of 409 (Conflict)'};
const responses = [{url, status: 409}, {url, status: 204}];
test('only counted owned dependency refusals followed by deletion and absence are accepted', () => {
  const own = new Set(['owned']);
  assert.deepEqual(unexpectedCleanupErrors([error], responses, own, true), []);
  for (const args of [[responses, own, false], [responses, new Set(), true], [responses.slice(0, 1), own, true]]) {
    assert.equal(unexpectedCleanupErrors([error], ...args).length, 1);
  }
  assert.equal(unexpectedCleanupErrors([error, error], responses, own, true).length, 1);
  for (const changed of [{...error, kind: 'pageerror'}, {...error, text: 'unknown'}, {...error, url: url + '?other'}]) {
    assert.equal(unexpectedCleanupErrors([changed], responses, own, true).length, 1);
  }
});
test('cleanup deadline budgets real ownership reads, deletes and retries for large manifests', () => {
  assert.equal(componentCleanupTimeout({functions: Array(225), namespaces: Array(4)}), 2290000);
  assert.equal(componentCleanupTimeout({functions: [], namespaces: []}), 120000);
});

function lessonCleanupFixture({absent = false, confirmed = true, foreign = false} = {}) {
  const fs = require('node:fs'), vm = require('node:vm');
  const calls = [], rows = [{id: 'owned', name: 'editor', 'namespace-id': 'home'}];
  const original = {id: 'owned', created: true, previous: null, 'function-receipts': rows,
    'configuration-revision': 'original-revision', 'cohort-revision': 'original-cohort', 'restore-revision': 'restore-anchor'};
  const lesson = {request: {scope: {'principal-id': 'owner', org: 'org', 'branch-id': 'branch-id'}}, original, 'branch-name': 'main'};
  const reviewed = {'current-id': 'owned', previous: null, 'expected-configuration-revision': 'reviewed-revision', functions: rows};
  const sandbox = {module: {exports: {}}, require(name) {
    if (name === './edit-test-helpers') return {assert, api: async (_page, method, path, body) => {
      calls.push({method, path, body});
      if (path.includes('entities?')) return {fns: absent ? [] : rows};
      if (path.endsWith('reconcile-undo')) return {ok: true, confirmed, undone: confirmed};
      if (path.endsWith('cleanup-preview')) return {ok: true, kind: 'editor-configuration-cleanup-review', request: reviewed, delete: rows};
      throw Error('unexpected cleanup endpoint');
    }};
    if (name === './configuration-undo-test-helpers') return {undoConfiguration: async (_page, _proposal, command) => {
      calls.push({method: 'UNDO', command}); return {ok: true};
    }};
    throw Error('unexpected dependency');
  }};
  vm.runInNewContext(fs.readFileSync(require.resolve('../browser-test/partial-ui-components-fixture'), 'utf8'), sandbox);
  const page = {evaluate: async () => ({owner: foreign ? 'other' : 'owner', org: 'org', branch: 'main'})};
  return {restore: () => sandbox.module.exports.restoreLessonSelection(page, lesson), calls, reviewed};
}

test('failed lesson cleanup submits the exact reviewed cohort before any outer restoration', async () => {
  const fixture = lessonCleanupFixture();
  await fixture.restore();
  assert.equal(fixture.calls[1].body['restore-revision'], 'restore-anchor');
  assert.equal(fixture.calls[2].command, fixture.reviewed, 'the reviewed request is submitted unchanged');
  assert.equal(fixture.calls.filter(call => call.method === 'UNDO').length, 1);
});

test('absence alone cannot confirm a completed lesson cleanup', async () => {
  const good = lessonCleanupFixture({absent: true}); await good.restore();
  assert.equal(good.calls[1].path, '/api/ui/configuration/reconcile-undo');
  assert.equal(good.calls[1].body['restore-revision'], 'restore-anchor');
  const uncertain = lessonCleanupFixture({absent: true, confirmed: false});
  await assert.rejects(uncertain.restore(), /restore witness/);
  assert.equal(uncertain.calls.some(call => call.method === 'UNDO'), false);
});

test('lesson cleanup refuses a foreign owner before reading or mutating graph rows', async () => {
  const fixture = lessonCleanupFixture({foreign: true});
  await assert.rejects(fixture.restore(), /owner, organization and branch/);
  assert.equal(fixture.calls.length, 0);
});

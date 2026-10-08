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

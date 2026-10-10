'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {unexpectedSelectiveCopyErrors} = require('../browser-test/selective-copy-errors');
const url = 'http://localhost/api/ui/components/plan';
const error = {kind: 'console', url, text: 'Failed to load resource: the server responded with a status of 422 (Unprocessable Entity)'};
const missing = {kind: 'plan', url, component: 'account-menu', status: 422, code: 'component-missing', fallback: 'builtin'};
test('verified typed missing-component fallback permits only its exact network-console occurrence', () => {
  assert.deepEqual(unexpectedSelectiveCopyErrors([error], [missing], true), []);
  assert.deepEqual(unexpectedSelectiveCopyErrors([error, error], [missing], true), [error]);
});
test('unknown terminal refusals, wrong URLs, page errors and unverified behavior remain fatal', () => {
  for (const reply of [{...missing, code: 'tainted-result'}, {...missing, fallback: null}, {...missing, component: 'unknown'}, {...missing, url: url + '/other'}])
    assert.deepEqual(unexpectedSelectiveCopyErrors([error], [reply], true), [error]);
  assert.deepEqual(unexpectedSelectiveCopyErrors([error], [missing], false), [error]);
  const pageError = {...error, kind: 'pageerror'};
  assert.deepEqual(unexpectedSelectiveCopyErrors([pageError], [missing], true), [pageError]);
});
test('verified stale activation permits exactly the sanitized conflict response', () => {
  const conflictError = {...error, url: 'http://localhost/api/prefs/components', text: 'Failed to load resource: the server responded with a status of 409 (Conflict)'};
  const reply = {kind: 'activation', url: conflictError.url, status: 409, code: 'stale-preference', noValue: true};
  assert.deepEqual(unexpectedSelectiveCopyErrors([conflictError], [reply], true), []);
  assert.deepEqual(unexpectedSelectiveCopyErrors([conflictError], [{...reply, noValue: false}], true), [conflictError]);
});

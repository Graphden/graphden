'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {unexpectedThemeRetryErrors} = require('../browser-test/theme-retry-errors');
const url = 'http://localhost/api/ui/theme/evaluate';
const error = {kind: 'console', url, text: 'Failed to load resource: the server responded with a status of 422 (Unprocessable Entity)'};
const retry = {url, status: 422, sequence: 1, identity: 'exact-source-context',
  code: 'graph-changed', reason: 'result-unavailable', retryable: true};
const success = {url, status: 200, sequence: 2, identity: retry.identity, ok: true, payload: true};
test('verified exact selected theme recovery waives one network error only', () => {
  assert.deepEqual(unexpectedThemeRetryErrors([error], [retry, success], true), []);
  assert.deepEqual(unexpectedThemeRetryErrors([error, error], [retry, success], true), [error]);
});
test('terminal, stale, cross-context and unverified theme replies stay fatal', () => {
  for (const changed of [{code: 'tainted-result'}, {reason: 'timeout'}, {retryable: false}, {identity: null}])
    assert.deepEqual(unexpectedThemeRetryErrors([error], [{...retry, ...changed}, success], true), [error]);
  for (const changed of [{identity: 'other-source'}, {url: url + '/other'}, {sequence: 0}, {ok: false}, {payload: false}])
    assert.deepEqual(unexpectedThemeRetryErrors([error], [retry, {...success, ...changed}], true), [error]);
  assert.deepEqual(unexpectedThemeRetryErrors([error], [retry], true), [error]);
  assert.deepEqual(unexpectedThemeRetryErrors([error], [retry, success], false), [error]);
  for (const changed of [{kind: 'pageerror'}, {text: 'Other console error'}, {url: url + '/other'}]) {
    const actual = {...error, ...changed};
    assert.deepEqual(unexpectedThemeRetryErrors([actual], [retry, success], true), [actual]);
  }
});

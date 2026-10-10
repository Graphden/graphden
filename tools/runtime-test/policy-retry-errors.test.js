'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {unexpectedPolicyRetryErrors} = require('../browser-test/policy-retry-errors');
const url = 'https://editor.test/api/ui/components/plan';
const error = {kind: 'console', url, text: 'Failed to load resource: the server responded with a status of 422 (Unprocessable Entity)'};
const refusal = {url, sequence: 1, component: 'account-menu', status: 422, code: 'policy-refresh-required', retryable: true};
const success = {url, sequence: 2, component: 'account-menu', status: 200, ok: true};
test('only a classified retry recovered by the same component waives one network error', () => {
  assert.deepEqual(unexpectedPolicyRetryErrors([error], [refusal, success], true), []);
  assert.deepEqual(unexpectedPolicyRetryErrors([error, error], [refusal, success], true), [error]);
});
test('terminal, unknown, unmatched and unverified replies remain failures', () => {
  for (const replies of [[refusal], [{...refusal, retryable: false}, success],
    [{...refusal, code: 'tainted-result'}, success], [refusal, {...success, component: 'recents'}],
    [refusal, {...success, url: url + '/other'}], [refusal, {...success, sequence: 0}],
    [refusal, {...success, ok: false}], [refusal, success, {...refusal, sequence: 3}],
    [refusal, success, {...refusal, sequence: 3, code: 'unknown'}], [{...refusal, component: null}, success]]) {
    assert.deepEqual(unexpectedPolicyRetryErrors([error], replies, true), [error]);
  }
  assert.deepEqual(unexpectedPolicyRetryErrors([error], [refusal, success], false), [error]);
});
test('page errors and unrelated console errors are always fatal', () => {
  for (const e of [{...error, kind: 'pageerror'}, {...error, text: 'runtime failed'}, {...error, url: url + '/other'}]) {
    assert.deepEqual(unexpectedPolicyRetryErrors([e], [refusal, success], true), [e]);
  }
});

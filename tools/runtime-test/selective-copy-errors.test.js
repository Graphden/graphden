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
  assert.deepEqual(unexpectedSelectiveCopyErrors([error], [{...missing, component: 'inspector'}], true), []);
  assert.deepEqual(unexpectedSelectiveCopyErrors([error], [{...missing, component: 'explorer'}], true), []);
  assert.deepEqual(unexpectedSelectiveCopyErrors([error], [{...missing, component: 'unknown'}], true), [error]);
  assert.deepEqual(unexpectedSelectiveCopyErrors([error], [{...missing, component: 'inspector'}], false), [error]);
});
test('restoring a partial manifest recovers a policy refusal only through its later exact component fallback', () => {
  const retry = {kind: 'plan', url, component: missing.component, status: 422,
    code: 'policy-refresh-required', retryable: true, sequence: 1};
  const restored = {...missing, sequence: 2};
  assert.deepEqual(unexpectedSelectiveCopyErrors([error, error], [retry, restored], true), []);
  for (const changed of [{component: 'recents'}, {sequence: 0}, {url: url + '/other'}, {fallback: null}, {code: 'tainted-result'}])
    assert.deepEqual(unexpectedSelectiveCopyErrors([error, error], [retry, {...restored, ...changed}], true), [error, error]);
  assert.deepEqual(unexpectedSelectiveCopyErrors([error, error], [retry, restored], false), [error, error]);
});
test('tutorial catalog policy retry needs an exact later success and verified behavior', () => {
  const refusal = {kind: 'plan', url, component: 'tutorial-catalog', status: 422,
    code: 'policy-refresh-required', retryable: true, sequence: 7};
  const success = {...refusal, status: 200, ok: true, sequence: 8};
  assert.deepEqual(unexpectedSelectiveCopyErrors([error], [refusal, success], true), []);
  assert.deepEqual(unexpectedSelectiveCopyErrors([error, error], [refusal, success], true), [error]);
  assert.deepEqual(unexpectedSelectiveCopyErrors([error], [refusal, success], false), [error]);
  for (const change of [{component: 'unknown'}, {code: 'tainted-result'}, {retryable: false}]) {
    assert.deepEqual(unexpectedSelectiveCopyErrors([error], [{...refusal, ...change}, success], true), [error]);
  }
  for (const change of [{component: 'recents'}, {url: url + '/other'},
    {sequence: 7}, {status: 409}, {ok: false}]) {
    assert.deepEqual(unexpectedSelectiveCopyErrors([error], [refusal, {...success, ...change}], true), [error]);
  }
  assert.deepEqual(unexpectedSelectiveCopyErrors([error], [refusal], true), [error]);
  assert.deepEqual(unexpectedSelectiveCopyErrors([{...error, kind: 'pageerror'}], [refusal, success], true),
    [{...error, kind: 'pageerror'}]);
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
test('partial-manifest fallback and recovered policy replies have separate bounded allowances', () => {
  const retry = {kind: 'plan', url, component: 'inspector', status: 422,
    code: 'policy-refresh-required', retryable: true, sequence: 2};
  const success = {...retry, status: 200, ok: true, sequence: 3};
  const replies = [missing, retry, success];
  assert.deepEqual(unexpectedSelectiveCopyErrors([error, error], replies, true), []);
  assert.deepEqual(unexpectedSelectiveCopyErrors([error, error, error], replies, true), [error]);
  assert.deepEqual(unexpectedSelectiveCopyErrors([error, error], replies, false), [error, error]);
  const pageError = {...error, kind: 'pageerror'};
  assert.deepEqual(unexpectedSelectiveCopyErrors([error, error, pageError], replies, true), [pageError]);
  assert.deepEqual(unexpectedSelectiveCopyErrors([error, error],
    [missing, {...missing, code: 'tainted-result'}], true), [error, error]);
  assert.deepEqual(unexpectedSelectiveCopyErrors([error, error],
    [missing, {...missing, component: 'unknown'}], true), [error, error]);
  assert.deepEqual(unexpectedSelectiveCopyErrors([error], [missing, retry], true), [error]);
});

test('observed policy recovery requires the same nonmissing loader identity', () => {
  const retry = {kind: 'plan', url, component: 'canvas-type-structure', status: 422,
    code: 'policy-refresh-required', retryable: true, sequence: 1, loadId: 4, configuration: 'same-root'};
  const success = {...retry, status: 200, ok: true, sequence: 2};
  assert.deepEqual(unexpectedSelectiveCopyErrors([error], [retry, success], true, true), []);
  for (const changed of [{loadId: 5}, {loadId: null}, {loadId: undefined}, {component: 'canvas-bindings'}])
    assert.deepEqual(unexpectedSelectiveCopyErrors([error], [retry, {...success, ...changed}], true, true), [error]);
  assert.deepEqual(unexpectedSelectiveCopyErrors([error], [retry], true, true), [error]);
  assert.deepEqual(unexpectedSelectiveCopyErrors([error], [{...retry, code: null}, success], true, true), [error]);
});

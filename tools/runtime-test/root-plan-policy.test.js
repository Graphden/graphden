'use strict';
const assert = require('node:assert/strict');
const {storedRootPlan} = require('../browser-test/root-plan-test-helpers');

const refusal = {ok: false, code: 'policy-refresh-required', retryable: true, 'retry-after': 1};
assert.deepEqual(storedRootPlan({status: 422, ok: false, body: refusal}), {pending: true, delayMs: 1000});
assert.deepEqual(storedRootPlan({status: 409, ok: false, body: null}), {pending: true, delayMs: 0});
const plan = {functions: []};
assert.deepEqual(storedRootPlan({status: 200, ok: true, body: {ok: true, plan}}), {plan});
assert.deepEqual(storedRootPlan({status: 422, ok: false, body: {...refusal, plan}}), {pending: true, delayMs: 1000});
for (const receipt of [
  {status: 403, ok: false, body: refusal},
  {status: 500, ok: false, body: refusal},
  {status: 422, ok: false, body: null},
  {status: 422, ok: false, body: {...refusal, ok: true}},
  {status: 422, ok: false, body: {...refusal, ok: undefined}},
  {status: 422, ok: false, body: {...refusal, retryable: false}},
  {status: 422, ok: false, body: {...refusal, retryable: 'true'}},
  {status: 422, ok: false, body: {...refusal, retryable: undefined}},
  {status: 422, ok: false, body: {...refusal, code: 'source-denied'}},
  {status: 422, ok: false, body: {...refusal, code: undefined}},
  {status: 200, ok: true, body: refusal},
]) assert.throws(() => storedRootPlan(receipt), /^Error: Stored root export refused: HTTP (?:200|403|422|500); code=(?:policy-refresh-required|unclassified)$/);
assert.throws(() => storedRootPlan({status: 422, ok: false,
  body: {ok: false, code: 'private-code', reason: 'private-source'}}),
new Error('Stored root export refused: HTTP 422; code=unclassified'));
console.log('PASS stored root policy envelope: exact fresh-read retry; all other denials remain terminal');

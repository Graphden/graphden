'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {componentsForRequest, decode} = require('../browser-test/component-plan-replies');
const request = {components: ['canvas', 'canvas-values']};
const item = (component, status, body) => ({component, result: {status, headers: {}, body: JSON.stringify(body)}});

test('single and global refusals retain actual HTTP status and body for every requested component', () => {
  const body = {ok: false, code: 'policy-refresh-required', retryable: true};
  assert.deepEqual(decode({component: 'canvas'}, 422, body), [{component: 'canvas', status: 422, body}]);
  assert.deepEqual(decode(request, 503, body), request.components.map(component => ({component, status: 503, body})));
});

test('batch replies preserve each component body/status and request order', () => {
  const success = {ok: true, 'selection-id': 'exact-selection', plan: {private: true}};
  const fallback = {ok: false, code: 'component-missing', fallback: 'builtin'};
  assert.deepEqual(decode(request, 200, {ok: true, items: [item('canvas-values', 422, fallback), item('canvas', 200, success)]}),
    [{component: 'canvas', status: 200, body: success}, {component: 'canvas-values', status: 422, body: fallback}]);
});

for (const [name, items] of [
  ['duplicate', [item('canvas', 200, {ok: true}), item('canvas', 200, {ok: true})]],
  ['missing', [item('canvas', 200, {ok: true})]],
  ['unknown', [item('canvas', 200, {ok: true}), item('inspector', 200, {ok: true})]],
  ['invalid status', [item('canvas', 200, {ok: true}), item('canvas-values', '200', {ok: true})]],
  ['malformed body', [item('canvas', 200, {ok: true}), {component: 'canvas-values', result: {status: 200, body: '{'}}]],
  ['unencoded body', [item('canvas', 200, {ok: true}), {component: 'canvas-values', result: {status: 200, body: {ok: true}}}]],
]) test('decoder strictly rejects ' + name + ' batch items', () => {
  assert.throws(() => decode(request, 200, {ok: true, items}), /Invalid observed component batch/);
});

test('a successful envelope cannot certify absent or refused item results', () => {
  for (const body of [null, {ok: true}, {ok: false, items: []}])
    assert.throws(() => decode(request, 200, body), /Invalid observed component batch/);
});

test('invalid request components are rejected before collecting replies', () => {
  for (const invalid of [{}, {components: []}, {components: ['canvas', 'canvas']},
    {components: ['canvas'], component: 'canvas'}, {components: [null]},
    {components: Array.from({length: 9}, (_, i) => 'component-' + i)}])
    assert.throws(() => componentsForRequest(invalid), /Invalid observed component plan request/);
});

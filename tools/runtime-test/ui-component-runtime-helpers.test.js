'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {setTimeout: pause} = require('node:timers/promises');
const {drainUIPlanReplies} = require('../browser-test/ui-component-runtime-test-helpers');

test('a parsed policy refusal stays pending until its actual retry reaches a terminal reply', async () => {
  const requests = [{sequence: 1, component: 'canvas-metadata', status: 422}];
  const replies = [{kind: 'plan', sequence: 1, component: 'canvas-metadata', status: 422,
    code: 'policy-refresh-required', retryable: true}];
  let completed = false;
  const drained = drainUIPlanReplies(requests, replies, []).then(() => { completed = true; });
  await pause(30);
  assert.equal(completed, false);
  requests.push({sequence: 2, component: 'canvas-metadata', status: 200});
  replies.push({kind: 'plan', sequence: 2, component: 'canvas-metadata', status: 200, ok: true});
  await drained;
});

test('another parsed response cannot stand in for an outstanding request sequence', async () => {
  const requests = [{sequence: 1, component: 'canvas-inheritance', status: 'pending'}];
  const replies = [{kind: 'plan', sequence: 2, component: 'canvas-metadata', status: 200, ok: true}];
  let completed = false;
  const drained = drainUIPlanReplies(requests, replies, []).then(() => { completed = true; });
  await pause(30);
  assert.equal(completed, false);
  requests[0].status = 200;
  replies.push({kind: 'plan', sequence: 1, component: 'canvas-inheritance', status: 200, ok: true});
  await drained;
});

test('only the existing classified missing-component fallback is a terminal refusal', async () => {
  const requests = [{sequence: 1, component: 'canvas-metadata', status: 422}];
  const reply = {kind: 'plan', sequence: 1, component: 'canvas-metadata', status: 422,
    code: 'component-missing', fallback: 'builtin'};
  await drainUIPlanReplies(requests, [reply], []);
  await assert.rejects(drainUIPlanReplies(requests, [{...reply, code: 'unknown'}], []), /known terminal receipt/);
  await assert.rejects(drainUIPlanReplies(requests, [{...reply, code: 'policy-refresh-required', retryable: false}], []),
    /known terminal receipt/);
});

test('failed requests never count as drained responses', async () => {
  await assert.rejects(drainUIPlanReplies([{sequence: 1, component: 'canvas-metadata', status: 'failed'}], [], []),
    /request failed/);
});

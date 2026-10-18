'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {setTimeout: pause} = require('node:timers/promises');
const vm = require('node:vm');
const {drainUIPlanReplies, observeUIPlanLoads} = require('../browser-test/ui-component-runtime-test-helpers');

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

test('a different reload of the same configuration cannot complete an observed policy retry', async () => {
  const requests = [{sequence: 1, component: 'canvas-bindings', loadId: 1, status: 422}];
  const replies = [{kind: 'plan', sequence: 1, component: 'canvas-bindings', loadId: 1,
    status: 422, code: 'policy-refresh-required', retryable: true}];
  let completed = false;
  const drained = drainUIPlanReplies(requests, replies, [], true).then(() => { completed = true; });
  requests.push({sequence: 2, component: 'canvas-bindings', loadId: 2, status: 200});
  replies.push({kind: 'plan', sequence: 2, component: 'canvas-bindings', loadId: 2, status: 200, ok: true});
  await pause(30);
  assert.equal(completed, false);
  requests.push({sequence: 3, component: 'canvas-bindings', loadId: 1, status: 200});
  replies.push({kind: 'plan', sequence: 3, component: 'canvas-bindings', loadId: 1, status: 200, ok: true});
  await drained;
});

test('an unknown historical refusal remains fatal even after a successful reply', async () => {
  const requests = [{sequence: 1, component: 'canvas-bindings', loadId: 1, status: 422},
    {sequence: 2, component: 'canvas-bindings', loadId: 1, status: 200}];
  const replies = requests.map(request => ({...request, kind: 'plan', ok: request.status === 200}));
  await assert.rejects(drainUIPlanReplies(requests, replies, [], true), /known terminal receipt/);
});

test('the test observer retains native responses and correlates only finite metadata by actual signal', async () => {
  const originals = [], signals = [];
  const context = vm.createContext({URL, location: {href: 'http://localhost/'}, window: {
    fetch: async (_url, options) => {
      signals.push(options.signal);
      const response = new Response(JSON.stringify({ok: true, plan: {private: 'not recorded'}}));
      originals.push(response); return response;
    },
  }});
  const page = {
    async exposeFunction(name, callback) { context.window[name] = (...args) => Promise.resolve(callback(...args)); },
    async addInitScript(callback, names) {
      context.names = names;
      vm.runInContext('(' + callback.toString() + ')(names)', context);
    },
    async evaluate(callback) { return vm.runInContext('(' + callback.toString() + ')()', context); },
  };
  const observed = await observeUIPlanLoads(page);
  const first = new AbortController(), second = new AbortController();
  const invoke = signal => context.window.fetch('/api/ui/configuration/plan',
    {method: 'POST', signal, body: JSON.stringify({component: 'canvas-bindings'})});
  assert.equal(await invoke(first.signal), originals[0]);
  await invoke(first.signal);
  await invoke(second.signal);
  await observed.flush();
  await drainUIPlanReplies(observed.requests, observed.replies, [], true);
  assert.equal(signals[0], first.signal);
  assert.equal(observed.requests[0].loadId, observed.requests[1].loadId);
  assert.notEqual(observed.requests[0].loadId, observed.requests[2].loadId);
  assert.deepEqual(Object.keys(observed.replies[0]).sort(),
    ['code', 'component', 'fallback', 'kind', 'loadId', 'ok', 'retryable', 'sequence', 'status', 'url']);
  assert.equal(JSON.stringify(observed).includes('not recorded'), false);
});

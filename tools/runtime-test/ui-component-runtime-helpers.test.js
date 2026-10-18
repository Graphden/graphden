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
  const context = vm.createContext({URL, crypto: {randomUUID: () => 'document-one'}, location: {href: 'http://localhost/'}, window: {
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
    ['code', 'component', 'documentId', 'fallback', 'kind', 'loadId', 'ok', 'retryable', 'sequence', 'status', 'url']);
  assert.equal(JSON.stringify(observed).includes('not recorded'), false);
});


test('an unknown reply parsed during the final pending drain remains fatal', async () => {
  const replies = [];
  let finish;
  const pending = [new Promise(resolve => { finish = resolve; }).then(() => replies.push({
    sequence: 1, component: 'canvas-bindings', status: 422, code: 'unknown',
  }))];
  const drained = drainUIPlanReplies([], replies, pending);
  finish();
  await assert.rejects(drained, /known terminal receipt/);
});


function supersededFixture() {
  const requests = [
    {sequence: 1, component: 'canvas-bindings', documentId: 'document-one', loadId: 1,
      status: 'failed', abortError: true, signalAborted: true},
    {sequence: 2, component: 'canvas-bindings', documentId: 'document-one', loadId: 2, status: 200},
  ];
  const replies = [{...requests[1], kind: 'plan', ok: true}];
  return {requests, replies};
}

test('explicitly aborted loads terminate as superseded only after a newer accepted load', async () => {
  const {requests, replies} = supersededFixture();
  let accepted;
  const terminal = await drainUIPlanReplies(requests, replies, [], true, async names => { accepted = names; });
  assert.deepEqual(accepted, ['canvas-bindings']);
  assert.deepEqual(terminal.superseded, [{component: 'canvas-bindings', documentId: 'document-one', loadId: 1, replacementLoadId: 2}]);
  await assert.rejects(drainUIPlanReplies(requests, replies, [], true), /request failed/);
  await assert.rejects(drainUIPlanReplies(requests, replies, [], true, async () => { throw Error('runtime not accepted'); }),
    /runtime not accepted/);
});

for (const [name, change] of [
  ['a network error despite a later signal abort', ({requests}) => { requests[0].abortError = false; }],
  ['an AbortError without a captured signal abort', ({requests}) => { requests[0].signalAborted = false; }],
  ['a missing load identity', ({requests}) => { requests[0].loadId = null; }],
  ['a missing document identity', ({requests}) => { delete requests[0].documentId; }],
  ['another document', ({requests, replies}) => { requests[1].documentId = replies[0].documentId = 'document-two'; }],
  ['another component', ({requests, replies}) => { requests[1].component = replies[0].component = 'canvas-metadata'; }],
  ['the same load', ({requests, replies}) => { requests[1].loadId = replies[0].loadId = 1; }],
  ['no replacement', ({requests, replies}) => { requests.pop(); replies.pop(); }],
  ['an aborted latest load', ({requests, replies}) => { requests[1].status = 'failed'; requests[1].abortError = requests[1].signalAborted = true; replies.pop(); }],
  ['a builtin fallback instead of successful replacement', ({replies}) => { Object.assign(replies[0], {status: 422, ok: false, code: 'component-missing', fallback: 'builtin'}); }],
]) test('supersession rejects ' + name, async () => {
  const fixture = supersededFixture(); change(fixture);
  await assert.rejects(drainUIPlanReplies(fixture.requests, fixture.replies, [], true, async () => {}), /request failed/);
});

test('unknown historical HTTP refusals remain fatal even when their load was explicitly aborted', async () => {
  const {requests, replies} = supersededFixture();
  replies.unshift({...requests[0], kind: 'plan', status: 422, code: 'unknown'});
  await assert.rejects(drainUIPlanReplies(requests, replies, [], true, async () => {}), /known terminal receipt/);
});

test('a pending newer load blocks supersession until its response and current runtime acceptance', async () => {
  const {requests, replies} = supersededFixture();
  const reply = replies.pop(); requests[1].status = 'pending';
  let completed = false, accepted = false;
  const draining = drainUIPlanReplies(requests, replies, [], true, async () => { accepted = true; })
    .then(() => { completed = true; });
  await pause(30);
  assert.equal(completed, false); assert.equal(accepted, false);
  replies.push(reply); requests[1].status = 200;
  await draining;
  assert.equal(accepted, true);
});

test('the observer freezes cancellation facts at rejection and never records exception payloads', async () => {
  let reject;
  const context = vm.createContext({URL, crypto: {randomUUID: () => 'document-one'}, location: {href: 'http://localhost/'},
    window: {fetch: () => new Promise((_resolve, fail) => { reject = fail; })}});
  const page = {
    async exposeFunction(name, callback) { context.window[name] = (...args) => Promise.resolve(callback(...args)); },
    async addInitScript(callback, names) { context.names = names; vm.runInContext('(' + callback.toString() + ')(names)', context); },
    async evaluate(callback) { return vm.runInContext('(' + callback.toString() + ')()', context); },
  };
  const observed = await observeUIPlanLoads(page);
  const controller = new AbortController();
  const nativePromise = context.window.fetch('/api/ui/configuration/plan',
    {method: 'POST', signal: controller.signal, body: JSON.stringify({component: 'canvas-bindings'})});
  const originalError = Object.assign(new Error('private-error-payload'), {name: 'TypeError'});
  reject(originalError);
  await assert.rejects(nativePromise, error => error === originalError);
  await observed.flush();
  controller.abort();
  assert.equal(observed.requests[0].abortError, false);
  assert.equal(observed.requests[0].signalAborted, false);
  assert.equal(JSON.stringify(observed).includes('private-error-payload'), false);
  const second = new AbortController();
  const aborted = context.window.fetch('/api/ui/configuration/plan',
    {method: 'POST', signal: second.signal, body: JSON.stringify({component: 'canvas-bindings'})});
  second.abort();
  reject(Object.assign(new Error('private-abort-payload'), {name: 'AbortError'}));
  await assert.rejects(aborted, error => error.name === 'AbortError');
  await observed.flush();
  assert.equal(observed.requests[1].abortError, true);
  assert.equal(observed.requests[1].signalAborted, true);
  assert.equal(JSON.stringify(observed).includes('private-abort-payload'), false);
});

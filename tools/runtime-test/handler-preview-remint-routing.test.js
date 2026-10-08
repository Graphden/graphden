'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {remintWithPendingCheck} = require('../browser-test/tutorial-handler-preview-helpers');

function fixture() {
  const events = [];
  let handler;
  let reached;
  let finish;
  let minted;
  let rejectMint;
  let ready = false;
  const requestReached = new Promise(resolve => { reached = resolve; });
  const forwarding = new Promise(resolve => { finish = resolve; });
  const response = new Promise((resolve, reject) => { minted = resolve; rejectMint = reject; });
  const page = {
    route: async (_pattern, callback) => { handler = callback; },
    unroute: async (_pattern, callback) => {
      assert.equal(callback, handler);
      events.push('unroute');
    },
    waitForRequest: () => requestReached,
    waitForResponse: () => response,
    waitForSelector: async () => {},
    evaluate: async () => 'tutorial-owned',
    locator: () => ({
      click: async () => {
        void handler({continue: async () => {
          events.push('forward-start');
          const failure = await forwarding;
          events.push('forward-end');
          if (failure) { rejectMint(failure); throw failure; }
          ready = true;
          minted({ok: () => true, json: async () => ({ok: true, mode: 'handler', 'expires-in-ms': 120000}),
            request: () => ({headerValue: async () => 'tutorial-owned'})});
        }});
        reached();
      },
      getAttribute: async () => ready ? 'https://synthetic.invalid/__preview/handler/new/' : null,
      isVisible: async () => ready,
      isDisabled: async () => { events.push('pending-checked'); return true; },
    }),
  };
  return {page, events, finish};
}

test('pending remint forwards completely before its route is unregistered', async () => {
  const f = fixture();
  const running = remintWithPendingCheck(f.page, 'https://synthetic.invalid/__preview/handler/old/');
  await new Promise(setImmediate);
  assert.deepEqual(f.events, ['pending-checked', 'forward-start'],
    'releasing the paused request must not unregister an unfinished route');
  f.finish();
  await running;
  assert.deepEqual(f.events, ['pending-checked', 'forward-start', 'forward-end', 'unroute']);
});

test('forwarding and pending mint rejection remain observed and reach the caller', async () => {
  const f = fixture();
  const failure = new Error('synthetic forwarding failure');
  const running = remintWithPendingCheck(f.page, 'https://synthetic.invalid/__preview/handler/old/');
  const rejected = assert.rejects(running, error => {
    assert(error instanceof AggregateError);
    assert.deepEqual(error.errors, [failure, failure]);
    return true;
  });
  await new Promise(setImmediate);
  f.finish(failure);
  await rejected;
  assert.deepEqual(f.events, ['pending-checked', 'forward-start', 'forward-end', 'unroute']);
});
